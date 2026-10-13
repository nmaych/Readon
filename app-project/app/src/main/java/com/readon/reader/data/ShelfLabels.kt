package com.readon.reader.data

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The two extra facts the shelf shows about a book: how far through its chapters
 * the reader is, and when it was last opened.
 *
 * Kept as pure functions in the `data` package, with no Android or Compose types,
 * for the same reason [Chapters] and [TextCodec] are: `tools/check-book-logic.cmd`
 * compiles this exact file and checks it on the desktop. Date arithmetic is easy
 * to get subtly wrong -- "昨天" is not "24 hours ago", and a test that runs at
 * 00:05 would catch the difference -- so it is checked rather than eyeballed.
 *
 * Everything that depends on the current time takes it as an argument, so the
 * checks are deterministic instead of depending on when they run.
 */
object ShelfLabels {

    /**
     * "12/340" -- the chapter the reader is in out of the book's total.
     *
     * Returns null when there is nothing honest to show: a book that has never
     * been opened (no position recorded), or one whose outline is unknown because
     * it has not been read since this field was introduced. Showing "0/0" or
     * "1/0" would be worse than showing nothing.
     */
    fun chapterProgress(book: Book): String? {
        val total = book.chapterCount
        if (total <= 0) return null
        // -1 means the book has no detectable outline, which is a real state (a
        // txt with no 第X章 headings) rather than a missing value -- and a book
        // with no outline reports a total of 0, so this pair is only reachable
        // from a stale record. Show nothing rather than a made-up position.
        val current = book.chapterIndex
        if (current < 0) return null
        // A stored index can outlive a revision of the book upstream, so it is
        // clamped rather than trusted.
        val shown = (current + 1).coerceIn(1, total)
        return "$shown/$total 章"
    }

    /**
     * When the book was last opened, relative to [now].
     *
     * Deliberately relative for anything recent -- "今天 14:30" answers "did I read
     * this today?" at a glance, which a full date does not -- and absolute past a
     * week, where a relative form stops being useful.
     *
     * @return null when the book has never been read.
     */
    fun lastRead(millis: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): String? {
        if (millis <= 0L) return null
        // A clock change (or a restored backup) can put the stamp in the future,
        // which makes `days` negative. Anything at or below zero reads as "今天":
        // a clock skewed by a few minutes must not print "-1 天前", and a stamp
        // that far ahead is a data problem the shelf cannot explain anyway.
        val days = daysBetween(millis, now, zone)
        val time = timeOfDay(millis, zone)
        return when {
            days <= 0 -> "今天 $time"
            days == 1L -> "昨天 $time"
            days < 7L -> "$days 天前"
            else -> date(millis, now, zone)
        }
    }

    /** "上次阅读：今天 14:30", or null when the book has never been opened. */
    fun lastReadLine(
        millis: Long,
        now: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): String? = lastRead(millis, now, zone)?.let { "上次阅读：$it" }

    // -----------------------------------------------------------------------

    /** Whole calendar days from [from] to [to]; negative when [from] is later. */
    private fun daysBetween(from: Long, to: Long, zone: TimeZone): Long {
        val a = Calendar.getInstance(zone).apply { timeInMillis = from }
        val b = Calendar.getInstance(zone).apply { timeInMillis = to }
        // Zero out the time of day so the difference counts calendar days rather
        // than 24-hour blocks: 23:50 and 00:10 the next day are 20 minutes and
        // one day apart at the same time.
        for (c in listOf(a, b)) {
            c.set(Calendar.HOUR_OF_DAY, 0)
            c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
        }
        // Round rather than truncate: a DST transition makes a "day" 23 or 25
        // hours long, and integer division would report 0 for a real day.
        return Math.round((b.timeInMillis - a.timeInMillis).toDouble() / DAY_MS)
    }

    private fun timeOfDay(millis: Long, zone: TimeZone): String =
        format(millis, zone, "HH:mm")

    private fun date(millis: Long, now: Long, zone: TimeZone): String {
        val sameYear = year(millis, zone) == year(now, zone)
        return format(millis, zone, if (sameYear) "M月d日" else "yyyy年M月d日")
    }

    private fun year(millis: Long, zone: TimeZone): Int =
        Calendar.getInstance(zone).apply { timeInMillis = millis }.get(Calendar.YEAR)

    private fun format(millis: Long, zone: TimeZone, pattern: String): String =
        java.text.SimpleDateFormat(pattern, Locale.getDefault())
            .apply { timeZone = zone }
            .format(java.util.Date(millis))

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
