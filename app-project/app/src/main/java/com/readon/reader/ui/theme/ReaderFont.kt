package com.readon.reader.ui.theme

import androidx.compose.ui.text.font.FontFamily

/**
 * The body typefaces the reader offers.
 *
 * Compose ships a small set of families on every device and has no bundled font
 * files here, so these are the *system* families rather than downloaded fonts:
 * a reader that has to fetch a font before it can show a page is worse than one
 * that offers four it already has. `Cursive` is deliberately absent -- its
 * device-dependent appearance ranges from a handwriting script to a fallback
 * sans, which would make the setting look broken on some phones.
 *
 * The enum name is what [com.readon.reader.data.Prefs] persists, so renaming an
 * entry would silently reset every user's choice; the labels are free to change.
 */
enum class ReaderFont(
    val label: String,
    val family: FontFamily,
) {
    Serif("宋体", FontFamily.Serif),
    Sans("黑体", FontFamily.SansSerif),
    Monospace("等宽", FontFamily.Monospace),
    Cursive("楷体", FontFamily.Cursive),
    ;

    companion object {
        fun fromName(name: String?): ReaderFont? = entries.firstOrNull { it.name == name }

        /** Resolve a stored name, falling back to the legacy `serif` flag. */
        fun resolve(name: String?, legacySerif: Boolean): ReaderFont =
            fromName(name) ?: if (legacySerif) Serif else Sans
    }
}
