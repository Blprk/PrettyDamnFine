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
import com.blprkfgre.prettydamnfine.ai.GroqSearchService
import com.blprkfgre.prettydamnfine.ai.SemanticSearchResult
import com.blprkfgre.prettydamnfine.data.DocumentRepository
import com.blprkfgre.prettydamnfine.data.PdfDocumentItem
import com.blprkfgre.prettydamnfine.pdf.Bookmark
import com.blprkfgre.prettydamnfine.pdf.CharPos
import com.blprkfgre.prettydamnfine.pdf.OutlineItem
import com.blprkfgre.prettydamnfine.pdf.PdfEngine
import com.blprkfgre.prettydamnfine.pdf.ReadingTheme
import com.blprkfgre.prettydamnfine.pdf.SearchResult
import com.blprkfgre.prettydamnfine.pdf.TextExtractor
import com.blprkfgre.prettydamnfine.ui.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TextSelection(
    val pageIndex: Int,
    val startCharIndex: Int,
    val endCharIndex: Int
)

class PdfViewerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = DocumentRepository(application)
    private val textExtractor = TextExtractor(application)
    private val settingsRepository = SettingsRepository(application)
    private val groqSearchService = GroqSearchService(application)

    val libraryDocuments = MutableStateFlow<List<PdfDocumentItem>>(emptyList())

    private val _engine = MutableStateFlow<PdfEngine?>(null)
    val engine = _engine.asStateFlow()

    private val _activeDocument = MutableStateFlow<PdfDocumentItem?>(null)
    val activeDocument = _activeDocument.asStateFlow()

    private val prefs = application.getSharedPreferences("pdf_viewer_prefs", Context.MODE_PRIVATE)

    private val _isReadMode = MutableStateFlow(false)
    val isReadMode = _isReadMode.asStateFlow()

    private val _currentTheme = MutableStateFlow(ReadingTheme.LIGHT)
    val currentTheme = _currentTheme.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks = _bookmarks.asStateFlow()

    private val _outline = MutableStateFlow<List<OutlineItem>>(emptyList())
    val outline = _outline.asStateFlow()

    private val _showOutline = MutableStateFlow(false)
    val showOutline = _showOutline.asStateFlow()

    // --- Text & Search ---
    private val _extractedText = MutableStateFlow<Map<Int, String>>(emptyMap())
    val extractedText = _extractedText.asStateFlow()

    private val _isExtractingText = MutableStateFlow(false)
    val isExtractingText = _isExtractingText.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults = _searchResults.asStateFlow()

    private val _currentSearchIndex = MutableStateFlow(-1)
    val currentSearchIndex = _currentSearchIndex.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _searchHighlights = MutableStateFlow<Map<Int, List<RectF>>>(emptyMap())
    val searchHighlights = _searchHighlights.asStateFlow()

    // --- Semantic Search ---
    private val _semanticSearchResults = MutableStateFlow<List<SemanticSearchResult>>(emptyList())
    val semanticSearchResults = _semanticSearchResults.asStateFlow()

    private val _isSemanticSearching = MutableStateFlow(false)
    val isSemanticSearching = _isSemanticSearching.asStateFlow()

    private val _semanticSearchError = MutableStateFlow<String?>(null)
    val semanticSearchError = _semanticSearchError.asStateFlow()

    private val _showSemanticSearch = MutableStateFlow(false)
    val showSemanticSearch = _showSemanticSearch.asStateFlow()

    // --- Selection ---
    private val _textSelection = MutableStateFlow<TextSelection?>(null)
    val textSelection = _textSelection.asStateFlow()

    private val _selectedText = MutableStateFlow("")
    val selectedText = _selectedText.asStateFlow()

    private val _pageCharPositions = MutableStateFlow<Map<Int, List<CharPos>>>(emptyMap())
    val pageCharPositions = _pageCharPositions.asStateFlow()

    // --- Navigation ---
    private val _navigateToPage = MutableStateFlow(-1)
    val navigateToPage = _navigateToPage.asStateFlow()

    // --- Overview ---
    private val _showOverview = MutableStateFlow(false)
    val showOverview = _showOverview.asStateFlow()

    // --- Clipboard feedback ---
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage = _toastMessage.asStateFlow()

    // --- Recent documents ---
    val recentDocuments = MutableStateFlow<List<PdfDocumentItem>>(emptyList())

    init {
        // Load library and theme on startup
        loadTheme()
        refreshLibrary()
        refreshRecentDocuments()
    }

    private fun loadTheme() {
        val themeName = prefs.getString("reading_theme", ReadingTheme.LIGHT.name) ?: ReadingTheme.LIGHT.name
        _currentTheme.value = try {
            ReadingTheme.valueOf(themeName)
        } catch (e: Exception) {
            ReadingTheme.LIGHT
        }
    }

    fun setTheme(theme: ReadingTheme) {
        _currentTheme.value = theme
        prefs.edit().putString("reading_theme", theme.name).apply()
    }

    fun cycleTheme() {
        val themes = ReadingTheme.entries
        val currentIndex = themes.indexOf(_currentTheme.value)
        val nextIndex = (currentIndex + 1) % themes.size
        setTheme(themes[nextIndex])
    }

    fun refreshLibrary() {
        libraryDocuments.value = repository.getDocuments()
    }

    fun refreshRecentDocuments() {
        recentDocuments.value = repository.getRecentDocuments()
    }

    fun openDocument(uri: Uri) {
        try {
            _engine.value?.close()
        } catch (e: Exception) {
            // Ignore close errors
        }

        viewModelScope.launch {
            try {
                val docItem = repository.addDocument(uri)
                refreshLibrary()
                refreshRecentDocuments()

                val newEngine = PdfEngine(getApplication(), uri)
                _activeDocument.value = docItem
                _engine.value = newEngine
                _isReadMode.value = prefs.getBoolean("read_mode", false)

                _bookmarks.value = newEngine.getBookmarks()
                _outline.value = newEngine.getOutline()

                _extractedText.value = emptyMap()
                _searchResults.value = emptyList()
                _currentSearchIndex.value = -1
                _searchQuery.value = ""
                _searchHighlights.value = emptyMap()
                _textSelection.value = null
                _selectedText.value = ""
                _pageCharPositions.value = emptyMap()
                _semanticSearchResults.value = emptyList()
                _showSemanticSearch.value = false

                startTextExtraction(uri, newEngine.pageCount)
            } catch (e: Exception) {
                e.printStackTrace()
                _toastMessage.value = "Error opening document: ${e.message}"
            }
        }
    }

    private fun startTextExtraction(uri: Uri, pageCount: Int) {
        viewModelScope.launch {
            _isExtractingText.value = true
            textExtractor.extractTextStreaming(uri, pageCount) { page, text ->
                val currentMap = _extractedText.value.toMutableMap()
                currentMap[page] = text
                _extractedText.value = currentMap
                
                if (_searchQuery.value.isNotBlank()) {
                    val results = textExtractor.search(currentMap, _searchQuery.value)
                    _searchResults.value = results
                }
            }
            _isExtractingText.value = false
        }
    }
    

    fun closeDocument() {
        _engine.value?.close()
        _engine.value = null
        _activeDocument.value = null
        _extractedText.value = emptyMap()
        _searchResults.value = emptyList()
        _currentSearchIndex.value = -1
        _searchQuery.value = ""
        _searchHighlights.value = emptyMap()
        _textSelection.value = null
        _selectedText.value = ""
        _pageCharPositions.value = emptyMap()
        _bookmarks.value = emptyList()
        _outline.value = emptyList()
        _showOverview.value = false
        _semanticSearchResults.value = emptyList()
        _showSemanticSearch.value = false
    }

    fun removeDocument(uri: Uri) {
        repository.removeDocument(uri)
        refreshLibrary()
        refreshRecentDocuments()
    }

    fun toggleReadMode() {
        _isReadMode.value = !_isReadMode.value
        prefs.edit().putBoolean("read_mode", _isReadMode.value).apply()
    }

    // --- Bookmarks ---
    fun toggleBookmark(pageIndex: Int) {
        val engine = _engine.value ?: return
        if (engine.isBookmarked(pageIndex)) {
            engine.removeBookmark(pageIndex)
            _toastMessage.value = "Bookmark removed"
        } else {
            engine.addBookmark(pageIndex, "Page ${pageIndex + 1}")
            _toastMessage.value = "Bookmark added"
        }
        _bookmarks.value = engine.getBookmarks()
    }

    fun goToBookmark(bookmark: Bookmark) {
        requestNavigateToPage(bookmark.pageIndex)
    }

    // --- Overview ---
    fun showPageOverview() {
        _showOverview.value = true
    }

    fun dismissOverview() {
        _showOverview.value = false
    }

    // --- Outline ---
    fun toggleOutline() {
        _showOutline.value = !_showOutline.value
    }

    fun dismissOutline() {
        _showOutline.value = false
    }

    // --- Share ---
    fun shareDocument() {
        val engine = _engine.value ?: return
        val context = getApplication<Application>()
        
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, engine.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        val chooser = Intent.createChooser(shareIntent, "Share PDF")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    // --- Regular Search ---
    fun performSearch(query: String) {
        _searchQuery.value = query
        _searchHighlights.value = emptyMap()
        clearSelection()

        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _currentSearchIndex.value = -1
            return
        }
        val results = textExtractor.search(_extractedText.value, query)
        _searchResults.value = results
        _currentSearchIndex.value = if (results.isNotEmpty()) 0 else -1

        if (results.isNotEmpty()) {
            val pageIndex = results[0].pageIndex
            requestNavigateToPage(pageIndex)
            fetchHighlightsForPage(pageIndex, query)
        }
    }

    private fun fetchHighlightsForPage(pageIndex: Int, query: String) {
        val engine = _engine.value ?: return
        if (query.isBlank()) return
        
        viewModelScope.launch {
            val rects = textExtractor.getPageMatchRects(engine.uri, pageIndex, query)
            val currentHighlights = _searchHighlights.value.toMutableMap()
            currentHighlights[pageIndex] = rects
            _searchHighlights.value = currentHighlights
        }
    }

    fun nextSearchResult() {
        val results = _searchResults.value
        if (results.isEmpty()) return
        val next = (_currentSearchIndex.value + 1) % results.size
        _currentSearchIndex.value = next
        val pageIndex = results[next].pageIndex
        requestNavigateToPage(pageIndex)
        fetchHighlightsForPage(pageIndex, _searchQuery.value)
    }

    fun previousSearchResult() {
        val results = _searchResults.value
        if (results.isEmpty()) return
        val prev = if (_currentSearchIndex.value <= 0) results.size - 1 else _currentSearchIndex.value - 1
        _currentSearchIndex.value = prev
        val pageIndex = results[prev].pageIndex
        requestNavigateToPage(pageIndex)
        fetchHighlightsForPage(pageIndex, _searchQuery.value)
    }

    // --- Semantic Search ---
    fun showSemanticSearch() {
        _showSemanticSearch.value = true
    }

    fun dismissSemanticSearch() {
        _showSemanticSearch.value = false
    }

    fun performSemanticSearch(query: String) {
        if (query.isBlank()) return
        
        val apiKey = settingsRepository.apiKey
        if (apiKey.isBlank()) {
            _semanticSearchError.value = "Please set your Groq API key in Settings first"
            return
        }

        val pageTexts = _extractedText.value
        val extractedCount = pageTexts.size
        
        if (extractedCount == 0) {
            _semanticSearchError.value = "Please wait for the document to load completely"
            return
        }
        
        // Show warning if extraction is still in progress
        val engine = _engine.value
        if (engine != null && extractedCount < engine.pageCount && _isExtractingText.value) {
            _semanticSearchError.value = "Still loading... ($extractedCount/${engine.pageCount} pages). Try again soon."
            return
        }

        viewModelScope.launch {
            _isSemanticSearching.value = true
            _semanticSearchError.value = null
            
            val model = settingsRepository.selectedModel
            
            val result = groqSearchService.semanticSearch(
                apiKey = apiKey,
                query = query,
                pageTexts = pageTexts,
                model = model
            )
            
            result.onSuccess { results ->
                _semanticSearchResults.value = results
                if (results.isNotEmpty()) {
                    // Navigate only if answer was found
                    val rawPage = results[0].pageNumber
                    val engine = _engine.value
                    val maxPage = engine?.pageCount ?: 1
                    
                    // Only navigate if page is valid and AI actually found something
                    val content = results[0].content.lowercase()
                    val foundAnswer = !content.contains("could not find") && 
                                      !content.contains("not found") && 
                                      !content.contains("don't have") &&
                                      !content.contains("no information")
                    
                    if (foundAnswer && rawPage in 1..maxPage) {
                        val pageIndex = (rawPage - 1).coerceIn(0, maxPage - 1)
                        requestNavigateToPage(pageIndex)
                    }
                }
            }.onFailure { error ->
                _semanticSearchError.value = error.message ?: "Search failed"
            }
            
            _isSemanticSearching.value = false
        }
    }

    // --- Selection ---
    fun fetchCharPositionsIfNeeded(pageIndex: Int) {
        if (_pageCharPositions.value.containsKey(pageIndex)) return
        val engine = _engine.value ?: return
        
        viewModelScope.launch {
            val chars = textExtractor.getPageCharPositions(engine.uri, pageIndex)
            val current = _pageCharPositions.value.toMutableMap()
            current[pageIndex] = chars
            _pageCharPositions.value = current
            
            if (_textSelection.value?.pageIndex == pageIndex) {
                updateSelectedText()
            }
        }
    }
    
    fun setSelection(pageIndex: Int, start: Int, end: Int) {
        _textSelection.value = TextSelection(pageIndex, start, end)
        updateSelectedText()
    }
    
    private fun updateSelectedText() {
        val sel = _textSelection.value
        val chars = _pageCharPositions.value[sel?.pageIndex]
        if (sel == null || chars == null) {
            _selectedText.value = ""
            return
        }
        
        val sb = StringBuilder()
        val start = minOf(sel.startCharIndex, sel.endCharIndex)
        val end = maxOf(sel.startCharIndex, sel.endCharIndex)
        for (i in start..end) {
            if (i in chars.indices) {
                sb.append(chars[i].c)
            }
        }
        _selectedText.value = sb.toString().trim()
    }
    
    fun clearSelection() {
        _textSelection.value = null
        _selectedText.value = ""
    }
    
    fun copySelection() {
        val text = _selectedText.value
        if (text.isNotBlank()) {
            val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("PDF Selection", text))
            _toastMessage.value = "Copied to clipboard"
            clearSelection()
        }
    }

    // --- Navigation ---
    fun requestNavigateToPage(pageIndex: Int) {
        val engine = _engine.value ?: return
        if (pageIndex in 0 until engine.pageCount) {
            _navigateToPage.value = pageIndex
        }
    }

    fun onNavigationConsumed() {
        _navigateToPage.value = -1
    }

    // --- Copy Text ---
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

    fun getPageText(pageIndex: Int): String {
        return _extractedText.value[pageIndex] ?: ""
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    fun clearSemanticSearchError() {
        _semanticSearchError.value = null
    }
    
    fun setModel(model: String) {
        settingsRepository.selectedModel = model
    }

    // --- Session persistence ---
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
        _engine.value?.close()
    }
}
