package com.blprkfgre.prettydamnfine

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.blprkfgre.prettydamnfine.ui.library.LibraryScreen
import com.blprkfgre.prettydamnfine.ui.theme.AppThemeMode
import com.blprkfgre.prettydamnfine.ui.pdf.PdfViewerScreen
import com.blprkfgre.prettydamnfine.ui.pdf.PdfViewerViewModel
import com.blprkfgre.prettydamnfine.ui.theme.PrettyDamnFineTheme

class MainActivity : ComponentActivity() {
    private val viewModel: PdfViewerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { viewModel.openDocument(it) }
        }

        setContent {
            val themeMode by viewModel.appThemeMode.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val dark = when (themeMode) {
                AppThemeMode.SYSTEM -> systemDark
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
            }
            PrettyDamnFineTheme(darkTheme = dark) {
                MainApp(viewModel, dark)
            }
        }
    }
}

@Composable
fun MainApp(viewModel: PdfViewerViewModel, dark: Boolean) {
    val engine by viewModel.engine.collectAsState()

    if (engine == null) {
        LibraryScreen(
            viewModel = viewModel,
            onOpenPdf = { uri -> viewModel.openDocument(uri) },
            isDark = dark,
            onToggleTheme = { viewModel.toggleAppTheme(dark) }
        )
    } else {
        PdfViewerScreen(
            viewModel = viewModel,
            onBack = { viewModel.closeDocument() }
        )
    }
}
