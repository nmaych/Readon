package com.readon.reader.ui.theme

import androidx.compose.ui.graphics.Color
import com.readon.reader.data.ReaderInk

/**
 * The colours the reader draws with, after the user's overrides are applied.
 *
 * [ReaderTheme] is the *choice* (纸白 / 米黄 / 夜间 …); this is the resolved result
 * once an independent text or page colour has been layered on top of it. The
 * panels take this rather than the enum so that a custom page colour reaches the
 * table of contents, the search box and the toolbar -- not just the body text,
 * which is what makes a custom colour feel like a setting instead of a bug.
 */
data class ReaderSkin(
    val background: Color,
    val text: Color,
    val secondaryText: Color,
    /** Used for the selected row, the progress bar and the chapter marker. */
    val accent: Color,
    /** True when the page is dark, for the toolbar's own elevation decisions. */
    val dark: Boolean,
) {
    companion object {
        fun of(theme: ReaderTheme): ReaderSkin = ReaderSkin(
            background = theme.background,
            text = theme.text,
            secondaryText = theme.secondaryText,
            accent = theme.accent,
            dark = ReaderInk.isDark(theme.background.toArgbInt()),
        )

        /**
         * Resolve the theme against the user's text and page colour overrides.
         *
         * The accent is only re-derived when an override is actually in play: the
         * theme's own accent is tuned against the theme's own page, and replacing
         * it on an untouched theme would change how the app looks for everyone who
         * never opens the colour picker.
         */
        fun of(theme: ReaderTheme, backgroundOverride: Int, textOverride: Int): ReaderSkin {
            val (bg, fg, secondary) = ReaderInk.resolve(
                themeBackground = theme.background.toArgbInt(),
                themeText = theme.text.toArgbInt(),
                themeSecondary = theme.secondaryText.toArgbInt(),
                backgroundOverride = backgroundOverride,
                textOverride = textOverride,
            )
            val overridden = backgroundOverride != ReaderInk.NONE || textOverride != ReaderInk.NONE
            return ReaderSkin(
                background = Color(bg),
                text = Color(fg),
                secondaryText = Color(secondary),
                // A custom page colour can be anything, so the brand accent may
                // vanish into it; fall back to something drawn from the text.
                accent = if (overridden) Color(fg) else theme.accent,
                dark = ReaderInk.isDark(bg),
            )
        }
    }
}

/** Packed ARGB for a Compose [Color], for the Android-free [ReaderInk] helpers. */
fun Color.toArgbInt(): Int = ReaderInk.argb(
    a = (alpha * 255f + 0.5f).toInt(),
    r = (red * 255f + 0.5f).toInt(),
    g = (green * 255f + 0.5f).toInt(),
    b = (blue * 255f + 0.5f).toInt(),
)
