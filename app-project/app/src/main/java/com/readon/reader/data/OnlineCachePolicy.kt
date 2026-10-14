package com.readon.reader.data

/**
 * The decisions an online-reading cache has to make, with no Android types.
 *
 * Kept apart from [OnlineCache] -- which does the file work -- for the same reason
 * [com.readon.reader.reader.PageCursor] is kept apart from `Paginator`: these are
 * the rules that can be *wrong* independently of any I/O, and `tools/check-book-logic.cmd`
 * compiles this exact file and exercises it on the desktop.
 *
 * ## What can go wrong here, and why it is worth a test
 *
 *  * **A stale entry served as fresh content.** A cached book that no longer
 *    matches the repository would be shown instead of the real file, and the user
 *    would have no way to tell -- there is no "reload" that bypasses a cache that
 *    believes it is current. [isUsable] therefore trusts a cached file only when
 *    its size matches what the repository reported.
 *  * **An unbounded cache.** This cache holds whole books, and this library's
 *    books reach 75 MB. Without eviction, reading a few long novels online would
 *    leave hundreds of megabytes behind with no user-visible cause.
 *    [evict] is what keeps the directory bounded.
 */
object OnlineCachePolicy {

    /** One cached file, as the eviction decision sees it. */
    data class Entry(
        /** Identifies the file to delete; the cache passes the file's name. */
        val key: String,
        val size: Long,
        /** When the entry was last *read*, which is what "least recently used" means. */
        val lastUsed: Long,
    )

    /**
     * Is a cached file of [cachedSize] bytes a valid copy of a book the repository
     * reports as [expectedSize] bytes?
     *
     * A zero or negative [expectedSize] means the listing did not say -- some
     * routes and older listings do not -- in which case any non-empty file is
     * accepted, because refusing it would defeat the cache on exactly the books
     * that are most expensive to re-fetch.
     *
     * An empty file is never usable: it is the residue of an interrupted write,
     * and serving it would show the user an empty book.
     */
    fun isUsable(cachedSize: Long, expectedSize: Long): Boolean {
        if (cachedSize <= 0L) return false
        if (expectedSize <= 0L) return true
        return cachedSize == expectedSize
    }

    /**
     * Which entries to delete so the cache fits inside [maxBytes].
     *
     * Oldest read first, so the book the user is most likely to open again
     * survives. Returns an empty list when nothing needs to go, which is the
     * common case.
     *
     * A single entry larger than the whole cap is deleted rather than kept: an
     * oversized entry that can never fit would otherwise be evicted and re-added
     * on every read, evicting everything else each time.
     *
     * A non-positive [maxBytes] means "no limit" and evicts nothing. That is
     * deliberate -- treating it as "evict everything" would make a misconfigured
     * cap silently disable the feature instead of failing visibly.
     */
    fun evict(entries: List<Entry>, maxBytes: Long): List<String> {
        if (maxBytes <= 0L) return emptyList()

        var total = entries.sumOf { it.size }
        if (total <= maxBytes) return emptyList()

        val doomed = ArrayList<String>()
        for (entry in entries.sortedBy { it.lastUsed }) {
            if (total <= maxBytes) break
            doomed.add(entry.key)
            total -= entry.size
        }
        return doomed
    }
}
