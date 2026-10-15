package com.readon.reader.reader

/**
 * The rules behind automatic reading.
 *
 * Auto-read advances the book on a timer: one page every [delayMillis] in paged
 * mode, one screenful in scrolling mode. The reader owns the timer; this object
 * owns the decisions that can be wrong independently of any timer, which is why
 * it is free of Android and Compose types and is exercised by
 * `tools/check-book-logic.cmd` on the desktop.
 *
 * The rule worth stating out loud is the clamp. A stored interval outside
 * [MIN_SECONDS]..[MAX_SECONDS] is not honoured literally: zero (or a negative)
 * would turn pages as fast as the device can, so the book would appear to flick
 * itself to the end, and an hour per page is indistinguishable from the feature
 * being broken. A preference written by a future version -- or hand-edited --
 * therefore still produces a sane pace rather than a runaway.
 *
 * The end of the book is handled by the reader, not here: auto-read stops on the
 * last page instead of wrapping to the first. Silently restarting a novel from
 * page one, with the saved reading position following it, is a way to lose
 * someone's place while they were not looking.
 *
 * ## The two modes move differently, on purpose
 *
 * Paged mode is a discrete act: a page is either turned or it is not, so the
 * interval is spent waiting and the turn is instantaneous.
 *
 * Scrolling mode has no pages, so its unit is one screenful -- but it must not
 * *behave* like a page turn. Standing still for the whole interval and then
 * jumping a screenful in a fraction of a second is a page turn with an animation
 * attached, and it reads as a lurch. The scroll instead spends **the entire
 * interval moving**, at a constant speed, so the text crawls continuously and
 * there is no moment where it jumps. See [SCROLL_FRACTION] and
 * [scrollDurationMillis].
 */
object AutoRead {

    /** Fastest automatic page turn that is still readable. */
    const val MIN_SECONDS = 3

    /** Slowest, before it stops being automatic at all. */
    const val MAX_SECONDS = 60

    /** What a reader who has never touched the setting gets. */
    const val DEFAULT_SECONDS = 15

    /**
     * How much of a screenful one automatic scroll advances.
     *
     * A little under a full screenful, so the first line of the next screen is one
     * the reader has already seen: advancing by exactly a screenful risks the new
     * top line being one that was cut in half at the bottom, and a line that was
     * never fully visible is a line that was never read.
     */
    const val SCROLL_FRACTION = 0.92f

    /** [seconds] brought inside the usable range. */
    fun clampSeconds(seconds: Int): Int = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

    /** How long to stay on one page, in milliseconds. */
    fun delayMillis(seconds: Int): Long = clampSeconds(seconds) * 1000L

    /**
     * How long one automatic scroll step takes, in milliseconds.
     *
     * The step covers [SCROLL_FRACTION] of a screenful, and it takes the whole
     * interval: the slide is continuous, so the reader sees constant motion rather
     * than a pause followed by a jump. That is the entire point of the mode --
     * "缓慢滑动" is a speed and a smoothness, and neither is achievable if the
     * movement is compressed into a fraction of the interval.
     *
     * Because the duration is the interval, the *average* speed is unchanged from
     * the old stop-start behaviour: the setting still means "one screenful per
     * interval". What changes is that the screen is never still and never lurches.
     */
    fun scrollDurationMillis(seconds: Int): Long = delayMillis(seconds)

    /**
     * The speed automatic scrolling moves at, in dp per second.
     *
     * Exposed so the "it is slow" claim can be checked rather than asserted: the
     * old behaviour moved the same distance in 700 ms, which is roughly
     * `viewportDp / 0.7` -- over a thousand dp/s on a phone -- and this is the
     * number that says the replacement is an order of magnitude gentler.
     */
    fun scrollSpeedDpPerSecond(seconds: Int, viewportDp: Float): Float =
        viewportDp * SCROLL_FRACTION / clampSeconds(seconds)

    /**
     * The interval to show for a stored value, as a whole number of seconds.
     *
     * The label and the behaviour come from the same clamp, so the panel cannot
     * claim "3 秒" while the reader waits a stored 0.
     */
    fun label(seconds: Int): String = "${clampSeconds(seconds)} 秒/页"
}
