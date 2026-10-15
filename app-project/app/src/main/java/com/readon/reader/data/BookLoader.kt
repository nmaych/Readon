package com.readon.reader.data

import android.content.Context
import com.readon.reader.net.GithubClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * A book that has been turned into something the reader can show.
 *
 * [chapters] is empty for formats with no detectable outline, in which case the
 * whole of [text] is one implicit chapter.
 */
data class BookContent(
    val title: String,
    val text: String,
    val chapters: List<Chapters.Chapter>,
    val charset: String,
    val notice: String? = null,
) {
    /** Total characters, used to turn a character offset into a percentage. */
    val length: Int get() = text.length
}

/** Raised when a format cannot be shown in the reader. */
class UnsupportedBookException(message: String) : Exception(message)

/**
 * Turns a [Book] into [BookContent].
 *
 * Sources, in order of preference: the local copy if there is one, otherwise the
 * network. Format handling:
 *
 *   * `txt` / `md` -- decoded with charset detection; Markdown keeps its
 *     structure because the heading patterns are what [Chapters] looks for.
 *   * `html` -- tags are stripped to readable text so it lands in the same
 *     paginated reader rather than a WebView.
 *   * `epub` -- the zip is walked for its OPF spine, and each spine document
 *     becomes a chapter.
 *   * `pdf` -- not rendered here. See [UnsupportedBookException].
 *
 * ## Why online reading fetches a prefix, not the file
 *
 * The first version fetched the whole book into a `ByteArray` and then decoded
 * it, which is two full copies of a file that can be 75 MB before a single
 * character reaches the reader -- and the second copy is a `String` of up to
 * three bytes per character. That is what made "在线阅读" stall on "正在排版…" and
 * then die on low-memory devices.
 *
 * It was then changed to stream the whole file to disk and decode from there,
 * which fixed the memory problem but left the *wait*: a 75 MB novel on a phone
 * connection is minutes of "正在下载… 3%" before the first page. So a text book is
 * now fetched in pieces by [OnlineFetcher]: a prefix large enough to read for a
 * long while is all that is needed to open the book, and the remainder arrives in
 * the background. Decoding still happens from disk, so peak memory is one
 * character buffer and the result.
 *
 * EPUB is the exception and stays in memory, because reading a zip genuinely
 * needs random access to the bytes.
 *
 * @param onStage reports what is happening, so the reader can say "正在下载…"
 *   rather than showing a single unchanging "正在排版…".
 */
class BookLoader(
    private val context: Context,
    private val client: GithubClient,
    /**
     * Where a book read online is kept between reads, and how its remainder is
     * fetched once a prefix is on screen.
     *
     * Optional so the loader still works without one; when absent, an online book
     * is fetched fresh every time, which is what every version before 1.0.5 did.
     */
    private val fetcher: OnlineFetcher? = null,
) {

    /** The text that was loaded, and whether more of the book is still coming. */
    data class Loaded(
        val content: BookContent,
        /**
         * True when only part of the file was on disk, so the rest is being
         * fetched. The reader uses it to say "正在后台下载…" and to know that the
         * end of what it has is not the end of the book.
         */
        val partial: Boolean = false,
        /**
         * Fetch the remainder and hand back the full text, or null when there is
         * nothing left to fetch or no fetcher configured.
         */
        val finish: (suspend () -> BookContent?)? = null,
    )

    suspend fun load(book: Book, onStage: (String) -> Unit = {}): Loaded =
        withContext(Dispatchers.IO) {
            when (book.formatEnum) {
                BookFormat.TXT, BookFormat.MD -> textContent(book, stripMarkup = false, onStage)
                BookFormat.HTML -> textContent(book, stripMarkup = true, onStage)
                BookFormat.EPUB -> epubContent(book, onStage)
                BookFormat.PDF -> throw UnsupportedBookException(
                    "PDF 暂不支持在线阅读。请先下载，再用系统应用打开。"
                )
                null -> throw UnsupportedBookException("不支持的文件格式")
            }
        }

    /**
     * The local copy, or null when there is none and the network must be used.
     *
     * A record can outlive its file -- a manual cleanup, or a restored backup --
     * and a locally imported book has no remote to fall back to, so that case is
     * reported plainly instead of surfacing as a network error.
     */
    private fun localFileOf(book: Book): File? {
        val local = book.localFile ?: return null
        val f = File(local)
        if (f.exists() && f.length() > 0) return f
        if (book.isLocal) {
            throw UnsupportedBookException("文件已不在设备上，可能被清理掉了。请重新从本地导入。")
        }
        return null
    }

    private suspend fun textContent(
        book: Book,
        stripMarkup: Boolean,
        onStage: (String) -> Unit,
    ): Loaded {
        val local = localFileOf(book)
        if (local != null) {
            onStage("正在排版…")
            return Loaded(decodeText(book, local, stripMarkup))
        }

        // A book that is already complete on this device -- read to the end once,
        // or downloaded to the shelf -- is never fetched again. This is checked
        // before anything touches the network, because it is the common case for
        // a book the reader is coming back to.
        val complete = fetcher?.completeCopy(book)
        if (complete != null) {
            onStage("正在读取缓存…")
            return Loaded(decodeText(book, complete, stripMarkup))
        }

        // No fetcher configured: the old behaviour, a straight whole-file fetch.
        val fetcher = this.fetcher
            ?: return Loaded(decodeWholeFile(book, stripMarkup, onStage))

        onStage("正在连接线路…")
        val fetched = fetcher.ensureReadable(book, onStage)
        onStage("正在排版…")
        // Decode what is on disk now. For a partial file that is a prefix, and
        // the chapter split over it is provisional -- which is exactly why the
        // reader is handed a way to reload once the rest has arrived, rather
        // than being told the book is finished.
        val content = decodeText(book, fetched.file, stripMarkup, partial = !fetched.complete)
        if (fetched.complete) {
            // Everything is here already, so there is nothing to finish; promote
            // it so the next open is local.
            fetcher.promote(book, fetched.file)
            return Loaded(content)
        }
        return Loaded(
            content = content,
            partial = true,
            finish = {
                val file = fetcher.awaitComplete(book)
                // Decoding a whole novel is seconds of work; it must not happen on
                // whatever dispatcher is awaiting this.
                if (file == null) null
                else withContext(Dispatchers.IO) { decodeText(book, file, stripMarkup) }
            },
        )
    }

    /** The pre-1.0.6 path: fetch the whole file, then decode it. */
    private suspend fun decodeWholeFile(
        book: Book,
        stripMarkup: Boolean,
        onStage: (String) -> Unit,
    ): BookContent {
        onStage("正在连接线路…")
        val scratch = File(context.cacheDir, "readon-online")
            .apply { mkdirs() }
            .let { File(it, "online-${book.id.hashCode()}.${book.format}") }
        try {
            onStage("正在下载…")
            client.downloadTo(
                source = book.source,
                path = book.path,
                target = scratch,
                expectedSize = book.size,
                onProgress = { p -> onStage("正在下载… ${(p * 100).toInt()}%") },
                onStage = onStage,
            )
            onStage("正在排版…")
            return decodeText(book, scratch, stripMarkup)
        } finally {
            runCatching { if (scratch.exists()) scratch.delete() }
        }
    }

    private fun decodeText(
        book: Book,
        file: File,
        stripMarkup: Boolean,
        /**
         * True when [file] is a prefix of the book rather than the whole thing.
         *
         * A prefix ends wherever the byte count ran out, which is usually in the
         * middle of a character: the streaming decoder turns those trailing bytes
         * into one U+FFFD, and the reader would draw it at the foot of the last
         * page it has. Only for a partial file is that character an artefact
         * rather than a property of the book, so only then is it removed.
         */
        partial: Boolean = false,
    ): BookContent {
        val decoded = TextCodec.decodeFile(file)
        val raw = if (stripMarkup) HtmlText.strip(decoded.text) else decoded.text
        val normalised = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val text = if (partial) PartialRead.trimDanglingReplacement(normalised) else normalised
        val chapters = Chapters.split(text)
        return BookContent(
            title = book.title,
            text = text,
            chapters = chapters,
            charset = decoded.charset,
        )
    }

    /**
     * Read an EPUB.
     *
     * The spec allows the OPF to sit anywhere and to be named anything, so the
     * path is discovered through `META-INF/container.xml` rather than guessed.
     * Spine order, not file order, is what defines reading order.
     */
    private suspend fun epubContent(book: Book, onStage: (String) -> Unit): Loaded {
        val local = localFileOf(book)
        val bytes = if (local != null) {
            onStage("正在排版…")
            local.readBytes()
        } else {
            // The same cache as the text formats, so a re-read of an online EPUB
            // does not fetch the zip again.
            val cached = fetcher?.completeCopy(book)
            if (cached != null) {
                onStage("正在读取缓存…")
                cached.readBytes()
            } else {
                onStage("正在连接线路…")
                client.fetchBytes(
                    source = book.source,
                    path = book.path,
                    limit = GithubClient.MAX_TEXT_BYTES,
                    onStage = onStage,
                ).also { fetched ->
                    // Write the bytes through the cache so the next read is local.
                    // Best effort: a cache that cannot be written must not stop the
                    // book from opening.
                    //
                    // An EPUB is the one format that is *not* fetched in pieces:
                    // a zip needs random access to its bytes, so a prefix of it is
                    // not a readable book. It is buffered whole, which the 64 MB
                    // cap above bounds.
                    runCatching { fetcher?.storeWhole(book, fetched) }
                }
            }
        }
        onStage("正在排版…")

        val entries = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                // Guard against a zip bomb: an EPUB of a few MB should not be
                // allowed to expand without limit.
                if (entry.size > MAX_EPUB_ENTRY) continue
                entries[name] = zip.readBytes()
                if (entries.size > MAX_EPUB_ENTRIES) break
            }
        }
        if (entries.isEmpty()) throw UnsupportedBookException("EPUB 文件为空或已损坏")

        val opfPath = findOpfPath(entries)
            ?: entries.keys.firstOrNull { it.endsWith(".opf", true) }
            ?: throw UnsupportedBookException("EPUB 缺少 OPF 清单")
        val opf = entries[opfPath]?.toString(Charsets.UTF_8)
            ?: throw UnsupportedBookException("EPUB 无法读取 OPF 清单")

        val base = opfPath.substringBeforeLast('/', "")
        val manifest = parseManifest(opf)
        val spineIds = Regex("""<itemref[^>]*idref\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .findAll(opf).map { it.groupValues[1] }.toList()

        val sb = StringBuilder()
        val chapters = ArrayList<Chapters.Chapter>()
        val order = if (spineIds.isNotEmpty()) spineIds else manifest.keys.toList()
        var index = 0
        for (id in order) {
            val href = manifest[id] ?: continue
            val full = resolve(base, href)
            val raw = entries[full] ?: entries.entries
                .firstOrNull { it.key.endsWith(href, true) }?.value ?: continue
            val html = raw.toString(Charsets.UTF_8)
            val title = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                ?: "第 ${index + 1} 节"
            val body = HtmlText.strip(html).trim()
            if (body.isEmpty()) continue
            val start = sb.length
            sb.append(title).append('\n').append(body).append("\n\n")
            chapters.add(Chapters.Chapter(title, start, sb.length, index))
            index++
        }

        if (sb.isEmpty()) throw UnsupportedBookException("EPUB 没有可显示的正文")
        return Loaded(
            BookContent(
                title = book.title,
                text = sb.toString(),
                chapters = chapters,
                charset = "UTF-8",
            )
        )
    }

    private fun findOpfPath(entries: Map<String, ByteArray>): String? {
        val container = entries["META-INF/container.xml"]?.toString(Charsets.UTF_8) ?: return null
        return Regex("""full-path\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(container)?.groupValues?.get(1)
    }

    /** id -> href for every manifest item whose media type is a document. */
    private fun parseManifest(opf: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val itemRe = Regex("""<item\b[^>]*>""", RegexOption.IGNORE_CASE)
        for (m in itemRe.findAll(opf)) {
            val tag = m.value
            val id = attr(tag, "id") ?: continue
            val href = attr(tag, "href") ?: continue
            val type = attr(tag, "media-type").orEmpty()
            if (type.contains("html", true) || href.endsWith(".xhtml", true) ||
                href.endsWith(".html", true) || href.endsWith(".htm", true)
            ) {
                out[id] = href
            }
        }
        return out
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""$name\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1)

    private fun resolve(base: String, href: String): String {
        val decoded = href.substringBefore('#')
        if (base.isEmpty()) return decoded
        // OPF hrefs are relative to the OPF's own directory, and may climb out
        // of it; normalise rather than concatenating blindly.
        val parts = (base.split('/') + decoded.split('/')).toMutableList()
        val stack = ArrayDeque<String>()
        for (p in parts) {
            when (p) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(p)
            }
        }
        return stack.joinToString("/")
    }

    private companion object {
        const val MAX_EPUB_ENTRY = 8L * 1024 * 1024
        const val MAX_EPUB_ENTRIES = 2000
    }
}

/**
 * Reduce HTML to readable plain text.
 *
 * Deliberately not a full parser: the input is book markup, and what the reader
 * needs is paragraphs in the right order with the inline noise removed. Script
 * and style bodies are dropped rather than stripped, because their *content* is
 * not text -- leaving it in would dump minified CSS into the middle of a page.
 */
object HtmlText {

    private val dropBlocks = listOf(
        Regex("""<script\b[^>]*>.*?</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        Regex("""<style\b[^>]*>.*?</style>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        Regex("""<head\b[^>]*>.*?</head>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL),
    )

    private val blockTags = Regex(
        """</?\s*(p|div|br|li|tr|h[1-6]|section|article|blockquote|pre)\b[^>]*>""",
        RegexOption.IGNORE_CASE,
    )

    fun strip(html: String): String {
        var s = html
        for (r in dropBlocks) s = r.replace(s, "\n")
        // Block boundaries become line breaks so paragraphs survive; the tags
        // themselves carry no meaning for a paginated text reader.
        s = blockTags.replace(s, "\n")
        s = Regex("""<[^>]+>""").replace(s, "")
        s = unescape(s)
        // Collapse the ragged whitespace the tag removal leaves behind, but keep
        // paragraph breaks.
        s = s.replace(Regex("""[ \t\u00A0]+"""), " ")
        s = s.replace(Regex(""" *\n *"""), "\n")
        s = s.replace(Regex("""\n{3,}"""), "\n\n")
        return s.trim()
    }

    private fun unescape(s: String): String {
        if (!s.contains('&')) return s
        var out = s
        out = out.replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        // Numeric entities, decimal and hex.
        out = Regex("""&#(\d+);""").replace(out) { m ->
            m.groupValues[1].toIntOrNull()?.let { cp -> String(Character.toChars(cp)) } ?: m.value
        }
        out = Regex("""&#[xX]([0-9a-fA-F]+);""").replace(out) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { cp -> String(Character.toChars(cp)) } ?: m.value
        }
        return out
    }
}
