package com.readon.reader.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.readon.reader.data.Book
import com.readon.reader.data.BookContent
import com.readon.reader.data.Bookmark
import com.readon.reader.data.Chapters

/**
 * Everything the reader screen needs to know, held outside the composition.
 *
 * The reason this is not simply remembered inside the screen: a book is loaded
 * asynchronously, and rotating the device must not throw away the fetched text,
 * the computed pages, or the reading position. A ViewModel-scoped holder is also
 * where the "load failed, retry" state belongs.
 */
class ReaderState {

    sealed interface Status {
        data object Idle : Status
        data object Loading : Status
        data class Ready(val content: BookContent) : Status
        data class Failed(val message: String) : Status
    }

    var status: Status by mutableStateOf(Status.Idle)
        private set

    /**
     * What the loader is doing right now, for the loading view.
     *
     * The reader used to show a fixed "正在排版…" for the entire load, so a book
     * being fetched over a slow route looked identical to one that had hung.
     */
    var stage: String by mutableStateOf("正在排版…")

    /** The book being read. */
    var book: Book? by mutableStateOf(null)
        private set

    /** Which chapter the reader is in; -1 when the book has no outline. */
    var chapterIndex: Int by mutableStateOf(-1)

    /** Page within the current chapter. */
    var pageInChapter: Int by mutableStateOf(0)

    /** Whether the reader chrome (bars, toolbar) is showing. */
    var chromeVisible: Boolean by mutableStateOf(true)

    /** Whether the in-book search panel is open. */
    var searchVisible: Boolean by mutableStateOf(false)

    /** Current search query and its hits (character offsets). */
    var searchQuery: String by mutableStateOf("")
    var searchHits: List<Int> by mutableStateOf(emptyList())
    var searchIndex: Int by mutableStateOf(0)

    /** Whether the table of contents drawer is open. */
    var tocVisible: Boolean by mutableStateOf(false)

    /** Whether the bookmark list is open. */
    var bookmarksVisible: Boolean by mutableStateOf(false)

    /**
     * Whether the quick options panel (font, colour, reading mode) is open.
     *
     * It lives in the reader rather than only in Settings because these are the
     * adjustments a reader makes *while* reading -- "this page is too bright",
     * "this typeface is hard to scan" -- and sending them to another tab to do it
     * loses their place.
     */
    var optionsVisible: Boolean by mutableStateOf(false)

    /** The book's bookmarks, newest first. Owned by the screen's loader. */
    var bookmarks: List<Bookmark> by mutableStateOf(emptyList())

    /**
     * An offset waiting to be scrolled to.
     *
     * It is deferred because jumping to a search hit, a table-of-contents entry
     * or a bookmark usually means switching chapter, and the position cannot be
     * resolved until the new chapter has been laid out. The reader applies this
     * as soon as it can.
     */
    var pendingJumpOffset: Int? by mutableStateOf(null)

    /**
     * The saved position to restore, consumed once.
     *
     * Held here rather than read from [Book.charOffset] at every layout, because
     * that value is the position the book was *opened* at and never changes
     * while reading. Consulting it whenever a chapter is laid out meant that
     * navigating to a chapter the reader had previously been in jumped to that
     * old spot instead of the start of the chapter. It is nulled the moment it
     * is applied, and cleared by any explicit navigation.
     */
    var restoreOffset: Int? by mutableStateOf(null)

    /**
     * Character offset of the reader's current page, kept up to date by the
     * reader as pages turn. Progress and persistence both read it, so there is
     * one definition of "where am I".
     */
    var currentOffset: Int by mutableStateOf(0)

    val content: BookContent? get() = (status as? Status.Ready)?.content

    val chapters: List<Chapters.Chapter> get() = content?.chapters.orEmpty()

    fun begin(book: Book) {
        this.book = book
        status = Status.Loading
        stage = "正在打开…"
        chapterIndex = book.chapterIndex
        pageInChapter = 0
        restoreOffset = book.charOffset.takeIf { it > 0 }
        pendingJumpOffset = null
        searchQuery = ""
        searchHits = emptyList()
        searchIndex = 0
        searchVisible = false
        tocVisible = false
        bookmarksVisible = false
        optionsVisible = false
        chromeVisible = true
    }

    fun succeed(content: BookContent, chapter: Int, page: Int) {
        this.status = Status.Ready(content)
        // Clamp a restored position against the book that was actually loaded;
        // the stored chapter may be stale if the book was revised upstream.
        val chapters = content.chapters
        chapterIndex = when {
            chapters.isEmpty() -> -1
            chapter in chapters.indices -> chapter
            else -> 0
        }
        pageInChapter = page.coerceAtLeast(0)
    }

    fun fail(message: String) {
        status = Status.Failed(message)
    }

    /** Character offset of the reader's current position in the whole book. */
    fun currentOffset(pageStart: Int): Int = pageStart

    /** How far through the book the reader is, 0f..1f. */
    fun readingFraction(): Float = progress(currentOffset)

    /**
     * Overall progress through the book, 0f..1f.
     *
     * For a book with an outline this is chapter-based, because that is the unit
     * the user navigates in and a character ratio would disagree with the table
     * of contents. Otherwise it is the character ratio.
     */
    fun progress(offset: Int): Float {
        val c = content ?: return 0f
        if (c.length == 0) return 0f
        val chapters = c.chapters
        if (chapters.isNotEmpty() && chapterIndex >= 0) {
            val chapter = chapters.getOrNull(chapterIndex)
            if (chapter != null) {
                val span = (chapter.end - chapter.start).coerceAtLeast(1)
                val within = ((offset - chapter.start).toFloat() / span).coerceIn(0f, 1f)
                return ((chapterIndex + within) / chapters.size).coerceIn(0f, 1f)
            }
        }
        return (offset.toFloat() / c.length).coerceIn(0f, 1f)
    }
}
