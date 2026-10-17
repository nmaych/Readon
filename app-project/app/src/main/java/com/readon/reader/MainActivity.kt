package com.readon.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.readon.reader.data.Prefs
import com.readon.reader.ui.AppShell
import com.readon.reader.ui.components.ProvideWindowSize
import com.readon.reader.ui.theme.ReadonTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { ReadonRoot() }
    }
}

@Composable
private fun ReadonRoot() {
    val context = LocalContext.current
    val app = remember(context) { ReadonApp.of(context) }
    // The theme choice is read from the same store the settings screen writes,
    // so switching it recomposes the whole tree rather than only the screen.
    val mode by app.prefs.revision.collectAsState(initial = 0)
    val themeMode = remember(mode) { app.prefs.themeMode }

    val dark = when (themeMode) {
        Prefs.THEME_LIGHT -> false
        Prefs.THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }

    ReadonTheme(darkTheme = dark) {
        // The window's measured size is published once, here, and read by every
        // screen. Measured at the root rather than per screen so that the width
        // class is a property of the window and not of whatever space a `Scaffold`
        // had left over -- on a device in landscape the bottom bar is 62 dp of
        // height, and a class computed from the content box would flip between two
        // layouts depending on whether that bar was showing.
        ProvideWindowSize {
            AppShell(app = app)
        }
    }
}
