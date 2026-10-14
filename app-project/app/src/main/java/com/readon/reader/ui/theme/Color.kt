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
    /**
     * The colour the reader uses for the selected chapter, the progress bar and
     * the bookmark marker.
     *
     * Per-theme rather than the app's Material accent: on a black page the brand
     * aquamarine is legible, but on the 护眼 green it reads as a smudge, so each
     * theme names the colour that actually works against its own page.
     */
    val accent: Color,
) {
    Paper("纸白", Color(0xFFFDFDFB), Color(0xFF1A1A1A), Color(0xFF8A8A8A), Color(0xFF12B5A6)),
    Warm("米黄", Color(0xFFF6F1E4), Color(0xFF2B2620), Color(0xFF918875), Color(0xFFB07D3A)),
    Green("护眼", Color(0xFFCCE8CF), Color(0xFF1F2A22), Color(0xFF6E7F72), Color(0xFF2E7D4F)),
    Night("夜间", Color(0xFF14181F), Color(0xFFC7CCD4), Color(0xFF6B7280), Color(0xFF33E1D0)),
    Black("纯黑", Color(0xFF000000), Color(0xFFB9BEC6), Color(0xFF5A6068), Color(0xFF33E1D0)),
    ;

    companion object {
        fun fromName(name: String?): ReaderTheme =
            entries.firstOrNull { it.name == name } ?: Paper
    }
}

/**
 * Swatches for the reader's independent text and page colours.
 *
 * The five [ReaderTheme] presets above set both colours at once, which is the
 * right default but not the whole story: the common requests are a warmer page
 * behind the theme's own text, or a dimmer text on the same page, and neither is
 * expressible as one of five fixed pairs. These lists are the choices offered on
 * top of the theme, and `ReaderInk` re-derives the muted colour from whichever
 * pair results so the footer stays readable.
 *
 * Kept as plain ints rather than Compose [Color] values so the same lists can be
 * compared against what `Prefs` stores, which is an int.
 */
object ReaderPalette {

    /** Page colours, light to dark. */
    val backgrounds: List<Int> = listOf(
        0xFFFDFDFB.toInt(), // 纸白
        0xFFF6F1E4.toInt(), // 米黄
        0xFFEFE6D5.toInt(), // 牛皮
        0xFFCCE8CF.toInt(), // 护眼
        0xFFD8E6F2.toInt(), // 淡蓝
        0xFFE8E4F3.toInt(), // 淡紫
        0xFFF5E6EA.toInt(), // 淡粉
        0xFF3A3A38.toInt(), // 石墨
        0xFF14181F.toInt(), // 夜间
        0xFF000000.toInt(), // 纯黑
    )

    /** Text colours, dark to light. */
    val texts: List<Int> = listOf(
        0xFF000000.toInt(), // 黑
        0xFF1A1A1A.toInt(), // near-black, the default
        0xFF2B2620.toInt(), // 暖褐
        0xFF3B3B3B.toInt(), // 深灰
        0xFF5A5A5A.toInt(), // 灰
        0xFF7A6A55.toInt(), // 棕
        0xFF1F3A5F.toInt(), // 藏青
        0xFF3A2F4A.toInt(), // 深紫
        0xFFC7CCD4.toInt(), // 夜间灰
        0xFFB9BEC6.toInt(), // 浅灰
        0xFFE8E8E8.toInt(), // 白
        0xFFFFFFFF.toInt(), // 纯白
    )
}
