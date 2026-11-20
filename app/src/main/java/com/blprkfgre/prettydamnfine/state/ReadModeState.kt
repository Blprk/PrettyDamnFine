package com.blprkfgre.prettydamnfine.state

/**
 * Distraction-free reading mode. [On] locks horizontal panning to the page centre
 * (the composable re-centres when this flips). It is a sealed type rather than a
 * boolean so additional reading modes can be added with compiler-checked `when`s.
 */
sealed interface ReadModeState {
    data object Off : ReadModeState
    data object On : ReadModeState

    val enabled: Boolean get() = this is On
}
