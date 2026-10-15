package com.readon.reader.data

/**
 * The rules for reading a book that has only been fetched in part.
 *
 * ## Why a book is not downloaded whole before it is shown
 *
 * This library's books reach 75 MB. Waiting for one of those before the first
 * character appears meant the reader sat on "正在下载… 3%" for minutes, on exactly
 * the networks where the mirror racing in [com.readon.reader.net.GithubClient]
 * exists because GitHub is barely reachable. Nothing about reading the *first*
 * page needs the last chapter to be on the device.
 *
 * So the first pass fetches a prefix, the reader opens on it, and the remainder
 * is fetched in the background (see [BookLoader.finishDownload]). Everything that
 * decision depends on lives here, with no Android types, so
 * `tools/check-book-logic.cmd` can exercise it:
 *
 *  * **How much to fetch first.** [firstChunk].
 *  * **Whether what is on disk is the whole file.** [isComplete] -- and the
 *    `Content-Range` parsing that answers it when the repository listing did not
 *    report a size.
 *  * **What a cut in the middle of a character looks like.**
 *    [trimDanglingReplacement]: a prefix ends mid-character, which decodes to a
 *    single replacement character at the very end of the text, and the reader
 *    must not show that as if the book contained it.
 */
object PartialRead {

    /** A parsed `Content-Range` header, e.g. `bytes 0-2047/12345678`. */
    data class ContentRange(
        val start: Long,
        val end: Long,
        /** The file's full size, or 0 when the server wrote `*` -- "not stated". */
        val total: Long,
    ) {
        val length: Long get() = end - start + 1
    }

    /**
     * Parse a `Content-Range` header.
     *
     * `bytes 0-2047/12345678` gives the offset, the last byte of the range and
     * the full size -- which is the only way to learn the size of a book whose
     * listing did not report one. A total of `*` is a valid answer too: the range
     * is known, the total is not, and that is reported as 0 rather than guessed.
     * Anything else (a missing header, a proxy that rewrote it) is null.
     */
    fun parseContentRange(header: String?): ContentRange? {
        val raw = header?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val match = Regex("""^bytes\s+(\d+)-(\d+)/(\d+|\*)$""", RegexOption.IGNORE_CASE)
            .find(raw) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        // A range that ends before it starts is a broken header, not an empty
        // file; treating it as usable would make the reader show nothing.
        if (end < start) return null
        val totalText = match.groupValues[3]
        val total = if (totalText == "*") 0L else totalText.toLongOrNull() ?: 0L
        return ContentRange(start, end, total)
    }

    /**
     * How many bytes to fetch before showing the book.
     *
     * [preferred] is the reader's own budget (see `BookLoader.FIRST_CHUNK_BYTES`).
     * A book smaller than that is fetched whole, because stopping at a prefix of
     * a book that would have arrived in one piece only adds a round trip.
     */
    fun firstChunk(expectedSize: Long, preferred: Long): Long {
        if (preferred <= 0L) return 0L
        if (expectedSize in 1 until preferred) return expectedSize
        return preferred
    }

    /**
     * Does [loaded] bytes mean the whole file is present?
     *
     * Only a known, positive [total] can prove it. An unknown size answers false
     * on purpose: claiming completeness would mean showing a truncated book as a
     * finished one, and the caller has a cheaper way to find out -- asking for
     * the next range, which answers "past the end" exactly when there is nothing
     * left.
     */
    fun isComplete(loaded: Long, total: Long): Boolean = total > 0L && loaded >= total

    /**
     * Drop the replacement character a cut multi-byte sequence leaves behind.
     *
     * The decoder is streaming, so a file that ends half-way through a character
     * ends with exactly one U+FFFD. Only a *trailing* one is removed: a
     * replacement character in the middle of the text is either a real decoding
     * problem in the source file or the deliberate result of the lossy fallback
     * in [TextCodec], and hiding those would hide a genuine "this file is
     * corrupt" signal.
     */
    fun trimDanglingReplacement(text: String): String =
        if (text.isNotEmpty() && text[text.length - 1] == '\uFFFD') text.dropLast(1) else text
}
