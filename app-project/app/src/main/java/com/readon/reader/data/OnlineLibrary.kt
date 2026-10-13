package com.readon.reader.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.readon.reader.net.GithubClient
import com.readon.reader.net.LibraryException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The online library, held for the life of the process.
 *
 * ## Why this is not screen state
 *
 * Every one of these was a bug caused by keeping the library inside `HomeScreen`:
 *
 *  * **Re-fetching on every tab switch.** The listing was loaded by a
 *    `LaunchedEffect` inside the home screen. Compose disposes that screen when
 *    the user taps 书架 or 设置, so coming back re-ran the whole nine-host race --
 *    a full round trip, every time, for a list that had not changed.
 *  * **Downloads that died on navigation.** The download coroutine ran on the
 *    screen's `rememberCoroutineScope()`, which is cancelled the moment the
 *    screen leaves the composition. Starting a download and going to the shelf to
 *    watch it was enough to kill it, which is what "无法从书库下载" looked like.
 *  * **A refresh that threw away the screen.** The listing, the error and the
 *    in-flight download progress all reset whenever the tab changed.
 *
 * So the state and the coroutines live at application scope instead. The screen
 * renders this object and asks it to load; it does not own the work.
 */
class OnlineLibrary(
    private val prefs: Prefs,
    private val client: GithubClient,
    private val store: BookStore,
    private val scope: CoroutineScope,
) {

    /** One book being downloaded, and how far along it is. */
    data class Download(
        val progress: Float,
        val stage: String,
        /** True when no total size is known, so the UI shows an indeterminate bar. */
        val indeterminate: Boolean = false,
    )

    // --- listing -----------------------------------------------------------

    var books: List<Book> by mutableStateOf(emptyList())
        private set

    var loading: Boolean by mutableStateOf(false)
        private set

    var error: String? by mutableStateOf(null)
        private set

    var truncated: Boolean by mutableStateOf(false)
        private set

    /** Which route answered, shown so a blocked network is diagnosable. */
    var via: String? by mutableStateOf(null)
        private set

    /** True once a listing has been obtained for the current configuration. */
    var loaded: Boolean by mutableStateOf(false)
        private set

    // --- local copies ------------------------------------------------------

    /** Ids that already have a file on the device. */
    var downloadedIds: Set<String> by mutableStateOf(emptySet())
        private set

    /** Books being downloaded right now, keyed by id. */
    val downloads = mutableStateMapOf<String, Download>()

    /**
     * What the current listing was fetched for.
     *
     * Changing the repository, the branch, the token or a pinned route must
     * invalidate the cache; nothing else should. This is a fingerprint rather
     * than a flag so that editing the token in Settings actually re-lists, while
     * changing the font size does not.
     */
    private var loadedFingerprint: String? = null

    private val loadLock = Mutex()

    /** The configuration the listing depends on. */
    private fun fingerprint(): String = listOf(
        prefs.owner, prefs.repo, prefs.branch,
        prefs.token.hashCode().toString(),
        prefs.pinnedMirror, prefs.pinnedListSource, prefs.mirrorPrefix,
    ).joinToString("|")

    /** True when the cached listing is still valid for the current settings. */
    private fun isFresh(): Boolean = loaded && loadedFingerprint == fingerprint()

    /**
     * Load the listing if it is missing or stale.
     *
     * @param force re-fetch even when a valid cache exists; this is the refresh
     *   button, not a screen appearing.
     */
    fun ensureLoaded(force: Boolean = false) {
        if (!force && isFresh()) return
        if (loading) {
            // A refresh requested while a load is already running must not be
            // silently dropped -- the user pressed the button. It is remembered
            // and run once the current load finishes, so a tap always results in
            // a fresh listing.
            if (force) pendingForce = true
            return
        }
        scope.launch { load() }
    }

    /** Set when a forced refresh arrives while a load is in flight. */
    private var pendingForce = false

    private suspend fun load() {
        // One load at a time: a double tap on 刷新, or the screen appearing while
        // a previous load is still running, must not start a second race.
        loadLock.withLock {
            loading = true
            error = null
            try {
                val listing = client.listBooks(prefs.source)
                books = listing.books
                truncated = listing.truncated
                via = listing.via
                // Record the fingerprint of the settings this listing was fetched
                // for, read *after* the request so a change made mid-flight is not
                // mistaken for one that is already reflected.
                loadedFingerprint = fingerprint()
                loaded = true
                if (listing.books.isEmpty()) {
                    error = "仓库里没有找到可阅读的文件"
                }
            } catch (e: LibraryException) {
                error = e.message
                // Keep whatever was already on screen: a refresh that fails should
                // not empty a library the user was browsing.
                if (!loaded) books = emptyList()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message ?: "加载失败"
                if (!loaded) books = emptyList()
            } finally {
                loading = false
            }
        }

        if (pendingForce) {
            pendingForce = false
            ensureLoaded(force = true)
        }
    }

    /** Re-read which books have a local copy. */
    fun refreshDownloaded() {
        scope.launch { downloadedIds = store.downloadedIds() }
    }

    // --- downloading -------------------------------------------------------

    /**
     * Download [book] to the shelf.
     *
     * Runs on the application scope, so leaving the home screen -- to watch it
     * land on the shelf, which is the obvious thing to do -- no longer cancels it.
     * Progress is published here, so the row shows the same state from wherever
     * it is observed.
     *
     * @param onDone called with a message for the user, on success or failure.
     */
    fun download(book: Book, onDone: (String) -> Unit) {
        if (downloads.containsKey(book.id)) return
        downloads[book.id] = Download(0f, "正在连接线路…", indeterminate = true)

        scope.launch {
            try {
                val target = store.targetFileFor(book)
                // Progress callbacks arrive on the IO thread that is copying
                // bytes, and the download map is Compose snapshot state that the
                // home screen reads. The writes are therefore marshalled back onto
                // the main dispatcher, and throttled to one per whole percent so a
                // 75 MB book does not queue six hundred updates.
                var lastPercent = -1
                client.downloadTo(
                    source = book.source,
                    path = book.path,
                    target = target,
                    expectedSize = book.size,
                    onProgress = { p ->
                        val percent = (p * 100).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            publish(book.id) {
                                Download(
                                    progress = p,
                                    stage = "正在下载…",
                                    indeterminate = false,
                                )
                            }
                        }
                    },
                    onStage = { stage ->
                        // Only the stage changes here; a bare stage update must not
                        // wipe a percentage that is already showing.
                        publish(book.id) { current ->
                            Download(
                                progress = current.progress,
                                stage = stage,
                                indeterminate = current.indeterminate,
                            )
                        }
                    },
                )
                store.register(book, target, target.length())
                downloadedIds = store.downloadedIds()
                onDone("已下载：${book.title}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The process is going away; leave the partial file for cleanup.
                throw e
            } catch (e: Throwable) {
                // A failed transfer must not leave a `.part` behind pretending to
                // be a book, nor a target file the shelf would show as complete.
                cleanupPartial(book)
                onDone(e.message ?: "下载失败")
            } finally {
                downloads.remove(book.id)
            }
        }
    }

    /** Remove the remains of an interrupted download. */
    private fun cleanupPartial(book: Book) {
        runCatching {
            val target = store.targetFileFor(book)
            File(target.parentFile, target.name + ".part").delete()
        }
    }

    /**
     * Update a download's state on the main dispatcher.
     *
     * The `downloads` map is Compose snapshot state that the home screen reads,
     * while the callbacks that drive it run on the IO thread copying bytes.
     * Writing across that boundary is not safe, so the write is posted to the
     * scope, which uses `Dispatchers.Main.immediate`.
     *
     * A callback that arrives after the transfer has already finished is dropped.
     * The final progress tick and the `finally` that removes the entry both run on
     * the main dispatcher, and without this check a late tick could re-add the
     * entry and leave the row spinning on "downloading" forever.
     */
    private fun publish(id: String, next: (Download) -> Download) {
        scope.launch {
            val current = downloads[id] ?: return@launch
            downloads[id] = next(current)
        }
    }

    fun isDownloading(id: String): Boolean = downloads.containsKey(id)
}
