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
    /**
     * The disposable online-reading cache.
     *
     * Held only so a completed *shelf* download can drop the cache's copy of the
     * same book: once the book lives on the shelf, the cache's version is a
     * second full copy of a file that can be 75 MB, kept for a speed-up nobody
     * needs any more. Optional so the library still works without one.
     */
    private val cache: OnlineCache? = null,
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

    /**
     * The shelf's own record for each downloaded book, keyed by id.
     *
     * The listing's entries and the shelf's records describe the same book but
     * are not the same object: only the shelf's has `localFile` set. Opening a
     * book from 主页 has to hand the reader the *shelf's* record when there is
     * one, or the reader would go to the network for a file that is already on
     * the device -- and then write a second copy of it into the online cache.
     * That is the "already downloaded, but tapping it downloads it again" bug.
     */
    var downloaded: Map<String, Book> by mutableStateOf(emptyMap())
        private set

    /**
     * Ids the online cache can serve without a network round trip.
     *
     * A third thing again, next to [downloadedIds] and [downloads]: the book is
     * neither on the shelf nor being fetched, it just happens to be sitting in
     * `cacheDir` from an earlier read. The user cannot see that directory and
     * Android can delete it at any moment, so the only honest way to use it is to
     * tell them when it is there.
     */
    var cachedIds: Set<String> by mutableStateOf(emptySet())
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

        // A fresh listing is what makes the cache question answerable at all --
        // the sizes it carries are half of `isUsable` -- so the scan belongs here
        // rather than only at the call site, which cannot know when the load
        // actually finished.
        refreshCached()
    }

    /** Re-read which books have a local copy. */
    fun refreshDownloaded() {
        scope.launch {
            val shelf = store.load()
            downloaded = shelf.associateBy { it.id }
            downloadedIds = shelf.mapTo(HashSet()) { it.id }
        }
    }

    /**
     * Re-read which books the online cache can serve as they are.
     *
     * Only meaningful once a listing is in hand -- what is cached is decided by
     * comparing against each book's size -- so a call before the first load finishes
     * is a no-op rather than an empty answer that would flash the whole shelf as
     * uncached.
     */
    fun refreshCached() {
        val current = books
        if (current.isEmpty()) return
        scope.launch { cachedIds = cache?.cachedIds(current) ?: emptySet() }
    }

    /**
     * Forget every cached mark without consulting the disk.
     *
     * For the one caller that has just deleted everything: 设置's 清理在线阅读缓存
     * empties the cache and then has to say so. Going through [refreshCached] would
     * work only while a listing happens to be loaded -- it returns early on an empty
     * `books`, which is exactly the state after the user clears the cache before ever
     * opening 主页 -- and the stale marks would then be re-shown from the previous
     * scan the next time the list composed.
     *
     * An empty set is the honest answer: the directory is gone.
     */
    fun forgetCached() {
        cachedIds = emptySet()
    }

    /**
     * The record to hand the reader for [book].
     *
     * The shelf's copy when the book has been downloaded, because that one points
     * at the file on the device; the listing's entry otherwise. Without this,
     * opening a downloaded book from 主页 went to the network for a file that was
     * already there -- and, having gone to the network, wrote a second copy of it
     * into the online-reading cache as well.
     *
     * The shelf record is preferred even when its file has since been removed
     * (`localFile` set but the file gone): [BookLoader] then reports that plainly
     * and falls back to the network, which is a better answer than silently
     * fetching a fresh copy the user did not ask for.
     */
    fun openable(book: Book): Book = downloaded[book.id] ?: book

    // --- downloading -------------------------------------------------------

    /**
     * Download [book] to the shelf.
     *
     * Runs on the application scope, so leaving the home screen -- to watch it
     * land on the shelf, which is the obvious thing to do -- no longer cancels it.
     * Progress is published here, so the row shows the same state from wherever
     * it is observed.
     *
     * ## A cached book is moved, not fetched again
     *
     * Reading a book online leaves a complete copy in [OnlineCache]. Pressing 下载
     * on that same book used to ignore it and pull the whole file down a second
     * time -- up to 75 MB, over the same barely-reachable hosts the cache exists to
     * avoid. The bytes are already on the device and already verified against the
     * repository's reported size (that is what makes them a cache *hit*), so the
     * honest action is to move that file onto the shelf.
     *
     * "Move" is a rename inside the app's own storage, which is why this is fast and
     * why it must not fall back to a copy: the cache entry is deleted afterwards
     * either way, so a copy would briefly double the disk usage for no benefit.
     *
     * @param onDone called with a message for the user, on success or failure.
     */
    fun download(book: Book, onDone: (String) -> Unit) {
        if (downloads.containsKey(book.id)) return
        downloads[book.id] = Download(0f, "正在连接线路…", indeterminate = true)

        scope.launch {
            try {
                val promoted = adoptCachedCopy(book)
                if (promoted != null) {
                    downloads.remove(book.id)
                    refreshDownloaded()
                    refreshCached()
                    onDone("已加入书架：${book.title}")
                    return@launch
                }

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
                // The shelf copy is now the one that will be read, so the cache's
                // copy of the same book is a duplicate of a file that can be
                // 75 MB. Dropping it here is what keeps "downloaded" from meaning
                // "stored twice".
                cache?.remove(book)
                refreshDownloaded()
                refreshCached()
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

    /**
     * Move a complete cache copy of [book] onto the shelf, if there is one.
     *
     * Returns the shelf record on success, or null when there is nothing usable in
     * the cache -- in which case the caller downloads as usual.
     *
     * ## Why this is checked here and not in the UI
     *
     * `cachedIds` already tells the row that the book is cached, but that set is a
     * *snapshot*: the cache is in `cacheDir` and Android may evict it, the user may
     * clear it from 设置, and an earlier failed transfer may have left a file whose
     * size no longer matches the repository's. The decision to skip the network
     * therefore has to be made against the file that exists at this moment, which is
     * what [OnlineCache.take] does -- it re-checks `isUsable` and removes residue.
     *
     * A failure here is not an error: the file may have been evicted between the
     * check and the rename, and the honest fallback is simply to download it.
     */
    private suspend fun adoptCachedCopy(book: Book): Book? {
        val c = cache ?: return null
        return try {
            // Reuse the book's own shelf target so the file lands exactly where a
            // downloaded copy would, then register it as one.
            val target = store.targetFileFor(book)
            val taken = c.take(book, target) ?: return null
            val record = store.register(book, taken, taken.length())
            // `register` bumps the shelf revision, so the shelf screen picks the
            // new book up without a manual refresh.
            record
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
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
