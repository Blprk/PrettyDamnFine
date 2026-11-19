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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blprkfgre.prettydamnfine.data.PdfDocumentItem
import com.blprkfgre.prettydamnfine.ui.pdf.PdfViewerViewModel
import java.text.SimpleDateFormat
import java.util.*

private enum class SortMode { RECENT, OLDEST, NAME }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    viewModel: PdfViewerViewModel,
    onOpenPdf: (Uri) -> Unit,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
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

    LaunchedEffect(Unit) { viewModel.refreshLibrary() }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { onOpenPdf(it) } }

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
            icon = { Icon(Icons.Filled.Delete, null) },
            title = { Text("Delete ${selected.size} file(s)?") },
            text = { Text("This removes the files from your device and from the library. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteDocumentsForever(selected.toList())
                    selected.clear()
                    selectionMode = false
                    confirmDelete = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
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
                        IconButton(onClick = {
                            selectionMode = false
                            selected.clear()
                        }) { Icon(Icons.Filled.Close, "Cancel selection") }
                    },
                    actions = {
                        IconButton(onClick = {
                            if (selected.size == visibleDocs.size) selected.clear()
                            else { selected.clear(); selected.addAll(visibleDocs.map { it.uri }) }
                        }) { Icon(Icons.Filled.SelectAll, "Select all") }
                        IconButton(
                            onClick = { confirmDelete = true },
                            enabled = selected.isNotEmpty()
                        ) { Icon(Icons.Filled.Delete, "Delete selected") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            } else {
                TopAppBar(
                    title = { Text("Pretty Damn Fine", style = MaterialTheme.typography.headlineSmall) },
                    actions = {
                        IconButton(onClick = onToggleTheme) {
                            Icon(
                                if (isDark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                                if (isDark) "Light mode" else "Dark mode"
                            )
                        }
                        Box {
                            IconButton(onClick = { sortExpanded = true }) {
                                Icon(Icons.Filled.Sort, "Sort")
                            }
                            DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                                SortMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(when (mode) {
                                            SortMode.RECENT -> "Recently opened"
                                            SortMode.OLDEST -> "Oldest first"
                                            SortMode.NAME -> "Name (A–Z)"
                                        }) },
                                        onClick = { sortMode = mode; sortExpanded = false },
                                        leadingIcon = {
                                            if (sortMode == mode) Icon(Icons.Filled.Check, null)
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
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Open PDF") }
            )
        }
    ) { padding ->
        if (documents.isEmpty()) {
            EmptyLibraryState(
                modifier = Modifier.fillMaxSize().padding(padding),
                onImportClick = { launcher.launch(arrayOf("application/pdf")) }
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!selectionMode && query.isBlank() && recentDocs.isNotEmpty()) {
                    item {
                        Text("Recent", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp))
                        Spacer(Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(recentDocs.take(5)) { doc ->
                                RecentDocumentCard(doc, onClick = { onOpenPdf(doc.uri) })
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("All documents", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }

                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search documents") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        trailingIcon = {
                            if (query.isNotBlank()) IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, "Clear search")
                            }
                        },
                        singleLine = true,
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
                        onShare = { viewModel.shareDocument(doc.uri) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryState(modifier: Modifier, onImportClick: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.PictureAsPdf, null,
            modifier = Modifier.size(88.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
        Spacer(Modifier.height(20.dp))
        Text("Your library is empty", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Import a PDF to start reading", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        FilledTonalButton(onClick = onImportClick) {
            Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("Import PDF")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentDocumentCard(document: PdfDocumentItem, onClick: () -> Unit) {
    Card(
        Modifier.width(150.dp).combinedClickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            Box(
                Modifier.fillMaxWidth().height(78.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.PictureAsPdf, null, modifier = Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            Spacer(Modifier.height(8.dp))
            Text(document.title, style = MaterialTheme.typography.labelMedium, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
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
    onShare: () -> Unit
) {
    val date = remember(document.lastAccessed) {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(document.lastAccessed))
    }
    Card(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) Icon(Icons.Filled.CheckCircle, null,
                    tint = MaterialTheme.colorScheme.primary)
                else Icon(Icons.Filled.PictureAsPdf, null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(document.title, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(date, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!selectionMode) {
                IconButton(onClick = onShare) { Icon(Icons.Filled.Share, "Share") }
            }
        }
    }
}
