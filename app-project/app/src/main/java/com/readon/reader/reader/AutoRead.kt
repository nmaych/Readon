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
 */
object AutoRead {

    /** Fastest automatic page turn that is still readable. */
    const val MIN_SECONDS = 3

    /** Slowest, before it stops being automatic at all. */
    const val MAX_SECONDS = 60

    /** What a reader who has never touched the setting gets. */
    const val DEFAULT_SECONDS = 15

    /** [seconds] brought inside the usable range. */
    fun clampSeconds(seconds: Int): Int = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

    /** How long to stay on one page, in milliseconds. */
    fun delayMillis(seconds: Int): Long = clampSeconds(seconds) * 1000L

    /**
     * The interval to show for a stored value, as a whole number of seconds.
     *
     * The label and the behaviour come from the same clamp, so the panel cannot
     * claim "3 秒" while the reader waits a stored 0.
     */
    fun label(seconds: Int): String = "${clampSeconds(seconds)} 秒/页"
}
