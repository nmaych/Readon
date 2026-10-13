package com.readon.reader.data

import java.io.File
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Decode a book's bytes into text.
 *
 * This matters more than it looks. A Chinese plain-text library seeded by
 * dropping files into a repository is mostly GBK-encoded, and decoding those as
 * UTF-8 does not fail loudly -- it yields a page of replacement characters that
 * looks like corruption. So the charset is detected, not assumed:
 *
 *   1. A byte-order mark, if present, is authoritative.
 *   2. Otherwise strict UTF-8 is attempted. Valid multi-byte UTF-8 is
 *      statistically very unlikely to occur by chance in GBK text, so a clean
 *      decode is strong evidence.
 *   3. Otherwise GBK, then UTF-16, then a lossy UTF-8 decode as the last resort.
 *
 * ## Why there are two entry points
 *
 * [decode] takes a byte array and is used for small payloads -- an EPUB entry, a
 * chapter of HTML. [decodeFile] is what the reader uses for a whole book, and it
 * exists because of a crash: books in this library reach 75 MB, and decoding one
 * of those through a byte array meant holding the compressed bytes, a decoded
 * `String` *and* the intermediate copy that `StringBuilder.toString()` makes --
 * roughly 200 MB of peak heap for a 20-million-character novel. That is an
 * out-of-memory crash on exactly the mid-range phone the app targets. Decoding
 * straight from the file needs one buffer and the result, and nothing else.
 */
object TextCodec {

    private val gbk: Charset? = runCatching { Charset.forName("GBK") }.getOrNull()
    private val gb18030: Charset? = runCatching { Charset.forName("GB18030") }.getOrNull()

    data class Decoded(val text: String, val charset: String)

    /** How much of a file is read to decide its encoding. */
    private const val SAMPLE_BYTES = 128 * 1024

    /** Characters per read when streaming a file. */
    private const val STREAM_CHUNK = 64 * 1024

    /** The label used when no strict decode succeeded. */
    private const val LOSSY_UTF8 = "UTF-8 (有损)"

    // -----------------------------------------------------------------------
    // Whole files
    // -----------------------------------------------------------------------

    /**
     * Decode a book from disk, streaming.
     *
     * The encoding is decided from a sample and then applied to the whole file, so
     * the text is produced in one pass and only the final `String` is large.
     */
    fun decodeFile(file: File): Decoded {
        val choice = detectInFile(file)

        val out = StringBuilder(estimateLength(file.length(), choice.charset))
        file.inputStream().use { raw ->
            // Step over the BOM the detector matched, so it does not surface as
            // a stray character at the start of the first page.
            if (choice.bomBytes > 0) {
                var skipped = 0L
                while (skipped < choice.bomBytes) {
                    val n = raw.skip(choice.bomBytes - skipped)
                    if (n <= 0) break
                    skipped += n
                }
            }
            InputStreamReader(raw, choice.charset).use { reader ->
                val buf = CharArray(STREAM_CHUNK)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    out.append(buf, 0, n)
                }
            }
        }
        return Decoded(out.toString(), choice.label)
    }

    /**
     * Decide a file's encoding from its bytes.
     *
     * The head of the file is sampled first. If that sample is pure ASCII it
     * cannot distinguish UTF-8 from GBK -- both decode it identically -- so a
     * sample from the middle of the file is consulted too. Without that second
     * look, a book with an English title page or a long ASCII table of contents
     * followed by GBK prose would be read as UTF-8 and come out as mojibake, which
     * is the exact failure this class exists to prevent.
     */
    private fun detectInFile(file: File): Choice {
        val head = readSample(file, 0L, SAMPLE_BYTES)
        val headChoice = detect(head)
        // A BOM or a non-ASCII head is decisive on its own.
        if (!isPureAscii(head) || file.length() <= SAMPLE_BYTES) return headChoice
        if (headChoice.bomBytes > 0) return headChoice

        val middle = readSample(file, file.length() / 2, SAMPLE_BYTES)
        if (isPureAscii(middle)) return headChoice

        // The head was ASCII, so only the middle sample carries information. A
        // lossy result is discarded in favour of the plain UTF-8 the head implied,
        // because "有损" means the guess failed, not that it succeeded.
        val middleChoice = detect(middle)
        return if (middleChoice.label == LOSSY_UTF8) headChoice else middleChoice
    }

    private fun isPureAscii(bytes: ByteArray): Boolean {
        for (b in bytes) if (b.toInt() and 0x80 != 0) return false
        return true
    }

    /**
     * A cheap pre-size for the builder.
     *
     * Getting this wrong only costs a reallocation, so it is a guess: UTF-8 CJK
     * is three bytes per character, GBK is two, and UTF-16 is two.
     */
    private fun estimateLength(bytes: Long, charset: Charset): Int {
        val perChar = when (charset.name().uppercase()) {
            "UTF-8" -> 3
            "UTF-16", "UTF-16LE", "UTF-16BE" -> 2
            else -> 2
        }
        return (bytes / perChar).coerceIn(16L, Int.MAX_VALUE - 8L).toInt()
    }

    /** Read up to [limit] bytes of [file] starting at [from]. */
    private fun readSample(file: File, from: Long, limit: Int): ByteArray {
        if (from >= file.length()) return ByteArray(0)
        val size = minOf(limit.toLong(), file.length() - from).toInt()
        if (size <= 0) return ByteArray(0)
        return file.inputStream().use { input ->
            // `skip` is allowed to skip fewer bytes than asked, so loop.
            var remaining = from
            while (remaining > 0) {
                val n = input.skip(remaining)
                if (n <= 0) break
                remaining -= n
            }
            val buf = ByteArray(size)
            var read = 0
            while (read < buf.size) {
                val n = input.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
            if (read == buf.size) buf else buf.copyOf(read)
        }
    }

    /** A detected encoding, the label to report, and how many BOM bytes to skip. */
    private class Choice(val charset: Charset, val label: String, val bomBytes: Int)

    private fun detect(sample: ByteArray): Choice {
        // --- 1. BOM --------------------------------------------------------
        if (sample.size >= 3 &&
            sample[0] == 0xEF.toByte() && sample[1] == 0xBB.toByte() && sample[2] == 0xBF.toByte()
        ) {
            return Choice(Charsets.UTF_8, "UTF-8 (BOM)", 3)
        }
        if (sample.size >= 2 && sample[0] == 0xFF.toByte() && sample[1] == 0xFE.toByte()) {
            return Choice(Charsets.UTF_16LE, "UTF-16LE", 2)
        }
        if (sample.size >= 2 && sample[0] == 0xFE.toByte() && sample[1] == 0xFF.toByte()) {
            return Choice(Charsets.UTF_16BE, "UTF-16BE", 2)
        }

        // --- 2. strict UTF-8 on the sample --------------------------------
        if (sampleDecodes(sample, Charsets.UTF_8)) {
            return Choice(Charsets.UTF_8, "UTF-8", 0)
        }

        // --- 3. Chinese legacy encodings ----------------------------------
        // GB18030 is a superset of GBK, so it is tried first; the label says GBK
        // because that is the name a user will recognise.
        if (sampleDecodes(sample, gb18030) || sampleDecodes(sample, gbk)) {
            return Choice(gb18030 ?: gbk ?: Charsets.UTF_8, "GBK", 0)
        }

        // --- 4. UTF-16 without a BOM --------------------------------------
        if (looksLikeUtf16(sample, littleEndian = true)) {
            return Choice(Charsets.UTF_16LE, "UTF-16LE", 0)
        }
        if (looksLikeUtf16(sample, littleEndian = false)) {
            return Choice(Charsets.UTF_16BE, "UTF-16BE", 0)
        }

        // --- 5. give up gracefully ----------------------------------------
        return Choice(Charsets.UTF_8, LOSSY_UTF8, 0)
    }

    /**
     * Does the sample decode strictly under [charset]?
     *
     * The sample usually ends mid-character, which is not a decoding error in the
     * file -- it is an artifact of where the sample was cut. So up to three
     * trailing bytes are trimmed before deciding, which is the most a single
     * multi-byte sequence can be short by.
     */
    private fun sampleDecodes(sample: ByteArray, charset: Charset?): Boolean {
        if (charset == null) return false
        if (sample.isEmpty()) return true
        for (trim in 0..minOf(3, sample.size - 1)) {
            val slice = if (trim == 0) sample else sample.copyOf(sample.size - trim)
            if (strictDecode(slice, charset) != null) return true
        }
        return false
    }

    // -----------------------------------------------------------------------
    // In-memory payloads
    // -----------------------------------------------------------------------

    fun decode(bytes: ByteArray): Decoded {
        // --- 1. BOM --------------------------------------------------------
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return Decoded(String(bytes, 3, bytes.size - 3, Charsets.UTF_8), "UTF-8 (BOM)")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE), "UTF-16LE")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE), "UTF-16BE")
        }

        // --- 2. strict UTF-8 ----------------------------------------------
        strictDecode(bytes, Charsets.UTF_8)?.let { return Decoded(it, "UTF-8") }

        // --- 3. Chinese legacy encodings ----------------------------------
        // GB18030 is a superset of GBK, so try it first and report GBK, which is
        // the name a user will recognise.
        strictDecode(bytes, gb18030)?.let { return Decoded(it, "GBK") }
        strictDecode(bytes, gbk)?.let { return Decoded(it, "GBK") }

        // --- 4. UTF-16 without a BOM --------------------------------------
        // Only worth guessing when the zero-byte pattern is unambiguous.
        if (looksLikeUtf16(bytes, littleEndian = true)) {
            return Decoded(String(bytes, Charsets.UTF_16LE), "UTF-16LE")
        }
        if (looksLikeUtf16(bytes, littleEndian = false)) {
            return Decoded(String(bytes, Charsets.UTF_16BE), "UTF-16BE")
        }

        // --- 5. give up gracefully ----------------------------------------
        return Decoded(String(bytes, Charsets.UTF_8), LOSSY_UTF8)
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset?): String? {
        if (charset == null) return null
        return try {
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Heuristic for BOM-less UTF-16: in Latin/CJK text, UTF-16 puts a zero byte
     * in every other position, so a high proportion of zeros on one side of each
     * pair is a reliable tell.
     */
    private fun looksLikeUtf16(bytes: ByteArray, littleEndian: Boolean): Boolean {
        if (bytes.size < 16) return false
        val sample = minOf(bytes.size, 4096)
        var zeros = 0
        var i = if (littleEndian) 1 else 0
        var pairs = 0
        while (i < sample) {
            if (bytes[i] == 0.toByte()) zeros++
            pairs++
            i += 2
        }
        return pairs > 0 && zeros.toDouble() / pairs > 0.7
    }
}
