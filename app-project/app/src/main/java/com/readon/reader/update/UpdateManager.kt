package com.readon.reader.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import com.readon.reader.BuildConfig
import com.readon.reader.data.Prefs
import com.readon.reader.net.GithubClient
import com.readon.reader.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Finds and installs new versions from GitHub.
 *
 * Two sources, both consulted on every check:
 *
 *   1. A manifest at `dist/update.json` in the configured update repository.
 *      It is a small static file, it carries the release notes and the APK's
 *      SHA-256, and reading it does not consume the GitHub API rate limit.
 *   2. The GitHub releases API.
 *
 * ## Why both are always asked
 *
 * The manifest used to be authoritative: a manifest that said "no update" ended
 * the check, and the releases API was consulted only when the manifest could not
 * be read at all. That made a *stale* manifest indistinguishable from "you are
 * up to date" -- and stale is the normal failure, not a rare one. The manifest
 * lives on the default branch and is written by the release workflow, so a
 * release published by hand, or a run where the manifest commit did not land,
 * leaves it describing the previous version. The app then said 已是最新版本 while
 * a newer release sat on the releases page. That is the reported bug.
 *
 * Both sources are therefore asked and the newer answer wins; see
 * [UpdateDecision.reconcile], which is where that rule lives and where it is
 * tested. Two small JSON requests instead of one is a fair price for not lying to
 * the user.
 *
 * The version comparison is always on `versionCode`, never on the version name:
 * string comparison puts "1.10.0" before "1.9.0", which would silently stop
 * offering updates at exactly the wrong moment.
 *
 * Both lookups go through [GithubClient.fetchRaw], so they inherit the same
 * mirror racing as everything else, and they are sent with `no-cache` so a
 * proxy's cached copy of the manifest cannot hide a release either.
 */
class UpdateManager(
    private val context: Context,
    private val prefs: Prefs,
    private val client: GithubClient,
) {

    suspend fun check(): UpdateResult = withContext(Dispatchers.IO) {
        // An explicit manifest URL wins, so a fork or a self-hosted mirror works
        // without touching the app. A hand-configured URL is a deliberate choice,
        // and it is the only source such a setup has.
        val explicit = prefs.updateManifestUrl
        if (explicit.isNotBlank()) {
            return@withContext checkManifest(explicit)
        }

        val owner = prefs.updateOwner
        val repo = prefs.updateRepo
        val manifestUrl =
            "https://raw.githubusercontent.com/$owner/$repo/main/dist/update.json"

        // Both sources are asked, concurrently, and the newer answer wins.
        //
        // The manifest alone was authoritative here, which is exactly what made
        // the app claim 已是最新版本 while a newer release existed: a manifest left
        // behind at the previous version reports "up to date" in perfect health,
        // so it cannot be told apart from the truth. Asking the releases API too
        // costs one extra small request and removes the failure mode entirely.
        coroutineScope {
            val manifest = async { checkManifest(manifestUrl) }
            val releases = async { checkReleases(owner, repo) }
            UpdateDecision.reconcile(manifest.await(), releases.await())
        }
    }

    /**
     * Fetch the manifest.
     *
     * The timeout is passed down to every route attempt. Without it the check
     * could sit for the sum of four hosts' connect timeouts before giving up,
     * which is why "检查更新" appeared to hang on "连接 GitHub 中": the request
     * had not failed yet, so nothing was reported.
     */
    private suspend fun checkManifest(url: String): UpdateResult {
        return try {
            // `noCache` because the proxies in front of raw.githubusercontent.com
            // happily serve a stale copy of this file. A cached manifest is
            // another way to be told "已是最新版本" while a release is out.
            val body = client.fetchRaw(url, CHECK_TIMEOUT_MS, noCache = true)
            val obj = JsonParser.parseString(body).asJsonObject
            val code = obj.get("versionCode")?.asInt
                ?: return UpdateResult.Failed("清单缺少 versionCode")
            val name = obj.get("versionName")?.asString ?: code.toString()
            val apk = obj.get("apkUrl")?.asString

            if (code > BuildConfig.VERSION_CODE) {
                // A newer version with no download URL is not something to
                // install, but it is emphatically not "up to date" either --
                // saying so is the bug this whole class is about.
                if (apk.isNullOrBlank()) {
                    return UpdateResult.Failed(
                        "发现新版本 $name，但清单里没有下载地址。请稍后重试，或到 GitHub 下载。",
                        newerExists = true,
                    )
                }
                UpdateResult.Available(
                    versionName = name,
                    versionCode = code,
                    apkUrl = apk,
                    sha256 = obj.get("sha256")?.asString,
                    notes = obj.get("notes")?.asString.orEmpty(),
                    mandatory = obj.get("mandatory")?.asBoolean ?: false,
                )
            } else {
                UpdateResult.UpToDate(name)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            UpdateResult.Failed(friendly(e))
        }
    }

    private suspend fun checkReleases(owner: String, repo: String): UpdateResult {
        return try {
            val body = client.fetchRaw(
                "https://api.github.com/repos/$owner/$repo/releases/latest",
                CHECK_TIMEOUT_MS,
                noCache = true,
            )
            val obj = JsonParser.parseString(body).asJsonObject
            if (obj.has("message") && !obj.has("tag_name")) {
                return UpdateResult.Failed(obj.get("message").asString)
            }
            val tag = obj.get("tag_name")?.asString.orEmpty()
            val name = tag.removePrefix("v").ifBlank { "未知" }
            // A release tag is `v1.2.3`; without a manifest there is no
            // versionCode, so it is derived from the tag using the same scheme as
            // the build itself.
            val code = UpdateDecision.versionCodeOf(name)
            val apk = obj.getAsJsonArray("assets")
                ?.map { it.asJsonObject }
                ?.firstOrNull { it.get("name")?.asString?.endsWith(".apk") == true }
                ?.get("browser_download_url")?.asString

            if (code > BuildConfig.VERSION_CODE) {
                if (apk.isNullOrBlank()) {
                    return UpdateResult.Failed(
                        "发现新版本 $name，但这个 release 没有附带 APK。请到 GitHub 手动下载。",
                        newerExists = true,
                    )
                }
                UpdateResult.Available(
                    versionName = name,
                    versionCode = code,
                    apkUrl = apk,
                    sha256 = null,
                    notes = obj.get("body")?.asString.orEmpty(),
                    mandatory = false,
                )
            } else {
                UpdateResult.UpToDate(name)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            UpdateResult.Failed(friendly(e))
        }
    }

    /**
     * Turn a transport failure into something a user can act on.
     *
     * A bare `SocketTimeoutException` message tells them nothing; the honest
     * summary is that GitHub could not be reached and the library still works.
     */
    private fun friendly(e: Throwable): String {
        val raw = e.message.orEmpty()
        return when {
            raw.contains("timeout", true) || e is java.net.SocketTimeoutException ->
                "连接 GitHub 超时。可以稍后重试，或在「设置 → 网络线路」里换一条线路。"
            raw.contains("Unable to resolve host", true) ->
                "无法解析 GitHub 域名，请检查网络连接。"
            raw.isBlank() -> "检查更新失败"
            else -> raw
        }
    }

    /**
     * Download an update APK into app-private cache storage.
     *
     * OkHttp rather than `DownloadManager`: this is a single ~10 MB file fetched
     * while the user watches a progress bar, and doing it in-process keeps the
     * progress, the failure and the "now install it" step in one flow instead of
     * a broadcast that has to be correlated back to the screen that started it.
     *
     * @param onProgress called with 0f..1f, or -1f when the size is unknown.
     * @return the downloaded file, ready to hand to the installer.
     */
    suspend fun download(
        update: UpdateResult.Available,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Clear previous downloads: they are large and only one is ever needed.
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val target = File(dir, "readon-${update.versionName}.apk")

        val req = Request.Builder()
            .url(update.apkUrl)
            .header("User-Agent", Http.USER_AGENT)
            .build()

        // A whole-call ceiling: an APK is ~10 MB, so if it has not arrived in ten
        // minutes the route is not going to deliver it.
        Http.execute(req, DOWNLOAD_TIMEOUT_MS).use { resp ->
            if (!resp.isSuccessful) error("下载失败：HTTP ${resp.code}")
            val body = resp.body ?: error("下载失败：内容为空")
            val total = body.contentLength()
            body.byteStream().use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        onProgress(if (total > 0) read.toFloat() / total else -1f)
                    }
                }
            }
        }

        // Verify the manifest's digest when it published one. A truncated or
        // substituted APK would otherwise be handed straight to the installer.
        val expected = update.sha256?.trim()?.lowercase()
        if (!expected.isNullOrBlank() && sha256Of(target) != expected) {
            target.delete()
            error("更新包校验失败，请稍后重试")
        }

        onProgress(1f)
        target
    }

    /** Whether the user has allowed this app to install packages. */
    fun canInstall(): Boolean = if (android.os.Build.VERSION.SDK_INT >= 26) {
        context.packageManager.canRequestPackageInstalls()
    } else {
        true
    }

    /** Open the system's "install unknown apps" screen for this app. */
    fun requestInstallPermission() {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Hand a downloaded APK to the system installer. */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Delete the downloaded update packages.
     *
     * ## Why this is called on every start
     *
     * The update APK is ~11 MB kept in `cacheDir/updates`. Android *may* reclaim
     * `cacheDir` under storage pressure, but "may" is not a promise: on a phone that
     * never runs short of space the file stays indefinitely, and every release adds
     * another one -- the directory is cleared before a new download, so the old file
     * only goes when a *newer* update is fetched, which never happens for a user who
     * installs from the store or stops updating.
     *
     * Once the new version is running, the package it was installed from is dead
     * weight: the installed copy is what runs, and the APK cannot even be reused for a
     * reinstall after an uninstall (the signature check passes but the file is a
     * different version's). So the app deletes it at startup, which is the first
     * moment it can know the install succeeded -- the installer process replaces this
     * app's code, so no code inside the *old* version gets to run after the user taps
     * 安装.
     *
     * Safe to call at any time: an empty or missing directory is not an error, and a
     * file locked by the system installer is left for the next start rather than
     * reported.
     */
    fun clearDownloadedPackages() {
        val dir = File(context.cacheDir, "updates")
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { file ->
            // Best effort: a file the installer still holds open cannot be deleted on
            // some devices, and that is not a failure worth surfacing.
            runCatching { file.delete() }
        }
        runCatching { dir.delete() }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /**
         * Whole-call ceiling for a check.
         *
         * The manifest and the releases API are both small JSON documents. Four
         * routes each getting a full connect timeout is what made the card look
         * stuck; fifteen seconds is long enough for a slow-but-working route and
         * short enough that a dead one reports a failure the user can act on.
         */
        private const val CHECK_TIMEOUT_MS = 15_000L

        /** A whole APK transfer. Generous, but bounded. */
        private const val DOWNLOAD_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
