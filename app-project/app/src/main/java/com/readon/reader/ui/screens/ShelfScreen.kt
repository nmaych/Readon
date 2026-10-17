package com.readon.reader.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readon.reader.ReadonApp
import com.readon.reader.data.Book
import com.readon.reader.data.LocalImport
import com.readon.reader.data.ShelfLabels
import com.readon.reader.data.formatSize
import com.readon.reader.ui.Adapt
import com.readon.reader.ui.collectAsStateSafe
import com.readon.reader.ui.components.BookCover
import com.readon.reader.ui.components.EmptyState
import com.readon.reader.ui.components.LocalWindowSize
import kotlinx.coroutines.launch

private enum class ShelfSort(val label: String) {
    Recent("最近阅读"),
    Added("最近添加"),
    Title("书名"),
    Author("分类"),
}

/**
 * The shelf: books that live on the device.
 *
 * A grid of generated covers rather than a list, because the cover is the
 * fastest thing to recognise when scanning for "the one I was reading". Sorting
 * defaults to most-recently-read, which keeps the current book in the top-left
 * corner where the eye lands first.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ShelfScreen(
    app: ReadonApp,
    contentPadding: PaddingValues,
    toast: (String) -> Unit,
    onOpen: (Book) -> Unit,
    onBrowse: () -> Unit,
    /** Bumped when the reader closes, so progress changes are picked up. */
    dataRevision: Int = 0,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(ShelfSort.Recent) }
    var sortMenu by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Book?>(null) }
    var usedBytes by remember { mutableStateOf(0L) }
    var importing by remember { mutableStateOf(false) }

    // The shelf must reflect reading progress as the user comes back from a
    // book, so it reloads on every settings revision, whenever it appears, and
    // when the reader is closed over it. It also follows the store's own revision
    // so a download that finishes while this tab is open appears immediately.
    val revision by app.prefs.revision.collectAsStateSafe()
    val storeRevision by app.store.revision.collectAsStateSafe()
    var refreshTick by remember { mutableStateOf(0) }

    /**
     * The system file picker.
     *
     * `OpenMultipleDocuments` so a user can bring in a whole folder's worth of
     * books at once, which is the normal case when moving a library onto a phone.
     */
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        // Importing runs on the application scope. On the screen's scope it was
        // cancelled the moment the user left the tab, which silently abandoned
        // every remaining file in a multi-file import.
        app.launch {
            var ok = 0
            val problems = ArrayList<String>()
            for (uri in uris) {
                try {
                    // Persist read access where the provider allows it, so a book
                    // can be re-read even if the grant would otherwise lapse.
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    LocalImport.import(context, uri, app.store)
                    ok++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    problems.add(e.message ?: "导入失败")
                }
            }
            importing = false
            refreshTick++
            // The store may have grown while the shelf was showing, so re-read
            // the listing here rather than waiting for a recomposition.
            books = app.store.load()
            usedBytes = app.store.usedBytes()
            when {
                ok > 0 && problems.isEmpty() -> toast("已导入 $ok 本书")
                ok > 0 -> toast("已导入 $ok 本，${problems.size} 本失败：${problems.first()}")
                else -> toast(problems.firstOrNull() ?: "导入失败")
            }
        }
    }

    LaunchedEffect(revision, storeRevision, refreshTick, dataRevision) {
        books = app.store.load()
        usedBytes = app.store.usedBytes()
        loaded = true
    }

    val sorted = remember(books, sort) {
        when (sort) {
            ShelfSort.Recent -> books.sortedByDescending { it.lastReadAt }
            ShelfSort.Added -> books.sortedByDescending { it.downloadedAt }
            ShelfSort.Title -> books.sortedBy { it.title }
            ShelfSort.Author -> books.sortedWith(compareBy({ it.folder }, { it.title }))
        }
    }

    // One "now" for the whole grid, taken when the listing is (re)read rather
    // than once per item. Reading the clock during composition would make the
    // relative times disagree between items, and re-reading it on every
    // recomposition would let a card's label change while the user looks at it.
    val now = remember(books, refreshTick, dataRevision) { System.currentTimeMillis() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding())
    ) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        "书架",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (books.isEmpty()) "还没有下载的书"
                        else "${books.size} 本 · ${formatSize(usedBytes)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = {
                if (importing) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(
                        onClick = { picker.launch(LocalImport.MIME_TYPES) },
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "从本地导入")
                    }
                }
                Box {
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        ShelfSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        option.label,
                                        fontWeight = if (option == sort) FontWeight.SemiBold
                                        else FontWeight.Normal,
                                    )
                                },
                                onClick = {
                                    sort = option
                                    sortMenu = false
                                },
                            )
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        if (loaded && books.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                title = "书架是空的",
                message = "到「主页」下载在线书籍，或从手机里导入已有的 txt / epub 文件。",
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onBrowse) {
                            Icon(
                                Icons.Filled.Explore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("去书库看看")
                        }
                        TextButton(onClick = { picker.launch(LocalImport.MIME_TYPES) }) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("导入本地文件")
                        }
                    }
                },
            )
        } else {
            // `GridCells.Adaptive` has no upper bound: at 1200 dp it lays out eleven
            // covers across, which is not a bigger shelf but a contact sheet --
            // titles too small to read, rows too long to scan. The column count is
            // therefore computed and capped in [Adapt], and the grid is fixed at it.
            val window = LocalWindowSize.current
            val columns = remember(window.widthDp) {
                Adapt.gridColumns(
                    widthDp = window.widthDp - 32, // minus the 16 dp horizontal padding
                    gapDp = 12,
                    minColumnDp = Adapt.SHELF_TILE_MIN,
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                    bottom = contentPadding.calculateBottomPadding() + 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(sorted, key = { it.id }) { book ->
                    ShelfItem(
                        book = book,
                        now = now,
                        onOpen = { onOpen(book) },
                        onLongPress = { pendingDelete = book },
                        onDelete = { pendingDelete = book },
                    )
                }
            }
        }
    }

    pendingDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这本书？") },
            text = {
                Text(
                    "《${book.title}》将从设备上移除，阅读进度也会一起删掉。\n" +
                        "它仍然可以在在线书库里重新下载。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = book
                    pendingDelete = null
                    scope.launch {
                        app.store.delete(target.id)
                        // The bookmarks belong to the book that just went away;
                        // leaving them behind would grow the file forever and
                        // resurrect them if the book were re-downloaded.
                        app.bookmarks.clear(target.id)
                        books = app.store.load()
                        usedBytes = app.store.usedBytes()
                        toast("已删除：${target.title}")
                    }
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ShelfItem(
    book: Book,
    now: Long,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Box(Modifier.fillMaxWidth()) {
            BookCover(
                title = book.title,
                subtitle = book.folder,
                badge = book.format.uppercase(),
                progress = book.progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.68f)
                    .combinedClickable(
                        onClick = onOpen,
                        onLongClick = onLongPress,
                    ),
            )
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = book.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(2.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (book.progress > 0.001f) "${(book.progress * 100).toInt()}%"
                else "未读",
                fontSize = 11.sp,
                color = if (book.progress > 0.001f) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
            )
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(15.dp),
                )
            }
        }

        // The chapter position, when the reader has recorded one. Shown next to
        // the percentage rather than instead of it: "12/340 章" says where you
        // are, the percentage says how far through the book that is, and the two
        // legitimately disagree when chapters are of different lengths.
        ShelfLabels.chapterProgress(book)?.let { chapters ->
            Text(
                text = chapters,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // When the book was last opened. Relative for anything recent, because
        // "今天 14:30" answers "did I read this today?" and a full date does not.
        // A book that has never been opened shows nothing rather than 1970.
        ShelfLabels.lastReadLine(book.lastReadAt, now)?.let { line ->
            Text(
                text = line,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
