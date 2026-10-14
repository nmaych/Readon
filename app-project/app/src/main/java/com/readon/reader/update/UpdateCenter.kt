package com.readon.reader.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.readon.reader.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The state of the update check, held for the life of the process.
 *
 * ## Why this is not screen state
 *
 * The check used to run in a `LaunchedEffect` inside the settings screen, on that
 * screen's coroutine scope. Compose cancels that scope when the user leaves the
 * tab, so tapping 设置 and then 主页 killed the check mid-flight -- and because the
 * `lastUpdateCheck` timestamp was only written *after* the check returned, the
 * next visit started it again from scratch. The visible result was a card stuck
 * on "正在检查… / 连接 GitHub 中" that never resolved, which is exactly what was
 * reported.
 *
 * The check now runs at application scope, its result survives navigation, and
 * only one check can be in flight at a time.
 */
class UpdateCenter(
    private val prefs: Prefs,
    private val manager: UpdateManager,
    private val scope: CoroutineScope,
) {

    /** What the card should render. */
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val latest: String?) : State
        data class Available(val info: UpdateResult.Available) : State
        data class Failed(val message: String) : State

        /**
         * A newer version exists but cannot be installed from here.
         *
         * Kept apart from [Failed] because the card must not label it "检查失败":
         * the check *succeeded* and found something. The user needs a different
         * action (download it themselves), not a retry.
         */
        data class NewerNotInstallable(val message: String) : State

        data class Downloading(val progress: Float) : State
        data class ReadyToInstall(val info: UpdateResult.Available, val file: File) : State
    }

    var state: State by mutableStateOf(State.Idle)
        private set

    /** True once a check has completed in this process, so it is not repeated. */
    private var checkedThisProcess = false

    private val lock = Mutex()

    /**
     * Check for updates if the user asked for automatic checks and enough time has
     * passed.
     *
     * Safe to call from a `LaunchedEffect` on every screen appearance: it returns
     * immediately when the cached result is still valid, and it never cancels a
     * check that is already running.
     */
    fun ensureChecked() {
        if (!prefs.autoCheckUpdates) return
        if (checkedThisProcess) return
        if (state is State.Checking) return
        if (System.currentTimeMillis() - prefs.lastUpdateCheck < CHECK_INTERVAL_MS) {
            checkedThisProcess = true
            return
        }
        check()
    }

    /** Run a check now, regardless of the interval. Backs the 检查更新 button. */
    fun check() {
        if (state is State.Checking) return
        state = State.Checking
        scope.launch {
            // One check at a time, so a button tap during the automatic check
            // cannot run two of them.
            lock.withLock {
                val result = manager.check()
                state = when (result) {
                    is UpdateResult.Available ->
                        if (result.versionCode == prefs.ignoredVersionCode) State.Idle
                        else State.Available(result)
                    is UpdateResult.UpToDate -> State.UpToDate(result.latestName)
                    is UpdateResult.Failed ->
                        if (result.newerExists) State.NewerNotInstallable(result.message)
                        else State.Failed(result.message)
                }
                checkedThisProcess = true
                // Record the timestamp only when the check actually completed. A
                // failure that wrote it here would silence retries for six hours
                // over what is usually a transient network problem.
                if (result !is UpdateResult.Failed) {
                    prefs.lastUpdateCheck = System.currentTimeMillis()
                }
            }
        }
    }

    /** Download [info] and hand it to the installer. */
    fun download(info: UpdateResult.Available, onReady: (File) -> Unit) {
        scope.launch {
            try {
                val file = manager.download(info) { p -> state = State.Downloading(p) }
                state = State.ReadyToInstall(info, file)
                onReady(file)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                state = State.Failed(e.message ?: "下载失败")
            }
        }
    }

    fun ignore(info: UpdateResult.Available) {
        prefs.ignoredVersionCode = info.versionCode
        state = State.Idle
    }

    companion object {
        /** Automatic checks are throttled to this interval. */
        const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
