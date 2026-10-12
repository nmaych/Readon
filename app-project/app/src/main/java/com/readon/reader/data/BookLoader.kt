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
 */
class BookLoader(
    private val context: Context,
    private val client: GithubClient,
) {

    suspend fun load(book: Book): BookContent = withContext(Dispatchers.IO) {
        val bytes = readBytes(book)
        when (book.formatEnum) {
            BookFormat.TXT, BookFormat.MD -> textContent(book, bytes)
            BookFormat.HTML -> textContent(book, bytes, stripMarkup = true)
            BookFormat.EPUB -> epubContent(book, bytes)
            BookFormat.PDF -> throw UnsupportedBookException(
                "PDF 暂不支持在线阅读。请先下载，再用系统应用打开。"
            )
            null -> throw UnsupportedBookException("不支持的文件格式")
        }
    }

    private suspend fun readBytes(book: Book): ByteArray {
        val local = book.localFile
        if (local != null) {
            val f = File(local)
            if (f.exists()) return f.readBytes()
            // The record survived but the file did not -- a manual cleanup, or a
            // restored backup. A locally imported book has no remote to fall back
            // to, so say so plainly instead of failing with a network error.
            if (book.isLocal) {                throw UnsupportedBookException(
                    "文件已不在设备上，可能被清理掉了。请重新从本地导入。"
                )
            }
            // Otherwise fall through to the network rather than failing.
        }
        return client.fetchText(book.source, book.path)
    }

    private fun textContent(book: Book, bytes: ByteArray, stripMarkup: Boolean = false): BookContent {
        val decoded = TextCodec.decode(bytes)
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
    private fun epubContent(book: Book, bytes: ByteArray): BookContent {
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
