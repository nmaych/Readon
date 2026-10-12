package com.readon.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Importing books the user already has on the device.
 *
 * The file is read through the content resolver and copied into app storage by
 * [BookStore.importLocal]; the URI grant is only needed for the moment of the
 * copy, so nothing is persisted and nothing breaks if the grant is later revoked.
 */
object LocalImport {

    /** Guard against picking a 2 GB video by mistake. */
    private const val MAX_IMPORT_BYTES = 64L * 1024 * 1024

    /**
     * Formats the picker should offer.
     *
     * A wildcard type is included because plenty of file providers report an
     * `application/octet-stream` or a vendor-specific type for plain text, and a
     * filter that hides the user's own book is worse than one that shows a few
     * extra files -- which [BookFormat] then rejects with a clear message.
     */
    val MIME_TYPES = arrayOf(
        "text/plain",
        "text/markdown",
        "text/html",
        "application/epub+zip",
        "application/pdf",
        "application/octet-stream",
        "*/*",
    )

    /** What went wrong, phrased for the user. */
    class ImportException(message: String) : Exception(message)

    /**
     * Copy the picked document into the shelf.
     *
     * @return the stored record.
     */
    suspend fun import(context: Context, uri: Uri, store: BookStore): Book =
        withContext(Dispatchers.IO) {
            val name = displayName(context, uri)
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "未命名"

            val format = BookFormat.of(name)
                ?: throw ImportException("不支持的文件类型：$name")

            if (format == BookFormat.PDF) {
                throw ImportException(
                    "PDF 暂不支持在 Readon 内阅读，导入后也无法打开。请用系统应用打开 PDF。"
                )
            }

            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readAtMost(MAX_IMPORT_BYTES)
            } ?: throw ImportException("无法读取这个文件")

            if (bytes.isEmpty()) throw ImportException("文件是空的")

            store.importLocal(name, bytes, format)
        }

    /**
     * The document's display name.
     *
     * Read from the provider's cursor rather than from the URI: a document URI
     * ends in an opaque id like `content://.../document/1234`, and using that as
     * a title would put a number on the shelf instead of the book's name.
     */
    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()
}

/** Read at most [limit] bytes from a stream. */
private fun java.io.InputStream.readAtMost(limit: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = read(buf)
        if (n < 0) break
        total += n
        if (total > limit) throw LocalImport.ImportException("文件太大，无法导入")
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
