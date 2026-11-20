package com.blprkfgre.prettydamnfine.state

/**
 * Progress of background text extraction for the open document.
 *
 * `Idle --startTextExtraction--> Extracting --completed/cancelled--> Done`,
 * with `Done --openDocument/closeDocument--> Idle`. Extraction is independent of
 * search: a search may be off while pages are still being indexed.
 */
sealed interface TextExtractionState {
    data object Idle : TextExtractionState
    data object Extracting : TextExtractionState
    data object Done : TextExtractionState
}
