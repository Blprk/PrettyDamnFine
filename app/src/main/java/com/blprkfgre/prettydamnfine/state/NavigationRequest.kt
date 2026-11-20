package com.blprkfgre.prettydamnfine.state

/**
 * One-shot "scroll to page" request.
 *
 * Null in [ReaderUiState] means no pending navigation; this replaces the legacy
 * `-1` sentinel without changing it (the compatibility flow still maps null to
 * -1). The consumer must call `onNavigationConsumed()` after scrolling.
 */
data class NavigationRequest(val pageIndex: Int)
