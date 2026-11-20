package com.blprkfgre.prettydamnfine.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.LruCache
import com.artifex.mupdf.fitz.Cookie
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Link
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import android.graphics.RectF
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

enum class ReadingTheme {
    LIGHT, DARK, SEPIA, AUTO
}

data class Bookmark(
    val pageIndex: Int,
    val title: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class OutlineItem(
    val title: String,
    val pageIndex: Int,
    val level: Int = 0,
    val children: List<OutlineItem> = emptyList()
)

/**
 * A clickable area on a page. Coordinates are normalised to 0..1 of the page box,
 * matching the convention used for search results and character positions.
 *
 * Exactly one of [targetPage] (a link inside this document) or [uri] (an external
 * link, e.g. a web URL) is normally set.
 */
data class PdfLink(
    val bounds: RectF,
    val targetPage: Int? = null,
    val uri: String? = null
)

/**
 * MuPDF-backed PDF engine.
 *
 * MuPDF native objects are not thread-safe, so every native call is made under
 * [nativeLock]. The coroutine [mutex] additionally protects the bitmap caches.
 */
class PdfEngine private constructor(
    private val context: Context,
    val uri: Uri,
    private val cacheFile: File
) {
    private var document: Document? = null
    private val nativeLock = Any()
    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val aspectRatioCache = mutableMapOf<Int, Float>()
    private var thumbnailCache: LruCache<Int, Bitmap>? = null
    private var bitmapCache: LruCache<String, Bitmap>? = null
    private val prefetchJobs = ConcurrentHashMap<Int, Job>()

    private val bookmarks = mutableListOf<Bookmark>()
    private var outline: List<OutlineItem> = emptyList()

    init {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = minOf(maxMemory / 8, 48 * 1024)

        bitmapCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount / 1024
        }
        thumbnailCache = object : LruCache<Int, Bitmap>(100) {
            override fun sizeOf(key: Int, bitmap: Bitmap): Int = bitmap.byteCount / 1024
        }
    }

    /** Opens the (already cached) file. */
    private fun open() {
        synchronized(nativeLock) {
            document = Document.openDocument(cacheFile.absolutePath)
            outline = buildOutline()
        }
    }

    companion object {
        /** Tile edge length used for high-resolution zoomed rendering. */
        private const val TILE_SIZE = 1024

        /**
         * Opens a URI with MuPDF. Content URIs are copied into app-private storage so
         * annotations can be saved; already-imported file:// URIs are opened directly
         * instead of being copied a second time.
         */
        suspend fun create(context: Context, uri: Uri): PdfEngine = withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "pdf").apply { mkdirs() }
            val internalDir = dir.absolutePath + File.separator
            val file = if (uri.scheme == "file" && uri.path?.startsWith(internalDir) == true) {
                File(uri.path!!)
            } else {
                val target = File(dir, "${uri.toString().hashCode()}.pdf")
                if (!target.exists() || target.length() == 0L) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IOException("Cannot open the selected file")
                    FileOutputStream(target).use { out ->
                        input.use { it.copyTo(out, 64 * 1024) }
                    }
                }
                target
            }
            val engine = PdfEngine(context, uri, file)
            engine.open()
            engine
        }
    }

    /** Raw document handle, for TextExtractor. Only touch it under [withDocument]. */
    internal val rawDocument: Document? get() = document

    internal fun <T> withDocument(block: (Document) -> T): T? {
        val doc = document ?: return null
        return synchronized(nativeLock) { block(doc) }
    }

    val needsPassword: Boolean
        get() = synchronized(nativeLock) { document?.needsPassword() ?: false }

    fun authenticate(password: String): Boolean {
        return synchronized(nativeLock) {
            val ok = document?.authenticatePassword(password) ?: false
            if (ok) outline = buildOutline()
            ok
        }
    }

    val pageCount: Int
        get() = if (needsPassword) 0 else synchronized(nativeLock) {
            try { document?.countPages() ?: 0 } catch (e: Exception) { 0 }
        }

    val isOpen: Boolean get() = document != null && !needsPassword && pageCount > 0

    fun getBookmarks(): List<Bookmark> = bookmarks.toList()

    fun addBookmark(pageIndex: Int, title: String) {
        bookmarks.removeAll { it.pageIndex == pageIndex }
        bookmarks.add(Bookmark(pageIndex, title))
        bookmarks.sortBy { it.pageIndex }
    }

    fun removeBookmark(pageIndex: Int) {
        bookmarks.removeAll { it.pageIndex == pageIndex }
    }

    fun clearBookmarks() {
        bookmarks.clear()
    }

    fun isBookmarked(pageIndex: Int): Boolean = bookmarks.any { it.pageIndex == pageIndex }

    fun getOutline(): List<OutlineItem> = outline

    private fun buildOutline(): List<OutlineItem> {
        val doc = document ?: return emptyList()
        val roots = try { doc.loadOutline() } catch (e: Exception) { null } ?: return emptyList()
        return mapOutline(roots, 0)
    }

    private fun mapOutline(items: Array<Outline>?, level: Int): List<OutlineItem> {
        if (items == null) return emptyList()
        val result = mutableListOf<OutlineItem>()
        for (item in items) {
            val page = try {
                document?.resolveLink(item)?.let { document?.pageNumberFromLocation(it) }
            } catch (e: Exception) {
                null
            }
            val children = mapOutline(item.down, level + 1)
            if (page != null && page >= 0) {
                result.add(OutlineItem(item.title ?: "Untitled", page, level, children))
            } else {
                result.addAll(children)
            }
        }
        return result
    }

    /**
     * All link annotations on a page, normalised to 0..1 of the page box.
     * Internal destinations are resolved to a page index; external URIs are kept
     * as a string so the UI can hand them to the browser.
     */
    fun getPageLinks(pageIndex: Int): List<PdfLink> = synchronized(nativeLock) {
        val doc = document ?: return@synchronized emptyList()
        if (pageIndex !in 0 until doc.countPages()) return@synchronized emptyList()
        val page = try {
            doc.loadPage(pageIndex)
        } catch (e: Exception) {
            return@synchronized emptyList()
        }
        try {
            val bounds = page.bounds
            val w = maxOf(1f, bounds.x1 - bounds.x0)
            val h = maxOf(1f, bounds.y1 - bounds.y0)
            val links = try { page.getLinks() } catch (e: Exception) { null } ?: emptyArray()
            val result = mutableListOf<PdfLink>()
            for (link in links) {
                try {
                    val r = link.getBounds() ?: continue
                    val rect = RectF(
                        (r.x0 - bounds.x0) / w,
                        (r.y0 - bounds.y0) / h,
                        (r.x1 - bounds.x0) / w,
                        (r.y1 - bounds.y0) / h
                    )
                    val uri = link.getURI()
                    val external = try { link.isExternal() } catch (e: Exception) { false }
                    val target = if (!external && !uri.isNullOrBlank()) {
                        try {
                            doc.resolveLink(link)?.page?.takeIf { it in 0 until doc.countPages() }
                        } catch (e: Exception) {
                            null
                        }
                    } else null
                    if (target != null || (!uri.isNullOrBlank() && external)) {
                        result.add(PdfLink(rect, target, if (external) uri else null))
                    }
                } catch (e: Exception) {
                    // Skip malformed links rather than failing the whole page.
                } finally {
                    try { link.destroy() } catch (e: Exception) { }
                }
            }
            result
        } finally {
            page.destroy()
        }
    }

    fun getPageAspectRatioCached(pageIndex: Int): Float =
        aspectRatioCache[pageIndex] ?: 0.707f

    suspend fun getPageAspectRatio(pageIndex: Int): Float = withContext(Dispatchers.IO) {
        aspectRatioCache[pageIndex]?.let { return@withContext it }
        mutex.withLock {
            aspectRatioCache[pageIndex]?.let { return@withLock it }
            val ratio = synchronized(nativeLock) {
                val doc = document ?: return@synchronized 0.707f
                if (pageIndex !in 0 until doc.countPages()) return@synchronized 0.707f
                val page = doc.loadPage(pageIndex)
                try {
                    val b = page.bounds
                    (b.x1 - b.x0) / (b.y1 - b.y0)
                } finally {
                    page.destroy()
                }
            }
            aspectRatioCache[pageIndex] = ratio
            ratio
        }
    }

    suspend fun renderPage(
        pageIndex: Int,
        targetWidth: Int,
        targetHeight: Int,
        theme: ReadingTheme = ReadingTheme.LIGHT
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "${pageIndex}_${targetWidth}_${targetHeight}_${theme.name}"
        bitmapCache?.get(cacheKey)?.let { return@withContext it }

        mutex.withLock {
            bitmapCache?.get(cacheKey)?.let { return@withLock it }

            val rendered = synchronized(nativeLock) {
                val doc = document ?: return@synchronized null
                if (pageIndex !in 0 until doc.countPages()) return@synchronized null
                val page = doc.loadPage(pageIndex)
                try {
                    val bounds = page.bounds
                    val pageW = maxOf(1f, bounds.x1 - bounds.x0)
                    val pageH = maxOf(1f, bounds.y1 - bounds.y0)
                    // Cap bitmap dimensions to stay memory-safe on huge pages.
                    val scale = minOf(
                        targetWidth / pageW,
                        targetHeight / pageH,
                        2048f / pageW,
                        2048f / pageH
                    ).coerceAtLeast(0.0001f)
                    val ctm = Matrix(scale)
                    // drawPage fills the page background and returns an ARGB_8888 bitmap.
                    AndroidDrawDevice.drawPage(page, ctm)
                } catch (e: Exception) {
                    null
                } finally {
                    page.destroy()
                }
            }
            if (rendered == null) return@withLock null

            val themedBitmap = if (theme != ReadingTheme.LIGHT) {
                applyTheme(rendered, theme)
            } else {
                rendered
            }

            bitmapCache?.put(cacheKey, themedBitmap)
            if (!aspectRatioCache.containsKey(pageIndex)) {
                aspectRatioCache[pageIndex] = rendered.width.toFloat() / rendered.height.toFloat()
            }
            themedBitmap
        }
    }

    suspend fun renderThumbnail(pageIndex: Int, size: Int = 256): Bitmap? {
        thumbnailCache?.get(pageIndex)?.let { return it }
        val ratio = getPageAspectRatio(pageIndex)
        val w: Int
        val h: Int
        if (ratio >= 1f) {
            w = size
            h = (size / ratio).toInt().coerceAtLeast(1)
        } else {
            h = size
            w = (size * ratio).toInt().coerceAtLeast(1)
        }
        val bitmap = renderPage(pageIndex, w, h) ?: return null
        thumbnailCache?.put(pageIndex, bitmap)
        return bitmap
    }

    suspend fun prefetchPages(currentPage: Int, range: Int = 3) = withContext(Dispatchers.IO) {
        val pages = (-range..range).map { currentPage + it }
            .filter { it in 0 until pageCount && !prefetchJobs.containsKey(it) }

        pages.forEach { page ->
            if (!prefetchJobs.containsKey(page)) {
                prefetchJobs[page] = scope.launch { renderPage(page, 1024, 1448) }
            }
        }

        prefetchJobs.entries.removeIf { (page, job) ->
            val far = kotlin.math.abs(page - currentPage) > range * 2
            if (far) { job.cancel(); true } else false
        }
    }

    fun cancelPrefetch() {
        prefetchJobs.values.forEach { it.cancel() }
        prefetchJobs.clear()
    }

    /** Draws one viewport tile. Caller must hold [nativeLock]. */
    private fun drawTileLocked(
        page: Page,
        tileWidth: Int,
        tileHeight: Int,
        originX: Int,
        originY: Int,
        zoom: Float
    ): Bitmap? {
        val bitmap = Bitmap.createBitmap(tileWidth, tileHeight, Bitmap.Config.ARGB_8888)
        // AndroidDrawDevice treats these values as the pixmap's bounding box in
        // *device* space: (originX+patchX0 .. originX+patchX1). Origins must be
        // positive page coordinates; negative values sample outside the page and
        // render blank tiles.
        val device = AndroidDrawDevice(
            bitmap,
            originX, originY,
            0, 0, tileWidth, tileHeight,
            false
        )
        val cookie = Cookie()
        try {
            page.run(device, Matrix(zoom), cookie)
            return bitmap
        } catch (e: Exception) {
            bitmap.recycle()
            return null
        } finally {
            device.close()
            device.destroy()
            cookie.destroy()
        }
    }

    /** Renders a viewport tile using a cancellable MuPDF cookie. */
    suspend fun renderTile(
        pageIndex: Int,
        tileWidth: Int,
        tileHeight: Int,
        tileOffsetX: Float,
        tileOffsetY: Float,
        zoom: Float,
        theme: ReadingTheme = ReadingTheme.LIGHT
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "tile_${pageIndex}_${tileWidth}_${tileHeight}_${tileOffsetX}_${tileOffsetY}_${zoom}_${theme.name}"
        bitmapCache?.get(cacheKey)?.let { return@withContext it }

        mutex.withLock {
            bitmapCache?.get(cacheKey)?.let { return@withLock it }
            val rendered = synchronized(nativeLock) {
                val doc = document ?: return@synchronized null
                if (pageIndex !in 0 until doc.countPages()) return@synchronized null
                val page = doc.loadPage(pageIndex)
                try {
                    drawTileLocked(
                        page, tileWidth, tileHeight,
                        tileOffsetX.toInt(), tileOffsetY.toInt(), zoom
                    )
                } finally {
                    page.destroy()
                }
            } ?: return@withLock null

            val themed = if (theme != ReadingTheme.LIGHT) applyTheme(rendered, theme) else rendered
            bitmapCache?.put(cacheKey, themed)
            themed
        }
    }

    /**
     * Renders a whole page at high resolution for zoomed-in viewing.
     *
     * [renderPage] caps its bitmap at 2048px, which makes a 3–5x zoom look soft.
     * This method draws the page in [TILE_SIZE] tiles through [drawTileLocked] so
     * the final bitmap can be much larger without an equally large intermediate
     * allocation inside MuPDF. The caller displays it at the unzoomed dp size;
     * the existing graphics-layer zoom then scales the high-res pixels 1:1.
     */
    suspend fun renderSharpPage(
        pageIndex: Int,
        targetWidth: Int,
        targetHeight: Int,
        theme: ReadingTheme = ReadingTheme.LIGHT
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (targetWidth <= 0 || targetHeight <= 0) return@withContext null
        val cacheKey = "sharp_${pageIndex}_${targetWidth}_${targetHeight}_${theme.name}"
        bitmapCache?.get(cacheKey)?.let { return@withContext it }

        mutex.withLock {
            bitmapCache?.get(cacheKey)?.let { return@withLock it }
            val rendered = synchronized(nativeLock) {
                val doc = document ?: return@synchronized null
                if (pageIndex !in 0 until doc.countPages()) return@synchronized null
                val page = doc.loadPage(pageIndex)
                try {
                    val bounds = page.bounds
                    val pageW = maxOf(1f, bounds.x1 - bounds.x0)
                    val zoom = targetWidth / pageW
                    // Match AndroidDrawDevice.drawPage(): the rendered box starts at
                    // the transformed page origin, which is non-zero for some PDFs.
                    val baseX = (bounds.x0 * zoom).toInt()
                    val baseY = (bounds.y0 * zoom).toInt()
                    val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(result)
                    var top = 0
                    while (top < targetHeight) {
                        val th = minOf(TILE_SIZE, targetHeight - top)
                        var left = 0
                        while (left < targetWidth) {
                            val tw = minOf(TILE_SIZE, targetWidth - left)
                            val tile = drawTileLocked(page, tw, th, baseX + left, baseY + top, zoom)
                            if (tile != null) {
                                canvas.drawBitmap(tile, left.toFloat(), top.toFloat(), null)
                                tile.recycle()
                            }
                            left += tw
                        }
                        top += th
                    }
                    result
                } catch (e: Exception) {
                    null
                } finally {
                    page.destroy()
                }
            } ?: return@withLock null

            val themed = if (theme != ReadingTheme.LIGHT) applyTheme(rendered, theme) else rendered
            bitmapCache?.put(cacheKey, themed)
            themed
        }
    }

    private fun applyTheme(bitmap: Bitmap, theme: ReadingTheme): Bitmap {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        when (theme) {
            ReadingTheme.DARK -> {
                val matrix = android.graphics.ColorMatrix(floatArrayOf(
                    -0.85f, 0f, 0f, 0f, 235f,
                    0f, -0.85f, 0f, 0f, 235f,
                    0f, 0f, -0.85f, 0f, 235f,
                    0f, 0f, 0f, 1f, 0f
                ))
                paint.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
            }
            ReadingTheme.SEPIA -> {
                val matrix = android.graphics.ColorMatrix(floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ))
                paint.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
            }
            else -> canvas.drawBitmap(bitmap, 0f, 0f, null)
        }
        return result
    }

    /**
     * Adds a real PDF text annotation (highlight/underline) at normalised page rects.
     * The edited page is written back to the local working copy by [saveChanges].
     */
    fun addTextAnnotation(
        pageIndex: Int,
        rects: List<RectF>,
        type: Int = PDFAnnotation.TYPE_HIGHLIGHT,
        color: FloatArray = floatArrayOf(1f, 0.84f, 0.2f)
    ): Boolean = synchronized(nativeLock) {
        val doc = document as? PDFDocument ?: return@synchronized false
        if (rects.isEmpty()) return@synchronized false
        val page = try {
            doc.loadPage(pageIndex) as? PDFPage
        } catch (e: Exception) {
            null
        } ?: return@synchronized false

        try {
            val bounds = page.bounds
            val w = maxOf(1f, bounds.x1 - bounds.x0)
            val h = maxOf(1f, bounds.y1 - bounds.y0)
            val quads = rects.map { r ->
                Quad(
                    Rect(
                        bounds.x0 + r.left * w,
                        bounds.y0 + r.top * h,
                        bounds.x0 + r.right * w,
                        bounds.y0 + r.bottom * h
                    )
                )
            }.toTypedArray()

            val annotation = page.createAnnotation(type)
            annotation.setQuadPoints(quads)
            annotation.setColor(color)
            annotation.setOpacity(0.45f)
            annotation.setContents("Highlighted in PrettyDamnFine")
            annotation.setAuthor("PrettyDamnFine")
            annotation.update()
            annotation.destroy()
            page.update()
            true
        } catch (e: Exception) {
            false
        } finally {
            page.destroy()
        }
    }

    /** Removes PDF annotations whose area intersects the given normalised rects. */
    fun removeAnnotationsInRects(pageIndex: Int, rects: List<RectF>): Int = synchronized(nativeLock) {
        val doc = document as? PDFDocument ?: return@synchronized 0
        if (rects.isEmpty()) return@synchronized 0
        val page = try {
            doc.loadPage(pageIndex) as? PDFPage
        } catch (e: Exception) {
            null
        } ?: return@synchronized 0

        try {
            val bounds = page.bounds
            val w = maxOf(1f, bounds.x1 - bounds.x0)
            val h = maxOf(1f, bounds.y1 - bounds.y0)
            val annotations = page.annotations ?: return@synchronized 0
            var removed = 0
            for (annotation in annotations) {
                val r = annotation.bounds
                val annotationRect = RectF(
                    (r.x0 - bounds.x0) / w,
                    (r.y0 - bounds.y0) / h,
                    (r.x1 - bounds.x0) / w,
                    (r.y1 - bounds.y0) / h
                )
                if (rects.any {
                        it.intersects(
                            annotationRect.left,
                            annotationRect.top,
                            annotationRect.right,
                            annotationRect.bottom
                        )
                    }) {
                    page.deleteAnnotation(annotation)
                    removed++
                }
                annotation.destroy()
            }
            if (removed > 0) page.update()
            removed
        } catch (e: Exception) {
            0
        } finally {
            page.destroy()
        }
    }

    /** Drops cached bitmaps for a page so an annotation edit becomes visible. */
    fun invalidatePage(pageIndex: Int) {
        bitmapCache?.let { cache ->
            cache.snapshot().keys
                .filter {
                    it.startsWith("${pageIndex}_") ||
                        it.startsWith("tile_${pageIndex}_") ||
                        it.startsWith("sharp_${pageIndex}_")
                }
                .forEach { cache.remove(it) }
        }
    }

    fun annotationCount(pageIndex: Int): Int = synchronized(nativeLock) {
        val doc = document ?: return@synchronized 0
        val page = try { doc.loadPage(pageIndex) } catch (e: Exception) { null }
            ?: return@synchronized 0
        try {
            (page as? PDFPage)?.annotations?.size ?: 0
        } catch (e: Exception) {
            0
        } finally {
            page.destroy()
        }
    }

    fun releaseBitmap(bitmap: Bitmap) {
        // No-op: a cached bitmap may still be referenced by a Compose Image.
    }

    /** Path of the local working copy, for saving annotations. */
    fun workingCopyPath(): String = cacheFile.absolutePath

    /** Saves annotation/form changes made through MuPDF back to the working copy. */
    fun saveChanges(): Boolean = synchronized(nativeLock) {
        val pdf = document as? PDFDocument ?: return@synchronized false
        if (!pdf.hasUnsavedChanges()) return@synchronized false
        val tmp = File(cacheFile.parentFile, "${cacheFile.name}.saved")
        pdf.save(tmp.absolutePath, "")
        if (tmp.exists() && tmp.renameTo(cacheFile)) true else false
    }

    fun close() {
        scope.cancel()
        cancelPrefetch()
        bitmapCache?.evictAll()
        thumbnailCache?.evictAll()
        synchronized(nativeLock) {
            try { document?.destroy() } catch (e: Exception) { }
            document = null
        }
        aspectRatioCache.clear()
        bookmarks.clear()
    }
}
