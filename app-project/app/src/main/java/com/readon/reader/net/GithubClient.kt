package com.readon.reader.net

import com.google.gson.JsonParser
import com.readon.reader.data.Book
import com.readon.reader.data.BookFormat
import com.readon.reader.data.LibrarySource
import com.readon.reader.data.Prefs
import com.readon.reader.data.RepoFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream

/** A failure that is worth showing to the user verbatim. */
class LibraryException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A library listing, plus how it was obtained. */
data class LibraryListing(
    val books: List<Book>,
    val truncated: Boolean,
    val via: String,
)

/**
 * Reads the online library out of a GitHub repository.
 *
 * ## Why this is not one hostname
 *
 * The first release fetched everything from `raw.githubusercontent.com`. That
 * host is blocked on the user's network, and because listing, reading and
 * downloading all went through it, the app could do nothing at all.
 *
 * Probing the live repository from the development machine produced the numbers
 * this class is built around:
 *
 * | route             | small file | large file | listing |
 * |-------------------|-----------|-----------|---------|
 * | raw.github...     | timeout   | timeout   | --      |
 * | cdn.jsdelivr.net  | ok        | 403       | 403     |
 * | fastly / gcore    | ok        | 403       | 403     |
 * | ghproxy.net       | ok        | ok        | 403     |
 * | gh-proxy.com      | ok        | ok        | ok      |
 * | api.github.com    | --        | --        | ok      |
 *
 * Three things follow, and each is a separate mechanism below:
 *
 *  1. **The library is huge.** 53 books, 33 of them over 8 MB and one at 75 MB.
 *     jsDelivr refuses anything over 20 MB per file and over 50 MB per package,
 *     so the CDN that looks like the obvious choice silently fails on most of
 *     this library. Only the two proxies served every file tested.
 *
 *  2. **Cheap probing beats racing whole files.** Racing a 75 MB download across
 *     nine hosts would be absurd. A route is chosen with a 2 KB range request
 *     first, and the file is then streamed from that one host.
 *
 *  3. **Listing is a different problem from fetching.** `api.github.com` is not
 *     blocked here even though `raw.githubusercontent.com` is -- they are
 *     different services -- and jsDelivr's listing API cannot describe this
 *     repository at all. So the two use independent route lists.
 */
class GithubClient(private val prefs: Prefs) {

    private fun request(url: String, rangeProbe: Boolean = false): Request {
        val b = Request.Builder()
            .url(url)
            .header("User-Agent", Http.USER_AGENT)
            .header("Accept", "application/vnd.github+json")
        if (rangeProbe) {
            // Two kilobytes proves a route carries this file, and is small enough
            // that probing every host costs less than one page of a book.
            b.header("Range", "bytes=0-2047")
        }
        val t = prefs.token
        if (t.isNotBlank()) b.header("Authorization", "Bearer $t")
        return b.build()
    }

    // -----------------------------------------------------------------------
    // Route selection
    // -----------------------------------------------------------------------

    /**
     * The file hosts to try, best-known first.
     *
     * A pinned host is used alone: pinning is an explicit user choice, and
     * quietly falling back to a host they deliberately avoided would be a
     * surprise. Otherwise the remembered winner leads and the rest follow.
     */
    private fun mirrorOrder(): List<Mirror> {
        Mirror.byId(prefs.pinnedMirror)?.let { return listOf(it) }
        return ordered(Mirror.byId(prefs.preferredMirror), Mirror.RACE_ORDER)
    }

    /**
     * Pick a host that can actually deliver [path], using a 2 KB range request.
     *
     * Every candidate is probed at once because each probe is tiny, and the first
     * to answer wins. This is the step that turns "one of nine hosts might work"
     * into a single known-good host before any real bytes move.
     */
    private suspend fun pickMirror(source: LibrarySource, path: String): Mirror? =
        withContext(Dispatchers.IO) {
            val candidates = mirrorOrder()
            val results = Channel<Pair<Boolean, Mirror>>(Channel.UNLIMITED)

            coroutineScope {
                val jobs = candidates.map { mirror ->
                    launch {
                        val url = mirror.rawUrl(source, path, prefs.mirrorPrefix)
                        val ok = runCatching { probeRange(url) }.getOrDefault(false)
                        results.send(ok to mirror)
                    }
                }

                var winner: Mirror? = null
                for (i in candidates.indices) {
                    val (ok, mirror) = results.receive()
                    if (ok) {
                        winner = mirror
                        break
                    }
                }
                jobs.forEach { it.cancel() }
                results.close()
                winner
            }
        }

    /** True when a 2 KB range of [url] comes back as plausible file content. */
    private fun probeRange(url: String): Boolean {
        Http.client.newCall(request(url, rangeProbe = true)).execute().use { resp ->
            if (!resp.isSuccessful) return false
            val body = resp.body ?: return false
            // Several of these proxies answer an unknown path with an HTML error
            // page under a 200, so the payload is inspected, not just the status.
            val head = body.byteStream().use { input ->
                val buf = ByteArray(256)
                val n = input.read(buf)
                if (n <= 0) return false
                String(buf, 0, n, Charsets.ISO_8859_1)
            }
            val lower = head.lowercase()
            return !(lower.contains("<html") || lower.contains("<!doctype"))
        }
    }

    /**
     * Run [block] against each candidate host until one succeeds.
     *
     * Used for the real transfer after [pickMirror] has named a host: if that host
     * drops mid-transfer, the next is tried rather than failing the whole
     * operation. The remembered winner is updated on success.
     */
    private suspend fun <T> withMirrors(
        source: LibrarySource,
        path: String,
        preferred: Mirror?,
        block: (Mirror) -> T,
    ): T = withContext(Dispatchers.IO) {
        val order = buildList {
            preferred?.let { add(it) }
            mirrorOrder().forEach { if (it != preferred) add(it) }
        }

        var lastError: Throwable? = null
        for (mirror in order) {
            try {
                val value = block(mirror)
                prefs.preferredMirror = mirror.id
                return@withContext value
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastError = e
            }
        }
        throw LibraryException(
            lastError?.message ?: "所有线路都无法访问，请检查网络或在「设置」里换一条线路",
            lastError,
        )
    }

    // -----------------------------------------------------------------------
    // Listing
    // -----------------------------------------------------------------------

    /**
     * List every readable book in the repository.
     *
     * [LibraryListing.truncated] is not an error: GitHub caps very large trees and
     * a partial library is still usable, so the flag only drives a warning.
     */
    suspend fun listBooks(source: LibrarySource): LibraryListing = withContext(Dispatchers.IO) {
        val (listing, via) = race(listOrder(), { it.label }) { src -> fetchListing(src, source) }
        prefs.preferredListSource = via.id
        listing
    }

    private fun listOrder(): List<ListSource> {
        ListSource.byId(prefs.pinnedListSource)?.let { return listOf(it) }
        return ordered(ListSource.byId(prefs.preferredListSource), ListSource.RACE_ORDER)
    }

    private fun fetchListing(src: ListSource, source: LibrarySource): LibraryListing {
        val body = get(src.url(source))
        return when (src) {
            ListSource.JsDelivrData -> parseJsDelivr(body, source)
            else -> parseGithubTree(body, source)
        }
    }

    private fun parseGithubTree(body: String, source: LibrarySource): LibraryListing {
        val root = try {
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            throw LibraryException("仓库返回的内容无法解析", e)
        }

        if (root.has("message") && !root.has("tree")) {
            throw LibraryException(explain(root.get("message").asString))
        }

        val tree = root.getAsJsonArray("tree")
            ?: throw LibraryException("仓库没有返回文件列表")

        val files = ArrayList<RepoFile>(tree.size())
        for (el in tree) {
            val obj = el.asJsonObject
            if (obj.get("type")?.asString != "blob") continue
            val path = obj.get("path")?.asString ?: continue
            if (!BookFormat.isReadable(path)) continue
            files.add(RepoFile(path, obj.get("size")?.asLong ?: 0L))
        }

        return LibraryListing(
            books = files.mapNotNull { Book.fromRepoFile(source, it) }
                .sortedWith(compareBy({ it.folder }, { it.title })),
            truncated = root.get("truncated")?.asBoolean ?: false,
            via = ListSource.GithubApi.label,
        )
    }

    /**
     * jsDelivr's flat file listing.
     *
     * Kept as a fallback for small repositories, but it cannot describe this
     * library: the package is far over jsDelivr's 50 MB limit, so every variant of
     * this endpoint answers 403. That is why it is last in [ListSource.RACE_ORDER].
     */
    private fun parseJsDelivr(body: String, source: LibrarySource): LibraryListing {
        val root = try {
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            throw LibraryException("镜像返回的内容无法解析", e)
        }

        // This endpoint reports its own failure inside a 200 body.
        if (root.has("status") && root.get("status").asInt >= 400) {
            throw LibraryException(root.get("message")?.asString ?: "镜像拒绝了请求")
        }

        val files = root.getAsJsonArray("files")
            ?: throw LibraryException("镜像没有返回文件列表")

        val out = ArrayList<Book>(files.size())
        for (el in files) {
            val obj = el.asJsonObject
            // jsDelivr prefixes every path with a slash.
            val path = (obj.get("name")?.asString ?: continue).removePrefix("/")
            if (!BookFormat.isReadable(path)) continue
            Book.fromRepoFile(source, RepoFile(path, obj.get("size")?.asLong ?: 0L))
                ?.let { out.add(it) }
        }

        return LibraryListing(
            books = out.sortedWith(compareBy({ it.folder }, { it.title })),
            truncated = false,
            via = ListSource.JsDelivrData.label,
        )
    }

    // -----------------------------------------------------------------------
    // File bytes
    // -----------------------------------------------------------------------

    /**
     * Download a book to [target], streaming straight to disk.
     *
     * Never holds the file in memory: this library's books reach 75 MB, and
     * buffering one of those to hand it to a file write is how a reader gets an
     * out-of-memory crash on a mid-range phone.
     *
     * @return the number of bytes written.
     */
    suspend fun downloadTo(
        source: LibrarySource,
        path: String,
        target: File,
        onProgress: (Float) -> Unit = {},
    ): Long = withContext(Dispatchers.IO) {
        val preferred = pickMirror(source, path)
        withMirrors(source, path, preferred) { mirror ->
            streamToFile(mirror.rawUrl(source, path, prefs.mirrorPrefix), target, onProgress)
        }
    }

    private fun streamToFile(url: String, target: File, onProgress: (Float) -> Unit): Long {
        // Write to a sibling temp file and rename on success, so an interrupted
        // transfer can never be mistaken for a complete book.
        val tmp = File(target.parentFile, target.name + ".part")
        target.parentFile?.mkdirs()
        tmp.delete()

        try {
            Http.client.newCall(request(url)).execute().use { resp ->
                if (!resp.isSuccessful) throw LibraryException(httpMessage(resp.code))
                val body = resp.body ?: throw LibraryException("下载失败：内容为空")
                val total = body.contentLength()
                var read = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) onProgress(read.toFloat() / total)
                        }
                    }
                }
                if (read == 0L) throw LibraryException("下载失败：内容为空")
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            return target.length()
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    /**
     * Fetch a file into memory, for reading.
     *
     * The cap is generous because this library needs it -- 33 of its 53 books are
     * over 8 MB -- but it is still a cap: an unbounded read of a 75 MB file into a
     * String is a crash waiting for a low-memory device. Books past it are still
     * readable after downloading, which streams to disk instead.
     */
    suspend fun fetchText(source: LibrarySource, path: String): ByteArray =
        withContext(Dispatchers.IO) {
            val preferred = pickMirror(source, path)
            withMirrors(source, path, preferred) { mirror ->
                download(mirror.rawUrl(source, path, prefs.mirrorPrefix), MAX_TEXT_BYTES)
            }
        }

    private fun download(url: String, limit: Long): ByteArray {
        Http.client.newCall(request(url)).execute().use { resp ->
            if (!resp.isSuccessful) throw LibraryException(httpMessage(resp.code))
            val body = resp.body ?: throw LibraryException("下载失败：内容为空")
            val declared = body.contentLength()
            if (declared > limit) {
                throw LibraryException(
                    "这本书有 ${declared / 1024 / 1024} MB，超过在线阅读上限。" +
                        "请先下载到书架再打开。"
                )
            }
            return body.byteStream().use { it.readAtMost(limit) }
        }
    }

    // -----------------------------------------------------------------------
    // Raw text, for the update check
    // -----------------------------------------------------------------------

    /**
     * Fetch a small text document, trying the GitHub proxies.
     *
     * `dist/update.json` lives on `raw.githubusercontent.com` and is therefore
     * just as blocked as the books are, so it needs the same treatment.
     */
    suspend fun fetchRaw(url: String): String = withContext(Dispatchers.IO) {
        val candidates = buildList {
            add(url)
            if (url.startsWith("https://raw.githubusercontent.com/")) {
                add("https://ghproxy.net/$url")
                add("https://gh-proxy.com/$url")
                add("https://ghfast.top/$url")
            }
        }
        val (body, _) = race(candidates, ::hostOf) { get(it) }
        body
    }

    /**
     * Probe every file route and report which ones answer.
     *
     * Backs the "检测线路" button. On a blocked network the user cannot tell a
     * broken app from a blocked host; this is what makes the difference visible.
     */
    suspend fun probeRoutes(source: LibrarySource, path: String): List<Pair<String, Boolean>> =
        withContext(Dispatchers.IO) {
            Mirror.RACE_ORDER.map { mirror ->
                val url = mirror.rawUrl(source, path, prefs.mirrorPrefix)
                val ok = runCatching { probeRange(url) }.getOrDefault(false)
                mirror.label to ok
            }
        }

    // -----------------------------------------------------------------------
    // Racing
    // -----------------------------------------------------------------------

    /**
     * Try every candidate at once and return the first success.
     *
     * For the listing only: those payloads are ~13 KB, so racing all of them costs
     * less than a single page of a book. File transfers go through [pickMirror]
     * and [withMirrors] instead, because racing whole files would multiply
     * bandwidth by the number of hosts.
     */
    private suspend fun <T, C> race(
        candidates: List<C>,
        describe: (C) -> String,
        block: suspend (C) -> T,
    ): Pair<T, C> = coroutineScope {
        if (candidates.isEmpty()) throw LibraryException("没有可用的访问线路")

        val results = Channel<Pair<Result<T>, C>>(Channel.UNLIMITED)
        val jobs = candidates.map { candidate ->
            launch { results.send(runCatching { block(candidate) } to candidate) }
        }

        var winner: Pair<T, C>? = null
        val failures = ArrayList<String>(candidates.size)

        for (i in candidates.indices) {
            val (outcome, candidate) = results.receive()
            val value = outcome.getOrNull()
            if (value != null) {
                winner = value to candidate
                break
            }
            failures.add("${describe(candidate)}：${outcome.exceptionOrNull()?.message ?: "失败"}")
        }

        jobs.forEach { it.cancel() }
        results.close()

        winner ?: throw LibraryException(
            failures.firstOrNull()?.substringAfter("：")?.takeIf { it.isNotBlank() }
                ?: "网络不可用，请检查连接或在「设置」里换一条线路",
        )
    }

    private fun <T> ordered(preferred: T?, all: List<T>): List<T> {
        if (preferred == null) return all
        return listOf(preferred) + all.filter { it != preferred }
    }

    private fun get(url: String): String {
        Http.client.newCall(request(url)).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching {
                    JsonParser.parseString(body).asJsonObject.get("message")?.asString
                }.getOrNull()
                throw LibraryException(explain(msg ?: httpMessage(resp.code)))
            }
            return body
        }
    }

    private fun httpMessage(code: Int): String = when (code) {
        404 -> "文件不存在，可能已被移除或改名"
        401, 403 -> "访问被拒绝。可能是接口限额用尽（在「设置」填写访问令牌可提升），" +
            "也可能是这条线路不支持这么大的文件"
        429 -> "访问过于频繁，请稍后再试"
        else -> "HTTP $code"
    }

    private fun explain(message: String): String = when {
        message.contains("Not Found", true) ->
            "找不到仓库。请到「设置」检查仓库名和分支是否正确"
        message.contains("rate limit", true) ->
            "GitHub 接口访问次数已达上限，请在「设置」里填写访问令牌后重试"
        message.contains("Bad credentials", true) ->
            "访问令牌无效，请在「设置」里重新填写"
        else -> message
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull() ?: url

    companion object {
        /**
         * Online-reading cap.
         *
         * 64 MB because this library needs it -- books here reach 75 MB, and a
         * lower cap would make "在线阅读" fail for most of the shelf. Books past it
         * remain readable after downloading, which streams to disk.
         */
        const val MAX_TEXT_BYTES = 64L * 1024 * 1024
    }
}

/** Read at most [limit] bytes, so an oversized file cannot exhaust the heap. */
private fun InputStream.readAtMost(limit: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(128 * 1024)
    var total = 0L
    while (true) {
        val n = read(buf)
        if (n < 0) break
        total += n
        if (total > limit) throw LibraryException("文件过大，请下载后再打开")
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
