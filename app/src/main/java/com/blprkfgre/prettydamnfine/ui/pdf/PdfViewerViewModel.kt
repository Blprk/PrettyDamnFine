package com.blprkfgre.prettydamnfine.ui.pdf

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blprkfgre.prettydamnfine.data.DocumentRepository
import com.blprkfgre.prettydamnfine.data.PdfDocumentItem
import com.blprkfgre.prettydamnfine.pdf.Bookmark
import com.blprkfgre.prettydamnfine.pdf.CharPos
import com.blprkfgre.prettydamnfine.pdf.OutlineItem
import com.blprkfgre.prettydamnfine.pdf.PdfEngine
import com.blprkfgre.prettydamnfine.pdf.ReadingTheme
import com.blprkfgre.prettydamnfine.pdf.SearchResult
import com.blprkfgre.prettydamnfine.pdf.TextExtractor
import com.blprkfgre.prettydamnfine.state.LibrarySelectionState
import com.blprkfgre.prettydamnfine.state.LibrarySortMode
import com.blprkfgre.prettydamnfine.state.NavigationRequest
import com.blprkfgre.prettydamnfine.state.OverlayState
import com.blprkfgre.prettydamnfine.state.ReadModeState
import com.blprkfgre.prettydamnfine.state.ReaderUiState
import com.blprkfgre.prettydamnfine.state.SearchState
import com.blprkfgre.prettydamnfine.state.SelectionState
import com.blprkfgre.prettydamnfine.state.TextExtractionState
import com.blprkfgre.prettydamnfine.state.ThemeState
import com.blprkfgre.prettydamnfine.state.ViewerState
import com.blprkfgre.prettydamnfine.ui.theme.AppThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Source of truth for the selection range; kept accessible as `ui.pdf.TextSelection`. */
typealias TextSelection = com.blprkfgre.prettydamnfine.state.TextSelection

/**
 * Owns the PDF engine and all reader modes.
 *
 * Every mode (viewer lifecycle, search, selection, overlays, themes, read mode,
 * library multi-select, navigation and extraction progress) lives in one
 * [ReaderUiState] snapshot. The public `StateFlow`s used by the existing UI are
 * *derived views* of that snapshot, so they can never disagree with each other.
 */
class PdfViewerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = DocumentRepository(application)
    private val textExtractor = TextExtractor()

    private val prefs = application.getSharedPreferences("pdf_viewer_prefs", Context.MODE_PRIVATE)

    // --- Single source of truth -------------------------------------------------

    private val _uiState = MutableStateFlow(
        ReaderUiState(
            theme = ThemeState(
                appTheme = when (prefs.getString("app_theme_mode", "SYSTEM")) {
                    "LIGHT" -> AppThemeMode.LIGHT
                    "DARK" -> AppThemeMode.DARK
                    else -> AppThemeMode.SYSTEM
                },
                reading = ReadingTheme.LIGHT
            )
        )
    )

    /** Immutable snapshot of every explicit state machine. Prefer this in new UI. */
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    /** Creates a legacy-compatible `StateFlow` view of one part of the snapshot. */
    private fun <T> derived(transform: (ReaderUiState) -> T): StateFlow<T> =
        uiState.map(transform).distinctUntilChanged().stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = transform(uiState.value)
        )

    // Explicit state views (for the UI agent / tooling).
    val viewerState: StateFlow<ViewerState> = derived { it.viewer }
    val searchState: StateFlow<SearchState> = derived { it.search }
    val selectionState: StateFlow<SelectionState> = derived { it.selection }
    val overlayState: StateFlow<OverlayState> = derived { it.overlay }
    val themeState: StateFlow<ThemeState> = derived { it.theme }
    val readModeState: StateFlow<ReadModeState> = derived { it.readMode }
    val librarySelectionState: StateFlow<LibrarySelectionState> = derived { it.librarySelection }
    val extractionState: StateFlow<TextExtractionState> = derived { it.extraction }
    val navigationRequest: StateFlow<NavigationRequest?> = derived { it.navigation }

    // --- Legacy compatibility views -------------------------------------------
    // Same names/types as before; all derive from the snapshot above.

    val engine: StateFlow<PdfEngine?> = derived { it.viewer.openEngine }
    val activeDocument: StateFlow<PdfDocumentItem?> = derived { it.viewer.openDocument }
    val passwordRequired: StateFlow<Boolean> = derived { it.viewer is ViewerState.PasswordRequired }
    val isReadMode: StateFlow<Boolean> = derived { it.readMode.enabled }
    val appThemeMode: StateFlow<AppThemeMode> = derived { it.theme.appTheme }
    val currentTheme: StateFlow<ReadingTheme> = derived { it.theme.reading }
    val showOutline: StateFlow<Boolean> = derived { it.overlay is OverlayState.Outline }
    val showOverview: StateFlow<Boolean> = derived { it.overlay is OverlayState.Overview }
    val isExtractingText: StateFlow<Boolean> = derived { it.extraction is TextExtractionState.Extracting }
    val searchQuery: StateFlow<String> = derived { it.search.queryOrNull ?: "" }
    val searchResults: StateFlow<List<SearchResult>> = derived { it.search.matchesOrEmpty }
    val currentSearchIndex: StateFlow<Int> = derived { it.search.indexOrMinusOne }
    val textSelection: StateFlow<TextSelection?> = derived { it.selection.rangeOrNull }
    val selectedText: StateFlow<String> = derived { it.selection.textOrEmpty }
    val navigateToPage: StateFlow<Int> = derived { it.navigation?.pageIndex ?: -1 }

    // Data (not modes) keep their own flows; they are unrelated to the state machines.
    val libraryDocuments = MutableStateFlow<List<PdfDocumentItem>>(emptyList())
    val recentDocuments = MutableStateFlow<List<PdfDocumentItem>>(emptyList())
    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()
    private val _outline = MutableStateFlow<List<OutlineItem>>(emptyList())
    val outline: StateFlow<List<OutlineItem>> = _outline.asStateFlow()
    private val _extractedText = MutableStateFlow<Map<Int, String>>(emptyMap())
    val extractedText: StateFlow<Map<Int, String>> = _extractedText.asStateFlow()
    private val _searchHighlights = MutableStateFlow<Map<Int, List<RectF>>>(emptyMap())
    val searchHighlights: StateFlow<Map<Int, List<RectF>>> = _searchHighlights.asStateFlow()
    private val _pageCharPositions = MutableStateFlow<Map<Int, List<CharPos>>>(emptyMap())
    val pageCharPositions: StateFlow<Map<Int, List<CharPos>>> = _pageCharPositions.asStateFlow()
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()
    private val _annotationRevision = MutableStateFlow(0)
    /** Bumped when annotations change, forcing affected pages to re-render. */
    val annotationRevision: StateFlow<Int> = _annotationRevision.asStateFlow()

    // Cancellable background work, tied to the currently open document.
    private var textExtractionJob: Job? = null
    private var outlineJob: Job? = null

    init {
        loadTheme()
        refreshLibrary()
        refreshRecentDocuments()
    }

    private fun loadTheme() {
        val themeName = prefs.getString("reading_theme", ReadingTheme.LIGHT.name) ?: ReadingTheme.LIGHT.name
        val reading = try {
            ReadingTheme.valueOf(themeName)
        } catch (e: Exception) {
            ReadingTheme.LIGHT
        }
        _uiState.update { it.copy(theme = it.theme.copy(reading = reading)) }
    }

    fun setTheme(theme: ReadingTheme) {
        _uiState.update { it.copy(theme = it.theme.copy(reading = theme)) }
        prefs.edit().putString("reading_theme", theme.name).apply()
    }

    fun cycleTheme() {
        val themes = ReadingTheme.entries
        val currentIndex = themes.indexOf(_uiState.value.theme.reading)
        setTheme(themes[(currentIndex + 1) % themes.size])
    }

    /** Convenience for new UI: resolves `AUTO` without duplicating the resolution rule. */
    fun effectiveReadingTheme(systemIsDark: Boolean): ReadingTheme =
        _uiState.value.theme.effectiveReadingTheme(systemIsDark)

    fun setAppThemeMode(mode: AppThemeMode) {
        _uiState.update { it.copy(theme = it.theme.copy(appTheme = mode)) }
        prefs.edit().putString("app_theme_mode", mode.name).apply()
    }

    fun toggleAppTheme(currentIsDark: Boolean) {
        setAppThemeMode(if (currentIsDark) AppThemeMode.LIGHT else AppThemeMode.DARK)
    }

    fun refreshLibrary() {
        libraryDocuments.value = repository.getDocuments()
    }

    fun refreshRecentDocuments() {
        recentDocuments.value = repository.getRecentDocuments()
    }

    // --- Viewer lifecycle -------------------------------------------------------

    fun openDocument(uri: Uri) {
        // Any work belonging to a previous document is now stale.
        cancelDocumentWork()
        try {
            _uiState.value.viewer.openEngine?.close()
        } catch (e: Exception) {
            // Ignore close errors
        }
        // Loading publishes no engine/document: the library stays visible while opening.
        _uiState.update { state ->
            state.copy(
                viewer = ViewerState.Loading(uri),
                overlay = OverlayState.None,
                search = SearchState.Off,
                selection = SelectionState.Idle,
                navigation = null,
                extraction = TextExtractionState.Idle
            )
        }
        _extractedText.value = emptyMap()
        _searchHighlights.value = emptyMap()
        _pageCharPositions.value = emptyMap()
        _bookmarks.value = emptyList()
        _outline.value = emptyList()

        viewModelScope.launch {
            try {
                // Files opened from other apps (WhatsApp, Gmail, Drive...) often grant
                // only a temporary read permission. Import a private copy so the document
                // stays in the library and Recent documents after a restart.
                val docItem = when {
                    repository.isInternalCopy(uri) || repository.hasDocument(uri) ->
                        repository.addDocument(uri)
                    else -> repository.importDocument(uri)
                }
                refreshLibrary()
                refreshRecentDocuments()

                val newEngine = PdfEngine.create(getApplication(), docItem.uri)
                when {
                    newEngine.needsPassword -> {
                        // Keep the library entry and ask for the password — do NOT treat an
                        // encrypted PDF as a stale/deleted file.
                        _uiState.update { state ->
                            state.copy(viewer = ViewerState.PasswordRequired(docItem, newEngine))
                        }
                        _toastMessage.value = "This PDF is password protected"
                    }
                    !newEngine.isOpen -> {
                        newEngine.close()
                        repository.removeDocument(docItem.uri)
                        refreshLibrary()
                        refreshRecentDocuments()
                        _uiState.update { state ->
                            state.copy(
                                viewer = ViewerState.Error(
                                    "This file is no longer available. Please open it again.",
                                    uri
                                )
                            )
                        }
                        _toastMessage.value = "This file is no longer available. Please open it again."
                    }
                    else -> prepareDocument(docItem, newEngine)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update { state ->
                    state.copy(viewer = ViewerState.Error("Error opening document: ${e.message}", uri))
                }
                _toastMessage.value = "Error opening document: ${e.message}"
            }
        }
    }

    /** Single transition into [ViewerState.Ready], used by open and by unlock. */
    private fun prepareDocument(docItem: PdfDocumentItem, newEngine: PdfEngine) {
        _uiState.update { state ->
            state.copy(
                viewer = ViewerState.Ready(docItem, newEngine),
                overlay = OverlayState.None,
                search = SearchState.Off,
                selection = SelectionState.Idle,
                navigation = null,
                extraction = TextExtractionState.Idle,
                readMode = if (prefs.getBoolean("read_mode", false)) ReadModeState.On else ReadModeState.Off
            )
        }
        _bookmarks.value = loadBookmarks(docItem.uri)
        syncBookmarksToEngine()
        _outline.value = emptyList()
        outlineJob = viewModelScope.launch {
            val items = textExtractor.extractOutline(newEngine)
            if ((_uiState.value.viewer as? ViewerState.Ready)?.engine === newEngine) {
                _outline.value = items
            }
        }

        _extractedText.value = emptyMap()
        _searchHighlights.value = emptyMap()
        _pageCharPositions.value = emptyMap()

        startTextExtraction(newEngine)
    }

    /** Authenticates an encrypted PDF; keeps it in the library either way. */
    fun authenticate(password: String): Boolean {
        val current = _uiState.value.viewer as? ViewerState.PasswordRequired ?: return false
        if (!current.engine.authenticate(password)) {
            _toastMessage.value = "Incorrect password"
            return false
        }
        prepareDocument(current.document, current.engine)
        return true
    }

    private fun cancelDocumentWork() {
        textExtractionJob?.cancel()
        outlineJob?.cancel()
        textExtractionJob = null
        outlineJob = null
    }

    private fun startTextExtraction(engine: PdfEngine) {
        textExtractionJob = viewModelScope.launch {
            _uiState.update { it.copy(extraction = TextExtractionState.Extracting) }
            var lastSearchResults = 0
            try {
                textExtractor.extractTextStreaming(engine) { page, text ->
                    val currentMap = _extractedText.value.toMutableMap()
                    currentMap[page] = text
                    _extractedText.value = currentMap

                    // Re-run the local search only when the number of matches can actually
                    // have changed. Previously this re-scanned every page on every page
                    // callback, which was O(n^2) for large documents.
                    val query = (_uiState.value.search as? SearchState.Active)?.query
                    if (!query.isNullOrBlank() && text.contains(query, ignoreCase = true)) {
                        val results = textExtractor.search(currentMap, query)
                        if (results.size != lastSearchResults) {
                            lastSearchResults = results.size
                            _uiState.update { state ->
                                state.copy(search = updatedResults(state.search, results))
                            }
                        }
                    }
                }
            } finally {
                // Cancellation must not leave the viewer stuck in "extracting".
                _uiState.update { state ->
                    if (state.extraction is TextExtractionState.Extracting) {
                        state.copy(extraction = TextExtractionState.Done)
                    } else {
                        state
                    }
                }
            }
        }
    }

    /** Replaces matches while keeping the query and a valid cursor. */
    private fun updatedResults(search: SearchState, results: List<SearchResult>): SearchState =
        when (search) {
            SearchState.Off -> SearchState.Off
            is SearchState.Active.NoResults ->
                if (results.isEmpty()) search
                else SearchState.Active.Results(search.query, results, 0)
            is SearchState.Active.Results -> when {
                results.isEmpty() -> SearchState.Active.NoResults(search.query)
                results.size == search.matches.size -> search
                else -> search.copy(
                    matches = results,
                    currentIndex = search.currentIndex.coerceIn(results.indices)
                )
            }
        }

    fun closeDocument() {
        cancelDocumentWork()
        _uiState.value.viewer.openEngine?.close()
        _uiState.update { state ->
            state.copy(
                viewer = ViewerState.Closed,
                overlay = OverlayState.None,
                search = SearchState.Off,
                selection = SelectionState.Idle,
                navigation = null,
                extraction = TextExtractionState.Idle
            )
        }
        _extractedText.value = emptyMap()
        _searchHighlights.value = emptyMap()
        _pageCharPositions.value = emptyMap()
        _bookmarks.value = emptyList()
        _outline.value = emptyList()
    }

    fun removeDocument(uri: Uri) {
        repository.removeDocument(uri)
        refreshLibrary()
        refreshRecentDocuments()
    }

    /**
     * Removes documents from the library only. The user's files on disk are never
     * deleted — only app-private import copies are cleaned up (see DocumentRepository).
     */
    fun removeDocumentsFromLibrary(uris: List<Uri>) {
        uris.forEach { repository.removeDocument(it) }
        refreshLibrary()
        refreshRecentDocuments()
        _uiState.update { it.copy(librarySelection = LibrarySelectionState.Idle) }
        _toastMessage.value = if (uris.size == 1) "Removed from library (file kept on device)"
        else "Removed ${uris.size} from library (files kept on device)"
    }

    // --- Library multi-select (explicit; UI can adopt these) --------------------

    fun setLibrarySelection(uris: Set<Uri>) {
        _uiState.update { state ->
            state.copy(
                librarySelection = if (uris.isEmpty()) {
                    LibrarySelectionState.Idle
                } else {
                    LibrarySelectionState.Selecting(uris)
                }
            )
        }
    }

    fun requestLibraryRemoval() {
        _uiState.update { state ->
            val uris = state.librarySelection.selectedUrisOrEmpty
            if (uris.isEmpty()) state
            else state.copy(librarySelection = LibrarySelectionState.ConfirmRemove(uris))
        }
    }

    fun cancelLibraryRemoval() {
        _uiState.update { state ->
            val uris = state.librarySelection.selectedUrisOrEmpty
            if (uris.isEmpty()) state.copy(librarySelection = LibrarySelectionState.Idle)
            else state.copy(librarySelection = LibrarySelectionState.Selecting(uris))
        }
    }

    fun clearLibrarySelection() {
        _uiState.update { it.copy(librarySelection = LibrarySelectionState.Idle) }
    }

    /** Exposed for a future UI migration; the current `LibraryScreen` owns its sort enum. */
    fun setLibrarySortMode(mode: LibrarySortMode) {
        _librarySortMode.value = mode
    }

    private val _librarySortMode = MutableStateFlow(LibrarySortMode.RECENT)
    val librarySortMode: StateFlow<LibrarySortMode> = _librarySortMode.asStateFlow()

    // --- Save / share -----------------------------------------------------------

    /**
     * Writes a copy of the currently open PDF to a location chosen by the user
     * (Downloads, Drive, etc.) via the Storage Access Framework. No permission needed.
     */
    fun saveCurrentDocumentAs(destination: Uri) {
        val engine = _uiState.value.viewer.openEngine ?: return
        engine.saveChanges()
        val source = File(engine.workingCopyPath())
        viewModelScope.launch(Dispatchers.IO) {
            try {
                writeToFile(source, destination)
                _toastMessage.value = "Saved to device"
            } catch (e: Exception) {
                e.printStackTrace()
                _toastMessage.value = "Could not save: ${e.message}"
            }
        }
    }

    /** Saves a copy of a library document to a user-chosen destination. */
    fun saveDocumentAs(documentUri: Uri, destination: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                openForRead(documentUri).use { input ->
                    val out = getApplication<Application>().contentResolver.openOutputStream(destination)
                        ?: throw IOException("Cannot write to the selected location")
                    out.use { input.copyTo(it, 64 * 1024) }
                }
                _toastMessage.value = "Saved to device"
            } catch (e: Exception) {
                e.printStackTrace()
                _toastMessage.value = "Could not save: ${e.message}"
            }
        }
    }

    private fun writeToFile(source: File, destination: Uri) {
        source.inputStream().use { input ->
            val out = getApplication<Application>().contentResolver.openOutputStream(destination)
                ?: throw IOException("Cannot write to the selected location")
            out.use { input.copyTo(it, 64 * 1024) }
        }
    }

    private fun openForRead(uri: Uri): InputStream =
        if (uri.scheme == "file") {
            File(uri.path ?: throw IOException("Invalid file path")).inputStream()
        } else {
            getApplication<Application>().contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot open the selected file")
        }

    fun toggleReadMode() {
        _uiState.update { state ->
            state.copy(
                readMode = if (state.readMode.enabled) ReadModeState.Off else ReadModeState.On
            )
        }
        prefs.edit().putBoolean("read_mode", _uiState.value.readMode.enabled).apply()
    }

    // --- Bookmarks (persisted per document) ---
    fun isPageBookmarked(pageIndex: Int): Boolean = _bookmarks.value.any { it.pageIndex == pageIndex }

    fun toggleBookmark(pageIndex: Int) {
        val current = _bookmarks.value.toMutableList()
        val existing = current.firstOrNull { it.pageIndex == pageIndex }
        if (existing != null) {
            current.remove(existing)
            _toastMessage.value = "Bookmark removed"
        } else {
            current.add(Bookmark(pageIndex, "Page ${pageIndex + 1}"))
            current.sortBy { it.pageIndex }
            _toastMessage.value = "Bookmark added"
        }
        _bookmarks.value = current
        saveBookmarks(current)
        syncBookmarksToEngine()
    }

    private fun bookmarksKey(uri: Uri): String = "bookmarks_${uri.toString().hashCode()}"

    private fun saveBookmarks(items: List<Bookmark>) {
        val uri = _uiState.value.viewer.openDocument?.uri ?: return
        val array = JSONArray()
        items.forEach { b ->
            val obj = org.json.JSONObject()
            obj.put("page", b.pageIndex)
            obj.put("title", b.title)
            obj.put("createdAt", b.createdAt)
            array.put(obj)
        }
        prefs.edit().putString(bookmarksKey(uri), array.toString()).apply()
    }

    private fun loadBookmarks(uri: Uri): List<Bookmark> {
        val raw = prefs.getString(bookmarksKey(uri), null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                Bookmark(
                    pageIndex = obj.getInt("page"),
                    title = obj.optString("title", "Page ${obj.getInt("page") + 1}"),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                )
            }.sortedBy { it.pageIndex }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun syncBookmarksToEngine() {
        val engine = _uiState.value.viewer.openEngine ?: return
        engine.clearBookmarks()
        _bookmarks.value.forEach { engine.addBookmark(it.pageIndex, it.title) }
    }

    fun goToBookmark(bookmark: Bookmark) {
        requestNavigateToPage(bookmark.pageIndex)
    }

    // --- Overlays ---------------------------------------------------------------

    /** New API: opens one overlay; only a Ready viewer can show overlays. */
    fun showOverlay(overlay: OverlayState) {
        if (_uiState.value.viewer !is ViewerState.Ready) return
        _uiState.update { it.copy(overlay = overlay) }
    }

    fun showPageJump() = showOverlay(OverlayState.PageJump)

    fun showThemeSheet() = showOverlay(OverlayState.ThemeSheet)

    fun dismissOverlay() {
        _uiState.update { it.copy(overlay = OverlayState.None) }
    }

    fun showPageOverview() = showOverlay(OverlayState.Overview)

    fun dismissOverview() {
        if (_uiState.value.overlay is OverlayState.Overview) dismissOverlay()
    }

    fun toggleOutline() {
        if (_uiState.value.viewer !is ViewerState.Ready) return
        _uiState.update { state ->
            state.copy(
                overlay = if (state.overlay is OverlayState.Outline) {
                    OverlayState.None
                } else {
                    OverlayState.Outline
                }
            )
        }
    }

    fun dismissOutline() {
        if (_uiState.value.overlay is OverlayState.Outline) dismissOverlay()
    }

    // --- Share ------------------------------------------------------------------

    /** Shares the app-private working copy via FileProvider so any app can read it. */
    fun shareDocument() {
        val engine = _uiState.value.viewer.openEngine ?: return
        // Flush unsaved annotations so the shared file matches what the user sees.
        engine.saveChanges()
        val context = getApplication<Application>()
        val file = java.io.File(engine.workingCopyPath())
        if (!file.exists()) return

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        launchShareChooser(context, uri)
    }

    /** Shares a document directly from the library (before it is opened). */
    fun shareDocument(uri: Uri) {
        val context = getApplication<Application>()
        if (!repository.isUriAccessible(uri)) {
            _toastMessage.value = "This file is no longer available"
            return
        }
        // Exposing a file:// URI to another app is blocked on modern Android. Always
        // hand out a FileProvider content:// URI for our own private copies.
        val shareUri = if (repository.isInternalCopy(uri)) {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                File(uri.path ?: return)
            )
        } else uri
        launchShareChooser(context, shareUri)
    }

    private fun launchShareChooser(context: Context, uri: Uri) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Share PDF")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    // --- Regular Search ---------------------------------------------------------

    fun performSearch(query: String) {
        val results = if (query.isBlank()) emptyList() else textExtractor.search(_extractedText.value, query)
        _searchHighlights.value = emptyMap()
        _uiState.update { state ->
            state.copy(
                search = if (query.isBlank()) {
                    SearchState.Off
                } else if (results.isEmpty()) {
                    SearchState.Active.NoResults(query)
                } else {
                    SearchState.Active.Results(query, results, 0)
                },
                // Starting a new search drops any active selection.
                selection = SelectionState.Idle
            )
        }

        if (results.isNotEmpty()) {
            val pageIndex = results[0].pageIndex
            requestNavigateToPage(pageIndex)
            fetchHighlightsForPage(pageIndex, query)
        }
    }

    private fun fetchHighlightsForPage(pageIndex: Int, query: String) {
        val engine = _uiState.value.viewer.openEngine ?: return
        if (query.isBlank()) return

        viewModelScope.launch {
            val rects = textExtractor.getPageMatchRects(engine, pageIndex, query)
            // Drop stale results if the query changed while extraction ran.
            if ((_uiState.value.search as? SearchState.Active)?.query != query) return@launch
            val currentHighlights = _searchHighlights.value.toMutableMap()
            currentHighlights[pageIndex] = rects
            _searchHighlights.value = currentHighlights
        }
    }

    fun nextSearchResult() = moveSearchResult(wrapForward = true)

    fun previousSearchResult() = moveSearchResult(wrapForward = false)

    private fun moveSearchResult(wrapForward: Boolean) {
        val active = _uiState.value.search as? SearchState.Active.Results ?: return
        val next = if (wrapForward) {
            (active.currentIndex + 1) % active.matches.size
        } else if (active.currentIndex <= 0) {
            active.matches.size - 1
        } else {
            active.currentIndex - 1
        }
        _uiState.update { state ->
            when (val search = state.search) {
                is SearchState.Active.Results -> state.copy(search = search.copy(currentIndex = next))
                else -> state
            }
        }
        val pageIndex = active.matches[next].pageIndex
        requestNavigateToPage(pageIndex)
        fetchHighlightsForPage(pageIndex, active.query)
    }

    // --- Selection --------------------------------------------------------------

    fun fetchCharPositionsIfNeeded(pageIndex: Int) {
        if (_pageCharPositions.value.containsKey(pageIndex)) return
        val engine = (_uiState.value.viewer as? ViewerState.Ready)?.engine ?: return
        // Guard against prefetching past the last page (MuPDF throws on an invalid index).
        if (pageIndex !in 0 until engine.pageCount) return

        viewModelScope.launch {
            try {
                val chars = textExtractor.getPageCharPositions(engine, pageIndex)
                val current = _pageCharPositions.value.toMutableMap()
                current[pageIndex] = chars
                _pageCharPositions.value = current

                val selectedPage = _uiState.value.selection.rangeOrNull?.pageIndex
                if (selectedPage == pageIndex) updateSelectedText()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setSelection(pageIndex: Int, start: Int, end: Int) {
        // No selection in Closed/Loading/Error/PasswordRequired states.
        if (_uiState.value.viewer !is ViewerState.Ready) return
        _uiState.update { it.copy(selection = SelectionState.Selecting(TextSelection(pageIndex, start, end))) }
        updateSelectedText()
    }

    private fun updateSelectedText() {
        val range = _uiState.value.selection.rangeOrNull ?: return
        val chars = _pageCharPositions.value[range.pageIndex]
        val sb = StringBuilder()
        if (chars != null) {
            val start = minOf(range.startCharIndex, range.endCharIndex)
            val end = maxOf(range.startCharIndex, range.endCharIndex)
            for (i in start..end) {
                if (i in chars.indices) sb.append(chars[i].c)
            }
        }
        val text = sb.toString().trim()
        _uiState.update { state ->
            state.copy(
                selection = when (val selection = state.selection) {
                    is SelectionState.Selecting ->
                        if (text.isBlank()) selection.copy(text = "")
                        else SelectionState.Actionable(selection.range, text)
                    is SelectionState.Actionable ->
                        if (text.isBlank()) SelectionState.Selecting(selection.range)
                        else selection.copy(text = text)
                    SelectionState.Idle -> selection
                }
            )
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selection = SelectionState.Idle) }
    }

    fun copySelection() {
        val text = _uiState.value.selection.textOrEmpty
        if (text.isNotBlank()) {
            val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("PDF Selection", text))
            _toastMessage.value = "Copied to clipboard"
            clearSelection()
        }
    }

    /** Creates a real PDF highlight annotation over the currently selected characters. */
    fun highlightSelection() {
        val selection = _uiState.value.selection.rangeOrNull ?: return
        val engine = _uiState.value.viewer.openEngine ?: return
        val chars = _pageCharPositions.value[selection.pageIndex] ?: return
        val start = minOf(selection.startCharIndex, selection.endCharIndex)
        val end = maxOf(selection.startCharIndex, selection.endCharIndex)
        val rects = (start..end).mapNotNull { chars.getOrNull(it)?.rect }
        if (rects.isEmpty()) {
            _toastMessage.value = "Could not highlight this selection"
            return
        }
        if (engine.addTextAnnotation(selection.pageIndex, rects)) {
            engine.invalidatePage(selection.pageIndex)
            _annotationRevision.value++
            _toastMessage.value = "Highlight added — save to keep it"
            clearSelection()
        } else {
            _toastMessage.value = "Could not highlight this selection"
        }
    }

    /** Removes a highlight covering the currently selected text. */
    fun removeHighlightFromSelection() {
        val selection = _uiState.value.selection.rangeOrNull ?: return
        val engine = _uiState.value.viewer.openEngine ?: return
        val chars = _pageCharPositions.value[selection.pageIndex] ?: return
        val start = minOf(selection.startCharIndex, selection.endCharIndex)
        val end = maxOf(selection.startCharIndex, selection.endCharIndex)
        val rects = (start..end).mapNotNull { chars.getOrNull(it)?.rect }
        val removed = engine.removeAnnotationsInRects(selection.pageIndex, rects)
        if (removed > 0) {
            engine.invalidatePage(selection.pageIndex)
            _annotationRevision.value++
            _toastMessage.value = "Highlight removed — save to keep the change"
            clearSelection()
        } else {
            _toastMessage.value = "No highlight found on this selection"
        }
    }

    /** Writes annotation edits back to the app-private working copy of the PDF. */
    fun saveAnnotations() {
        val engine = _uiState.value.viewer.openEngine ?: return
        _toastMessage.value = if (engine.saveChanges()) {
            "Annotations saved"
        } else {
            "No annotation changes to save"
        }
    }

    // --- Navigation -------------------------------------------------------------

    fun requestNavigateToPage(pageIndex: Int) {
        val engine = _uiState.value.viewer.openEngine ?: return
        if (pageIndex in 0 until engine.pageCount) {
            _uiState.update { it.copy(navigation = NavigationRequest(pageIndex)) }
        }
    }

    fun onNavigationConsumed() {
        _uiState.update { it.copy(navigation = null) }
    }

    // --- Copy Text ---------------------------------------------------------------

    fun copyPageText(pageIndex: Int) {
        val text = _extractedText.value[pageIndex]
        if (text.isNullOrBlank()) {
            _toastMessage.value = "No text found on this page"
            return
        }
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PDF Page Text", text))
        _toastMessage.value = "Page text copied to clipboard"
    }

    fun getPageText(pageIndex: Int): String = _extractedText.value[pageIndex] ?: ""

    fun clearToast() {
        _toastMessage.value = null
    }

    // --- Session persistence ----------------------------------------------------

    fun saveSession(uri: Uri, scrollIndex: Int, zoom: Float) {
        val keyBase = uri.toString().hashCode().toString()
        prefs.edit()
            .putInt("last_page_$keyBase", scrollIndex)
            .putFloat("last_zoom_$keyBase", zoom)
            .apply()
    }

    fun getSavedPage(uri: Uri): Int {
        val keyBase = uri.toString().hashCode().toString()
        return prefs.getInt("last_page_$keyBase", 0)
    }

    fun getSavedZoom(uri: Uri): Float {
        val keyBase = uri.toString().hashCode().toString()
        return prefs.getFloat("last_zoom_$keyBase", 1f)
    }

    override fun onCleared() {
        super.onCleared()
        cancelDocumentWork()
        _uiState.value.viewer.openEngine?.close()
    }
}
