package com.readon.reader.data

/**
 * A bookmark: a named position in a book.
 *
 * Stored as a character offset, never a page number, for the same reason the
 * reading position is: changing the font size repaginates the whole book, and a
 * page number would then point somewhere unrelated. [chapterTitle] is captured
 * at creation time so the bookmark list can still say "第三章" for a book whose
 * outline is not loaded.
 *
 * The class and the helpers below it are free of Android, Gson and Compose types
 * so `tools/check-book-logic.cmd` can compile this file and exercise the snippet
 * extraction and the add/remove rules on the desktop.
 */
data class Bookmark(
    /** Stable within a book; `bookId` plus the offset it was taken at. */
    val id: String,
    val charOffset: Int,
    /** -1 when the book has no outline. */
    val chapterIndex: Int,
    val chapterTitle: String?,
    /** What the user typed, or "" to fall back to the text at the offset. */
    val label: String,
    val createdAt: Long,
    /** 0f..1f through the book, for the list's progress hint. */
    val progress: Float,
)

object Bookmarks {

    /** How much surrounding text a derived label shows. */
    private const val SNIPPET_RADIUS = 26

    /** Longest label the list renders without ellipsising. */
    const val MAX_LABEL = 60

    fun idOf(bookId: String, charOffset: Int): String = "$bookId@$charOffset"

    /**
     * The text around [offset], flattened to one line.
     *
     * A bookmark taken at the top of a page should read as the sentence the user
     * was looking at, not as "第 12 页". Newlines and runs of spaces are
     * collapsed so a label cannot contain a line break that would break the
     * list's layout, and the result is trimmed to something that fits one row.
     */
    fun snippet(text: CharSequence, offset: Int, radius: Int = SNIPPET_RADIUS): String {
        if (text.isEmpty()) return ""
        val at = offset.coerceIn(0, text.length - 1)
        val from = (at - radius).coerceAtLeast(0)
        val to = (at + radius).coerceAtMost(text.length)
        val raw = text.subSequence(from, to).toString()
        val flat = raw.replace(Regex("""\s+"""), " ").trim()
        if (flat.isEmpty()) return ""
        val clipped = if (flat.length > MAX_LABEL) flat.take(MAX_LABEL) else flat
        // Only the ends that were actually cut get an ellipsis, so a short
        // snippet taken from the middle of a page does not claim to be truncated.
        val head = if (from > 0) "…" else ""
        val tail = if (to < text.length || clipped.length < flat.length) "…" else ""
        return head + clipped + tail
    }

    /**
     * What the list shows for a bookmark.
     *
     * Falls back to the text at the offset when the user did not name it, which
     * is the case that has to be right: an unnamed bookmark is the common one,
     * and a row of identical "未命名书签" entries would be useless.
     */
    fun displayLabel(bookmark: Bookmark, text: CharSequence): String {
        val typed = bookmark.label.trim()
        if (typed.isNotEmpty()) return typed
        val derived = snippet(text, bookmark.charOffset)
        if (derived.isNotEmpty()) return derived
        val chapter = bookmark.chapterTitle?.trim().orEmpty()
        return chapter.ifEmpty { "书签" }
    }

    /** Where the row says the bookmark is, e.g. "第三章 · 42%". */
    fun locationLabel(bookmark: Bookmark): String {
        val percent = "${(bookmark.progress.coerceIn(0f, 1f) * 100).toInt()}%"
        val chapter = bookmark.chapterTitle?.trim().orEmpty()
        return if (chapter.isEmpty()) percent else "$chapter · $percent"
    }

    /** Newest first: the bookmark just added is the one being looked for. */
    fun sorted(list: List<Bookmark>): List<Bookmark> =
        list.sortedWith(compareByDescending<Bookmark> { it.createdAt }.thenBy { it.charOffset })

    /**
     * Add [bookmark], replacing any bookmark already at the same offset.
     *
     * Replacing rather than appending is what makes "bookmark this page" behave
     * when the user taps it twice with a different name: they meant to rename it,
     * not to end up with two rows for one position.
     */
    fun add(list: List<Bookmark>, bookmark: Bookmark): List<Bookmark> {
        val kept = list.filterNot { it.id == bookmark.id }
        return sorted(kept + bookmark)
    }

    fun remove(list: List<Bookmark>, id: String): List<Bookmark> =
        list.filterNot { it.id == id }

    fun at(list: List<Bookmark>, charOffset: Int): Bookmark? =
        list.firstOrNull { it.charOffset == charOffset }
}
