package com.blprkfgre.prettydamnfine.state

import android.content.Intent
import android.net.Uri

/**
 * Explicit representation of the incoming intents the app handles.
 *
 * `None --ACTION_VIEW(uri)--> ViewDocument`; `None --ACTION_SEND(uri)-->
 * SendDocument`. Both document routes open the same PDF, but keeping them distinct
 * makes the routing `when` exhaustive and documents that no other action is
 * handled. Parsing is pure so `onCreate` and `onNewIntent` share one code path.
 */
sealed interface IntentRoute {
    data object None : IntentRoute
    data class ViewDocument(val uri: Uri) : IntentRoute
    data class SendDocument(val uri: Uri) : IntentRoute

    companion object {
        /** Maps an intent (including null, before `setIntent`) to a route. */
        fun from(intent: Intent?): IntentRoute = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let(::ViewDocument) ?: None
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
                uri?.let(::SendDocument) ?: None
            }
            else -> None
        }
    }
}
