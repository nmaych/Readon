package com.readon.reader.data

/**
 * A small Markdown reader, for release notes.
 *
 * ## Why this exists
 *
 * The update card used to show the release notes by taking their first non-blank
 * line and cutting it at 90 characters. Release notes are written as Markdown --
 * they are the same text that becomes the GitHub release page, extracted from
 * `CHANGELOG.md` by `tools/ci_release.py` -- so what the user actually saw was
 * `### Added`, or worse, a bullet cut off mid-sentence in the middle of a
 * bold marker. The notes are the only place the app explains *what changed*, and
 * showing a fragment of the markup instead of the content makes the update prompt
 * useless exactly when the user is deciding whether to install.
 *
 * ## What it deliberately does not do
 *
 * This is not a CommonMark implementation and does not try to be. It covers what
 * a changelog uses: ATX headings, paragraphs, nested bullets, ordered items,
 * blockquotes, fenced code, horizontal rules, and inline bold / italic /
 * strikethrough / code / links. Everything else is treated as text, which is the
 * safe failure: an unrecognised construct renders as its own source rather than
 * disappearing.
 *
 * ## Why it is free of Android and Compose types
 *
 * The same reason as [AutoRead] and [UpdateDecision]: a parser is something that
 * can be *wrong* rather than merely fail, and it can be exercised on the desktop
 * by `tools/check-book-logic.cmd`. A parser that silently drops a bullet is
 * invisible on a phone -- the notes just look shorter than they should -- and
 * obvious against assertions.
 */
object Markdown {

    /** One block-level element of the document. */
    sealed interface Block {
        /** An ATX heading; [level] is 1..6. */
        data class Heading(val level: Int, val text: String) : Block

        /** A run of consecutive non-blank lines, joined into one paragraph. */
        data class Paragraph(val text: String) : Block

        /** A `- ` / `* ` / `+ ` item. [depth] is its nesting, 0-based. */
        data class Bullet(val text: String, val depth: Int) : Block

        /** A `1. ` / `1) ` item, with the marker kept verbatim for display. */
        data class Numbered(val marker: String, val text: String, val depth: Int) : Block

        /** A `> ` block, with its continuation lines joined. */
        data class Quote(val text: String) : Block

        /** A fenced block, kept verbatim: its contents are not Markdown. */
        data class Code(val language: String?, val text: String) : Block

        /** `---`, `***` or `___`. */
        data object Divider : Block
    }

    /** A run of text with a decoration, possibly containing further runs. */
    sealed interface Span {
        data class Text(val text: String) : Span

        /**
         * Bold / italic / strikethrough.
         *
         * The children are parsed rather than kept as a raw string so that
         * `**`Content-Range` 的解析**` -- which is how the changelog actually writes
         * it -- keeps its code span instead of rendering the backticks literally.
         */
        data class Styled(val style: Style, val spans: List<Span>) : Span

        /** A backtick span. Its text is literal; nothing inside it is parsed. */
        data class Code(val text: String) : Span

        data class Link(val spans: List<Span>, val url: String) : Span
    }

    enum class Style { Bold, Italic, Strikethrough }

    /**
     * How deep a nested list is followed before further indentation is ignored.
     *
     * The notes use two levels at most, and a phone screen runs out of width
     * before it runs out of depth: indenting a fourth level off the right edge
     * would hide the text rather than structure it.
     */
    const val MAX_DEPTH = 3

    /** Characters of indentation that count as one nesting level. */
    private const val INDENT_PER_LEVEL = 2

    /** Parse [source] into block-level elements. */
    fun parse(source: String): List<Block> {
        val out = ArrayList<Block>()
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val paragraph = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                out.add(Block.Paragraph(paragraph.toString().trim()))
                paragraph.setLength(0)
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trimEnd()
            val trimmed = line.trimStart()
            val indent = line.length - trimmed.length

            // --- fenced code -------------------------------------------------
            // Checked before anything else so that a heading or a bullet inside a
            // fence stays part of the code sample.
            val fence = fenceOf(trimmed)
            if (fence != null) {
                flushParagraph()
                val language = trimmed.drop(3).trim().ifBlank { null }
                i++
                val body = StringBuilder()
                while (i < lines.size && !lines[i].trimStart().startsWith(fence)) {
                    body.append(lines[i]).append('\n')
                    i++
                }
                // Consume the closing fence when there is one. An unterminated
                // fence is a real possibility in a hand-edited changelog, and the
                // rest of the document is better shown as code than dropped.
                if (i < lines.size) i++
                out.add(Block.Code(language, body.toString().trimEnd('\n')))
                continue
            }

            if (trimmed.isEmpty()) {
                flushParagraph()
                i++
                continue
            }

            // --- heading -----------------------------------------------------
            val level = headingLevel(trimmed)
            if (level > 0) {
                flushParagraph()
                out.add(
                    Block.Heading(
                        level = level,
                        text = trimmed.drop(level).trim().trimEnd('#').trim(),
                    )
                )
                i++
                continue
            }

            // --- horizontal rule ---------------------------------------------
            if (isDivider(trimmed)) {
                flushParagraph()
                out.add(Block.Divider)
                i++
                continue
            }

            // --- blockquote --------------------------------------------------
            // Continuation lines are folded into one quote: the notes use quotes
            // for a single remark, and a separate block per line would draw a
            // stack of bars for one sentence.
            if (trimmed.startsWith(">")) {
                flushParagraph()
                val body = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    if (body.isNotEmpty()) body.append(' ')
                    body.append(lines[i].trimStart().removePrefix(">").trim())
                    i++
                }
                out.add(Block.Quote(body.toString().trim()))
                continue
            }

            // --- list items --------------------------------------------------
            //
            // A list item owns the indented lines under it, and the changelog
            // relies on that: its bullets wrap at 80 columns and continue on
            // lines indented by two spaces (51 of them in the 1.0.6 section
            // alone). Treating those as ordinary paragraphs would cut every
            // bullet off at its first line and float the rest of the sentence
            // below it as a separate, unindented block -- the text would all
            // still be on screen, which is exactly why this is the kind of bug
            // that survives a glance.
            val bullet = bulletOf(trimmed)
            val numbered = if (bullet == null) numberedOf(trimmed) else null
            if (bullet != null || numbered != null) {
                flushParagraph()
                val itemIndent = indent
                val text = StringBuilder(bullet ?: numbered!!.second)
                i++
                while (i < lines.size) {
                    val next = lines[i]
                    val nextTrimmed = next.trimStart()
                    if (nextTrimmed.isEmpty()) {
                        // A blank line only continues the item if something more
                        // indented follows it -- otherwise the item has ended and
                        // the blank line belongs to the document.
                        val following = lines.drop(i + 1)
                            .firstOrNull { it.isNotBlank() }
                            ?.let { it.length - it.trimStart().length }
                        if (following == null || following <= itemIndent) break
                        i++
                        continue
                    }
                    val nextIndent = next.length - nextTrimmed.length
                    // A nested list, a new heading or a fence is its own block,
                    // not this item's prose, so it stops the continuation however
                    // far it happens to be indented.
                    val endsItem = nextIndent <= itemIndent ||
                        bulletOf(nextTrimmed) != null ||
                        numberedOf(nextTrimmed) != null ||
                        headingLevel(nextTrimmed) > 0 ||
                        fenceOf(nextTrimmed) != null ||
                        isDivider(nextTrimmed) ||
                        nextTrimmed.startsWith(">")
                    if (endsItem) break
                    // Continuation lines are soft wraps of the same sentence, so
                    // they join with a space -- the same rule paragraphs use.
                    if (text.isNotEmpty()) text.append(' ')
                    text.append(nextTrimmed)
                    i++
                }
                val body = text.toString().trim()
                if (numbered != null) {
                    out.add(Block.Numbered(numbered.first, body, depthOf(itemIndent)))
                } else {
                    out.add(Block.Bullet(body, depthOf(itemIndent)))
                }
                continue
            }

            // --- paragraph ---------------------------------------------------
            // Soft-wrapped source lines are joined with a space, which is what
            // Markdown means by them; the changelog wraps its prose at 80 columns.
            if (paragraph.isNotEmpty()) paragraph.append(' ')
            paragraph.append(trimmed)
            i++
        }
        flushParagraph()
        return out
    }

    /** Parse the inline markup of a single block's text. */
    fun inline(source: String): List<Span> {
        val out = ArrayList<Span>()
        val buffer = StringBuilder()

        fun flush() {
            if (buffer.isNotEmpty()) {
                out.add(Span.Text(buffer.toString()))
                buffer.setLength(0)
            }
        }

        var i = 0
        while (i < source.length) {
            val c = source[i]

            // --- code span ---------------------------------------------------
            // First, because backticks suppress every other marker: `` `**` `` is
            // the two-character string "**", not the start of a bold run.
            if (c == '`') {
                val end = source.indexOf('`', i + 1)
                if (end > i) {
                    flush()
                    out.add(Span.Code(source.substring(i + 1, end)))
                    i = end + 1
                    continue
                }
            }

            // --- link ---------------------------------------------------------
            if (c == '[') {
                val close = source.indexOf(']', i + 1)
                if (close > i && close + 1 < source.length && source[close + 1] == '(') {
                    val paren = source.indexOf(')', close + 2)
                    if (paren > close) {
                        flush()
                        val label = source.substring(i + 1, close)
                        val url = source.substring(close + 2, paren).trim()
                        out.add(Span.Link(inline(label), url))
                        i = paren + 1
                        continue
                    }
                }
            }

            // --- emphasis -----------------------------------------------------
            // Two-character delimiters are tested first, or `**bold**` would be
            // read as an italic `*` immediately followed by another.
            val delimiter = when {
                source.startsWith("**", i) -> "**"
                source.startsWith("__", i) -> "__"
                source.startsWith("~~", i) -> "~~"
                c == '*' -> "*"
                c == '_' -> "_"
                else -> null
            }
            if (delimiter != null) {
                val end = source.indexOf(delimiter, i + delimiter.length)
                // An empty run (`**`) and an unclosed one are not emphasis; both
                // are left as literal text so the marker is visible rather than
                // silently swallowed.
                if (end > i + delimiter.length) {
                    flush()
                    val style = when (delimiter) {
                        "~~" -> Style.Strikethrough
                        "**", "__" -> Style.Bold
                        else -> Style.Italic
                    }
                    out.add(Span.Styled(style, inline(source.substring(i + delimiter.length, end))))
                    i = end + delimiter.length
                    continue
                }
            }

            buffer.append(c)
            i++
        }
        flush()
        return out
    }

    /**
     * The notes with all markup removed, as one line of prose.
     *
     * For the places that need a sentence rather than a document -- a one-line
     * summary, a notification -- where rendering the Markdown would be wrong.
     */
    fun plainText(source: String): String {
        val sb = StringBuilder()
        for (block in parse(source)) {
            val text = when (block) {
                is Block.Heading -> block.text
                is Block.Paragraph -> block.text
                is Block.Bullet -> block.text
                is Block.Numbered -> block.text
                is Block.Quote -> block.text
                is Block.Code -> block.text
                Block.Divider -> ""
            }
            if (text.isBlank()) continue
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(flatten(inline(text)))
        }
        return sb.toString()
    }

    /**
     * [plainText], collapsed to at most [maxChars] characters.
     *
     * The ellipsis is only added when something was actually cut, so a short set of
     * notes does not appear to be truncated.
     */
    fun summary(source: String, maxChars: Int): String {
        val text = plainText(source).replace(Regex("\\s+"), " ").trim()
        if (maxChars <= 0 || text.length <= maxChars) return text
        return text.take(maxChars).trimEnd() + "…"
    }

    /** Flatten a span tree back to plain text. */
    private fun flatten(spans: List<Span>): String {
        val sb = StringBuilder()
        for (span in spans) {
            when (span) {
                is Span.Text -> sb.append(span.text)
                is Span.Code -> sb.append(span.text)
                is Span.Styled -> sb.append(flatten(span.spans))
                is Span.Link -> sb.append(flatten(span.spans))
            }
        }
        return sb.toString()
    }

    /** The fence that opens a code block at [trimmed], or null. */
    private fun fenceOf(trimmed: String): String? = when {
        trimmed.startsWith("```") -> "```"
        trimmed.startsWith("~~~") -> "~~~"
        else -> null
    }

    /** The heading level of [trimmed], or 0 when it is not a heading. */
    private fun headingLevel(trimmed: String): Int {
        var n = 0
        while (n < trimmed.length && n < 6 && trimmed[n] == '#') n++
        if (n == 0) return 0
        // `#hashtag` is not a heading; Markdown requires a space (or nothing)
        // after the hashes, and the notes contain no `#`-prefixed words, so the
        // strict rule costs nothing and avoids promoting a stray `#` to a title.
        if (n < trimmed.length && trimmed[n] != ' ') return 0
        return n
    }

    /** Whether [trimmed] is `---`, `***` or `___` (three or more). */
    private fun isDivider(trimmed: String): Boolean {
        val compact = trimmed.replace(" ", "").replace("\t", "")
        if (compact.length < 3) return false
        val c = compact[0]
        if (c != '-' && c != '*' && c != '_') return false
        return compact.all { it == c }
    }

    /** The text of a `- ` item, or null. */
    private fun bulletOf(trimmed: String): String? {
        if (trimmed.length < 1) return null
        val marker = trimmed[0]
        if (marker != '-' && marker != '*' && marker != '+') return null
        if (trimmed.length > 1 && trimmed[1] != ' ') return null
        return trimmed.drop(1).trim()
    }

    /** The marker and text of a `1. ` item, or null. */
    private fun numberedOf(trimmed: String): Pair<String, String>? {
        var digits = 0
        while (digits < trimmed.length && trimmed[digits].isDigit() && digits < 3) digits++
        if (digits == 0 || digits >= trimmed.length) return null
        val delimiter = trimmed[digits]
        if (delimiter != '.' && delimiter != ')') return null
        if (digits + 1 >= trimmed.length || trimmed[digits + 1] != ' ') return null
        val marker = trimmed.substring(0, digits) + delimiter
        return marker to trimmed.drop(digits + 1).trim()
    }

    /** Indentation in characters, as a nesting level. */
    private fun depthOf(indent: Int): Int =
        (indent / INDENT_PER_LEVEL).coerceIn(0, MAX_DEPTH)
}
