package com.blprkfgre.prettydamnfine.state

import android.net.Uri

/**
 * Multi-select lifecycle of the library.
 *
 * `Idle --long press--> Selecting(uris) --delete icon--> ConfirmRemove(uris)`;
 * `ConfirmRemove --cancel--> Selecting`, `ConfirmRemove --confirm--> Idle`,
 * `Selecting --empty selection / cancel--> Idle`.
 * Both non-idle variants guarantee at least one selected URI, so the "0 selected,
 * confirm removal" state cannot exist.
 *
 * Sorting (recent/oldest/name) and the sort dropdown are currently local UI state
 * in `LibraryScreen`; [LibrarySortMode] is the canonical model for a future move.
 */
sealed interface LibrarySelectionState {
    data object Idle : LibrarySelectionState

    data class Selecting(val selectedUris: Set<Uri>) : LibrarySelectionState {
        init {
            require(selectedUris.isNotEmpty()) { "Selecting needs at least one URI" }
        }
    }

    data class ConfirmRemove(val selectedUris: Set<Uri>) : LibrarySelectionState {
        init {
            require(selectedUris.isNotEmpty()) { "ConfirmRemove needs at least one URI" }
        }
    }

    val selectedUrisOrEmpty: Set<Uri>
        get() = when (this) {
            is Selecting -> selectedUris
            is ConfirmRemove -> selectedUris
            Idle -> emptySet()
        }
}

/** Library sort axis; maps to the private `SortMode` enum in `LibraryScreen`. */
enum class LibrarySortMode { RECENT, OLDEST, NAME }
