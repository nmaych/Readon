package com.readon.reader.data

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
 */
object TextCodec {

    private val gbk: Charset? = runCatching { Charset.forName("GBK") }.getOrNull()
    private val gb18030: Charset? = runCatching { Charset.forName("GB18030") }.getOrNull()

    data class Decoded(val text: String, val charset: String)

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
        return Decoded(String(bytes, Charsets.UTF_8), "UTF-8 (有损)")
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
