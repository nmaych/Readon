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
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Sort
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
import com.readon.reader.data.formatSize
import com.readon.reader.ui.collectAsStateSafe
import com.readon.reader.ui.components.BookCover
import com.readon.reader.ui.components.EmptyState
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
    // book, so it reloads on every settings revision and whenever it appears.
    val revision by app.prefs.revision.collectAsStateSafe()
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
        scope.launch {
            importing = true
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
                } catch (e: Exception) {
                    problems.add(e.message ?: "导入失败")
                }
            }
            importing = false
            refreshTick++
            when {
                ok > 0 && problems.isEmpty() -> toast("已导入 $ok 本书")
                ok > 0 -> toast("已导入 $ok 本，${problems.size} 本失败：${problems.first()}")
                else -> toast(problems.firstOrNull() ?: "导入失败")
            }
        }
    }

    LaunchedEffect(revision, refreshTick) {
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
                        Icon(Icons.Filled.Sort, contentDescription = "排序")
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
                icon = Icons.Filled.MenuBook,
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
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
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
    }
}
