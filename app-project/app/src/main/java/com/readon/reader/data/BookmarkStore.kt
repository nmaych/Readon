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
 * Bookmarks, kept per book in one JSON file.
 *
 * A separate file from `library.json` rather than a field on [Book] on purpose:
 * the shelf reads the whole book index on every appearance and after every page
 * turn, and folding a growing list of bookmarks into it would make that read
 * grow without bound. Here a book's bookmarks are read only when the reader asks
 * for them.
 *
 * The same temp-file-rename discipline as [BookStore]: a crash mid-write can
 * lose the newest bookmark but never the file, which is what stops a power cut
 * from wiping every bookmark the user has.
 *
 * Writes are serialised by a mutex because the reader can add or delete a
 * bookmark while the shelf is deleting the book it belongs to.
 */
class BookmarkStore(private val context: Context) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val file: File get() = File(context.filesDir, "bookmarks.json")

    private val type = object : TypeToken<MutableMap<String, MutableList<Bookmark>>>() {}.type

    private val lock = Mutex()

    private val _revision = MutableStateFlow(0)

    /**
     * Bumped on every change, so the reader's bookmark panel and any badge on the
     * toolbar refresh without polling.
     */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private fun read(): MutableMap<String, MutableList<Bookmark>> {
        if (!file.exists()) return mutableMapOf()
        return try {
            val json = file.readText()
            if (json.isBlank()) mutableMapOf()
            else gson.fromJson<MutableMap<String, MutableList<Bookmark>>>(json, type)
                ?: mutableMapOf()
        } catch (_: Exception) {
            // A corrupt file must not brick the reader. The books are untouched;
            // the user loses the bookmark list, not the library.
            mutableMapOf()
        }
    }

    private fun write(all: Map<String, List<Bookmark>>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "bookmarks.json.tmp")
        tmp.writeText(gson.toJson(all, type))
        if (!tmp.renameTo(file)) {
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    /** A book's bookmarks, newest first. */
    suspend fun load(bookId: String): List<Bookmark> = withContext(Dispatchers.IO) {
        lock.withLock { Bookmarks.sorted(read()[bookId].orEmpty()) }
    }

    /**
     * Add or replace a bookmark, returning the book's new list.
     *
     * Replacing an entry at the same offset is what makes tapping the bookmark
     * button twice a rename rather than a duplicate.
     */
    suspend fun add(bookmark: Bookmark, bookId: String): List<Bookmark> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val all = read()
                val updated = Bookmarks.add(all[bookId].orEmpty(), bookmark)
                all[bookId] = updated.toMutableList()
                write(all)
                _revision.value = _revision.value + 1
                Bookmarks.sorted(updated)
            }
        }

    suspend fun remove(bookId: String, id: String): List<Bookmark> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val all = read()
                val updated = Bookmarks.remove(all[bookId].orEmpty(), id)
                if (updated.isEmpty()) all.remove(bookId) else all[bookId] = updated.toMutableList()
                write(all)
                _revision.value = _revision.value + 1
                Bookmarks.sorted(updated)
            }
        }

    /** Forget every bookmark for a book; used when the book is deleted. */
    suspend fun clear(bookId: String): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            val all = read()
            if (all.remove(bookId) == null) return@withLock
            write(all)
            _revision.value = _revision.value + 1
        }
    }

    /** Every bookmark for every book; used by "clear downloaded books". */
    suspend fun clearAll(): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            write(emptyMap())
            _revision.value = _revision.value + 1
        }
    }
}
