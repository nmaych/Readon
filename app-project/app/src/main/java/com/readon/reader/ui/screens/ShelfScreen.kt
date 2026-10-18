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

/** The gap between shelf columns, shared by the column arithmetic and the grid. */
private const val SHELF_GAP_DP = 10

/**
 * The shelf: books that live on the device.
 *
 * A grid of generated covers rather than a list, because the cover is the
 * fastest thing to recognise when scanning for "the one I was reading". Sorting
 * defaults to most-recently-read, which keeps the current book in the top-left
 * corner where the eye lands first.
 *
 * The tile is deliberately *small*: a cover, a title of at most two lines, and two
 * lines of meta in one compact column. See [Adapt.SHELF_TILE_TARGET] and
 * [Adapt.shelfColumns] for why -- the earlier version used a 116 dp *minimum* and no
 * target, and filled a phone screen with three oversized covers.
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
            // therefore computed in [Adapt] and the grid is fixed at it.
            //
            // [Adapt.shelfColumns] rather than [Adapt.gridColumns]: the latter feeds
            // on a *minimum* tile width, so it always answers with the smallest legal
            // number of columns -- which is what made each cover enormous on a phone
            // (three 126 dp tiles across a 411 dp window). The shelf function works
            // from a *target* tile width instead, so a wider window gets more covers
            // of the same size rather than fewer covers of a bigger one.
            //
            // The content column is capped first ([Adapt.contentWidthDp], the same cap
            // the home screen's list uses) and the leftover becomes a symmetric gutter.
            // Without that cap a 2560 dp window would either keep growing the tiles
            // with every extra column or hit the safety ceiling and snap back to
            // 200 dp covers; with it, a cover is ~96 dp at every window size and a
            // tablet simply shows more of them.
            val window = LocalWindowSize.current
            val contentWidth = remember(window.widthDp) {
                Adapt.contentWidthDp(window.widthDp, window.widthClass)
            }
            val sideGutter = remember(window.widthDp, contentWidth) {
                ((window.widthDp - contentWidth).coerceAtLeast(0)) / 2
            }
            val columns = remember(contentWidth) {
                Adapt.shelfColumns(
                    widthDp = contentWidth - 32, // minus the 16 dp horizontal padding
                    gapDp = SHELF_GAP_DP,
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(
                    start = (16 + sideGutter).dp,
                    end = (16 + sideGutter).dp,
                    top = 4.dp,
                    bottom = contentPadding.calculateBottomPadding() + 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(SHELF_GAP_DP.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(sorted, key = { it.id }) { book ->
                    ShelfItem(
                        book = book,
                        now = now,
                        onOpen = { onOpen(book) },
                        onLongPress = { pendingDelete = book },
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
) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Box(Modifier.fillMaxWidth()) {
            BookCover(
                title = book.title,
                // The folder used to be drawn *on* the cover. At 96 dp it would take
                // the title's space for something the reader can already infer from
                // the book list, so the cover carries only the format badge now.
                subtitle = null,
                badge = book.format.uppercase(),
                progress = book.progress,
                compact = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.68f)
                    .combinedClickable(
                        onClick = onOpen,
                        onLongClick = onLongPress,
                    ),
            )
        }

        Spacer(Modifier.height(5.dp))

        // One line of title at 12 sp, two when the title needs it. The cover is
        // decoration; this is what the reader scans for.
        Text(
            text = book.title,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        // The percentage and the chapter position share one line: "23% · 12/340 章".
        // Two short facts, and on a 96 dp tile two separate lines cost more height than
        // they are worth.
        val chapterText = ShelfLabels.chapterProgress(book)
        val percent = if (book.progress > 0.001f) "${(book.progress * 100).toInt()}%" else "未读"
        Text(
            text = if (chapterText != null) "$percent · $chapterText" else percent,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            color = if (book.progress > 0.001f) MaterialTheme.colorScheme.secondary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // When the book was last opened. Relative for anything recent, because
        // "今天 14:30" answers "did I read this today?" and a full date does not.
        // A book that has never been opened shows nothing rather than 1970.
        val lastRead = ShelfLabels.lastRead(book.lastReadAt, now)
        Text(
            // Always present, even when empty: a missing line would make every tile
            // whose book has never been read 13 dp shorter, and the grid would have
            // ragged rows.
            text = lastRead ?: "",
            fontSize = 10.sp,
            lineHeight = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    // The delete action lives on the long-press menu (see the caller) rather than on a
    // button in every tile. At 96 dp a 24 dp button next to a 10 sp label is a third of
    // the tile spent on the destructive action, and it is the one action that must not
    // be easy to hit by accident.
}
