package com.blprkfgre.prettydamnfine.pdf

import android.graphics.RectF
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.StructuredTextWalker
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

/** Text, search and outline extraction backed by the engine's single MuPDF document. */
class TextExtractor {

    fun extractOutline(engine: PdfEngine): List<OutlineItem> = engine.getOutline()

    /** Extracts text from all pages, yielding after each page so the UI stays responsive. */
    suspend fun extractTextStreaming(
        engine: PdfEngine,
        onPageExtracted: suspend (Int, String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val pageCount = engine.pageCount
        for (i in 0 until pageCount) {
            val text = engine.withDocument { doc ->
                val page = doc.loadPage(i)
                try {
                    val structured = page.toStructuredText()
                    try {
                        structured.asText()?.trim() ?: ""
                    } finally {
                        structured.destroy()
                    }
                } catch (e: Exception) {
                    ""
                } finally {
                    page.destroy()
                }
            } ?: ""
            onPageExtracted(i, text)
            yield()
        }
    }

    /** Precise character positions, normalised to 0..1 of the page box. */
    suspend fun getPageCharPositions(engine: PdfEngine, pageIndex: Int): List<CharPos> =
        withContext(Dispatchers.IO) {
            engine.withDocument { doc ->
                val page = doc.loadPage(pageIndex)
                val bounds = page.bounds
                val w = maxOf(1f, bounds.x1 - bounds.x0)
                val h = maxOf(1f, bounds.y1 - bounds.y0)
                val chars = mutableListOf<CharPos>()
                val walker = object : StructuredTextWalker {
                    override fun onImageBlock(rect: Rect, matrix: com.artifex.mupdf.fitz.Matrix, image: Image) {}
                    override fun beginTextBlock(rect: Rect, b: Int) {}
                    override fun endTextBlock() {}
                    override fun beginLine(rect: Rect, b: Int, dir: Point) {}
                    override fun endLine() {}
                    override fun onChar(
                        unicode: Int,
                        origin: Point,
                        font: Font,
                        size: Float,
                        quad: Quad,
                        c: Int,
                        d: Int,
                        e: Int
                    ) {
                        val r = quad.toRect()
                        chars.add(
                            CharPos(
                                unicode.toChar(),
                                RectF(
                                    (r.x0 - bounds.x0) / w,
                                    (r.y0 - bounds.y0) / h,
                                    (r.x1 - bounds.x0) / w,
                                    (r.y1 - bounds.y0) / h
                                ),
                                chars.size
                            )
                        )
                    }
                    override fun beginStruct(name: String, value: String, b: Int) {}
                    override fun endStruct() {}
                    override fun onVector(rect: Rect, info: StructuredTextWalker.VectorInfo, b: Int) {}
                }
                val structured = page.toStructuredText()
                try {
                    structured.walk(walker)
                } finally {
                    structured.destroy()
                    page.destroy()
                }
                chars
            } ?: emptyList()
        }

    /** Precise normalised rectangles for every occurrence of [query] on a page. */
    suspend fun getPageMatchRects(
        engine: PdfEngine,
        pageIndex: Int,
        query: String
    ): List<RectF> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        engine.withDocument { doc ->
            val page = doc.loadPage(pageIndex)
            val bounds = page.bounds
            val w = maxOf(1f, bounds.x1 - bounds.x0)
            val h = maxOf(1f, bounds.y1 - bounds.y0)
            try {
                val hits = page.search(query, StructuredText.SEARCH_IGNORE_CASE) ?: emptyArray()
                hits.mapNotNull { quads ->
                    if (quads.isEmpty()) null
                    else {
                        var x0 = Float.MAX_VALUE
                        var y0 = Float.MAX_VALUE
                        var x1 = -Float.MAX_VALUE
                        var y1 = -Float.MAX_VALUE
                        quads.forEach { q ->
                            val r = q.toRect()
                            x0 = minOf(x0, r.x0); y0 = minOf(y0, r.y0)
                            x1 = maxOf(x1, r.x1); y1 = maxOf(y1, r.y1)
                        }
                        RectF(
                            (x0 - bounds.x0) / w,
                            (y0 - bounds.y0) / h,
                            (x1 - bounds.x0) / w,
                            (y1 - bounds.y0) / h
                        )
                    }
                }
            } catch (e: Exception) {
                emptyList()
            } finally {
                page.destroy()
            }
        } ?: emptyList()
    }

    /** Searches the cached plain text for a query string. */
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
