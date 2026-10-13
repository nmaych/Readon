package com.readon.reader.reader

/**
 * One rendered page.
 *
 * [start] is the index into the book's text of the first character on the page,
 * and [end] the first character *after* it. Storing offsets rather than page
 * numbers is what keeps a reading position meaningful when the user changes the
 * font size: the position is anchored to the text, so repagination lands on the
 * same sentence instead of jumping to an unrelated page 12.
 */
data class PageSlice(val start: Int, val end: Int)

/**
 * The page list for one chapter, built on demand.
 *
 * ## Why this is its own class
 *
 * This is the bookkeeping that decides where each page begins and ends -- the
 * part that can be wrong independently of how text is measured. It used to sit
 * inside [Paginator], where nothing could reach it: the first page of every book
 * was produced by asking an *empty* list for its last entry, so
 * `pages.last()` threw `NoSuchElementException` the moment a book finished
 * loading. Thrown from `remember { ... }` inside the composition, that is the
 * crash the reader saw as "显示正在排版…" followed by the app closing -- for every
 * book, not just a large or unusual one.
 *
 * It has no Android or Compose types on purpose: that is what lets
 * `tools/check-book-logic.cmd` compile this exact file and exercise it on the
 * desktop, where a page-cursor bug is caught instead of shipped.
 *
 * @param length number of characters in the chapter.
 * @param pageEnd computes the end offset of the page that starts at the given
 *   offset. It must return a value greater than the offset it was handed.
 */
class PageCursor(
    private val length: Int,
    private val pageEnd: (Int) -> Int,
) {
    private val pages = ArrayList<PageSlice>()
    private var complete = false

    val pageCount: Int get() = pages.size
    val isComplete: Boolean get() = complete

    init {
        if (length <= 0) {
            // An empty chapter is still one (empty) page: the reader then has a
            // page to draw and a count of one, rather than zero pages and a
            // "第 1 / 0 页" label.
            pages.add(PageSlice(0, 0))
            complete = true
        } else {
            ensurePage(0)
        }
    }

    /** Make sure page [index] exists, computing forward as needed. */
    fun ensurePage(index: Int) {
        if (complete || index < 0) return
        var guard = 0
        while (pages.size <= index && !complete) {
            // The first page starts at the top of the chapter; every later page
            // starts where the previous one ended. `lastOrNull()` rather than
            // `last()` is the fix: on the very first call the list is empty, and
            // `last()` throws -- which is what opening a book did.
            val start = pages.lastOrNull()?.end ?: 0
            if (start >= length) {
                complete = true
                break
            }
            val end = pageEnd(start).coerceIn(start + 1, length)
            pages.add(PageSlice(start, end))
            if (++guard > HARD_LIMIT) {
                // A pathological input (no break opportunity, absurdly small
                // viewport) must not spin forever.
                complete = true
            }
        }
    }

    /** Run pagination to the end. Used only when the user asks for the count. */
    fun completeAll() {
        var guard = 0
        while (!complete && guard < HARD_LIMIT) {
            ensurePage(pages.size)
            guard++
        }
    }

    fun page(index: Int): PageSlice {
        if (index < 0) return PageSlice(0, 0)
        ensurePage(index)
        return pages.getOrElse(index) { pages.last() }
    }

    /** Keep a page's top visible; used to reflow after a viewport change. */
    fun pageOf(offset: Int): Int {
        if (pages.isEmpty()) return 0
        for (i in pages.indices) {
            if (offset < pages[i].end) return i
        }
        // The offset is past everything computed so far: compute forward to it.
        var guard = 0
        while (!complete && guard < HARD_LIMIT) {
            val last = pages.last()
            if (offset < last.end) return pages.size - 1
            ensurePage(pages.size)
            guard++
        }
        return (pages.size - 1).coerceAtLeast(0)
    }

    private companion object {
        const val HARD_LIMIT = 50_000
    }
}
