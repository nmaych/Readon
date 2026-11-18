package com.readon.reader.data

/**
 * Split a book into chapters.
 *
 * Plain-text Chinese books are almost always one file with a table of contents
 * written inline, so the reader detects chapter headings to build a navigable
 * outline.
 *
 * ## A heading is a whole label, not a prefix
 *
 * The first version of this matched a *prefix*: a line that began with 第 and a
 * numeral and 章 was believed, and the rest of the line was accepted as its
 * title. That misreads prose. "第一章我就看过了，写得很差。" begins with a chapter
 * marker and is a sentence; so is every line of the table of contents at the
 * front of the file. The reader navigates by this outline, so each bogus entry
 * is a wrong turn -- missing a heading is a small annoyance, inventing one is
 * not.
 *
 * ## A heading is not always on its own line
 *
 * Files put the marker anywhere. Sometimes it sits on a line of its own,
 * sometimes it is glued to the end of the previous chapter ("……他走了。第一章
 * 风起"), and some files have no line breaks at all, so *every* heading is in
 * the middle of a paragraph. The scan is therefore positional rather than
 * line-based: a marker is a heading wherever it appears, as long as
 *
 *   * it starts a sentence -- the character before it ends one (。！？…), closes
 *     a quotation, or is whitespace. This is what keeps 他翻到第三章 and
 *     见第三章第二节 out of the outline;
 *   * the text after it is a label rather than the rest of a sentence: it opens
 *     with a noun, not with 的 / 我 / 就 (第一章的写法, 第一章我就看过了);
 *   * it is far enough from the next heading to hold a chapter of prose, which
 *     is what removes the table of contents;
 *   * and its title can be delimited. On a line of its own the line ends it; in
 *     the middle of a paragraph it ends at the first sentence punctuation or at
 *     the end of the first word, because there is no newline to end it.
 *
 * When nothing matches, the book still reads fine -- the reader shows it as one
 * continuous text, with no chapter number in the footer and no outline to
 * navigate.
 */
object Chapters {

    /**
     * A heading plus its range in the source text.
     *
     * [headingEnd] is recorded by [split] rather than recomputed on demand
     * because the reader asks for it once per chapter per frame (see
     * [headingLineEnd]).
     */
    data class Chapter(
        val title: String,
        val start: Int,
        val end: Int,
        val index: Int,
        /** Offset just past the heading text, or [NO_HEADING]. */
        val headingEnd: Int = NO_HEADING,
    ) {
        companion object {
            /** [headingEnd] for a chapter whose title does not appear in the text. */
            const val NO_HEADING: Int = -1
        }
    }

    /** The title given to whatever sits before the first heading. */
    private const val FRONT_MATTER_TITLE = "前言"

    /** Longest line that can still be a heading rather than a paragraph. */
    private const val MAX_HEADING_CHARS = 60

    /** Longest title that may follow a chapter marker, e.g. 第一章 xxx. */
    private const val MAX_TITLE_CHARS = 30

    /** Longest tail a standalone heading may carry, e.g. 番外 一. */
    private const val MAX_NAMED_TAIL = 12

    /**
     * Longest run of words after a heading's first word that is still read as
     * part of the title rather than as the prose behind it.
     *
     * 第一章 风起云涌 天地变色 is a two-part title; 第一章 风起 张三醒来的时候 is a
     * title and a paragraph. The two are the same shape, so the length of what
     * follows -- and whether it has punctuation in it -- is what decides.
     */
    private const val MAX_LABEL_TAIL = 12

    /** Whitespace a heading may carry before it counts as an indented paragraph. */
    private const val MAX_HEADING_INDENT = 4

    /** Longest run of numerals in 第一百二十三回. */
    private const val MAX_NUMERALS = 9

    /** A heading closer than this to the next one cannot hold a chapter. */
    private const val MIN_CHAPTER_CHARS = 120

    /**
     * More headings than this means the scan went wrong, not that the book is
     * long.
     *
     * The old limit was 2000, which a serialized web novel passes easily -- and
     * passing it used to throw the whole outline away, so the longest books in
     * the library were exactly the ones with no chapters at all.
     */
    private const val MAX_CHAPTERS = 20_000

    /** Units that follow 第 and mark a chapter: 章回节卷篇部集话. */
    private const val CHAPTER_UNITS = "章回节卷篇部集话"

    /** Numerals as they are written in 第一百二十三回. */
    private const val CHINESE_NUMERALS = "零〇一二三四五六七八九十百千万两廿卅"

    /**
     * Headings that stand on their own rather than after a number.
     *
     * Matched longest first, so 番外篇 wins over 番外. These are only believed
     * when they are the whole line: 尾声 is an ordinary word, and an inline scan
     * for it would match half the epilogues in the library.
     */
    private val NAMED_HEADINGS: List<String> = listOf(
        "楔子", "序章", "序言", "引子", "前言", "尾声", "结局", "终章", "大结局",
        "后记", "番外篇", "番外", "正文", "附录", "完本感言", "作者的话", "序",
    ).sortedByDescending { it.length }

    /** First characters of [NAMED_HEADINGS], so the scan can skip most lines. */
    private val NAMED_FIRST: Set<Char> = NAMED_HEADINGS.mapTo(HashSet()) { it[0] }

    /**
     * Words that introduce an English chapter heading.
     *
     * Compared case-insensitively against the first word of the line.
     */
    private val ENGLISH_WORDS = setOf("chapter", "part", "book", "volume", "section")

    /**
     * Characters that continue a sentence rather than start a title.
     *
     * Consulted when 第X章 runs straight into text with no separator, where the
     * first character is the only evidence there is: 第一章风起 is a title,
     * 第一章的写法 is a sentence about one. The list is function words -- the
     * particles and verbs that open a predicate -- because a title opens with a
     * noun, and anything broader starts rejecting real titles like 第一章我来了.
     */
    private const val CONTINUATIONS = "的中里是在有和与之就也都被把让说写讲"

    /**
     * Characters that open a sentence rather than a title.
     *
     * [CONTINUATIONS] plus the pronouns and demonstratives. A pronoun is a
     * perfectly possible start for a title (第一章我来了), so these are only held
     * against a heading that has prose after it on the same line
     * (第一章 我就看过了，写得很差) -- which is what an inline heading looks like
     * by definition, so inline headings are held to them too.
     */
    private const val SENTENCE_OPENERS = CONTINUATIONS + "我你他她它这那"

    // -----------------------------------------------------------------------
    // Splitting
    // -----------------------------------------------------------------------

    fun split(text: String): List<Chapter> {
        if (text.isEmpty()) return emptyList()

        // A book whose headings are indented like its paragraphs -- some are --
        // would otherwise come back with no outline at all, so indentation is
        // only held against a line when believing it still leaves an outline.
        var hits = scan(text, allowIndented = false)
        if (hits.size < 2) hits = scan(text, allowIndented = true)
        if (hits.size < 2) return emptyList()

        val kept = dropTableOfContents(hits, text.length)
        if (kept.size < 2 || kept.size > MAX_CHAPTERS) return emptyList()

        val chapters = ArrayList<Chapter>(kept.size + 1)
        var index = 0

        // Anything before the first heading is front matter and becomes chapter 0.
        val firstStart = kept[0].start
        if (firstStart > 0 && hasText(text, 0, firstStart)) {
            chapters.add(Chapter(FRONT_MATTER_TITLE, 0, firstStart, index++))
        }

        for (i in kept.indices) {
            val hit = kept[i]
            val end = if (i + 1 < kept.size) kept[i + 1].start else text.length
            chapters.add(Chapter(hit.title, hit.start, end, index++, hit.headingEnd))
        }
        return chapters
    }

    /** A heading that was found, before the table of contents is sorted out. */
    private class Hit(val title: String, val start: Int, val headingEnd: Int)

    /**
     * Every heading in the text, in reading order.
     *
     * The text is walked by offset rather than split into lines: the obvious
     * `substring(lineStart, lineEnd)` allocated a String for every line of the
     * book -- half a million of them for a long novel -- and this runs on the
     * path that shows "正在排版…". Only a heading is ever copied.
     */
    private fun scan(text: String, allowIndented: Boolean): ArrayList<Hit> {
        val hits = ArrayList<Hit>()
        var lineStart = 0
        while (lineStart < text.length) {
            var lineEnd = text.indexOf('\n', lineStart)
            if (lineEnd < 0) lineEnd = text.length

            var from = lineStart
            var to = lineEnd
            while (from < to && text[from].isWhitespace()) from++
            while (to > from && text[to - 1].isWhitespace()) to--

            if (to > from) {
                collectLine(
                    text = text,
                    from = from,
                    to = to,
                    allowLineStart = allowIndented || !isIndented(text, lineStart, from),
                    out = hits,
                )
            }
            lineStart = lineEnd + 1
        }
        return hits
    }

    /**
     * The headings on one line: the one at the start of it, then any inside it.
     *
     * The indentation rule only applies to the first: a heading that starts a
     * paragraph is flush left, but one in the middle of a paragraph inherits
     * whatever indentation the paragraph has.
     */
    private fun collectLine(
        text: String,
        from: Int,
        to: Int,
        allowLineStart: Boolean,
        out: MutableList<Hit>,
    ) {
        // The scan for further headings starts after this one, not after the
        // first character: "## 第1章 标题" is one heading, and re-reading its own
        // marker would make a two-character chapter out of the hashes.
        var i = from + 1
        if (allowLineStart) {
            val first = headingAt(text, from, to, inline = false)
            if (first != null) {
                out.add(first)
                i = first.headingEnd
            }
        }

        while (i < to) {
            // A bounded scan: `text.indexOf` would walk to the end of the book
            // looking for a 第 this line does not have, once per line.
            var p = i
            while (p < to && text[p] != '第') p++
            if (p >= to) break
            // Only a marker that starts a sentence is one: 他翻到第三章 is prose.
            if (isBoundary(text[p - 1])) {
                val hit = headingAt(text, p, to, inline = true)
                if (hit != null) {
                    out.add(hit)
                    i = hit.headingEnd
                    continue
                }
            }
            i = p + 1
        }
    }

    /**
     * Does this character end a sentence -- or a line -- rather than run on into
     * one? Used as the boundary in front of an inline chapter marker.
     */
    private fun isBoundary(c: Char): Boolean =
        c.isWhitespace() || c == '。' || c == '！' || c == '？' || c == '…' ||
            c == '；' || c == '」' || c == '』' || c == '”' || c == '）' ||
            c == '"' || c == '!'

    /**
     * Is this line indented the way a paragraph is?
     *
     * Chinese prose is indented with two ideographic spaces (U+3000), and a
     * heading never is: a heading is centred or flush left. A single space is
     * tolerated, because plenty of files indent their headings by one.
     */
    private fun isIndented(text: String, lineStart: Int, from: Int): Boolean {
        if (from - lineStart > MAX_HEADING_INDENT) return true
        for (i in lineStart until from) if (text[i] == '\u3000') return true
        return false
    }

    /**
     * Drop the table of contents at the front of the file.
     *
     * Its entries *are* headings -- they are the same lines as the chapter titles
     * further down -- but the gap between two of them is a line or two rather
     * than a chapter. Measuring that gap is what separates an entry from the
     * chapter it names.
     *
     * The first entry is not exempt, and used to be: keeping it made chapter 1
     * of every book with a table of contents a duplicate of chapter 1, spanning
     * the rest of the contents.
     */
    private fun dropTableOfContents(hits: List<Hit>, textLength: Int): List<Hit> {
        val kept = ArrayList<Hit>(hits.size)
        for (i in hits.indices) {
            val next = if (i + 1 < hits.size) hits[i + 1].start else textLength
            if (next - hits[i].start >= MIN_CHAPTER_CHARS) kept.add(hits[i])
        }
        // A book of very short chapters -- poems, fragments -- would lose its
        // entire outline to that rule, and a line that already reads as a heading
        // is better evidence than a gap. The rule only applies while it leaves
        // something behind.
        return if (kept.size >= 2) kept else hits
    }

    private fun hasText(text: String, from: Int, to: Int): Boolean {
        for (i in from until to) if (!text[i].isWhitespace()) return true
        return false
    }

    // -----------------------------------------------------------------------
    // What a heading looks like
    // -----------------------------------------------------------------------

    /**
     * The heading that starts at [from] and runs no further than [to], or null.
     *
     * [inline] says the marker is inside a line rather than at the start of one.
     * Only a numbered marker is believed there: 尾声, 结局 and 前言 are ordinary
     * words, and an inline scan for them would match half the prose in the
     * library.
     */
    private fun headingAt(text: String, from: Int, to: Int, inline: Boolean): Hit? {
        if (text[from] == '第') return numberedHeading(text, from, to, inline)
        if (inline) return null
        return when (text[from]) {
            // An explicit marker needs no guessing about the rest of the line.
            '#' -> markdownHeading(text, from, to)
            'C', 'c', 'P', 'p', 'B', 'b', 'V', 'v', 'S', 's' -> englishHeading(text, from, to)
            else -> if (text[from] in NAMED_FIRST) namedHeading(text, from, to) else null
        }
    }

    /**
     * 第一章 / 第 12 章 / 第一百二十三回 标题
     *
     * ## Where the title ends
     *
     * A heading on a line of its own takes the whole line: 第一章 风起云涌 天地变色
     * is one label. A heading that has prose after it on the same line cannot --
     * 第一章 风起 张三醒来的时候，天还没亮。 has a title of two characters and a
     * paragraph behind it -- so there the label is the first word, which is what
     * "第一章 风起" is.
     */
    private fun numberedHeading(text: String, from: Int, to: Int, inline: Boolean): Hit? {
        var i = from + 1
        while (i < to && text[i].isWhitespace()) i++
        val numeralsStart = i
        while (i < to && isNumeral(text[i])) i++
        val numerals = i - numeralsStart
        if (numerals == 0 || numerals > MAX_NUMERALS) return null

        while (i < to && text[i].isWhitespace()) i++
        if (i >= to || !CHAPTER_UNITS.contains(text[i])) return null
        i++

        // Whatever follows the unit is the title, separated by a space, a colon,
        // or nothing at all.
        var separated = false
        while (i < to && text[i].isWhitespace()) {
            i++
            separated = true
        }
        if (i < to && isTitleSeparator(text[i])) {
            i++
            separated = true
            while (i < to && text[i].isWhitespace()) i++
        }

        // 第一章 on a line of its own is still a heading; in the middle of a
        // paragraph a marker with no title is a reference to one.
        if (i >= to) {
            if (inline) return null
            return Hit(text.substring(from, to), from, to)
        }
        val titleStart = i

        // The label is the first run of text: to the end of the line, a space,
        // or the first sentence punctuation.
        var titleEnd = titleStart
        while (titleEnd < to && !text[titleEnd].isWhitespace() && !endsSentence(text[titleEnd])) titleEnd++
        if (titleEnd - titleStart > MAX_TITLE_CHARS) return null

        var rest = titleEnd
        while (rest < to && text[rest].isWhitespace()) rest++
        // Prose after the label, or a longer label? A label is a few words with
        // no sentence punctuation in it (第一章 风起云涌 天地变色); a paragraph
        // runs on, and has commas and full stops.
        val proseFollows = rest < to &&
            (to - rest > MAX_LABEL_TAIL || hasBreak(text, rest, to))

        // 第一章的写法 / 第一章我就看过了，写得很差 -- the marker runs into a
        // predicate, so the line is a sentence about a chapter, not a chapter.
        if (!separated && CONTINUATIONS.contains(text[titleStart])) return null
        // 第一章 我读了三遍，觉得很好 -- the same, one step further out: only a
        // heading with prose behind it is judged on this, because 第一章我来了 on
        // a line of its own is a title.
        if (proseFollows && SENTENCE_OPENERS.contains(text[titleStart])) return null

        if (proseFollows) return Hit(text.substring(from, titleEnd), from, titleEnd)

        // Nothing follows: the whole line is the title.
        if (to - from > MAX_HEADING_CHARS) return null
        if (hasBreak(text, titleStart, to)) return null
        return Hit(text.substring(from, to), from, to)
    }

    /**
     * Chapter 12 / CHAPTER XII: 标题
     *
     * The number must be a number: "Chapter one of the reasons" is prose.
     */
    private fun englishHeading(text: String, from: Int, to: Int): Hit? {
        if (endsSentence(text[to - 1])) return null

        var i = from
        while (i < to && text[i].isLetter()) i++
        if (i == from || i >= to || !text[i].isWhitespace()) return null
        if (text.substring(from, i).lowercase() !in ENGLISH_WORDS) return null

        while (i < to && text[i].isWhitespace()) i++
        val numberStart = i
        while (i < to && !text[i].isWhitespace() && text[i] != ':' && text[i] != '.') i++
        val number = text.substring(numberStart, i)
        if (number.isEmpty() || number.length > 8) return null
        if (!number.all { it.isDigit() } && !number.all { it in "ivxlcdmIVXLCDM" }) return null

        while (i < to && text[i].isWhitespace()) i++
        if (i < to && (text[i] == ':' || text[i] == '.')) {
            i++
            while (i < to && text[i].isWhitespace()) i++
        }
        val titleLength = to - i
        if (titleLength > MAX_TITLE_CHARS) return null
        if (hasBreak(text, i, to)) return null
        return Hit(text.substring(from, to), from, to)
    }

    /**
     * 楔子 / 番外一 / 尾声 大结局
     *
     * Matched whole: 前言不搭后语 is a sentence, and only an exact name (or a
     * name plus a short tail after a separator) is a heading.
     */
    private fun namedHeading(text: String, from: Int, to: Int): Hit? {
        if (endsSentence(text[to - 1])) return null

        for (name in NAMED_HEADINGS) {
            val nameEnd = from + name.length
            if (nameEnd > to) continue
            if (!text.regionMatches(from, name, 0, name.length)) continue
            if (nameEnd == to) return Hit(text.substring(from, to), from, to)

            var i = nameEnd
            // 番外三 / 番外 3
            if (isNumeral(text[i])) {
                while (i < to && isNumeral(text[i])) i++
                if (i == to) return Hit(text.substring(from, to), from, to)
            }
            if (!text[i].isWhitespace() && !isTitleSeparator(text[i])) continue

            while (i < to && (text[i].isWhitespace() || isTitleSeparator(text[i]))) i++
            if (i >= to) return Hit(text.substring(from, to), from, to)
            if (to - i > MAX_NAMED_TAIL) continue
            if (hasBreak(text, i, to)) continue
            return Hit(text.substring(from, to), from, to)
        }
        return null
    }

    /** `# 标题`, `## 标题`: the hash is the marker, so the title drops it. */
    private fun markdownHeading(text: String, from: Int, to: Int): Hit? {
        var i = from
        while (i < to && text[i] == '#') i++
        if (i - from > 6) return null
        if (i >= to || !text[i].isWhitespace()) return null
        while (i < to && text[i].isWhitespace()) i++
        // "###" on its own is a rule, not a heading.
        if (i >= to) return null
        return Hit(text.substring(i, to), from, to)
    }

    private fun isNumeral(c: Char): Boolean = c.isDigit() || CHINESE_NUMERALS.contains(c)

    /** Punctuation that separates a marker from its title. */
    private fun isTitleSeparator(c: Char): Boolean =
        c == ' ' || c == '\u3000' || c == '：' || c == ':' || c == '、' || c == '·' ||
            c == '・' || c == '-' || c == '—' || c == '｜' || c == '|' || c == '.' ||
            c == '．' || c == '「' || c == '“'

    /**
     * Punctuation that ends a sentence, and therefore a title.
     *
     * This is the single most effective rule here: a hard-wrapped paragraph line
     * almost always ends in one of these, and so does the clause that follows an
     * inline heading.
     */
    private fun endsSentence(c: Char): Boolean =
        c == '。' || c == '，' || c == '、' || c == '；' || c == '：' || c == '！' ||
            c == '？' || c == '…' || c == '.' || c == ',' || c == ';' || c == ':' ||
            c == '!' || c == '?' || c == '．'

    /** Punctuation that has no place inside a title. */
    private fun breaksTitle(c: Char): Boolean =
        c == '。' || c == '，' || c == '；' || c == '！' || c == '？' || c == '…'

    private fun hasBreak(text: String, from: Int, to: Int): Boolean {
        for (i in from until to) if (breaksTitle(text[i])) return true
        return false
    }

    // -----------------------------------------------------------------------
    // Looking chapters up
    // -----------------------------------------------------------------------

    /**
     * Which chapter contains [offset]? Returns -1 when the book has no outline.
     *
     * Binary search: chapters are in reading order, and this runs on every page
     * turn, every scroll and every restore -- a serialized novel has thousands
     * of them.
     */
    fun indexAt(chapters: List<Chapter>, offset: Int): Int {
        if (chapters.isEmpty()) return -1
        var lo = 0
        var hi = chapters.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (chapters[mid].end > offset) hi = mid else lo = mid + 1
        }
        return chapters[lo].index
    }

    /**
     * Position in [chapters] of the first chapter that starts at or after
     * [offset], or `chapters.size` when there is none.
     *
     * For callers that annotate a range of text: starting their walk at the top
     * of a 3000-chapter book is 3000 comparisons per paragraph, per frame.
     */
    fun firstAtOrAfter(chapters: List<Chapter>, offset: Int): Int {
        var lo = 0
        var hi = chapters.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (chapters[mid].start < offset) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Where the heading of [chapter] ends in [text], or -1 if it has none.
     *
     * The reader draws a chapter's heading larger and bolder than the prose under
     * it, and the heading is not a node of its own -- it is simply where the
     * chapter starts. This is the range that has to be emphasised, and it is
     * computed once, in [split], so both reading modes agree on it and neither
     * pays for it on every frame.
     *
     * The range ends where the title ends, which is the end of the line for a
     * heading on its own line and the end of the label for one that has prose
     * behind it. It is deliberately *not* the end of the line in the second case:
     * bolding the first sentence of the chapter is exactly the mistake this is
     * here to avoid.
     *
     * The `-1` is not defensive. [split] synthesises a 前言 chapter for whatever
     * sits before the first real heading, and that chapter has no heading -- the
     * word appears nowhere in the book.
     */
    fun headingLineEnd(text: String, chapter: Chapter): Int {
        val recorded = chapter.headingEnd
        if (recorded > chapter.start && recorded <= text.length) return recorded

        // A chapter built by hand -- a test, or a format with its own outline --
        // carries no recorded range. Fall back to the first line, but only when
        // it reads back as the title, which is what tells a real heading from the
        // synthesised 前言.
        if (chapter.start < 0 || chapter.start >= text.length) return -1
        var end = text.indexOf('\n', chapter.start)
        if (end < 0 || end > chapter.end) end = chapter.end
        var from = chapter.start
        var to = end
        while (from < to && text[from].isWhitespace()) from++
        while (to > from && text[to - 1].isWhitespace()) to--
        if (to - from != chapter.title.length) return -1
        return if (text.regionMatches(from, chapter.title, 0, chapter.title.length)) to else -1
    }
}
