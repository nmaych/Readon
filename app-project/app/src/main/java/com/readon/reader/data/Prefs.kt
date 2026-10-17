package com.readon.reader.data

import android.content.Context
import android.content.SharedPreferences
import com.readon.reader.reader.AutoRead
import com.readon.reader.ui.theme.ReaderFont
import com.readon.reader.ui.theme.ReaderTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the reader moves through a book.
 *
 * `Paged` is the original left/right page turn; `Scroll` is one continuous
 * column the user drags up and down.
 */
enum class ReadingMode(val label: String) {
    Paged("翻页"),
    Scroll("上下滚动"),
    ;

    companion object {
        fun fromName(name: String?): ReadingMode =
            entries.firstOrNull { it.name == name } ?: Paged
    }
}

/**
 * App preferences.
 *
 * Plain [SharedPreferences] rather than DataStore on purpose: this project's
 * dependency set has to stay inside what is already in the offline Gradle cache,
 * and every value here is written by a user tapping a control.
 *
 * SharedPreferences has no change notification for a single-process app, so
 * [revision] is bumped by every write. Composables observe that one counter and
 * re-read whichever fields they care about, which keeps the reading screens in
 * step with the settings screen without wiring a flow per field.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("readon", Context.MODE_PRIVATE)

    private val _revision = MutableStateFlow(0)

    /** Increments on every write; observe it to react to any setting change. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        sp.edit().apply(block).apply()
        _revision.value = _revision.value + 1
    }

    /**
     * Write without bumping [revision].
     *
     * For state that is *bookkeeping about the UI* rather than a setting: where
     * the user had scrolled, what they had typed. Those must not look like a
     * configuration change, because every screen treats [revision] as "the
     * settings moved, re-read everything" -- 主页 in particular restarts its
     * library load on it. Saving the scroll position through [edit] made the home
     * screen reload the whole listing every time the user left the tab, which is
     * both a wasted network round trip and exactly the kind of thing that makes a
     * "remembered" screen feel slower than a fresh one.
     */
    private fun editQuiet(block: SharedPreferences.Editor.() -> Unit) {
        sp.edit().apply(block).apply()
    }

    // --- library source ----------------------------------------------------
    var owner: String
        get() = sp.getString(K_OWNER, null) ?: DEFAULT_OWNER
        set(v) = edit { putString(K_OWNER, v.trim()) }

    var repo: String
        get() = sp.getString(K_REPO, null) ?: DEFAULT_REPO
        set(v) = edit { putString(K_REPO, v.trim()) }

    var branch: String
        get() = sp.getString(K_BRANCH, null) ?: DEFAULT_BRANCH
        set(v) = edit { putString(K_BRANCH, v.trim()) }

    /**
     * Optional personal access token. Unauthenticated GitHub API calls are
     * capped at 60/hour per IP, which a couple of library refreshes will spend;
     * a token lifts that to 5000/hour.
     */
    var token: String
        get() = sp.getString(K_TOKEN, "").orEmpty()
        set(v) = edit { putString(K_TOKEN, v.trim()) }

    val source: LibrarySource
        get() = LibrarySource(owner, repo, branch)

    // --- network route -----------------------------------------------------
    /**
     * The host that last worked, remembered per request kind.
     *
     * These are caches, not settings: the user never picks them, the client writes
     * them after a successful race and reads them to put the known-good host
     * first. Storing them is what turns "try nine hosts every time" into "try the
     * one that works".
     */
    var preferredMirror: String
        get() = sp.getString(K_PREFERRED_MIRROR, "").orEmpty()
        set(v) = edit { putString(K_PREFERRED_MIRROR, v) }

    var preferredListSource: String
        get() = sp.getString(K_PREFERRED_LIST, "").orEmpty()
        set(v) = edit { putString(K_PREFERRED_LIST, v) }

    /**
     * Prefix for the custom route, e.g. a self-hosted proxy. Only used when the
     * user selects the custom mirror in Settings.
     */
    var mirrorPrefix: String
        get() = sp.getString(K_MIRROR_PREFIX, "").orEmpty()
        set(v) = edit { putString(K_MIRROR_PREFIX, v.trim()) }

    /**
     * A fixed route, or empty for "race them and remember the winner".
     *
     * Pinning is an escape hatch: on a network where every automatic choice
     * misbehaves, the user can name the one that works and stop the racing.
     */
    var pinnedMirror: String
        get() = sp.getString(K_PIN_MIRROR, "").orEmpty()
        set(v) = edit { putString(K_PIN_MIRROR, v.trim()) }

    var pinnedListSource: String
        get() = sp.getString(K_PIN_LIST, "").orEmpty()
        set(v) = edit { putString(K_PIN_LIST, v.trim()) }

    // --- reading -----------------------------------------------------------
    var readerTheme: ReaderTheme
        get() = ReaderTheme.fromName(sp.getString(K_READER_THEME, null))
        set(v) = edit { putString(K_READER_THEME, v.name) }

    /** Body text size in sp. */
    var fontSize: Float
        get() = sp.getFloat(K_FONT_SIZE, 18f)
        set(v) = edit { putFloat(K_FONT_SIZE, v) }

    /** Line height multiplier. */
    var lineSpacing: Float
        get() = sp.getFloat(K_LINE_SPACING, 1.7f)
        set(v) = edit { putFloat(K_LINE_SPACING, v) }

    /**
     * The body typeface.
     *
     * Stored as the enum name, and resolved against the legacy [serif] flag when
     * absent: a user upgrading from a version that only had "衬线字体" keeps the
     * typeface they had chosen rather than being silently moved to a new default.
     */
    var readerFont: ReaderFont
        get() = ReaderFont.resolve(
            sp.getString(K_READER_FONT, null),
            sp.getBoolean(K_SERIF, true),
        )
        set(v) = edit { putString(K_READER_FONT, v.name) }

    /** Kept so the pre-1.0.4 preference still reads back; see [readerFont]. */
    var serif: Boolean
        get() = sp.getBoolean(K_SERIF, true)
        set(v) = edit { putBoolean(K_SERIF, v) }

    /**
     * Overrides for the reader's text and page colours.
     *
     * [ReaderInk.NONE] (0) means "use the theme's colour". An opaque colour always
     * has a non-zero alpha byte, so no real colour collides with the sentinel --
     * and a user who has never touched these gets the theme exactly as before.
     */
    var readerTextColor: Int
        get() = sp.getInt(K_READER_TEXT_COLOR, ReaderInk.NONE)
        set(v) = edit { putInt(K_READER_TEXT_COLOR, v) }

    var readerBackgroundColor: Int
        get() = sp.getInt(K_READER_BG_COLOR, ReaderInk.NONE)
        set(v) = edit { putInt(K_READER_BG_COLOR, v) }

    /** Page-turn animation style: slide or a plain fade. */
    var pageAnimation: Int
        get() = sp.getInt(K_PAGE_ANIM, PAGE_ANIM_SLIDE)
        set(v) = edit { putInt(K_PAGE_ANIM, v) }

    /**
     * How the reader moves through the book: page-turning or a vertical scroll.
     *
     * A separate axis from the theme because it is about the *interaction*: some
     * readers want a page with a defined end, others want the endless column a
     * phone's web browser trained them on.
     */
    var readingMode: ReadingMode
        get() = ReadingMode.fromName(sp.getString(K_READING_MODE, null))
        set(v) = edit { putString(K_READING_MODE, v.name) }

    /** Keep the screen awake while a book is open. */
    var keepScreenOn: Boolean
        get() = sp.getBoolean(K_KEEP_AWAKE, true)
        set(v) = edit { putBoolean(K_KEEP_AWAKE, v) }

    /**
     * How long automatic reading stays on one page, in seconds.
     *
     * Read through [AutoRead.clampSeconds] rather than used raw: a stored value
     * of zero would turn pages as fast as the device can, and the point of the
     * clamp is that a bad value produces a sane pace instead of a runaway. The
     * clamp is applied on read as well as on write so a value written by an older
     * or newer version -- or hand-edited -- is still bounded.
     */
    var autoReadSeconds: Int
        get() = AutoRead.clampSeconds(sp.getInt(K_AUTO_READ_SECONDS, AutoRead.DEFAULT_SECONDS))
        set(v) = edit { putInt(K_AUTO_READ_SECONDS, AutoRead.clampSeconds(v)) }

    /**
     * Whether a newly opened book starts reading itself.
     *
     * Off by default, and off for a book that has not been touched this session
     * even when it is on: a book that starts turning its own pages the instant it
     * opens is startling, and the reader may have opened it to check something.
     * The setting is what the reader's play button does for *the next* book.
     */
    var autoReadEnabled: Boolean
        get() = sp.getBoolean(K_AUTO_READ, false)
        set(v) = edit { putBoolean(K_AUTO_READ, v) }

    /** Follow the system dark theme, or force light/dark. */
    var themeMode: Int
        get() = sp.getInt(K_THEME_MODE, THEME_SYSTEM)
        set(v) = edit { putInt(K_THEME_MODE, v) }

    // --- updates -----------------------------------------------------------
    /**
     * Where to look for updates. Defaults to the project's own repository; a
     * fork only has to change these two fields in Settings.
     */
    var updateOwner: String
        get() = sp.getString(K_UPD_OWNER, null) ?: DEFAULT_UPDATE_OWNER
        set(v) = edit { putString(K_UPD_OWNER, v.trim()) }

    var updateRepo: String
        get() = sp.getString(K_UPD_REPO, null) ?: DEFAULT_UPDATE_REPO
        set(v) = edit { putString(K_UPD_REPO, v.trim()) }

    /** Optional direct URL to an update manifest; wins over the repo lookup. */
    var updateManifestUrl: String
        get() = sp.getString(K_UPD_URL, "").orEmpty()
        set(v) = edit { putString(K_UPD_URL, v.trim()) }

    var ignoredVersionCode: Int
        get() = sp.getInt(K_IGNORED_VERSION, 0)
        set(v) = edit { putInt(K_IGNORED_VERSION, v) }

    var autoCheckUpdates: Boolean
        get() = sp.getBoolean(K_AUTO_UPDATE, true)
        set(v) = edit { putBoolean(K_AUTO_UPDATE, v) }

    var lastUpdateCheck: Long
        get() = sp.getLong(K_LAST_CHECK, 0L)
        set(v) = edit { putLong(K_LAST_CHECK, v) }

    // --- home screen memory ------------------------------------------------
    /**
     * What the user was browsing on 主页.
     *
     * Stored here rather than in the screen because 主页 is disposed whenever the
     * user visits another tab -- `AppShell` swaps the subtree, so neither
     * `remember` nor `rememberSaveable` survives it. See [HomeMemory] for what is
     * kept and why the source is part of the record.
     *
     * Written through [editQuiet] rather than [edit]: this is bookkeeping about
     * where the user was looking, not a setting, and bumping [revision] for it
     * would make 主页 reload its whole listing every time the user switched tabs.
     */
    var homeMemory: HomeMemory
        get() = HomeMemory(
            sourceKey = sp.getString(K_HOME_SOURCE, "").orEmpty(),
            query = sp.getString(K_HOME_QUERY, "").orEmpty(),
            collection = sp.getString(K_HOME_COLLECTION, null),
            scrollIndex = sp.getInt(K_HOME_SCROLL_INDEX, 0),
            scrollOffset = sp.getInt(K_HOME_SCROLL_OFFSET, 0),
        )
        set(v) = editQuiet {
            putString(K_HOME_SOURCE, v.sourceKey)
            putString(K_HOME_QUERY, v.query)
            putString(K_HOME_COLLECTION, v.collection)
            putInt(K_HOME_SCROLL_INDEX, v.scrollIndex)
            putInt(K_HOME_SCROLL_OFFSET, v.scrollOffset)
        }

    /**
     * Forget the remembered browse state.
     *
     * Clearing rather than writing defaults, so an unset memory and a memory of
     * "the default view" are different states on disk: the first means "never
     * visited", the second means "visited and left at the top". Quiet for the same
     * reason as the setter.
     */
    fun clearHomeMemory() = editQuiet {
        remove(K_HOME_SOURCE)
        remove(K_HOME_QUERY)
        remove(K_HOME_COLLECTION)
        remove(K_HOME_SCROLL_INDEX)
        remove(K_HOME_SCROLL_OFFSET)
    }

    companion object {
        const val DEFAULT_OWNER = "nmaych"
        const val DEFAULT_REPO = "ReadonBooklibrary"
        const val DEFAULT_BRANCH = "main"

        const val DEFAULT_UPDATE_OWNER = "nmaych"
        const val DEFAULT_UPDATE_REPO = "Readon"

        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        const val PAGE_ANIM_SLIDE = 0
        const val PAGE_ANIM_NONE = 1

        private const val K_OWNER = "owner"
        private const val K_REPO = "repo"
        private const val K_BRANCH = "branch"
        private const val K_TOKEN = "token"
        private const val K_PREFERRED_MIRROR = "preferred_mirror"
        private const val K_PREFERRED_LIST = "preferred_list"
        private const val K_MIRROR_PREFIX = "mirror_prefix"
        private const val K_PIN_MIRROR = "pin_mirror"
        private const val K_PIN_LIST = "pin_list"
        private const val K_READER_THEME = "reader_theme"
        private const val K_FONT_SIZE = "font_size"
        private const val K_LINE_SPACING = "line_spacing"
        private const val K_SERIF = "serif"
        private const val K_READER_FONT = "reader_font"
        private const val K_READER_TEXT_COLOR = "reader_text_color"
        private const val K_READER_BG_COLOR = "reader_bg_color"
        private const val K_PAGE_ANIM = "page_anim"
        private const val K_READING_MODE = "reading_mode"
        private const val K_KEEP_AWAKE = "keep_awake"
        private const val K_AUTO_READ = "auto_read"
        private const val K_AUTO_READ_SECONDS = "auto_read_seconds"
        private const val K_THEME_MODE = "theme_mode"
        private const val K_UPD_OWNER = "upd_owner"
        private const val K_UPD_REPO = "upd_repo"
        private const val K_UPD_URL = "upd_url"
        private const val K_IGNORED_VERSION = "ignored_version"
        private const val K_AUTO_UPDATE = "auto_update"
        private const val K_LAST_CHECK = "last_check"
        private const val K_HOME_SOURCE = "home_source"
        private const val K_HOME_QUERY = "home_query"
        private const val K_HOME_COLLECTION = "home_collection"
        private const val K_HOME_SCROLL_INDEX = "home_scroll_index"
        private const val K_HOME_SCROLL_OFFSET = "home_scroll_offset"
    }
}
