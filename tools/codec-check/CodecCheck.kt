package com.readon.reader.data

import com.readon.reader.reader.PageCursor
import java.io.File
import java.util.Calendar
import java.util.TimeZone

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

    println()
    if (failures == 0) {
        println("全部通过")
    } else {
        println("$failures 项失败")
        kotlin.system.exitProcess(1)
    }
}
