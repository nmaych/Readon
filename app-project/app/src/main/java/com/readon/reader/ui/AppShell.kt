package com.readon.reader.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import com.readon.reader.ReadonApp
import com.readon.reader.data.Prefs
import com.readon.reader.ui.screens.HomeScreen
import com.readon.reader.ui.screens.ReaderScreen
import com.readon.reader.ui.screens.SettingsScreen
import com.readon.reader.ui.screens.ShelfScreen
import com.readon.reader.ui.theme.ReadonColors
import kotlinx.coroutines.launch

/** The three destinations in the bottom bar. */
private enum class Tab(
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
) {
    Home("主页", Icons.Filled.Explore, Icons.Outlined.Explore),
    Shelf("书架", Icons.AutoMirrored.Filled.MenuBook, Icons.AutoMirrored.Outlined.MenuBook),
    Settings("设置", Icons.Filled.Settings, Icons.Outlined.Settings),
}

/**
 * The app's frame: three tabs over a shared host for messages, with the reader
 * pushed on top as a full-screen destination.
 *
 * The reader is deliberately not a fourth tab. It is a *place you go into* from
 * either the home screen or the shelf, and it owns the whole window -- keeping
 * it out of the tab state means leaving a book always returns to whichever list
 * you started from.
 */
@Composable
fun AppShell(app: ReadonApp) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // The book currently open, if any. Held here rather than in a screen so the
    // reader survives tab changes and configuration changes.
    var openBook by remember { androidx.compose.runtime.mutableStateOf<com.readon.reader.data.Book?>(null) }

    // Bumped whenever the reader closes. Reading a book writes progress to the
    // store, and the shelf is still in the composition behind the reader, so
    // without this signal the cover would show a stale percentage until the app
    // was restarted.
    var dataRevision by remember { mutableIntStateOf(0) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val toast: (String) -> Unit = { msg ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }

    /**
     * The system back key for the tab level.
     *
     * The reader registers its own handler and is composed on top of this one, so
     * while a book is open back never reaches here. When no book is open, back
     * first returns to 主页 from another tab -- the three tabs are one screen's
     * worth of navigation, and dropping the user out of the app from 设置 loses
     * their place for no reason -- and only then leaves the app, which is what a
     * back press with nothing left to close is supposed to do.
     *
     * Disabled outright while a book is open rather than relying on the reader's
     * handler being registered later: which callback the dispatcher picks is not
     * something this screen should have to reason about.
     */
    BackHandler(enabled = tab != 0 && openBook == null) { tab = 0 }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                ReadonBottomBar(
                    current = tab,
                    onSelect = { tab = it },
                )
            },
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = padding.calculateBottomPadding())
            ) {
                when (tab) {
                    0 -> HomeScreen(
                        app = app,
                        contentPadding = padding,
                        toast = toast,
                        onOpen = { openBook = it },
                        // Same reason as the shelf: reading a book from the online
                        // library both writes to and reads from the cache, so the
                        // "已缓存" marks on the list are re-read once the reader
                        // closes rather than left as they were when it opened.
                        dataRevision = dataRevision,
                    )
                    1 -> ShelfScreen(
                        app = app,
                        contentPadding = padding,
                        toast = toast,
                        onOpen = { openBook = it },
                        onBrowse = { tab = 0 },
                        dataRevision = dataRevision,
                    )
                    else -> SettingsScreen(
                        app = app,
                        contentPadding = padding,
                        toast = toast,
                    )
                }
            }
        }

        // The reader sits above everything, including the bottom bar.
        openBook?.let { book ->
            ReaderScreen(
                app = app,
                book = book,
                onClose = {
                    openBook = null
                    dataRevision++
                },
            )
        }
    }
}

/**
 * The bottom navigation bar.
 *
 * A custom bar rather than `NavigationBar` because the selected pill is a
 * rounded highlight behind the icon and label together, which is the look the
 * design calls for; `NavigationBar` only offers an indicator behind the icon.
 */
@Composable
private fun ReadonBottomBar(current: Int, onSelect: (Int) -> Unit) {
    val tabs = Tab.entries

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(62.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, t ->
                BottomBarItem(
                    tab = t,
                    selected = index == current,
                    onClick = { if (index != current) onSelect(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BottomBarItem(
    tab: Tab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant

    // A small lift on selection, which reads as the item "coming forward"
    // without moving the row's baseline.
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.94f,
        animationSpec = tween(180),
        label = "tabScale",
    )

    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else Color.Transparent
                )
                .padding(horizontal = 16.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (selected) tab.selectedIcon else tab.icon,
                contentDescription = tab.label,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = tab.label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
