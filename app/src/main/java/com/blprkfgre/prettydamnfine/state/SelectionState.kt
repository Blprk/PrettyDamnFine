package com.blprkfgre.prettydamnfine.state

/**
 * A text range on one page, with inclusive character indices.
 *
 * Declared here (and type-aliased from `ui.pdf`) so the state layer does not
 * depend on the UI layer.
 */
data class TextSelection(
    val pageIndex: Int,
    val startCharIndex: Int,
    val endCharIndex: Int
)

/**
 * Text selection lifecycle.
 *
 * Transitions: `Idle --setSelection--> Selecting --characters resolved--> Actionable`;
 * `Actionable --copy/highlight/remove-highlight/clear--> Idle`; any state
 * `--closeDocument() / performSearch()--> Idle`.
 *
 * [Selecting] may carry an empty [text] while character positions are still being
 * fetched; [Actionable] guarantees non-blank text, i.e. the selection toolbar has
 * something to act on. The short "delay before showing the toolbar" animation is
 * presentation-only and stays in the composable.
 */
sealed interface SelectionState {
    data object Idle : SelectionState

    data class Selecting(val range: TextSelection, val text: String = "") : SelectionState

    data class Actionable(val range: TextSelection, val text: String) : SelectionState {
        init {
            require(text.isNotBlank()) { "SelectionState.Actionable needs non-blank text" }
        }
    }

    val rangeOrNull: TextSelection?
        get() = when (this) {
            is Selecting -> range
            is Actionable -> range
            Idle -> null
        }

    val textOrEmpty: String
        get() = when (this) {
            is Selecting -> text
            is Actionable -> text
            Idle -> ""
        }
}
