package com.readon.reader.net

import com.google.gson.JsonParser
import com.readon.reader.data.Book
import com.readon.reader.data.BookFormat
import com.readon.reader.data.LibrarySource
import com.readon.reader.data.PartialRead
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
 * What one ranged fetch of a book produced.
 *
 * @param bytes how many bytes this call appended to the target file.
 * @param total the file's full size, as the route stated it in `Content-Range` or
 *   `Content-Length`; 0 when the route did not say.
 * @param wholeFile true when the route ignored the `Range` header and started
 *   sending the file from the beginning. Some proxies do this, and the caller
 *   has to know so it does not append a second copy of the book to the first.
 */
data class RangeResult(
    val bytes: Long,
    val total: Long,
    val wholeFile: Boolean,
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
 *
 * ## Why every request is cancellable and time-bounded
 *
 * Route selection is a race: one request per candidate, first success wins. That
 * only works if the losers actually *stop*. They did not -- the requests were
 * blocking `execute()` calls that kept their sockets until their own timeouts
 * expired, so opening a book could leave a dozen dead connections draining for a
 * minute while the reader displayed "正在排版…". Every call now goes through
 * [Http.execute], which cancels the underlying OkHttp call the moment its
 * coroutine is cancelled, and carries a whole-call timeout.
 */
class GithubClient(private val prefs: Prefs) {

    /**
     * @param rangeHeader a `Range` value, e.g. `bytes=0-2047`. Used both by the
     *   route probe (a 2 KB window) and by the incremental reader, which fetches
     *   a book in chunks rather than waiting for the whole file.
     */
    private fun request(
        url: String,
        rangeHeader: String? = null,
        noCache: Boolean = false,
    ): Request {
        val b = Request.Builder()
            .url(url)
            .header("User-Agent", Http.USER_AGENT)
            .header("Accept", "application/vnd.github+json")
        if (rangeHeader != null) b.header("Range", rangeHeader)
        if (noCache) {
            // The GitHub proxies in front of raw.githubusercontent.com cache
            // aggressively, and the update manifest is a file whose whole point is
            // that it changes on release day. Without this, a proxy can keep
            // answering with the previous version -- which the app then reports as
            // "已是最新版本" while a newer release is published. `no-store` is added
            // alongside `no-cache` because some CDNs honour only one of the two.
            b.header("Cache-Control", "no-cache, no-store, max-age=0")
            b.header("Pragma", "no-cache")
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
     * Pick a host that can actually deliver [path].
     *
     * When a winner from an earlier request is remembered, it is probed *alone*
     * first and the rest are only woken if it fails. That fast path is what keeps
     * opening a book to a single 2 KB request on a working network; racing all
     * nine hosts every time is nine requests' worth of latency, bandwidth and
     * battery for the same answer.
     *
     * @param onStage called with a short user-facing note as selection proceeds.
     */
    private suspend fun pickMirror(
        source: LibrarySource,
        path: String,
        onStage: (String) -> Unit = {},
    ): Mirror? = withContext(Dispatchers.IO) {
        val candidates = mirrorOrder()
        val remembered = Mirror.byId(prefs.preferredMirror)

        if (remembered != null && remembered in candidates) {
            val ok = runCatching {
                probeRange(remembered.rawUrl(source, path, prefs.mirrorPrefix), FAST_PROBE_MS)
            }.getOrDefault(false)
            if (ok) return@withContext remembered
            onStage("首选线路无响应，正在切换…")
        }

        // The remembered host has just failed its short probe, so racing it again
        // would only add a request that cannot win. Everything else is tried.
        val racers = if (remembered != null) candidates.filter { it != remembered } else candidates
        if (racers.isEmpty()) return@withContext remembered

        val results = Channel<Pair<Boolean, Mirror>>(Channel.UNLIMITED)

        coroutineScope {
            val jobs = racers.map { mirror ->
                launch {
                    val url = mirror.rawUrl(source, path, prefs.mirrorPrefix)
                    val ok = runCatching { probeRange(url) }.getOrDefault(false)
                    results.send(ok to mirror)
                }
            }

            var winner: Mirror? = null
            for (i in racers.indices) {
                val (ok, mirror) = results.receive()
                if (ok) {
                    winner = mirror
                    break
                }
            }
            // Cancelling here now genuinely aborts the in-flight probes: each is
            // an enqueued OkHttp call whose cancellation closes its socket.
            jobs.forEach { it.cancel() }
            results.close()
            // Falling back to the remembered host is better than returning null:
            // its fast probe may have lost a race with a momentarily busy network,
            // and the real transfer retries the whole list anyway.
            winner ?: remembered
        }
    }

    /** True when a 2 KB range of [url] comes back as plausible file content. */
    private suspend fun probeRange(url: String, timeoutMs: Long = PROBE_MS): Boolean {
        // Two kilobytes proves a route carries this file, and is small enough that
        // probing every host costs less than one page of a book.
        Http.execute(request(url, rangeHeader = PROBE_RANGE), timeoutMs).use { resp ->
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
     *
     * The URL is built by [block] rather than passed in, because each attempt
     * needs its *own* host's URL -- that is the whole point of retrying.
     */
    private suspend fun <T> withMirrors(
        preferred: Mirror?,
        block: suspend (Mirror) -> T,
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

    private suspend fun fetchListing(src: ListSource, source: LibrarySource): LibraryListing {
        val body = get(src.url(source), LIST_TIMEOUT_MS)
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
     * @param expectedSize the size the repository reported, used for progress when
     *   the route does not send a `Content-Length` (the proxies frequently do not).
     * @param onProgress called with 0f..1f. It is only called when a total is
     *   known, so the UI never shows a progress bar that cannot move.
     * @return the number of bytes written.
     */
    suspend fun downloadTo(
        source: LibrarySource,
        path: String,
        target: File,
        expectedSize: Long = 0L,
        onProgress: (Float) -> Unit = {},
        onStage: (String) -> Unit = {},
    ): Long = withContext(Dispatchers.IO) {
        val preferred = pickMirror(source, path, onStage)
        withMirrors(preferred) { mirror ->
            onStage("正在下载…")
            streamToFile(
                mirror.rawUrl(source, path, prefs.mirrorPrefix),
                target,
                expectedSize,
                onProgress,
            )
        }
    }

    private suspend fun streamToFile(
        url: String,
        target: File,
        expectedSize: Long,
        onProgress: (Float) -> Unit,
    ): Long {
        // Write to a sibling temp file and rename on success, so an interrupted
        // transfer can never be mistaken for a complete book.
        val tmp = File(target.parentFile, target.name + ".part")
        target.parentFile?.mkdirs()
        tmp.delete()

        try {
            Http.execute(request(url), TRANSFER_TIMEOUT_MS).use { resp ->
                if (!resp.isSuccessful) throw LibraryException(httpMessage(resp.code))
                val body = resp.body ?: throw LibraryException("下载失败：内容为空")
                // A proxy that re-encodes the stream reports -1 here; the size from
                // the repository listing is the reliable one.
                val declared = body.contentLength()
                val total = if (declared > 0) declared else expectedSize
                var read = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                if (read == 0L) throw LibraryException("下载失败：内容为空")
                // A truncated transfer that still closed cleanly is the one
                // failure mode the user cannot see: it produces a book that opens
                // and then ends mid-sentence. When the repository told us the size,
                // insist on it.
                if (declared <= 0 && expectedSize > 0 && read != expectedSize) {
                    throw LibraryException("下载不完整（$read / $expectedSize 字节），请重试")
                }
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
     * Fetch a byte range of a book and append it to [target].
     *
     * This is what makes online reading start in seconds instead of after a 75 MB
     * download: the reader asks for a prefix, shows it, and the rest arrives
     * behind the reader's back. It is also how an interrupted read resumes --
     * [offset] is however much is already on disk.
     *
     * Three answers have to be told apart, because the proxies in front of
     * `raw.githubusercontent.com` do not all behave:
     *
     *  * **206 Partial Content** -- the normal case. `Content-Range` carries the
     *    file's real size, which is the only way to learn it when the listing did
     *    not report one.
     *  * **200 OK** -- the route ignored `Range` and is sending the file from the
     *    start. The copy is capped at [maxBytes] and the connection is dropped, so
     *    a proxy that cannot do ranges costs one prefix rather than the whole
     *    book; the caller is told via [RangeResult.wholeFile] so it rewrites
     *    rather than appends.
     *  * **416 Range Not Satisfiable** -- there is nothing at [offset]. That is
     *    the honest "the file ends here" answer, not an error.
     *
     * @param maxBytes most bytes to append. Zero or less means "to the end".
     * @return how much arrived and what the route said about the total size.
     */
    suspend fun fetchRange(
        source: LibrarySource,
        path: String,
        offset: Long,
        maxBytes: Long,
        target: File,
        expectedSize: Long = 0L,
        onProgress: (Float) -> Unit = {},
        onStage: (String) -> Unit = {},
    ): RangeResult = withContext(Dispatchers.IO) {
        val preferred = pickMirror(source, path, onStage)
        withMirrors(preferred) { mirror ->
            streamRange(
                mirror.rawUrl(source, path, prefs.mirrorPrefix),
                offset = offset,
                maxBytes = maxBytes,
                target = target,
                expectedSize = expectedSize,
                onProgress = onProgress,
            )
        }
    }

    private suspend fun streamRange(
        url: String,
        offset: Long,
        maxBytes: Long,
        target: File,
        expectedSize: Long,
        onProgress: (Float) -> Unit,
    ): RangeResult {
        target.parentFile?.mkdirs()
        val range = if (maxBytes > 0L) {
            "bytes=$offset-${offset + maxBytes - 1}"
        } else {
            "bytes=$offset-"
        }

        Http.execute(request(url, rangeHeader = range), TRANSFER_TIMEOUT_MS).use { resp ->
            if (resp.code == 416) {
                // Past the end of the file. `Content-Range: bytes */12345` states
                // the real size; when it does not, the caller falls back to the
                // size the listing reported.
                val total = PartialRead.parseContentRange(resp.header("Content-Range"))?.total ?: 0L
                return RangeResult(bytes = 0L, total = total, wholeFile = false)
            }
            if (!resp.isSuccessful) throw LibraryException(httpMessage(resp.code))
            val body = resp.body ?: throw LibraryException("下载失败：内容为空")

            val wholeFile = resp.code == 200
            val contentRange = PartialRead.parseContentRange(resp.header("Content-Range"))
            val declared = body.contentLength()
            // A range answer states the file's full size; a whole-file answer
            // states its own length, which is the same thing.
            val total = contentRange?.total?.takeIf { it > 0L }
                ?: declared.takeIf { it > 0L }
                ?: expectedSize

            // Appending to a file that this response is about to rewrite from the
            // beginning would interleave two copies of the book. This happens when
            // a route that cannot do ranges is used to resume, and when a range
            // request is answered with the whole file.
            if (wholeFile || offset == 0L) {
                if (target.exists() && !target.delete()) {
                    target.writeBytes(ByteArray(0))
                }
            }

            var written = 0L
            var received = 0L
            body.byteStream().use { input ->
                java.io.FileOutputStream(target, true).use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        received += n
                        // A route that ignored the range is capped here rather
                        // than after the whole file has crossed the network.
                        val room = if (maxBytes > 0L) maxBytes - written else Long.MAX_VALUE
                        if (room <= 0L) break
                        val take = minOf(n.toLong(), room).toInt()
                        out.write(buf, 0, take)
                        written += take
                        if (total > 0L) onProgress(((offset + written).toFloat() / total).coerceIn(0f, 1f))
                        if (take < n) break
                    }
                }
            }
            if (written == 0L) {
                // A 200 that carried nothing means the file is empty or the route
                // returned an error page; either way there is no book here.
                if (received == 0L) throw LibraryException("下载失败：内容为空")
            }
            return RangeResult(bytes = written, total = total, wholeFile = wholeFile)
        }
    }

    /**
     * Fetch a whole file into memory.
     *
     * Used only for formats that genuinely need random access to the bytes -- EPUB
     * is a zip. Text formats stream to a file and are decoded from it, so a 75 MB
     * novel never has to exist as a byte array.
     */
    suspend fun fetchBytes(
        source: LibrarySource,
        path: String,
        limit: Long,
        onStage: (String) -> Unit = {},
    ): ByteArray = withContext(Dispatchers.IO) {
        val preferred = pickMirror(source, path, onStage)
        withMirrors(preferred) { mirror ->
            download(mirror.rawUrl(source, path, prefs.mirrorPrefix), limit)
        }
    }

    private suspend fun download(url: String, limit: Long): ByteArray {
        Http.execute(request(url), TRANSFER_TIMEOUT_MS).use { resp ->
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
     *
     * @param timeoutMs a whole-call ceiling. The update check passes a short one:
     *   "no answer" and "no update" are the same outcome to the user, and waiting
     *   a minute to learn it is worse than saying so quickly.
     * @param noCache ask every hop to revalidate, and defeat the proxies that
     *   ignore that. Used by the update check, where a cached copy of the manifest
     *   is indistinguishable from "no update available".
     */
    suspend fun fetchRaw(
        url: String,
        timeoutMs: Long = RAW_TIMEOUT_MS,
        noCache: Boolean = false,
    ): String =
        withContext(Dispatchers.IO) {
            val target = if (noCache) bustCache(url) else url
            val candidates = buildList {
                add(target)
                if (target.startsWith("https://raw.githubusercontent.com/")) {
                    add("https://ghproxy.net/$target")
                    add("https://gh-proxy.com/$target")
                    add("https://ghfast.top/$target")
                }
            }
            val (body, _) = race(candidates, ::hostOf) { get(it, timeoutMs, noCache) }
            body
        }

    /**
     * Append a per-call nonce to [url].
     *
     * Headers alone are not enough: the GitHub proxies are caching layers that
     * variously ignore `Cache-Control`, and the failure this prevents is silent --
     * a stale manifest reads as a perfectly valid "no update available". A unique
     * query string makes the cached entry unaddressable, which no cache can
     * defeat. Existing query parameters are preserved.
     */
    private fun bustCache(url: String): String {
        val sep = if (url.contains('?')) '&' else '?'
        return "$url${sep}_readon=${System.currentTimeMillis()}"
    }

    /**
     * Probe every file route and report which ones answer.
     *
     * Backs the "检测线路" button. On a blocked network the user cannot tell a
     * broken app from a blocked host; this is what makes the difference visible.
     *
     * The probes run concurrently: sequentially, a blocked network makes this
     * take nine timeouts, which reads as a hang rather than a diagnostic.
     */
    suspend fun probeRoutes(source: LibrarySource, path: String): List<Pair<String, Boolean>> =
        withContext(Dispatchers.IO) {
            val results = Channel<Pair<String, Boolean>>(Channel.UNLIMITED)
            coroutineScope {
                Mirror.RACE_ORDER.forEach { mirror ->
                    launch {
                        val url = mirror.rawUrl(source, path, prefs.mirrorPrefix)
                        val ok = runCatching { probeRange(url) }.getOrDefault(false)
                        results.send(mirror.label to ok)
                    }
                }
                val out = ArrayList<Pair<String, Boolean>>(Mirror.RACE_ORDER.size)
                repeat(Mirror.RACE_ORDER.size) { out.add(results.receive()) }
                results.close()
                // Report in the declared order rather than in completion order.
                Mirror.RACE_ORDER.map { m -> out.first { it.first == m.label } }
            }
        }

    // -----------------------------------------------------------------------
    // Racing
    // -----------------------------------------------------------------------

    /**
     * Try every candidate at once and return the first success.
     *
     * For the listing and small documents only: those payloads are ~13 KB, so
     * racing all of them costs less than a single page of a book. File transfers
     * go through [pickMirror] and [withMirrors] instead, because racing whole
     * files would multiply bandwidth by the number of hosts.
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

    private suspend fun get(url: String, timeoutMs: Long? = null, noCache: Boolean = false): String {
        Http.execute(request(url, noCache = noCache), timeoutMs).use { resp ->
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
        /** Online-reading cap, for the formats that must be buffered whole. */
        const val MAX_TEXT_BYTES = 64L * 1024 * 1024

        /** A probe that has not answered in this long is not going to. */
        private const val PROBE_MS = 15_000L

        /** The remembered route gets a shorter leash before the race starts. */
        private const val FAST_PROBE_MS = 8_000L

        /** Listing payloads are ~13 KB; they should arrive quickly or not at all. */
        private const val LIST_TIMEOUT_MS = 25_000L

        /** A whole book transfer. Generous, but still a ceiling. */
        private const val TRANSFER_TIMEOUT_MS = 10 * 60 * 1000L

        /** Small documents (update manifest, release JSON). */
        private const val RAW_TIMEOUT_MS = 20_000L

        /** The window a route probe asks for. */
        private const val PROBE_RANGE = "bytes=0-2047"
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
