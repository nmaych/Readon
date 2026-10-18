package com.readon.reader.ui.screens

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Battery1Bar
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.readon.reader.ReadonApp
import com.readon.reader.data.Book
import com.readon.reader.data.BookContent
import com.readon.reader.data.BookLoader
import com.readon.reader.data.Bookmark
import com.readon.reader.data.Bookmarks
import com.readon.reader.data.Chapters
import com.readon.reader.data.ReadingMode
import com.readon.reader.data.ReaderInk
import com.readon.reader.data.UnsupportedBookException
import com.readon.reader.reader.AutoRead
import com.readon.reader.reader.Paginator
import com.readon.reader.reader.ReaderHud
import com.readon.reader.reader.ReaderState
import com.readon.reader.reader.TextFlow
import com.readon.reader.reader.TextPaginator
import com.readon.reader.ui.Adapt
import com.readon.reader.ui.collectAsStateSafe
import com.readon.reader.ui.components.EmptyState
import com.readon.reader.ui.components.rememberStatusReadout
import com.readon.reader.ui.theme.ReaderFont
import com.readon.reader.ui.theme.ReaderPalette
import com.readon.reader.ui.theme.ReaderSkin
import com.readon.reader.ui.theme.ReaderTheme
import com.readon.reader.ui.theme.toArgbInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * unrelated. The scrolling mode leans on the same property -- its blocks are
 * also addressed by offset -- so switching between the two modes keeps the
 * reader where they were instead of at the top of the chapter.
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

    /**
     * The system back key, unwound one layer at a time.
     *
     * The reader is a full-screen destination with several panels stacked over it,
     * and before this every one of them was a dead end: pressing back with the
     * table of contents open left the app entirely, losing the reader's place --
     * and pressing it with nothing open did the same, because the reader never
     * claimed the event at all. Back now closes the topmost thing and nothing else,
     * which is what "返回键处理（关闭当前窗口或界面）" has to mean for a stack this
     * deep.
     *
     * The order below is the panels' z-order in [ReaderBody] reversed -- the last
     * one drawn is the topmost, so it goes first -- which is what makes back
     * dismiss the panel the user can actually see.
     *
     * Registered here rather than inside each panel because a panel that is not
     * composed cannot own a handler, and because the final fallback -- leaving the
     * book -- belongs to the screen that owns `onClose`.
     */
    BackHandler {
        when {
            state.bookmarksVisible -> state.bookmarksVisible = false
            state.searchVisible -> state.searchVisible = false
            state.tocVisible -> state.tocVisible = false
            state.optionsVisible -> state.optionsVisible = false
            // Nothing is open, so back means "leave the book". Returning to the
            // list is the same thing the toolbar's arrow does, so the two cannot
            // disagree about where the user ends up.
            else -> onClose()
        }
    }

    val readerTheme = remember(revision) { prefs.readerTheme }
    val fontSize = remember(revision) { prefs.fontSize }
    val lineSpacing = remember(revision) { prefs.lineSpacing }
    val font = remember(revision) { prefs.readerFont }
    val textColorOverride = remember(revision) { prefs.readerTextColor }
    val backgroundColorOverride = remember(revision) { prefs.readerBackgroundColor }
    val readingMode = remember(revision) { prefs.readingMode }
    val keepAwake = remember(revision) { prefs.keepScreenOn }
    val autoReadSeconds = remember(revision) { prefs.autoReadSeconds }

    LaunchedEffect(book.id, loadAttempt) {
        state.begin(book)
        // The loader reports its stage from the IO thread. A StateFlow is safe to
        // write from there, and this collector moves the value into Compose state
        // on the composition's own dispatcher.
        val stage = MutableStateFlow(state.stage)
        val stageCollector = launch { stage.collect { state.stage = it } }
        try {
            val loader = BookLoader(context, app.github, app.onlineFetcher)
            val loaded = withContext(Dispatchers.IO) {
                loader.load(book) { s -> stage.value = s }
            }
            state.succeed(loaded.content, book.chapterIndex, 0)
            // Automatic reading resumes across books when the reader has asked for
            // it before, but a book never starts turning its own pages the first
            // time -- the preference is off until the play button is pressed once.
            state.autoRead = prefs.autoReadEnabled
            state.partial = loaded.partial
            if (loaded.partial) {
                // A prefix is on screen and the rest is arriving behind it. The
                // reader is usable now, which is the whole point; when the full
                // text lands it is swapped in under the same reading position.
                state.finishing = true
                launch {
                    val full = runCatching { loaded.finish?.invoke() }.getOrNull()
                    if (full != null) state.extend(full) else state.finishing = false
                }
            }
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

    // The book's bookmarks, re-read whenever the store changes so a bookmark
    // added here and one removed here stay in step with the list being shown.
    val bookmarkRevision by app.bookmarks.revision.collectAsStateSafe()
    LaunchedEffect(book.id, bookmarkRevision) {
        state.bookmarks = app.bookmarks.load(book.id)
    }

    // Keep the screen awake while reading, if the user asked for it.
    DisposableEffect(keepAwake, book.id) {
        val window = (context as? android.app.Activity)?.window
        if (keepAwake) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val skin = remember(readerTheme, textColorOverride, backgroundColorOverride) {
        ReaderSkin.of(readerTheme, backgroundColorOverride, textColorOverride)
    }

    /**
     * Point the status- and navigation-bar icons at the page colour.
     *
     * The window is edge-to-edge and the reader draws its own background, but the
     * icon tint comes from the *app* theme (set by `enableEdgeToEdge`). The reader
     * theme is a separate axis, so 夜间 reading with a light app theme left dark
     * icons on a dark page -- and the custom page colour added in 1.0.4 makes that
     * easy to hit from either direction. The previous values are captured and put
     * back on dispose, so leaving the reader restores the app's own choice rather
     * than guessing it from the theme mode.
     */
    DisposableEffect(skin.dark, book.id) {
        val window = (context as? android.app.Activity)?.window
        val view = window?.decorView
        if (window == null || view == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            val previousStatus = controller.isAppearanceLightStatusBars
            val previousNav = controller.isAppearanceLightNavigationBars
            controller.isAppearanceLightStatusBars = !skin.dark
            controller.isAppearanceLightNavigationBars = !skin.dark
            onDispose {
                controller.isAppearanceLightStatusBars = previousStatus
                controller.isAppearanceLightNavigationBars = previousNav
            }
        }
    }

    /**
     * Take **both** system bars out of the way for as long as the book is open.
     *
     * The reader is edge-to-edge and draws its own background, so the whole window
     * is the page: a strip of system chrome at the top and another at the bottom is
     * screen the text cannot use and attention it does not need. Both are hidden
     * for the whole visit and **restored on dispose** -- leaving the reader with
     * them still gone would take the app's own navigation away from every other
     * screen.
     *
     * The behaviour is [WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE]
     * rather than a hard hide: a reader who needs the clock, the battery or the
     * navigation buttons can still get them back by swiping from that edge, and
     * they slide away again on their own. A permanent hide with no way out would
     * make the app's own back gesture unreachable for anyone who does not know the
     * edge-swipe, which is a worse trade than bars that reappear when asked for.
     *
     * Because the status bar is hidden, the body text does **not** reserve space
     * for it -- see `topInset` in [ReaderBody], which measures the blank space from
     * the display cutout and a line of text instead.
     *
     * Keyed on `book.id` and **not** on the reader theme, unlike the icon tints
     * above: the system can restore the bars on its own (returning from the
     * installer, a configuration change), so this has to be re-applied per book --
     * but a theme change happens *while* the reader is sitting there, and keying
     * on it would show the bars and hide them again, a visible flicker, every time
     * the reader switched between 纸白 and 夜间.
     */
    DisposableEffect(book.id) {
        val window = (context as? android.app.Activity)?.window
        val view = window?.decorView
        if (window == null || view == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            val previousBehavior = controller.systemBarsBehavior
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                controller.systemBarsBehavior = previousBehavior
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    val textStyle = remember(fontSize, lineSpacing, font, skin) {
        TextStyle(
            fontSize = fontSize.sp,
            lineHeight = (fontSize * lineSpacing).sp,
            fontFamily = font.family,
            color = skin.text,
            textAlign = TextAlign.Justify,
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(skin.background)
    ) {
        when (val status = state.status) {
            is ReaderState.Status.Idle, is ReaderState.Status.Loading ->
                LoadingView(skin, state.stage, onClose)

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
                skin = skin,
                textStyle = textStyle,
                font = font,
                fontSize = fontSize,
                lineSpacing = lineSpacing,
                readerTheme = readerTheme,
                textColorOverride = textColorOverride,
                backgroundColorOverride = backgroundColorOverride,
                readingMode = readingMode,
                autoReadSeconds = autoReadSeconds,
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
private fun LoadingView(skin: ReaderSkin, stage: String, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(strokeWidth = 3.dp, color = skin.secondaryText)
        Spacer(Modifier.height(14.dp))
        Text(stage, color = skin.secondaryText, fontSize = 14.sp)
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onClose) {
            Text("返回", color = skin.secondaryText)
        }
    }
}

@Composable
private fun ReaderBody(
    app: ReadonApp,
    book: Book,
    state: ReaderState,
    skin: ReaderSkin,
    textStyle: TextStyle,
    font: ReaderFont,
    fontSize: Float,
    lineSpacing: Float,
    readerTheme: ReaderTheme,
    textColorOverride: Int,
    backgroundColorOverride: Int,
    readingMode: ReadingMode,
    autoReadSeconds: Int,
    measurer: TextMeasurer,
    density: Density,
    onClose: () -> Unit,
) {
    val content = state.content ?: return

    val verticalPadding = 16.dp

    // The clock and the battery, drawn by the reader itself because it hides the
    // phone's status bar. Read once here so the top and bottom lines share one
    // reading rather than each registering its own receiver.
    val readout = rememberStatusReadout()

    /**
     * The blank space kept above the body text.
     *
     * The reader hides the phone's status bar for the whole visit (see the
     * `DisposableEffect` in [ReaderScreen]) and replaces it with its own line
     * ([ReaderStatusHeader]), so the clearance above the body is:
     *
     *   * **The display cutout**, because that is a hardware obstruction and not a
     *     bar that can be hidden. It is measured from the cutout rather than from
     *     `WindowInsets.statusBars` on purpose: Compose reports the status-bar
     *     inset as the *visible* inset, which is zero while the bar is hidden -- so
     *     sizing this from the status bar would silently collapse to the bare
     *     minimum on every device, which is exactly the bug this replaced.
     *   * **The reader's own status line**, which is where the chapter name lives
     *     now that there is no system bar to carry it.
     *
     * A small gap is added so the first line of text does not sit flush against the
     * status line, which at 30 sp would read as one run of text.
     */
    val topInset = with(density) {
        val cutout = WindowInsets.displayCutout.getTop(this).toDp()
        cutout + HUD_LINE_HEIGHT + HUD_TEXT_GAP
    }

    /**
     * The clearance kept below the body text, for the footer line.
     *
     * Same reasoning at the other end: the footer shows the position and the clock,
     * and the last line of a page must not be drawn underneath it. Measured from a
     * constant rather than from the navigation-bar inset, which is zero here because
     * that bar is hidden too.
     */
    val bottomInset = HUD_LINE_HEIGHT + HUD_TEXT_GAP + CHROME_EDGE_MIN

    /**
     * Move to a chapter.
     *
     * The two modes position themselves differently and the bookkeeping has to
     * respect that. Paged mode sets the page directly -- and for "previous
     * chapter" it uses the [SENTINEL_LAST_PAGE] marker, because the page count of
     * a chapter that has not been measured yet is not known. Scroll mode has no
     * pages, so it scrolls the column to the chapter's first character.
     *
     * Deliberately *not* both at once: setting `pageInChapter` and a pending jump
     * offset together meant the jump won the race and reset "previous chapter,
     * last page" to the first page of that chapter.
     */
    fun goToChapter(index: Int, lastPage: Boolean = false) {
        state.chapterIndex = index
        // An explicit navigation supersedes the position the book was opened at;
        // leaving it set would let the restore pull the reader back to it.
        state.restoreOffset = null
        when (readingMode) {
            ReadingMode.Paged -> {
                state.pageInChapter = if (lastPage) SENTINEL_LAST_PAGE else 0
                state.pendingJumpOffset = null
            }
            ReadingMode.Scroll -> {
                state.pageInChapter = 0
                state.pendingJumpOffset = content.chapters.getOrNull(index)?.start ?: 0
            }
        }
    }

    /** Move to an absolute character offset, switching chapter if needed. */
    fun jumpTo(absolute: Int) {
        state.restoreOffset = null
        val index = Chapters.indexAt(content.chapters, absolute)
        if (index >= 0 && index != state.chapterIndex) {
            state.chapterIndex = index
            state.pageInChapter = 0
        }
        state.pendingJumpOffset = absolute
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The text column is capped, and the margin is whatever that leaves over.
        //
        // A 1200 dp tablet with the phone's 22 dp margins is a ~1150 dp line, which
        // at 18 sp holds roughly 64 Chinese characters -- twice the upper end of
        // what is comfortable, and the eye loses the line on the way back down.
        //
        // Note the direction of the fix: it is the *column* that is bounded, not the
        // margin. Capping the margin instead leaves a 2560 dp window with a text
        // column over 2000 dp wide, so the cap has to apply to the text and the
        // margin has to be derived from what is left. See [Adapt.READER_TEXT_MAX_DP].
        //
        // Derived from `constraints` rather than from the window size published at
        // the root because the reader is the one screen that really does fill the
        // window, and the *paginator* and the *drawn column* must get the width from
        // the same number -- measuring a page against one width and drawing it at
        // another clips the last line mid-character.
        val windowWidthDp = with(density) { constraints.maxWidth.toDp().value.toInt() }
        val textWidthDp = Adapt.readerTextWidthDp(windowWidthDp)
        val horizontalPadding = with(density) {
            Adapt.readerHorizontalPaddingDp(windowWidthDp).dp
        }

        val pageWidthPx = with(density) { textWidthDp.dp.roundToPx() }
        val pageHeightPx = with(density) {
            // Both insets are part of the page's padding, so they have to come out
            // of the measured height as well: paginating against the full height
            // would produce pages whose last line is drawn under the footer --
            // measured as fitting, and then clipped.
            (constraints.maxHeight - (verticalPadding * 2 + topInset + bottomInset).toPx()).toInt()
        }

        // The paginator is built here rather than inside [PagedReader] because the
        // footer needs it too: "第 12 / 340 页" is the paginator's own count, and
        // threading it back out of the child would be worse than owning it here.
        // Null in scroll mode, where there are no pages to count.
        //
        // Keyed on `contentGeneration` rather than on `content`: `BookContent` is
        // a data class holding the whole book, and while comparing it is cheap
        // when the instance is unchanged, the counter says exactly what is meant
        // -- "the text was replaced".
        val paginator = if (readingMode == ReadingMode.Paged) {
            remember(
                state.contentGeneration,
                state.chapterIndex,
                pageWidthPx,
                pageHeightPx,
                textStyle,
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
        } else {
            null
        }

        when (readingMode) {
            ReadingMode.Paged -> PagedReader(
                app = app,
                book = book,
                content = content,
                state = state,
                textStyle = textStyle,
                paginator = paginator,
                horizontalPadding = horizontalPadding,
                verticalPadding = verticalPadding,
                topInset = topInset,
                bottomInset = bottomInset,
                autoReadSeconds = autoReadSeconds,
                goToChapter = ::goToChapter,
            )

            ReadingMode.Scroll -> ScrollReader(
                app = app,
                book = book,
                content = content,
                state = state,
                skin = skin,
                textStyle = textStyle,
                horizontalPadding = horizontalPadding,
                verticalPadding = verticalPadding,
                topInset = topInset,
                bottomInset = bottomInset,
                autoReadSeconds = autoReadSeconds,
            )
        }

        // --- the reader's own status lines ---------------------------------
        //
        // Drawn *under* the chrome (below this block) so a toolbar that appears over
        // them covers them rather than the other way round, and drawn whether or not
        // the chrome is showing: they replace the phone's status bar, which is hidden
        // for the whole visit, so they are the only clock and battery the reader has.
        //
        // Faded out while the toolbar is up: the toolbar names the same chapter in
        // larger type, and two copies of it one above the other reads as a bug.
        ReaderStatusHeader(
            chapterTitle = content.chapters.getOrNull(state.chapterIndex)?.title,
            bookTitle = book.title,
            skin = skin,
            visible = !state.chromeVisible,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        ReaderStatusFooter(
            fraction = state.readingFraction(),
            readout = readout,
            skin = skin,
            visible = !state.chromeVisible,
            bottomInset = bottomInset,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

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
                skin = skin,
                onClose = onClose,
                onSearch = {
                    state.searchVisible = !state.searchVisible
                    state.optionsVisible = false
                },
                onOptions = {
                    state.optionsVisible = !state.optionsVisible
                    state.searchVisible = false
                },
            )
        }

        AnimatedVisibility(
            visible = state.chromeVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderBottomBar(
                app = app,
                book = book,
                state = state,
                content = content,
                skin = skin,
                mode = readingMode,
                paginator = paginator,
                autoReadSeconds = autoReadSeconds,
                onToggleAutoRead = {
                    // The button writes the preference as well as the session
                    // state, so "I read with the pages turning themselves" carries
                    // to the next book instead of having to be re-armed every time.
                    val next = !state.autoRead
                    state.autoRead = next
                    app.prefs.autoReadEnabled = next
                    // Starting to read automatically means putting the phone down,
                    // so the bars get out of the way -- they are drawn *over* the
                    // text, and leaving them up would cover the first lines of
                    // every page the reader is not touching. Pausing does the
                    // opposite: it is a sign someone is holding the phone, so the
                    // controls come back within reach.
                    state.chromeVisible = !next
                },
                onPrevChapter = {
                    if (state.chapterIndex > 0) goToChapter(state.chapterIndex - 1)
                },
                onNextChapter = {
                    if (state.chapterIndex + 1 < content.chapters.size) {
                        goToChapter(state.chapterIndex + 1)
                    }
                },
                onOpenToc = {
                    state.tocVisible = true
                    state.optionsVisible = false
                },
                onOpenBookmarks = {
                    state.bookmarksVisible = true
                    state.optionsVisible = false
                },
            )
        }

        if (state.optionsVisible) {
            ReaderOptionsPanel(
                app = app,
                skin = skin,
                font = font,
                fontSize = fontSize,
                lineSpacing = lineSpacing,
                theme = readerTheme,
                textColorOverride = textColorOverride,
                backgroundColorOverride = backgroundColorOverride,
                mode = readingMode,
                onClose = { state.optionsVisible = false },
                onModeChange = { next ->
                    if (next != readingMode) {
                        // Carry the position across the mode switch. Both modes
                        // address the text by offset, so this is exact rather
                        // than approximate.
                        state.pendingJumpOffset = state.currentOffset
                    }
                },
            )
        }

        if (state.tocVisible) {
            TocPanel(
                chapters = content.chapters,
                current = state.chapterIndex,
                skin = skin,
                onPick = {
                    goToChapter(it)
                    state.tocVisible = false
                },
                onDismiss = { state.tocVisible = false },
            )
        }

        if (state.searchVisible) {
            SearchPanel(
                state = state,
                content = content,
                skin = skin,
                onJump = { absolute ->
                    jumpTo(absolute)
                    state.searchVisible = false
                },
                onDismiss = { state.searchVisible = false },
            )
        }

        if (state.bookmarksVisible) {
            BookmarkPanel(
                app = app,
                book = book,
                state = state,
                content = content,
                skin = skin,
                currentOffset = state.currentOffset,
                onJump = { absolute ->
                    jumpTo(absolute)
                    state.bookmarksVisible = false
                },
                onDismiss = { state.bookmarksVisible = false },
            )
        }
    }
}

/* ------------------------------------------------------------------------- */
/*  Paged mode                                                                */
/* ------------------------------------------------------------------------- */

@Composable
private fun PagedReader(
    app: ReadonApp,
    book: Book,
    content: BookContent,
    state: ReaderState,
    textStyle: TextStyle,
    paginator: Paginator?,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    verticalPadding: androidx.compose.ui.unit.Dp,
    topInset: androidx.compose.ui.unit.Dp,
    bottomInset: androidx.compose.ui.unit.Dp,
    autoReadSeconds: Int,
    goToChapter: (Int, Boolean) -> Unit,
) {

    // Restore the saved position once, on the first layout of each chapter.
    //
    // Reads `state.restoreOffset`, not `book.charOffset`: the latter is where the
    // book was *opened* and never changes, so consulting it on every chapter
    // layout sent the reader back to that old spot whenever they navigated into a
    // chapter they had previously been in. The offset is consumed on use, and
    // explicit navigation clears it (see `goToChapter`/`jumpTo`).
    //
    // Skipped for the "previous chapter, last page" sentinel: restoring over it
    // would turn "go to the previous chapter" into "go to its first page".
    var restoredFor by remember(paginator) { mutableStateOf<Int?>(null) }
    LaunchedEffect(paginator) {
        val p = paginator ?: return@LaunchedEffect
        if (restoredFor == state.chapterIndex) return@LaunchedEffect
        restoredFor = state.chapterIndex
        if (state.pageInChapter >= SENTINEL_LAST_PAGE) return@LaunchedEffect
        val saved = state.restoreOffset ?: return@LaunchedEffect
        state.restoreOffset = null
        val chapter = content.chapters.getOrNull(state.chapterIndex)
        val target = when {
            chapter != null && saved in chapter.start until chapter.end -> saved - chapter.start
            chapter == null -> saved
            // The saved offset is in another chapter, so this chapter starts at
            // its own beginning.
            else -> 0
        }
        if (target > 0) state.pageInChapter = p.pageOf(target)
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

    // Apply a deferred jump now that the target chapter is paginated. The offset
    // is absolute in the book; the paginator's own text is one chapter.
    LaunchedEffect(paginator, state.pendingJumpOffset) {
        val target = state.pendingJumpOffset ?: return@LaunchedEffect
        val p = paginator ?: return@LaunchedEffect
        val chapter = content.chapters.getOrNull(state.chapterIndex)
        val relative = if (chapter != null) target - chapter.start else target
        state.pageInChapter = p.pageOf(relative.coerceAtLeast(0))
        state.pendingJumpOffset = null
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

    /**
     * Turn one page, and report whether there was a page to turn to.
     *
     * The boolean is what automatic reading keys on: at the end of the book there
     * is nowhere to go, and auto-read stops there rather than wrapping to page
     * one and quietly moving the reader's saved position with it.
     */
    fun turn(delta: Int): Boolean {
        val p = paginator ?: return false
        val next = state.pageInChapter + delta
        if (next < 0) {
            if (state.chapterIndex > 0) {
                goToChapter(state.chapterIndex - 1, true)
                return true
            }
            return false
        }
        // Producing the next page is how "is there one?" is answered: pages
        // are computed on demand, so there is no up-front count to consult.
        p.ensurePage(next)
        if (next < p.pageCount) {
            state.pageInChapter = next
            return true
        }
        if (content.chapters.isNotEmpty() &&
            state.chapterIndex + 1 < content.chapters.size
        ) {
            goToChapter(state.chapterIndex + 1, false)
            return true
        }
        return false
    }

    // --- automatic reading ----------------------------------------------
    //
    // The timer is keyed on the page as well as the switch, so it restarts from
    // zero whenever the position changes -- by the timer itself, by a tap, or by
    // the reader jumping in from the outline. Without that, a page the reader
    // turned by hand could be replaced a moment later by a tick that was already
    // most of the way through its wait.
    //
    // `contentGeneration` is a key because of online reading: while a book is
    // still arriving, the last page of the prefix is not the last page of the
    // book, so the timer must be woken again when the rest lands rather than
    // concluding the novel has ended.
    LaunchedEffect(
        state.autoRead,
        autoReadSeconds,
        state.pageInChapter,
        state.chapterIndex,
        state.contentGeneration,
    ) {
        if (!state.autoRead) return@LaunchedEffect
        delay(AutoRead.delayMillis(autoReadSeconds))
        if (!state.autoRead) return@LaunchedEffect
        if (turn(1)) return@LaunchedEffect
        // Nowhere to go. If more of the book is still being fetched, this is not
        // the end of anything -- wait for it rather than stopping.
        if (!state.partial) {
            // The end of the book. Stop and say so, rather than turning back to
            // the beginning.
            state.autoRead = false
        }
    }

    // --- the page ------------------------------------------------------
    Box(
        Modifier
            .fillMaxSize()
            .padding(
                start = horizontalPadding,
                end = horizontalPadding,
                // The insets are added to the usual vertical padding: the text has
                // to clear the reader's own status line above and footer below,
                // both of which are drawn over the page.
                top = verticalPadding + topInset,
                bottom = verticalPadding + bottomInset,
            )
            .pointerInput(paginator, state.chapterIndex) {
                detectTapGestures { offset ->
                    val third = size.width / 3f
                    when {
                        offset.x < third -> turn(-1)
                        offset.x > third * 2 -> turn(1)
                        // A tap in the middle opens the toolbar. Any tap is also
                        // a sign that someone is holding the phone, so automatic
                        // reading pauses: carrying on from under their finger
                        // would move the page they are looking at.
                        else -> state.chromeVisible = !state.chromeVisible
                    }
                    if (state.autoRead) state.autoRead = false
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
}

/* ------------------------------------------------------------------------- */
/*  Scrolling mode                                                            */
/* ------------------------------------------------------------------------- */

/**
 * One continuous column, dragged up and down.
 *
 * The book is split into paragraph blocks by [TextFlow] and handed to a
 * `LazyColumn`, which is what makes a million-character novel scroll at all: only
 * the visible blocks are measured and drawn. The blocks are addressed by
 * character offset, so the reading position, the progress bar and a jump from
 * the outline or a bookmark all work exactly as they do in paged mode.
 */
@Composable
private fun ScrollReader(
    app: ReadonApp,
    book: Book,
    content: BookContent,
    state: ReaderState,
    skin: ReaderSkin,
    textStyle: TextStyle,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    verticalPadding: androidx.compose.ui.unit.Dp,
    topInset: androidx.compose.ui.unit.Dp,
    bottomInset: androidx.compose.ui.unit.Dp,
    autoReadSeconds: Int,
) {
    // The block split is a full pass over the book -- roughly 160 ms for 8M
    // characters on a desktop JVM, and several times that on a low-end phone for
    // a book at the 64 MB online cap. Doing it inline during composition would
    // freeze the reader for that whole time on the frame that opens a book, so it
    // runs on the default dispatcher and the column appears when it is ready.
    //
    // Deliberately *not* keyed on the text style: blocks are character ranges, so
    // a font or colour change does not invalidate them, and recomputing them per
    // style change would be another full pass over the book for nothing.
    val blocks by produceState(
        initialValue = null as List<TextFlow.Block>?,
        // Keyed on the generation counter rather than on `content`: `BookContent`
        // is a data class holding the whole book, so comparing it as a key would
        // mean comparing tens of millions of characters. The counter changes
        // exactly when the text does.
        state.contentGeneration,
    ) {
        value = withContext(Dispatchers.Default) { TextFlow.blocks(content.text) }
    }
    val listState = rememberLazyListState()

    /**
     * Whether the saved position has been applied.
     *
     * The progress writer below is keyed on this, and that is the point:
     * `snapshotFlow` emits the *current* index as its first value, which before
     * the restore is the top of the book. Persisting that would overwrite the
     * saved position with offset 0 the instant a book was opened, so the reader
     * would lose their place every single time. Relaunching the writer once
     * [restored] flips means its first emission is the post-restore index, which
     * is also what makes a *new* book (nothing to restore) record its position
     * without waiting for the user to scroll.
     */
    var restored by remember(book.id) { mutableStateOf(false) }

    // Restore the reading position, and apply any jump requested before the first
    // layout.
    //
    // The fallback chain matters. This composable -- and its `LazyListState` --
    // is discarded whenever the user switches to paged mode and back, so on
    // re-entry the list would start at the top of the book. `currentOffset` is
    // the position the reader was actually at, maintained by whichever mode was
    // active, so it is the correct thing to scroll back to. Order: an explicit
    // jump wins, then the position the book was opened at, then where we are now.
    //
    // Handling the jump here too (rather than only in the effect below) is what
    // keeps the progress writer from starting on the pre-scroll index and briefly
    // persisting "the top of the book" over the reader's real position.
    LaunchedEffect(blocks) {
        val ready = blocks ?: return@LaunchedEffect
        val target = state.pendingJumpOffset ?: state.restoreOffset ?: state.currentOffset
        state.restoreOffset = null
        state.pendingJumpOffset = null
        if (ready.isNotEmpty() && target > 0) {
            listState.scrollToItem(TextFlow.blockIndexOf(ready, target))
        }
        restored = true
    }

    // A jump that arrives after the initial layout: from the outline, a search
    // hit or a bookmark.
    LaunchedEffect(blocks, state.pendingJumpOffset, restored) {
        if (!restored) return@LaunchedEffect
        val ready = blocks ?: return@LaunchedEffect
        val target = state.pendingJumpOffset ?: return@LaunchedEffect
        if (ready.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(TextFlow.blockIndexOf(ready, target))
        state.pendingJumpOffset = null
    }

    // The scroll position is the reading position. `snapshotFlow` rather than
    // reading `firstVisibleItemIndex` in the composition: the offset only has to
    // be written when it changes, and writing it during composition would make
    // every scroll frame a recomposition of the whole reader.
    LaunchedEffect(blocks, listState, restored) {
        val ready = blocks ?: return@LaunchedEffect
        if (!restored) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                val block = ready.getOrNull(index) ?: return@collect
                val absolute = block.start
                state.currentOffset = absolute
                val chapter = Chapters.indexAt(content.chapters, absolute)
                if (chapter >= 0 && chapter != state.chapterIndex) state.chapterIndex = chapter
                app.store.updateProgress(
                    id = book.id,
                    progress = state.progress(absolute),
                    charOffset = absolute,
                    chapterIndex = chapter,
                    chapterCount = content.chapters.size,
                )
            }
    }

    /**
     * Automatic reading in scrolling mode: a slow, continuous slide.
     *
     * "One page" here is one screenful, which is what the mode's unit of reading
     * is -- there are no pages to turn. The loop rather than a keyed
     * `LaunchedEffect` per position: an animated scroll changes the first visible
     * index as it passes block boundaries, and keying on that would cancel the
     * animation half-way through and start the wait over.
     *
     * ## Why the movement takes the whole interval
     *
     * The first version waited out the interval and *then* scrolled a screenful in
     * 700 ms. That is not automatic scrolling, it is an automatic page turn with a
     * transition: the screen sits perfectly still for 14 of every 15 seconds and
     * then lurches a full screenful in under one. Reported as "自动阅读滑动模式
     * 改为缓慢滑动" -- and the complaint is fair, because the setting says 15 秒
     * while the actual movement takes seven tenths of a second.
     *
     * Now the scroll *is* the interval. [AutoRead.scrollDurationMillis] is the same
     * 15 seconds, so the average speed is unchanged (still one screenful per
     * interval, which is what the interval means) but the text crawls the whole
     * time instead of jumping. [LinearEasing] rather than the default curve is
     * load-bearing: an ease-in-out on a 15-second animation would start almost
     * imperceptibly slowly and finish with a visible rush, which is the lurch this
     * is meant to remove.
     */
    LaunchedEffect(state.autoRead, autoReadSeconds, blocks, state.contentGeneration) {
        val ready = blocks ?: return@LaunchedEffect
        if (ready.isEmpty()) return@LaunchedEffect
        while (state.autoRead) {
            val viewport = listState.layoutInfo.viewportSize.height
            if (viewport <= 0) {
                // The column has not been measured yet. Waiting a frame is the
                // honest answer; scrolling by zero and looping would spin.
                delay(FRAME_MS)
                continue
            }
            val step = viewport * AutoRead.SCROLL_FRACTION
            val before = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            listState.animateScrollBy(
                value = step,
                animationSpec = tween(
                    durationMillis = AutoRead.scrollDurationMillis(autoReadSeconds).toInt(),
                    easing = LinearEasing,
                ),
            )
            if (!state.autoRead) break
            val after = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            if (after == before) {
                // The bottom of what is on the device. While the rest of the book
                // is still arriving that is not the end of anything, so the loop
                // waits rather than stopping; the effect is restarted by
                // `contentGeneration` when the full text lands.
                if (state.partial) {
                    delay(AutoRead.delayMillis(autoReadSeconds))
                    continue
                }
                // The end of the book. Stop rather than starting again from the
                // top with the saved position following it.
                state.autoRead = false
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures {
                    state.chromeVisible = !state.chromeVisible
                    // A tap means someone is holding the phone; carrying on
                    // scrolling from under their finger is not what they asked
                    // for.
                    if (state.autoRead) state.autoRead = false
                }
            },
    ) {
        val ready = blocks
        if (ready == null) {
            // Still splitting. The chrome is drawn over this, so the reader sees
            // the toolbar and the footer rather than a blank screen.
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(strokeWidth = 3.dp, color = skin.secondaryText)
            }
            return@Box
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // The chrome is drawn *over* this column, so the first and last
            // paragraphs have to be pushed clear of it or they are unreadable
            // behind a translucent bar. These are the bars' own heights, and they
            // are duplicated here rather than measured because the alternative --
            // plumbing the chrome's measured height back into the list -- is a
            // composition-time loop for a constant.
            //
            // Top: the reader's own status line (`topInset`) and the top bar's own
            // height (~60 dp of content) on top of that. Bottom: whichever covers
            // more of the column, the footer status line or the chrome's progress
            // bar, position row and action row (~92 dp) -- `maxOf`, not a sum, so
            // the last paragraph is not left stranded well above the bottom edge
            // when only the footer is showing.
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = verticalPadding + topInset + 60.dp,
                bottom = verticalPadding + maxOf(bottomInset, 92.dp),
            ),
        ) {
            itemsIndexed(ready) { _, block ->
                // The text between blocks is whitespace only, so drawing the
                // blocks back to back with a paragraph gap reproduces the
                // book's own spacing without a run of empty lines.
                Text(
                    text = content.text.substring(block.start, block.end),
                    style = textStyle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                )
            }
        }
    }
}

/* ------------------------------------------------------------------------- */
/*  Chrome                                                                    */
/* ------------------------------------------------------------------------- */

/** Signals "the last page of this chapter"; resolved once the count is known. */
private const val SENTINEL_LAST_PAGE = Int.MAX_VALUE / 2

/**
 * The height of one line of the reader's own status text.
 *
 * Fixed rather than derived from the reading font size: these two lines are chrome, not
 * body text, and they must not grow when the reader turns the page text up to 30 sp --
 * a 30 sp chapter heading across the top of the page would cost a third of a screen.
 * The body's insets are computed from this, so the two always agree.
 */
private val HUD_LINE_HEIGHT = 18.dp

/**
 * The line height of the status text itself.
 *
 * `sp`, not `dp`: this goes to `Text`'s `lineHeight`, which is a text unit and scales
 * with the user's font-size setting, whereas [HUD_LINE_HEIGHT] is a layout figure used
 * for the insets. The two are the same number, which is what keeps the reserved band
 * and the text drawn inside it in agreement.
 */
private val HUD_TEXT_LINE = 18.sp

/**
 * The gap between a status line and the body text.
 *
 * Small, because the status line is already a margin: the body text used to clear a
 * blank line of its own, and that blank line is now the line carrying the chapter name.
 */
private val HUD_TEXT_GAP = 8.dp

/**
 * The smallest margin the reader's own chrome keeps from the top and bottom edges.
 *
 * The reader hides both system bars, so `statusBarsPadding()` and
 * `navigationBarsPadding()` both report zero here and the toolbar would be drawn
 * flush against the edge of the screen -- which looks like a clipped layout rather
 * than a deliberate one, and at the bottom leaves the buttons inside the system's
 * gesture area where a swipe competes with them. The cutout is padded for
 * separately; this is the margin on a device that has neither a bar nor a notch.
 */
private val CHROME_EDGE_MIN = 10.dp

/**
 * Clearance for the reader's own chrome at the top of the screen.
 *
 * `displayCutoutPadding()` rather than `statusBarsPadding()`: the status bar is
 * hidden for the whole visit, so its inset is zero, but a notch is *hardware* and
 * does not care that the bar is hidden. Padding the cutout explicitly is what
 * keeps the toolbar's buttons out from under the camera.
 */
@Composable
private fun Modifier.readerTopChrome(): Modifier =
    this.displayCutoutPadding().padding(top = CHROME_EDGE_MIN)

/**
 * Clearance for the reader's own chrome at the bottom of the screen.
 *
 * Same reasoning as [readerTopChrome], for the hidden navigation bar.
 */
private fun Modifier.readerBottomChrome(): Modifier =
    this.padding(bottom = CHROME_EDGE_MIN)

/**
 * The line where the phone's status bar used to be: which chapter is being read.
 *
 * ## Why this exists
 *
 * 1.0.7 hid both system bars so the whole window could be the page, and that is right
 * -- but it also took the clock, the battery and the app's own sense of place with it.
 * The chapter name is the one piece of that the reader actually needs: it is how you
 * know where you are in a novel, and the reader's toolbar -- which does show it -- is
 * hidden while you read.
 *
 * So it is drawn here, in the reader's own colours and font, above the body text. The
 * body's top inset is measured from [HUD_LINE_HEIGHT], so the first line of prose can
 * never end up underneath it.
 *
 * ## Why it disappears when the toolbar appears
 *
 * The toolbar shows the same chapter name, in larger type, a few dp below this line.
 * Two copies of one string stacked on top of each other reads as a rendering bug rather
 * than as chrome, so this fades out while the toolbar is up and back in when it goes.
 *
 * The chapter name is preferred over the book's title because it is the thing that
 * changes as you read; a book with no detected outline falls back to the title rather
 * than showing an empty strip.
 */
@Composable
private fun ReaderStatusHeader(
    chapterTitle: String?,
    bookTitle: String,
    skin: ReaderSkin,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .readerTopChrome()
                .padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = chapterTitle?.takeIf { it.isNotBlank() } ?: bookTitle,
                color = skin.secondaryText,
                fontSize = 12.sp,
                lineHeight = HUD_TEXT_LINE,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The line where the phone's navigation bar used to be: position on the left, the clock
 * and the battery on the right.
 *
 * The two halves answer the two questions a reader glances down for. On the left, how
 * far through the book they are -- the same fraction the progress bar draws, so the two
 * cannot disagree. On the right, what time it is and whether the phone is about to die,
 * which is exactly what the hidden status bar used to say and the reason it was missed.
 *
 * The battery is tinted only when it is low *and* not charging ([ReaderHud.isLowBattery]);
 * a phone on a charger at 5% is not a problem worth colouring red.
 *
 * The footer sits above the bottom edge by `bottomInset`, which is the same figure the
 * body reserves, so the last line of a page never ends up behind it.
 */
@Composable
private fun ReaderStatusFooter(
    fraction: Float,
    readout: com.readon.reader.ui.components.StatusReadout,
    skin: ReaderSkin,
    visible: Boolean,
    bottomInset: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val lowBattery = ReaderHud.isLowBattery(readout.batteryLevel, readout.charging)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = bottomInset)
                .padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = ReaderHud.progressText(fraction),
                color = skin.secondaryText,
                fontSize = 11.sp,
                lineHeight = HUD_TEXT_LINE,
            )
            Spacer(Modifier.weight(1f))
            readout.clock?.let { clock ->
                Text(
                    text = clock,
                    color = skin.secondaryText,
                    fontSize = 11.sp,
                    lineHeight = HUD_TEXT_LINE,
                )
                Spacer(Modifier.width(10.dp))
            }
            Icon(
                imageVector = batteryIcon(readout.batteryLevel, readout.charging),
                contentDescription = null,
                tint = if (lowBattery) MaterialTheme.colorScheme.error else skin.secondaryText,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = ReaderHud.batteryText(readout.batteryLevel),
                color = if (lowBattery) MaterialTheme.colorScheme.error else skin.secondaryText,
                fontSize = 11.sp,
                lineHeight = HUD_TEXT_LINE,
            )
        }
    }
}

/**
 * The battery glyph for a level.
 *
 * Deliberately the platform's own battery icons rather than a hand-drawn vector: they are
 * the shape the user already recognises from the status bar this line replaces, and the
 * charging variant is the one thing a level alone cannot say.
 */
private fun batteryIcon(
    level: Int,
    charging: Boolean,
): androidx.compose.ui.graphics.vector.ImageVector = when {
    charging -> Icons.Filled.BatteryChargingFull
    level >= 95 -> Icons.Filled.BatteryFull
    level >= 70 -> Icons.Filled.Battery6Bar
    level >= 50 -> Icons.Filled.Battery5Bar
    level >= 35 -> Icons.Filled.Battery4Bar
    level >= 20 -> Icons.Filled.Battery3Bar
    level >= 10 -> Icons.Filled.Battery2Bar
    else -> Icons.Filled.Battery1Bar
}

/**
 * One frame, for the wait when the column has not been measured yet.
 *
 * Long enough that the loop is not a spin, short enough that the first automatic
 * step is not visibly late.
 */
private const val FRAME_MS = 16L

/**
 * The reader's top bar.
 *
 * Only the things that belong to the *whole book* live here: leaving it, and the
 * two panels that act on the text at large (search and reading settings). The
 * per-position controls -- bookmarks and the table of contents -- moved to the
 * bottom bar, next to the page/chapter indicator that says where in the book they
 * would take effect, and next to the progress bar they relate to.
 *
 * That move is also what lets the chapter name be readable. The bar used to carry
 * four icons beside the title, and on a phone that left roughly half the width for
 * the text -- enough for a chapter name to be ellipsised to "第一章…", which is
 * worse than useless when every chapter starts with the same two characters. With
 * two icons left, the title has room, so it is set larger and allowed to wrap to a
 * second line before it is truncated.
 */
@Composable
private fun ReaderTopBar(
    title: String,
    chapterTitle: String?,
    skin: ReaderSkin,
    onClose: () -> Unit,
    onSearch: () -> Unit,
    onOptions: () -> Unit,
) {
    Surface(color = skin.background, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                // Not `statusBarsPadding()`: the reader hides the status bar, so
                // that inset is zero and this bar would be drawn flush against the
                // top edge and under any notch. See `readerTopChrome`.
                .readerTopChrome()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = skin.text)
            }
            Column(Modifier.weight(1f)) {
                // The chapter name is the primary line when there is one: it is
                // what the reader uses to know where they are, and it changes as
                // they read, whereas the book's own title never does.
                Text(
                    text = chapterTitle ?: title,
                    color = skin.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    // Two lines rather than one: chapter headings in this library
                    // are long ("第十二章 在漫长的雨季里我们失去的一切"), and a
                    // single ellipsised line is indistinguishable from the next
                    // chapter's. The bar is not fixed-height, so the second line
                    // costs only the space it needs.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (chapterTitle != null) {
                    Text(
                        text = title,
                        color = skin.secondaryText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onSearch) {
                Icon(Icons.Filled.Search, contentDescription = "搜索", tint = skin.text)
            }
            IconButton(onClick = onOptions) {
                Icon(Icons.Filled.Tune, contentDescription = "阅读设置", tint = skin.text)
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(
    app: ReadonApp,
    book: Book,
    state: ReaderState,
    content: BookContent,
    skin: ReaderSkin,
    mode: ReadingMode,
    paginator: Paginator?,
    autoReadSeconds: Int,
    onToggleAutoRead: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenBookmarks: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val hasChapters = state.chapters.isNotEmpty()
    val fraction = state.readingFraction()

    // Whether the exact spot on screen is already bookmarked, so the button can
    // show the state it would toggle rather than always looking "add".
    val atCurrent = Bookmarks.at(state.bookmarks, state.currentOffset)

    Surface(color = skin.background, modifier = Modifier.fillMaxWidth()) {
        // `readerBottomChrome` rather than `navigationBarsPadding()`: the reader
        // hides the navigation bar, so that inset is zero and the buttons would sit
        // inside the system's gesture area. See the helper.
        Column(Modifier.readerBottomChrome()) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = skin.accent,
                trackColor = skin.secondaryText.copy(alpha = 0.2f),
            )

            /**
             * Where the reader is: "第 12 / 340 章" or "第 3 / 18 页".
             *
             * The chapter name is shown alongside the count, because the count
             * alone does not answer "where am I" -- and with the chapter title now
             * removed from this row's competition for space, it fits.
             */
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        hasChapters -> "第 ${state.chapterIndex + 1} / ${state.chapters.size} 章"
                        // Scrolling has no pages to count, so the position is the
                        // percentage alone; paged mode still shows the page, with
                        // a "+" marking a count that is still growing so the user
                        // is not misled into thinking it is the last page.
                        mode == ReadingMode.Paged && paginator != null ->
                            "第 ${state.pageInChapter + 1} / ${paginator.pageCount}" +
                                if (!paginator.isComplete) "+" else ""
                        else -> "${(fraction * 100).toInt()}%"
                    },
                    color = skin.secondaryText,
                    fontSize = 11.sp,
                )
                val chapterTitle = content.chapters.getOrNull(state.chapterIndex)?.title
                if (hasChapters && !chapterTitle.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = chapterTitle,
                        color = skin.secondaryText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Text(
                    text = "${(fraction * 100).toInt()}%",
                    color = skin.secondaryText,
                    fontSize = 11.sp,
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasChapters) {
                    IconButton(onClick = onPrevChapter, enabled = state.chapterIndex > 0) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "上一章",
                            tint = if (state.chapterIndex > 0) skin.text
                            else skin.secondaryText.copy(alpha = 0.4f),
                        )
                    }
                }

                // The table of contents. It moved down from the top bar because
                // this is where the chapter the reader is in is already named, so
                // the list it opens is visibly about *this* position.
                IconButton(onClick = onOpenToc) {
                    Icon(
                        Icons.AutoMirrored.Filled.List,
                        contentDescription = "目录",
                        tint = skin.text,
                    )
                }

                // Quick bookmark at the current position. Tapping an existing one
                // removes it, which is the same button doing the obvious thing.
                IconButton(onClick = {
                    scope.launch {
                        if (atCurrent != null) {
                            app.bookmarks.remove(book.id, atCurrent.id)
                        } else {
                            app.bookmarks.add(
                                bookmarkAt(book, state, content, label = ""),
                                book.id,
                            )
                        }
                    }
                }) {
                    Icon(
                        imageVector = if (atCurrent != null) Icons.Filled.Bookmark
                        else Icons.Filled.BookmarkBorder,
                        contentDescription = if (atCurrent != null) "删除书签" else "添加书签",
                        tint = if (atCurrent != null) skin.accent else skin.text,
                    )
                }
                IconButton(onClick = onOpenBookmarks) {
                    Icon(
                        Icons.Filled.Bookmarks,
                        contentDescription = "书签列表",
                        tint = skin.text,
                    )
                }

                Spacer(Modifier.weight(1f))

                // Automatic reading. In the action row rather than the settings
                // panel because it is a *mode you read in*, not a preference: it
                // is switched on when the reader's hands are busy, and off the
                // moment they pick the phone up.
                IconButton(onClick = onToggleAutoRead) {
                    Icon(
                        imageVector = if (state.autoRead) Icons.Filled.Pause
                        else Icons.Filled.PlayArrow,
                        contentDescription = if (state.autoRead) "暂停自动阅读" else "自动阅读",
                        tint = if (state.autoRead) skin.accent else skin.text,
                    )
                }

                if (hasChapters) {
                    IconButton(
                        onClick = onNextChapter,
                        enabled = state.chapterIndex + 1 < state.chapters.size,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "下一章",
                            tint = if (state.chapterIndex + 1 < state.chapters.size) skin.text
                            else skin.secondaryText.copy(alpha = 0.4f),
                        )
                    }
                }
            }

            /**
             * What the reader is waiting for, if anything.
             *
             * Two different waits, and saying which one it is matters: online
             * reading opens on a prefix and fetches the rest behind it, so the
             * honest message is that the book is readable *now* and still
             * arriving. An automatic page turn is a deliberate pause, so it is
             * shown as a countdown rather than as a spinner.
             */
            if (state.finishing || state.autoRead) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        state.autoRead -> Text(
                            "自动阅读中 · ${AutoRead.label(autoReadSeconds)}",
                            color = skin.accent,
                            fontSize = 10.sp,
                        )

                        else -> Text(
                            "正在后台下载剩余内容，可以先读这一部分",
                            color = skin.secondaryText,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}

/** Build a bookmark for wherever the reader currently is. */
private fun bookmarkAt(
    book: Book,
    state: ReaderState,
    content: BookContent,
    label: String,
): Bookmark {
    val offset = state.currentOffset.coerceIn(0, content.text.length)
    val chapter = content.chapters.getOrNull(state.chapterIndex)
    return Bookmark(
        id = Bookmarks.idOf(book.id, offset),
        charOffset = offset,
        chapterIndex = state.chapterIndex,
        chapterTitle = chapter?.title,
        label = label,
        createdAt = System.currentTimeMillis(),
        progress = state.progress(offset),
    )
}

/* ------------------------------------------------------------------------- */
/*  Panels                                                                    */
/* ------------------------------------------------------------------------- */

@Composable
private fun TocPanel(
    chapters: List<Chapters.Chapter>,
    current: Int,
    skin: ReaderSkin,
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
            color = skin.background,
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
                    .readerTopChrome()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "目录",
                        color = skin.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("${chapters.size} 章", color = skin.secondaryText, fontSize = 12.sp)
                }

                if (chapters.isEmpty()) {
                    Text(
                        "这本书没有可识别的章节结构。",
                        color = skin.secondaryText,
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
                                    color = if (selected) skin.accent else skin.text,
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
                                        tint = skin.accent,
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
    skin: ReaderSkin,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = skin.background,
        tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .readerTopChrome(),
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
                            color = skin.secondaryText,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {
                            state.searchIndex =
                                (state.searchIndex - 1 + state.searchHits.size) % state.searchHits.size
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "上一个",
                                tint = skin.text,
                            )
                        }
                        Text("${state.searchIndex + 1}", color = skin.text, fontSize = 13.sp)
                        IconButton(onClick = {
                            state.searchIndex = (state.searchIndex + 1) % state.searchHits.size
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "下一个",
                                tint = skin.text,
                            )
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
                            color = skin.secondaryText,
                            fontSize = 12.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                state.searchQuery.isNotEmpty() -> {
                    Spacer(Modifier.height(8.dp))
                    Text("没有找到", color = skin.secondaryText, fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * The bookmark list.
 *
 * Rows are ordered newest-first, because the bookmark just added is the one the
 * user is looking for. Each row offers jump, rename and delete; the rename is a
 * dialog rather than inline editing so a mistap cannot overwrite a name.
 */
@Composable
private fun BookmarkPanel(
    app: ReadonApp,
    book: Book,
    state: ReaderState,
    content: BookContent,
    skin: ReaderSkin,
    currentOffset: Int,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<Bookmark?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(onClick = onDismiss)
    ) {
        Surface(
            color = skin.background,
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.72f)
                .align(Alignment.BottomCenter)
                .clickable(enabled = true, onClick = {}),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .readerBottomChrome()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "书签",
                        color = skin.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${state.bookmarks.size} 个",
                        color = skin.secondaryText,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        scope.launch {
                            app.bookmarks.add(
                                bookmarkAt(book, state, content, label = ""),
                                book.id,
                            )
                        }
                    }) {
                        Icon(
                            Icons.Filled.BookmarkBorder,
                            contentDescription = null,
                            tint = skin.accent,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("加书签", color = skin.accent)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭", tint = skin.text)
                    }
                }

                HorizontalDivider(color = skin.secondaryText.copy(alpha = 0.2f))

                if (state.bookmarks.isEmpty()) {
                    Text(
                        "还没有书签。在阅读时点下方的书签图标，就会记住当前位置。",
                        color = skin.secondaryText,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
                        items(state.bookmarks, key = { it.id }) { mark ->
                            val here = mark.charOffset == currentOffset
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onJump(mark.charOffset) }
                                    .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = Bookmarks.displayLabel(mark, content.text),
                                        color = if (here) skin.accent else skin.text,
                                        fontSize = 14.sp,
                                        fontWeight = if (here) FontWeight.SemiBold
                                        else FontWeight.Normal,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = Bookmarks.locationLabel(mark),
                                        color = skin.secondaryText,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(onClick = { renaming = mark }) {
                                    Icon(
                                        Icons.Filled.Edit,
                                        contentDescription = "重命名",
                                        tint = skin.secondaryText,
                                        modifier = Modifier.size(17.dp),
                                    )
                                }
                                IconButton(onClick = {
                                    scope.launch { app.bookmarks.remove(book.id, mark.id) }
                                }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "删除",
                                        tint = skin.secondaryText,
                                        modifier = Modifier.size(17.dp),
                                    )
                                }
                            }
                            HorizontalDivider(
                                color = skin.secondaryText.copy(alpha = 0.12f),
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    renaming?.let { mark ->
        RenameBookmarkDialog(
            initial = mark.label.ifBlank {
                Bookmarks.snippet(content.text, mark.charOffset)
            },
            onDismiss = { renaming = null },
            onSave = { name ->
                scope.launch {
                    app.bookmarks.add(mark.copy(label = name), book.id)
                    renaming = null
                }
            },
        )
    }
}

@Composable
private fun RenameBookmarkDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("书签名称") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(Bookmarks.MAX_LABEL) },
                label = { Text("名称") },
                supportingText = { Text("留空则显示当前位置的正文。", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * The in-reader options panel.
 *
 * These controls duplicate Settings on purpose. The adjustments a reader makes
 * *while* reading -- "this page is too bright", "this typeface is hard to scan",
 * "I would rather scroll" -- are exactly the ones that must not cost them their
 * place in the book, and sending them to another tab does.
 */
@Composable
private fun ReaderOptionsPanel(
    app: ReadonApp,
    skin: ReaderSkin,
    font: ReaderFont,
    fontSize: Float,
    lineSpacing: Float,
    theme: ReaderTheme,
    textColorOverride: Int,
    backgroundColorOverride: Int,
    mode: ReadingMode,
    onClose: () -> Unit,
    onModeChange: (ReadingMode) -> Unit,
) {
    val prefs = app.prefs

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(onClick = onClose)
    ) {
        Surface(
            color = skin.background,
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .align(Alignment.BottomCenter)
                .clickable(enabled = true, onClick = {}),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .readerBottomChrome()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "阅读设置",
                        color = skin.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭", tint = skin.text)
                    }
                }

                // --- reading mode -------------------------------------------
                OptionLabel("阅读方式", skin)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadingMode.entries.forEach { option ->
                        OptionChip(
                            label = option.label,
                            selected = option == mode,
                            skin = skin,
                            onClick = {
                                prefs.readingMode = option
                                onModeChange(option)
                            },
                        )
                    }
                }

                // --- typeface ------------------------------------------------
                OptionLabel("字体", skin)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderFont.entries.forEach { option ->
                        OptionChip(
                            label = option.label,
                            selected = option == font,
                            skin = skin,
                            onClick = { prefs.readerFont = option },
                        )
                    }
                }

                // --- size and spacing ----------------------------------------
                OptionLabel("字号 ${fontSize.toInt()} sp", skin)
                Slider(
                    value = fontSize,
                    onValueChange = { prefs.fontSize = it },
                    valueRange = 12f..30f,
                    steps = 17,
                    modifier = Modifier.fillMaxWidth(),
                )

                OptionLabel(String.format("行距 %.1f 倍", lineSpacing), skin)
                Slider(
                    value = lineSpacing,
                    onValueChange = { prefs.lineSpacing = it },
                    valueRange = 1.2f..2.4f,
                    steps = 11,
                    modifier = Modifier.fillMaxWidth(),
                )

                // --- theme ---------------------------------------------------
                OptionLabel("阅读主题", skin)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderTheme.entries.forEach { option ->
                        ThemeDot(
                            background = option.background,
                            text = option.text,
                            selected = option == theme,
                            skin = skin,
                            onClick = {
                                // Choosing a preset is a request to *be* that
                                // preset, so it clears any hand-picked colour;
                                // otherwise the preset would appear to do nothing.
                                prefs.readerTheme = option
                                prefs.readerBackgroundColor = ReaderInk.NONE
                                prefs.readerTextColor = ReaderInk.NONE
                            },
                        )
                    }
                }

                // --- page colour ---------------------------------------------
                OptionLabel("页面颜色", skin)
                ColorRow(
                    colors = ReaderPalette.backgrounds,
                    selected = backgroundColorOverride,
                    skin = skin,
                    onPick = { prefs.readerBackgroundColor = it },
                    onClear = { prefs.readerBackgroundColor = ReaderInk.NONE },
                )

                // --- text colour ---------------------------------------------
                OptionLabel("文字颜色", skin)
                ColorRow(
                    colors = ReaderPalette.texts,
                    selected = textColorOverride,
                    skin = skin,
                    onPick = { prefs.readerTextColor = it },
                    onClear = { prefs.readerTextColor = ReaderInk.NONE },
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    "文字与页面颜色会覆盖主题；点「跟随主题」恢复。",
                    color = skin.secondaryText,
                    fontSize = 11.sp,
                )
                // Warn rather than override. The page colour is the user's
                // explicit choice, and silently changing their text colour back
                // would look like the setting had failed -- but a black-on-black
                // page is worth saying out loud.
                if (ReaderInk.hasPoorContrast(
                        skin.background.toArgbInt(),
                        skin.text.toArgbInt(),
                    )
                ) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "当前文字与页面颜色太接近，正文可能看不清。",
                        color = skin.accent,
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun OptionLabel(text: String, skin: ReaderSkin) {
    Spacer(Modifier.height(14.dp))
    Text(text, color = skin.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun OptionChip(
    label: String,
    selected: Boolean,
    skin: ReaderSkin,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) skin.accent.copy(alpha = 0.18f) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) skin.accent else skin.secondaryText.copy(alpha = 0.4f),
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            color = if (selected) skin.accent else skin.text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun ThemeDot(
    background: Color,
    text: Color,
    selected: Boolean,
    skin: ReaderSkin,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) skin.accent else skin.secondaryText.copy(alpha = 0.3f),
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("文", color = text, fontSize = 15.sp)
    }
}

/**
 * A wrapping row of colour swatches, with "follow the theme" first.
 *
 * `FlowRow` is not in the Compose version this project builds against, so the
 * swatches are chunked into rows of a fixed size -- the same approach the
 * settings screen's route picker takes, and predictable given the swatch size.
 */
@Composable
private fun ColorRow(
    colors: List<Int>,
    selected: Int,
    skin: ReaderSkin,
    onPick: (Int) -> Unit,
    onClear: () -> Unit,
) {
    val following = selected == ReaderInk.NONE
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OptionChip(
            label = "跟随主题",
            selected = following,
            skin = skin,
            onClick = onClear,
        )
        colors.chunked(6).forEach { chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chunk.forEach { argb ->
                    val color = Color(argb)
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(
                                width = if (!following && argb == selected) 2.dp else 1.dp,
                                color = if (!following && argb == selected) skin.accent
                                else skin.secondaryText.copy(alpha = 0.35f),
                                shape = CircleShape,
                            )
                            .clickable { onPick(argb) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!following && argb == selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                // Contrast against the swatch itself, not the
                                // page: a white check on a white swatch is
                                // invisible, and the swatch is what it sits on.
                                tint = Color(ReaderInk.readableOn(argb)),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
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
