package com.readon.reader.data

import com.readon.reader.net.GithubClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fetches a book from the network *progressively*, and finishes it in the
 * background.
 *
 * ## Why not "download it, then show it"
 *
 * That was the old behaviour, and it is the reason online reading of anything
 * large was unusable: this library's books reach 75 MB, so the reader sat on
 * "正在下载… 3%" for minutes -- on exactly the networks where the mirror racing in
 * [GithubClient] exists because GitHub is barely reachable. Reading the first
 * page does not need the last chapter to be on the device.
 *
 * So a read now costs one ranged request: [ensureReadable] fetches a prefix
 * ([PREFIX_BYTES]), the reader opens on it immediately, and [finish] pulls the
 * rest down behind the reader's back. When the whole file has arrived it is
 * promoted into [OnlineCache], so the next open is local.
 *
 * ## Why this lives at application scope
 *
 * The finishing download must outlive the screen that started it. A reader who
 * opens a book, reads two pages and goes back to the shelf has not asked for the
 * download to be cancelled -- the point of the feature is that the *next* open is
 * instant. It also means the in-flight bookkeeping below has a single owner: two
 * concurrent writers appending to the same staging file would interleave two
 * copies of the book, so [finish] joins the running job instead of starting a
 * second one.
 *
 * The file a read uses is a `.part` staging file, so an interrupted download is
 * never mistaken for a complete book, and a later read *resumes* from however
 * much of it is already on disk.
 */
class OnlineFetcher(
    private val client: GithubClient,
    /** Where a completed file is kept so a re-read is local. May be absent. */
    private val cache: OnlineCache?,
    /** Where a book lives when there is no cache to promote it into. */
    private val scratchDir: File,
    /** Runs the background completion; must outlive any screen. */
    private val scope: CoroutineScope,
) {

    /** A copy of a book on disk, and whether it is the whole thing. */
    data class Fetched(
        val file: File,
        /** True when every byte of the book is present. */
        val complete: Boolean,
        val loaded: Long,
        /** The full size, or 0 when no route stated one. */
        val total: Long,
    )

    /** Books whose remainder is being fetched right now, keyed by book id. */
    private val jobs = HashMap<String, Deferred<File?>>()

    /**
     * A complete copy of [book] already on disk, or null.
     *
     * Asked before anything touches the network: a book that has been read to the
     * end once -- or downloaded to the shelf -- must never be fetched again.
     */
    suspend fun completeCopy(book: Book): File? = cache?.get(book)

    /**
     * The staging file for [book]: where a partial (or in-progress) copy lives.
     *
     * Deliberately the cache's own `.part` file when there is a cache, so a
     * completed download is promoted by a rename rather than a second copy of a
     * file that can be 75 MB.
     */
    fun stagingFile(book: Book): File {
        cache?.let { return it.partFileFor(book) }
        scratchDir.mkdirs()
        return File(scratchDir, "online-${book.id.hashCode()}.${book.format}")
    }

    /**
     * Make sure at least a readable prefix of [book] is on disk, and report what
     * is there.
     *
     * The prefix is [PREFIX_BYTES] or the whole book, whichever is smaller -- a
     * book that would have arrived in one piece is fetched in one piece, because
     * stopping early there only adds a round trip.
     *
     * A prefix left by an earlier, interrupted read is resumed rather than
     * discarded: that is what makes a dropped connection cost one request instead
     * of the whole book.
     */
    suspend fun ensureReadable(book: Book, onStage: (String) -> Unit = {}): Fetched {
        val staging = stagingFile(book)
        val expected = book.size
        var have = if (staging.exists()) staging.length() else 0L
        var total = expected

        if (PartialRead.isComplete(have, total)) {
            return Fetched(staging, complete = true, loaded = have, total = total)
        }

        val want = PartialRead.firstChunk(expected, PREFIX_BYTES)
        if (want > 0L && have < want) {
            onStage("正在下载…")
            val result = client.fetchRange(
                source = book.source,
                path = book.path,
                offset = have,
                maxBytes = want - have,
                target = staging,
                expectedSize = expected,
                onProgress = { p -> onStage("正在下载… ${(p * 100).toInt()}%") },
                onStage = onStage,
            )
            if (result.total > 0L) total = result.total
            // A route that ignored the `Range` header rewrote the file from the
            // beginning, so what is on disk is its own length -- not what was
            // there before plus what arrived.
            have = if (result.wholeFile) result.bytes else have + result.bytes
        }

        if (have <= 0L) {
            throw com.readon.reader.net.LibraryException("下载失败：没有收到内容，请重试")
        }
        return Fetched(staging, PartialRead.isComplete(have, total), have, total)
    }

    /**
     * Fetch the rest of [book] at application scope and report when it is done.
     *
     * The returned [Deferred] belongs to the caller for *awaiting* only: the
     * transfer itself runs on [scope], so going back to the shelf does not cancel
     * it. The point of the feature is that the next open is instant, and a reader
     * who reads two pages and leaves has not asked for the download to stop.
     *
     * One download per book: a second reader opening the same book while the
     * first is still fetching gets the same job, rather than a competing writer
     * appending to the same staging file -- which would interleave two copies of
     * the book.
     */
    fun finish(book: Book): Deferred<File?> = start(book)

    /**
     * The complete file for [book], starting (or joining) the transfer and
     * waiting for it.
     *
     * Null when the transfer failed or has not finished: the reader keeps the
     * prefix it already has, and the next open resumes from the staging file.
     *
     * Cancellation is *not* turned into null. The awaiting coroutine belongs to
     * the reader's screen, so it is cancelled whenever the reader leaves the book
     * -- and swallowing that would leave the coroutine running after it was told
     * to stop. The transfer itself is unaffected either way: it runs on the
     * application scope, which is the point.
     */
    suspend fun awaitComplete(book: Book): File? =
        try {
            start(book).await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }

    private fun start(book: Book): Deferred<File?> {
        synchronized(jobs) {
            jobs[book.id]?.let { if (it.isActive) return it }
            val job = scope.async {
                try {
                    withContext(Dispatchers.IO) { downloadRest(book) }
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // The process is going away; the staging file stays for next
                    // time, which is exactly what makes a resume cheap.
                    null
                } catch (_: Throwable) {
                    // The reader keeps the prefix it already has.
                    null
                } finally {
                    synchronized(jobs) { jobs.remove(book.id) }
                }
            }
            jobs[book.id] = job
            return job
        }
    }

    /** Keep bytes that were fetched whole, for a format that needs random access. */
    suspend fun storeWhole(book: Book, bytes: ByteArray): File? =
        cache?.let { c -> runCatching { c.store(book, bytes) }.getOrNull() }

    /** Move a completed staging file into the cache, so the next open is local. */
    suspend fun promote(book: Book, file: File) {
        val c = cache ?: return
        // A no-op when `file` is already the cache's own file: commit would rename
        // it onto itself.
        if (file == c.fileFor(book)) return
        runCatching { c.commit(book, file) }
    }

    /** Fetch everything after what is already on disk. Returns null on failure. */
    private suspend fun downloadRest(book: Book): File? {
        val staging = stagingFile(book)
        var have = if (staging.exists()) staging.length() else 0L
        var total = book.size

        // One extra request when the size is unknown tells us where the end is:
        // a range past the end answers 416 with the real length, and a short read
        // answers with fewer bytes than asked for.
        var guard = 0
        while (guard++ < MAX_CHUNKS) {
            val result = client.fetchRange(
                source = book.source,
                path = book.path,
                offset = have,
                maxBytes = CHUNK_BYTES,
                target = staging,
                expectedSize = total,
                onStage = {},
            )
            if (result.total > 0L) total = result.total

            if (result.wholeFile) {
                // The route ignored the `Range` header and sent the file from the
                // beginning, so ranged fetching cannot finish here -- asking again
                // would rewrite the same prefix forever. Fall back to one straight
                // whole-file download, which the client already knows how to do
                // and which retries the other hosts.
                return fullDownload(book, staging)
            }

            val before = have
            have += result.bytes
            if (result.bytes <= 0L) break
            if (PartialRead.isComplete(have, total)) break
            // No progress means the same request would repeat itself.
            if (have <= before) break
        }

        if (!PartialRead.isComplete(have, total)) return null

        // Promote into the cache so the next open is local. Best effort: a cache
        // that cannot be written must not cost the reader the book they have.
        val promoted = cache?.let { c -> runCatching { c.commit(book, staging) }.getOrNull() }
        return promoted ?: staging
    }

    /**
     * Fetch the whole book in one transfer.
     *
     * The fallback for a route that cannot do ranges, and the answer for a book
     * whose size is unknown and whose prefix request never reached the end.
     * [GithubClient.downloadTo] streams to disk and verifies the length against
     * the repository's own figure, so this is the same path the shelf download
     * uses -- and it selects its own route, so a host that ignores `Range` is not
     * necessarily the host that carries this.
     */
    private suspend fun fullDownload(book: Book, staging: File): File? {
        val result = runCatching {
            client.downloadTo(
                source = book.source,
                path = book.path,
                target = staging,
                expectedSize = book.size,
                onStage = {},
            )
        }.getOrNull() ?: return null
        if (result <= 0L) return null
        val promoted = cache?.let { c -> runCatching { c.commit(book, staging) }.getOrNull() }
        return promoted ?: staging
    }

    private companion object {
        /**
         * How much is fetched before the book is shown.
         *
         * 2 MB of Chinese prose is roughly 700,000 characters -- well over a
         * thousand pages at a phone's page size, so the reader is not going to
         * reach the end of the prefix before the background transfer has caught
         * up. Smaller would risk a visible stall at the prefix's edge; larger
         * would put back most of the wait this exists to remove.
         */
        const val PREFIX_BYTES = 2L * 1024 * 1024

        /** How much of the remainder is asked for per request. */
        const val CHUNK_BYTES = 4L * 1024 * 1024

        /**
         * A ceiling on the number of ranged requests one book may take.
         *
         * A 75 MB book is twenty 4 MB requests. The limit is well above that, and
         * it exists so a route that answers without advancing cannot spin forever.
         */
        const val MAX_CHUNKS = 64
    }
}
