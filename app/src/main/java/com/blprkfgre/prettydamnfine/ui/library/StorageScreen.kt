package com.blprkfgre.prettydamnfine.ui.library

import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blprkfgre.prettydamnfine.data.PdfDocumentItem
import com.blprkfgre.prettydamnfine.data.PrivateCopy
import com.blprkfgre.prettydamnfine.ui.components.ActionIconButton
import com.blprkfgre.prettydamnfine.ui.components.IconSizes
import com.blprkfgre.prettydamnfine.ui.components.PdfEmptyState
import com.blprkfgre.prettydamnfine.ui.components.SectionHeader
import com.blprkfgre.prettydamnfine.ui.components.Spacing
import com.blprkfgre.prettydamnfine.ui.pdf.PdfViewerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shows the app-owned PDF copies on disk and the library entries whose files went
 * missing. Users can reclaim unused space or re-link a lost entry to a new file;
 * files the user owns outside the app are never touched here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    viewModel: PdfViewerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var copies by remember { mutableStateOf<List<PrivateCopy>>(emptyList()) }
    var missing by remember { mutableStateOf<List<PdfDocumentItem>>(emptyList()) }
    var pendingRelink by remember { mutableStateOf<PdfDocumentItem?>(null) }
    var confirmCleanup by remember { mutableStateOf(false) }

    fun refresh() {
        copies = viewModel.storageCopies()
        missing = viewModel.missingDocuments()
    }

    LaunchedEffect(Unit) { refresh() }

    BackHandler(onBack = onBack)

    val relinkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val target = pendingRelink
        pendingRelink = null
        if (uri != null && target != null) {
            val updated = viewModel.relinkDocument(target.uri, uri)
            Toast.makeText(
                context,
                if (updated != null) "File re-linked" else "Could not re-link this file",
                Toast.LENGTH_SHORT
            ).show()
        }
        refresh()
    }

    val unused = copies.filter { !it.inLibrary }
    val totalBytes = copies.sumOf { it.sizeBytes }

    if (confirmCleanup) {
        AlertDialog(
            onDismissRequest = { confirmCleanup = false },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text("Delete ${unused.size} unused ${if (unused.size == 1) "copy" else "copies"}?") },
            text = { Text("Only copies that are no longer in your library are deleted. Your own files are never touched.") },
            confirmButton = {
                TextButton(onClick = {
                    var deleted = 0
                    unused.forEach { if (viewModel.deletePrivateCopy(it.file)) deleted++ }
                    confirmCleanup = false
                    refresh()
                    Toast.makeText(context, "$deleted deleted", Toast.LENGTH_SHORT).show()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmCleanup = false }) { Text("Cancel") } }
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Storage") },
                navigationIcon = {
                    ActionIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to library",
                        onClick = onBack
                    )
                },
                actions = {
                    ActionIconButton(
                        icon = Icons.Filled.DeleteSweep,
                        contentDescription = "Delete unused copies",
                        onClick = { confirmCleanup = true },
                        enabled = unused.isNotEmpty()
                    )
                }
            )
        }
    ) { padding ->
        if (copies.isEmpty() && missing.isEmpty()) {
            PdfEmptyState(
                icon = Icons.Filled.FolderOpen,
                title = "No private copies",
                message = "PDFs you open are stored here so they stay available offline. Nothing to clean up yet.",
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(
                    horizontal = Spacing.Md,
                    vertical = Spacing.Sm
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.Sm)
            ) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(Modifier.padding(Spacing.Md)) {
                            Text(
                                "Used by this app",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.height(Spacing.Xs))
                            Text(
                                "${Formatter.formatFileSize(context, totalBytes)} across ${copies.size} " +
                                    (if (copies.size == 1) "copy" else "copies") +
                                    (if (unused.isNotEmpty()) " · ${unused.size} unused" else ""),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                if (missing.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(Spacing.Sm))
                        SectionHeader("Missing files")
                        Text(
                            "These library entries lost their file. Re-link one to a new file or forget it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(missing, key = { "missing_${it.uri}" }) { doc ->
                        MissingFileCard(
                            document = doc,
                            sourceName = viewModel.sourceName(doc),
                            onRelink = {
                                pendingRelink = doc
                                relinkLauncher.launch(arrayOf("application/pdf"))
                            },
                            onForget = {
                                viewModel.forgetDocument(doc.uri)
                                refresh()
                            }
                        )
                    }
                }

                item {
                    Spacer(Modifier.height(Spacing.Sm))
                    SectionHeader("Private copies")
                }
                items(copies, key = { it.file.absolutePath }) { copy ->
                    PrivateCopyCard(
                        copy = copy,
                        onDelete = if (copy.inLibrary) null else {
                            {
                                if (viewModel.deletePrivateCopy(copy.file)) {
                                    Toast.makeText(context, "Copy deleted", Toast.LENGTH_SHORT).show()
                                }
                                refresh()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MissingFileCard(
    document: PdfDocumentItem,
    sourceName: String,
    onRelink: () -> Unit,
    onForget: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.Md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.HideSource,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(IconSizes.Standard)
                )
                Spacer(Modifier.width(Spacing.Sm))
                Column(Modifier.weight(1f)) {
                    Text(
                        document.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "Was: $sourceName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(Spacing.Sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                Button(onClick = onRelink) { Text("Re-link") }
                OutlinedButton(onClick = onForget) { Text("Forget") }
            }
        }
    }
}

@Composable
private fun PrivateCopyCard(copy: PrivateCopy, onDelete: (() -> Unit)?) {
    val context = LocalContext.current
    val date = remember(copy.lastModified) {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(copy.lastModified))
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(Spacing.Md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.PictureAsPdf,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(Spacing.Sm))
            Column(Modifier.weight(1f)) {
                Text(
                    copy.title ?: copy.file.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${Formatter.formatFileSize(context, copy.sizeBytes)} · $date · " +
                        if (copy.inLibrary) "In library" else "Unused copy",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onDelete != null) {
                ActionIconButton(
                    icon = Icons.Filled.Delete,
                    contentDescription = "Delete unused copy ${copy.file.name}",
                    onClick = onDelete,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
