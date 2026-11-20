package com.blprkfgre.prettydamnfine.state

/**
 * The single modal sheet/dialog layer shown over the PDF pages.
 *
 * Transitions: `None --showOverlay--> one of the variants`;
 * variant `--dismissOverlay()--> None`; any variant `--closeDocument()--> None`.
 * Because the model holds one value, two sheets (outline + overview, for example)
 * cannot be open at once.
 *
 * Two layers are intentionally modelled elsewhere instead of here: the password
 * prompt is [ViewerState.PasswordRequired] (so a prompt cannot outlive the engine
 * that needs it), and the search bar is [SearchState.Active] (so it cannot show
 * results that do not exist).
 */
sealed interface OverlayState {
    data object None : OverlayState
    data object PageJump : OverlayState
    data object ThemeSheet : OverlayState
    data object Outline : OverlayState
    data object Overview : OverlayState
}
