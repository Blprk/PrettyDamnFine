package com.blprkfgre.prettydamnfine.ui.pdf

import android.content.Intent
import android.net.Uri
import android.util.Patterns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.blprkfgre.prettydamnfine.pdf.PdfEngine
import com.blprkfgre.prettydamnfine.pdf.ReadingTheme
import com.blprkfgre.prettydamnfine.ui.components.IconSizes
import com.blprkfgre.prettydamnfine.ui.components.PdfSearchTopBar
import com.blprkfgre.prettydamnfine.ui.components.PdfTopBar
import com.blprkfgre.prettydamnfine.ui.components.SelectionToolbar
import com.blprkfgre.prettydamnfine.ui.components.Spacing
import com.blprkfgre.prettydamnfine.ui.components.ViewerBottomBar
import com.blprkfgre.prettydamnfine.ui.components.ViewerChrome

private fun suggestedPdfName(title: String?): String {
    val base = (title ?: "document").trim()
        .replace(Regex("[^\\w. -]"), "_")
        .replace(Regex("(?i)\\.pdf$"), "")
        .trim()
    return (if (base.isBlank()) "document" else base) + ".pdf"
}

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
    val passwordRequired by viewModel.passwordRequired.collectAsState()

    // "Auto" is a user-facing choice; resolve it to an actual rendering theme.
    val effectiveTheme = if (currentTheme == ReadingTheme.AUTO) {
        if (isSystemInDarkTheme()) ReadingTheme.DARK else ReadingTheme.LIGHT
    } else {
        currentTheme
    }

    val context = LocalContext.current

    // Save-a-copy: the system file picker lets the user choose Downloads or any
    // storage provider. No storage permission is required (Storage Access Framework).
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destination -> if (destination != null) viewModel.saveCurrentDocumentAs(destination) }

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

    // Prefetch selection data for the visible page so the FIRST long-press can select
    // text instead of just warming up character positions.
    LaunchedEffect(currentPage, engine?.uri) {
        if (engine == null) return@LaunchedEffect
        viewModel.fetchCharPositionsIfNeeded(currentPage)
        viewModel.fetchCharPositionsIfNeeded(currentPage + 1)
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var viewportWidth by remember { mutableFloatStateOf(0f) }
    var viewportHeight by remember { mutableFloatStateOf(0f) }

    /**
     * Changes zoom while keeping the document point under the viewport centre
     * anchored. Without this, scaling around the centre pushes the text you were
     * reading off-screen and leaves a blank page visible.
     */
    fun zoomTo(target: Float) {
        val newScale = target.coerceIn(1f, 5f)
        val factor = newScale / scale
        scale = newScale
        // Anchor the TOP-LEFT of the viewport (not its centre). This is a
        // vertical reader: the line you were reading should stay on screen
        // instead of being pushed off into blank space.
        val centreX = viewportWidth / 2f
        val centreY = viewportHeight / 2f
        offsetX = if (isReadMode) 0f else (centreX + offsetX) * factor - centreX
        offsetY = (centreY + offsetY) * factor - centreY
        if (newScale <= 1f) {
            offsetX = 0f
            offsetY = 0f
            return
        }
        val maxX = (viewportWidth * newScale - viewportWidth) / 2f
        val maxY = (viewportHeight * newScale - viewportHeight) / 2f
        offsetX = if (isReadMode) 0f else offsetX.coerceIn(-maxX, maxX)
        offsetY = offsetY.coerceIn(-maxY, maxY)
    }

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
            // Page aspect ratios resolve asynchronously on first open. Re-align
            // once sizes have settled so a jump (incl. a PDF link) lands exactly.
            kotlinx.coroutines.delay(200)
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
                PdfSearchTopBar(
                    query = searchQuery,
                    onQueryChange = { viewModel.performSearch(it) },
                    resultCount = searchResults.size,
                    currentIndex = currentSearchIndex,
                    onClose = {
                        isSearchMode = false
                        viewModel.performSearch("")
                    },
                    onPrevious = viewModel::previousSearchResult,
                    onNext = viewModel::nextSearchResult
                )
            } else {
                PdfTopBar(
                    title = activeDoc?.title ?: "Document",
                    currentPage = currentPage,
                    pageCount = currentEngine.pageCount,
                    onBack = onBack,
                    onOpenPageJump = { showPageJump = true },
                    onSaveCopy = { saveLauncher.launch(suggestedPdfName(activeDoc?.title)) },
                    onShare = { viewModel.shareDocument() },
                    onSearch = { isSearchMode = true },
                    onThemeSettings = { showThemeSelector = true }
                )
            }
        },
        floatingActionButton = {
            // Hidden while text is selected so it can never collide with the
            // selection toolbar, which occupies the same bottom band.
            if (!isSearchMode && textSelection == null) {
                FloatingActionButton(
                    onClick = { viewModel.toggleReadMode() },
                    modifier = Modifier.padding(bottom = ViewerChrome.FloatingBottomInset),
                    containerColor = if (isReadMode)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (isReadMode)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurface
                ) {
                    Icon(
                        imageVector = if (isReadMode) Icons.Filled.Lock else Icons.Filled.Star,
                        contentDescription = if (isReadMode) "Exit read mode" else "Enter read mode",
                        modifier = Modifier.size(IconSizes.Standard)
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

                LaunchedEffect(maxWidth, maxHeight) {
                    viewportWidth = maxWidth
                    viewportHeight = maxHeight
                }

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
                                // Pass the real zoom so the page is rendered at the
                                // displayed resolution instead of an upscaled 1x bitmap.
                                scale = scale,
                                theme = effectiveTheme,
                                highlights = searchHighlights[index] ?: emptyList(),
                                charPositions = pageCharPositions[index] ?: emptyList(),
                                selection = if (textSelection?.pageIndex == index) textSelection else null,
                                onSelectionChange = { start, end -> viewModel.setSelection(index, start, end) },
                                onRequestCharPositions = { viewModel.fetchCharPositionsIfNeeded(index) },
                                onClearSelection = { viewModel.clearSelection() },
                                onLinkClick = { link ->
                                    when {
                                        link.targetPage != null ->
                                            viewModel.requestNavigateToPage(link.targetPage)
                                        !link.uri.isNullOrBlank() -> {
                                            val parsed = Uri.parse(link.uri)
                                            if (parsed.scheme in setOf("http", "https", "mailto", "tel")) {
                                                try {
                                                    context.startActivity(Intent(Intent.ACTION_VIEW, parsed))
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "Cannot open link", Toast.LENGTH_SHORT).show()
                                                }
                                            } else {
                                                Toast.makeText(context, "Unsupported link", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
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
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                thickness = 4.dp
                            )
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
                SelectionToolbar(
                    canCall = Patterns.PHONE.matcher(selectedText).matches(),
                    canEmail = Patterns.EMAIL_ADDRESS.matcher(selectedText).matches(),
                    onCall = {
                        val clean = selectedText.replace(Regex("[^0-9+]"), "")
                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$clean")))
                    },
                    onEmail = {
                        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$selectedText")))
                    },
                    onCopy = { viewModel.copySelection() },
                    onHighlight = { viewModel.highlightSelection() },
                    onRemoveHighlight = { viewModel.removeHighlightFromSelection() },
                    onClose = { viewModel.clearSelection() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(
                            start = Spacing.Md,
                            end = Spacing.Md,
                            bottom = ViewerChrome.FloatingBottomInset
                        )
                )
            }

            // Bottom contextual bar — outline, zoom and save-annotations.
            ViewerBottomBar(
                scale = scale,
                onOutline = { viewModel.toggleOutline() },
                onZoomOut = { zoomTo(scale - 0.5f) },
                onZoomIn = { zoomTo(scale + 0.5f) },
                onSaveAnnotations = { viewModel.saveAnnotations() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = ViewerChrome.BarBottomInset)
            )
        }
    }

    if (passwordRequired) {
        PasswordDialog(
            onAuthenticate = { viewModel.authenticate(it) },
            onDismiss = { viewModel.closeDocument() }
        )
    }
}

@Composable
private fun PasswordDialog(onAuthenticate: (String) -> Boolean, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
        title = { Text("Password protected PDF") },
        text = {
            Column {
                Text("Enter the password to open this document.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { onAuthenticate(password) }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAuthenticate(password) }) { Text("Unlock") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
