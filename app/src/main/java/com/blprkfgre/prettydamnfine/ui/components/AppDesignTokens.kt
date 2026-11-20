package com.blprkfgre.prettydamnfine.ui.components

import androidx.compose.ui.unit.dp

/**
 * Shared layout tokens for the whole app.
 *
 * These are the single source of truth for spacing, corner radii, icon sizes
 * and touch targets. Screens must reference these instead of sprinkling raw
 * `dp` values, so rhythm stays consistent across the viewer, the library and
 * every bottom sheet.
 */
object Spacing {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 16.dp
    val Lg = 24.dp
    val Xl = 32.dp
}

object Radii {
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 20.dp
    val Pill = 50.dp
}

object IconSizes {
    val Small = 18.dp
    val Standard = 24.dp
    val Large = 32.dp
    val Hero = 48.dp
}

object TouchTargets {
    /** Material 3 minimum accessible touch target (Android guidance: 48dp). */
    val Min = 48.dp
}

object Elevation {
    val Panel = 3.dp
    val PanelShadow = 6.dp
}

/** Fixed elevation/geometry of the viewer's bottom contextual bar. */
object ViewerChrome {
    val BarBottomInset = 24.dp
    val BarHeight = 56.dp
    val FloatingGap = Spacing.Sm

    /** Bottom inset for the read-mode FAB and the selection panel, so neither
     * overlaps the zoom bar. */
    val FloatingBottomInset = BarBottomInset + BarHeight + FloatingGap
}
