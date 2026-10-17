package com.readon.reader.ui

/**
 * Layout decisions for the app's list screens, as pure arithmetic.
 *
 * ## Why this is not done inline with `BoxWithConstraints`
 *
 * Every value here answers a question that is invisible when it is wrong. A list
 * that is 8 dp too wide on a tablet looks like a design choice; a text column
 * that is 140 characters wide on a foldable is *unreadable* but renders
 * perfectly; a row whose cover and buttons fight for the same 320 dp clips a
 * title without any error. Composing these decisions inside the screens would
 * leave them untestable -- there is no JUnit in the offline dependency set, and a
 * screenshot on one device says nothing about the one the user holds.
 *
 * So the arithmetic lives here: no Compose types, no `Dp`, no `Context`. The
 * screens convert to and from [Int] dp values at the boundary. That places it in
 * the same category as [com.readon.reader.reader.AutoRead] and
 * [com.readon.reader.update.UpdateDecision] -- logic that is checked on the
 * desktop by `tools/check-book-logic.cmd` because a wrong answer is silent.
 *
 * ## What "adaptive" means here
 *
 * Readon is one Activity and a handful of lists. It does not need a second layout
 * for tablets; it needs every list to stop doing the things that only work on a
 * narrow phone:
 *
 *  * content stretched edge to edge across a 1200 dp window;
 *  * a single column of book rows on a device wide enough for three;
 *  * a text column so wide the eye cannot find the next line.
 *
 * The answers are a width class, a gutter-capped content width, and a column
 * count -- three numbers the screens then use consistently.
 */
object Adapt {

    /**
     * Width classes, at the breakpoints Android's own guidance uses.
     *
     * `Compact` is a phone held upright, `Medium` a phone on its side or a small
     * tablet, `Expanded` a tablet in landscape or a foldable unfolded. The
     * boundaries are on the *window* width, not the screen width: a freeform
     * window on a tablet is a narrow window and has to lay out like one.
     */
    enum class WidthClass {
        Compact,
        Medium,
        Expanded,
        ;

        /** True for the classes that get more than one column of content. */
        val isWide: Boolean get() = this != Compact
    }

    /** 600 dp: the traditional "at least a small tablet" boundary. */
    const val MEDIUM_MIN_WIDTH_DP = 600

    /** 840 dp: a tablet in landscape, or a foldable unfolded. */
    const val EXPANDED_MIN_WIDTH_DP = 840

    /**
     * The class for a window [widthDp] across.
     *
     * Negative or zero widths -- which a preview or a first measurement can hand
     * over -- answer [WidthClass.Compact] rather than throwing: the narrow layout
     * is the one that is always safe to render.
     */
    fun widthClass(widthDp: Int): WidthClass = when {
        widthDp >= EXPANDED_MIN_WIDTH_DP -> WidthClass.Expanded
        widthDp >= MEDIUM_MIN_WIDTH_DP -> WidthClass.Medium
        else -> WidthClass.Compact
    }

    /**
     * How wide a list's *content* is allowed to get, in dp.
     *
     * The reason this exists: a book row stretched across 1200 dp puts a 52 dp
     * cover at the far left and a download button 1100 dp away, which reads as two
     * unrelated things. Capping the column and centring it is what every reading
     * app does, and it is the single biggest difference a tablet user sees.
     *
     * The cap is generous rather than tight (`Compact` gets none at all): on a
     * phone the full width *is* the design, and capping it would just add margins.
     */
    fun contentMaxWidthDp(cls: WidthClass): Int = when (cls) {
        WidthClass.Compact -> Int.MAX_VALUE
        WidthClass.Medium -> 760
        WidthClass.Expanded -> 900
    }

    /**
     * The width to lay content out in, given the window and the class.
     *
     * Returns [windowDp] when nothing needs capping, so callers can compare it
     * against the window to decide whether to centre.
     */
    fun contentWidthDp(windowDp: Int, cls: WidthClass): Int {
        if (windowDp <= 0) return 0
        return minOf(windowDp, contentMaxWidthDp(cls))
    }

    /** The horizontal margin a centred content column needs, per side. */
    fun sideGutterDp(windowDp: Int, cls: WidthClass): Int {
        if (windowDp <= 0) return 0
        return ((windowDp - contentWidthDp(windowDp, cls)).coerceAtLeast(0)) / 2
    }

    /**
     * How many book rows (or shelf covers) fit comfortably in [widthDp].
     *
     * A phone gets one, because a book row is a cover plus two lines of text plus
     * a button and two of those side by side would be 160 dp each. A tablet gets
     * two, a large one three. Deliberately *not* unbounded: the row is a reading
     * surface, and eight columns of it would be a spreadsheet.
     *
     * @param minColumnDp the narrowest a single item may be; [Adapt.BOOK_ROW_MIN]
     *   for the home screen's rows, [Adapt.SHELF_TILE_MIN] for shelf covers.
     */
    fun gridColumns(widthDp: Int, gapDp: Int, minColumnDp: Int): Int {
        if (widthDp <= 0 || minColumnDp <= 0) return 1
        val usable = widthDp + gapDp
        val fit = usable / (minColumnDp + gapDp)
        return fit.coerceIn(1, MAX_GRID_COLUMNS)
    }

    /**
     * A book row on 主页: a 52 dp cover, two lines of title, a format badge and one
     * action.
     *
     * 275 dp rather than the 380 dp that looks comfortable on a phone. The number is set
     * by the *narrowest* wide layout that has to show two columns: at 600 dp the content
     * column is 568 dp after padding, and a 300 dp minimum still fits only one, so the
     * tablet branch would engage and then render exactly the phone layout -- worse than
     * not engaging at all, because the wider margins appear and the extra column does
     * not. At 275 dp the two cells are ~279 dp each, which is a cover, a two-line title
     * and the download button.
     */
    const val BOOK_ROW_MIN = 275

    /** A shelf tile: a cover at roughly 2:3 plus a title under it. */
    const val SHELF_TILE_MIN = 116

    /**
     * A hard ceiling on columns.
     *
     * Not a layout opinion -- a guard. `gridColumns` is fed a measured width, and
     * a measurement of a zero-height or pre-layout container can be enormous;
     * without a cap that produces a single frame with dozens of columns and every
     * item measuring itself, which is exactly the kind of hitch that looks like a
     * slow device.
     */
    const val MAX_GRID_COLUMNS = 4

    /**
     * The widest the reader's text column is allowed to get, in dp.
     *
     * ## Why capping the *column* is the fix and capping the *margin* is not
     *
     * The obvious formulation is "make the margin grow with the window", and it
     * fails at both ends. On a 360 dp phone a 16% margin is 57 dp, which is nearly
     * three times the 22 dp the reader shipped with -- so the fix would have made
     * every phone's margins worse in order to help tablets. And on a 2560 dp window
     * any sane *margin* cap leaves a text column over 2000 dp wide, which is 117
     * Chinese characters per line: the cap has to bound the text, not the space
     * around it.
     *
     * So the readable column is the invariant and the margin is derived from it:
     * whatever is left over becomes the margin, split evenly. 720 dp at 18 sp is
     * about 40 Chinese characters, near the wide end of what is comfortable and
     * comfortably inside it on a phone.
     */
    const val READER_TEXT_MAX_DP = 720

    /**
     * The reader's minimum horizontal margin, in dp.
     *
     * The value the reader shipped with before it was adaptive, kept as the floor so
     * a phone's layout is unchanged -- text must never touch the screen edge even
     * when the column is capped, which on a narrow window it never is.
     */
    const val READER_PADDING_MIN_DP = 22

    /**
     * How wide the reader's text column is on a window [windowDp] across.
     *
     * Exposed separately from the padding so the paginator and the drawn column
     * cannot disagree -- the bug that would produce is a page measured against one
     * width and drawn at another, which shows up as a last line cut off
     * mid-character.
     */
    fun readerTextWidthDp(windowDp: Int): Int {
        if (windowDp <= 0) return 0
        val available = windowDp - READER_PADDING_MIN_DP * 2
        if (available <= 0) return 1
        return minOf(available, READER_TEXT_MAX_DP)
    }

    /**
     * The reader's horizontal margin, in dp.
     *
     * Derived from [readerTextWidthDp] rather than computed independently, so the
     * two always add up to the window: half of whatever the capped column leaves
     * over, never less than [READER_PADDING_MIN_DP]. Deriving it is what keeps a
     * 1200 dp tablet from laying out a 1150 dp line.
     */
    fun readerHorizontalPaddingDp(windowDp: Int): Int {
        if (windowDp <= 0) return READER_PADDING_MIN_DP
        val leftover = windowDp - readerTextWidthDp(windowDp)
        return (leftover / 2).coerceAtLeast(READER_PADDING_MIN_DP)
    }

    /**
     * How wide a dialog's content may be, in dp.
     *
     * Material's own default cap on a dialog is generous for a phone and absurd
     * for a tablet -- a two-line confirmation becomes a 1000 dp-wide box with the
     * buttons at opposite corners. Applied to the notes reader, which is the one
     * dialog that holds long prose.
     */
    fun dialogMaxWidthDp(windowDp: Int): Int {
        if (windowDp <= 0) return 400
        return minOf(windowDp - 48, DIALOG_MAX_DP).coerceAtLeast(240)
    }

    private const val DIALOG_MAX_DP = 560
}
