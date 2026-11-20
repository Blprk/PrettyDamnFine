package com.blprkfgre.prettydamnfine.state

/**
 * One immutable snapshot of every explicit state machine in the reader.
 *
 * The ViewModel owns this value as its single source of truth; all legacy
 * `StateFlow`s exposed for the existing UI are derived from it. Rendering from a
 * single snapshot means an `when` over [viewer], [search], [selection] and
 * [overlay] is exhaustive and contradictory combinations (for example a search
 * result index while search is off) cannot be produced by the ViewModel.
 */
data class ReaderUiState(
    val viewer: ViewerState = ViewerState.Closed,
    val search: SearchState = SearchState.Off,
    val selection: SelectionState = SelectionState.Idle,
    val overlay: OverlayState = OverlayState.None,
    val theme: ThemeState,
    val readMode: ReadModeState = ReadModeState.Off,
    val librarySelection: LibrarySelectionState = LibrarySelectionState.Idle,
    val navigation: NavigationRequest? = null,
    val extraction: TextExtractionState = TextExtractionState.Idle
)
