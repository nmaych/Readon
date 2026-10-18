package com.readon.reader.reader

import java.util.Locale

/**
 * The two lines the reader draws in place of the phone's status bar.
 *
 * ## Why the reader draws its own
 *
 * Since 1.0.7 the reader hides **both** system bars for the whole visit, which is what
 * makes the window the page. The cost was the one thing the status bar was actually
 * good for: the clock and the battery. A reader who has been reading for two hours had
 * no way to see either without swiping from the top edge, which is a gesture most
 * people never discover.
 *
 * So the reader draws the same information itself, in its own colours, where the status
 * bar used to be:
 *
 *  * a line at the **top** with the chapter being read, because "where am I" is the
 *    question a heading answers, and the reader's own toolbar is hidden most of the time;
 *  * a line at the **bottom** with the position on the left and the clock and battery on
 *    the right, so the two things people glance at while reading are in one place.
 *
 * ## Why the arithmetic is here
 *
 * Same reason as [AutoRead] and [com.readon.reader.ui.Adapt]: these are the strings and
 * thresholds that look right on one device and wrong on another, and a mistake in them
 * is silent. `"0:5"` instead of `"00:05"`, a battery reading of `"120%"` from a badly
 * scaled broadcast, a progress line stuck at `"100%"` for the last chapter -- none of
 * those crash, and none of them are visible in a screenshot review. Keeping them as
 * pure functions means `tools/check-book-logic.cmd` can assert them on the desktop.
 */
object ReaderHud {

    /**
     * `14:05`, always two digits on each side.
     *
     * A one-digit minute (`14:5`) is the kind of thing that is obvious in a screenshot
     * and invisible in code review, and the padding is why this is not `"$h:$m"`.
     * Out-of-range values are clamped rather than thrown on: the caller is a clock, and
     * a clock that crashes the reader at midnight would be a spectacular own goal.
     */
    fun clockText(hour: Int, minute: Int): String {
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        return String.format(Locale.US, "%02d:%02d", h, m)
    }

    /**
     * The battery percentage, as shown next to the icon.
     *
     * Clamped to 0..100 because a device reporting `EXTRA_SCALE` of zero, or a level
     * above its own scale, produces a number the user would read as a bug. An unknown
     * reading (-1 from `EXTRA_LEVEL` when the extra is missing) becomes 0 rather than
     * "-1%".
     */
    fun batteryText(level: Int): String = "${level.coerceIn(0, 100)}%"

    /**
     * How far through the book, as a whole percentage.
     *
     * The floor rather than the round: 99.6% of a book should read as "99%", not as
     * "100%", because "100%" next to another page of text is a lie the reader will
     * notice. The value is clamped, since a chapter index past the outline (which
     * happens for one frame while a partial online read is extended) would otherwise
     * produce a negative or >100 figure.
     */
    fun progressText(fraction: Float): String {
        val pct = (fraction.coerceIn(0f, 1f) * 100f).toInt()
        return "$pct%"
    }

    /**
     * Where the reader is, for the footer line: the book percentage, and the chapter
     * position when the book has an outline.
     *
     * `"第 12 / 340 章 · 23%"` answers both "how far through" and "where am I", which
     * are the two things a reader glances down for. A book with no detected outline
     * gets the percentage alone rather than a fabricated chapter number.
     *
     * The percentage is the *same* [progressText] the progress bar's fraction goes
     * through, so the two cannot disagree.
     */
    fun positionText(fraction: Float, chapterIndex: Int, chapterCount: Int): String {
        val percent = progressText(fraction)
        if (chapterCount <= 0 || chapterIndex < 0) return percent
        val shown = (chapterIndex + 1).coerceIn(1, chapterCount)
        return "第 $shown / $chapterCount 章 · $percent"
    }

    /**
     * Whether the battery reading deserves the warning colour.
     *
     * Low **and** not charging: a phone plugged in at 5% is not a problem the reader
     * needs to be told about, and colouring it red would teach them to ignore the
     * colour. 15% is Android's own low-battery threshold.
     */
    fun isLowBattery(level: Int, charging: Boolean): Boolean =
        !charging && level in 0..LOW_BATTERY_LEVEL

    /** Android's low-battery notification threshold, used for the tint decision. */
    const val LOW_BATTERY_LEVEL = 15

    // -----------------------------------------------------------------------
    //  The bands the two status lines occupy
    // -----------------------------------------------------------------------

    /** The margin the status lines keep from the screen edge, on a device with no notch. */
    const val EDGE_DP = 10f

    /** The gap between a status line and the body text. */
    const val GAP_DP = 8f

    /**
     * The floor for a status line's height.
     *
     * The line's height arrives as a *measured* dp value (18 sp at the current font
     * scale), and a caller that hands over zero -- a preview, a first frame -- must not
     * collapse the band and drop the first line of prose under the chapter heading.
     */
    const val MIN_LINE_DP = 12f

    /**
     * How much of the screen the reader's own two status lines take, top and bottom.
     *
     * ## Why this is a function rather than two constants in the screen
     *
     * It used to be two constants -- `18.dp` for the line and a sum of them for the
     * band -- while the *text* in those lines was set in `sp`. `sp` scales with the
     * reader's system font size and `dp` does not, so on a phone with the font size
     * turned up (which on Chinese Android builds is one slider, commonly set to 1.3x)
     * the two lines drew 23 dp tall inside an 18 dp reservation, and the body text was
     * laid out underneath them. That is the reported bug: 上方章节 / 下方时间电量 **盖住**
     * 正文. The same mismatch made the footer's own bottom margin double as its
     * clearance, which cost another 18 dp of overlap even at 1.0x.
     *
     * So both numbers are derived here from one line height, and the screen is only
     * allowed to *use* them: the header is padded by [Band.edgeDp] + the line +
     * [Band.gapDp], the footer by [Band.edgeDp], and the body is inset by
     * [Band.topDp] / [Band.bottomDp]. Nothing draws outside the band it reserved.
     *
     * @param cutoutTopDp the hardware cutout, or the status bar when it is visible.
     *   A notch is not hidden by hiding the status bar, so it is always taken; using
     *   `max` of the two means the band also grows on the one frame where the system
     *   puts the bar back (see the resume handling in the reader).
     * @param lineDp one line of status text, in dp, **after** the font scale -- i.e.
     *   `with(density) { 18.sp.toDp() }`, not the literal `18`.
     */
    fun band(
        cutoutTopDp: Float,
        lineDp: Float,
        gapDp: Float = GAP_DP,
        edgeDp: Float = EDGE_DP,
    ): Band {
        val line = lineDp.coerceAtLeast(MIN_LINE_DP)
        val gap = gapDp.coerceAtLeast(0f)
        val edge = edgeDp.coerceAtLeast(0f)
        val cutout = cutoutTopDp.coerceAtLeast(0f)
        return Band(
            lineDp = line,
            gapDp = gap,
            edgeDp = edge,
            // Top: the cutout, the line's top margin, the line itself, the gap below it.
            topDp = cutout + edge + line + gap,
            // Bottom: the line's bottom margin, the line, and the gap above it. The
            // footer is drawn at `edgeDp` from the bottom edge and the line is part of
            // the band, so a body inset of this figure clears the text it draws.
            bottomDp = edge + line + gap,
        )
    }

    /**
     * The reserved top and bottom strips, in dp.
     *
     * @property lineDp the line height the band was computed from, so the text drawn
     *   inside it uses the same measurement the reservation did.
     */
    data class Band(
        val lineDp: Float,
        val gapDp: Float,
        val edgeDp: Float,
        val topDp: Float,
        val bottomDp: Float,
    )
}
