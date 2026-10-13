package com.readon.reader.ui.screens

import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readon.reader.ReadonApp
import com.readon.reader.data.Book
import com.readon.reader.data.BookContent
import com.readon.reader.data.BookLoader
import com.readon.reader.data.Chapters
import com.readon.reader.data.UnsupportedBookException
import com.readon.reader.reader.Paginator
import com.readon.reader.reader.ReaderState
import com.readon.reader.reader.TextPaginator
import com.readon.reader.ui.collectAsStateSafe
import com.readon.reader.ui.components.EmptyState
import com.readon.reader.ui.theme.ReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The reader.
 *
 * Text is laid out with the same [rememberTextMeasurer] Compose uses to draw, so
 * pagination and rendering cannot disagree about what fits.
 *
 * Reading position is stored as a character offset into the book, never a page
 * number. Changing the font size repaginates everything; an offset keeps the
 * reader on the same sentence, where a page number would drop them somewhere
 * unrelated.
 */
@Composable
fun ReaderScreen(
    app: ReadonApp,
    book: Book,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val prefs = app.prefs
    val revision by prefs.revision.collectAsStateSafe()

    // One state holder per book, so leaving and re-entering a book starts clean
    // but a recomposition or a settings change does not.
    val state = remember(book.id) { ReaderState() }
    // Bumping this re-runs the loader for the retry button.
    var loadAttempt by remember(book.id) { mutableStateOf(0) }

    val readerTheme = remember(revision) { prefs.readerTheme }
    val fontSize = remember(revision) { prefs.fontSize }
    val lineSpacing = remember(revision) { prefs.lineSpacing }
    val serif = remember(revision) { prefs.serif }
    val keepAwake = remember(revision) { prefs.keepScreenOn }

    LaunchedEffect(book.id, loadAttempt) {
        state.begin(book)
        // The loader reports its stage from the IO thread. A StateFlow is safe to
        // write from there, and this collector moves the value into Compose state
        // on the composition's own dispatcher.
        val stage = MutableStateFlow(state.stage)
        val stageCollector = launch { stage.collect { state.stage = it } }
        try {
            val loader = BookLoader(context, app.github)
            val content = withContext(Dispatchers.IO) {
                loader.load(book) { s -> stage.value = s }
            }
            state.succeed(content, book.chapterIndex, 0)
        } catch (e: UnsupportedBookException) {
            state.fail(e.message ?: "无法打开")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The screen is going away; leave the state alone.
            throw e
        } catch (e: Throwable) {
            // `Throwable`, not `Exception`: an OutOfMemoryError while decoding a
            // large book is exactly the case that left the reader stuck on
            // "正在排版…" forever, because an Error sailed past the old catch and
            // the status was never moved off Loading.
            state.fail(e.message ?: "加载失败，请重试或先下载到书架")
        } finally {
            stageCollector.cancel()
        }
    }

    // Keep the screen awake while reading, if the user asked for it.
    DisposableEffect(keepAwake, book.id) {
        val window = (context as? android.app.Activity)?.window
        if (keepAwake) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val textStyle = remember(fontSize, lineSpacing, serif, readerTheme) {
        TextStyle(
            fontSize = fontSize.sp,
            lineHeight = (fontSize * lineSpacing).sp,
            fontFamily = if (serif) FontFamily.Serif else FontFamily.SansSerif,
            color = readerTheme.text,
            textAlign = TextAlign.Justify,
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(readerTheme.background)
    ) {
        when (val status = state.status) {
            is ReaderState.Status.Idle, is ReaderState.Status.Loading ->
                LoadingView(readerTheme, state.stage, onClose)

            is ReaderState.Status.Failed -> Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                EmptyState(
                    icon = Icons.Filled.AutoStories,
                    title = "打不开这本书",
                    message = status.message,
                    action = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onClose) { Text("返回") }
                            TextButton(onClick = { loadAttempt++ }) { Text("重试") }
                        }
                    },
                )
            }

            is ReaderState.Status.Ready -> ReaderBody(
                app = app,
                book = book,
                state = state,
                readerTheme = readerTheme,
                textStyle = textStyle,
                measurer = measurer,
                density = density,
                onClose = onClose,
            )
        }
    }
}

/**
 * The loading state.
 *
 * It shows the loader's current stage rather than a fixed message, and offers a
 * way back: a book being fetched over a slow route can legitimately take a while,
 * and a screen with no exit is indistinguishable from a frozen one.
 */
@Composable
private fun LoadingView(theme: ReaderTheme, stage: String, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(strokeWidth = 3.dp, color = theme.secondaryText)
        Spacer(Modifier.height(14.dp))
        Text(stage, color = theme.secondaryText, fontSize = 14.sp)
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onClose) {
            Text("返回", color = theme.secondaryText)
        }
    }
}

@Composable
private fun ReaderBody(
    app: ReadonApp,
    book: Book,
    state: ReaderState,
    readerTheme: ReaderTheme,
    textStyle: TextStyle,
    measurer: TextMeasurer,
    density: Density,
    onClose: () -> Unit,
) {
    val content = state.content ?: return

    val horizontalPadding = 22.dp
    val verticalPadding = 16.dp

    // The viewport is measured by the constraints of the page area itself, so
    // there is no second pass and no chance of measuring a different box than
    // the one the text is drawn into.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageWidthPx = with(density) {
            (constraints.maxWidth - horizontalPadding.toPx() * 2).toInt()
        }
        val pageHeightPx = with(density) {
            (constraints.maxHeight - verticalPadding.toPx() * 2).toInt()
        }

        val paginator = remember(
            content, state.chapterIndex, pageWidthPx, pageHeightPx, textStyle,
        ) {
            if (pageWidthPx <= 0 || pageHeightPx <= 0) {
                null
            } else {
                // Paginate one chapter at a time. A novel is over a million
                // characters; measuring it in one go would stall for seconds,
                // and the chapter is the unit the user navigates in anyway.
                val chapter = content.chapters.getOrNull(state.chapterIndex)
                val slice = if (chapter != null) {
                    content.text.substring(chapter.start, chapter.end)
                } else {
                    content.text
                }
                Paginator(
                    text = slice,
                    paginator = TextPaginator(measurer, density),
                    style = textStyle,
                    width = pageWidthPx,
                    height = pageHeightPx,
                )
            }
        }

        // Restore the saved position once, on the first layout of each chapter.
        var restoredFor by remember(paginator) { mutableStateOf<Int?>(null) }
        LaunchedEffect(paginator) {
            val p = paginator ?: return@LaunchedEffect
            if (restoredFor == state.chapterIndex) return@LaunchedEffect
            val chapter = content.chapters.getOrNull(state.chapterIndex)
            val target = when {
                chapter != null && book.charOffset in chapter.start until chapter.end ->
                    book.charOffset - chapter.start
                chapter == null && book.charOffset > 0 -> book.charOffset
                else -> 0
            }
            if (target > 0) state.pageInChapter = p.pageOf(target)
            restoredFor = state.chapterIndex
        }

        // The "previous chapter, last page" sentinel is resolved here, where the
        // page count is known.
        LaunchedEffect(paginator) {
            val p = paginator ?: return@LaunchedEffect
            if (state.pageInChapter >= SENTINEL_LAST_PAGE) {
                p.completeAll()
                state.pageInChapter = (p.pageCount - 1).coerceAtLeast(0)
            }
        }

        // Apply a deferred search jump now that the target chapter is paginated.
        LaunchedEffect(paginator, state.pendingSearchOffset) {
            val target = state.pendingSearchOffset ?: return@LaunchedEffect
            val p = paginator ?: return@LaunchedEffect
            state.pageInChapter = p.pageOf(target)
            state.pendingSearchOffset = null
        }

        // Track the current offset and persist progress. Keyed on the page, so
        // it runs exactly when the position changes.
        LaunchedEffect(paginator, state.pageInChapter, state.chapterIndex) {
            val p = paginator ?: return@LaunchedEffect
            val chapter = content.chapters.getOrNull(state.chapterIndex)
            val page = p.page(state.pageInChapter)
            val absolute = if (chapter != null) chapter.start + page.start else page.start
            state.currentOffset = absolute
            app.store.updateProgress(
                id = book.id,
                progress = state.progress(absolute),
                charOffset = absolute,
                chapterIndex = state.chapterIndex,
                // The outline was built when the book loaded, so the shelf's
                // "第 12 / 340 章" does not have to decode the book again.
                chapterCount = content.chapters.size,
            )
        }

        fun goToChapter(index: Int, lastPage: Boolean = false) {
            state.chapterIndex = index
            state.pageInChapter = if (lastPage) SENTINEL_LAST_PAGE else 0
        }

        fun turn(delta: Int) {
            val p = paginator ?: return
            val next = state.pageInChapter + delta
            if (next < 0) {
                if (state.chapterIndex > 0) goToChapter(state.chapterIndex - 1, lastPage = true)
                return
            }
            // Producing the next page is how "is there one?" is answered: pages
            // are computed on demand, so there is no up-front count to consult.
            p.ensurePage(next)
            if (next < p.pageCount) {
                state.pageInChapter = next
            } else if (content.chapters.isNotEmpty() &&
                state.chapterIndex + 1 < content.chapters.size
            ) {
                goToChapter(state.chapterIndex + 1)
            }
        }

        // --- the page ------------------------------------------------------
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding, vertical = verticalPadding)
                .pointerInput(paginator, state.chapterIndex) {
                    detectTapGestures { offset ->
                        val third = size.width / 3f
                        when {
                            offset.x < third -> turn(-1)
                            offset.x > third * 2 -> turn(1)
                            else -> state.chromeVisible = !state.chromeVisible
                        }
                    }
                },
        ) {
            if (paginator != null) {
                Text(
                    text = paginator.textOf(state.pageInChapter),
                    style = textStyle,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // --- chrome --------------------------------------------------------
        AnimatedVisibility(
            visible = state.chromeVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopBar(
                title = book.title,
                chapterTitle = content.chapters.getOrNull(state.chapterIndex)?.title,
                theme = readerTheme,
                onClose = onClose,
                onToc = { state.tocVisible = true },
                onSearch = { state.searchVisible = !state.searchVisible },
            )
        }

        AnimatedVisibility(
            visible = state.chromeVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderBottomBar(
                state = state,
                theme = readerTheme,
                paginator = paginator,
                onPrevChapter = {
                    if (state.chapterIndex > 0) goToChapter(state.chapterIndex - 1)
                },
                onNextChapter = {
                    if (state.chapterIndex + 1 < content.chapters.size) {
                        goToChapter(state.chapterIndex + 1)
                    }
                },
            )
        }

        if (state.tocVisible) {
            TocPanel(
                chapters = content.chapters,
                current = state.chapterIndex,
                theme = readerTheme,
                onPick = { goToChapter(it) ; state.tocVisible = false },
                onDismiss = { state.tocVisible = false },
            )
        }

        if (state.searchVisible) {
            SearchPanel(
                state = state,
                content = content,
                theme = readerTheme,
                onJump = { absolute ->
                    val index = Chapters.indexAt(content.chapters, absolute)
                    if (index >= 0) {
                        val chapter = content.chapters[index]
                        state.chapterIndex = index
                        state.pageInChapter = 0
                        state.pendingSearchOffset = absolute - chapter.start
                    } else {
                        state.pendingSearchOffset = absolute
                    }
                    state.searchVisible = false
                },
                onDismiss = { state.searchVisible = false },
            )
        }
    }
}

/** Signals "the last page of this chapter"; resolved once the count is known. */
private const val SENTINEL_LAST_PAGE = Int.MAX_VALUE / 2

/* ------------------------------------------------------------------------- */

@Composable
private fun ReaderTopBar(
    title: String,
    chapterTitle: String?,
    theme: ReaderTheme,
    onClose: () -> Unit,
    onToc: () -> Unit,
    onSearch: () -> Unit,
) {
    Surface(color = theme.background, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = theme.text)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = chapterTitle ?: title,
                    color = theme.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (chapterTitle != null) {
                    Text(
                        text = title,
                        color = theme.secondaryText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onSearch) {
                Icon(Icons.Filled.Search, contentDescription = "搜索", tint = theme.text)
            }
            IconButton(onClick = onToc) {
                Icon(Icons.Filled.List, contentDescription = "目录", tint = theme.text)
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(
    state: ReaderState,
    theme: ReaderTheme,
    paginator: Paginator?,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
) {
    val hasChapters = state.chapters.isNotEmpty()
    val fraction = state.readingFraction()

    Surface(color = theme.background, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding()) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.secondary,
                trackColor = theme.secondaryText.copy(alpha = 0.2f),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasChapters) {
                    IconButton(onClick = onPrevChapter, enabled = state.chapterIndex > 0) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = "上一章",
                            tint = if (state.chapterIndex > 0) theme.text
                            else theme.secondaryText.copy(alpha = 0.4f),
                        )
                    }
                }
                Text(
                    text = if (hasChapters) {
                        "第 ${state.chapterIndex + 1} / ${state.chapters.size} 章"
                    } else if (paginator != null) {
                        // The "+" marks a count that is still growing, so the
                        // user is not misled into thinking it is the last page.
                        "第 ${state.pageInChapter + 1} / ${paginator.pageCount}" +
                            if (!paginator.isComplete) "+" else ""
                    } else {
                        ""
                    },
                    color = theme.secondaryText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${(fraction * 100).toInt()}%",
                    color = theme.secondaryText,
                    fontSize = 12.sp,
                )
                if (hasChapters) {
                    IconButton(
                        onClick = onNextChapter,
                        enabled = state.chapterIndex + 1 < state.chapters.size,
                    ) {
                        Icon(
                            Icons.Filled.ArrowForward,
                            contentDescription = "下一章",
                            tint = if (state.chapterIndex + 1 < state.chapters.size) theme.text
                            else theme.secondaryText.copy(alpha = 0.4f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TocPanel(
    chapters: List<Chapters.Chapter>,
    current: Int,
    theme: ReaderTheme,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(current) {
        if (current in chapters.indices) listState.scrollToItem(current)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(onClick = onDismiss)
    ) {
        Surface(
            color = theme.background,
            shape = RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp),
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.82f)
                // Swallow taps so they do not fall through to the scrim.
                .clickable(enabled = true, onClick = {}),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "目录",
                        color = theme.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("${chapters.size} 章", color = theme.secondaryText, fontSize = 12.sp)
                }

                if (chapters.isEmpty()) {
                    Text(
                        "这本书没有可识别的章节结构。",
                        color = theme.secondaryText,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(chapters, key = { it.index }) { chapter ->
                            val selected = chapter.index == current
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(chapter.index) }
                                    .padding(horizontal = 16.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = chapter.title,
                                    color = if (selected) MaterialTheme.colorScheme.secondary
                                    else theme.text,
                                    fontSize = 14.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold
                                    else FontWeight.Normal,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (selected) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchPanel(
    state: ReaderState,
    content: BookContent,
    theme: ReaderTheme,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = theme.background,
        tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
    ) {
        Column(Modifier.padding(14.dp)) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { q ->
                    state.searchQuery = q
                    state.searchHits = findAll(content.text, q)
                    state.searchIndex = 0
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("在本书中搜索", fontSize = 14.sp) },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp))
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )

            when {
                state.searchHits.isNotEmpty() -> {
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "找到 ${state.searchHits.size} 处",
                            color = theme.secondaryText,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {
                            state.searchIndex =
                                (state.searchIndex - 1 + state.searchHits.size) % state.searchHits.size
                        }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "上一个", tint = theme.text)
                        }
                        Text("${state.searchIndex + 1}", color = theme.text, fontSize = 13.sp)
                        IconButton(onClick = {
                            state.searchIndex = (state.searchIndex + 1) % state.searchHits.size
                        }) {
                            Icon(Icons.Filled.ArrowForward, contentDescription = "下一个", tint = theme.text)
                        }
                        TextButton(onClick = { onJump(state.searchHits[state.searchIndex]) }) {
                            Text("跳转")
                        }
                    }

                    // Show the surrounding text, so the user can tell whether
                    // this is the occurrence they meant before jumping.
                    val hit = state.searchHits[state.searchIndex]
                    val from = (hit - 20).coerceAtLeast(0)
                    val to = (hit + state.searchQuery.length + 30).coerceAtMost(content.text.length)
                    if (from < to) {
                        Text(
                            text = "…" + content.text.substring(from, to).replace('\n', ' ') + "…",
                            color = theme.secondaryText,
                            fontSize = 12.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                state.searchQuery.isNotEmpty() -> {
                    Spacer(Modifier.height(8.dp))
                    Text("没有找到", color = theme.secondaryText, fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * Find every occurrence of [query], literally.
 *
 * A literal search is what "find in book" means, and treating the query as a
 * regex would make `(` throw and `.` match the whole book.
 */
private fun findAll(text: String, query: String, limit: Int = 500): List<Int> {
    if (query.isEmpty()) return emptyList()
    val out = ArrayList<Int>()
    var from = 0
    while (out.size < limit) {
        val i = text.indexOf(query, from, ignoreCase = true)
        if (i < 0) break
        out.add(i)
        from = i + query.length
    }
    return out
}
