package com.readon.reader.data

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Colour arithmetic for the reader, on packed ARGB ints.
 *
 * Deliberately free of `android.graphics.Color` and of Compose types so
 * `tools/check-book-logic.cmd` can compile this exact file and check it on the
 * desktop. That matters because the interesting part is not the arithmetic but
 * its *guarantees*: when the user picks a text colour and a page colour
 * independently, the muted colour used for the footer, the chapter subtitle and
 * the search excerpt must stay legible. Picking a dark grey text on a black page
 * is a perfectly reasonable thing to do, and the secondary text must not
 * disappear into the background when they do.
 */
object ReaderInk {

    /**
     * "No override -- follow the theme."
     *
     * Zero is safe as a sentinel because a real colour is opaque and therefore
     * has an alpha byte of 0xFF, so no visible colour can collide with it.
     */
    const val NONE = 0

    /** WCAG AA for large text; the reader's secondary labels are all small. */
    const val MIN_CONTRAST = 3.0

    fun alpha(argb: Int): Int = (argb ushr 24) and 0xFF
    fun red(argb: Int): Int = (argb ushr 16) and 0xFF
    fun green(argb: Int): Int = (argb ushr 8) and 0xFF
    fun blue(argb: Int): Int = argb and 0xFF

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)

    fun rgb(r: Int, g: Int, b: Int): Int = argb(255, r, g, b)

    /** True when [argb] is closer to black than to white, by WCAG luminance. */
    fun isDark(argb: Int): Boolean = luminance(argb) < 0.5

    /**
     * WCAG relative luminance, 0 (black) .. 1 (white).
     *
     * The sRGB gamma curve is applied rather than a naive average: the whole
     * point is to know whether a muted grey stays readable on a given page, and
     * a linear average rates a mid grey far too generously on a light page.
     */
    fun luminance(argb: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(red(argb)) +
            0.7152 * channel(green(argb)) +
            0.0722 * channel(blue(argb))
    }

    /** WCAG contrast ratio, 1.0 (identical) .. 21.0 (black on white). */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Linear blend of [a] toward [b]; `t = 0` is [a], `t = 1` is [b]. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        fun lerp(x: Int, y: Int) = (x + (y - x) * f + 0.5f).toInt()
        return argb(
            lerp(alpha(a), alpha(b)),
            lerp(red(a), red(b)),
            lerp(green(a), green(b)),
            lerp(blue(a), blue(b)),
        )
    }

    /**
     * Black or white, whichever reads better on [background].
     *
     * Chosen by comparing the two actual contrast ratios rather than by a
     * luminance threshold. A threshold gets the middle of the range wrong: at a
     * luminance of 0.30 a naive "dark enough, use white" rule returns white with
     * a contrast of only 3.0, where black would have scored 7.0. Comparing always
     * picks the better of the two, and the worst case over all backgrounds is
     * 4.58 -- comfortably above the floor this class enforces.
     */
    fun readableOn(background: Int): Int {
        val black = rgb(0, 0, 0)
        val white = rgb(255, 255, 255)
        return if (contrast(black, background) >= contrast(white, background)) black else white
    }

    /**
     * A muted version of [text] that is still legible on [background].
     *
     * Starts from a 45% blend toward the page colour -- which is what makes
     * secondary text look secondary -- and walks the blend back until the
     * contrast floor is met. Walking rather than computing a closed form keeps
     * the result on the line between the two colours the user chose, so the
     * muted tone is always recognisably *their* text colour.
     *
     * A blend can never beat the contrast of the text colour it starts from, so
     * when even that fails -- the user picked black text on a black page -- there
     * is nothing useful on the line between the two. That case falls back to plain
     * black or white, which is readable on any page: the body text is the user's
     * explicit choice and is left alone, but the footer and the chapter subtitle
     * must not become invisible as well. [hasPoorContrast] lets the UI warn about
     * the pair rather than silently drawing an unreadable page.
     */
    fun readableSecondary(
        background: Int,
        text: Int,
        minContrast: Double = MIN_CONTRAST,
    ): Int {
        if (contrast(text, background) < minContrast) {
            val fallback = readableOn(background)
            if (contrast(fallback, background) >= contrast(text, background)) return fallback
        }
        var t = 0.45f
        var candidate = mix(text, background, t)
        var steps = 0
        while (steps < 12 && contrast(candidate, background) < minContrast) {
            t -= 0.05f
            if (t <= 0f) return text
            candidate = mix(text, background, t)
            steps++
        }
        return candidate
    }

    /**
     * True when [text] is too close to [background] to read comfortably.
     *
     * Used to warn in the options panel rather than to override: the page colour
     * is the user's explicit choice, and silently changing their text colour back
     * would look like the setting had failed.
     */
    fun hasPoorContrast(background: Int, text: Int): Boolean =
        contrast(text, background) < MIN_CONTRAST

    /**
     * Resolve the reader's three colours from the theme plus any user overrides.
     *
     * An override of [NONE] keeps the theme's own value. The secondary colour is
     * re-derived whenever either override is present, because a theme's muted
     * grey is only tuned for the theme's own page colour.
     */
    fun resolve(
        themeBackground: Int,
        themeText: Int,
        themeSecondary: Int,
        backgroundOverride: Int,
        textOverride: Int,
    ): Triple<Int, Int, Int> {
        val background = if (backgroundOverride == NONE) themeBackground else backgroundOverride
        val text = if (textOverride == NONE) themeText else textOverride
        val secondary = if (backgroundOverride == NONE && textOverride == NONE) {
            themeSecondary
        } else {
            readableSecondary(background, text)
        }
        return Triple(background, text, secondary)
    }
}
