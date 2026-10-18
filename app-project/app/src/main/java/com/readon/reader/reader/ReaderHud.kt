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
}
