package com.readon.reader.reader

/**
 * Character-level text navigation, shared by both reading modes.
 *
 * Deliberately free of Android and Compose types so `tools/check-book-logic.cmd`
 * can compile this exact file and exercise it on the desktop. Two pieces of
 * behaviour live here because they are the ones a device makes expensive to
 * verify:
 *
 *  * [skipBlank] is what stops the paged reader from showing a blank page. A
 *    chapter that ends in a run of newlines used to be paginated into a final
 *    page containing nothing but those newlines, and the reader drew it -- an
 *    empty screen the user had to tap past. Page starts now skip over
 *    whitespace, so a page always begins on a visible character.
 *
 *  * [blocks] is what the scrolling reader draws. A book is a million
 *    characters, and a `LazyColumn` needs discrete items: paragraphs split on
 *    blank lines, with any single paragraph capped so one enormous run of text
 *    cannot become a single unmeasurable item.
 */
object TextFlow {

    /** A run of text drawn as one unit. Offsets index the book's own text. */
    data class Block(val start: Int, val end: Int) {
        val length: Int get() = end - start
    }

    /** Is every character in `[start, end)` whitespace (or is the range empty)? */
    fun isBlank(text: CharSequence, start: Int, end: Int): Boolean {
        var i = start.coerceAtLeast(0)
        val stop = end.coerceAtMost(text.length)
        while (i < stop) {
            if (!text[i].isWhitespace()) return false
            i++
        }
        return true
    }

    /**
     * The first non-whitespace character at or after [from], or `text.length`.
     *
     * Used as a page start. Skipping *all* leading whitespace rather than just a
     * newline matters for a file that separates chapters with several blank
     * lines: the next page then starts on the heading instead of on the gap.
     */
    fun skipBlank(text: CharSequence, from: Int): Int {
        var i = from.coerceAtLeast(0)
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    /** One past the last non-whitespace character in `[start, end)`. */
    fun trimEnd(text: CharSequence, start: Int, end: Int): Int {
        var e = end.coerceAtMost(text.length)
        val from = start.coerceAtLeast(0)
        while (e > from && text[e - 1].isWhitespace()) e--
        return e
    }

    /**
     * Split [text] into the blocks the scrolling reader draws.
     *
     * A paragraph is a run of non-blank lines, so a wrapped English paragraph
     * stays one item while the blank line between two Chinese paragraphs
     * separates them. Whitespace-only runs produce no item at all -- the same
     * "no blank page" rule as [skipBlank], applied to scrolling.
     *
     * @param maxChars hard cap on one block. A book with no blank lines at all
     *   is otherwise one block of a million characters, and a single `Text`
     *   composable handed that much content is what makes scrolling stall.
     */
    fun blocks(text: CharSequence, maxChars: Int = DEFAULT_MAX_BLOCK): List<Block> {
        val out = ArrayList<Block>()
        if (text.isEmpty()) return out

        val n = text.length
        var lineStart = 0
        // Start of the paragraph being accumulated, or -1 when between
        // paragraphs; `contentEnd` is the end of its last non-blank line.
        var paraStart = -1
        var contentEnd = 0

        while (lineStart <= n) {
            var lineEnd = text.indexOf('\n', lineStart)
            if (lineEnd < 0) lineEnd = n

            if (isBlank(text, lineStart, lineEnd)) {
                if (paraStart >= 0) {
                    emit(out, text, paraStart, contentEnd, maxChars)
                    paraStart = -1
                }
            } else {
                if (paraStart < 0) paraStart = lineStart
                contentEnd = lineEnd
            }

            if (lineEnd >= n) break
            lineStart = lineEnd + 1
        }
        if (paraStart >= 0) emit(out, text, paraStart, contentEnd, maxChars)

        return out
    }

    /**
     * The block containing [offset], or the nearest one before it.
     *
     * Returns -1 for an empty list. Used both to restore a saved position and to
     * jump to a search hit or a table-of-contents entry.
     */
    fun blockIndexOf(blocks: List<Block>, offset: Int): Int {
        if (blocks.isEmpty()) return -1
        var lo = 0
        var hi = blocks.size - 1
        var answer = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (blocks[mid].start <= offset) {
                answer = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return answer
    }

    /** Append `[start, end)` to [out], splitting it if it exceeds [maxChars]. */
    private fun emit(
        out: MutableList<Block>,
        text: CharSequence,
        start: Int,
        end: Int,
        maxChars: Int,
    ) {
        val cap = if (maxChars < MIN_BLOCK) MIN_BLOCK else maxChars
        if (end <= start) return
        var from = start
        while (end - from > cap) {
            val limit = from + cap
            // Prefer to break where the author did, then at a space, so a
            // split does not land in the middle of a word when it can be
            // avoided. The look-back is bounded: past that, a slightly worse
            // break beats a shorter block.
            var cut = -1
            for (i in limit downTo (limit - BREAK_LOOKBACK).coerceAtLeast(from + 1)) {
                if (text[i - 1] == '\n') {
                    cut = i
                    break
                }
            }
            if (cut < 0) {
                for (i in limit downTo (limit - BREAK_LOOKBACK).coerceAtLeast(from + 1)) {
                    if (text[i - 1] == ' ' || text[i - 1] == '\t') {
                        cut = i
                        break
                    }
                }
            }
            val next = if (cut > from) cut else limit
            out.add(Block(from, next))
            from = skipBlank(text, next)
            if (from >= end) return
        }
        val tail = trimEnd(text, from, end)
        if (tail > from) out.add(Block(from, tail)) else if (from < end) out.add(Block(from, end))
    }

    /** Default cap on one scrolling block, in characters. */
    const val DEFAULT_MAX_BLOCK = 1600

    private const val MIN_BLOCK = 200

    /** How far back a block split may look for a nicer break. */
    private const val BREAK_LOOKBACK = 200
}
