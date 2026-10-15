package com.readon.reader.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Source
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readon.reader.BuildConfig
import com.readon.reader.ReadonApp
import com.readon.reader.data.Prefs
import com.readon.reader.data.LibrarySource
import com.readon.reader.data.Markdown
import com.readon.reader.data.ReaderInk
import com.readon.reader.data.ReadingMode
import com.readon.reader.data.formatSize
import com.readon.reader.net.ListSource
import com.readon.reader.net.Mirror
import com.readon.reader.reader.AutoRead
import com.readon.reader.ui.collectAsStateSafe
import com.readon.reader.ui.components.MarkdownView
import com.readon.reader.ui.theme.ReaderFont
import com.readon.reader.ui.theme.ReaderPalette
import com.readon.reader.ui.theme.ReaderTheme
import com.readon.reader.update.UpdateCenter
import com.readon.reader.update.UpdateResult
import kotlinx.coroutines.launch

/**
 * Settings.
 *
 * Grouped into reading preferences, library source, storage and updates. Every
 * control writes straight through to [Prefs], which bumps a revision that the
 * other screens observe -- so a font change made here is visible the moment the
 * reader is opened, with no explicit "apply".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    app: ReadonApp,
    contentPadding: PaddingValues,
    toast: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = app.prefs
    val revision by prefs.revision.collectAsStateSafe()
    val snapshot = remember(revision) { SettingsSnapshot.of(prefs) }

    var showLibraryEditor by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    // The full release notes, shown when the card's preview is not enough. Held
    // here rather than inside the card so the dialog is not clipped by the card's
    // own bounds.
    var showNotes by remember { mutableStateOf(false) }

    var cachedBytes by remember { mutableStateOf(0L) }
    var onlineCacheBytes by remember { mutableStateOf(0L) }
    var onlineCacheCount by remember { mutableStateOf(0) }
    var probeState by remember { mutableStateOf<ProbeState>(ProbeState.Idle) }

    // The update check lives at application scope: a check started here used to be
    // cancelled the moment the user switched tabs, which left the card stuck on
    // "正在检查… / 连接 GitHub 中" and restarted the check on every visit.
    val updateState = app.updateCenter.state

    LaunchedEffect(revision) {
        cachedBytes = app.store.usedBytes()
    }

    // The online-reading cache is accounted separately from the shelf, and shown
    // separately: the two are different promises. One is "books you kept", the
    // other is "books you happened to read over the network". `cacheTick` lets the
    // clear button refresh these numbers without waiting for a settings change.
    var cacheTick by remember { mutableStateOf(0) }
    LaunchedEffect(revision, cacheTick) {
        onlineCacheBytes = app.onlineCache.usedBytes()
        onlineCacheCount = app.onlineCache.count()
    }

    // Ask once per appearance; the centre itself decides whether a check is due.
    LaunchedEffect(Unit) {
        app.updateCenter.ensureChecked()
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding())
    ) {
        TopAppBar(
            title = {
                Text(
                    "设置",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                bottom = contentPadding.calculateBottomPadding() + 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // --- update banner ---------------------------------------------
            item {
                UpdateCard(
                    state = updateState,
                    onCheck = { app.updateCenter.check() },
                    onDownload = { available ->
                        app.updateCenter.download(available) { file ->
                            if (!app.updates.canInstall()) {
                                toast("请先允许 Readon 安装应用")
                                app.updates.requestInstallPermission()
                            } else {
                                app.updates.install(file)
                            }
                        }
                    },
                    onInstall = { _, file -> app.updates.install(file) },
                    onIgnore = { available -> app.updateCenter.ignore(available) },
                    onOpenReleases = { openUrl(context, ReadonLinks.RELEASES, toast) },
                    onShowNotes = { showNotes = true },
                    onOpenLink = { openUrl(context, it, toast) },
                )
            }

            // --- reading ----------------------------------------------------
            item {
                SectionCard(title = "阅读", icon = Icons.AutoMirrored.Filled.MenuBook) {
                    Text(
                        "阅读方式",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReadingMode.entries.forEach { option ->
                            ThemeModeChip(option.label, snapshot.readingMode == option) {
                                prefs.readingMode = option
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (snapshot.readingMode == ReadingMode.Scroll) {
                            "上下拖动连续阅读，适合长篇；位置与翻页模式互通。"
                        } else {
                            "左右点击翻页，每页有明确边界。"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    HorizontalDivider(Modifier.padding(vertical = 10.dp))

                    Text(
                        "字体",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRowOf(
                        options = ReaderFont.entries.map { it.label to it.name },
                        selected = snapshot.readerFont.name,
                        onSelect = { name ->
                            ReaderFont.fromName(name)?.let { prefs.readerFont = it }
                        },
                    )

                    HorizontalDivider(Modifier.padding(vertical = 10.dp))

                    SettingRow(
                        title = "阅读主题",
                        subtitle = snapshot.readerTheme.label,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReaderTheme.entries.forEach { theme ->
                                ThemeSwatch(
                                    theme = theme,
                                    selected = theme == snapshot.readerTheme,
                                    onClick = {
                                        // A preset means "be this preset", so it
                                        // clears any hand-picked colour; otherwise
                                        // the swatch would appear to do nothing.
                                        prefs.readerTheme = theme
                                        prefs.readerBackgroundColor = ReaderInk.NONE
                                        prefs.readerTextColor = ReaderInk.NONE
                                    },
                                )
                            }
                        }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 10.dp))

                    Text(
                        "页面颜色",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    ColorChoices(
                        colors = ReaderPalette.backgrounds,
                        selected = snapshot.readerBackgroundColor,
                        onPick = { prefs.readerBackgroundColor = it },
                        onClear = { prefs.readerBackgroundColor = ReaderInk.NONE },
                    )

                    Spacer(Modifier.height(14.dp))

                    Text(
                        "文字颜色",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    ColorChoices(
                        colors = ReaderPalette.texts,
                        selected = snapshot.readerTextColor,
                        onPick = { prefs.readerTextColor = it },
                        onClear = { prefs.readerTextColor = ReaderInk.NONE },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "自定义颜色会覆盖主题；「跟随主题」用回主题自身的配色。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    HorizontalDivider(Modifier.padding(vertical = 10.dp))

                    SettingRow(
                        title = "字号",
                        subtitle = "${snapshot.fontSize.toInt()} sp",
                        icon = Icons.Filled.TextFields,
                    ) {
                        Slider(
                            value = snapshot.fontSize,
                            onValueChange = { prefs.fontSize = it },
                            valueRange = 12f..30f,
                            steps = 17,
                            modifier = Modifier.width(160.dp),
                        )
                    }

                    HorizontalDivider(Modifier.padding(vertical = 10.dp))

                    SettingRow(
                        title = "行距",
                        subtitle = String.format("%.1f 倍", snapshot.lineSpacing),
                    ) {
                        Slider(
                            value = snapshot.lineSpacing,
                            onValueChange = { prefs.lineSpacing = it },
                            valueRange = 1.2f..2.4f,
                            steps = 11,
                            modifier = Modifier.width(160.dp),
                        )
                    }

                    HorizontalDivider(Modifier.padding(vertical = 10.dp))

                    SwitchRow(
                        title = "阅读时保持屏幕常亮",
                        checked = snapshot.keepScreenOn,
                        onCheckedChange = { prefs.keepScreenOn = it },
                    )

                    HorizontalDivider(Modifier.padding(vertical = 10.dp))

                    // --- automatic reading ---------------------------------
                    //
                    // Two controls, because they are two different things: the
                    // switch is "a newly opened book starts reading itself", and
                    // the slider is how long each page stays. The reader's own
                    // play button writes the same switch, so turning it on while
                    // reading is remembered here rather than having to be set
                    // twice.
                    SwitchRow(
                        title = "自动阅读",
                        subtitle = "打开书后自动翻页；阅读界面下方也有开关",
                        checked = snapshot.autoReadEnabled,
                        onCheckedChange = { prefs.autoReadEnabled = it },
                    )
                    Spacer(Modifier.height(6.dp))
                    SettingRow(
                        title = "翻页间隔",
                        subtitle = AutoRead.label(snapshot.autoReadSeconds),
                    ) {
                        Slider(
                            value = snapshot.autoReadSeconds.toFloat(),
                            onValueChange = { prefs.autoReadSeconds = it.toInt() },
                            valueRange = AutoRead.MIN_SECONDS.toFloat()..AutoRead.MAX_SECONDS.toFloat(),
                            steps = 10,
                            modifier = Modifier.width(160.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "到最后一页会自动停止，不会翻回开头；点一下正文也会暂停。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // --- appearance -------------------------------------------------
            item {
                SectionCard(title = "外观", icon = Icons.Filled.DarkMode) {
                    Text(
                        "应用主题",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeModeChip("跟随系统", snapshot.themeMode == Prefs.THEME_SYSTEM) {
                            prefs.themeMode = Prefs.THEME_SYSTEM
                        }
                        ThemeModeChip("浅色", snapshot.themeMode == Prefs.THEME_LIGHT) {
                            prefs.themeMode = Prefs.THEME_LIGHT
                        }
                        ThemeModeChip("深色", snapshot.themeMode == Prefs.THEME_DARK) {
                            prefs.themeMode = Prefs.THEME_DARK
                        }
                    }
                }
            }

            // --- library source --------------------------------------------
            item {
                SectionCard(title = "书库来源", icon = Icons.Filled.Source) {
                    InfoLine("仓库", "${snapshot.owner}/${snapshot.repo}")
                    InfoLine("分支", snapshot.branch)
                    InfoLine("状态", if (snapshot.token.isBlank()) "未设置访问令牌" else "已设置访问令牌")

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showLibraryEditor = true }) {
                            Text("修改书库")
                        }
                        OutlinedButton(onClick = {
                            // This button used to show a toast and do nothing at
                            // all, which is worse than no button: the library was
                            // never actually re-read.
                            app.library.ensureLoaded(force = true)
                            toast("正在刷新书库…")
                        }) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("刷新")
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        "书库从 GitHub 仓库读取，按文件夹分类。仓库里放入 txt / md / " +
                            "html / epub 文件即可自动出现在主页。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // --- network route ----------------------------------------------
            item {
                SectionCard(title = "网络线路", icon = Icons.Filled.CloudSync) {
                    Text(
                        "GitHub 在国内经常连不上。Readon 会同时尝试多条线路并自动记住" +
                            "可用的那条；如果自动选择不理想，也可以在这里手动指定。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(12.dp))
                    InfoLine(
                        "当前线路",
                        snapshot.preferredMirrorLabel,
                    )
                    InfoLine("书库接口", snapshot.preferredListLabel)

                    Spacer(Modifier.height(12.dp))
                    Text("手动指定线路", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(8.dp))

                    FlowRowOf(
                        options = buildList {
                            add("自动" to "")
                            Mirror.entries.forEach { add(it.label to it.id) }
                        },
                        selected = snapshot.pinnedMirror,
                        onSelect = { prefs.pinnedMirror = it },
                    )

                    if (snapshot.pinnedMirror == Mirror.Custom.id) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = snapshot.mirrorPrefix,
                            onValueChange = { prefs.mirrorPrefix = it },
                            label = { Text("加速地址前缀") },
                            placeholder = { Text("https://your-proxy.example/") },
                            supportingText = {
                                Text(
                                    "会拼成：前缀 + https://raw.githubusercontent.com/…",
                                    fontSize = 11.sp,
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    probeState = ProbeState.Running
                                    val results = runCatching {
                                        // Probe against a real file from the
                                        // library: routes differ in the file size
                                        // they will carry, so probing a path that
                                        // does not exist would report every route
                                        // as broken.
                                        val sample = app.github.listBooks(snapshot.source)
                                            .books
                                            .minByOrNull { it.size }
                                        if (sample == null) {
                                            ProbeState.Failed("书库里没有文件可用来检测")
                                        } else {
                                            ProbeState.Done(
                                                app.github.probeRoutes(
                                                    snapshot.source,
                                                    sample.path,
                                                )
                                            )
                                        }
                                    }
                                    probeState = results.fold(
                                        onSuccess = { it },
                                        onFailure = {
                                            ProbeState.Failed(it.message ?: "检测失败")
                                        },
                                    )
                                }
                            },
                            enabled = probeState !is ProbeState.Running,
                        ) {
                            if (probeState is ProbeState.Running) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text("检测线路")
                        }
                    }

                    when (val probe = probeState) {
                        is ProbeState.Done -> {
                            Spacer(Modifier.height(10.dp))
                            probe.results.forEach { (label, ok) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (ok) Icons.Filled.Check
                                        else Icons.Filled.Close,
                                        contentDescription = null,
                                        tint = if (ok) MaterialTheme.colorScheme.secondary
                                        else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        label,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        is ProbeState.Failed -> {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                probe.message,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        else -> Unit
                    }
                }
            }

            // --- storage ----------------------------------------------------
            item {
                SectionCard(title = "存储", icon = Icons.Filled.DeleteSweep) {
                    InfoLine("已下载书籍占用", formatSize(cachedBytes))
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { showClearConfirm = true }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("清空已下载的书")
                    }

                    HorizontalDivider(Modifier.padding(vertical = 14.dp))

                    // The online-reading cache, deliberately its own block: it
                    // holds books that were *read* over the network, not books the
                    // user chose to keep, and clearing it costs nothing but the
                    // bandwidth of the next read. Mixing the two into one number
                    // would make the shelf's "clear" button look like it was
                    // deleting more than the user asked for.
                    Text(
                        "在线阅读缓存",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "在线阅读过的书会留一份在设备上，再次打开就不用重新下载。" +
                            "它和书架互不影响，随时可以清掉。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    InfoLine(
                        "缓存占用",
                        if (onlineCacheCount == 0) "无" 
                        else "${formatSize(onlineCacheBytes)} · $onlineCacheCount 本",
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                app.onlineCache.clear()
                                onlineCacheBytes = 0
                                onlineCacheCount = 0
                                cacheTick++
                                toast("已清空在线阅读缓存")
                            }
                        },
                        enabled = onlineCacheCount > 0,
                    ) {
                        Icon(Icons.Filled.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("清理在线阅读缓存")
                    }
                }
            }

            // --- update settings -------------------------------------------
            item {
                SectionCard(title = "更新", icon = Icons.Filled.CloudSync) {
                    SwitchRow(
                        title = "启动时自动检查更新",
                        checked = snapshot.autoCheckUpdates,
                        onCheckedChange = { prefs.autoCheckUpdates = it },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    InfoLine("更新仓库", "${snapshot.updateOwner}/${snapshot.updateRepo}")
                    if (snapshot.lastUpdateCheck > 0) {
                        InfoLine("上次检查", formatTime(snapshot.lastUpdateCheck))
                    }
                }
            }

            // --- about ------------------------------------------------------
            item {
                SectionCard(title = "关于", icon = Icons.Filled.Info) {
                    InfoLine("应用名称", "Readon")
                    InfoLine("版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { showAbout = true }) {
                        Text("关于 Readon")
                    }
                }
            }

            // --- links ------------------------------------------------------
            item {
                SectionCard(title = "链接", icon = Icons.Filled.Link) {
                    Text(
                        "点一下直接在浏览器里打开。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    LinkRow(
                        label = "项目主页",
                        value = ReadonLinks.REPO,
                        onOpen = { openUrl(context, ReadonLinks.REPO_URL, toast) },
                    )
                    LinkRow(
                        label = "全部版本",
                        value = ReadonLinks.RELEASES,
                        onOpen = { openUrl(context, ReadonLinks.RELEASES, toast) },
                    )
                    LinkRow(
                        label = "问题反馈",
                        value = ReadonLinks.ISSUES,
                        onOpen = { openUrl(context, ReadonLinks.ISSUES, toast) },
                    )
                    LinkRow(
                        label = "默认书库",
                        value = ReadonLinks.BOOK_LIBRARY,
                        onOpen = { openUrl(context, ReadonLinks.BOOK_LIBRARY, toast) },
                    )
                }
            }
        }
    }

    // --- dialogs -----------------------------------------------------------

    // Back closes whatever dialog is open rather than leaving the app. The
    // dialogs are `AlertDialog`s, which handle the back key themselves while
    // focused, but this covers the case where one is open and focus has moved on
    // -- and it makes "back always closes the topmost thing" true everywhere
    // rather than almost everywhere.
    BackHandler(enabled = showLibraryEditor || showClearConfirm || showAbout || showNotes) {
        when {
            showNotes -> showNotes = false
            showAbout -> showAbout = false
            showClearConfirm -> showClearConfirm = false
            showLibraryEditor -> showLibraryEditor = false
        }
    }

    if (showLibraryEditor) {
        LibraryEditorDialog(
            initialOwner = snapshot.owner,
            initialRepo = snapshot.repo,
            initialBranch = snapshot.branch,
            initialToken = snapshot.token,
            onDismiss = { showLibraryEditor = false },
            onSave = { o, r, b, t ->
                prefs.owner = o
                prefs.repo = r
                prefs.branch = b
                prefs.token = t
                showLibraryEditor = false
                toast("书库已更新")
            },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空已下载的书？") },
            text = { Text("这会删除全部本地书籍和阅读进度，在线书库不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        app.store.clearAll()
                        app.bookmarks.clearAll()
                        cachedBytes = 0
                        showClearConfirm = false
                        toast("已清空")
                    }
                }) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("关于 Readon") },
            text = {
                Column {
                    Text(
                        "Readon 是一个简洁的阅读器：从 GitHub 书库在线阅读或下载书籍，" +
                            "在书架上管理，支持章节导航、全文搜索和多种阅读主题。",
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "书库格式：仓库中按文件夹分类，放入 txt / md / html / epub 文件。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("好") }
            },
        )
    }

    // The full release notes. A dialog rather than a longer card because the
    // notes for a release like 1.0.6 run to several thousand characters, and a
    // card that tall would bury the download button and the rest of Settings.
    val available = (updateState as? UpdateCenter.State.Available)?.info
    if (showNotes && available != null) {
        AlertDialog(
            onDismissRequest = { showNotes = false },
            title = { Text("更新内容 · ${available.versionName}") },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    MarkdownView(
                        markdown = available.notes,
                        textColor = MaterialTheme.colorScheme.onSurface,
                        secondaryTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        accentColor = MaterialTheme.colorScheme.primary,
                        linkColor = MaterialTheme.colorScheme.primary,
                        baseSize = 13.sp,
                        onLink = { openUrl(context, it, toast) },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showNotes = false }) { Text("好") }
            },
        )
    }
}

/**
 * How many blocks of the release notes the update card shows before deferring to
 * the dialog.
 *
 * Enough to say what kind of release this is -- a heading, a paragraph and the
 * first couple of bullets -- and few enough that the card does not push the
 * download button off the screen.
 */
private const val NOTES_PREVIEW_BLOCKS = 6

/** State of the "test the routes" action. */
private sealed interface ProbeState {
    data object Idle : ProbeState
    data object Running : ProbeState
    data class Done(val results: List<Pair<String, Boolean>>) : ProbeState
    data class Failed(val message: String) : ProbeState
}

/**
 * The project's own addresses, in one place.
 *
 * Hard-coded rather than derived from the configured library or update repository:
 * those are user-editable, and "问题反馈" pointing at whatever repository the user
 * happens to be reading books from would send bug reports nowhere useful. The
 * update repository is a *setting*; this is the app's identity.
 */
private object ReadonLinks {
    private const val OWNER = "nmaych"
    private const val REPO_NAME = "Readon"

    const val REPO_URL = "https://github.com/$OWNER/$REPO_NAME"
    const val RELEASES = "$REPO_URL/releases"
    const val ISSUES = "$REPO_URL/issues"
    const val BOOK_LIBRARY = "https://github.com/$OWNER/ReadonBooklibrary"

    /** What is shown as the link's text, without the scheme. */
    const val REPO = "github.com/$OWNER/$REPO_NAME"
}

/**
 * Open [url] in whatever the device uses for web pages.
 *
 * Every failure is reported rather than swallowed: on a device with no browser --
 * or with the one browser disabled -- `startActivity` throws, and a link that
 * silently does nothing reads as a broken app. The URL is offered as text so the
 * user can still get to it by hand.
 */
private fun openUrl(context: Context, url: String, toast: (String) -> Unit) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val opened = runCatching { context.startActivity(intent) }.isSuccess
    if (!opened) toast("没有可用的浏览器。地址：$url")
}

/**
 * A chip row that wraps onto multiple lines.
 *
 * `FlowRow` is not in the Compose version this project builds against, and the
 * route list is long enough that a single `Row` would push chips off-screen --
 * so the options are chunked into rows of a fixed size, which is predictable
 * with the labels in play here.
 */
@Composable
private fun FlowRowOf(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val perRow = 3
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(perRow).forEach { chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chunk.forEach { (label, id) ->
                    ThemeModeChip(label, selected == id) { onSelect(id) }
                }
            }
        }
    }
}

/** Update flow states the card renders, owned by the app-scoped update centre. */
/** A snapshot of the settings, read once per revision. */
private data class SettingsSnapshot(
    val owner: String,
    val repo: String,
    val branch: String,
    val token: String,
    val readerTheme: ReaderTheme,
    val readerFont: ReaderFont,
    val readerTextColor: Int,
    val readerBackgroundColor: Int,
    val readingMode: ReadingMode,
    val fontSize: Float,
    val lineSpacing: Float,
    val serif: Boolean,
    val keepScreenOn: Boolean,
    val autoReadEnabled: Boolean,
    val autoReadSeconds: Int,
    val themeMode: Int,
    val autoCheckUpdates: Boolean,
    val updateOwner: String,
    val updateRepo: String,
    val lastUpdateCheck: Long,
    val pinnedMirror: String,
    val mirrorPrefix: String,
    val preferredMirrorLabel: String,
    val preferredListLabel: String,
) {
    val source: LibrarySource get() = LibrarySource(owner, repo, branch)

    companion object {
        fun of(p: Prefs): SettingsSnapshot {
            // Show what actually worked last time, which is the only honest
            // answer to "which route am I using".
            val mirror = Mirror.byId(p.preferredMirror)
            val list = ListSource.byId(p.preferredListSource)
            val pinned = Mirror.byId(p.pinnedMirror)
            return SettingsSnapshot(
                owner = p.owner,
                repo = p.repo,
                branch = p.branch,
                token = p.token,
                readerTheme = p.readerTheme,
                readerFont = p.readerFont,
                readerTextColor = p.readerTextColor,
                readerBackgroundColor = p.readerBackgroundColor,
                readingMode = p.readingMode,
                fontSize = p.fontSize,
                lineSpacing = p.lineSpacing,
                serif = p.serif,
                keepScreenOn = p.keepScreenOn,
                autoReadEnabled = p.autoReadEnabled,
                autoReadSeconds = p.autoReadSeconds,
                themeMode = p.themeMode,
                autoCheckUpdates = p.autoCheckUpdates,
                updateOwner = p.updateOwner,
                updateRepo = p.updateRepo,
                lastUpdateCheck = p.lastUpdateCheck,
                pinnedMirror = p.pinnedMirror,
                mirrorPrefix = p.mirrorPrefix,
                preferredMirrorLabel = when {
                    pinned != null -> "${pinned.label}（已手动指定）"
                    mirror != null -> "${mirror.label}（自动）"
                    else -> "尚未确定，首次访问时自动选择"
                },
                preferredListLabel = list?.label ?: "尚未确定",
            )
        }
    }
}

@Composable
private fun UpdateCard(
    state: UpdateCenter.State,
    onCheck: () -> Unit,
    onDownload: (UpdateResult.Available) -> Unit,
    onInstall: (UpdateResult.Available, java.io.File) -> Unit,
    onIgnore: (UpdateResult.Available) -> Unit,
    onOpenReleases: () -> Unit,
    onShowNotes: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val (accent, title, body) = when (state) {
        is UpdateCenter.State.Idle -> Triple(
            MaterialTheme.colorScheme.primary,
            "检查更新",
            "Readon 会从 GitHub 获取新版本。",
        )
        is UpdateCenter.State.Checking -> Triple(
            MaterialTheme.colorScheme.primary, "正在检查…", "连接 GitHub 中",
        )
        is UpdateCenter.State.UpToDate -> Triple(
            MaterialTheme.colorScheme.secondary, "已是最新版本",
            "当前版本 ${BuildConfig.VERSION_NAME}",
        )
        is UpdateCenter.State.Failed -> Triple(
            MaterialTheme.colorScheme.error, "检查失败", state.message,
        )
        is UpdateCenter.State.NewerNotInstallable -> Triple(
            // Not the error colour: the check worked and found a release. The
            // user's next step is to go get it, not to retry.
            MaterialTheme.colorScheme.tertiary,
            "发现新版本，但无法在这里安装",
            state.message,
        )
        is UpdateCenter.State.Available -> Triple(
            MaterialTheme.colorScheme.secondary,
            "发现新版本 ${state.info.versionName}",
            // A sentence, not a fragment of markup: the card used to cut the
            // notes' first line at 90 characters, so what the user saw was
            // "### Added" or a bullet ending in "**". The notes are Markdown and
            // are rendered as such below; this line is the summary.
            Markdown.summary(state.info.notes, 100).ifBlank { "点击下载并安装。" },
        )
        is UpdateCenter.State.Downloading -> Triple(
            MaterialTheme.colorScheme.primary, "正在下载更新…", "",
        )
        is UpdateCenter.State.ReadyToInstall -> Triple(
            MaterialTheme.colorScheme.secondary, "下载完成",
            "点击安装 ${state.info.versionName}",
        )
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = accent.copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (state) {
                        is UpdateCenter.State.Available,
                        is UpdateCenter.State.ReadyToInstall -> Icons.Filled.Download
                        is UpdateCenter.State.UpToDate -> Icons.Filled.Check
                        is UpdateCenter.State.Failed -> Icons.Filled.Info
                        // A release was found, so it is "download", not "problem".
                        is UpdateCenter.State.NewerNotInstallable -> Icons.Filled.Download
                        else -> Icons.Filled.CloudSync
                    },
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (body.isNotBlank()) {
                        Text(
                            body,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            when (state) {
                is UpdateCenter.State.Downloading -> {
                    Spacer(Modifier.height(10.dp))
                    if (state.progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }

                is UpdateCenter.State.Available -> {
                    Spacer(Modifier.height(10.dp))
                    // The notes, as Markdown. Truncated to a few blocks so the
                    // card stays a card; the full text is one tap away.
                    MarkdownView(
                        markdown = state.info.notes,
                        textColor = MaterialTheme.colorScheme.onSurface,
                        secondaryTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        accentColor = accent,
                        linkColor = MaterialTheme.colorScheme.primary,
                        baseSize = 12.sp,
                        maxBlocks = NOTES_PREVIEW_BLOCKS,
                        onLink = onOpenLink,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onDownload(state.info) }) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("下载更新")
                        }
                        if (state.info.notes.isNotBlank()) {
                            OutlinedButton(onClick = onShowNotes) {
                                Text("更新内容")
                            }
                        }
                        OutlinedButton(onClick = { onIgnore(state.info) }) {
                            Text("忽略此版本")
                        }
                    }
                }

                is UpdateCenter.State.ReadyToInstall -> {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { onInstall(state.info, state.file) }) {
                        Text("立即安装")
                    }
                }

                is UpdateCenter.State.NewerNotInstallable -> {
                    // The one useful action here is to go and get it, so the card
                    // offers exactly that instead of a retry that would find the
                    // same release again.
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onOpenReleases() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("到 GitHub 下载")
                        }
                        OutlinedButton(
                            onClick = onCheck,
                            enabled = state !is UpdateCenter.State.Checking,
                        ) {
                            Text("重新检查")
                        }
                    }
                }

                else -> {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onCheck,
                        enabled = state !is UpdateCenter.State.Checking,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("检查更新")
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(
            value,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A row that opens a web page.
 *
 * A whole-row click target rather than a small "open" icon: the label, the address
 * and the icon are all part of the same action, and a tap that lands on the text
 * of a link and does nothing is the sort of thing that makes an app feel broken.
 * The address is tinted like a link so it is visibly the tappable thing.
 */
@Composable
private fun LinkRow(
    label: String,
    value: String,
    onOpen: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Link,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                value,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = "在浏览器中打开",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun ThemeSwatch(theme: ReaderTheme, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(theme.background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("文", color = theme.text, fontSize = 15.sp)
        if (selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(2.dp)
                        .size(13.dp),
                )
            }
        }
    }
}

@Composable
private fun ThemeModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp)
    ) {
        Text(label, color = fg, fontSize = 13.sp)
    }
}

/**
 * The reader's text or page colour swatches, with "follow the theme" first.
 *
 * The selected swatch carries a check tinted for *itself* rather than for the
 * page: a white check on a white swatch is invisible, and the swatch is what it
 * sits on.
 */
@Composable
private fun ColorChoices(
    colors: List<Int>,
    selected: Int,
    onPick: (Int) -> Unit,
    onClear: () -> Unit,
) {
    val following = selected == ReaderInk.NONE
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ThemeModeChip("跟随主题", following, onClear)
        colors.chunked(6).forEach { chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                chunk.forEach { argb ->
                    val picked = !following && argb == selected
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(argb))
                            .border(
                                width = if (picked) 2.dp else 1.dp,
                                color = if (picked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape,
                            )
                            .clickable { onPick(argb) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (picked) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = Color(ReaderInk.readableOn(argb)),
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryEditorDialog(
    initialOwner: String,
    initialRepo: String,
    initialBranch: String,
    initialToken: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit,
) {
    var owner by remember { mutableStateOf(initialOwner) }
    var repo by remember { mutableStateOf(initialRepo) }
    var branch by remember { mutableStateOf(initialBranch) }
    var token by remember { mutableStateOf(initialToken) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("书库设置") },
        text = {
            Column {
                OutlinedTextField(
                    value = owner,
                    onValueChange = { owner = it },
                    label = { Text("GitHub 用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = repo,
                    onValueChange = { repo = it },
                    label = { Text("仓库名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = branch,
                    onValueChange = { branch = it },
                    label = { Text("分支") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("访问令牌（可选）") },
                    supportingText = {
                        Text(
                            "公开仓库可以留空。填写后接口限额从 60 次/小时提升到 5000 次。",
                            fontSize = 11.sp,
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (owner.isNotBlank() && repo.isNotBlank() && branch.isNotBlank()) {
                        onSave(owner, repo, branch, token)
                    }
                },
                enabled = owner.isNotBlank() && repo.isNotBlank() && branch.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun formatTime(millis: Long): String {
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(millis))
}
