package com.readon.reader.data

/**
 * Split a book into chapters.
 *
 * Plain-text Chinese books are almost always one file with a table of contents
 * written inline, so the reader detects chapter headings to build a navigable
 * outline. The patterns below are the conventions that actually appear in the
 * wild, ordered from most to least specific; the first match wins.
 *
 * When nothing matches, the book still reads fine -- [Chapter.index] is -1 and
 * the reader falls back to a single implicit chapter covering the whole text.
 */
object Chapters {

    /** A heading plus its match position in the source text. */
    data class Chapter(
        val title: String,
        val start: Int,
        val end: Int,
        val index: Int,
    )

    private val PATTERNS: List<Regex> = listOf(
        // 第一章 / 第1章 / 第一百二十三回 -- with or without a following title
        Regex("""^\s{0,6}第\s*[0-9零一二三四五六七八九十百千两]{1,8}\s*[章回节卷篇部集]\s*[^\n]{0,40}$"""),
        // Chapter 12
        Regex("""^\s{0,6}Chapter\s+[0-9IVXLC]{1,6}\b[^\n]{0,40}$""", RegexOption.IGNORE_CASE),
        // 楔子 / 序章 / 尾声 / 番外 / 后记, which usually stand alone on a line
        Regex("""^\s{0,6}(楔子|序章|序言|前言|引子|尾声|结局|后记|番外[一二三四五六七八九十0-9]{0,3})\s*$"""),
        // Markdown headings, for the .md books in the library
        Regex("""^#{1,3}\s+\S[^\n]{0,60}$"""),
    )

    /** A chapter is only believed if it is plausible: not a whole novel, not one line. */
    private const val MIN_CHAPTER_CHARS = 120
    private const val MAX_CHAPTERS = 2000

    /** Longest line that can still be a heading rather than a paragraph. */
    private const val MAX_HEADING_CHARS = 60

    fun split(text: String): List<Chapter> {
        if (text.isEmpty()) return emptyList()

        val hits = ArrayList<Pair<String, Int>>()
        // Scan by line *without* materialising each one. The obvious
        // `substring(lineStart, lineEnd)` allocated a String for every line of the
        // book -- half a million of them for a long novel -- and this runs on the
        // path that shows "正在排版…". Only lines short enough to be a heading are
        // ever turned into a String.
        var lineStart = 0
        while (lineStart <= text.length) {
            var lineEnd = text.indexOf('\n', lineStart)
            if (lineEnd < 0) lineEnd = text.length
            val length = lineEnd - lineStart
            // Only consider short lines: a 200-character "line" that happens to
            // start with 第一章 is a paragraph, not a heading.
            if (length in 1..MAX_HEADING_CHARS) {
                val line = text.substring(lineStart, lineEnd)
                for (p in PATTERNS) {
                    if (p.matches(line)) {
                        hits.add(line.trim() to lineStart)
                        break
                    }
                }
            }
            if (lineEnd >= text.length) break
            lineStart = lineEnd + 1
        }

        // Discard a table of contents at the front of the file: those entries are
        // short and clustered, and treating them as chapters would produce an
        // outline of dozens of empty sections. A heading is dropped when the gap
        // to the next heading is far too small to hold a chapter of prose.
        val filtered = ArrayList<Pair<String, Int>>()
        for (i in hits.indices) {
            val (title, pos) = hits[i]
            val nextPos = if (i + 1 < hits.size) hits[i + 1].second else text.length
            if (nextPos - pos >= MIN_CHAPTER_CHARS || filtered.isEmpty()) {
                filtered.add(title to pos)
            }
        }

        if (filtered.size < 2) return emptyList()
        if (filtered.size > MAX_CHAPTERS) return emptyList()

        // Anything before the first heading is front matter and becomes chapter 0.
        val chapters = ArrayList<Chapter>(filtered.size + 1)
        var index = 0
        val firstPos = filtered.first().second
        if (firstPos > 0) {
            val head = text.substring(0, firstPos).trim()
            if (head.isNotEmpty()) {
                chapters.add(Chapter("前言", 0, firstPos, index++))
            }
        }
        for (i in filtered.indices) {
            val (title, start) = filtered[i]
            val end = if (i + 1 < filtered.size) filtered[i + 1].second else text.length
            chapters.add(Chapter(title, start, end, index++))
        }
        return chapters
    }

    /** Which chapter contains [offset]? Returns -1 when the book has no outline. */
    fun indexAt(chapters: List<Chapter>, offset: Int): Int {
        if (chapters.isEmpty()) return -1
        for (c in chapters) {
            if (offset < c.end) return c.index
        }
        return chapters.last().index
    }
}
