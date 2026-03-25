package com.blprkfgre.prettydamnfine.ui.pdf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Patterns
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.blprkfgre.prettydamnfine.ai.SemanticSearchResult
import com.blprkfgre.prettydamnfine.pdf.PdfEngine
import com.blprkfgre.prettydamnfine.pdf.ReadingTheme
import com.blprkfgre.prettydamnfine.ui.settings.SettingsRepository

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
    val bookmarks by viewModel.bookmarks.collectAsState()
    val outline by viewModel.outline.collectAsState()
    val currentTheme by viewModel.currentTheme.collectAsState()
    val showOutline by viewModel.showOutline.collectAsState()
    
    // Semantic search state
    val semanticSearchResults by viewModel.semanticSearchResults.collectAsState()
    val isSemanticSearching by viewModel.isSemanticSearching.collectAsState()
    val semanticSearchError by viewModel.semanticSearchError.collectAsState()
    val showSemanticSearch by viewModel.showSemanticSearch.collectAsState()
    
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }

    var isSearchMode by remember { mutableStateOf(false) }
    var showPageJump by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showThemeSelector by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

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

    // Show semantic search dialog
    if (showSemanticSearch) {
        SemanticSearchDialog(
            isSearching = isSemanticSearching,
            results = semanticSearchResults,
            error = semanticSearchError,
            onSearch = { query -> viewModel.performSemanticSearch(query) },
            onDismiss = { viewModel.dismissSemanticSearch() },
            onClearError = { viewModel.clearSemanticSearchError() },
            onModelChange = { model -> viewModel.setModel(model) },
            currentModel = settingsRepository.selectedModel
        )
    }

    if (engine == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No PDF Loaded", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    val currentEngine = engine!!
    val currentPage = listState.firstVisibleItemIndex

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
            onPageSelected = { viewModel.requestNavigateToPage(it) },
            onDismiss = { viewModel.dismissOverview() }
        )
    }

    if (showBookmarks) {
        BookmarksSheet(
            bookmarks = bookmarks,
            currentPage = currentPage,
            onBookmarkSelected = { bookmark ->
                viewModel.goToBookmark(bookmark)
                showBookmarks = false
            },
            onDismiss = { showBookmarks = false }
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
                        IconButton(onClick = { isSearchMode = true }) { Icon(Icons.Default.Search, "Search text") }
                        IconButton(onClick = { showThemeSelector = true }) { Icon(Icons.Default.Settings, "Settings") }
                    }
                )
            }
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 72.dp) // Move FABs higher to clear the floating pill
            ) {
                // Semantic search FAB
                if (!isSearchMode && textSelection == null) {
                    FloatingActionButton(
                        onClick = { viewModel.showSemanticSearch() },
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "AI Search")
                    }
                }
                
                // Text selection FABs
                if (textSelection != null && selectedText.isNotBlank()) {
                    // Phone button
                    if (Patterns.PHONE.matcher(selectedText).matches()) {
                        SmallFloatingActionButton(
                            onClick = { 
                                val cleanPhone = selectedText.replace(Regex("[^0-9+]"), "")
                                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanPhone")))
                            },
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ) { Icon(Icons.Default.Phone, null) }
                    }
                    // Email button
                    if (Patterns.EMAIL_ADDRESS.matcher(selectedText).matches()) {
                        SmallFloatingActionButton(
                            onClick = { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$selectedText"))) },
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ) { Icon(Icons.Default.Email, null) }
                    }
                    // Copy button
                    ExtendedFloatingActionButton(
                        onClick = { viewModel.copySelection() },
                        icon = { Icon(Icons.Default.Check, null) },
                        text = { Text("Copy") }
                    )
                } else if (!isSearchMode && textSelection == null) {
                    FloatingActionButton(
                        onClick = { viewModel.toggleReadMode() },
                        containerColor = if (isReadMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    ) { Icon(if (isReadMode) Icons.Default.Lock else Icons.Default.Star, null) }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF2A2A2A))
            ) {
                val maxWidth = constraints.maxWidth.toFloat()
                val maxHeight = constraints.maxHeight.toFloat()

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                
                                if (scale > 1f) {
                                    // Calculate max allowed offsets to keep edges clamped
                                    // Since we scale from the center, the content expands by (scale - 1) * dimension / 2 on each side
                                    val maxOffsetX = (maxWidth * scale - maxWidth) / 2
                                    val maxOffsetY = (maxHeight * scale - maxHeight) / 2
                                    
                                    offsetX = (offsetX + pan.x).coerceIn(-maxOffsetX, maxOffsetX)
                                    offsetY = (offsetY + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
                                }
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
                                theme = currentTheme,
                                highlights = searchHighlights[index] ?: emptyList(),
                                charPositions = pageCharPositions[index] ?: emptyList(),
                                selection = if (textSelection?.pageIndex == index) textSelection else null,
                                onSelectionChange = { start, end -> viewModel.setSelection(index, start, end) },
                                onRequestCharPositions = { viewModel.fetchCharPositionsIfNeeded(index) },
                                onClearSelection = { viewModel.clearSelection() }
                            )
                            HorizontalDivider(color = Color.DarkGray, thickness = 4.dp)
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
                    
                    // View bookmarks button
                    IconButton(onClick = { showBookmarks = true }) {
                        Icon(
                            imageVector = Icons.Default.FavoriteBorder,
                            contentDescription = "View Bookmarks",
                            tint = if (bookmarks.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    
                    VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp))

                    IconButton(onClick = { 
                        scale = (scale - 0.5f).coerceIn(1f, 5f)
                        if (scale <= 1f) { offsetX = 0f; offsetY = 0f }
                    }) { 
                        Text("-", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
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
                        Text("+", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                    }

                    VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp))

                    IconButton(onClick = { viewModel.toggleBookmark(currentPage) }) { 
                        Icon(
                            imageVector = if (currentEngine.isBookmarked(currentPage)) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Bookmark",
                            tint = if (currentEngine.isBookmarked(currentPage)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SemanticSearchDialog(
    isSearching: Boolean,
    results: List<SemanticSearchResult>,
    error: String?,
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit,
    onClearError: () -> Unit,
    onModelChange: (String) -> Unit,
    currentModel: String
) {
    var query by remember { mutableStateOf("") }
    var showModelSelector by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    
    val availableModels = SettingsRepository.AVAILABLE_MODELS
    val currentModelName = availableModels.find { it.id == currentModel }?.name ?: "Llama 3.1 8B"
    
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(error) {
        error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            onClearError()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header with model selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "AI Semantic Search",
                        style = MaterialTheme.typography.titleLarge
                    )
                    TextButton(onClick = { showModelSelector = true }) {
                        Text(
                            text = currentModelName,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Select model",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                
                Text(
                    text = "Ask questions about your PDF using natural language",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("e.g., What is this document about?") },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { 
                            if (query.isNotBlank()) {
                                onSearch(query)
                            }
                        }
                    ),
                    enabled = !isSearching,
                    trailingIcon = {
                        if (isSearching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                )
                
                Button(
                    onClick = { 
                        if (query.isNotBlank()) {
                            onSearch(query)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSearching && query.isNotBlank()
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Search with AI")
                }
                
                if (results.isNotEmpty()) {
                    HorizontalDivider()
                    
                    results.forEach { result ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Page ${result.pageNumber}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    TextButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AI Result", result.content))
                                            Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Text("Copy")
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 200.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        text = result.content,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
                
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Close")
                }
            }
        }
    }
    
    // Model selector dialog
    if (showModelSelector) {
        AlertDialog(
            onDismissRequest = { showModelSelector = false },
            title = { Text("Select AI Model") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    availableModels.forEach { model ->
                        TextButton(
                            onClick = {
                                onModelChange(model.id)
                                showModelSelector = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = model.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (model.id == currentModel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = model.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelSelector = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
