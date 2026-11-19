package com.blprkfgre.prettydamnfine.ui.pdf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Patterns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.blprkfgre.prettydamnfine.pdf.PdfEngine
import com.blprkfgre.prettydamnfine.pdf.ReadingTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    viewModel: PdfViewerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val engine by viewModel.engine.collectAsState()
    val isReadMode by viewModel.isReadMode.collectAsState()
    val activeDoc by viewModel.activeDocument.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val currentSearchIndex by viewModel.currentSearchIndex.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val navigateToPage by viewModel.navigateToPage.collectAsState()
    val showOverview by viewModel.showOverview.collectAsState()
    val searchHighlights by viewModel.searchHighlights.collectAsState()
    val textSelection by viewModel.textSelection.collectAsState()
    val selectedText by viewModel.selectedText.collectAsState()
    val pageCharPositions by viewModel.pageCharPositions.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    val outline by viewModel.outline.collectAsState()
    val currentTheme by viewModel.currentTheme.collectAsState()
    val showOutline by viewModel.showOutline.collectAsState()
    val annotationRevision by viewModel.annotationRevision.collectAsState()

    // "Auto" is a user-facing choice; resolve it to an actual rendering theme.
    val effectiveTheme = if (currentTheme == ReadingTheme.AUTO) {
        if (isSystemInDarkTheme()) ReadingTheme.DARK else ReadingTheme.LIGHT
    } else {
        currentTheme
    }
    
    val context = LocalContext.current

    var isSearchMode by remember { mutableStateOf(false) }
    var showPageJump by remember { mutableStateOf(false) }
    var showThemeSelector by remember { mutableStateOf(false) }
    var selectionToolbarVisible by remember { mutableStateOf(false) }

    // Delay the selection toolbar so the tap/drag that finishes a selection can
    // never land on its Copy button and silently "copy" the text.
    LaunchedEffect(textSelection) {
        selectionToolbarVisible = false
        if (textSelection != null) {
            kotlinx.coroutines.delay(350)
            selectionToolbarVisible = true
        }
    }

    // Back gesture: dismiss the current layer before leaving the document.
    // Dialogs and modal bottom sheets dismiss themselves via onDismissRequest.
    val overlayIsOpen = showPageJump || showThemeSelector || showOutline || showOverview
    BackHandler(enabled = true) {
        when {
            overlayIsOpen -> Unit
            isSearchMode -> {
                isSearchMode = false
                viewModel.performSearch("")
            }
            textSelection != null -> viewModel.clearSelection()
            else -> onBack()
        }
    }

    val listState = rememberLazyListState()

    // derivedStateOf keeps the page indicator (and everything that reads currentPage)
    // from recomposing on every scroll frame — only when the page actually changes.
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Read Mode locks horizontal movement: re-center whenever it is enabled.
    LaunchedEffect(isReadMode) {
        if (isReadMode) offsetX = 0f
    }

    // Restore session when engine first becomes available
    LaunchedEffect(engine?.uri) {
        val uri = engine?.uri ?: return@LaunchedEffect
        val savedPage = viewModel.getSavedPage(uri)
        val savedZoom = viewModel.getSavedZoom(uri)
        scale = savedZoom
        if (savedPage > 0) {
            listState.scrollToItem(savedPage)
        }
    }

    // Handle navigation to page
    LaunchedEffect(navigateToPage) {
        if (navigateToPage >= 0) {
            listState.scrollToItem(navigateToPage)
            viewModel.onNavigationConsumed()
        }
    }

    // Save session on scroll/zoom changes (debounced)
    LaunchedEffect(listState.firstVisibleItemIndex, scale) {
        val uri = engine?.uri ?: return@LaunchedEffect
        kotlinx.coroutines.delay(500)
        viewModel.saveSession(uri, listState.firstVisibleItemIndex, scale)
    }

    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    if (engine == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No PDF Loaded", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    val currentEngine = engine!!

    if (showPageJump) {
        PageJumpDialog(
            currentPage = currentPage,
            totalPages = currentEngine.pageCount,
            onDismiss = { showPageJump = false },
            onJump = { viewModel.requestNavigateToPage(it) }
        )
    }

    if (showOverview) {
        PageOverviewSheet(
            engine = currentEngine,
            currentPage = currentPage,
            theme = effectiveTheme,
            onPageSelected = { viewModel.requestNavigateToPage(it) },
            onDismiss = { viewModel.dismissOverview() }
        )
    }

    if (showOutline) {
        OutlineSheet(
            outline = outline,
            onItemSelected = { item ->
                viewModel.requestNavigateToPage(item.pageIndex)
                viewModel.dismissOutline()
            },
            onDismiss = { viewModel.dismissOutline() }
        )
    }

    if (showThemeSelector) {
        ThemeSelectorSheet(
            currentTheme = currentTheme,
            onThemeSelected = { theme ->
                viewModel.setTheme(theme)
                showThemeSelector = false
            },
            onDismiss = { showThemeSelector = false }
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            if (isSearchMode) {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.performSearch(it) },
                            placeholder = { Text("Search…") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().focusRequester(FocusRequester()),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {}),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent
                            )
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            isSearchMode = false
                            viewModel.performSearch("")
                        }) { Icon(Icons.Default.Close, null) }
                    },
                    actions = {
                        if (searchResults.isNotEmpty()) {
                            Text("${currentSearchIndex + 1}/${searchResults.size}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp))
                        }
                        IconButton(onClick = viewModel::previousSearchResult, enabled = searchResults.isNotEmpty()) { Icon(Icons.Default.KeyboardArrowUp, null) }
                        IconButton(onClick = viewModel::nextSearchResult, enabled = searchResults.isNotEmpty()) { Icon(Icons.Default.KeyboardArrowDown, null) }
                    }
                )
            } else {
                TopAppBar(
                    title = { 
                        Column(modifier = Modifier.clickable { showPageJump = true }) {
                            Text(activeDoc?.title ?: "Document", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text("Page ${currentPage + 1} of ${currentEngine.pageCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.shareDocument() }) {
                            Icon(Icons.Filled.Share, "Share")
                        }
                        IconButton(onClick = { isSearchMode = true }) { Icon(Icons.Default.Search, "Search text") }
                        IconButton(onClick = { showThemeSelector = true }) { Icon(Icons.Default.Settings, "Settings") }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!isSearchMode && textSelection == null) {
                FloatingActionButton(
                    onClick = { viewModel.toggleReadMode() },
                    modifier = Modifier.padding(bottom = 96.dp),
                    containerColor = if (isReadMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Icon(
                        if (isReadMode) Icons.Filled.Lock else Icons.Filled.Star,
                        if (isReadMode) "Exit read mode" else "Read mode"
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                val maxWidth = constraints.maxWidth.toFloat()
                val maxHeight = constraints.maxHeight.toFloat()

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(isReadMode) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)

                                val maxOffsetY = (maxHeight * scale - maxHeight) / 2
                                offsetY = if (scale > 1f) {
                                    (offsetY + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                                } else 0f

                                // Read Mode locks horizontal movement to the page center.
                                offsetX = if (!isReadMode && scale > 1f) {
                                    val maxOffsetX = (maxWidth * scale - maxWidth) / 2
                                    (offsetX + pan.x).coerceIn(-maxOffsetX, maxOffsetX)
                                } else 0f
                            }
                        }
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offsetX
                                translationY = offsetY
                            },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        items(currentEngine.pageCount) { index ->
                            PdfPage(
                                engine = currentEngine,
                                pageIndex = index,
                                baseWidthPx = maxWidth.toInt(),
                                scale = 1f,
                                theme = effectiveTheme,
                                highlights = searchHighlights[index] ?: emptyList(),
                                charPositions = pageCharPositions[index] ?: emptyList(),
                                selection = if (textSelection?.pageIndex == index) textSelection else null,
                                onSelectionChange = { start, end -> viewModel.setSelection(index, start, end) },
                                onRequestCharPositions = { viewModel.fetchCharPositionsIfNeeded(index) },
                                onClearSelection = { viewModel.clearSelection() },
                                revision = annotationRevision,
                                onDoubleTap = { pos ->
                                    if (scale > 1f) {
                                        scale = 1f
                                        offsetX = 0f
                                        offsetY = 0f
                                    } else {
                                        val newScale = 2.5f
                                        scale = newScale
                                        val maxOffsetX = (maxWidth * newScale - maxWidth) / 2
                                        val maxOffsetY = (maxHeight * newScale - maxHeight) / 2
                                        offsetX = ((maxWidth / 2 - pos.x) * (newScale - 1f)).coerceIn(-maxOffsetX, maxOffsetX)
                                        offsetY = ((maxHeight / 2 - pos.y) * (newScale - 1f)).coerceIn(-maxOffsetY, maxOffsetY)
                                        if (isReadMode) offsetX = 0f
                                    }
                                }
                            )
                            HorizontalDivider(color = Color.DarkGray, thickness = 4.dp)
                        }
                    }
                }
            }

            // Fast scrubbing is only useful for longer documents.
            if (currentEngine.pageCount > 3) {
                FastScrollbar(
                    listState = listState,
                    totalItems = currentEngine.pageCount,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(vertical = 88.dp)
                )
            }

            // Text selection toolbar — a proper contextual panel, not floating buttons.
            if (textSelection != null && selectedText.isNotBlank() && selectionToolbarVisible) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 96.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 3.dp,
                    shadowElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (Patterns.PHONE.matcher(selectedText).matches()) {
                            IconButton(onClick = {
                                val clean = selectedText.replace(Regex("[^0-9+]"), "")
                                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$clean")))
                            }) { Icon(Icons.Filled.Phone, "Call") }
                        }
                        if (Patterns.EMAIL_ADDRESS.matcher(selectedText).matches()) {
                            IconButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$selectedText")))
                            }) { Icon(Icons.Filled.Email, "Email") }
                        }
                        IconButton(onClick = { viewModel.copySelection() }) {
                            Icon(Icons.Filled.ContentCopy, "Copy selection")
                        }
                        IconButton(onClick = { viewModel.highlightSelection() }) {
                            Icon(Icons.Filled.Edit, "Highlight selection")
                        }
                        IconButton(onClick = { viewModel.removeHighlightFromSelection() }) {
                            Icon(Icons.Filled.Delete, "Remove highlight")
                        }
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Filled.Close, "Clear selection")
                        }
                    }
                }
            }

            // Floating Pill Overlay
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .height(56.dp)
                    .wrapContentWidth(),
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(onClick = { viewModel.toggleOutline() }) {
                        Icon(Icons.AutoMirrored.Filled.List, "Outline", tint = MaterialTheme.colorScheme.onSurface)
                    }

                    VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp))

                    IconButton(onClick = { 
                        scale = (scale - 0.5f).coerceIn(1f, 5f)
                        if (scale <= 1f) { offsetX = 0f; offsetY = 0f }
                    }) {
                        Icon(Icons.Filled.Remove, "Zoom out", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    
                    Text(
                        text = "${(scale * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(48.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    IconButton(onClick = { 
                        scale = (scale + 0.5f).coerceIn(1f, 5f)
                    }) {
                        Icon(Icons.Filled.Add, "Zoom in", tint = MaterialTheme.colorScheme.onSurface)
                    }

                    VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp))

                    IconButton(onClick = { viewModel.shareDocument() }) {
                        Icon(Icons.Filled.Share, "Share", tint = MaterialTheme.colorScheme.onSurface)
                    }

                    VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp))

                    IconButton(onClick = { viewModel.saveAnnotations() }) {
                        Icon(Icons.Filled.Save, "Save annotations", tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}
