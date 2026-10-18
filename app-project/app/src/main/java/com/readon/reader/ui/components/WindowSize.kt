package com.readon.reader.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.readon.reader.ui.Adapt

/**
 * The measured window, published once for every screen to read.
 *
 * ## Why a `CompositionLocal` rather than each screen measuring itself
 *
 * The alternative -- a `BoxWithConstraints` per screen -- measures each screen against
 * *its own* constraints, which are the ones left after `Scaffold` has taken the bottom
 * bar. That is not the window, and the difference matters on a device in landscape
 * where the bar eats 62 dp: a width class computed from the content box can flip
 * between two layouts depending on whether an unrelated bar is showing. Measured at the
 * root, before any of that, it is a property of the window and stays put.
 *
 * ## Why the width is not taken from `LocalConfiguration`
 *
 * Because it reports the *display*, not the window. A tablet in split screen, a freeform
 * window, and a foldable running two apps are all narrower than the screen, and laying
 * out for the screen there means a layout designed for 1200 dp rendered inside 500 dp.
 * Measured constraints are the honest answer.
 */
@Immutable
data class WindowSize(
    /** The window's width in dp. */
    val widthDp: Int,
    /** The window's height in dp. */
    val heightDp: Int,
) {
    /** The class this width falls into. */
    val widthClass: Adapt.WidthClass get() = Adapt.widthClass(widthDp)

    /** True for the classes that get more than one column of content. */
    val isWide: Boolean get() = widthClass.isWide
}

/**
 * Defaults to a phone-until-measured window, which is the always-safe layout.
 *
 * A portrait phone is assumed rather than a tablet because the narrow layout is the one
 * that renders acceptably at any width: getting it wrong in that direction costs a
 * couple of frames of single-column layout, and getting it wrong the other way would
 * attempt a three-column grid in the space of a phone.
 */
val LocalWindowSize = staticCompositionLocalOf { WindowSize(400, 800) }

/**
 * Measure the window and publish it to everything below.
 *
 * The `BoxWithConstraints` is only a measurement host: it fills its parent and its single
 * child is the app, so inserting this at the root cannot change what the app looks like.
 *
 * ## Why the constraints are converted to dp here
 *
 * `constraints.maxWidth` is in **pixels**, and [WindowSize] is documented in **dp** --
 * every consumer feeds it straight into [Adapt], whose breakpoints are 600 dp and 840 dp.
 * Handing the raw pixel count over therefore multiplied the measured width by the
 * display density: a 393 dp phone at 2.75x reports ~1080, which is `Expanded`, so the
 * phone took the tablet path on every device. That is the bug that made 主页 render the
 * wide layout on a phone; converting at this one boundary is what makes the value match
 * its own documentation.
 */
@Composable
fun ProvideWindowSize(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val size = remember(constraints.maxWidth, constraints.maxHeight, density) {
            WindowSize(
                widthDp = with(density) { constraints.maxWidth.toDp().value.toInt() },
                heightDp = with(density) { constraints.maxHeight.toDp().value.toInt() },
            )
        }
        CompositionLocalProvider(LocalWindowSize provides size, content = content)
    }
}
