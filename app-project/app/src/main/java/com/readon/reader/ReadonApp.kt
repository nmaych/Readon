package com.readon.reader

import android.app.Application
import android.content.Context
import com.readon.reader.data.BookStore
import com.readon.reader.data.BookmarkStore
import com.readon.reader.data.OnlineCache
import com.readon.reader.data.OnlineFetcher
import com.readon.reader.data.OnlineLibrary
import com.readon.reader.data.Prefs
import com.readon.reader.net.GithubClient
import com.readon.reader.update.UpdateCenter
import com.readon.reader.update.UpdateManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-wide singletons.
 *
 * A hand-rolled container rather than a DI framework: the object graph is five
 * objects deep and entirely without cycles, so a framework would only add a
 * build step and an annotation processor to the offline dependency budget.
 */
class ReadonApp : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var store: BookStore
        private set
    lateinit var bookmarks: BookmarkStore
        private set
    lateinit var updates: UpdateManager
        private set

    /**
     * Books read online, kept so a second read does not download them again.
     *
     * Deliberately separate from [store]: the shelf is what the user chose to
     * keep, this is disposable, and the two have different sizes and different
     * clear buttons. See [OnlineCache].
     */
    lateinit var onlineCache: OnlineCache
        private set

    /**
     * How a book read online is fetched: a readable prefix first, the rest behind
     * the reader's back.
     *
     * At application scope rather than in the reader, because finishing a
     * download must outlive the screen that started it -- a reader who opens a
     * book, reads two pages and goes back to the shelf has not asked for the
     * download to stop, and the next open being instant is the whole point.
     * See [OnlineFetcher].
     */
    lateinit var onlineFetcher: OnlineFetcher
        private set

    /**
     * Work that must outlive a screen.
     *
     * Library refreshes and downloads run here rather than on a composable's
     * scope. A `rememberCoroutineScope()` is cancelled when its screen leaves the
     * composition, so a download started on the home screen died the moment the
     * user switched to the shelf to watch it arrive.
     *
     * `SupervisorJob` so one failed download cannot cancel the refresh that is
     * running beside it; the app lives as long as the process, so there is
     * nothing to cancel the scope itself.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The online library listing and its downloads, shared across screens. */
    lateinit var library: OnlineLibrary
        private set

    /** The update check's state, shared across screens. */
    lateinit var updateCenter: UpdateCenter
        private set

    /**
     * Bumped whenever something outside a screen invalidates what a screen is
     * showing.
     *
     * The concrete case is the online cache: 设置 can empty it while 主页 still holds
     * the 已缓存 marks it derived before. 主页 only re-derives them on its own
     * triggers (a listing load, a finished download, the reader closing), and
     * clearing the cache is none of those -- it happens on a different screen with
     * no call into [OnlineLibrary] at all. So the cache reports it here and the home
     * screen watches it.
     *
     * Distinct from [Prefs.revision], which means "a setting changed": the home
     * screen restarts its library load on that one, and a cache clear must not
     * cost a network round trip.
     */
    var cacheRevision: Int by mutableStateOf(0)
        private set

    /** Announce that the online cache changed and screen-held marks are stale. */
    fun invalidateCache() {
        cacheRevision++
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        store = BookStore(this)
        bookmarks = BookmarkStore(this)
        onlineCache = OnlineCache(this)
        onlineFetcher = OnlineFetcher(
            client = github,
            cache = onlineCache,
            scratchDir = java.io.File(cacheDir, "readon-online"),
            scope = appScope,
        )
        updates = UpdateManager(this, prefs, github)
        library = OnlineLibrary(prefs, github, store, appScope, onlineCache)
        updateCenter = UpdateCenter(prefs, updates, appScope)

        // A downloaded update APK is dead weight once the version it installed is
        // running, and nothing else ever removes it: the next download clears the
        // directory first, so a user who updates once and then stops keeps that
        // 11 MB forever. Startup is the first moment this code can know the install
        // succeeded -- the installer replaces the app, so the old version never gets
        // to run again after the user taps 安装. Off the main thread because it is
        // filesystem work.
        appScope.launch(Dispatchers.IO) { updates.clearDownloadedPackages() }
    }

    /**
     * The GitHub client.
     *
     * One instance for the process: it reads the token, the pinned route and the
     * remembered host out of [prefs] on every request, so there is no
     * configuration baked into it that could go stale when the user edits
     * Settings.
     */
    val github: GithubClient by lazy { GithubClient(prefs) }

    /**
     * Run [block] on the application scope.
     *
     * For work that must not be cancelled by navigation: importing files from the
     * device, and anything else the user would expect to finish after they have
     * moved on.
     */
    fun launch(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        appScope.launch(block = block)
    }

    companion object {
        lateinit var instance: ReadonApp
            private set

        fun of(context: Context): ReadonApp =
            context.applicationContext as ReadonApp
    }
}
