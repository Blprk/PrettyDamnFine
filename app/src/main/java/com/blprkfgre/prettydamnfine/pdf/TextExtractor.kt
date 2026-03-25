package com.blprkfgre.prettydamnfine.pdf

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext

data class SearchResult(
    val pageIndex: Int,
    val snippet: String,
    val matchOffset: Int,
    val rects: List<RectF> = emptyList()
)

data class CharPos(
    val c: Char,
    val rect: RectF,
    val index: Int
)

class TextExtractor(private val context: Context) {

    private var initialized = false

    private fun ensureInitialized() {
        if (!initialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            initialized = true
        }
    }

    /**
     * Extracts text from all pages in the background, yielding after each page.
     */
    suspend fun extractTextStreaming(
        uri: Uri,
        pageCount: Int,
        onPageExtracted: suspend (Int, String) -> Unit
    ) = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                PDDocument.load(inputStream).use { document ->
                    val stripper = PDFTextStripper()
                    for (i in 0 until minOf(pageCount, document.numberOfPages)) {
                        try {
                            stripper.startPage = i + 1
                            stripper.endPage = i + 1
                            val text = stripper.getText(document).trim()
                            onPageExtracted(i, text)
                        } catch (e: Exception) {
                            onPageExtracted(i, "")
                        }
                        yield()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Get precise character positions for a page.
     * Used for text selection and hit-testing actionable links.
     */
    suspend fun getPageCharPositions(uri: Uri, pageIndex: Int): List<CharPos> = withContext(Dispatchers.IO) {
        ensureInitialized()
        val charList = mutableListOf<CharPos>()

        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                PDDocument.load(inputStream).use { document ->
                    val stripper = object : PDFTextStripper() {
                        override fun writeString(string: String, textPositions: List<TextPosition>) {
                            // Loop through text positions and map to CharPos
                            for (i in textPositions.indices) {
                                val tp = textPositions[i]
                                val char = string.getOrElse(i) { ' ' }
                                
                                val x = tp.xDirAdj
                                val y = tp.yDirAdj - tp.heightDir // Top of the character
                                val w = tp.widthDirAdj
                                val h = tp.heightDir
                                
                                charList.add(CharPos(char, RectF(x, y, x + w, y + h), charList.size))
                            }
                        }
                    }
                    
                    stripper.startPage = pageIndex + 1
                    stripper.endPage = pageIndex + 1
                    stripper.getText(document)
                    
                    // Normalize coordinates to 0..1 based on MediaBox
                    val page = document.getPage(pageIndex)
                    val mediaBox = page.mediaBox
                    val pageWidth = mediaBox.width
                    val pageHeight = mediaBox.height
                    
                    val normalizedChars = charList.map { 
                        it.copy(
                            rect = RectF(
                                it.rect.left / pageWidth,
                                it.rect.top / pageHeight,
                                it.rect.right / pageWidth,
                                it.rect.bottom / pageHeight
                            )
                        )
                    }
                    return@withContext normalizedChars
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        emptyList()
    }

    /**
     * Finds the precise coordinates of a query on a specific page.
     */
    suspend fun getPageMatchRects(uri: Uri, pageIndex: Int, query: String): List<RectF> = withContext(Dispatchers.IO) {
        ensureInitialized()
        val rects = mutableListOf<RectF>()
        if (query.isBlank()) return@withContext rects

        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                PDDocument.load(inputStream).use { document ->
                    val stripper = object : PDFTextStripper() {
                        val lowerQuery = query.lowercase()
                        
                        override fun writeString(string: String, textPositions: List<TextPosition>) {
                            val lowerString = string.lowercase()
                            var startIdx = 0
                            while (true) {
                                val idx = lowerString.indexOf(lowerQuery, startIdx)
                                if (idx == -1) break
                                
                                if (idx + lowerQuery.length <= textPositions.size) {
                                    val matchPositions = textPositions.subList(idx, idx + lowerQuery.length)
                                    if (matchPositions.isNotEmpty()) {
                                        var minX = Float.MAX_VALUE
                                        var minY = Float.MAX_VALUE
                                        var maxX = Float.MIN_VALUE
                                        var maxY = Float.MIN_VALUE
                                        
                                        for (tp in matchPositions) {
                                            minX = minOf(minX, tp.xDirAdj)
                                            minY = minOf(minY, tp.yDirAdj - tp.heightDir)
                                            maxX = maxOf(maxX, tp.xDirAdj + tp.widthDirAdj)
                                            maxY = maxOf(maxY, tp.yDirAdj)
                                        }
                                        
                                        rects.add(RectF(minX, minY, maxX, maxY))
                                    }
                                }
                                startIdx = idx + 1
                            }
                        }
                    }
                    
                    stripper.startPage = pageIndex + 1
                    stripper.endPage = pageIndex + 1
                    stripper.getText(document)
                    
                    val page = document.getPage(pageIndex)
                    val mediaBox = page.mediaBox
                    val pageWidth = mediaBox.width
                    val pageHeight = mediaBox.height
                    
                    val normalizedRects = rects.map { r ->
                        RectF(
                            r.left / pageWidth,
                            r.top / pageHeight,
                            r.right / pageWidth,
                            r.bottom / pageHeight
                        )
                    }
                    return@withContext normalizedRects
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        emptyList()
    }

    /**
     * Searches cached text for a query string.
     */
    fun search(cachedText: Map<Int, String>, query: String): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        val results = mutableListOf<SearchResult>()
        val lowerQuery = query.lowercase()

        for ((pageIndex, text) in cachedText.entries.sortedBy { it.key }) {
            val lowerText = text.lowercase()
            var startIdx = 0
            while (true) {
                val idx = lowerText.indexOf(lowerQuery, startIdx)
                if (idx == -1) break

                val snippetStart = maxOf(0, idx - 30)
                val snippetEnd = minOf(text.length, idx + query.length + 30)
                val snippet = (if (snippetStart > 0) "…" else "") +
                        text.substring(snippetStart, snippetEnd).replace('\n', ' ') +
                        (if (snippetEnd < text.length) "…" else "")

                results.add(SearchResult(pageIndex, snippet, idx))
                startIdx = idx + 1
            }
        }
        return results
    }
}
