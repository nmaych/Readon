package com.readon.reader.data

import com.google.gson.JsonParser
import com.readon.reader.data.Markdown
import com.readon.reader.data.PartialRead
import com.readon.reader.net.RouteHealth
import com.readon.reader.reader.AutoRead
import com.readon.reader.reader.PageCursor
import com.readon.reader.reader.ReaderHud
import com.readon.reader.reader.TextFlow
import com.readon.reader.ui.Adapt
import com.readon.reader.update.UpdateDecision
import com.readon.reader.update.UpdateResult
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * The repository root, passed in by `tools/check-book-logic.cmd`.
 *
 * A property rather than a relative path: the harness is run from wherever the
 * caller happens to be standing, and resolving `dist/update.json` against the
 * process's working directory would either find the wrong file or silently find
 * nothing and skip the check that uses it.
 */
private val ROOT_OF_REPO: File = File(
    System.getProperty("readon.repoRoot") ?: ".",
)

/**
 * Verification harness for the pure-JVM logic the bug reports touch: encoding
 * detection (the "import a txt and it shows mojibake" path), chapter splitting
 * (part of "正在排版" latency), page bookkeeping (the "显示正在排版后闪退" crash) and
 * the shelf's chapter/time labels.
 *
 * Compiled against the real TextCodec.kt, Chapters.kt, PageCursor.kt, Model.kt and
 * ShelfLabels.kt from the app -- never a copy, which would drift.
 */
fun main() {
    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) {
            println("  [PASS] $name")
        } else {
            failures++
            println("  [FAIL] $name${if (detail.isEmpty()) "" else " -> $detail"}")
        }
    }

    val tmp = File(System.getProperty("java.io.tmpdir"), "readon-verify").apply { mkdirs() }
    fun write(name: String, bytes: ByteArray): File =
        File(tmp, name).apply { writeBytes(bytes) }

    println("== 编码检测 ==")

    // 1. UTF-8 with BOM
    val bomUtf8 = "第一章 起点\n正文内容。\n".toByteArray(Charsets.UTF_8)
    val bomFile = write(
        "bom-utf8.txt",
        byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bomUtf8,
    )
    val bomDecoded = TextCodec.decodeFile(bomFile)
    check("UTF-8 BOM 被识别", bomDecoded.charset == "UTF-8 (BOM)", bomDecoded.charset)
    check("BOM 不残留为字符", !bomDecoded.text.startsWith("\uFEFF"), bomDecoded.text.take(3))

    // 2. Plain UTF-8 Chinese
    val utf8File = write(
        "utf8.txt",
        "第一章 起点\n这是一段中文正文，用来验证解码。\n".toByteArray(Charsets.UTF_8),
    )
    val utf8Decoded = TextCodec.decodeFile(utf8File)
    check("UTF-8 被识别", utf8Decoded.charset == "UTF-8", utf8Decoded.charset)
    check("UTF-8 无乱码", utf8Decoded.text.contains("这是一段中文正文"), utf8Decoded.text.take(20))

    // 3. GBK Chinese -- the case that used to produce mojibake
    val gbkText = "第一章 起点\n这是一段中文正文，用GBK编码保存。\n第二章 继续\n更多的正文内容在这里。\n"
    val gbkFile = write("gbk.txt", gbkText.toByteArray(charset("GBK")))
    val gbkDecoded = TextCodec.decodeFile(gbkFile)
    check("GBK 被识别", gbkDecoded.charset == "GBK", gbkDecoded.charset)
    check("GBK 解码正确", gbkDecoded.text.contains("这是一段中文正文"), gbkDecoded.text.take(20))

    // 4. THE REGRESSION CASE: ASCII head, GBK body.
    // A book with an English title page or an ASCII table of contents followed by
    // GBK prose. Sampling only the head sees pure ASCII and would pick UTF-8,
    // turning the whole body into mojibake. The head must exceed the 128 KB sample
    // window for this to be a real test.
    val asciiHead = "A Novel\nAuthor: Someone\n".repeat(6000)
    val asciiHeadBytes = asciiHead.toByteArray(Charsets.US_ASCII)
    check(
        "测试数据头部确为纯 ASCII 且超过采样窗口",
        asciiHeadBytes.size > 128 * 1024,
        "${asciiHeadBytes.size} bytes",
    )
    val mixedFile = write("ascii-head-gbk.txt", asciiHeadBytes + gbkText.toByteArray(charset("GBK")))
    val mixedDecoded = TextCodec.decodeFile(mixedFile)
    check("ASCII 头 + GBK 正文 被识别为 GBK", mixedDecoded.charset == "GBK", mixedDecoded.charset)
    check(
        "ASCII 头 + GBK 正文 正文无乱码",
        mixedDecoded.text.contains("这是一段中文正文"),
        mixedDecoded.text.takeLast(40),
    )

    // 5. UTF-16LE with BOM
    val utf16File = write(
        "utf16le.txt",
        byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + gbkText.toByteArray(Charsets.UTF_16LE),
    )
    val utf16Decoded = TextCodec.decodeFile(utf16File)
    check("UTF-16LE 被识别", utf16Decoded.charset == "UTF-16LE", utf16Decoded.charset)
    check("UTF-16LE 解码正确", utf16Decoded.text.contains("这是一段中文正文"), utf16Decoded.text.take(20))

    // 6. A large GBK file must take the streaming path and agree exactly with the
    // in-memory decoder, which is the reference implementation.
    val repeats = 20000
    val bigGbk = gbkText.repeat(repeats).toByteArray(charset("GBK"))
    check("大文件测试数据 > 1 MB", bigGbk.size > 1024 * 1024, "${bigGbk.size} bytes")
    val bigFile = write("big-gbk.txt", bigGbk)
    val bigDecoded = TextCodec.decodeFile(bigFile)
    check("大 GBK 文件被识别", bigDecoded.charset == "GBK", bigDecoded.charset)
    check(
        "大 GBK 文件长度正确",
        bigDecoded.text.length == gbkText.length * repeats,
        "${bigDecoded.text.length} vs ${gbkText.length * repeats}",
    )
    check("大 GBK 文件无乱码", !bigDecoded.text.contains('\uFFFD'))
    val inMemory = TextCodec.decode(bigGbk)
    check("流式与内存解码结果一致", inMemory.text == bigDecoded.text)
    check("内存解码也识别为 GBK", inMemory.charset == "GBK", inMemory.charset)

    // 7. Empty and tiny files must not throw.
    check(
        "空文件不抛异常",
        runCatching { TextCodec.decodeFile(write("empty.txt", ByteArray(0))) }.isSuccess,
    )
    check(
        "单字节文件不抛异常",
        runCatching { TextCodec.decodeFile(write("one.txt", byteArrayOf(0x41))) }.isSuccess,
    )

    println()
    println("== 章节切分 ==")

    val novel = buildString {
        append("书名\n作者\n\n")
        for (i in 1..50) {
            append("第${i}章 标题${i}\n")
            append("这是第${i}章的正文内容。".repeat(40))
            append("\n\n")
        }
    }
    val chapters = Chapters.split(novel)
    check("识别出章节", chapters.size >= 50, "${chapters.size}")
    check("章节序号连续", chapters.mapIndexed { i, c -> c.index == i }.all { it })
    check("章节范围不重叠", chapters.zipWithNext().all { (a, b) -> a.end <= b.start })
    check(
        "末章覆盖到文末",
        chapters.last().end == novel.length,
        "${chapters.last().end} vs ${novel.length}",
    )

    // A book with no headings still reads: split returns empty, not a crash.
    check("无章节书返回空列表", Chapters.split("只是一段很长的文字。".repeat(500)).isEmpty())

    // A table of contents at the front must not become dozens of empty chapters.
    val withToc = buildString {
        for (i in 1..40) append("第${i}章 标题\n")
        append("\n")
        for (i in 1..40) {
            append("第${i}章 标题\n")
            append("正文。".repeat(200))
            append("\n\n")
        }
    }
    val tocChapters = Chapters.split(withToc)
    check("目录未被误当作章节", tocChapters.size in 1..45, "${tocChapters.size}")
    check("章节正文非空", tocChapters.all { it.end - it.start > 0 })

    if (chapters.size > 3) {
        val target = chapters[2].start + 5
        check("indexAt 定位正确", Chapters.indexAt(chapters, target) == chapters[2].index)
    }

    // Smoke test that a large book splits quickly. The old implementation
    // allocated a String per line; this only materialises candidate headings.
    val huge = buildString {
        for (i in 1..3000) {
            append("第${i}章 标题\n")
            append("正文内容。".repeat(300))
            append("\n\n")
        }
    }
    val start = System.nanoTime()
    val hugeChapters = Chapters.split(huge)
    val elapsedMs = (System.nanoTime() - start) / 1_000_000
    println("  [info] ${huge.length} 字符切分耗时 ${elapsedMs}ms，章节 ${hugeChapters.size}")
    check("大书切分在 5 秒内完成", elapsedMs < 5000, "${elapsedMs}ms")

    // -----------------------------------------------------------------------
    // Page bookkeeping -- the "显示正在排版后闪退" crash.
    //
    // The reader produced the first page by asking an empty list for its last
    // entry, so `pages.last()` threw NoSuchElementException the instant any
    // non-empty book finished loading. Thrown from inside the composition, that
    // is a hard crash: the user saw "正在排版…" and then the app closed. These
    // checks pin the exact case, because a regression here is invisible until a
    // book is opened on a device.
    // -----------------------------------------------------------------------
    println()
    println("== 分页游标 ==")

    // A page holds 10 characters, so the expected layout is exactly derivable.
    fun cursor(length: Int) = PageCursor(length) { start -> start + 10 }

    check(
        "非空文本不会在构造时崩溃（回归）",
        runCatching { cursor("第一章\n这是一段正文。".length) }.isSuccess,
        runCatching { cursor(1) }.exceptionOrNull()?.toString().orEmpty(),
    )

    val c = cursor(95)
    check("首页已生成", c.pageCount == 1, "${c.pageCount}")
    check("页范围从 0 开始", c.page(0) == com.readon.reader.reader.PageSlice(0, 10), "${c.page(0)}")
    check("构造后尚未完成", !c.isComplete)

    c.completeAll()
    check("completeAll 能跑完", c.isComplete)
    check("页数正确", c.pageCount == 10, "${c.pageCount}")
    check(
        "页首尾相接且不重叠",
        c.let { (0 until it.pageCount - 1).all { i -> it.page(i).end == it.page(i + 1).start } },
    )
    check("末页覆盖到文末", c.page(c.pageCount - 1).end == 95, "${c.page(c.pageCount - 1)}")
    check(
        "每页都在前进",
        (0 until c.pageCount).all { i -> c.page(i).end > c.page(i).start },
    )

    // An empty chapter is one empty page, not zero: the reader draws from it and
    // the footer counts from it.
    val empty = cursor(0)
    check("空文本是 1 页且已完成", empty.pageCount == 1 && empty.isComplete, "${empty.pageCount}")
    check("空文本的页是空的", empty.page(0) == com.readon.reader.reader.PageSlice(0, 0))

    // pageOf maps a character offset to the page containing it.
    check("pageOf 定位第 1 页", c.pageOf(0) == 0, "${c.pageOf(0)}")
    check("pageOf 定位中间页", c.pageOf(55) == 5, "${c.pageOf(55)}")
    check("pageOf 定位末页", c.pageOf(94) == 9, "${c.pageOf(94)}")
    check("pageOf 超出已算范围会继续算", c.pageOf(1_000) == c.pageCount - 1)

    // A pathological measurer that never advances must not spin: the cursor
    // coerces every page to at least one character of progress.
    val stuck = PageCursor(50) { start -> start }
    stuck.completeAll()
    check("不前进的测量器不会死循环", stuck.isComplete && stuck.pageCount == 50, "${stuck.pageCount}")

    // A cursor over a real chapter slice, the way the reader builds it.
    val novelChapters = Chapters.split(novel)
    if (novelChapters.isNotEmpty()) {
        val ch = novelChapters[1]
        val sliceLen = ch.end - ch.start
        val real = PageCursor(sliceLen) { s -> minOf(sliceLen, s + 500) }
        real.completeAll()
        check("真实章节可分页", real.pageCount >= 1 && real.isComplete, "${real.pageCount}")
        check("真实章节末页到章末", real.page(real.pageCount - 1).end == sliceLen)
    }

    // -----------------------------------------------------------------------
    // Shelf labels: "12/340 章" and "上次阅读：今天 14:30".
    // -----------------------------------------------------------------------
    println()
    println("== 书架标签 ==")

    fun book(chapterIndex: Int, chapterCount: Int) = Book(
        id = "x", title = "t", author = null, folder = "f", path = "t.txt",
        format = "txt", size = 0, owner = "o", repo = "r", branch = "b",
        chapterIndex = chapterIndex, chapterCount = chapterCount,
    )

    check("从未打开不显示章节", ShelfLabels.chapterProgress(book(-1, 0)) == null)
    check("无章节信息的旧记录不显示", ShelfLabels.chapterProgress(book(0, 0)) == null)
    check(
        "章节位置显示为 当前/总数",
        ShelfLabels.chapterProgress(book(11, 340)) == "12/340 章",
        ShelfLabels.chapterProgress(book(11, 340)).orEmpty(),
    )
    check(
        "首页显示 1/总数",
        ShelfLabels.chapterProgress(book(0, 340)) == "1/340 章",
        ShelfLabels.chapterProgress(book(0, 340)).orEmpty(),
    )
    check(
        "越界的章节号被夹紧而不是溢出",
        ShelfLabels.chapterProgress(book(999, 340)) == "340/340 章",
        ShelfLabels.chapterProgress(book(999, 340)).orEmpty(),
    )
    check(
        "没有大纲的书不编造位置",
        ShelfLabels.chapterProgress(book(-1, 12)) == null,
    )

    // Fixed zone so the expectations do not depend on where this runs.
    val zone = TimeZone.getTimeZone("Asia/Shanghai")

    fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(y, mo - 1, d, h, mi, 0)
        }.timeInMillis

    val now = at(2026, 10, 12, 20, 0)

    check("从未阅读不显示时间", ShelfLabels.lastReadLine(0L, now, zone) == null)
    check(
        "今天的显示今天+时刻",
        ShelfLabels.lastReadLine(at(2026, 10, 12, 14, 30), now, zone) == "上次阅读：今天 14:30",
        ShelfLabels.lastReadLine(at(2026, 10, 12, 14, 30), now, zone).orEmpty(),
    )
    check(
        "昨天的显示昨天+时刻",
        ShelfLabels.lastReadLine(at(2026, 10, 11, 23, 50), now, zone) == "上次阅读：昨天 23:50",
        ShelfLabels.lastReadLine(at(2026, 10, 11, 23, 50), now, zone).orEmpty(),
    )
    // The case that a naive "elapsed / 24h" would get wrong: 20 minutes apart,
    // but on different calendar days.
    check(
        "跨零点的 20 分钟算昨天",
        ShelfLabels.lastRead(at(2026, 10, 11, 23, 50), at(2026, 10, 12, 0, 10), zone)
            ?.startsWith("昨天") == true,
        ShelfLabels.lastRead(at(2026, 10, 11, 23, 50), at(2026, 10, 12, 0, 10), zone).orEmpty(),
    )
    check(
        "三天前显示天数",
        ShelfLabels.lastRead(at(2026, 10, 9, 8, 0), now, zone) == "3 天前",
        ShelfLabels.lastRead(at(2026, 10, 9, 8, 0), now, zone).orEmpty(),
    )
    check(
        "六天前仍显示天数",
        ShelfLabels.lastRead(at(2026, 10, 6, 8, 0), now, zone) == "6 天前",
        ShelfLabels.lastRead(at(2026, 10, 6, 8, 0), now, zone).orEmpty(),
    )
    check(
        "七天前改用日期",
        ShelfLabels.lastRead(at(2026, 10, 5, 8, 0), now, zone) == "10月5日",
        ShelfLabels.lastRead(at(2026, 10, 5, 8, 0), now, zone).orEmpty(),
    )
    check(
        "跨年带上年份",
        ShelfLabels.lastRead(at(2025, 10, 5, 8, 0), now, zone) == "2025年10月5日",
        ShelfLabels.lastRead(at(2025, 10, 5, 8, 0), now, zone).orEmpty(),
    )
    // A restored backup or a clock change can put the stamp ahead of now; that
    // must read as "今天", not as a negative day count.
    check(
        "未来时间戳不产生负数",
        ShelfLabels.lastRead(at(2026, 10, 12, 23, 0), now, zone)?.startsWith("今天") == true,
        ShelfLabels.lastRead(at(2026, 10, 12, 23, 0), now, zone).orEmpty(),
    )

    // Every label the shelf can render must be short enough not to wreck a card.
    val labels = listOfNotNull(
        ShelfLabels.lastReadLine(now, now, zone),
        ShelfLabels.lastReadLine(at(2025, 1, 2, 3, 4), now, zone),
        ShelfLabels.chapterProgress(book(11, 340)),
    )
    check(
        "标签长度适合卡片",
        labels.all { it.length <= 20 },
        labels.joinToString(" | ") { "$it(${it.length})" },
    )

    tmp.deleteRecursively()

    // -----------------------------------------------------------------------
    // Blank pages -- "阅读界面出现空白页".
    //
    // A chapter in a plain-text book routinely ends in a run of blank lines, and
    // a run of blank lines measures as *fitting* any viewport: "\n\n" is two
    // empty lines, which is short. Paginating straight through therefore
    // produced pages containing nothing but newlines, which the reader drew as
    // an empty screen the user had to tap past. Every page now starts at the
    // first non-whitespace character, so `pageStart` is exercised here exactly
    // as `Paginator` wires it.
    // -----------------------------------------------------------------------
    println()
    println("== 空白页 ==")

    // The reader's own wiring: skip blank characters, pages of 10 characters.
    fun readerCursor(text: String) = PageCursor(
        length = text.length,
        pageStart = { raw -> TextFlow.skipBlank(text, raw) },
    ) { start -> start + 10 }

    check("skipBlank 跳过换行", TextFlow.skipBlank("ab\n\ncd", 2) == 4, "${TextFlow.skipBlank("ab\n\ncd", 2)}")
    check("skipBlank 跳过空格与制表符", TextFlow.skipBlank("a \t\n b", 1) == 5, "${TextFlow.skipBlank("a \t\n b", 1)}")
    check("skipBlank 到文末返回长度", TextFlow.skipBlank("ab\n\n", 2) == 4, "${TextFlow.skipBlank("ab\n\n", 2)}")
    check("skipBlank 不移动可见字符", TextFlow.skipBlank("abc", 1) == 1, "${TextFlow.skipBlank("abc", 1)}")

    check("isBlank 认空白", TextFlow.isBlank("a\n \n", 1, 4))
    check("isBlank 拒绝正文", !TextFlow.isBlank("a\nb\n", 0, 3))
    check("isBlank 空范围算空白", TextFlow.isBlank("abc", 1, 1))
    check("trimEnd 去掉尾部空白", TextFlow.trimEnd("ab\n\n", 0, 4) == 2, "${TextFlow.trimEnd("ab\n\n", 0, 4)}")

    // THE REGRESSION: a chapter that ends in blank lines.
    val trailingBlanks = "第一章 起点\n这是一段正文内容。\n\n\n\n"
    val tc = readerCursor(trailingBlanks)
    tc.completeAll()
    check(
        "章末空白不再生成空白页（回归）",
        (0 until tc.pageCount).none { i ->
            val p = tc.page(i)
            TextFlow.isBlank(trailingBlanks, p.start, p.end)
        },
        (0 until tc.pageCount).joinToString(" | ") { "${tc.page(it)}" },
    )
    check(
        "每页都从可见字符开始",
        (0 until tc.pageCount).all { i ->
            val p = tc.page(i)
            p.start >= trailingBlanks.length || !trailingBlanks[p.start].isWhitespace()
        },
    )
    // The last page *contains* the final sentence. Its slice may run past the
    // last visible character -- the trailing newlines are inside it -- because
    // `PageCursor` records the measured extent and `Paginator.textOf` trims what
    // it hands to the layout. What must hold is that the page is not blank and
    // that it starts on a visible character, which is the blank-page fix.
    check(
        "末页包含最后一句正文",
        trailingBlanks.substring(
            tc.page(tc.pageCount - 1).start,
            tc.page(tc.pageCount - 1).end,
        ).contains("正文内容"),
        "${tc.page(tc.pageCount - 1)}",
    )
    check(
        "末页起点是可见字符",
        !trailingBlanks[tc.page(tc.pageCount - 1).start].isWhitespace(),
    )

    // A chapter that is *only* whitespace still has to answer `page()`, and must
    // not be left asking an empty list for its last entry -- the 1.0.2 crash.
    val blankOnly = PageCursor(8, { raw -> TextFlow.skipBlank("\n\n\n\n\n\n\n\n", raw) }) { s -> s + 4 }
    check(
        "纯空白章节不崩溃且不产生可见页",
        runCatching { blankOnly.completeAll(); blankOnly.page(0) }.isSuccess,
        runCatching { blankOnly.page(0) }.exceptionOrNull()?.toString().orEmpty(),
    )
    check("纯空白章节页数为 1", blankOnly.pageCount == 1, "${blankOnly.pageCount}")

    // --- the actual mechanism, with a realistic measurer ---------------------
    //
    // The artificial "page holds 10 characters" model above is enough to pin the
    // skip, but it does not reproduce how a blank page was actually produced.
    // This model measures like a text layout does: height is the number of visual
    // lines, and a newline is a line. That is what made the bug reachable --
    //
    //   `pageEnd` searches for the largest slice that FITS, and a run of newlines
    //   fits (they are just empty lines). It then calls `adjustBreak`, which walks
    //   back over consecutive '\n' to avoid leaving blank lines at the top of the
    //   next page. At the very end of a chapter that walk-back consumes the entire
    //   run and returns `start + 1` -- a page holding exactly one newline.
    //
    // A chapter ending in N blank lines therefore produced N blank pages, one per
    // newline, each an empty screen the user had to tap past.
    val maxLines = 40
    val charsPerLine = 20
    fun visualLines(slice: CharSequence): Int {
        var lines = 1
        var col = 0
        for (ch in slice) {
            if (ch == '\n') {
                lines++
                col = 0
            } else {
                col++
                if (col > charsPerLine) {
                    lines++
                    col = 0
                }
            }
        }
        return lines
    }
    // Mirrors TextPaginator: largest fitting slice, then walk back to a newline.
    fun realisticPageEnd(text: CharSequence, start: Int): Int {
        var best = start + 1
        var end = start + 1
        while (end <= text.length) {
            if (visualLines(text.subSequence(start, end)) <= maxLines) best = end else break
            end++
        }
        val limit = maxOf(start + 1, best - 120)
        var i = best
        while (i > limit) {
            if (text[i - 1] == '\n') {
                var e = i
                while (e > limit && text[e - 1] == '\n') e--
                return e.coerceAtLeast(start + 1)
            }
            i--
        }
        return best
    }

    val chapterWithBlankTail =
        "第一章 起点\n" + "这是一段正文内容。".repeat(200) + "\n" + "\n".repeat(30)
    val simulated = PageCursor(
        length = chapterWithBlankTail.length,
        pageStart = { raw -> TextFlow.skipBlank(chapterWithBlankTail, raw) },
    ) { start -> realisticPageEnd(chapterWithBlankTail, start) }
    simulated.completeAll()

    val blankPages = (0 until simulated.pageCount).count { i ->
        val p = simulated.page(i)
        TextFlow.isBlank(chapterWithBlankTail, p.start, p.end)
    }
    println(
        "  [info] 章节末尾 ${30} 个空行，真实测量模型下共 ${simulated.pageCount} 页，空白页 $blankPages"
    )
    check("真实测量模型下没有空白页（回归）", blankPages == 0, "空白页 $blankPages")
    check(
        "真实测量模型下每页都有可见内容",
        (0 until simulated.pageCount).all { i ->
            val p = simulated.page(i)
            !TextFlow.isBlank(chapterWithBlankTail, p.start, p.end)
        },
    )
    check("真实测量模型下确实分出了多页", simulated.pageCount >= 3, "${simulated.pageCount}")

    // The same chapter under the *old* wiring -- no pageStart -- must still show
    // the bug, or this test is not pinning anything.
    val unsimulated = PageCursor(
        length = chapterWithBlankTail.length,
    ) { start -> realisticPageEnd(chapterWithBlankTail, start) }
    unsimulated.completeAll()
    val oldBlank = (0 until unsimulated.pageCount).count { i ->
        val p = unsimulated.page(i)
        TextFlow.isBlank(chapterWithBlankTail, p.start, p.end)
    }
    println("  [info] 不跳过空白时同样的章节有 $oldBlank 个空白页")
    check("不跳过空白时确实会产生空白页（证明测试有效）", oldBlank > 0, "空白页 $oldBlank")

    // The blank run between two headings must not become a page either.
    val betweenChapters = "第一章\n正文。\n\n\n\n\n\n第二章\n更多正文。\n"
    val bc = readerCursor(betweenChapters)
    bc.completeAll()
    check(
        "章节之间的空行不产生空白页",
        (0 until bc.pageCount).none { i ->
            val p = bc.page(i)
            TextFlow.isBlank(betweenChapters, p.start, p.end)
        },
        (0 until bc.pageCount).joinToString(" | ") { "${bc.page(it)}" },
    )

    // A whitespace-only page must also not be reachable by asking for an index
    // past the end, which is what a stale saved position produces.
    check(
        "越界索引返回末页而不是空页",
        runCatching { tc.page(tc.pageCount + 50) }.isSuccess,
    )

    // -----------------------------------------------------------------------
    // Scrolling blocks -- the "上下滚动" reading mode.
    // -----------------------------------------------------------------------
    println()
    println("== 滚动分段 ==")

    val paras = "第一段内容。\n还在第一段。\n\n第二段内容。\n\n\n第三段内容。\n"
    val pb = TextFlow.blocks(paras)
    check("空行分隔出 3 段", pb.size == 3, "${pb.size}")
    check(
        "段落文本正确",
        pb.map { paras.substring(it.start, it.end) } ==
            listOf("第一段内容。\n还在第一段。", "第二段内容。", "第三段内容。"),
        pb.map { paras.substring(it.start, it.end) }.toString(),
    )
    check("段落不重叠且有序", pb.zipWithNext().all { (a, b) -> a.end <= b.start })
    check("段落都非空", pb.all { it.length > 0 })
    check(
        "空白不会成为独立段落",
        pb.none { TextFlow.isBlank(paras, it.start, it.end) },
    )

    // The block list must cover every visible character, or scrolling would
    // silently drop text that paginating shows.
    val covered = BooleanArray(paras.length)
    pb.forEach { for (i in it.start until it.end) covered[i] = true }
    check(
        "所有可见字符都被覆盖",
        paras.indices.all { i -> covered[i] || paras[i].isWhitespace() },
    )

    check("空文本没有段落", TextFlow.blocks("").isEmpty())
    check("纯空白没有段落", TextFlow.blocks("\n\n   \n").isEmpty())

    // An English paragraph wrapped across lines is one block, not one per line.
    val wrapped = "This is a single paragraph\nthat wraps across several lines.\n\nSecond one.\n"
    val wb = TextFlow.blocks(wrapped)
    check("换行的英文段落仍是一段", wb.size == 2, "${wb.size}")

    // A book with no blank lines at all must be split, not handed over as one
    // unmeasurable block.
    val noBreaks = "字".repeat(5000)
    val nb = TextFlow.blocks(noBreaks, maxChars = 1000)
    check("无空行长文被切分", nb.size >= 5, "${nb.size}")
    check("切分后每块不超上限", nb.all { it.length <= 1000 }, nb.maxOf { it.length }.toString())
    check("切分不丢字符", nb.sumOf { it.length } == noBreaks.length, "${nb.sumOf { it.length }}")
    check(
        "切分不产生空白块",
        nb.none { TextFlow.isBlank(noBreaks, it.start, it.end) },
    )

    // Restoring a position and jumping from the outline both go through
    // blockIndexOf, so its binary search has to agree with a linear scan.
    val ordered = TextFlow.blocks(paras)
    check(
        "blockIndexOf 与线性扫描一致",
        paras.indices.all { off ->
            TextFlow.blockIndexOf(ordered, off) ==
                ordered.indexOfLast { it.start <= off }.coerceAtLeast(0)
        },
    )
    check("blockIndexOf 空列表返回 -1", TextFlow.blockIndexOf(emptyList(), 5) == -1)
    check("blockIndexOf 越界返回末块", TextFlow.blockIndexOf(ordered, 99_999) == ordered.size - 1)

    // The scrolling reader computes its blocks when a book is opened, so this has
    // to stay cheap on a book at the reader's 64 MB online cap -- roughly 32M
    // characters of Chinese prose.
    val bigBook = buildString {
        repeat(400_000) { append("这是一段正文内容，用来测试分段性能。\n\n") }
    }
    println("  [info] 分段性能样本 ${bigBook.length} 字符")
    val blockStart = System.nanoTime()
    val bigBlocks = TextFlow.blocks(bigBook)
    val blockMs = (System.nanoTime() - blockStart) / 1_000_000
    println("  [info] 分段耗时 ${blockMs}ms，块数 ${bigBlocks.size}")
    check("大书分段在 1 秒内完成", blockMs < 1000, "${blockMs}ms")
    check("大书分段块数正确", bigBlocks.size == 400_000, "${bigBlocks.size}")
    // Every visible character must survive the split. Blocks are trimmed of the
    // blank lines between paragraphs, so the total is the prose alone.
    val proseChars = bigBlocks.sumOf { it.length }
    check(
        "大书分段覆盖全部正文",
        proseChars == bigBook.count { !it.isWhitespace() },
        "$proseChars vs ${bigBook.count { !it.isWhitespace() }}",
    )
    check("大书分段每块非空", bigBlocks.all { it.length > 0 })

    // -----------------------------------------------------------------------
    // Reader colours -- "可选择字体及颜色".
    //
    // The interesting property is not the arithmetic but its guarantee: when the
    // user picks a text colour and a page colour independently, the muted colour
    // used for the footer and the chapter subtitle must stay legible.
    // -----------------------------------------------------------------------
    println()
    println("== 阅读配色 ==")

    check("NONE 不是可见颜色", ReaderInk.alpha(ReaderInk.NONE) == 0)
    check("黑色判定为深色", ReaderInk.isDark(ReaderInk.rgb(0, 0, 0)))
    check("白色判定为浅色", !ReaderInk.isDark(ReaderInk.rgb(255, 255, 255)))
    check(
        "亮度按 sRGB 曲线而非线性平均",
        ReaderInk.luminance(ReaderInk.rgb(128, 128, 128)) < 0.25,
        "${ReaderInk.luminance(ReaderInk.rgb(128, 128, 128))}",
    )
    check(
        "黑白对比度为 21",
        kotlin.math.abs(ReaderInk.contrast(ReaderInk.rgb(0, 0, 0), ReaderInk.rgb(255, 255, 255)) - 21.0) < 0.01,
        "${ReaderInk.contrast(ReaderInk.rgb(0, 0, 0), ReaderInk.rgb(255, 255, 255))}",
    )
    check("同色对比度为 1", kotlin.math.abs(ReaderInk.contrast(0xFF123456.toInt(), 0xFF123456.toInt()) - 1.0) < 0.01)

    check("mix 端点正确", ReaderInk.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0f) == 0xFF000000.toInt())
    check("mix 另一端正确", ReaderInk.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 1f) == 0xFFFFFFFF.toInt())
    check("argb 打包正确", ReaderInk.argb(255, 0x11, 0x22, 0x33) == 0xFF112233.toInt())
    check("readableOn 深底给白色", ReaderInk.readableOn(ReaderInk.rgb(0, 0, 0)) == ReaderInk.rgb(255, 255, 255))
    check("readableOn 浅底给黑色", ReaderInk.readableOn(ReaderInk.rgb(255, 255, 255)) == ReaderInk.rgb(0, 0, 0))

    // The guarantee: every combination of the palette's page and text colours
    // must produce secondary text that clears the contrast floor. A user picking
    // dark grey on black is a reasonable thing to do, and the footer must not
    // disappear when they do.
    val paletteBackgrounds = listOf(
        0xFFFDFDFB.toInt(), 0xFFF6F1E4.toInt(), 0xFFEFE6D5.toInt(), 0xFFCCE8CF.toInt(),
        0xFFD8E6F2.toInt(), 0xFFE8E4F3.toInt(), 0xFFF5E6EA.toInt(), 0xFF3A3A38.toInt(),
        0xFF14181F.toInt(), 0xFF000000.toInt(),
    )
    val paletteTexts = listOf(
        0xFF000000.toInt(), 0xFF1A1A1A.toInt(), 0xFF2B2620.toInt(), 0xFF3B3B3B.toInt(),
        0xFF5A5A5A.toInt(), 0xFF7A6A55.toInt(), 0xFF1F3A5F.toInt(), 0xFF3A2F4A.toInt(),
        0xFFC7CCD4.toInt(), 0xFFB9BEC6.toInt(), 0xFFE8E8E8.toInt(), 0xFFFFFFFF.toInt(),
    )

    var worst = Double.MAX_VALUE
    var worstPair = ""
    for (bg in paletteBackgrounds) {
        for (fg in paletteTexts) {
            val secondary = ReaderInk.readableSecondary(bg, fg)
            val ratio = ReaderInk.contrast(secondary, bg)
            if (ratio < worst) {
                worst = ratio
                worstPair = "${Integer.toHexString(bg)}/${Integer.toHexString(fg)}"
            }
        }
    }
    println("  [info] ${paletteBackgrounds.size * paletteTexts.size} 组配色，最差次级文字对比度 %.2f (%s)"
        .format(worst, worstPair))
    check(
        "任意页面/文字配色下次级文字都可读",
        worst >= ReaderInk.MIN_CONTRAST,
        "%.2f".format(worst),
    )

    // The specific trap: a muted grey blended toward a dark page must not stay
    // at 45% and vanish.
    val onBlack = ReaderInk.readableSecondary(ReaderInk.rgb(0, 0, 0), ReaderInk.rgb(90, 90, 90))
    check(
        "深色文字在黑色页面上被提亮",
        ReaderInk.contrast(onBlack, ReaderInk.rgb(0, 0, 0)) >= ReaderInk.MIN_CONTRAST,
        "%.2f".format(ReaderInk.contrast(onBlack, ReaderInk.rgb(0, 0, 0))),
    )

    // When the chosen text colour is already at the floor there is no room to
    // mute it further, so the original is kept rather than made worse.
    check(
        "已到对比度下限时不再变暗",
        ReaderInk.contrast(onBlack, ReaderInk.rgb(0, 0, 0)) >=
            ReaderInk.contrast(ReaderInk.rgb(90, 90, 90), ReaderInk.rgb(0, 0, 0)) - 0.001,
        "%.2f".format(ReaderInk.contrast(onBlack, ReaderInk.rgb(0, 0, 0))),
    )

    // With headroom, the secondary colour is genuinely muted -- otherwise the
    // footer would be indistinguishable from the body text.
    val muted = ReaderInk.readableSecondary(ReaderInk.rgb(255, 255, 255), ReaderInk.rgb(0, 0, 0))
    check(
        "有余量时次级文字确实被弱化",
        muted != ReaderInk.rgb(0, 0, 0),
        Integer.toHexString(muted),
    )
    check(
        "弱化后仍然可读",
        ReaderInk.contrast(muted, ReaderInk.rgb(255, 255, 255)) >= ReaderInk.MIN_CONTRAST,
        "%.2f".format(ReaderInk.contrast(muted, ReaderInk.rgb(255, 255, 255))),
    )

    // An unreadable pair is reported rather than silently drawn.
    check(
        "黑底黑字被判定为对比度不足",
        ReaderInk.hasPoorContrast(ReaderInk.rgb(0, 0, 0), ReaderInk.rgb(0, 0, 0)),
    )
    check(
        "白底黑字判定为对比度充足",
        !ReaderInk.hasPoorContrast(ReaderInk.rgb(255, 255, 255), ReaderInk.rgb(0, 0, 0)),
    )

    // isDark drives the status- and navigation-bar icon tint, so it has to be
    // right for the page colours the reader can actually produce -- including
    // the mid-tones, where a naive "average the channels" test gets it wrong.
    check("纯黑页面是深色（状态栏图标转白）", ReaderInk.isDark(0xFF000000.toInt()))
    check("夜间页面是深色", ReaderInk.isDark(0xFF14181F.toInt()))
    check("石墨页面是深色", ReaderInk.isDark(0xFF3A3A38.toInt()))
    check("纸白页面不是深色（状态栏图标转黑）", !ReaderInk.isDark(0xFFFDFDFB.toInt()))
    check("米黄页面不是深色", !ReaderInk.isDark(0xFFF6F1E4.toInt()))
    check("护眼绿页面不是深色", !ReaderInk.isDark(0xFFCCE8CF.toInt()))
    check("淡蓝页面不是深色", !ReaderInk.isDark(0xFFD8E6F2.toInt()))
    check("淡紫页面不是深色", !ReaderInk.isDark(0xFFE8E4F3.toInt()))
    check("淡粉页面不是深色", !ReaderInk.isDark(0xFFF5E6EA.toInt()))

    // The palette lists are what the settings and reader panels actually offer, so
    // the sweep above must cover them exactly -- otherwise a colour could be added
    // to the picker without ever being checked for legibility. These are the same
    // values as `ReaderPalette`, duplicated deliberately: the harness must not
    // import the Compose-bearing theme file, so a drift here is caught by
    // comparing the two lists rather than by hoping they stay in step.
    val paletteSource = File(
        "app-project/app/src/main/java/com/readon/reader/ui/theme/Color.kt"
    )
    if (paletteSource.exists()) {
        val themeText = paletteSource.readText()
        // Each list runs from `listOf(` to the closing paren on its own line.
        // Splitting on the first ")" would stop at the first `toInt()` instead.
        fun listBody(marker: String): String =
            themeText.substringAfter(marker).substringBefore("\n    )")
        val declaredBackgrounds = Regex("""0x[0-9A-Fa-f]{8}""")
            .findAll(listBody("val backgrounds: List<Int> = listOf("))
            .map { it.value.lowercase() }.toList()
        val declaredTexts = Regex("""0x[0-9A-Fa-f]{8}""")
            .findAll(listBody("val texts: List<Int> = listOf("))
            .map { it.value.lowercase() }.toList()
        check(
            "色板背景列表与检查用的列表一致",
            declaredBackgrounds == paletteBackgrounds.map { "0x%08x".format(it) },
            "declared=${declaredBackgrounds.size} checked=${paletteBackgrounds.size}",
        )
        check(
            "色板文字列表与检查用的列表一致",
            declaredTexts == paletteTexts.map { "0x%08x".format(it) },
            "declared=${declaredTexts.size} checked=${paletteTexts.size}",
        )
    } else {
        println("  [info] 跳过色板一致性检查（不在仓库根目录运行）")
    }

    // resolve() must leave an untouched theme exactly as it was.
    val (rb, rt, rs) = ReaderInk.resolve(
        themeBackground = 0xFFFDFDFB.toInt(),
        themeText = 0xFF1A1A1A.toInt(),
        themeSecondary = 0xFF8A8A8A.toInt(),
        backgroundOverride = ReaderInk.NONE,
        textOverride = ReaderInk.NONE,
    )
    check("无覆盖时页面颜色不变", rb == 0xFFFDFDFB.toInt())
    check("无覆盖时文字颜色不变", rt == 0xFF1A1A1A.toInt())
    check("无覆盖时次级文字不变", rs == 0xFF8A8A8A.toInt())

    val (ob, ot, os) = ReaderInk.resolve(
        themeBackground = 0xFFFDFDFB.toInt(),
        themeText = 0xFF1A1A1A.toInt(),
        themeSecondary = 0xFF8A8A8A.toInt(),
        backgroundOverride = 0xFF000000.toInt(),
        textOverride = 0xFFC7CCD4.toInt(),
    )
    check("覆盖后页面颜色生效", ob == 0xFF000000.toInt())
    check("覆盖后文字颜色生效", ot == 0xFFC7CCD4.toInt())
    check(
        "覆盖后次级文字按新配色重算",
        os != 0xFF8A8A8A.toInt() && ReaderInk.contrast(os, ob) >= ReaderInk.MIN_CONTRAST,
        "%.2f".format(ReaderInk.contrast(os, ob)),
    )

    // -----------------------------------------------------------------------
    // Bookmarks -- "标签（书签）功能".
    // -----------------------------------------------------------------------
    println()
    println("== 书签 ==")

    val prose = "第一章 起点\n他从梦中醒来，窗外下着雨。\n他想起昨天的事。\n第二章 转折\n一切都变了。\n"

    val snippet = Bookmarks.snippet(prose, 20)
    check("摘要取自当前位置", snippet.contains("窗外"), snippet)
    check("摘要不含换行", !snippet.contains('\n'), snippet)
    check("摘要长度受控", snippet.length <= Bookmarks.MAX_LABEL + 2, "${snippet.length}")

    val atStart = Bookmarks.snippet(prose, 0)
    check("文首摘要不带前省略号", !atStart.startsWith("…"), atStart)

    val unnamed = Bookmark(
        id = "b@20", charOffset = 20, chapterIndex = 0, chapterTitle = "第一章 起点",
        label = "", createdAt = 1L, progress = 0.1f,
    )
    check(
        "未命名书签显示正文摘要",
        Bookmarks.displayLabel(unnamed, prose).contains("窗外"),
        Bookmarks.displayLabel(unnamed, prose),
    )
    check(
        "命名书签显示名字",
        Bookmarks.displayLabel(unnamed.copy(label = "重要"), prose) == "重要",
    )
    check(
        "位置标签含章节与百分比",
        Bookmarks.locationLabel(unnamed) == "第一章 起点 · 10%",
        Bookmarks.locationLabel(unnamed),
    )
    check(
        "无章节时只显示百分比",
        Bookmarks.locationLabel(unnamed.copy(chapterTitle = null)) == "10%",
    )

    val markA = unnamed.copy(id = "b@20", charOffset = 20, createdAt = 3L)
    val markB = unnamed.copy(id = "b@50", charOffset = 50, createdAt = 2L)
    val markC = unnamed.copy(id = "b@80", charOffset = 80, createdAt = 1L)

    val added = Bookmarks.add(emptyList(), markA)
    check("添加一个书签", added.size == 1)
    check(
        "同一位置再次添加是重命名而不是重复",
        Bookmarks.add(added, markA.copy(label = "改名", createdAt = 9L)).size == 1,
        "${Bookmarks.add(added, markA.copy(label = "改名", createdAt = 9L)).size}",
    )
    check(
        "重命名保留新名字",
        Bookmarks.add(added, markA.copy(label = "改名", createdAt = 9L)).first().label == "改名",
    )

    val three = Bookmarks.add(Bookmarks.add(added, markB), markC)
    check("三个书签", three.size == 3)
    check("按时间倒序", three.map { it.charOffset } == listOf(20, 50, 80), three.map { it.charOffset }.toString())
    check("删除移除指定书签", Bookmarks.remove(three, "b@50").size == 2)
    check("删除不存在的 id 无副作用", Bookmarks.remove(three, "nope").size == 3)
    check("at 命中当前位置", Bookmarks.at(three, 50)?.id == "b@50")
    check("at 未命中返回 null", Bookmarks.at(three, 51) == null)
    check("idOf 稳定", Bookmarks.idOf("book", 42) == "book@42")

    // A bookmark taken in a book whose text has since shrunk must not crash.
    check("越界偏移的摘要不崩溃", runCatching { Bookmarks.snippet(prose, 99_999) }.isSuccess)
    check("空文本摘要为空", Bookmarks.snippet("", 0).isEmpty())

    println()
    println("== 在线阅读缓存 ==")

    // --- what counts as a usable cached copy -------------------------------
    check("大小一致时可用", OnlineCachePolicy.isUsable(1024, 1024))
    check(
        "大小不一致时不可用（上游换了文件）",
        !OnlineCachePolicy.isUsable(1024, 2048),
    )
    check(
        "截断的缓存不可用",
        !OnlineCachePolicy.isUsable(512, 1024),
    )
    check("空文件永远不可用", !OnlineCachePolicy.isUsable(0, 0))
    check(
        "仓库未报告大小时接受非空文件",
        OnlineCachePolicy.isUsable(1024, 0),
    )
    check(
        "仓库报告 0 且缓存也为 0 时仍拒绝",
        !OnlineCachePolicy.isUsable(0, 0),
    )

    // --- eviction ---------------------------------------------------------
    fun entry(name: String, size: Long, used: Long) =
        OnlineCachePolicy.Entry(key = name, size = size, lastUsed = used)

    val small = listOf(entry("a", 10, 3), entry("b", 10, 2), entry("c", 10, 1))
    check("未超上限时不淘汰", OnlineCachePolicy.evict(small, 100).isEmpty())

    // 30 bytes of entries against a 25-byte cap: the least recently used goes.
    val doomed = OnlineCachePolicy.evict(small, 25)
    check("超限时淘汰最久未用的", doomed == listOf("c"), doomed.toString())
    check(
        "淘汰后总量落回上限内",
        small.filterNot { it.key in doomed }.sumOf { it.size } <= 25,
    )

    // The one the user re-reads most must survive: "a" was used last.
    check("最近使用的条目保留", "a" !in doomed)

    // A single entry bigger than the whole cap would otherwise be evicted and
    // re-added on every read, evicting everything else each time.
    val oversized = listOf(entry("huge", 100, 5), entry("small", 10, 1))
    check(
        "单个超限条目也会被淘汰",
        OnlineCachePolicy.evict(oversized, 50).contains("huge"),
    )

    check("上限为 0 时不淘汰（视为不限）", OnlineCachePolicy.evict(small, 0).isEmpty())
    check("空缓存不淘汰", OnlineCachePolicy.evict(emptyList(), 1).isEmpty())

    // Exactly at the cap is inside it -- an off-by-one here would evict a book
    // that fits, on every single read.
    check(
        "正好等于上限时不淘汰",
        OnlineCachePolicy.evict(listOf(entry("a", 25, 1)), 25).isEmpty(),
    )

    // Eviction order must be by last use, not by the order the directory listing
    // happened to return.
    val shuffled = listOf(entry("old", 10, 1), entry("new", 10, 9), entry("mid", 10, 5))
    check(
        "按最后使用时间淘汰而不是目录顺序",
        OnlineCachePolicy.evict(shuffled, 20) == listOf("old"),
        OnlineCachePolicy.evict(shuffled, 20).toString(),
    )

    println()
    println("== 更新检查的两个来源如何合并 ==")

    fun avail(code: Int, name: String) = UpdateResult.Available(
        versionName = name,
        versionCode = code,
        apkUrl = "https://example.invalid/readon-$name.apk",
        sha256 = null,
        notes = "",
        mandatory = false,
    )

    val stale = UpdateResult.UpToDate("1.0.4")
    val fresh = avail(10005, "1.0.5")

    // THE BUG: a manifest left at the previous version reports "up to date" in
    // perfect health, so before this the app said 已是最新版本 while the releases
    // page had 1.0.5. Whichever order the two answers arrive in, the update wins.
    check(
        "过期清单说已是最新 + releases 有新版本 -> 提示更新",
        UpdateDecision.reconcile(stale, fresh) == fresh,
        UpdateDecision.reconcile(stale, fresh).toString(),
    )
    check(
        "两个来源顺序反过来结果相同",
        UpdateDecision.reconcile(fresh, stale) == fresh,
        UpdateDecision.reconcile(fresh, stale).toString(),
    )

    // Both found an update, but they disagree on which: the newer one is the
    // answer, in either order.
    val older = avail(10005, "1.0.5")
    val newer = avail(10006, "1.0.6")
    check("两个来源都有更新时取较新的", UpdateDecision.reconcile(older, newer) == newer)
    check("取较新与顺序无关", UpdateDecision.reconcile(newer, older) == newer)

    // A newer release with no APK is not installable, but it is emphatically not
    // "up to date" either. This must outrank the other source's 已是最新.
    val noApk = UpdateResult.Failed("发现新版本 1.0.6，但这个 release 没有附带 APK", newerExists = true)
    check(
        "无法安装的新版本优先于「已是最新」",
        UpdateDecision.reconcile(stale, noApk) == noApk,
        UpdateDecision.reconcile(stale, noApk).toString(),
    )
    check(
        "无法安装的新版本优先于「已是最新」（顺序无关）",
        UpdateDecision.reconcile(noApk, stale) == noApk,
    )
    check(
        "有可安装的更新时，它优先于无法安装的",
        UpdateDecision.reconcile(fresh, noApk) == fresh,
    )

    // Both agree there is nothing new: report the newest name seen, so the card
    // cannot show a version older than one that was just observed.
    check(
        "两个来源都说最新时取较新的版本号",
        UpdateDecision.reconcile(UpdateResult.UpToDate("1.0.4"), UpdateResult.UpToDate("1.0.5")) ==
            UpdateResult.UpToDate("1.0.5"),
    )
    check(
        "取较新版本号与顺序无关",
        UpdateDecision.reconcile(UpdateResult.UpToDate("1.0.5"), UpdateResult.UpToDate("1.0.4")) ==
            UpdateResult.UpToDate("1.0.5"),
    )
    // `latestName` is nullable, so "both current but silent" must still answer
    // rather than fall through and throw on an empty list.
    check(
        "两个来源都最新但没有版本名时不崩溃",
        runCatching {
            UpdateDecision.reconcile(UpdateResult.UpToDate(null), UpdateResult.UpToDate(null))
        }.isSuccess,
    )
    check(
        "一边有版本名一边没有时用有的那个",
        UpdateDecision.reconcile(UpdateResult.UpToDate(null), UpdateResult.UpToDate("1.0.5")) ==
            UpdateResult.UpToDate("1.0.5"),
    )

    // One source unreachable: the other's answer is used as-is.
    val network = UpdateResult.Failed("连接 GitHub 超时")
    check("清单超时但 releases 有更新 -> 提示更新", UpdateDecision.reconcile(network, fresh) == fresh)
    check("releases 超时但清单说最新 -> 已是最新", UpdateDecision.reconcile(stale, network) == stale)
    check("两个都失败 -> 报告失败", UpdateDecision.reconcile(network, network) == network)
    check(
        "两个都失败时优先能指导用户的那条",
        UpdateDecision.reconcile(network, noApk) == noApk,
    )

    // versionCode arithmetic: the comparison is on this number and nothing else,
    // because string order puts 1.10.0 before 1.9.0.
    check("1.0.5 -> 10005", UpdateDecision.versionCodeOf("1.0.5") == 10005)
    check("1.10.0 -> 11000", UpdateDecision.versionCodeOf("1.10.0") == 11000)
    check("1.9.0 -> 10900", UpdateDecision.versionCodeOf("1.9.0") == 10900)
    check(
        "字符串序会错而版本号序正确",
        UpdateDecision.versionCodeOf("1.10.0") > UpdateDecision.versionCodeOf("1.9.0") &&
            "1.10.0" < "1.9.0",
    )
    check("v 前缀被容忍", UpdateDecision.versionCodeOf("v1.0.5") == 10005)
    check(
        "带 v 的标签不会算成小两个数量级",
        UpdateDecision.versionCodeOf("v1.0.5") != 500,
        "${UpdateDecision.versionCodeOf("v1.0.5")}",
    )
    check("无法解析的版本名 -> 0", UpdateDecision.versionCodeOf("未知") == 0)

    println()
    println("== 自动阅读 ==")

    // The clamp is the whole safety property: a stored 0 would turn pages as fast
    // as the device can, which reads as the book flicking itself to the end.
    check("默认间隔在范围内", AutoRead.clampSeconds(AutoRead.DEFAULT_SECONDS) == AutoRead.DEFAULT_SECONDS)
    check("0 秒被抬到下限", AutoRead.clampSeconds(0) == AutoRead.MIN_SECONDS, "${AutoRead.clampSeconds(0)}")
    check("负数被抬到下限", AutoRead.clampSeconds(-5) == AutoRead.MIN_SECONDS)
    check(
        "超大值被压到上限",
        AutoRead.clampSeconds(100000) == AutoRead.MAX_SECONDS,
        "${AutoRead.clampSeconds(100000)}",
    )
    check("下限本身不变", AutoRead.clampSeconds(AutoRead.MIN_SECONDS) == AutoRead.MIN_SECONDS)
    check("上限本身不变", AutoRead.clampSeconds(AutoRead.MAX_SECONDS) == AutoRead.MAX_SECONDS)

    // A clamped value must never produce a zero-delay wait, which is what would
    // make the timer a busy loop.
    check(
        "任何输入都产生正的等待时间",
        listOf(-1, 0, 1, 3, 15, 60, 61, 9999).all { AutoRead.delayMillis(it) > 0L },
        listOf(-1, 0, 1, 3, 15, 60, 61, 9999).joinToString { "${it}->${AutoRead.delayMillis(it)}" },
    )
    check("等待时间与秒数一致", AutoRead.delayMillis(15) == 15_000L, "${AutoRead.delayMillis(15)}")
    check("超限秒数的等待时间被夹紧", AutoRead.delayMillis(0) == AutoRead.MIN_SECONDS * 1000L)

    // The label and the behaviour have to come from the same clamp, or the panel
    // could promise "3 秒" while the reader waits something else.
    check("标签显示夹紧后的秒数", AutoRead.label(0) == "${AutoRead.MIN_SECONDS} 秒/页", AutoRead.label(0))
    check("标签显示原值（在范围内）", AutoRead.label(20) == "20 秒/页", AutoRead.label(20))

    // --- the scrolling mode's step ------------------------------------------
    //
    // 1.0.7 replaced "wait out the interval, then jump a screenful in 700 ms" with
    // a continuous slide that spends the whole interval moving. The property that
    // makes it *slow* is that the duration is the interval, so these pin it: a
    // regression back to a fixed short animation would show up here as a duration
    // that no longer tracks the setting.
    check(
        "滚动时长等于间隔（整段都在动）",
        AutoRead.scrollDurationMillis(15) == AutoRead.delayMillis(15),
        "${AutoRead.scrollDurationMillis(15)}",
    )
    check(
        "间隔越长滑动越慢",
        AutoRead.scrollDurationMillis(30) > AutoRead.scrollDurationMillis(5),
    )
    check(
        "滚动时长同样被夹紧",
        AutoRead.scrollDurationMillis(0) == AutoRead.MIN_SECONDS * 1000L,
        "${AutoRead.scrollDurationMillis(0)}",
    )
    check(
        "滚动时长总是正的",
        listOf(-1, 0, 1, 3, 15, 60, 9999).all { AutoRead.scrollDurationMillis(it) > 0L },
    )
    check(
        "一步不满一屏（下一屏首行是刚看过的）",
        AutoRead.SCROLL_FRACTION > 0f && AutoRead.SCROLL_FRACTION < 1f,
        "${AutoRead.SCROLL_FRACTION}",
    )
    // The claim being made is "缓慢滑动", so the speed is asserted rather than
    // asserted-about: on a 800 dp viewport the default interval has to stay under
    // 100 dp/s, where the old 700 ms step was over 1000.
    val speed = AutoRead.scrollSpeedDpPerSecond(AutoRead.DEFAULT_SECONDS, 800f)
    check("默认速度是缓慢的（< 100 dp/s）", speed < 100f, "${speed} dp/s")
    check(
        "旧行为的速度会远超上限（证明这个检查有意义）",
        800f * AutoRead.SCROLL_FRACTION / 0.7f > 1000f,
        "${800f * AutoRead.SCROLL_FRACTION / 0.7f} dp/s",
    )
    check(
        "速度随间隔变慢",
        AutoRead.scrollSpeedDpPerSecond(30, 800f) < AutoRead.scrollSpeedDpPerSecond(10, 800f),
    )

    println()
    println("== 更新说明的 Markdown 渲染 ==")

    // The update card used to show the notes' first line cut at 90 characters, so
    // what a user deciding whether to install saw was "### Added" or a bullet
    // ending in "**". These pin the parse that replaced it.
    val notes = """
        ### Added

        - **自动阅读。** 阅读界面下方新增一个开关，打开后按设定的间隔自动翻页。

          - 子项一。
          - 子项二。

        ### Fixed

        - 修掉 `Content-Range` 的解析问题。
        - 支持 [链接](https://example.com/notes) 与 ~~删除线~~。

        1. 第一步
        2. 第二步

        > 引用一行。

        ---

        ```kotlin
        val x = 1
        ```
    """.trimIndent()

    val blocks = Markdown.parse(notes)

    fun headings() = blocks.filterIsInstance<Markdown.Block.Heading>()
    check("识别标题", headings().size == 2, "${headings().size}")
    check("标题级别正确", headings().all { it.level == 3 }, headings().joinToString { "${it.level}" })
    check("标题文本去掉了井号", headings().first().text == "Added", headings().first().text)

    val bullets = blocks.filterIsInstance<Markdown.Block.Bullet>()
    // One under Added, two nested under it, two under Fixed.
    check("识别无序列表", bullets.size == 5, "${bullets.size}")
    check("列表项文本正确", bullets.first().text.startsWith("**自动阅读。**"), bullets.first().text)
    check(
        "缩进的子项有深度",
        bullets.count { it.depth == 1 } == 2,
        bullets.joinToString { "${it.depth}:${it.text}" },
    )
    check("顶层项深度为 0", bullets.first().depth == 0, "${bullets.first().depth}")

    val numbered = blocks.filterIsInstance<Markdown.Block.Numbered>()
    check("识别有序列表", numbered.size == 2, "${numbered.size}")
    check("保留原有序号", numbered.map { it.marker } == listOf("1.", "2."), numbered.joinToString { it.marker })

    check("识别引用", blocks.any { it is Markdown.Block.Quote })
    check("识别分隔线", blocks.any { it is Markdown.Block.Divider })

    val code = blocks.filterIsInstance<Markdown.Block.Code>()
    check("识别围栏代码块", code.size == 1, "${code.size}")
    check("代码内容原样保留", code.first().text.trim() == "val x = 1", code.first().text)
    check("代码块语言被记录", code.first().language == "kotlin", "${code.first().language}")

    // A paragraph must be one block even though the source wraps it across lines:
    // the changelog wraps at 80 columns, and a block per source line would put a
    // gap in the middle of every sentence.
    val wrappedBlocks = Markdown.parse("这是第一行，\n这是第二行，\n这是第三行。")
    check("软换行合并为一个段落", wrappedBlocks.size == 1, "${wrappedBlocks.size}")
    check(
        "软换行以空格连接",
        (wrappedBlocks.first() as Markdown.Block.Paragraph).text == "这是第一行， 这是第二行， 这是第三行。",
        (wrappedBlocks.first() as Markdown.Block.Paragraph).text,
    )

    // A list item owns its indented continuation lines. The changelog wraps its
    // bullets at 80 columns and indents the overflow by two spaces -- 51 such
    // lines in the 1.0.6 section alone -- so a parser that treats them as ordinary
    // paragraphs cuts every bullet off at its first line and floats the rest of
    // the sentence below it. The text is all still on screen, which is exactly
    // what makes this survive a glance at the UI.
    val wrappedItem = Markdown.parse(
        "- **粗体开头。** 这一条很长，\n" +
            "  于是折到了第二行，\n" +
            "  甚至第三行。\n" +
            "- 下一条。\n"
    )
    check("折行的列表项仍是一项", wrappedItem.count { it is Markdown.Block.Bullet } == 2, "${wrappedItem.size} 块")
    check(
        "折行内容被并入同一项",
        (wrappedItem.first() as Markdown.Block.Bullet).text ==
            "**粗体开头。** 这一条很长， 于是折到了第二行， 甚至第三行。",
        (wrappedItem.first() as Markdown.Block.Bullet).text,
    )
    check("续行不会变成独立段落", wrappedItem.none { it is Markdown.Block.Paragraph })

    // A nested item is its own item, not prose belonging to its parent.
    val nestedList = Markdown.parse("- 父项。\n  - 子项一。\n  - 子项二。\n- 下一个父项。\n")
    check("嵌套列表不被并入父项", nestedList.count { it is Markdown.Block.Bullet } == 4, "${nestedList.size}")
    check("父项文本不被子项污染", (nestedList.first() as Markdown.Block.Bullet).text == "父项。")
    check("子项深度为 1", (nestedList[1] as Markdown.Block.Bullet).depth == 1)

    // A blank line inside an item joins it; a blank line followed by a
    // *less*-indented line ends it.
    val itemWithGap = Markdown.parse("- 第一句。\n\n  第二句仍在同一条里。\n")
    check("空行后更深的缩进仍属同一项", itemWithGap.size == 1, "${itemWithGap.size}")
    check(
        "空行不切断同一项",
        (itemWithGap.first() as Markdown.Block.Bullet).text == "第一句。 第二句仍在同一条里。",
        (itemWithGap.first() as Markdown.Block.Bullet).text,
    )
    val itemThenPara = Markdown.parse("- 一项。\n\n普通段落。\n")
    check("空行后无缩进则列表结束", itemThenPara.count { it is Markdown.Block.Bullet } == 1)
    check("结束后的段落是独立段落", itemThenPara.any { it is Markdown.Block.Paragraph })

    // A heading or a fence under an item is a new block, not the item's prose.
    val itemThenHeading = Markdown.parse("- 一项。\n  ### 小标题\n")
    check("列表项后的标题不被并入", itemThenHeading.any { it is Markdown.Block.Heading })
    check(
        "列表项在标题前结束",
        (itemThenHeading.first() as Markdown.Block.Bullet).text == "一项。",
        (itemThenHeading.first() as Markdown.Block.Bullet).text,
    )

    // --- inline --------------------------------------------------------------
    fun plain(spans: List<Markdown.Span>): String = spans.joinToString("") { s ->
        when (s) {
            is Markdown.Span.Text -> s.text
            is Markdown.Span.Code -> s.text
            is Markdown.Span.Styled -> plain(s.spans)
            is Markdown.Span.Link -> plain(s.spans)
        }
    }

    check("粗体被识别", Markdown.inline("**粗**").any { it is Markdown.Span.Styled })
    check("斜体被识别", Markdown.inline("*斜*").any { it is Markdown.Span.Styled })
    check(
        "删除线被识别",
        Markdown.inline("~~删~~").any {
            it is Markdown.Span.Styled && it.style == Markdown.Style.Strikethrough
        },
    )
    check(
        "粗体样式正确",
        (Markdown.inline("**粗**").first() as Markdown.Span.Styled).style == Markdown.Style.Bold,
    )
    // Two-character delimiters must win, or `**bold**` reads as an italic `*`
    // immediately followed by another.
    check("双星号不会被当成斜体", Markdown.inline("**粗**").size == 1, "${Markdown.inline("**粗**").size}")

    val codeSpan = Markdown.inline("`Content-Range`")
    check("行内代码被识别", codeSpan.size == 1 && codeSpan.first() is Markdown.Span.Code)
    // A code span suppresses every other marker, which is what makes
    // `` `**` `` render as the two characters it is.
    val suppressed = Markdown.inline("`**`")
    check(
        "代码里的标记不被解析",
        suppressed.size == 1 && (suppressed.first() as Markdown.Span.Code).text == "**",
        plain(suppressed),
    )

    val link = Markdown.inline("见 [说明](https://example.com/x) 一节")
    val linkSpan = link.filterIsInstance<Markdown.Span.Link>().firstOrNull()
    check("链接被识别", linkSpan != null)
    check("链接地址正确", linkSpan?.url == "https://example.com/x", "${linkSpan?.url}")
    check("链接文字正确", linkSpan?.let { plain(it.spans) } == "说明", linkSpan?.let { plain(it.spans) } ?: "null")

    // Unclosed and empty markers are not emphasis: swallowing them would delete
    // the user's text, and showing them is the honest failure.
    check("未闭合的星号保留为文本", plain(Markdown.inline("**没闭合")) == "**没闭合", plain(Markdown.inline("**没闭合")))
    check("空的星号保留为文本", plain(Markdown.inline("****")) == "****", plain(Markdown.inline("****")))
    check("普通文本不被改动", plain(Markdown.inline("普通文本。")) == "普通文本。")
    check("空字符串不崩溃", Markdown.inline("").isEmpty())
    check("空文档解析为空", Markdown.parse("").isEmpty())

    // Nested styling is what the changelog actually writes:
    // `**`Content-Range` 的解析**` -- a code span inside a bold run.
    val nested = Markdown.inline("**`Content-Range` 的解析**")
    check("粗体里可以嵌代码", nested.size == 1 && nested.first() is Markdown.Span.Styled)
    check(
        "嵌套内容完整",
        plain(nested) == "Content-Range 的解析",
        plain(nested),
    )

    // The summary line on the card has to be prose, not markup: showing the raw
    // "### Added" was the bug.
    val summary = Markdown.summary(notes, 40)
    check("摘要不含 Markdown 标记", !summary.contains("###") && !summary.contains("**"), summary)
    check("摘要被截断到上限", summary.length <= 41, "${summary.length}: $summary")
    check("摘要以省略号结尾", summary.endsWith("…"), summary)
    check(
        "短说明不加省略号",
        Markdown.summary("简短说明。", 100) == "简短说明。",
        Markdown.summary("简短说明。", 100),
    )
    check("摘要保留中文", Markdown.summary(notes, 100).any { it in '\u4e00'..'\u9fff' })

    // The notes the app actually ships must survive the parse: this is the real
    // section from dist/update.json, read back through the same JSON parser the
    // app uses. A regex over the manifest would be both fragile and a
    // catastrophic-backtracking hazard on a multi-kilobyte string.
    val realNotes = File(ROOT_OF_REPO, "dist/update.json").takeIf { it.exists() }?.let { f ->
        runCatching {
            JsonParser.parseString(f.readText(Charsets.UTF_8))
                .asJsonObject
                .get("notes")
                ?.asString
        }.getOrNull()
    }
    if (realNotes.isNullOrBlank()) {
        println("  [skip] dist/update.json 的说明未读到，跳过真实说明的解析检查")
    } else {
        val realBlocks = Markdown.parse(realNotes)
        check("真实更新说明能解析出内容", realBlocks.isNotEmpty(), "${realBlocks.size}")
        check(
            "真实更新说明的标题被识别",
            realBlocks.any { it is Markdown.Block.Heading },
            realBlocks.take(3).joinToString { it::class.simpleName ?: "?" },
        )
        check(
            "真实更新说明的列表被识别",
            realBlocks.any { it is Markdown.Block.Bullet },
        )
        // Nothing may be silently dropped: the headings the release is organised
        // under have to come through, or the notes the user reads are shorter than
        // the release's own description.
        //
        // Compared against the *source* rather than against a hard-coded list of two
        // section names. The earlier form required "Added" and "Fixed" specifically,
        // which was true for the release that happened to be current when it was
        // written and false for the next one that only had "Fixed" and "Changed" --
        // so the check failed on a perfectly good manifest, and the natural fix
        // (editing the literal) would have quietly weakened it to "these two headings
        // exist" forever. Reading the `### ` lines out of the notes and requiring all
        // of them to appear is both release-agnostic and stricter.
        val sourceHeadings = realNotes.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("### ") }
            .map { it.removePrefix("### ").trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val headingTexts = realBlocks.filterIsInstance<Markdown.Block.Heading>().map { it.text }
        check(
            "真实更新说明的每个章节标题都在",
            headingTexts.containsAll(sourceHeadings),
            "源 ${sourceHeadings.size} 个（${sourceHeadings.joinToString()}），" +
                "解析出 ${headingTexts.size} 个（${headingTexts.joinToString()}）",
        )
        check("真实更新说明至少有一个章节标题", sourceHeadings.isNotEmpty())
        val realSummary = Markdown.summary(realNotes, 100)
        check(
            "真实更新说明的摘要可读（不是标记）",
            !realSummary.startsWith("#") && realSummary.isNotBlank(),
            realSummary,
        )
        // Every bullet's text has to survive: a parser that eats a bullet shows a
        // shorter changelog rather than an error, which is invisible on a phone.
        // (The count is asserted again below, next to the wrap check, because the
        // two together are what rule out a parser that merged two items into one.)
        val sourceBullets = realNotes.lineSequence()
            .count { it.trimStart().startsWith("- ") }
        check(
            "真实更新说明的列表项没有丢失",
            realBlocks.count { it is Markdown.Block.Bullet } == sourceBullets,
            "源 $sourceBullets 项 -> 解析 ${realBlocks.count { it is Markdown.Block.Bullet }} 项",
        )

        // And every *line* of the notes has to be accounted for. This is the check
        // that catches the continuation-line bug: treating an indented wrap as a
        // new paragraph keeps every character on screen while cutting each bullet
        // in half, so only comparing the text can tell the difference. Markup
        // punctuation is stripped from both sides -- it is not content, and the
        // bullet markers would otherwise dominate the count.
        val markup = Regex("[\\s*`#>\\[\\]()]")
        val sourceWords = realNotes.replace(markup, "")
        val parsedWords = Markdown.plainText(realNotes).replace(markup, "")
        check(
            "真实更新说明的文字没有丢失",
            parsedWords.length >= sourceWords.length * 0.98,
            "源 ${sourceWords.length} 字 -> 解析 ${parsedWords.length} 字",
        )
        // Inline code inside the *shipped* notes must survive the parse.
        //
        // This used to assert the literal string "Content-Range", which only existed
        // in the 1.0.6 notes -- so the check silently turned into a tripwire for
        // "did anyone release since", failing on every new version for a reason that
        // had nothing to do with the parser. The property worth checking is the one
        // that generalises: whatever code spans this version's notes happen to
        // contain, the parser must hand their text through. Derived from the notes
        // themselves rather than hard-coded, so it tests the parser and not the
        // release.
        val codeSpans = Regex("`([^`]+)`").findAll(realNotes)
            .map { it.groupValues[1].trim() }
            // A single punctuation character is not a meaningful probe: it would pass
            // even if the parser dropped the span, because the character occurs
            // elsewhere in the prose.
            .filter { it.length >= 3 }
            .toList()
        val parsedText = Markdown.plainText(realNotes)
        if (codeSpans.isEmpty()) {
            println("  [skip] 该版本说明里没有行内代码，跳过行内代码保留检查")
        } else {
            val lost = codeSpans.filterNot { parsedText.contains(it) }
            check(
                "真实更新说明里的行内代码没有丢失",
                lost.isEmpty(),
                "丢失 ${lost.size}/${codeSpans.size}：${lost.take(3).joinToString()}",
            )
        }
        // No bullet may be left as its own first line only. The real notes wrap at
        // 80 columns, so the decisive fact is that a parsed bullet is *much longer
        // than any single source line*: under the orphan bug every bullet is
        // exactly one source line long, and the longest source line is bounded by
        // the wrap column. Comparing the longest parsed item against the longest
        // source line is therefore a check the bug cannot pass, where comparing
        // parsed text to the source's first line would be vacuous -- under the bug
        // those two are equal by construction.
        val longestSourceLine = realNotes.lineSequence().maxOf { it.trim().length }
        val longestBullet = realBlocks.filterIsInstance<Markdown.Block.Bullet>()
            .maxOfOrNull { it.text.length } ?: 0
        check(
            "真实更新说明的折行被并入列表项",
            longestBullet > longestSourceLine * 2,
            "最长列表项 $longestBullet 字 / 最长源行 $longestSourceLine 字",
        )
        // And the bullet count must match, so a parser that merged two items into
        // one cannot pass the check above by accident.
        val sourceBulletLines = realNotes.lineSequence()
            .count { it.trimStart().startsWith("- ") }
        check(
            "真实更新说明的列表项数量一致",
            realBlocks.count { it is Markdown.Block.Bullet } == sourceBulletLines,
            "源 $sourceBulletLines -> 解析 ${realBlocks.count { it is Markdown.Block.Bullet }}",
        )
    }

    println()
    println("== 在线阅读的部分下载 ==")

    // --- Content-Range ------------------------------------------------------
    val full = PartialRead.parseContentRange("bytes 0-2047/12345678")
    check("解析 Content-Range", full != null, "null")
    check("起点正确", full?.start == 0L, "${full?.start}")
    check("终点正确", full?.end == 2047L, "${full?.end}")
    check("总大小正确", full?.total == 12345678L, "${full?.total}")
    check("区间长度正确", full?.length == 2048L, "${full?.length}")

    val middle = PartialRead.parseContentRange("bytes 2048-4095/10000")
    check("解析中段区间", middle?.start == 2048L && middle.end == 4095L && middle.total == 10000L)

    // `*` is the "range known, size not stated" answer and must not be guessed at.
    val unknown = PartialRead.parseContentRange("bytes 0-99/*")
    check("总大小为 * 时报告未知", unknown?.total == 0L, "${unknown?.total}")
    check("总大小为 * 时区间仍然可用", unknown?.length == 100L, "${unknown?.length}")

    check("空头返回 null", PartialRead.parseContentRange(null) == null)
    check("空字符串返回 null", PartialRead.parseContentRange("") == null)
    check("错误格式返回 null", PartialRead.parseContentRange("items 0-10/20") == null)
    check("缺少斜杠返回 null", PartialRead.parseContentRange("bytes 0-10") == null)
    check("终点早于起点返回 null", PartialRead.parseContentRange("bytes 10-5/20") == null)
    check("大写 Bytes 也能解析", PartialRead.parseContentRange("Bytes 0-1/2") != null)
    check("多余空格被容忍", PartialRead.parseContentRange("  bytes 0-1/2  ") != null)

    // --- completeness -------------------------------------------------------
    //
    // Only a known, positive total can prove the file is whole. An unknown size
    // answering "yes" would show a truncated book as a finished one.
    check("已知大小且收满即为完整", PartialRead.isComplete(1000, 1000))
    check("收到超出报告大小也算完整", PartialRead.isComplete(1200, 1000))
    check("少于报告大小不完整", !PartialRead.isComplete(500, 1000))
    check("大小未知时不敢断言完整", !PartialRead.isComplete(1000, 0))
    check("零字节且大小未知不完整", !PartialRead.isComplete(0, 0))
    check("零字节但大小为 0 不完整", !PartialRead.isComplete(0, 0))

    // --- first chunk --------------------------------------------------------
    check("小书一次取完", PartialRead.firstChunk(500, 2048) == 500L, "${PartialRead.firstChunk(500, 2048)}")
    check("大书只取前缀", PartialRead.firstChunk(50_000_000, 2048) == 2048L)
    check(
        "大小刚好等于前缀时一次取完",
        PartialRead.firstChunk(2048, 2048) == 2048L,
        "${PartialRead.firstChunk(2048, 2048)}",
    )
    check("大小未知时取前缀", PartialRead.firstChunk(0, 2048) == 2048L)
    check("前缀预算为 0 时取 0", PartialRead.firstChunk(1000, 0) == 0L)

    // --- the dangling replacement character ---------------------------------
    //
    // A prefix ends mid-character, so a streaming decode leaves exactly one
    // U+FFFD at the end. Showing it would put a visible "?" at the foot of the
    // last page the reader has.
    check("末尾替换字符被去掉", PartialRead.trimDanglingReplacement("正文\uFFFD") == "正文")
    check("没有替换字符时不变", PartialRead.trimDanglingReplacement("正文") == "正文")
    check("空文本不崩溃", PartialRead.trimDanglingReplacement("") == "")
    check("只有替换字符时变空", PartialRead.trimDanglingReplacement("\uFFFD") == "")
    // Only a *trailing* one goes: a replacement character in the middle is either
    // a real decoding failure in the source file or TextCodec's deliberate lossy
    // fallback, and hiding those would hide "this file is corrupt".
    check(
        "中间的替换字符被保留",
        PartialRead.trimDanglingReplacement("正\uFFFD文") == "正\uFFFD文",
        PartialRead.trimDanglingReplacement("正\uFFFD文"),
    )
    check(
        "只去掉最后一个",
        PartialRead.trimDanglingReplacement("正\uFFFD\uFFFD") == "正\uFFFD",
        PartialRead.trimDanglingReplacement("正\uFFFD\uFFFD"),
    )

    println()
    println("== 屏幕自适应的布局算术 ==")

    // --- width classes -------------------------------------------------------
    //
    // The breakpoints are the whole feature: every decision below is taken from
    // the class, so a breakpoint that is off by one puts a phone into the tablet
    // layout and nothing else notices. The boundaries themselves are checked
    // because "600 dp is Medium" is the kind of fact that gets edited by accident.
    check("400dp 是紧凑宽度", Adapt.widthClass(400) == Adapt.WidthClass.Compact)
    check("599dp 仍是紧凑宽度", Adapt.widthClass(599) == Adapt.WidthClass.Compact)
    check("600dp 起是中宽", Adapt.widthClass(600) == Adapt.WidthClass.Medium)
    check("839dp 仍是中宽", Adapt.widthClass(839) == Adapt.WidthClass.Medium)
    check("840dp 起是宽", Adapt.widthClass(840) == Adapt.WidthClass.Expanded)
    check("1200dp 是宽", Adapt.widthClass(1200) == Adapt.WidthClass.Expanded)
    // A preview or a first measurement can hand over nothing. The narrow layout is
    // the one that is always safe to render, so zero must not throw or answer wide.
    check("宽度为 0 时退回紧凑", Adapt.widthClass(0) == Adapt.WidthClass.Compact)
    check("宽度为负时退回紧凑", Adapt.widthClass(-100) == Adapt.WidthClass.Compact)

    check("紧凑宽度不算宽", !Adapt.widthClass(400).isWide)
    check("中宽算宽", Adapt.widthClass(600).isWide)
    check("宽算宽", Adapt.widthClass(1200).isWide)

    // --- content width -------------------------------------------------------
    //
    // This is the number that stops a book row being 1200 dp wide with its cover
    // and its download button at opposite edges.
    check(
        "手机上内容宽度不封顶",
        Adapt.contentMaxWidthDp(Adapt.WidthClass.Compact) == Int.MAX_VALUE,
    )
    check("手机上内容宽度就是窗口宽度", Adapt.contentWidthDp(400, Adapt.WidthClass.Compact) == 400)
    check(
        "中宽上内容宽度被封顶",
        Adapt.contentWidthDp(800, Adapt.WidthClass.Medium) ==
            Adapt.contentMaxWidthDp(Adapt.WidthClass.Medium),
    )
    check(
        "封顶值小于窗口宽度才生效",
        Adapt.contentWidthDp(1000, Adapt.WidthClass.Expanded) < 1000,
    )
    // Never wider than the window: returning the cap when the window is narrower
    // would push the content off the right edge.
    check("内容宽度不超过窗口", Adapt.contentWidthDp(500, Adapt.WidthClass.Expanded) <= 500)
    check("窗口宽度为 0 时内容宽度为 0", Adapt.contentWidthDp(0, Adapt.WidthClass.Medium) == 0)

    // The gutter is what actually centres the column, so it has to be symmetric by
    // construction: content + both gutters must come back to the window.
    val gutterWindow = 1000
    val gutter = Adapt.sideGutterDp(gutterWindow, Adapt.WidthClass.Expanded)
    val contentHere = Adapt.contentWidthDp(gutterWindow, Adapt.WidthClass.Expanded)
    check("边距加内容宽度正好等于窗口", contentHere + gutter * 2 == gutterWindow, "$contentHere + $gutter*2")
    check("紧凑宽度上没有边距", Adapt.sideGutterDp(400, Adapt.WidthClass.Compact) == 0)
    check("窗口宽度为 0 时没有边距", Adapt.sideGutterDp(0, Adapt.WidthClass.Medium) == 0)

    // --- grid columns --------------------------------------------------------
    //
    // `GridCells.Adaptive` has no ceiling, which is exactly the bug this replaces:
    // a 1200 dp tablet laid out eleven shelf covers across, which is not a bigger
    // shelf but an unreadable contact sheet.
    check("手机上书架至少一列", Adapt.gridColumns(360, 12, Adapt.SHELF_TILE_MIN) >= 1)
    check("手机宽度至少有一列", Adapt.gridColumns(200, 12, Adapt.SHELF_TILE_MIN) >= 1)
    check("列数有上限", Adapt.gridColumns(4000, 12, Adapt.SHELF_TILE_MIN) <= Adapt.MAX_GRID_COLUMNS)
    check("极宽窗口不会得到几十列", Adapt.gridColumns(4000, 12, Adapt.SHELF_TILE_MIN) == Adapt.MAX_GRID_COLUMNS)
    check("宽度为 0 时一列", Adapt.gridColumns(0, 12, Adapt.SHELF_TILE_MIN) == 1)
    check("最小列宽为 0 时一列", Adapt.gridColumns(800, 12, 0) == 1)
    check("负宽度时一列", Adapt.gridColumns(-50, 12, 100) == 1)
    // Monotonic: a wider window must never produce fewer columns.
    val columnCounts = listOf(320, 400, 600, 800, 1000, 1400, 2000).map {
        Adapt.gridColumns(it, 12, Adapt.SHELF_TILE_MIN)
    }
    check("列数随宽度单调不减", columnCounts == columnCounts.sorted(), columnCounts.joinToString())
    // The gap is part of the arithmetic: N columns plus N-1 gaps have to fit, which
    // is why the `+ gap` appears on both sides. A wide gap with a narrow cell must
    // not produce a column count that overflows.
    val gapHeavy = Adapt.gridColumns(400, 40, 116)
    check("间距被算进列数", gapHeavy * 116 + (gapHeavy - 1) * 40 <= 400, "$gapHeavy 列")

    // The home screen's rows are wide (cover + two lines of text + a button), and its
    // content column is capped, so the two interact: at 600-900 dp there must be a
    // real second column, and past 900 dp the cap keeps the count from growing without
    // bound. Asserted per class because "the tablet layout never actually shows two
    // columns" is the failure that would look like a design choice.
    for (w in listOf(600, 700, 840, 900, 1200, 1600)) {
        val cls = Adapt.widthClass(w)
        val contentWidth = Adapt.contentWidthDp(w, cls) - 32
        val cols = Adapt.gridColumns(contentWidth, 10, Adapt.BOOK_ROW_MIN)
        check("主页在宽屏上至少两列（${w}dp）", cols >= 2, "$cols 列 / 内容 $contentWidth dp")
        check("主页列数不超过上限（${w}dp）", cols <= Adapt.MAX_GRID_COLUMNS, "$cols 列")
        // A column narrower than a book row would put the cover and the title on top of
        // each other.
        val cellWidth = (contentWidth - (cols - 1) * 10) / cols
        check(
            "主页列宽容得下一行书（${w}dp）",
            cellWidth >= Adapt.BOOK_ROW_MIN - 10,
            "每列 $cellWidth dp",
        )
    }

    // --- shelf columns -------------------------------------------------------
    //
    // The shelf is the one grid where a *wider* window must mean *more books*, not
    // bigger books. `gridColumns` is fed a minimum tile width, so it always answers
    // with the fewest legal columns -- which is how a 411 dp phone came to show three
    // 126 dp covers and the report was "每本书太大". `shelfColumns` starts from a
    // *target* instead, so a phone lands on 3-4 covers of ~90 dp.
    check("SHELF 目标宽度在最小与最大之间", Adapt.SHELF_TILE_TARGET > Adapt.SHELF_TILE_MIN)

    for (w in listOf(320, 360, 393, 411, 480, 600)) {
        // The grid's own content width is the window minus its 16 dp side padding.
        val content = w - 32
        val cols = Adapt.shelfColumns(content, 10)
        val tile = Adapt.shelfTileWidthDp(content, 10)
        check("手机上书架至少三列（${w}dp 窗口）", cols >= 3, "$cols 列，每列 $tile dp")
        // Larger than the floor, so titles still fit, but no longer the 126-158 dp
        // posters the minimum-width arithmetic produced.
        check(
            "手机上书架封面不小于 ${Adapt.SHELF_TILE_MIN} dp（${w}dp 窗口）",
            tile >= Adapt.SHELF_TILE_MIN,
            "每列 $tile dp",
        )
        check(
            "手机上书架封面不再是巨幅（${w}dp 窗口）",
            tile <= Adapt.SHELF_TILE_TARGET + 24,
            "每列 $tile dp",
        )
        // Columns plus their gaps must never exceed the width they were computed for.
        check(
            "书架列宽加间距不超宽（${w}dp 窗口）",
            cols * tile + (cols - 1) * 10 <= content,
            "$cols × $tile + ${cols - 1} × 10 > $content",
        )
    }

    // The regression itself, asserted rather than described: on the phone width the
    // report came from, the old arithmetic produced fewer and therefore larger covers.
    // A revert to `gridColumns` for the shelf fails this check.
    run {
        val content = 411 - 32
        val oldCols = Adapt.gridColumns(content, 10, 116) // the old SHELF_TILE_MIN
        val newCols = Adapt.shelfColumns(content, 10)
        val oldTile = (content - (oldCols - 1) * 10) / oldCols
        val newTile = Adapt.shelfTileWidthDp(content, 10)
        check("书架比旧算法多列（411dp 手机）", newCols > oldCols, "新 $newCols 列 vs 旧 $oldCols 列")
        check("书架封面比旧算法小（411dp 手机）", newTile < oldTile, "$newTile dp vs $oldTile dp")
    }

    // 320 dp is the narrowest phone still in use. It must not drop below three
    // columns, because two there means 139 dp covers again.
    check("320dp 窄屏上仍是三列", Adapt.shelfColumns(288, 10) >= 3, "${Adapt.shelfColumns(288, 10)} 列")

    // The size of a cover must not depend on the window: a tablet shows *more* books,
    // not bigger ones. Asserted through the same content-column cap the screen applies,
    // which is what makes it true past 900 dp.
    for (w in listOf(320, 360, 393, 411, 480, 600, 840, 1200, 1600, 2560)) {
        val cls = Adapt.widthClass(w)
        val content = Adapt.contentWidthDp(w, cls) - 32
        val tile = Adapt.shelfTileWidthDp(content, 10)
        check(
            "书架封面大小与窗口无关（${w}dp）",
            tile in Adapt.SHELF_TILE_MIN..(Adapt.SHELF_TILE_TARGET + 24),
            "每本 $tile dp（内容 $content dp）",
        )
    }
    // Two windows of the same class must land on the same tile size: that is what
    // "the cap holds it steady" means, and it is the property a screenshot on one
    // tablet cannot show.
    run {
        val a = Adapt.contentWidthDp(1200, Adapt.widthClass(1200))
        val b = Adapt.contentWidthDp(2560, Adapt.widthClass(2560))
        check("超宽窗口的内容列被封顶", a == b, "$a vs $b")
        check(
            "超宽窗口上书架封面不再变大",
            Adapt.shelfTileWidthDp(a - 32, 10) == Adapt.shelfTileWidthDp(b - 32, 10),
        )
    }

    // Bounded exactly like the other grid helper: a pre-layout measurement can be
    // enormous, and dozens of columns is a hitch rather than a shelf.
    check("书架列数有上限", Adapt.shelfColumns(6000, 10) <= Adapt.SHELF_COLUMNS_MAX)
    check("书架宽度为 0 时一列", Adapt.shelfColumns(0, 10) == 1)
    check("书架负宽度时一列", Adapt.shelfColumns(-50, 10) == 1)
    check("书架列数从不为零", (1..1200 step 7).all { Adapt.shelfColumns(it, 10) >= 1 })
    // Monotonic: a wider window must never show fewer columns.
    val shelfCounts = listOf(200, 320, 416, 600, 840, 1200, 1600, 2400).map {
        Adapt.shelfColumns(it, 10)
    }
    check("书架列数随宽度单调不减", shelfCounts == shelfCounts.sorted(), shelfCounts.joinToString())
    // The tile must stay at or above the floor for every width the app can be given,
    // which is the invariant the floor exists for -- a narrower tile clips its title.
    var worstTile = Int.MAX_VALUE
    for (w in 256..2400 step 3) {
        val cols = Adapt.shelfColumns(w, 10)
        val tile = Adapt.shelfTileWidthDp(w, 10)
        check("书架列数至少一列（${w}dp）", cols >= 1)
        if (tile < worstTile) worstTile = tile
    }
    check(
        "所有宽度上书架封面都不低于最小宽度",
        worstTile >= Adapt.SHELF_TILE_MIN,
        "最窄的封面 $worstTile dp",
    )
    // A huge gap must not produce a column count that overflows the window.
    for (gap in listOf(0, 10, 40)) {
        val cols = Adapt.shelfColumns(400, gap)
        val tile = Adapt.shelfTileWidthDp(400, gap)
        check("书架列宽在间距 $gap 下仍为正", tile > 0, "每列 $tile dp，$cols 列")
    }

    // --- reader text column --------------------------------------------------
    //
    // The reader is the opposite case: it wants *narrower* text on a wide screen.
    // 22 dp margins on a 1200 dp tablet is a ~1150 dp line, roughly 70 Chinese
    // characters at 18 sp, and the eye loses the line on the way back.
    check("手机上阅读边距是原来的 22dp", Adapt.readerHorizontalPaddingDp(360) == 22)
    check(
        "宽屏上阅读边距变大",
        Adapt.readerHorizontalPaddingDp(1200) > Adapt.readerHorizontalPaddingDp(360),
        "${Adapt.readerHorizontalPaddingDp(1200)} vs ${Adapt.readerHorizontalPaddingDp(360)}",
    )
    // The invariant is the *column*, not the margin: a margin cap alone leaves a
    // 2000 dp line on a very wide window, which is 117 Chinese characters.
    for (w in listOf(320, 360, 411, 480, 600, 800, 840, 1200, 1600, 2560, 4000)) {
        val textWidth = Adapt.readerTextWidthDp(w)
        check(
            "阅读文字列宽不超过 $Adapt.READER_TEXT_MAX_DP（${w}dp 窗口）",
            textWidth <= Adapt.READER_TEXT_MAX_DP || w < Adapt.READER_TEXT_MAX_DP + 44,
            "$textWidth dp，约 ${textWidth / 18} 个汉字",
        )
        check("阅读文字宽度为正（${w}dp 窗口）", textWidth > 0, "$textWidth")
        check(
            "文字宽度加两侧边距不超过窗口（${w}dp 窗口）",
            textWidth + Adapt.readerHorizontalPaddingDp(w) * 2 <= w,
            "$textWidth + 2*${Adapt.readerHorizontalPaddingDp(w)} > $w",
        )
    }
    check(
        "阅读文字宽度加两侧边距正好铺满窗口（1200dp）",
        Adapt.readerTextWidthDp(1200) + Adapt.readerHorizontalPaddingDp(1200) * 2 == 1200,
        "${Adapt.readerTextWidthDp(1200)} + 2*${Adapt.readerHorizontalPaddingDp(1200)}",
    )
    check(
        "阅读文字宽度加两侧边距正好铺满窗口（360dp）",
        Adapt.readerTextWidthDp(360) + Adapt.readerHorizontalPaddingDp(360) * 2 == 360,
    )
    // The column must stop growing once it hits the cap, or "capped" is a lie.
    check(
        "超过上限后文字列宽不再增长",
        Adapt.readerTextWidthDp(1200) == Adapt.readerTextWidthDp(2560),
        "${Adapt.readerTextWidthDp(1200)} vs ${Adapt.readerTextWidthDp(2560)}",
    )
    check("阅读文字列宽在上限处正好等于上限", Adapt.readerTextWidthDp(1600) == Adapt.READER_TEXT_MAX_DP)
    check("宽度为 0 时文字宽度为 0", Adapt.readerTextWidthDp(0) == 0)
    check("极窄窗口文字宽度仍为正", Adapt.readerTextWidthDp(20) > 0)
    // The margins on a phone must be untouched by this feature -- a tablet fix that
    // widened a phone's margins would be a regression on the device most people use.
    check("窄窗口上边距保持 22dp", Adapt.readerHorizontalPaddingDp(411) == 22)
    check("窄窗口上文字宽度就是窗口减两边距", Adapt.readerTextWidthDp(411) == 411 - 44)

    // --- dialog width --------------------------------------------------------
    check("对话框宽度有上限", Adapt.dialogMaxWidthDp(2000) <= 600, "${Adapt.dialogMaxWidthDp(2000)}")
    check("对话框比窗口窄", Adapt.dialogMaxWidthDp(600) < 600, "${Adapt.dialogMaxWidthDp(600)}")
    check("窄窗口上对话框仍有最小宽度", Adapt.dialogMaxWidthDp(300) >= 240)

    println()
    println("== 主页浏览记忆 ==")

    val key = "nmaych/ReadonBooklibrary@main"
    val other = "someone/OtherLib@main"
    val blank = HomeMemory()

    // --- restorable ----------------------------------------------------------
    //
    // Three separate reasons to refuse a memory, and each one is a visible bug if
    // it is ignored.
    check(
        "同一书库的记忆可以恢复",
        HomeMemory(sourceKey = key, query = "三体").restorable(key),
    )
    // Applying 《三体》's query to a repository that never held it shows an empty
    // list, which reads as "the library is broken".
    check(
        "换了书库就不恢复",
        !HomeMemory(sourceKey = key, query = "三体").restorable(other),
    )
    check("从未保存过时不恢复", !blank.restorable(key))
    check("书库为空时不恢复", !HomeMemory(sourceKey = "", query = "x").restorable(""))
    // A negative index would throw inside LazyListState, and Prefs is a text file a
    // user can edit.
    check("负数滚动位置不恢复", !HomeMemory(sourceKey = key, scrollIndex = -1).restorable(key))
    check("负数滚动偏移不恢复", !HomeMemory(sourceKey = key, scrollOffset = -5).restorable(key))
    check(
        "零滚动位置可以恢复（只是回到顶部）",
        HomeMemory(sourceKey = key, scrollIndex = 0).restorable(key),
    )

    // --- collection availability --------------------------------------------
    //
    // Null means 全部, which is not a missing collection -- conflating the two would
    // make every launch forget the filter.
    check("没有选中分类时总是可用", collectionAvailable(null, emptyList()))
    check("分类仍存在时可用", collectionAvailable("默认", listOf("默认", "武侠")))
    check("分类已消失时不可用", !collectionAvailable("科幻", listOf("默认", "武侠")))
    check("空分类列表上仍可用（全部）", collectionAvailable(null, emptyList()))

    // --- clamped position ---------------------------------------------------
    //
    // A LazyListState throws on an index past the end, and the listing legitimately
    // changes between visits.
    check("位置在范围内时原样返回", HomeMemory(scrollIndex = 5, scrollOffset = 30).clampedPosition(20) == (5 to 30))
    check("位置超出时收到末尾", HomeMemory(scrollIndex = 50, scrollOffset = 30).clampedPosition(20) == (19 to 0))
    check("列表为空时回到顶部", HomeMemory(scrollIndex = 5, scrollOffset = 30).clampedPosition(0) == (0 to 0))
    check("负数索引收到 0", HomeMemory(scrollIndex = -3, scrollOffset = 9).clampedPosition(10) == (0 to 0))
    // Once the index has been clamped, the pixel offset is meaningless: the user is
    // on a different row anyway, so it restarts at that row's top.
    check(
        "被收拢后偏移归零",
        HomeMemory(scrollIndex = 9, scrollOffset = 77).clampedPosition(3) == (2 to 0),
    )
    check(
        "第一项上保留偏移",
        HomeMemory(scrollIndex = 0, scrollOffset = 120).clampedPosition(10) == (0 to 120),
    )

    // --- meaningfulness -----------------------------------------------------
    //
    // A default view is not worth remembering, and writing it would make a user who
    // deliberately scrolled back to the top indistinguishable from one who never
    // left.
    check("默认视图不算值得记住", !blank.isMeaningful())
    check("有搜索词就值得记住", HomeMemory(query = "abc").isMeaningful())
    check("有分类筛选就值得记住", HomeMemory(collection = "默认").isMeaningful())
    check("滚动过就值得记住", HomeMemory(scrollIndex = 3).isMeaningful())
    check("滚动偏移不为零就值得记住", HomeMemory(scrollOffset = 40).isMeaningful())
    // The source key is metadata, not an expressed choice: counting it would make
    // every default view "worth remembering", because every save writes it.
    check("只有书库键时不值得记住", !HomeMemory(sourceKey = key).isMeaningful())

    println()
    println("== 阅读器自绘状态行 ==")

    // --- clock ---------------------------------------------------------------
    //
    // A one-digit minute is the kind of thing that is obvious in a screenshot and
    // invisible in code review, so the padding is asserted rather than trusted.
    check("整点补零", ReaderHud.clockText(9, 5) == "09:05", ReaderHud.clockText(9, 5))
    check("午夜是 00:00", ReaderHud.clockText(0, 0) == "00:00")
    check("最后一分钟是 23:59", ReaderHud.clockText(23, 59) == "23:59")
    // A clock that crashes the reader would be worse than one showing a wrong minute.
    check("小时越界被夹住", ReaderHud.clockText(25, 0) == "23:00", ReaderHud.clockText(25, 0))
    check("分钟越界被夹住", ReaderHud.clockText(12, 90) == "12:59", ReaderHud.clockText(12, 90))
    check("负数小时被夹住", ReaderHud.clockText(-3, 7) == "00:07", ReaderHud.clockText(-3, 7))
    check("时间总是五字符", ReaderHud.clockText(7, 3).length == 5)

    // --- battery -------------------------------------------------------------
    //
    // `EXTRA_LEVEL` is -1 when the extra is missing, and a device reporting a level
    // above its own scale produces a number the user reads as a bug.
    check("电池百分比带百分号", ReaderHud.batteryText(87) == "87%")
    check("满电是 100%", ReaderHud.batteryText(100) == "100%")
    check("未知读数不是 -1%", ReaderHud.batteryText(-1) == "0%", ReaderHud.batteryText(-1))
    check("超过 100 被夹住", ReaderHud.batteryText(140) == "100%", ReaderHud.batteryText(140))

    // Low *and* not charging: a phone plugged in at 5% is not a problem worth
    // colouring red, and colouring it would teach the reader to ignore the colour.
    check("低电量不充电时报警", ReaderHud.isLowBattery(10, charging = false))
    check("正好 15% 仍算低电量", ReaderHud.isLowBattery(15, charging = false))
    check("16% 不算低电量", !ReaderHud.isLowBattery(16, charging = false))
    check("充电时不报警", !ReaderHud.isLowBattery(5, charging = true))
    check("满电不报警", !ReaderHud.isLowBattery(100, charging = false))
    check("未知读数不报警", !ReaderHud.isLowBattery(-1, charging = false))

    // --- progress ------------------------------------------------------------
    //
    // Floor, not round: 99.6% of a book must read "99%", because "100%" beside
    // another page of text is a lie the reader will notice.
    check("开头是 0%", ReaderHud.progressText(0f) == "0%")
    check("结束是 100%", ReaderHud.progressText(1f) == "100%")
    check("一半是 50%", ReaderHud.progressText(0.5f) == "50%")
    check("99.6% 不显示成 100%", ReaderHud.progressText(0.996f) == "99%", ReaderHud.progressText(0.996f))
    check("超出范围被夹住（大于 1）", ReaderHud.progressText(1.7f) == "100%")
    check("超出范围被夹住（小于 0）", ReaderHud.progressText(-0.4f) == "0%")
    // One frame while a partial online read is extended can report NaN.
    check("NaN 不产生乱码百分比", ReaderHud.progressText(Float.NaN) == "0%", ReaderHud.progressText(Float.NaN))

    // --- the position line -----------------------------------------------
    //
    // "23% · 12/340 章": both facts the footer's left half has to answer.
    check(
        "有目录时同时给出章节和百分比",
        ReaderHud.positionText(0.25f, chapterIndex = 11, chapterCount = 340) == "第 12 / 340 章 · 25%",
        ReaderHud.positionText(0.25f, chapterIndex = 11, chapterCount = 340),
    )
    check(
        "没有目录时只给百分比",
        ReaderHud.positionText(0.25f, chapterIndex = -1, chapterCount = 0) == "25%",
        ReaderHud.positionText(0.25f, chapterIndex = -1, chapterCount = 0),
    )
    // A stored chapter index can outlive a revision of the book upstream; the number
    // shown must stay inside the outline rather than reading "第 400 / 340 章".
    check(
        "章节号超出总数时被夹住",
        ReaderHud.positionText(0.5f, chapterIndex = 399, chapterCount = 340) == "第 340 / 340 章 · 50%",
        ReaderHud.positionText(0.5f, chapterIndex = 399, chapterCount = 340),
    )
    check(
        "负章节号只显示百分比",
        ReaderHud.positionText(0.5f, chapterIndex = -5, chapterCount = 340) == "50%",
        ReaderHud.positionText(0.5f, chapterIndex = -5, chapterCount = 340),
    )

    // --- the bands the two status lines occupy -----------------------------
    //
    // This is the 1.0.10 fix, and it is arithmetic: the lines used to be *reserved*
    // at 18 dp while their text was *set* at 18 sp. `sp` scales with the system font
    // size and `dp` does not, so at 1.3x the two lines drew 23 dp inside an 18 dp
    // reservation and the page was laid out underneath them -- 上方章节 / 下方时间电量
    // 盖住正文.
    val band1x = ReaderHud.band(cutoutTopDp = 0f, lineDp = 18f)
    val band13x = ReaderHud.band(cutoutTopDp = 0f, lineDp = 18f * 1.3f)
    val band20x = ReaderHud.band(cutoutTopDp = 0f, lineDp = 18f * 2.0f)

    check(
        "顶部预留是 上边距+行高+间隙",
        band1x.topDp == ReaderHud.TOP_EDGE_DP + 18f + 8f,
        "${band1x.topDp}",
    )
    check(
        "底部预留是 下边距+行高+间隙",
        band1x.bottomDp == ReaderHud.EDGE_DP + 18f + 8f,
        "${band1x.bottomDp}",
    )
    // 阅读界面上方状态栏上移一些: the top line has to sit *above* where the footer's
    // margin would put it, and its band has to be the smaller of the two. Asserted
    // rather than left to the constants, because "moved up a bit" is exactly the kind
    // of change that gets reverted by a later refactor without anyone noticing.
    check(
        "顶部边距小于底部边距（状态栏上移）",
        ReaderHud.TOP_EDGE_DP < ReaderHud.EDGE_DP,
        "top ${ReaderHud.TOP_EDGE_DP} vs bottom ${ReaderHud.EDGE_DP}",
    )
    check(
        "顶部状态行比底部更靠近屏幕边缘",
        band1x.topDp < band1x.bottomDp,
        "top ${band1x.topDp} vs bottom ${band1x.bottomDp}",
    )
    check(
        "顶部边距仍然大于 0（不贴边）",
        ReaderHud.TOP_EDGE_DP > 0f,
        "${ReaderHud.TOP_EDGE_DP}",
    )
    // The regression: the band must grow with the font scale, because the text does.
    check(
        "字体放大后预留随之变大",
        band13x.topDp > band1x.topDp && band13x.bottomDp > band1x.bottomDp,
        "1x ${band1x.topDp}/${band1x.bottomDp} vs 1.3x ${band13x.topDp}/${band13x.bottomDp}",
    )
    check(
        "字体放大到 2 倍时预留同步翻倍",
        band20x.lineDp > band13x.lineDp && band20x.topDp > band13x.topDp,
    )
    // The actual invariant that makes the bug impossible: the space reserved for a
    // line is never smaller than the line the text will occupy, at any scale.
    for (scale in listOf(0.85f, 1.0f, 1.15f, 1.3f, 1.5f, 2.0f)) {
        val line = 18f * scale
        val b = ReaderHud.band(cutoutTopDp = 0f, lineDp = line)
        check(
            "预留的字号 (${scale}x) 容得下这一行",
            b.lineDp >= line - 0.001f,
            "预留 ${b.lineDp} dp，文字 $line dp",
        )
        // Top and bottom are each *more* than a line, so the body can never be flush
        // against the text the band carries.
        check("顶部留白大于一行 (${scale}x)", b.topDp > b.lineDp)
        check("底部留白大于一行 (${scale}x)", b.bottomDp > b.lineDp)
    }
    // A notch adds to the band; it never replaces part of it.
    val bandNotch = ReaderHud.band(cutoutTopDp = 32f, lineDp = 18f)
    check("挖孔加在顶部预留之上", bandNotch.topDp == band1x.topDp + 32f, "${bandNotch.topDp}")
    check("挖孔不影响底部预留", bandNotch.bottomDp == band1x.bottomDp)
    // A caller that measures nothing must not collapse the band: on a first frame
    // `toDp()` can report 0, and a zero-height band puts the prose under the heading.
    check("行高为 0 时仍保留最小行高", ReaderHud.band(0f, 0f).lineDp == ReaderHud.MIN_LINE_DP)
    check("行高为负时仍保留最小行高", ReaderHud.band(0f, -40f).lineDp == ReaderHud.MIN_LINE_DP)
    check("间隙为负时按 0 处理", ReaderHud.band(0f, 18f, gapDp = -5f).gapDp == 0f)
    check("顶部边缘为负时按 0 处理", ReaderHud.band(0f, 18f, topEdgeDp = -5f).topEdgeDp == 0f)
    check("底部边缘为负时按 0 处理", ReaderHud.band(0f, 18f, bottomEdgeDp = -5f).bottomEdgeDp == 0f)
    check("挖孔为负时按 0 处理", ReaderHud.band(-100f, 18f).topDp == band1x.topDp)
    // 显示/隐藏状态栏时正文位置不变: the band is a function of the cutout only, never
    // of the system status bar. The reader passes only the cutout in; what is
    // asserted here is that the same input always produces the same band, so a
    // transient system bar cannot re-inset the body or repaginate the chapter under
    // the reader's eyes.
    check(
        "同样的挖孔总是得到同样的顶部预留",
        ReaderHud.band(0f, 18f).topDp == ReaderHud.band(0f, 18f).topDp,
    )
    check(
        "没有挖孔时顶部预留就是上边距加一行",
        ReaderHud.band(0f, 18f).topDp == ReaderHud.TOP_EDGE_DP + 18f + ReaderHud.GAP_DP,
        "${ReaderHud.band(0f, 18f).topDp}",
    )

    println()
    println("== 已缓存的书移入书架 ==")
    //
    // Pressing 下载 on a cached book moves the cached file onto the shelf instead of
    // downloading it again. `adoptable` is stricter than `isUsable` on purpose: the
    // shelf record is permanent and carries a size that later cache-hit checks
    // compare against, so an unconfirmed length must not be adopted even though it
    // is good enough to *read*.
    check("大小一致可以移入", OnlineCachePolicy.adoptable(1000, 1000))
    check("空文件不能移入", !OnlineCachePolicy.adoptable(0, 1000))
    check("大小不一致不能移入", !OnlineCachePolicy.adoptable(999, 1000))
    // A book whose size the listing never reported is still adoptable: the shelf
    // record is written with the file's *own* length, so every later comparison is
    // against the real figure. Refusing it would re-download 75 MB to avoid a
    // problem that does not exist.
    check("仓库没报大小时仍可移入", OnlineCachePolicy.adoptable(1000, 0))
    check("仓库没报大小时空文件仍不可移入", !OnlineCachePolicy.adoptable(0, 0))
    // Reading and adopting agree today; asserted so a future divergence is a decision
    // rather than an accident.
    check(
        "可读取性与可移入性一致",
        listOf(0L, 1L, 1000L).all { size ->
            OnlineCachePolicy.isUsable(size, 1000) == OnlineCachePolicy.adoptable(size, 1000)
        },
    )

    println()
    println("== 窗口宽度单位 ==")    //
    // The 1.0.8 regression this guards against: `ProvideWindowSize` published
    // `constraints.maxWidth` -- which is in *pixels* -- into a value whose every
    // consumer treats as dp. A 393 dp phone at 2.75x therefore reported ~1080 and was
    // classified as a tablet, so 主页 took the wide layout on every phone. There is no
    // Compose in this check, so what is asserted is the arithmetic the conversion
    // relies on: pixels divided by density must land in the phone class on a phone,
    // and must only reach the tablet classes on a genuinely wide window.
    for ((widthPx, density, label) in listOf(
        Triple(1080, 2.75f, "393dp 手机"),
        Triple(1080, 3.0f, "360dp 手机"),
        Triple(720, 2.0f, "360dp 手机（2x）"),
        Triple(1440, 3.5f, "411dp 手机"),
        Triple(2560, 1.5f, "1707dp 平板"),
    )) {
        val dp = (widthPx / density).toInt()
        if (dp < Adapt.MEDIUM_MIN_WIDTH_DP) {
            check(
                "像素换算成 dp 后手机仍走窄屏（$label）",
                Adapt.widthClass(dp) == Adapt.WidthClass.Compact,
                "${widthPx}px / $density = ${dp}dp",
            )
        }
        // The raw pixel count, which is what 1.0.8 used, must *not* be what the
        // class is computed from: this asserts the bug was real rather than
        // theoretical, so the check would fail if someone reintroduced it by
        // "simplifying" the conversion away.
        if (widthPx >= Adapt.MEDIUM_MIN_WIDTH_DP && dp < Adapt.MEDIUM_MIN_WIDTH_DP) {
            check(
                "直接用像素会被误判成宽屏（$label）",
                Adapt.widthClass(widthPx).isWide,
                "${widthPx}px -> ${Adapt.widthClass(widthPx)}",
            )
        }
    }

    println()
    println("== 线路健康度（网络连接优化） ==")
    //
    // A host that just timed out must not be waited on again by the next request,
    // but it must also never be *removed*: a network that is briefly down would
    // otherwise leave nothing to try and the app would report "没有可用的访问线路"
    // for a minute after the connection came back. Both halves are asserted, and
    // the clock is injected so the cooldown arithmetic is exercised without sleeping.
    var clockNow = 1_000_000L
    val health = RouteHealth(cooldownMs = 90_000L, clock = { clockNow })
    val routes = listOf("gh-proxy", "ghproxy", "jsdelivr")

    check("初始时没有线路在冷却", routes.none { health.isCoolingDown(it) })
    check("初始顺序不变", health.order(routes) { it } == routes)

    // One failure is not enough: a single timeout is as often a dropped packet.
    health.recordFailure("ghproxy")
    check("失败一次不进入冷却", !health.isCoolingDown("ghproxy"))
    check("失败一次顺序不变", health.order(routes) { it } == routes)

    health.recordFailure("ghproxy")
    check("连续失败两次进入冷却", health.isCoolingDown("ghproxy"))
    check(
        "冷却的线路被移到队尾而不是移除",
        health.order(routes) { it } == listOf("gh-proxy", "jsdelivr", "ghproxy"),
        "${health.order(routes) { it }}",
    )
    check("冷却后线路数量不变", health.order(routes) { it }.size == routes.size)

    // The cooldown has to *expire*: a route that came back (a VPN switched on) must be
    // usable again without restarting the app.
    clockNow += 89_000L
    check("冷却期内仍在队尾", health.isCoolingDown("ghproxy"))
    clockNow += 2_000L
    check("冷却结束后恢复", !health.isCoolingDown("ghproxy"))
    check("冷却结束后回到原来的位置", health.order(routes) { it } == routes)

    // A success clears the history outright rather than halving it: the route is
    // demonstrably working, so a stale count must not demote it again.
    health.recordFailure("jsdelivr")
    health.recordFailure("jsdelivr")
    check("jsdelivr 冷却中", health.isCoolingDown("jsdelivr"))
    health.recordSuccess("jsdelivr")
    check("成功后冷却立即解除", !health.isCoolingDown("jsdelivr"))

    // Failures an hour apart are two unrelated hiccups, not "the route is down".
    health.recordFailure("gh-proxy")
    clockNow += 3_600_000L
    health.recordFailure("gh-proxy")
    check("相隔很久的两次失败不累计", !health.isCoolingDown("gh-proxy"))

    // 检测线路 is an explicit re-check, so it must not be filtered by old history.
    health.recordFailure("ghproxy")
    health.recordFailure("ghproxy")
    check("检测线路前处于冷却", health.isCoolingDown("ghproxy"))
    health.forgetAll()
    check("检测线路会清空冷却记录", !health.isCoolingDown("ghproxy"))

    // Several cooling routes keep their relative order among themselves, so the race
    // order is still the tie-break rather than completion order.
    val h2 = RouteHealth(cooldownMs = 90_000L, clock = { clockNow })
    h2.recordFailure("b")
    h2.recordFailure("b")
    h2.recordFailure("c")
    h2.recordFailure("c")
    check(
        "多条冷却线路之间保持原顺序",
        h2.order(listOf("a", "b", "c", "d")) { it } == listOf("a", "d", "b", "c"),
        "${h2.order(listOf("a", "b", "c", "d")) { it }}",
    )
    // All cooling is the case a *filter* would break: nothing may be dropped.
    val h3 = RouteHealth(cooldownMs = 90_000L, clock = { clockNow })
    for (r in listOf("a", "b", "c")) {
        h3.recordFailure(r)
        h3.recordFailure(r)
    }
    check(
        "全部线路都在冷却时仍全部保留",
        h3.order(listOf("a", "b", "c")) { it }.toSet() == setOf("a", "b", "c"),
        "${h3.order(listOf("a", "b", "c")) { it }}",
    )

    println()
    if (failures == 0) {
        println("全部通过")
    } else {
        println("$failures 项失败")
        kotlin.system.exitProcess(1)
    }
}
