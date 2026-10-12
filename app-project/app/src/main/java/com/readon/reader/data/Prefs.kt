package com.readon.reader.data

import android.content.Context
import android.content.SharedPreferences
import com.readon.reader.ui.theme.ReaderTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    var serif: Boolean
        get() = sp.getBoolean(K_SERIF, true)
        set(v) = edit { putBoolean(K_SERIF, v) }

    /** Keep the screen awake while a book is open. */
    var keepScreenOn: Boolean
        get() = sp.getBoolean(K_KEEP_AWAKE, true)
        set(v) = edit { putBoolean(K_KEEP_AWAKE, v) }

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

    companion object {
        const val DEFAULT_OWNER = "nmaych"
        const val DEFAULT_REPO = "ReadonBooklibrary"
        const val DEFAULT_BRANCH = "main"

        const val DEFAULT_UPDATE_OWNER = "nmaych"
        const val DEFAULT_UPDATE_REPO = "Readon"

        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

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
        private const val K_KEEP_AWAKE = "keep_awake"
        private const val K_THEME_MODE = "theme_mode"
        private const val K_UPD_OWNER = "upd_owner"
        private const val K_UPD_REPO = "upd_repo"
        private const val K_UPD_URL = "upd_url"
        private const val K_IGNORED_VERSION = "ignored_version"
        private const val K_AUTO_UPDATE = "auto_update"
        private const val K_LAST_CHECK = "last_check"
    }
}
