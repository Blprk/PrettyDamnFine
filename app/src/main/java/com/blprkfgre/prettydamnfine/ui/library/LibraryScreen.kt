package com.blprkfgre.prettydamnfine.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blprkfgre.prettydamnfine.data.PdfDocumentItem
import com.blprkfgre.prettydamnfine.ui.components.ActionIconButton
import com.blprkfgre.prettydamnfine.ui.components.IconSizes
import com.blprkfgre.prettydamnfine.ui.components.PdfEmptyState
import com.blprkfgre.prettydamnfine.ui.components.Radii
import com.blprkfgre.prettydamnfine.ui.components.SectionHeader
import com.blprkfgre.prettydamnfine.ui.components.Spacing
import com.blprkfgre.prettydamnfine.ui.components.TouchTargets
import com.blprkfgre.prettydamnfine.ui.pdf.PdfViewerViewModel
import java.text.SimpleDateFormat
import java.util.*

private enum class SortMode { RECENT, OLDEST, NAME }

/** Suggests a clean filename for the Save-a-copy picker. */
private fun suggestedPdfName(title: String): String {
    val base = title.trim()
        .replace(Regex("[^\\w. -]"), "_")
        .replace(Regex("(?i)\\.pdf$"), "")
        .trim()
    return (if (base.isBlank()) "document" else base) + ".pdf"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    viewModel: PdfViewerViewModel,
    onOpenPdf: (Uri) -> Unit,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    onOpenStorage: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val documents by viewModel.libraryDocuments.collectAsState()
    val recentDocs by viewModel.recentDocuments.collectAsState()

    var query by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.RECENT) }
    var sortExpanded by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Uri>() }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<PdfDocumentItem?>(null) }

    LaunchedEffect(Unit) { viewModel.refreshLibrary() }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { onOpenPdf(it) } }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destination ->
        if (destination != null) pendingSave?.let { viewModel.saveDocumentAs(it.uri, destination) }
        pendingSave = null
    }

    val visibleDocs by remember(documents, query, sortMode) {
        derivedStateOf {
            val filtered = if (query.isBlank()) documents
            else documents.filter { it.title.contains(query, ignoreCase = true) }
            when (sortMode) {
                SortMode.RECENT -> filtered.sortedByDescending { it.lastAccessed }
                SortMode.OLDEST -> filtered.sortedBy { it.lastAccessed }
                SortMode.NAME -> filtered.sortedBy { it.title.lowercase() }
            }
        }
    }

    fun toggleSelection(item: PdfDocumentItem) {
        if (selected.contains(item.uri)) selected.remove(item.uri) else selected.add(item.uri)
        if (selected.isEmpty()) selectionMode = false
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text("Remove ${selected.size} from library?") },
            text = { Text("Only the library entries are removed. Your files stay on your device.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeDocumentsFromLibrary(selected.toList())
                    selected.clear()
                    selectionMode = false
                    confirmDelete = false
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${selected.size} selected") },
                    navigationIcon = {
                        ActionIconButton(
                            Icons.Filled.Close,
                            "Cancel selection",
                            onClick = {
                                selectionMode = false
                                selected.clear()
                            }
                        )
                    },
                    actions = {
                        val allSelected = visibleDocs.isNotEmpty() && selected.size == visibleDocs.size
                        ActionIconButton(
                            icon = if (allSelected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                            contentDescription = if (allSelected) "Deselect all" else "Select all",
                            onClick = {
                                if (allSelected) selected.clear()
                                else {
                                    selected.clear()
                                    selected.addAll(visibleDocs.map { it.uri })
                                }
                            }
                        )
                        ActionIconButton(
                            icon = Icons.Filled.Delete,
                            contentDescription = "Remove selected from library",
                            onClick = { confirmDelete = true },
                            enabled = selected.isNotEmpty()
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            } else {
                TopAppBar(
                    title = { Text("Pretty Damn Fine", style = MaterialTheme.typography.headlineSmall) },
                    actions = {
                        ActionIconButton(
                            icon = Icons.Filled.Folder,
                            contentDescription = "Manage storage",
                            onClick = onOpenStorage
                        )
                        ActionIconButton(
                            icon = if (isDark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                            contentDescription = if (isDark) "Switch to light theme" else "Switch to dark theme",
                            onClick = onToggleTheme
                        )
                        Box {
                            ActionIconButton(
                                Icons.AutoMirrored.Filled.Sort,
                                "Sort documents",
                                onClick = { sortExpanded = true }
                            )
                            DropdownMenu(
                                expanded = sortExpanded,
                                onDismissRequest = { sortExpanded = false }
                            ) {
                                SortMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                when (mode) {
                                                    SortMode.RECENT -> "Recently opened"
                                                    SortMode.OLDEST -> "Oldest first"
                                                    SortMode.NAME -> "Name (A–Z)"
                                                }
                                            )
                                        },
                                        onClick = { sortMode = mode; sortExpanded = false },
                                        leadingIcon = {
                                            if (sortMode == mode) Icon(Icons.Filled.Check, contentDescription = null)
                                        }
                                    )
                                }
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { launcher.launch(arrayOf("application/pdf")) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Open PDF") }
            )
        }
    ) { padding ->
        if (documents.isEmpty()) {
            PdfEmptyState(
                icon = Icons.Filled.PictureAsPdf,
                title = "Your library is empty",
                message = "Import a PDF to start reading.",
                actionLabel = "Import PDF",
                onAction = { launcher.launch(arrayOf("application/pdf")) },
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(
                    horizontal = Spacing.Md,
                    vertical = Spacing.Sm + Spacing.Xs
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.Sm)
            ) {
                if (!selectionMode && query.isBlank() && recentDocs.isNotEmpty()) {
                    item {
                        SectionHeader("Recent")
                        Spacer(Modifier.height(Spacing.Sm))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                            items(recentDocs.take(5)) { doc ->
                                RecentDocumentCard(doc, onClick = { onOpenPdf(doc.uri) })
                            }
                        }
                        Spacer(Modifier.height(Spacing.Sm))
                        SectionHeader("All documents")
                    }
                }

                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search documents") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotBlank()) {
                                ActionIconButton(Icons.Filled.Close, "Clear search", onClick = { query = "" })
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(Radii.Md),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                items(visibleDocs, key = { it.uri.toString() }) { doc ->
                    DocumentCard(
                        document = doc,
                        isSelected = selected.contains(doc.uri),
                        selectionMode = selectionMode,
                        onClick = {
                            if (selectionMode) toggleSelection(doc) else onOpenPdf(doc.uri)
                        },
                        onLongClick = {
                            selectionMode = true
                            if (!selected.contains(doc.uri)) selected.add(doc.uri)
                        },
                        onShare = { viewModel.shareDocument(doc.uri) },
                        onSave = {
                            pendingSave = doc
                            saveLauncher.launch(suggestedPdfName(doc.title))
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentDocumentCard(document: PdfDocumentItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .width(150.dp)
            .combinedClickable(onClick = onClick)
            .semantics { contentDescription = "Open ${document.title}" },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(Spacing.Md)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(78.dp)
                    .clip(RoundedCornerShape(Radii.Sm))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.PictureAsPdf,
                    contentDescription = null,
                    modifier = Modifier.size(IconSizes.Large),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(Spacing.Sm))
            Text(
                document.title,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentCard(
    document: PdfDocumentItem,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit
) {
    val date = remember(document.lastAccessed) {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(document.lastAccessed))
    }
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = "${document.title}, last opened $date" },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.padding(Spacing.Md), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(TouchTargets.Min)
                    .clip(RoundedCornerShape(Radii.Md))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        Icons.Filled.PictureAsPdf,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.width(Spacing.Md))
            Column(Modifier.weight(1f)) {
                Text(
                    document.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    date,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!selectionMode) {
                Box {
                    ActionIconButton(
                        icon = Icons.Filled.MoreVert,
                        contentDescription = "More options for ${document.title}",
                        onClick = { menuExpanded = true }
                    )
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Save a copy") },
                            leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                            onClick = { menuExpanded = false; onSave() }
                        )
                        DropdownMenuItem(
                            text = { Text("Share") },
                            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                            onClick = { menuExpanded = false; onShare() }
                        )
                    }
                }
            }
        }
    }
}
