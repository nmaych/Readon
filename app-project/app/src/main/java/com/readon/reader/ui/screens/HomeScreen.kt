package com.readon.reader.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readon.reader.ReadonApp
import com.readon.reader.data.Book
import com.readon.reader.data.BookCollection
import com.readon.reader.data.formatSize
import com.readon.reader.net.LibraryException
import com.readon.reader.ui.collectAsStateSafe
import com.readon.reader.ui.components.BookCover
import com.readon.reader.ui.components.Chip
import com.readon.reader.ui.components.EmptyState
import kotlinx.coroutines.launch

/**
 * The online library.
 *
 * One recursive listing of the configured repository, grouped by the folder each
 * file came from -- the library keeps collections as folders (`默认/书名.txt`),
 * so the folder name is the collection.
 *
 * Each row offers the two things a reader wants from a remote library: open it
 * now without keeping a copy, or download it to the shelf.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    app: ReadonApp,
    contentPadding: PaddingValues,
    toast: (String) -> Unit,
    onOpen: (Book) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var truncated by remember { mutableStateOf(false) }
    /** Which route answered, shown so a blocked network is diagnosable. */
    var via by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var selectedCollection by remember { mutableStateOf<String?>(null) }

    // Which books already have a local copy, so the row can say so.
    var downloaded by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Books being downloaded right now, by id, with 0f..1f progress.
    var downloading by remember { mutableStateOf<Set<String>>(emptySet()) }
    val progress = remember { mutableStateMapOf<String, Float>() }

    // Re-read the source whenever settings change it.
    val revision by app.prefs.revision.collectAsStateSafe()
    val source = remember(revision) { app.prefs.source }

    suspend fun refreshDownloaded() {
        downloaded = app.store.load().map { it.id }.toSet()
    }

    suspend fun loadLibrary() {
        loading = true
        error = null
        try {
            val listing = app.github.listBooks(source)
            books = listing.books
            truncated = listing.truncated
            via = listing.via
            if (listing.books.isEmpty()) {
                error = "仓库里没有找到可阅读的文件"
            }
        } catch (e: LibraryException) {
            error = e.message
            books = emptyList()
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
            books = emptyList()
        } finally {
            loading = false
        }
    }

    LaunchedEffect(source) {
        refreshDownloaded()
        loadLibrary()
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

    val totalShown = collections.sumOf { it.books.size }

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
                IconButton(onClick = {
                    scope.launch {
                        refreshDownloaded()
                        loadLibrary()
                    }
                }, enabled = !loading) {
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

        // --- search ---------------------------------------------------------
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = { Text("搜索书名或分类", fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "清除", modifier = Modifier.size(18.dp))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )

        // --- collection filter ----------------------------------------------
        if (books.isNotEmpty()) {
            val names = remember(books) { books.map { it.folder }.distinct().sorted() }
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
                items(names) { name ->
                    FilterPill(
                        label = name,
                        selected = selectedCollection == name,
                        onClick = {
                            selectedCollection = if (selectedCollection == name) null else name
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
                loading && books.isEmpty() -> {
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

                error != null && books.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Outlined.CloudOff,
                        title = "无法加载书库",
                        message = error ?: "",
                        action = {
                            TextButton(onClick = { scope.launch { loadLibrary() } }) {
                                Text("重试")
                            }
                        },
                    )
                }

                totalShown == 0 -> {
                    EmptyState(
                        icon = Icons.Filled.Search,
                        title = if (query.isBlank()) "这里还没有书" else "没有匹配的书",
                        message = if (query.isBlank()) {
                            "换个书库地址，或在「设置」里检查仓库配置。"
                        } else {
                            "试试别的关键词。"
                        },
                    )
                }

                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 4.dp,
                            bottom = contentPadding.calculateBottomPadding() + 24.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        collections.forEach { collection ->
                            item(key = "h-${collection.name}") {
                                SectionHeader(
                                    title = collection.name,
                                    count = collection.books.size,
                                )
                            }
                            itemsIndexed(
                                items = collection.books,
                                key = { _, b -> b.id },
                            ) { _, book ->
                                LibraryRow(
                                    book = book,
                                    isDownloaded = book.id in downloaded,
                                    isDownloading = book.id in downloading,
                                    downloadProgress = progress[book.id],
                                    onOpen = { onOpen(book) },
                                    onDownload = {
                                        if (book.id in downloading) return@LibraryRow
                                        downloading = downloading + book.id
                                        progress[book.id] = 0f
                                        scope.launch {
                                            try {
                                                // Stream straight to the destination
                                                // file: this library's books reach
                                                // 75 MB, far too large to buffer.
                                                val target = app.store.targetFileFor(book)
                                                val written = app.github.downloadTo(
                                                    source = book.source,
                                                    path = book.path,
                                                    target = target,
                                                ) { p -> progress[book.id] = p }
                                                app.store.register(book, target, written)
                                                refreshDownloaded()
                                                toast("已下载：${book.title}")
                                            } catch (e: Exception) {
                                                toast(e.message ?: "下载失败")
                                            } finally {
                                                downloading = downloading - book.id
                                                progress.remove(book.id)
                                            }
                                        }
                                    },
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

@Composable
private fun LibraryRow(
    book: Book,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float?,
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
        Column {
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
                    if (isDownloaded) {
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
                }

                if (isDownloading) {
                    // A ring is not enough here: these books take minutes, so the
                    // percentage is shown next to it.
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            progress = { downloadProgress ?: 0f },
                            modifier = Modifier
                                .padding(horizontal = 10.dp)
                                .size(24.dp),
                            strokeWidth = 2.dp,
                        )
                        if (downloadProgress != null) {
                            Text(
                                "${(downloadProgress * 100).toInt()}%",
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
}
