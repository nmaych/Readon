package com.readon.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightScheme = lightColorScheme(
    primary = ReadonColors.Navy,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFD9E6F7),
    onPrimaryContainer = ReadonColors.NavyDeep,
    secondary = ReadonColors.AquaDeep,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFC9F5EF),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF00382F),
    background = ReadonColors.Paper,
    onBackground = androidx.compose.ui.graphics.Color(0xFF11151C),
    surface = androidx.compose.ui.graphics.Color.White,
    onSurface = androidx.compose.ui.graphics.Color(0xFF11151C),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE7ECF3),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF48505C),
    outline = androidx.compose.ui.graphics.Color(0xFFB6BEC9),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFFDDE3EB),
)

private val DarkScheme = darkColorScheme(
    primary = ReadonColors.Aqua,
    onPrimary = androidx.compose.ui.graphics.Color(0xFF00312B),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF11485F),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFC3F2EC),
    secondary = ReadonColors.Aqua,
    onSecondary = androidx.compose.ui.graphics.Color(0xFF00312B),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF14524A),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFC9F5EF),
    background = androidx.compose.ui.graphics.Color(0xFF0F1319),
    onBackground = androidx.compose.ui.graphics.Color(0xFFE4E8EF),
    surface = androidx.compose.ui.graphics.Color(0xFF171C24),
    onSurface = androidx.compose.ui.graphics.Color(0xFFE4E8EF),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF262C36),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFB3BAC6),
    outline = androidx.compose.ui.graphics.Color(0xFF4A5260),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFF2C333E),
)

/**
 * Extra tokens Material 3 has no slot for.
 *
 * Readon needs a book-cover gradient and a "shelf card" tint that are part of
 * its identity rather than of the colour scheme, so they live here instead of
 * being re-derived at each call site.
 */
data class ReadonExtras(
    val coverPalette: List<Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color>>,
    val isDark: Boolean,
)

val LocalReadonExtras = staticCompositionLocalOf {
    ReadonExtras(emptyList(), false)
}

private fun coverPalette(dark: Boolean) = if (dark) {
    listOf(
        androidx.compose.ui.graphics.Color(0xFF1E3A63) to androidx.compose.ui.graphics.Color(0xFF2E6F8E),
        androidx.compose.ui.graphics.Color(0xFF14524A) to androidx.compose.ui.graphics.Color(0xFF1E8C74),
        androidx.compose.ui.graphics.Color(0xFF3A2A5E) to androidx.compose.ui.graphics.Color(0xFF6A4B94),
        androidx.compose.ui.graphics.Color(0xFF5A2F3E) to androidx.compose.ui.graphics.Color(0xFF93506A),
        androidx.compose.ui.graphics.Color(0xFF54401C) to androidx.compose.ui.graphics.Color(0xFF97792F),
        androidx.compose.ui.graphics.Color(0xFF1D4A55) to androidx.compose.ui.graphics.Color(0xFF2F7E8C),
    )
} else {
    listOf(
        androidx.compose.ui.graphics.Color(0xFF25497C) to androidx.compose.ui.graphics.Color(0xFF3E7FB8),
        androidx.compose.ui.graphics.Color(0xFF12B5A6) to androidx.compose.ui.graphics.Color(0xFF57D9C9),
        androidx.compose.ui.graphics.Color(0xFF5A3E8C) to androidx.compose.ui.graphics.Color(0xFF9070C4),
        androidx.compose.ui.graphics.Color(0xFF8C4159) to androidx.compose.ui.graphics.Color(0xFFC2708B),
        androidx.compose.ui.graphics.Color(0xFF9A7526) to androidx.compose.ui.graphics.Color(0xFFD0A94A),
    )
}

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun ReadonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    CompositionLocalProvider(
        LocalReadonExtras provides ReadonExtras(coverPalette(darkTheme), darkTheme)
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            content = content,
        )
    }
}
