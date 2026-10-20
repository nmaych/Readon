package com.readon.reader.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readon.reader.ReadonApp
import com.readon.reader.data.Book
import com.readon.reader.data.LocalImport
import com.readon.reader.data.ShelfGroups
import com.readon.reader.data.ShelfLabels
import com.readon.reader.data.formatSize
import com.readon.reader.ui.Adapt
import com.readon.reader.ui.collectAsStateSafe
import com.readon.reader.ui.components.BookCover
import com.readon.reader.ui.components.EmptyState
import com.readon.reader.ui.components.LocalWindowSize
import kotlinx.coroutines.launch

/** The gap between shelf columns, shared by the column arithmetic and the grid. */
private const val SHELF_GAP_DP = 10

/**
 * The shelf: books that live on the device.
 *
 * A grid of generated covers rather than a list, because the cover is the
 * fastest thing to recognise when scanning for "the one I was reading".
 *
 * ## Groups and favourites
 *
 * The grid is divided into sections by [ShelfGroups.sections]: the 收藏 heading
 * first (holding every starred book), then each user-made group alphabetically,
 * then 未分组 last. A starred book is drawn twice on purpose -- once pinned and
 * once in its own group -- because pinning answers "what am I reading now"
 * without taking the book out of where the user put it.
 *
 * ## Batch editing
 *
 * A long press on a cover starts a selection instead of opening the old
 * delete-only dialog. While a selection is open the header becomes a selection
 * bar (count, 全选, 关闭) and the bottom of the screen grows an action row
 * (删除 / 加入书组 / 收藏). Tapping a cover toggles it; tapping outside the
 * selection, or the close button, ends it. This is the same interaction every
 * Android photo gallery uses, which is the point: the user already knows it.
 *
 * The tile itself is deliberately *small*: a cover, a title of at most two
 * lines, and two lines of meta in one compact column. See
 * [Adapt.SHELF_TILE_TARGET] and [Adapt.shelfColumns] for why -- the earlier
 * version used a 116 dp *minimum* and no target, and filled a phone screen with
 * three oversized covers.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    var order by remember { mutableStateOf(ShelfGroups.Order.Recent) }
    var sortMenu by remember { mutableStateOf(false) }
    var usedBytes by remember { mutableStateOf(0L) }
    var importing by remember { mutableStateOf(false) }

    // --- batch selection ---------------------------------------------------
    /**
     * The ids currently selected, empty when the selection is closed.
     *
     * Ids rather than [Book] records: the listing is re-read after every mutation,
     * so the records in hand are replaced while a selection is open. See
     * [ShelfGroups.toggle].
     */
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selecting = selected.isNotEmpty()

    /** The book whose delete confirmation is up, as a one-item selection. */
    var pendingDelete by remember { mutableStateOf<List<Book>>(emptyList()) }

    /** Whether the "加入书组" picker is up. */
    var groupPicker by remember { mutableStateOf(false) }

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
        // A selection that outlives its books is a delete aimed at nothing --
        // or, after a re-download reuses an id, at a book the user never chose.
        val pruned = ShelfGroups.prune(selected, books.map { it.id })
        if (pruned != selected) selected = pruned
    }

    /**
     * Reload after a mutation.
     *
     * The store's revision already triggers the effect above, but that is a
     * `StateFlow` write observed on the main thread -- a frame later. Reading
     * here makes the grid correct on the very next composition, which is what
     * stops a deleted tile from lingering under the toast.
     */
    suspend fun reload() {
        books = app.store.load()
        usedBytes = app.store.usedBytes()
    }

    val sections = remember(books, order) { ShelfGroups.sections(books, order) }
    val favorites = remember(books) { books.count { it.favorite } }
    val groupNames = remember(books) { ShelfGroups.names(books) }

    // One "now" for the whole grid, taken when the listing is (re)read rather
    // than once per item. Reading the clock during composition would make the
    // relative times disagree between items, and re-reading it on every
    // recomposition would let a card's label change while the user looks at it.
    val now = remember(books, refreshTick, dataRevision) { System.currentTimeMillis() }

    // --- the batch actions -------------------------------------------------

    fun closeSelection() {
        selected = emptySet()
    }

    fun askDelete(targets: List<Book>) {
        if (targets.isEmpty()) return
        pendingDelete = targets
    }

    fun applyFavorite(targets: List<Book>, favorite: Boolean) {
        if (targets.isEmpty()) return
        scope.launch {
            val changed = app.store.setFavorite(targets.map { it.id }, favorite)
            reload()
            toast(
                when {
                    changed == 0 && favorite -> "选中的书已经在收藏里"
                    changed == 0 -> "选中的书本来就没有收藏"
                    favorite -> "已收藏 $changed 本"
                    else -> "已取消收藏 $changed 本"
                }
            )
            // The selection stays open either way. Starring a book from the batch
            // bar changes *where it is drawn* (a pinned copy appears), and closing
            // the selection at the same moment would move the shelf out from under
            // the user before they could see that it worked.
        }
    }

    fun applyGroup(targets: List<Book>, group: String?) {
        if (targets.isEmpty()) return
        scope.launch {
            val changed = app.store.setGroup(targets.map { it.id }, group)
            reload()
            toast(
                when {
                    changed == 0 && group == null -> "选中的书本来就没有书组"
                    changed == 0 -> "选中的书已经在这个书组里"
                    group == null -> "已移出书组 $changed 本"
                    else -> "已加入「$group」$changed 本"
                }
            )
            groupPicker = false
            closeSelection()
        }
    }

    // --- the screen --------------------------------------------------------

    // Back leaves the selection before it leaves the tab: while a selection is
    // open the screen is in a mode, and the system gesture that means "undo the
    // mode" must not instead send the user to 主页 with their selection still
    // standing. Enabled only while selecting, so the app shell's own handler
    // keeps its behaviour the rest of the time.
    BackHandler(enabled = selecting) { closeSelection() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding())
    ) {
        if (selecting) {
            SelectionBar(
                count = selected.size,
                allSelected = ShelfGroups.allSelected(selected, books.map { it.id }),
                onToggleAll = {
                    selected = ShelfGroups.toggleAll(selected, books.map { it.id })
                },
                onClose = { closeSelection() },
            )
        } else {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "书架",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = when {
                                books.isEmpty() -> "还没有下载的书"
                                favorites > 0 ->
                                    "${books.size} 本 · ${formatSize(usedBytes)} · 收藏 $favorites"
                                else -> "${books.size} 本 · ${formatSize(usedBytes)}"
                            },
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
                            ShelfGroups.Order.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            option.label,
                                            fontWeight = if (option == order) FontWeight.SemiBold
                                            else FontWeight.Normal,
                                        )
                                    },
                                    onClick = {
                                        order = option
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
        }

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

            // A flattened list of "header or book", for the same reason the home
            // screen flattens its collections: a `LazyVerticalGrid` can give a
            // heading the full width through `GridItemSpan`, but only if the span is
            // declared per *index* while the grid builds its interval list. A nested
            // `item(...)` emitted from inside `items`' content lambda never reaches
            // the layout (see [WideBookList] on 主页, where that exact mistake left
            // the grid blank).
            val rows = remember(sections) {
                buildList {
                    for (section in sections) {
                        // A single 未分组 section over the whole shelf is not a
                        // heading, it is the absence of one -- see
                        // [ShelfGroups.showsHeading].
                        if (ShelfGroups.showsHeading(section, sections.size)) {
                            add(ShelfRow.Header(section))
                        }
                        for (book in section.books) add(ShelfRow.Tile(book, section))
                    }
                }
            }

            Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(
                        start = (16 + sideGutter).dp,
                        end = (16 + sideGutter).dp,
                        top = 4.dp,
                        // Room for the batch action row when it is up, so the last
                        // row of covers is not stuck behind it.
                        bottom = contentPadding.calculateBottomPadding() +
                            if (selecting) BATCH_BAR_HEIGHT + 16.dp else 24.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(SHELF_GAP_DP.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(
                        count = rows.size,
                        key = { index ->
                            when (val row = rows[index]) {
                                // The *section* is part of the key because a pinned
                                // favourite is deliberately drawn twice -- once under
                                // 收藏 and once in its own group. Keying tiles by book
                                // id alone would hand the grid two identical keys,
                                // which is a crash, not a rendering quirk.
                                is ShelfRow.Header ->
                                    "h:${row.section.name}:${row.section.pinned}"
                                is ShelfRow.Tile ->
                                    "t:${row.section.name}:${row.section.pinned}:${row.book.id}"
                            }
                        },
                        span = { index ->
                            if (rows[index] is ShelfRow.Header) GridItemSpan(maxLineSpan)
                            else GridItemSpan(1)
                        },
                    ) { index ->
                        when (val row = rows[index]) {
                            is ShelfRow.Header -> SectionHeading(row.section, favorites)
                            is ShelfRow.Tile -> ShelfItem(
                                book = row.book,
                                now = now,
                                // A pinned favourite is also drawn in its own group;
                                // the copy under 收藏 carries the mark, the copy in the
                                // group does not need to repeat it.
                                showFavoriteMark = row.section.pinned,
                                selecting = selecting,
                                selected = row.book.id in selected,
                                onClick = {
                                    if (selecting) {
                                        selected = ShelfGroups.toggle(selected, row.book.id)
                                    } else {
                                        onOpen(row.book)
                                    }
                                },
                                onLongPress = {
                                    selected = ShelfGroups.toggle(selected, row.book.id)
                                },
                            )
                        }
                    }
                }

                if (selecting) {
                    val chosen = books.filter { it.id in selected }
                    BatchActionBar(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        favoriteLabel = ShelfGroups.favoriteActionLabel(chosen),
                        onDelete = { askDelete(chosen) },
                        onGroup = { groupPicker = true },
                        // The label and the direction come from the same helper, so
                        // a button reading 取消收藏 can never star the selection.
                        onFavorite = {
                            applyFavorite(chosen, ShelfGroups.favoriteActionValue(chosen))
                        },
                    )
                }
            }
        }
    }

    // --- the delete confirmation -------------------------------------------
    //
    // One dialog for one book and for twenty. The wording changes with the count
    // rather than there being a second, terser dialog for batches: the sentence
    // that says 阅读进度也会一起删掉 is exactly the one a user selecting twenty
    // books most needs to read.
    pendingDelete.takeIf { it.isNotEmpty() }?.let { targets ->
        val single = targets.singleOrNull()
        AlertDialog(
            onDismissRequest = { pendingDelete = emptyList() },
            title = {
                Text(if (single != null) "删除这本书？" else "删除这 ${targets.size} 本书？")
            },
            text = {
                Text(
                    if (single != null) {
                        "《${single.title}》将从设备上移除，阅读进度也会一起删掉。\n" +
                            "它仍然可以在在线书库里重新下载。"
                    } else {
                        "选中的 ${targets.size} 本书将从设备上移除，阅读进度也会一起删掉。\n" +
                            "它们仍然可以在在线书库里重新下载。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val batch = targets
                    pendingDelete = emptyList()
                    scope.launch {
                        val removed = app.store.deleteAll(batch.map { it.id })
                        // The bookmarks belong to the books that just went away;
                        // leaving them behind would grow the file forever and
                        // resurrect them if a book were re-downloaded.
                        for (id in removed) app.bookmarks.clear(id)
                        selected = emptySet()
                        reload()
                        toast(
                            if (removed.size == 1) "已删除：${batch.first().title}"
                            else "已删除 ${removed.size} 本"
                        )
                    }
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = emptyList() }) { Text("取消") }
            },
        )
    }

    // --- the group picker --------------------------------------------------
    if (groupPicker) {
        GroupPickerDialog(
            groups = groupNames,
            count = selected.size,
            onDismiss = { groupPicker = false },
            onPick = { name ->
                val group = if (name == ShelfGroups.DEFAULT_NAME) null else name
                applyGroup(books.filter { it.id in selected }, group)
            },
        )
    }
}

/**
 * One row of the flattened shelf: a section heading, or a book inside one.
 *
 * The heading carries the section rather than just its name because the tile
 * needs to know whether it is the pinned copy -- see [ShelfItem.showFavoriteMark].
 */
private sealed interface ShelfRow {
    data class Header(val section: ShelfGroups.Section) : ShelfRow
    data class Tile(val book: Book, val section: ShelfGroups.Section) : ShelfRow
}

/** Height reserved at the bottom of the grid while the batch bar is up. */
private val BATCH_BAR_HEIGHT = 76.dp

/**
 * A group heading in the grid, spanning every column.
 *
 * 收藏 gets a star and 未分组 is drawn muted: the first is a section the user
 * made on purpose and the second is where everything they have not filed yet
 * ends up, and they should not look like peers.
 */
@Composable
private fun SectionHeading(section: ShelfGroups.Section, favorites: Int) {
    val color = when {
        section.pinned -> MaterialTheme.colorScheme.tertiary
        section.default -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onBackground
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when {
                section.pinned -> Icons.Filled.Star
                section.default -> Icons.Filled.FolderOpen
                else -> Icons.Filled.Folder
            },
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = ShelfGroups.headingLabel(section.name, section.books.size),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (section.pinned && favorites > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "置顶",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f),
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

/**
 * The header while a selection is open: count, 全选, close.
 *
 * A replacement for the top bar rather than an extra row under it: the shelf is
 * being used as a selection surface at that moment, and "书架 / 12 本 · 3.4 MB"
 * is no longer the most useful thing to put at the top of the screen.
 */
@Composable
private fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    onToggleAll: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "退出多选")
            }
            Text(
                text = ShelfGroups.selectionLabel(count),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onToggleAll) {
                Icon(
                    Icons.Filled.SelectAll,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (allSelected) "取消全选" else "全选")
            }
        }
    }
}

/**
 * The batch action row: 删除 / 加入书组 / 收藏.
 *
 * Floats over the bottom of the grid rather than being pushed into the layout,
 * so appearing does not resize the grid -- the same rule the reader's own
 * toolbar follows in 1.1.1: an overlay must not move the content under it.
 */
@Composable
private fun BatchActionBar(
    modifier: Modifier = Modifier,
    favoriteLabel: String,
    onDelete: () -> Unit,
    onGroup: () -> Unit,
    onFavorite: () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BatchAction(Icons.Filled.Delete, "删除", MaterialTheme.colorScheme.error, onDelete)
            BatchAction(Icons.Filled.CreateNewFolder, "加入书组", MaterialTheme.colorScheme.primary, onGroup)
            BatchAction(
                icon = if (favoriteLabel == "收藏") Icons.Filled.Star else Icons.Filled.StarBorder,
                label = favoriteLabel,
                tint = MaterialTheme.colorScheme.tertiary,
                onClick = onFavorite,
            )
        }
    }
}

@Composable
private fun BatchAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 11.sp, color = tint, fontWeight = FontWeight.Medium)
    }
}

/**
 * "加入书组": pick an existing group, or type a new one.
 *
 * The existing groups are derived from the books themselves (see
 * [ShelfGroups.names]) rather than stored, so the list can only ever offer a
 * destination that already means something. 未分组 is listed as 移出书组, which
 * is the one action a flat list of groups would not suggest on its own.
 */
@Composable
private fun GroupPickerDialog(
    groups: List<String>,
    count: Int,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    val query = typed.trim()
    // 收藏 and 未分组 are the shelf's own headings; see [ShelfGroups.isReserved].
    val reserved = ShelfGroups.isReserved(typed)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count > 0) "把 $count 本加入书组" else "加入书组") },
        text = {
            Column {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.take(ShelfGroups.MAX_NAME_LENGTH + 8) },
                    label = { Text("新书组名") },
                    singleLine = true,
                    isError = reserved,
                    supportingText = if (reserved) {
                        { Text("「${ShelfGroups.normalize(typed)}」是书架自带的标题，换个名字") }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                val options = groups.filter { ShelfGroups.matches(it, query) }
                Column(Modifier.fillMaxWidth()) {
                    options.forEach { name ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onPick(name) }
                                .padding(horizontal = 6.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (name == ShelfGroups.DEFAULT_NAME) {
                                    Icons.Filled.FolderOpen
                                } else {
                                    Icons.Filled.Folder
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = if (name == ShelfGroups.DEFAULT_NAME) "移出书组" else name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (options.isEmpty()) {
                        Text(
                            text = "没有匹配的书组，可以直接用上面的名字新建。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val name = ShelfGroups.normalize(typed)
                    if (name != null && !reserved) onPick(name) else onDismiss()
                },
                enabled = query.isNotEmpty() && !reserved,
            ) {
                Text(if (query.isEmpty()) "新建" else "新建「$query」")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfItem(
    book: Book,
    now: Long,
    showFavoriteMark: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
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
                        onClick = onClick,
                        onLongClick = onLongPress,
                    ),
            )

            // --- the selection state ---------------------------------------
            //
            // Drawn *over* the cover rather than replacing it: the cover is how the
            // user recognises which book they are ticking, so hiding it to show a
            // checkbox would remove the only information the tap depends on.
            if (selecting) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                            else Color.Black.copy(alpha = 0.10f)
                        )
                )
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else Color.Black.copy(alpha = 0.30f)
                        )
                        .border(
                            width = if (selected) 0.dp else 1.5.dp,
                            color = Color.White.copy(alpha = 0.9f),
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "已选中",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            } else if (showFavoriteMark) {
                // The 收藏 mark: only on the pinned copy. A book drawn in its own
                // group does not repeat it -- the group heading above it already
                // says where it is, and two stars on one shelf would read as two
                // favourites.
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.34f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "收藏",
                        tint = Color(0xFFFFD54F),
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
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

    // Delete lives on the batch bar (long press a cover, then 删除) rather than on a
    // button in every tile. At 96 dp a 24 dp button next to a 10 sp label is a third
    // of the tile spent on the destructive action, and it is the one action that
    // must not be easy to hit by accident.
}
