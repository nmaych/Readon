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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Source
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readon.reader.BuildConfig
import com.readon.reader.ReadonApp
import com.readon.reader.data.Prefs
import com.readon.reader.data.LibrarySource
import com.readon.reader.data.formatSize
import com.readon.reader.net.ListSource
import com.readon.reader.net.Mirror
import com.readon.reader.ui.collectAsStateSafe
import com.readon.reader.ui.theme.ReaderTheme
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
    val prefs = app.prefs
    val revision by prefs.revision.collectAsStateSafe()
    val snapshot = remember(revision) { SettingsSnapshot.of(prefs) }

    var showLibraryEditor by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    var cachedBytes by remember { mutableStateOf(0L) }
    var updateState by remember { mutableStateOf<UpdateUi>(UpdateUi.Idle) }
    var probeState by remember { mutableStateOf<ProbeState>(ProbeState.Idle) }

    LaunchedEffect(revision) {
        cachedBytes = app.store.usedBytes()
    }

    // Check for updates once when the screen first appears, if the user asked
    // for automatic checks. The timestamp guard keeps tab-switching from
    // hammering the GitHub API.
    LaunchedEffect(Unit) {
        if (prefs.autoCheckUpdates &&
            System.currentTimeMillis() - prefs.lastUpdateCheck > CHECK_INTERVAL_MS
        ) {
            updateState = UpdateUi.Checking
            val result = app.updates.check()
            prefs.lastUpdateCheck = System.currentTimeMillis()
            updateState = when (result) {
                is UpdateResult.Available ->
                    if (result.versionCode == prefs.ignoredVersionCode) UpdateUi.Idle
                    else UpdateUi.Available(result)
                is UpdateResult.UpToDate -> UpdateUi.UpToDate
                is UpdateResult.Failed -> UpdateUi.Failed(result.message)
            }
        }
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
                    onCheck = {
                        scope.launch {
                            updateState = UpdateUi.Checking
                            val result = app.updates.check()
                            prefs.lastUpdateCheck = System.currentTimeMillis()
                            updateState = when (result) {
                                is UpdateResult.Available -> UpdateUi.Available(result)
                                is UpdateResult.UpToDate -> UpdateUi.UpToDate
                                is UpdateResult.Failed -> UpdateUi.Failed(result.message)
                            }
                        }
                    },
                    onDownload = { available ->
                        scope.launch {
                            try {
                                val file = app.updates.download(available) { p ->
                                    updateState = UpdateUi.Downloading(p)
                                }
                                if (!app.updates.canInstall()) {
                                    toast("请先允许 Readon 安装应用")
                                    app.updates.requestInstallPermission()
                                    updateState = UpdateUi.ReadyToInstall(available, file)
                                } else {
                                    updateState = UpdateUi.ReadyToInstall(available, file)
                                    app.updates.install(file)
                                }
                            } catch (e: Exception) {
                                updateState = UpdateUi.Failed(e.message ?: "下载失败")
                            }
                        }
                    },
                    onInstall = { available, file -> app.updates.install(file) },
                    onIgnore = { available ->
                        prefs.ignoredVersionCode = available.versionCode
                        updateState = UpdateUi.Idle
                    },
                )
            }

            // --- reading ----------------------------------------------------
            item {
                SectionCard(title = "阅读", icon = Icons.Filled.MenuBook) {
                    SettingRow(
                        title = "阅读主题",
                        subtitle = snapshot.readerTheme.label,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReaderTheme.entries.forEach { theme ->
                                ThemeSwatch(
                                    theme = theme,
                                    selected = theme == snapshot.readerTheme,
                                    onClick = { prefs.readerTheme = theme },
                                )
                            }
                        }
                    }

                    Divider(Modifier.padding(vertical = 10.dp))

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

                    Divider(Modifier.padding(vertical = 10.dp))

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

                    Divider(Modifier.padding(vertical = 10.dp))

                    SwitchRow(
                        title = "衬线字体",
                        subtitle = "开启后使用宋体风格，适合长文阅读",
                        checked = snapshot.serif,
                        onCheckedChange = { prefs.serif = it },
                    )

                    SwitchRow(
                        title = "阅读时保持屏幕常亮",
                        checked = snapshot.keepScreenOn,
                        onCheckedChange = { prefs.keepScreenOn = it },
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
                            scope.launch {
                                toast("正在刷新书库索引…")
                            }
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
                    Divider(Modifier.padding(vertical = 10.dp))
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
        }
    }

    // --- dialogs -----------------------------------------------------------

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
}

private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

/** State of the "test the routes" action. */
private sealed interface ProbeState {
    data object Idle : ProbeState
    data object Running : ProbeState
    data class Done(val results: List<Pair<String, Boolean>>) : ProbeState
    data class Failed(val message: String) : ProbeState
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

/** Update flow states the card renders. */
private sealed interface UpdateUi {    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data object UpToDate : UpdateUi
    data class Failed(val message: String) : UpdateUi
    data class Available(val info: UpdateResult.Available) : UpdateUi
    data class Downloading(val progress: Float) : UpdateUi
    data class ReadyToInstall(val info: UpdateResult.Available, val file: java.io.File) : UpdateUi
}

/** A snapshot of the settings, read once per revision. */
private data class SettingsSnapshot(
    val owner: String,
    val repo: String,
    val branch: String,
    val token: String,
    val readerTheme: ReaderTheme,
    val fontSize: Float,
    val lineSpacing: Float,
    val serif: Boolean,
    val keepScreenOn: Boolean,
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
                fontSize = p.fontSize,
                lineSpacing = p.lineSpacing,
                serif = p.serif,
                keepScreenOn = p.keepScreenOn,
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
    state: UpdateUi,
    onCheck: () -> Unit,
    onDownload: (UpdateResult.Available) -> Unit,
    onInstall: (UpdateResult.Available, java.io.File) -> Unit,
    onIgnore: (UpdateResult.Available) -> Unit,
) {
    val (accent, title, body) = when (state) {
        is UpdateUi.Idle -> Triple(
            MaterialTheme.colorScheme.primary,
            "检查更新",
            "Readon 会从 GitHub 获取新版本。",
        )
        is UpdateUi.Checking -> Triple(
            MaterialTheme.colorScheme.primary, "正在检查…", "连接 GitHub 中",
        )
        is UpdateUi.UpToDate -> Triple(
            MaterialTheme.colorScheme.secondary, "已是最新版本",
            "当前版本 ${BuildConfig.VERSION_NAME}",
        )
        is UpdateUi.Failed -> Triple(
            MaterialTheme.colorScheme.error, "检查失败", state.message,
        )
        is UpdateUi.Available -> Triple(
            MaterialTheme.colorScheme.secondary,
            "发现新版本 ${state.info.versionName}",
            state.info.notes.lineSequence().firstOrNull { it.isNotBlank() }
                ?.take(90) ?: "点击下载并安装。",
        )
        is UpdateUi.Downloading -> Triple(
            MaterialTheme.colorScheme.primary, "正在下载更新…", "",
        )
        is UpdateUi.ReadyToInstall -> Triple(
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
                        is UpdateUi.Available, is UpdateUi.ReadyToInstall -> Icons.Filled.Download
                        is UpdateUi.UpToDate -> Icons.Filled.Check
                        is UpdateUi.Failed -> Icons.Filled.Info
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
                is UpdateUi.Downloading -> {
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

                is UpdateUi.Available -> {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onDownload(state.info) }) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("下载更新")
                        }
                        OutlinedButton(onClick = { onIgnore(state.info) }) {
                            Text("忽略此版本")
                        }
                    }
                }

                is UpdateUi.ReadyToInstall -> {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { onInstall(state.info, state.file) }) {
                        Text("立即安装")
                    }
                }

                else -> {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onCheck, enabled = state !is UpdateUi.Checking) {
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
