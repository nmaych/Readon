package com.readon.reader.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The downloaded library.
 *
 * Kept as a single JSON index beside the book files rather than in a database:
 * the whole record set is a few hundred entries at most, it is always read and
 * written whole, and a JSON file can be inspected and repaired by hand when a
 * user reports that a book "disappeared".
 *
 * Every mutation rewrites the index through [write], so a crash mid-write can
 * only ever lose the newest change, never the file -- see the temp-file rename.
 *
 * ## Why there is a mutex
 *
 * Downloads, imports, the reader's progress writes and the shelf's reads can all
 * run at the same time -- the reader persists a position on every page turn
 * while a download finishes in the background. Every one of them is a
 * read-modify-write of the same JSON file, so without serialisation a progress
 * write could resurrect the record list it read a moment before an import landed,
 * silently dropping the newly imported book. [lock] makes the whole
 * read-modify-write atomic.
 */
class BookStore(private val context: Context) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val indexFile: File get() = File(context.filesDir, "library.json")
    val booksDir: File get() = File(context.filesDir, "books")

    private val type = object : TypeToken<MutableList<Book>>() {}.type

    private val lock = Mutex()

    private val _revision = MutableStateFlow(0)

    /**
     * Bumped whenever the shelf's *contents* change -- a book added, removed or
     * re-registered.
     *
     * Screens observe this instead of polling. Deliberately not bumped by
     * [updateProgress]: the reader writes progress on every page turn, and a
     * signal there would make the shelf re-read the whole index from disk dozens
     * of times a minute while the user is reading.
     *
     * It *is* bumped by [setGroup] and [setFavorite], because a book's group and
     * its star are part of what the shelf draws. Nothing in the reader writes
     * those, so there is no per-page-turn write to worry about.
     */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** All downloaded books, newest addition first among equals. */
    suspend fun load(): List<Book> = withContext(Dispatchers.IO) {
        lock.withLock { readIndex().toList() }
    }

    private fun readIndex(): MutableList<Book> {
        if (!indexFile.exists()) return mutableListOf()
        return try {
            val json = indexFile.readText()
            if (json.isBlank()) mutableListOf()
            else gson.fromJson<MutableList<Book>>(json, type) ?: mutableListOf()
        } catch (_: Exception) {
            // A corrupt index must not brick the shelf. The book files are still
            // on disk; the user can re-download, and doing that overwrites the
            // index with a good one.
            mutableListOf()
        }
    }

    private fun write(books: List<Book>) {
        indexFile.parentFile?.mkdirs()
        val tmp = File(indexFile.parentFile, "library.json.tmp")
        tmp.writeText(gson.toJson(books, type))
        // renameTo replaces an existing target on Android's filesystems, so the
        // index is never absent, not even for an instant.
        if (!tmp.renameTo(indexFile)) {
            if (indexFile.exists()) indexFile.delete()
            if (!tmp.renameTo(indexFile)) {
                // Last resort, so a device whose filesystem refuses the rename
                // still ends up with a usable index.
                tmp.copyTo(indexFile, overwrite = true)
                tmp.delete()
            }
        }
    }

    /**
     * Where a book's file belongs on disk.
     *
     * Mirrors the repository's folder structure. The name is derived from the path
     * so a re-download overwrites rather than accumulating duplicates, and the id
     * is stable so reading progress carries over.
     */
    fun targetFileFor(book: Book): File {
        val source = book.source
        val dir = File(booksDir, "${source.owner}_${source.repo}/${book.folder}")
        dir.mkdirs()
        return File(dir, sanitize(book.title) + "." + book.format)
    }

    /**
     * Record a book whose bytes are already on disk.
     *
     * Separate from [save] because downloads stream straight to the destination
     * file: this library's books reach 75 MB, and buffering one in memory to hand
     * it to a write is how a reader runs out of heap on a mid-range phone.
     *
     * @param sizeBytes the file's size, already written.
     */
    suspend fun register(book: Book, file: File, sizeBytes: Long): Book =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val record = book.copy(
                    localFile = file.absolutePath,
                    downloadedAt = System.currentTimeMillis(),
                    size = sizeBytes,
                )
                val current = readIndex()
                val idx = current.indexOfFirst { it.id == record.id }
                if (idx >= 0) {
                    // Preserve reading position across a re-download.
                    val old = current[idx]
                    current[idx] = record.copy(
                        progress = old.progress,
                        charOffset = old.charOffset,
                        chapterIndex = old.chapterIndex,
                        chapterCount = old.chapterCount,
                        lastReadAt = old.lastReadAt,
                        // The user's own shelf decisions belong to the *book*, not
                        // to the copy of its bytes that was just replaced: a
                        // re-download must not quietly move a book out of its group
                        // or unstar it.
                        group = old.group,
                        favorite = old.favorite,
                    )
                } else {
                    current.add(record)
                }
                write(current)
                _revision.value = _revision.value + 1
                current.first { it.id == record.id }
            }
        }

    /**
     * Move an already-copied file into the shelf and record it.
     *
     * Takes a [File] rather than a `ByteArray` so a large import is never held in
     * memory: [LocalImport] streams the picked document to a scratch file and this
     * moves that file into place. The move is a rename inside the same filesystem,
     * which is why the scratch file lives in the app's own cache directory.
     *
     * @param sizeBytes the copied file's size, in bytes.
     */
    suspend fun importLocalFile(
        displayName: String,
        source: File,
        format: BookFormat,
        sizeBytes: Long,
    ): Book = withContext(Dispatchers.IO) {
        lock.withLock {
            val title = displayName.substringBeforeLast('.').ifBlank { displayName }
            val id = "local/$displayName"

            val dir = File(booksDir, LOCAL_FOLDER)
            dir.mkdirs()
            val target = File(dir, "${sanitize(title)}.${format.ext}")

            // Overwrite any previous copy of the same title, then rename the
            // scratch file into place. A rename across directories is still
            // atomic; if the filesystem refuses it, fall back to a copy.
            if (target.exists()) target.delete()
            if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = true)
                source.delete()
            }

            val record = Book(
                id = id,
                title = title,
                author = null,
                folder = LOCAL_FOLDER,
                path = displayName,
                format = format.ext,
                size = sizeBytes,
                owner = LOCAL_OWNER,
                repo = LOCAL_REPO,
                branch = LOCAL_BRANCH,
                localFile = target.absolutePath,
                downloadedAt = System.currentTimeMillis(),
            )

            val current = readIndex()
            val idx = current.indexOfFirst { it.id == id }
            if (idx >= 0) {
                // Keep the reading position across a re-import of the same title.
                val old = current[idx]
                current[idx] = record.copy(
                    progress = old.progress,
                    charOffset = old.charOffset,
                    chapterIndex = old.chapterIndex,
                    chapterCount = old.chapterCount,
                    lastReadAt = old.lastReadAt,
                    // Same rule as a re-download: re-importing a file the user
                    // already had must not move it out of its group.
                    group = old.group,
                    favorite = old.favorite,
                )
            } else {
                current.add(record)
            }
            write(current)
            _revision.value = _revision.value + 1
            current.first { it.id == id }
        }
    }

    /** Record reading progress without touching the file. */
    suspend fun updateProgress(
        id: String,
        progress: Float,
        charOffset: Int,
        chapterIndex: Int,
        /**
         * How many chapters the book has, as the reader counted them.
         *
         * Passed in rather than derived here: the reader has already built the
         * outline, and counting it again would mean decoding the whole book from
         * the shelf. Zero (the default) leaves whatever was stored alone, so a
         * caller that does not know the total cannot erase it.
         */
        chapterCount: Int = 0,
    ): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = readIndex()
            val idx = current.indexOfFirst { it.id == id }
            if (idx < 0) return@withLock
            current[idx] = current[idx].copy(
                progress = progress.coerceIn(0f, 1f),
                charOffset = charOffset.coerceAtLeast(0),
                chapterIndex = chapterIndex,
                chapterCount = if (chapterCount > 0) chapterCount
                else current[idx].chapterCount,
                lastReadAt = System.currentTimeMillis(),
            )
            write(current)
        }
    }

    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = readIndex()
            val idx = current.indexOfFirst { it.id == id }
            if (idx < 0) return@withLock
            val book = current[idx]
            book.localFile?.let { runCatching { File(it).delete() } }
            current.removeAt(idx)
            write(current)
            _revision.value = _revision.value + 1
        }
    }

    /**
     * Delete several books in one pass over the index.
     *
     * One read, one write, one revision bump rather than N of each. The batch
     * shelf has to delete a selection that can be the whole library, and doing
     * that one `delete` at a time would rewrite a JSON index -- and bump the
     * revision the shelf observes -- a few hundred times in a row, so the grid
     * would re-read and re-sort itself once per deleted book while the user
     * watched.
     *
     * Files are removed before the index is written, and a file that cannot be
     * removed does not stop the record from being dropped: a record pointing at a
     * file that is still there is the *worse* outcome (it would reappear on the
     * shelf and be unopenable). The stray bytes are reported by [usedBytes]
     * until the next `clearAll`.
     *
     * @return the ids that were actually found and removed, so the caller can
     *   clear their bookmarks and report an honest count.
     */
    suspend fun deleteAll(ids: Collection<String>): List<String> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        lock.withLock {
            val wanted = ids.toSet()
            val current = readIndex()
            val removed = ArrayList<String>(wanted.size)
            val kept = ArrayList<Book>(current.size)
            for (book in current) {
                if (book.id in wanted) {
                    book.localFile?.let { runCatching { File(it).delete() } }
                    removed.add(book.id)
                } else {
                    kept.add(book)
                }
            }
            if (removed.isEmpty()) return@withLock emptyList()
            write(kept)
            _revision.value = _revision.value + 1
            removed
        }
    }

    /**
     * Put books into a group, or take them out of one with `group == null`.
     *
     * The name is canonicalised through [ShelfGroups.normalize] here rather than
     * at the call site: it is the one place every write passes through, and a
     * group whose name differs by a trailing space would render as a second
     * heading identical to the first -- which the user cannot see, let alone fix.
     *
     * @return how many books changed, so the caller's message can say "已加入 3 本"
     *   rather than claiming to have moved books that were already there.
     */
    suspend fun setGroup(ids: Collection<String>, group: String?): Int =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext 0
            lock.withLock {
                val wanted = ids.toSet()
                val canonical = ShelfGroups.normalize(group)
                val current = readIndex()
                var changed = 0
                for (i in current.indices) {
                    val book = current[i]
                    if (book.id !in wanted) continue
                    if (ShelfGroups.normalize(book.group) == canonical) continue
                    current[i] = book.copy(group = canonical)
                    changed++
                }
                if (changed > 0) {
                    write(current)
                    _revision.value = _revision.value + 1
                }
                changed
            }
        }

    /**
     * Star or unstar books.
     *
     * @return how many books changed state. Already-starred books are not counted
     *   and, more importantly, are not rewritten: a batch "收藏" over a mixed
     *   selection must not un-star the ones that were already favourites, and
     *   `favorite = true` rather than a toggle is what guarantees that.
     */
    suspend fun setFavorite(ids: Collection<String>, favorite: Boolean): Int =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext 0
            lock.withLock {
                val wanted = ids.toSet()
                val current = readIndex()
                var changed = 0
                for (i in current.indices) {
                    val book = current[i]
                    if (book.id !in wanted) continue
                    if (book.favorite == favorite) continue
                    current[i] = book.copy(favorite = favorite)
                    changed++
                }
                if (changed > 0) {
                    write(current)
                    _revision.value = _revision.value + 1
                }
                changed
            }
        }

    /** Total bytes occupied by downloaded books. */
    suspend fun usedBytes(): Long = withContext(Dispatchers.IO) {
        lock.withLock {
            readIndex().sumOf { book ->
                book.localFile?.let { runCatching { File(it).length() }.getOrDefault(0L) } ?: 0L
            }
        }
    }

    suspend fun clearAll(): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            readIndex().forEach { b -> b.localFile?.let { runCatching { File(it).delete() } } }
            booksDir.deleteRecursively()
            write(emptyList())
            _revision.value = _revision.value + 1
        }
    }

    /** Does this remote book already have a local copy? */
    suspend fun find(id: String): Book? = withContext(Dispatchers.IO) {
        lock.withLock { readIndex().firstOrNull { it.id == id } }
    }

    private fun sanitize(name: String): String {
        val cleaned = name.map { c ->
            if (c in ILLEGAL || c.code < 0x20) '_' else c
        }.joinToString("").trim().trimEnd('.')
        // Windows and some Android filesystems dislike reserved device names,
        // and an empty stem would produce a file named ".txt".
        val safe = cleaned.ifBlank { "book" }
        return if (safe.length > 100) safe.take(100) else safe
    }

    private companion object {
        val ILLEGAL = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        /** Marks a record as locally imported rather than fetched from a repo. */
        const val LOCAL_OWNER = "local"
        const val LOCAL_REPO = "imported"
        const val LOCAL_BRANCH = "-"

        /** The collection imported books are grouped under. */
        const val LOCAL_FOLDER = "本地导入"
    }
}
