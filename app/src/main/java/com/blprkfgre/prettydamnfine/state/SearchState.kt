package com.blprkfgre.prettydamnfine.state

import com.blprkfgre.prettydamnfine.pdf.SearchResult

/**
 * Text search inside the open document.
 *
 * Transitions: `Off --performSearch(query)--> Active.NoResults | Active.Results`;
 * `Active.Results --next/previous--> Active.Results` (same matches, new index);
 * `Active --performSearch("")--> Off`; any state `--closeDocument()--> Off`.
 *
 * Match data and the cursor live only in [Active.Results], whose constructor
 * guarantees a non-empty match list and an in-bounds index. Streaming text
 * extraction may replace matches, but the ViewModel clamps the cursor before it
 * builds a new value, so an out-of-range "current result" is unrepresentable.
 * Extraction progress is tracked separately by [TextExtractionState].
 */
sealed interface SearchState {
    /** Search UI closed and no query/results retained. */
    data object Off : SearchState

    sealed interface Active : SearchState {
        val query: String

        /** Query entered, zero matches so far (text may still be extracting). */
        data class NoResults(override val query: String) : Active

        /** Query with at least one match and a valid cursor into [matches]. */
        data class Results(
            override val query: String,
            val matches: List<SearchResult>,
            val currentIndex: Int
        ) : Active {
            init {
                require(matches.isNotEmpty()) { "SearchState.Results needs at least one match" }
                require(currentIndex in matches.indices) { "Search result index out of bounds" }
            }
        }
    }

    /** Null unless a search is active; used by the legacy `searchQuery` flow. */
    val queryOrNull: String?
        get() = when (this) {
            is Active -> query
            Off -> null
        }

    val matchesOrEmpty: List<SearchResult>
        get() = when (this) {
            is Active.Results -> matches
            else -> emptyList()
        }

    /** -1 when there is no current match; used by the legacy `currentSearchIndex` flow. */
    val indexOrMinusOne: Int
        get() = when (this) {
            is Active.Results -> currentIndex
            else -> -1
        }
}
