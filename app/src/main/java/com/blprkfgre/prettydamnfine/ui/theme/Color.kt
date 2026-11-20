package com.blprkfgre.prettydamnfine.ui.theme

import androidx.compose.ui.graphics.Color
import com.blprkfgre.prettydamnfine.pdf.ReadingTheme

// ── Primitive palette ────────────────────────────────────────────────────────
// Primary - Deep Blue
val PrimaryLight = Color(0xFF1565C0)
val PrimaryDark = Color(0xFF90CAF9)
val OnPrimaryLight = Color(0xFFFFFFFF)
val OnPrimaryDark = Color(0xFF003258)

// Secondary - Teal
val SecondaryLight = Color(0xFF00897B)
val SecondaryDark = Color(0xFF80CBC4)
val OnSecondaryLight = Color(0xFFFFFFFF)
val OnSecondaryDark = Color(0xFF00382F)

// Tertiary - Amber
val TertiaryLight = Color(0xFFFF8F00)
val TertiaryDark = Color(0xFFFFE082)
val OnTertiaryLight = Color(0xFF000000)
val OnTertiaryDark = Color(0xFF000000)

// Background & Surface
val BackgroundLight = Color(0xFFFAFAFA)
val BackgroundDark = Color(0xFF121212)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceDark = Color(0xFF1E1E1E)
val SurfaceVariantLight = Color(0xFFF5F5F5)
val SurfaceVariantDark = Color(0xFF2D2D2D)

// On colors
val OnBackgroundLight = Color(0xFF1C1B1F)
val OnBackgroundDark = Color(0xFFE6E1E5)
val OnSurfaceLight = Color(0xFF1C1B1F)
val OnSurfaceDark = Color(0xFFE6E1E5)
val OnSurfaceVariantLight = Color(0xFF49454F)
val OnSurfaceVariantDark = Color(0xFFCAC4D0)

// Outlines / scrim / error — semantic roles shared by both schemes.
val OutlineLight = Color(0xFF79747E)
val OutlineDark = Color(0xFF938F99)
val OutlineVariantLight = Color(0xFFCAC4D0)
val OutlineVariantDark = Color(0xFF49454F)
val InverseSurfaceLight = Color(0xFF1C1B1F)
val InverseSurfaceDark = Color(0xFFE6E1E5)
val InverseOnSurfaceLight = Color(0xFFF5F5F5)
val InverseOnSurfaceDark = Color(0xFF1C1B1F)
val Scrim = Color(0xFF000000)
val ErrorLight = Color(0xFFB3261E)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorDark = Color(0xFFF2B8B5)
val OnErrorDark = Color(0xFF601410)

// ── App semantic tokens ──────────────────────────────────────────────────────
// Rendered-page backgrounds. Kept here (rather than inline) so components never
// contain raw colour literals.
val PdfPageLight = Color(0xFFFFFFFF)
val PdfPageDark = Color(0xFF18181C)
val PdfPageSepia = Color(0xFFF4ECD8)

// Annotation / selection overlays drawn on top of a rendered page.
val PdfHighlight = Color(0xFFFFD54F)
val PdfSelectionLight = Color(0xFF1565C0)
val PdfSelectionDark = Color(0xFF90CAF9)

/** Page background for a given reader theme — one source of truth. */
val ReadingTheme.pageBackground: Color
    get() = when (this) {
        ReadingTheme.DARK -> PdfPageDark
        ReadingTheme.SEPIA -> PdfPageSepia
        else -> PdfPageLight
    }
