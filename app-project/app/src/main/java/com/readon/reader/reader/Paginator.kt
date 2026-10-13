package com.readon.reader.reader

import android.text.TextPaint
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density

/**
 * Splits a book into pages that fit a given viewport.
 *
 * Layout is measured with the same [TextMeasurer] Compose uses to draw, so a
 * page that measures as fitting really does fit -- an approximation based on
 * average character width falls apart the moment a book contains a table or a
 * line of ASCII art.
 *
 * Cost is one measure pass per page. Rather than measuring the whole book up
 * front (a novel is 1M+ characters and would take seconds and a lot of memory),
 * pages are produced lazily and cached as they are reached; see [Paginator].
 */
class TextPaginator(
    private val measurer: TextMeasurer,
    private val density: Density,
) {

    /**
     * Find the end of the page starting at [start].
     *
     * The search is a binary search over candidate end offsets. A linear scan
     * character by character would measure the page a few thousand times; the
     * binary search measures it about twelve.
     *
     * Splitting preferentially happens at a paragraph break, then at any
     * whitespace, then mid-word -- but only when the break is close to the
     * bottom of the page, because honouring a break far from the end would waste
     * a visible amount of the viewport on every page.
     */
    fun pageEnd(
        text: CharSequence,
        start: Int,
        maxWidth: Int,
        maxHeight: Int,
        style: TextStyle,
    ): Int {
        if (start >= text.length) return text.length

        var lo = start + 1
        var hi = minOf(text.length, start + CHUNK)
        var best = start + 1

        // Grow the window until it overflows, so a page is never cut short just
        // because the initial guess was too small.
        while (hi < text.length && fits(text, start, hi, maxWidth, maxHeight, style)) {
            best = hi
            lo = hi
            hi = minOf(text.length, hi + CHUNK)
        }

        // Binary search the exact break inside [lo, hi].
        var low = lo
        var high = hi
        var answer = best
        while (low <= high) {
            val mid = low + (high - low) / 2
            if (mid <= start) {
                low = start + 1
                continue
            }
            if (fits(text, start, mid, maxWidth, maxHeight, style)) {
                answer = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        answer = answer.coerceIn(start + 1, text.length)
        return adjustBreak(text, start, answer)
    }

    private fun fits(
        text: CharSequence,
        start: Int,
        end: Int,
        maxWidth: Int,
        maxHeight: Int,
        style: TextStyle,
    ): Boolean {
        if (end <= start) return true
        val slice = text.subSequence(start, end)
        val result = measurer.measure(
            text = AnnotatedString(slice.toString()),
            style = style,
            constraints = Constraints(maxWidth = maxWidth.coerceAtLeast(1)),
        )
        return result.size.height <= maxHeight
    }

    /**
     * Nudge a page break back to a natural boundary.
     *
     * Only looks back a short distance: past that, the wasted space matters more
     * than a pretty break.
     */
    private fun adjustBreak(text: CharSequence, start: Int, end: Int): Int {
        val limit = maxOf(start + 1, end - LOOKBACK)
        for (i in end downTo limit) {
            val c = text[i - 1]
            if (c == '\n') {
                // Prefer a paragraph break, and never leave a blank line hanging
                // at the top of the next page.
                var e = i
                while (e > limit && text[e - 1] == '\n') e--
                return e.coerceAtLeast(start + 1)
            }
        }
        for (i in end downTo limit) {
            val c = text[i - 1]
            if (c == ' ' || c == '\t') return i
        }
        // CJK has no spaces; break between characters rather than pulling the
        // previous line down. Nothing to do beyond returning the raw offset.
        return end
    }

    private companion object {
        /** Initial window, in characters. ~4 pages of CJK text at a typical size. */
        const val CHUNK = 3000

        /** How far back a nicer break may be taken, in characters. */
        const val LOOKBACK = 120
    }
}

/**
 * A paginated book, with pages produced on demand.
 *
 * Pages are computed until the reader asks for one that does not exist yet, and
 * every computed page is kept. Scrolling forward through a chapter therefore
 * costs one measure pass per new page and nothing after that, which is what
 * makes a 3000-page novel scroll as smoothly as a short one.
 *
 * The page bookkeeping itself lives in [PageCursor], which is free of Android
 * types so it can be verified on the desktop. This class only connects it to a
 * [TextMeasurer]: the measurement is what is expensive, and the cursor is what
 * decides where pages begin and end.
 *
 * Not thread safe by construction; it is only ever touched from the composition
 * that owns it. [TextPaginator] and [TextMeasurer] are safe to reuse.
 */
class Paginator(
    private val text: CharSequence,
    private val paginator: TextPaginator,
    private val style: TextStyle,
    private val width: Int,
    private val height: Int,
) {
    private val cursor = PageCursor(text.length) { start ->
        paginator.pageEnd(text, start, width, height, style)
    }

    val pageCount: Int get() = cursor.pageCount
    val isComplete: Boolean get() = cursor.isComplete

    /** Make sure page [index] exists, computing forward as needed. */
    fun ensurePage(index: Int) = cursor.ensurePage(index)

    /** Run pagination to the end. Used only when the user asks for the count. */
    fun completeAll() = cursor.completeAll()

    fun page(index: Int): PageSlice = cursor.page(index)

    fun textOf(index: Int): String {
        val p = page(index)
        if (p.start >= p.end || p.start >= text.length) return ""
        return text.subSequence(p.start, p.end).toString()
    }

    /** Keep a page's top visible; used to reflow after a viewport change. */
    fun pageOf(offset: Int): Int = cursor.pageOf(offset)
}
