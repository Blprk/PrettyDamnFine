package com.blprkfgre.prettydamnfine.state

import com.blprkfgre.prettydamnfine.pdf.ReadingTheme
import com.blprkfgre.prettydamnfine.ui.theme.AppThemeMode

/**
 * Both theme axes: the app chrome (system/light/dark) and the page rendering
 * theme (light/dark/sepia/auto). Keeping them in one value prevents the two
 * persisted preferences from drifting apart in snapshots used by tooling.
 */
data class ThemeState(
    val appTheme: AppThemeMode,
    val reading: ReadingTheme
) {
    /** Resolves the user-facing `AUTO` page theme to a concrete rendering theme. */
    fun effectiveReadingTheme(systemIsDark: Boolean): ReadingTheme =
        if (reading == ReadingTheme.AUTO) {
            if (systemIsDark) ReadingTheme.DARK else ReadingTheme.LIGHT
        } else {
            reading
        }
}
