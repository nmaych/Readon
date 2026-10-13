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
 * ## Why online reading goes through a temp file
 *
 * The first version fetched the whole book into a `ByteArray` and then decoded
 * it, which is two full copies of a file that can be 75 MB before a single
 * character reaches the reader -- and the second copy is a `String` of up to
 * three bytes per character. That is what made "在线阅读" stall on "正在排版…" and
 * then die on low-memory devices.
 *
 * A text book is now streamed to a cache file and decoded from disk, so peak
 * memory is one character buffer and the result. EPUB is the exception and stays
 * in memory, because reading a zip genuinely needs random access to the bytes.
 *
 * @param onStage reports what is happening, so the reader can say "正在下载…"
 *   rather than showing a single unchanging "正在排版…".
 */
class BookLoader(
    private val context: Context,
    private val client: GithubClient,
) {

    suspend fun load(book: Book, onStage: (String) -> Unit = {}): BookContent =
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
    ): BookContent {
        val local = localFileOf(book)
        if (local != null) {
            onStage("正在排版…")
            return decodeText(book, local, stripMarkup)
        }

        // Online: stream to a cache file, decode from there, then remove it.
        onStage("正在连接线路…")
        val temp = File(context.cacheDir, "readon-online").apply { mkdirs() }
            .let { File(it, "online-${book.id.hashCode()}.${book.format}") }
        try {
            onStage("正在下载…")
            client.downloadTo(
                source = book.source,
                path = book.path,
                target = temp,
                expectedSize = book.size,
                onProgress = { p -> onStage("正在下载… ${(p * 100).toInt()}%") },
                onStage = onStage,
            )
            onStage("正在排版…")
            return decodeText(book, temp, stripMarkup)
        } finally {
            runCatching { temp.delete() }
        }
    }

    private fun decodeText(
        book: Book,
        file: File,
        stripMarkup: Boolean,
    ): BookContent {
        val decoded = TextCodec.decodeFile(file)
        val text = (if (stripMarkup) HtmlText.strip(decoded.text) else decoded.text)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
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
    private suspend fun epubContent(book: Book, onStage: (String) -> Unit): BookContent {
        val local = localFileOf(book)
        val bytes = if (local != null) {
            onStage("正在排版…")
            local.readBytes()
        } else {
            onStage("正在连接线路…")
            client.fetchBytes(
                source = book.source,
                path = book.path,
                limit = GithubClient.MAX_TEXT_BYTES,
                onStage = onStage,
            )
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
        return BookContent(
            title = book.title,
            text = sb.toString(),
            chapters = chapters,
            charset = "UTF-8",
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
