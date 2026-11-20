package com.blprkfgre.prettydamnfine.state

import android.net.Uri
import com.blprkfgre.prettydamnfine.data.PdfDocumentItem
import com.blprkfgre.prettydamnfine.pdf.PdfEngine

/**
 * Lifecycle of the document currently being viewed.
 *
 * Transitions:
 * - `Closed --openDocument(uri)--> Loading`
 * - `Loading --engine opens--> Ready`
 * - `Loading --PDF is encrypted--> PasswordRequired --authenticate--> Ready`
 * - `Loading --unreadable/cancelled--> Error`
 * - any state `--closeDocument()--> Closed`
 *
 * Only [PasswordRequired] and [Ready] own an engine and a document; all other
 * states deliberately have neither. This makes "password required" and "ready",
 * or "loading" and "open", impossible to represent at the same time.
 */
sealed interface ViewerState {
    /** No document open. The library screen is shown. */
    data object Closed : ViewerState

    /** Importing/opening a URI; no engine is published yet. */
    data class Loading(val source: Uri) : ViewerState

    /** Engine exists but the document is encrypted; wait for [authenticate]. */
    data class PasswordRequired(val document: PdfDocumentItem, val engine: PdfEngine) : ViewerState

    /** Usable engine; pages, search, selection and overlays are available. */
    data class Ready(val document: PdfDocumentItem, val engine: PdfEngine) : ViewerState

    /** Opening failed. The message is shown as a toast and the library stays visible. */
    data class Error(val message: String, val source: Uri?) : ViewerState

    /** The document shown by the viewer, or null while nothing is open. */
    val openDocument: PdfDocumentItem?
        get() = when (this) {
            is PasswordRequired -> document
            is Ready -> document
            Closed, is Loading, is Error -> null
        }

    /** The engine shown by the viewer, or null while no usable engine exists. */
    val openEngine: PdfEngine?
        get() = when (this) {
            is PasswordRequired -> engine
            is Ready -> engine
            Closed, is Loading, is Error -> null
        }
}
