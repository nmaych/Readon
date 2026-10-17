package com.readon.reader.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Books that were read online, kept so they are not downloaded twice.
 *
 * ## Why this is not the shelf
 *
 * A book read online is *not* a downloaded book, and the two must not be
 * confused: the shelf is what the user deliberately kept, with reading progress
 * and bookmarks attached, and "清空已下载的书" is a destructive action they expect
 * to be complete. This cache is the opposite kind of thing -- it exists only to
 * make a second read cheap, it is disposable at any moment, and clearing it must
 * cost the user nothing but bandwidth. So it lives in a separate directory, under
 * `cacheDir` rather than `filesDir`, with its own size accounting and its own
 * clear button. Android may also evict `cacheDir` under storage pressure, which
 * is exactly the right behaviour for something whose only job is speed.
 *
 * ## What it saves
 *
 * Without it, opening the same online book twice downloads it twice -- and this
 * library's books reach 75 MB, on networks where the whole reason for the mirror
 * racing in [com.readon.reader.net.GithubClient] is that GitHub is barely
 * reachable. Re-reading a book is the normal case, so the second read was the
 * slow one for no reason.
 *
 * ## What is stored, and what is not
 *
 * The *raw file bytes* as downloaded, keyed by book id. Deliberately not the
 * decoded text or the chapter split: those are cheap to recompute from the bytes
 * (the expensive part is the network), they are several times larger, and caching
 * them would mean a cache that has to be invalidated whenever the reader's
 * parsing changes rather than only when the book changes.
 *
 * The id is hashed rather than used as a filename: ids contain `/`, `@` and
 * non-ASCII folder names (`默认/书名.txt`), which are not safe path segments.
 */
class OnlineCache(private val context: Context) {

    /** A private directory, so nothing here can be mistaken for a downloaded book. */
    private val dir: File get() = File(context.cacheDir, "online-books")

    /**
     * Where [book] is cached, whether or not the file is there yet.
     *
     * The name is a digest of the id rather than the id itself, because an id is
     * `owner/repo@branch/默认/书名.txt` -- it contains `/`, `@` and non-ASCII
     * folder names, none of which are safe as a path segment. The digest is stable
     * across launches, so the same book always maps to the same file, and the
     * format extension is kept so the directory is readable when inspected by hand.
     */
    fun fileFor(book: Book): File = File(dir, "${stableHash(book.id)}.${book.format}")

    /**
     * The cached copy of [book], or null when there is none worth using.
     *
     * Touches the file's last-modified time on a hit, which is what makes
     * [OnlineCachePolicy.evict] a *least-recently-used* policy rather than a
     * least-recently-written one. Without the touch, the book the user re-reads
     * most would be evicted first, because its file would be the oldest.
     */
    suspend fun get(book: Book): File? = withContext(Dispatchers.IO) {
        val f = fileFor(book)
        if (!OnlineCachePolicy.isUsable(f.length(), book.size)) {
            // A zero-length or size-mismatched file is residue: an interrupted
            // write, or a copy of a version of the book that has since changed.
            // Removing it here means the caller's download has a clean target.
            if (f.exists()) runCatching { f.delete() }
            return@withContext null
        }
        // Best effort: failing to update the timestamp must not fail the read.
        runCatching { f.setLastModified(System.currentTimeMillis()) }
        f
    }

    /**
     * Which of [books] already have a complete copy here.
     *
     * Deliberately separate from [get]. [get] answers "hand me the bytes to
     * read": it is per-book, it touches the file's timestamp so that eviction
     * stays least-recently-*used*, and it deletes residue it finds. This answers
     * "show the user what is already on the device", which is a question about a
     * whole listing and must have none of those side effects -- touching the
     * timestamp here would let a scroll through 主页 rewrite the eviction order,
     * and deleting here would take that decision away from the read that has all
     * the context.
     *
     * One directory listing is matched by name rather than stat-ing every
     * expected path: a library can hold hundreds of books and almost none of them
     * are cached. `.part` files can never match, because [fileFor] names only the
     * finished file.
     */
    suspend fun cachedIds(books: List<Book>): Set<String> = withContext(Dispatchers.IO) {
        val present = dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(PART_SUFFIX) }
            ?.associateBy { it.name }
            ?: return@withContext emptySet()
        books.filter { book ->
            val file = present[fileFor(book).name] ?: return@filter false
            OnlineCachePolicy.isUsable(file.length(), book.size)
        }.mapTo(HashSet()) { it.id }
    }

    /**
     * Where a fresh download should be written before it is promoted.
     *
     * A sibling `.part` file, promoted by [commit] once the bytes are complete.
     * Writing straight to [fileFor] would mean an interrupted download leaves a
     * file that a later read could mistake for a complete book -- the same
     * discipline [com.readon.reader.net.GithubClient] uses for shelf downloads.
     */
    fun partFileFor(book: Book): File {
        dir.mkdirs()
        return File(dir, partNameFor(book))
    }

    /** The `.part` sibling's name, without touching the filesystem. */
    private fun partNameFor(book: Book): String = fileFor(book).name + PART_SUFFIX

    /** Promote a completed [part] file to the cache. Returns the cached file. */
    suspend fun commit(book: Book, part: File): File = withContext(Dispatchers.IO) {
        val target = fileFor(book)
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        runCatching { target.setLastModified(System.currentTimeMillis()) }
        trim()
        target
    }

    /**
     * Cache bytes that were fetched whole, for formats that need random access.
     *
     * EPUB is read through [com.readon.reader.net.GithubClient.fetchBytes] because
     * a zip cannot be streamed from a socket, so there is no part file to promote
     * -- the bytes are simply written out.
     */
    suspend fun store(book: Book, bytes: ByteArray): File = withContext(Dispatchers.IO) {
        val part = partFileFor(book)
        try {
            part.writeBytes(bytes)
            commit(book, part)
        } finally {
            // A failed write must not leave a fragment behind: `trim` would have
            // to reason about it, and a later read could find it.
            runCatching { if (part.exists()) part.delete() }
        }
    }

    /** Total bytes held by the cache. */
    suspend fun usedBytes(): Long = withContext(Dispatchers.IO) {
        dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
    }

    /** How many books are cached. */
    suspend fun count(): Int = withContext(Dispatchers.IO) {
        dir.listFiles()?.count { it.isFile && !it.name.endsWith(PART_SUFFIX) } ?: 0
    }

    /** Drop everything, including the remains of an interrupted download. */
    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        runCatching { dir.delete() }
    }

    /**
     * Forget [book], and anything left of a partial read of it.
     *
     * Called when the book has been downloaded to the shelf: the shelf copy is
     * the one that will be read from now on, so keeping a second full copy of a
     * file that can be 75 MB in the cache is pure waste. It is not an error if
     * there was nothing there -- a book downloaded without ever being read online
     * has no cache entry at all.
     */
    suspend fun remove(book: Book): Unit = withContext(Dispatchers.IO) {
        runCatching { fileFor(book).delete() }
        // Not `partFileFor`, which creates the directory: deleting something that
        // may not exist must not be the reason a cache directory appears.
        runCatching { File(dir, partNameFor(book)).delete() }
    }

    /**
     * Bring the cache back under [MAX_BYTES], oldest read first.
     *
     * Called after each successful write rather than on a timer: the only moment
     * the cache can grow is a commit, so that is the only moment worth checking,
     * and doing it inline keeps the size bounded without a background job the
     * process would have to own.
     */
    private fun trim() {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        if (files.isEmpty()) return

        val entries = files.map { f ->
            OnlineCachePolicy.Entry(
                key = f.name,
                size = f.length(),
                lastUsed = f.lastModified(),
            )
        }
        for (name in OnlineCachePolicy.evict(entries, MAX_BYTES)) {
            runCatching { File(dir, name).delete() }
        }
    }

    /**
     * A name that is stable across launches and unique per book.
     *
     * `String.hashCode()` is specified by the JVM and stable, but it is only 32
     * bits, and two books colliding would silently serve the wrong text -- a
     * reader opening 《三体》 and getting a different novel. 64 bits of SHA-256 is
     * not a cryptographic boundary (this is a cache of public book files), just a
     * name two books are not going to share. Hex, so the name is a legal path
     * segment on every filesystem.
     */
    private fun stableHash(id: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(id.toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PART_SUFFIX = ".part"

        /**
         * Cache ceiling: 512 MB.
         *
         * Large enough to hold several of this library's biggest books (the
         * largest is 75 MB) so that re-reading a long novel actually hits, and
         * small enough that a phone's storage is not consumed by a feature the
         * user never asked to keep. The user can clear it at any time from 设置,
         * and Android may reclaim it under pressure regardless.
         */
        const val MAX_BYTES = 512L * 1024 * 1024
    }
}
