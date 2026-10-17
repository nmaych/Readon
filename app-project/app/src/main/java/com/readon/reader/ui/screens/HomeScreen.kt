package com.readon.reader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readon.reader.ReadonApp
import com.readon.reader.data.Book
import com.readon.reader.data.BookCollection
import com.readon.reader.data.HomeMemory
import com.readon.reader.data.clampedPosition
import com.readon.reader.data.collectionAvailable
import com.readon.reader.data.formatSize
import com.readon.reader.data.isMeaningful
import com.readon.reader.data.restorable
import com.readon.reader.data.OnlineLibrary
import com.readon.reader.ui.Adapt
import com.readon.reader.ui.collectAsStateSafe
import com.readon.reader.ui.components.BookCover
import com.readon.reader.ui.components.Chip
import com.readon.reader.ui.components.EmptyState
import com.readon.reader.ui.components.LocalWindowSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * One row of the library, flattened.
 *
 * The two layouts (a single column on a phone, a grid on a tablet) emit exactly the
 * same sequence -- a collection header, then that collection's books, then the next
 * header -- so a *flattened index* means the same thing in both. That is what lets
 * the remembered scroll position survive a rotation across the breakpoint instead of
 * landing somewhere arbitrary.
 */
private sealed interface HomeItem {
    /** The collection header. Spans the full width in the grid layout. */
    data class Header(val name: String, val count: Int) : HomeItem

    data class Row(val book: Book) : HomeItem
}

/**
 * The online library.
 *
 * One recursive listing of the configured repository, grouped by the folder each
 * file came from -- the library keeps collections as folders (`默认/书名.txt`),
 * so the folder name is the collection.
 *
 * The listing itself is owned by [OnlineLibrary] at application scope, not by this
 * screen. Compose disposes this composable every time the user visits another tab,
 * so holding the library here meant re-running the whole multi-host listing race on
 * every return to 主页 -- and it cancelled any download that was in flight. This
 * screen now only *renders* that state and asks for a load when the configuration is
 * new.
 *
 * ## Browse memory
 *
 * The search box, the collection filter and the scroll position are saved to
 * [com.readon.reader.data.Prefs.homeMemory] and restored on the way back in. The two
 * failure modes this avoids are spelled out on
 * [com.readon.reader.data.HomeMemory.restorable] -- restoring against a different
 * repository, and restoring a scroll offset before the list has any items to scroll
 * past.
 *
 * ## Adaptive layout
 *
 * The window's width class comes from [LocalWindowSize], measured once at the root of
 * the app. A phone keeps the single-column list it always had; a tablet gets a
 * centred, capped column -- so a row's cover and its download button are not half a
 * screen apart -- with enough width for a second or third column of rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    app: ReadonApp,
    contentPadding: PaddingValues,
    toast: (String) -> Unit,
    onOpen: (Book) -> Unit,
    dataRevision: Int = 0,
) {
    val library = app.library
    val window = LocalWindowSize.current
    val wide = window.isWide

    // Re-read the source whenever settings change it.
    val revision by app.prefs.revision.collectAsStateSafe()
    val source = remember(revision) { app.prefs.source }

    // The saved browse state, read once per screen appearance. Deliberately not
    // re-read on `revision`: the memory's own setter is quiet (see
    // `Prefs.editQuiet`), so it cannot change under us, and re-reading it would
    // fight whatever the user is typing right now.
    val saved = remember { app.prefs.homeMemory }
    val restore = remember(saved, source.key) { saved.restorable(source.key) }

    var query by remember { mutableStateOf(if (restore) saved.query else "") }
    var selectedCollection by remember {
        mutableStateOf(if (restore) saved.collection else null)
    }

    // Both list states are created unconditionally so a width change does not lose
    // the position it had; only one is ever attached to a visible container.
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    // Load once per configuration. The holder short-circuits when the cached listing
    // is still valid, so returning to this tab is free.
    //
    // `dataRevision` is in the keys because reading a book is the main way one ends
    // up in the online cache: closing the reader bumps it, so the 已缓存 marks are
    // re-derived on the way back rather than staying as they were before the book
    // was read.
    LaunchedEffect(source, revision, dataRevision) {
        library.ensureLoaded()
        library.refreshDownloaded()
        library.refreshCached()
    }

    // 设置 can empty the online cache from its own screen, with no call into the
    // library at all. Without this the 已缓存 marks survive the clear and claim a book
    // opens instantly when its file is gone.
    //
    // `forgetCached()` rather than `refreshCached()`: the latter returns early when
    // no listing is loaded, which is the common case here (the user cleared the cache
    // before ever opening 主页), and would leave the previous scan's marks on screen.
    val cacheRevision = app.cacheRevision
    LaunchedEffect(cacheRevision) {
        if (cacheRevision > 0) library.forgetCached()
    }

    val books = library.books
    val loading = library.loading
    val error = library.error
    val truncated = library.truncated
    val downloaded = library.downloadedIds
    val cached = library.cachedIds

    val allCollections = remember(books) { books.map { it.folder }.distinct().sorted() }

    // A collection that has since been renamed or deleted would hide every book and
    // read as a broken library. Drop the filter rather than restore it.
    //
    // Gated on `books.isNotEmpty()`: the listing is loaded asynchronously, so on the
    // first composition every collection list is empty. Validating then would clear
    // the restored filter before the books that justify it had arrived -- the user's
    // filter would survive rotation but never survive a restart, which is exactly the
    // kind of half-working behaviour the feature is meant to remove.
    LaunchedEffect(allCollections, restore) {
        if (books.isEmpty()) return@LaunchedEffect
        if (selectedCollection != null && !collectionAvailable(selectedCollection, allCollections)) {
            selectedCollection = null
        }
    }

    val collections: List<BookCollection> = remember(books, query, selectedCollection) {
        val filtered = if (query.isBlank()) books else books.filter {
            it.title.contains(query, true) ||
                it.folder.contains(query, true) ||
                it.path.contains(query, true)
        }
        filtered.groupBy { it.folder }
            .map { (folder, list) -> BookCollection(folder, list) }
            .sortedBy { it.name }
            .let { all ->
                if (selectedCollection == null) all
                else all.filter { it.name == selectedCollection }
            }
    }

    val items: List<HomeItem> = remember(collections) {
        buildList {
            collections.forEach { collection ->
                add(HomeItem.Header(collection.name, collection.books.size))
                collection.books.forEach { add(HomeItem.Row(it)) }
            }
        }
    }

    val totalShown = collections.sumOf { it.books.size }

    // --- restore once, when there is something to scroll ----------------------
    //
    // `items.isEmpty()` is the gate. A `LazyListState` asked to scroll to index 40
    // when the list has no items clamps to 0 and never retries, so the restore has
    // to wait for the listing. Keyed on the *arrival* of the items rather than on
    // `query`, so typing a search does not yank the list back to the remembered
    // position; and latched by `restored` so it happens once per visit.
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(items, restore, wide) {
        if (restored || !restore || items.isEmpty()) return@LaunchedEffect
        val (index, offset) = saved.clampedPosition(items.size)
        if (index > 0) {
            // One frame, so the rows have been measured and the scroll has somewhere
            // to land. `scrollToItem` before layout is a no-op on some versions.
            delay(RESTORE_DELAY_MS)
            runCatching {
                if (wide) gridState.scrollToItem(index, offset)
                else listState.scrollToItem(index, offset)
            }
        }
        restored = true
    }

    // --- save -----------------------------------------------------------------
    //
    // The current values are read through `rememberUpdatedState` in the disposal
    // effect: capturing them directly would freeze them at first composition and
    // save the state the user *arrived* with rather than the one they left with.
    val latestQuery by rememberUpdatedState(query)
    val latestCollection by rememberUpdatedState(selectedCollection)
    val latestSourceKey by rememberUpdatedState(source.key)
    // `wide` too: a rotation across the 600 dp breakpoint swaps which list state is
    // attached, and a disposal effect that captured the first value would save the
    // position of a list the user stopped looking at -- reporting index 0 on a tablet
    // whose grid was scrolled, and vice versa.
    val latestWide by rememberUpdatedState(wide)

    /** Write the current browse state, or clear it when it is the default view. */
    fun saveBrowseState() {
        val fromGrid = latestWide
        val memory = HomeMemory(
            sourceKey = latestSourceKey,
            query = latestQuery,
            collection = latestCollection,
            scrollIndex = if (fromGrid) gridState.firstVisibleItemIndex
            else listState.firstVisibleItemIndex,
            scrollOffset = if (fromGrid) gridState.firstVisibleItemScrollOffset
            else listState.firstVisibleItemScrollOffset,
        )
        if (memory.isMeaningful()) app.prefs.homeMemory = memory
        else app.prefs.clearHomeMemory()
    }

    // Leaving the tab is the event this feature exists for, and `onDispose` is the
    // only place that sees it -- 主页 is disposed outright when `AppShell` switches
    // tabs, so a `remember` in this scope would be gone before it could be read.
    DisposableEffect(Unit) {
        onDispose { saveBrowseState() }
    }

    // A process killed mid-browse, or a crash, never reaches `onDispose`. This keeps
    // the stored position recent while the user scrolls. `collectLatest` cancels the
    // pending delay on every new emission, which makes it a trailing debounce: one
    // write per fling rather than one per frame.
    LaunchedEffect(listState, gridState, query, selectedCollection, wide) {
        snapshotFlow {
            if (wide) {
                gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
            } else {
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            }
        }.collectLatest {
            delay(SAVE_DELAY_MS)
            saveBrowseState()
        }
    }

    // Typing and filtering are not scroll events, so nothing above would record them
    // if the user never scrolled afterwards.
    LaunchedEffect(query, selectedCollection) {
        snapshotFlow { query to selectedCollection }.collectLatest {
            delay(SAVE_DELAY_MS)
            saveBrowseState()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding())
    ) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        "在线书库",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = source.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            actions = {
                if (query.isNotBlank() || selectedCollection != null) {
                    // One tap back to the unfiltered library. Without it the only
                    // way to see a book you had filtered out was to clear the query
                    // character by character -- having possibly forgotten that a
                    // collection filter was on as well.
                    IconButton(onClick = {
                        query = ""
                        selectedCollection = null
                    }) {
                        Icon(Icons.Filled.SearchOff, contentDescription = "清除筛选")
                    }
                }
                IconButton(
                    onClick = {
                        library.ensureLoaded(force = true)
                        library.refreshDownloaded()
                        library.refreshCached()
                    },
                    enabled = !loading,
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        // The header controls and the list share one capped column on a wide window:
        // a search box stretched across a tablet would be a different width from the
        // rows under it, which reads as a mistake.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (wide) {
                            Modifier.width(Adapt.contentMaxWidthDp(window.widthClass).dp)
                        } else {
                            Modifier.fillMaxWidth()
                        }
                    )
            ) {
                // --- search ------------------------------------------------
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    placeholder = { Text("搜索书名或分类", fontSize = 14.sp) },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "清除",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )

                // --- collection filter --------------------------------------
                if (books.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            FilterPill(
                                label = "全部",
                                selected = selectedCollection == null,
                                onClick = { selectedCollection = null },
                            )
                        }
                        items(allCollections) { name ->
                            FilterPill(
                                label = name,
                                selected = selectedCollection == name,
                                onClick = {
                                    selectedCollection =
                                        if (selectedCollection == name) null else name
                                },
                            )
                        }
                    }
                }

                if (truncated) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "仓库文件过多，GitHub 只返回了部分结果。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    when {
                        loading && books.isEmpty() -> LoadingState()

                        error != null && books.isEmpty() -> EmptyState(
                            icon = Icons.Outlined.CloudOff,
                            title = "无法加载书库",
                            message = error.orEmpty(),
                            action = {
                                TextButton(onClick = { library.ensureLoaded(force = true) }) {
                                    Text("重试")
                                }
                            },
                        )

                        totalShown == 0 -> EmptyState(
                            icon = if (query.isBlank()) Icons.Filled.Search
                            else Icons.Filled.SearchOff,
                            title = if (query.isBlank()) "这里还没有书" else "没有匹配的书",
                            message = if (query.isBlank()) {
                                "换个书库地址，或在「设置」里检查仓库配置。"
                            } else {
                                "试试别的关键词。"
                            },
                        )

                        else -> {
                            val row: @Composable (Book) -> Unit = { book ->
                                BookRow(
                                    book = book,
                                    downloaded = downloaded,
                                    cached = cached,
                                    library = library,
                                    onOpen = onOpen,
                                    toast = toast,
                                )
                            }
                            if (wide) {
                                WideBookList(
                                    items = items,
                                    state = gridState,
                                    contentPadding = contentPadding,
                                    row = row,
                                )
                            } else {
                                NarrowBookList(
                                    items = items,
                                    state = listState,
                                    contentPadding = contentPadding,
                                    row = row,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(strokeWidth = 3.dp)
        Spacer(Modifier.height(14.dp))
        Text(
            "正在获取书库…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The phone layout: one column, a header then its rows, in one lazy list. */
@Composable
private fun NarrowBookList(
    items: List<HomeItem>,
    state: LazyListState,
    contentPadding: PaddingValues,
    row: @Composable (Book) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyColumn(
        state = state,
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items.size) { index ->
            when (val item = items[index]) {
                is HomeItem.Header -> SectionHeader(title = item.name, count = item.count)
                is HomeItem.Row -> row(item.book)
            }
        }
    }
}

/**
 * The tablet layout: the same flattened sequence in a grid, with the collection
 * headers spanning every column.
 *
 * A single grid rather than one per collection: a header inside a grid cannot span
 * columns unless it is told to, and giving each collection its own grid would nest lazy
 * containers, which either lose laziness or fail to measure. `GridItemSpan` is the
 * mechanism meant for exactly this.
 *
 * The column count comes from [Adapt.gridColumns] rather than `GridCells.Adaptive`: the
 * outer column is already capped, so this cannot run away, but the two layouts should
 * agree on how many rows fit and the count has to be assertable on the desktop.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WideBookList(
    items: List<HomeItem>,
    state: LazyGridState,
    contentPadding: PaddingValues,
    row: @Composable (Book) -> Unit,
) {
    val window = LocalWindowSize.current
    val columns = remember(window.widthDp) {
        Adapt.gridColumns(
            widthDp = Adapt.contentWidthDp(window.widthDp, window.widthClass) - 32,
            gapDp = 10,
            minColumnDp = Adapt.BOOK_ROW_MIN,
        )
    }

    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items.size) { index ->
            when (val item = items[index]) {
                is HomeItem.Header ->
                    this@LazyVerticalGrid.item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionHeader(title = item.name, count = item.count)
                    }

                is HomeItem.Row ->
                    this@LazyVerticalGrid.item { row(item.book) }
            }
        }
    }
}

@Composable
private fun BookRow(
    book: Book,
    downloaded: Set<String>,
    cached: Set<String>,
    library: OnlineLibrary,
    onOpen: (Book) -> Unit,
    toast: (String) -> Unit,
) {
    LibraryRow(
        book = book,
        isDownloaded = book.id in downloaded,
        isCached = book.id in cached,
        download = library.downloads[book.id],
        // Hand the reader the shelf's own record when the book is already on the
        // device. The listing's entry has no `localFile`, so passing it through would
        // send the reader to the network for a file it already has -- and write a
        // second copy of it into the online cache on the way.
        onOpen = { onOpen(library.openable(book)) },
        onDownload = {
            // Runs at application scope, so navigating away no longer kills the
            // transfer.
            library.download(book) { message -> toast(message) }
        },
    )
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.secondary)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * One book in the online library.
 *
 * Three states can be true of the same book and they are deliberately shown with
 * three different marks, because they mean different things to the user:
 *
 *  * [download] -- being fetched now;
 *  * [isDownloaded] -- kept on the shelf, openable with no network and no risk of
 *    being evicted;
 *  * [isCached] -- read once before, so opening it again skips the download, but it
 *    lives in a directory Android may clean out at any time. Worth showing precisely
 *    because it is *not* a promise: without the mark the tap looks like it will
 *    re-download, and with it the user knows why it opens instantly.
 *
 * [isDownloaded] is checked first in the text line even though [isCached] can
 * temporarily also be true (the cache copy is dropped only once the shelf copy is
 * registered), because the shelf state is the one that survives a reboot.
 */
@Composable
private fun LibraryRow(
    book: Book,
    isDownloaded: Boolean,
    isCached: Boolean,
    download: OnlineLibrary.Download?,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(
                title = book.title,
                modifier = Modifier.size(width = 52.dp, height = 72.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Chip(book.format.uppercase())
                    Text(
                        text = formatSize(book.size),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    download != null -> {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            download.stage,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    isDownloaded -> {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.DownloadDone,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "已在书架",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }

                    // Only reached when the book is neither downloading nor on the
                    // shelf, so the two marks never compete for the same line. It is
                    // drawn in the muted text colour rather than `secondary`: 已在书架
                    // is a durable state the user asked for, this one is a leftover
                    // that the system may reclaim, and giving them the same green
                    // would overstate it.
                    isCached -> {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.OfflinePin,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "已缓存 · 可直接打开",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (download != null) {
                // A ring is not enough here: these books take minutes, so the
                // percentage is shown next to it -- and when the route sends no
                // `Content-Length` there is no percentage to show, so the ring spins
                // rather than sitting at a fixed 0%.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (download.indeterminate) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(horizontal = 10.dp)
                                .size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        CircularProgressIndicator(
                            progress = { download.progress },
                            modifier = Modifier
                                .padding(horizontal = 10.dp)
                                .size(24.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            "${(download.progress * 100).toInt()}%",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                IconButton(onClick = onDownload) {
                    Icon(
                        imageVector = if (isDownloaded) Icons.Filled.DownloadDone
                        else Icons.Filled.CloudDownload,
                        contentDescription = "下载",
                        tint = if (isDownloaded) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * How long the stored position is allowed to lag the screen.
 *
 * Long enough that a fling produces one write rather than sixty, short enough that a
 * process killed mid-browse still has a recent position.
 */
private const val SAVE_DELAY_MS = 400L

/**
 * The pause before a restore is applied.
 *
 * One frame's worth, so the freshly composed rows have been measured. Scrolling
 * before that either clamps to 0 or lands on an estimate that the next layout then
 * corrects, which reads as a flicker.
 */
private const val RESTORE_DELAY_MS = 80L
