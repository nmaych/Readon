package com.readon.reader.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Readon palette.
 *
 * The brand colours are taken from the launcher icon: a deep navy field, a
 * bright aquamarine accent and clean white "paper". Everything else is tuned
 * around those three so the app and its icon read as one thing.
 */
object ReadonColors {
    /** Icon background navy. */
    val Navy = Color(0xFF0E2A54)
    val NavyDeep = Color(0xFF0A1F3F)
    val NavySoft = Color(0xFF1B3D6E)

    /** Icon accent aquamarine. */
    val Aqua = Color(0xFF33E1D0)
    val AquaDeep = Color(0xFF12B5A6)

    /** Paper. */
    val Paper = Color(0xFFF7F9FC)
    val PaperWarm = Color(0xFFF6F1E4)
    val PaperGreen = Color(0xFFE3EFE4)
    val PaperDark = Color(0xFF14181F)
}

/**
 * Reader themes are independent of the app theme: a user reading at night wants
 * a dark page even when the app chrome is light, so these are a separate axis.
 */
enum class ReaderTheme(
    val label: String,
    val background: Color,
    val text: Color,
    val secondaryText: Color,
) {
    Paper("纸白", Color(0xFFFDFDFB), Color(0xFF1A1A1A), Color(0xFF8A8A8A)),
    Warm("米黄", Color(0xFFF6F1E4), Color(0xFF2B2620), Color(0xFF918875)),
    Green("护眼", Color(0xFFCCE8CF), Color(0xFF1F2A22), Color(0xFF6E7F72)),
    Night("夜间", Color(0xFF14181F), Color(0xFFC7CCD4), Color(0xFF6B7280)),
    Black("纯黑", Color(0xFF000000), Color(0xFFB9BEC6), Color(0xFF5A6068)),
    ;

    companion object {
        fun fromName(name: String?): ReaderTheme =
            entries.firstOrNull { it.name == name } ?: Paper
    }
}
