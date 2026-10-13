package com.readon.reader.data

import java.util.Locale

/** Where the online library lives. */
data class LibrarySource(
    val owner: String,
    val repo: String,
    val branch: String,
) {
    val key: String get() = "$owner/$repo@$branch"

    val repoUrl: String get() = "https://github.com/$owner/$repo"

    override fun toString(): String = "$owner/$repo ($branch)"
}

/** A file as reported by the repository tree. */
data class RepoFile(
    val path: String,
    val size: Long,
)

/** Ebook formats Readon knows how to open. */
enum class BookFormat(val ext: String, val label: String) {
    TXT("txt", "文本"),
    MD("md", "Markdown"),
    HTML("html", "网页"),
    EPUB("epub", "EPUB"),
    PDF("pdf", "PDF"),
    ;

    companion object {
        private val byExt = entries.associateBy { it.ext }

        /** Formats that also swallow a couple of common aliases. */
        fun of(path: String): BookFormat? {
            val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
            byExt[ext]?.let { return it }
            return when (ext) {
                "markdown", "mdown" -> MD
                "htm", "xhtml" -> HTML
                "text" -> TXT
                else -> null
            }
        }

        fun isReadable(path: String): Boolean = of(path) != null
    }
}

/**
 * One book.
 *
 * The same class describes an online-only entry and a downloaded one: [localFile]
 * is null until the file has been fetched, and [id] is stable across both states
 * so reading progress survives a download.
 */
data class Book(
    val id: String,
    val title: String,
    val author: String?,
    val folder: String,
    val path: String,
    val format: String,
    val size: Long,
    val owner: String,
    val repo: String,
    val branch: String,
    val localFile: String? = null,
    val downloadedAt: Long = 0L,
    val lastReadAt: Long = 0L,
    /** 0f..1f through the book. */
    val progress: Float = 0f,
    /** Character offset of the top of the current page, for text formats. */
    val charOffset: Int = 0,
    /** Chapter index for formats that have chapters, minus one when they do not. */
    val chapterIndex: Int = -1,
    /**
     * How many chapters the book has, as counted when it was last opened.
     *
     * The shelf needs the total to show "第 12 / 340 章", and it cannot count them
     * itself: building the outline means decoding and scanning the whole book,
     * which is the expensive work the reader already did. Zero means "not known
     * yet" -- a book that has never been opened, or a record written before this
     * field existed (Gson leaves an absent field at its Java default, so old
     * indexes read back as 0 rather than failing).
     */
    val chapterCount: Int = 0,
) {
    val downloaded: Boolean get() = localFile != null

    /**
     * True when this book came from the device rather than a repository.
     *
     * Imported books have no remote to fall back to, so anything that would
     * normally re-fetch from the network has to know the difference.
     */
    val isLocal: Boolean get() = owner == LOCAL_OWNER

    val formatEnum: BookFormat? get() = BookFormat.of(path)

    val source: LibrarySource get() = LibrarySource(owner, repo, branch)

    companion object {
        /** Marks a record as locally imported rather than fetched from a repo. */
        const val LOCAL_OWNER = "local"

        fun idOf(source: LibrarySource, path: String): String =
            "${source.owner}/${source.repo}@${source.branch}/$path"

        /**
         * Derive a display title from a path. The library keeps files in
         * per-collection folders (`默认/书名.txt`), so the folder becomes the
         * book's collection and the file stem becomes its title.
         */
        fun fromRepoFile(source: LibrarySource, file: RepoFile): Book? {
            val format = BookFormat.of(file.path) ?: return null
            val segments = file.path.split('/')
            val fileName = segments.last()
            val folder = if (segments.size > 1) segments[segments.size - 2] else "未分类"
            val stem = fileName.substringBeforeLast('.')
            return Book(
                id = idOf(source, file.path),
                title = stem.ifBlank { fileName },
                author = null,
                folder = folder,
                path = file.path,
                format = format.ext,
                size = file.size,
                owner = source.owner,
                repo = source.repo,
                branch = source.branch,
            )
        }
    }
}

/** A folder of books, used to group the home screen. */
data class BookCollection(
    val name: String,
    val books: List<Book>,
)

/** Human-readable size, e.g. "1.4 MB". */
fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "未知"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}
