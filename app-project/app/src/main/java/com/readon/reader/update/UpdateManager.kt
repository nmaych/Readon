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
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/** What an update check found. */
sealed interface UpdateResult {
    /** A newer build is available. */
    data class Available(
        val versionName: String,
        val versionCode: Int,
        val apkUrl: String,
        val sha256: String?,
        val notes: String,
        val mandatory: Boolean,
    ) : UpdateResult

    /** The installed build is current. */
    data class UpToDate(val latestName: String?) : UpdateResult

    /** The check could not run. */
    data class Failed(val message: String) : UpdateResult
}

/**
 * Finds and installs new versions from GitHub.
 *
 * Two sources, tried in order:
 *
 *   1. A manifest at `dist/update.json` in the configured update repository.
 *      This is the primary path: it is a small static file, it carries the
 *      release notes and the APK's SHA-256, and reading it does not consume the
 *      GitHub API rate limit.
 *   2. The GitHub releases API, as a fallback for a repository that publishes
 *      releases but has not added the manifest.
 *
 * The version comparison is always on `versionCode`, never on the version name:
 * string comparison puts "1.10.0" before "1.9.0", which would silently stop
 * offering updates at exactly the wrong moment.
 *
 * Both lookups go through [GithubClient.fetchRaw], so they inherit the same
 * mirror racing as everything else. That matters: `raw.githubusercontent.com` is
 * blocked on the same networks that block the books, and an update check that
 * cannot reach its manifest is indistinguishable from "no update available".
 */
class UpdateManager(
    private val context: Context,
    private val prefs: Prefs,
    private val client: GithubClient,
) {

    suspend fun check(): UpdateResult = withContext(Dispatchers.IO) {
        // An explicit manifest URL wins, so a fork or a self-hosted mirror works
        // without touching the app.
        val explicit = prefs.updateManifestUrl
        if (explicit.isNotBlank()) {
            return@withContext checkManifest(explicit)
        }

        val owner = prefs.updateOwner
        val repo = prefs.updateRepo
        val manifestUrl =
            "https://raw.githubusercontent.com/$owner/$repo/main/dist/update.json"

        val fromManifest = checkManifest(manifestUrl)
        // A missing manifest is expected on a repository that only uses releases.
        if (fromManifest is UpdateResult.Available) return@withContext fromManifest
        if (fromManifest is UpdateResult.UpToDate) return@withContext fromManifest

        val fromReleases = checkReleases(owner, repo)
        if (fromReleases is UpdateResult.Failed && fromManifest is UpdateResult.Failed) {
            // Prefer the manifest's message: it explains the common case, which is
            // that the repository has no dist/update.json yet.
            return@withContext fromManifest
        }
        fromReleases
    }

    private suspend fun checkManifest(url: String): UpdateResult {
        return try {
            val body = client.fetchRaw(url)
            val obj = JsonParser.parseString(body).asJsonObject
            val code = obj.get("versionCode")?.asInt
                ?: return UpdateResult.Failed("清单缺少 versionCode")
            val name = obj.get("versionName")?.asString ?: code.toString()
            val apk = obj.get("apkUrl")?.asString
                ?: return UpdateResult.Failed("清单缺少 apkUrl")

            if (code > BuildConfig.VERSION_CODE) {
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
        } catch (e: Exception) {
            UpdateResult.Failed(e.message ?: "检查更新失败")
        }
    }

    private suspend fun checkReleases(owner: String, repo: String): UpdateResult {
        return try {
            val body = client.fetchRaw(
                "https://api.github.com/repos/$owner/$repo/releases/latest"
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
            val code = versionCodeOf(name)
            val apk = obj.getAsJsonArray("assets")
                ?.map { it.asJsonObject }
                ?.firstOrNull { it.get("name")?.asString?.endsWith(".apk") == true }
                ?.get("browser_download_url")?.asString
                ?: return UpdateResult.Failed("该版本没有附带 APK")

            if (code > BuildConfig.VERSION_CODE) {
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
        } catch (e: Exception) {
            UpdateResult.Failed(e.message ?: "检查更新失败")
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

        Http.client.newCall(req).execute().use { resp ->
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
        /** `1.2.3` -> `10203`, matching the build's version scheme. */
        fun versionCodeOf(versionName: String): Int {
            val parts = versionName.trim().split('.', '-', '+')
            val nums = parts.mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }
            if (nums.isEmpty()) return 0
            val major = nums.getOrElse(0) { 0 }
            val minor = nums.getOrElse(1) { 0 }
            val patch = nums.getOrElse(2) { 0 }
            return major * 10000 + minor * 100 + patch
        }
    }
}
