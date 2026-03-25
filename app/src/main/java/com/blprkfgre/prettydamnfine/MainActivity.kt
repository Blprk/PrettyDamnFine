package com.blprkfgre.prettydamnfine

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.blprkfgre.prettydamnfine.ui.library.LibraryScreen
import com.blprkfgre.prettydamnfine.ui.pdf.PdfViewerScreen
import com.blprkfgre.prettydamnfine.ui.pdf.PdfViewerViewModel
import com.blprkfgre.prettydamnfine.ui.settings.SettingsScreen
import com.blprkfgre.prettydamnfine.ui.theme.PrettyDamnFineTheme
import com.blprkfgre.prettydamnfine.ui.settings.SettingsRepository

class MainActivity : ComponentActivity() {
    private val viewModel: PdfViewerViewModel by viewModels()
    private lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        settingsRepository = SettingsRepository(this)

        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                viewModel.openDocument(uri)
            }
        }

        setContent {
            PrettyDamnFineTheme {
                MainApp(viewModel, settingsRepository)
            }
        }
    }
}

@Composable
fun MainApp(viewModel: PdfViewerViewModel, settingsRepository: SettingsRepository) {
    val engine by viewModel.engine.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(
            repository = settingsRepository,
            onBack = { showSettings = false }
        )
    } else if (engine == null) {
        LibraryScreen(
            viewModel = viewModel,
            onOpenPdf = { uri -> viewModel.openDocument(uri) },
            onSettingsClick = { showSettings = true }
        )
    } else {
        PdfViewerScreen(
            viewModel = viewModel,
            onBack = { viewModel.closeDocument() }
        )
    }
}
