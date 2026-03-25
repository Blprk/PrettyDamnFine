package com.blprkfgre.prettydamnfine.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Collections
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

class PdfEngine(private val context: Context, val uri: Uri) {
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val aspectRatioCache = mutableMapOf<Int, Float>()
    private var thumbnailCache: LruCache<Int, Bitmap>? = null
    private var bitmapCache: LruCache<String, Bitmap>? = null
    private val bitmapPool = Collections.synchronizedList(mutableListOf<Bitmap>())
    
    private val prefetchJobs = ConcurrentHashMap<Int, Job>()

    val theme = ReadingTheme.LIGHT
    private val bookmarks = mutableListOf<Bookmark>()
    private var outline: List<OutlineItem> = emptyList()

    init {
        try {
            val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
            val cacheSize = minOf(maxMemory / 8, 48 * 1024)
            
            bitmapCache = object : LruCache<String, Bitmap>(cacheSize) {
                override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount / 1024
                override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
                    if (evicted) {
                        releaseBitmap(oldValue)
                    }
                }
            }
            
            thumbnailCache = object : LruCache<Int, Bitmap>(100) {
                override fun sizeOf(key: Int, bitmap: Bitmap): Int = bitmap.byteCount / 1024
                override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
                    if (evicted) {
                        releaseBitmap(oldValue)
                    }
                }
            }

            fileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            fileDescriptor?.let {
                pdfRenderer = PdfRenderer(it)
                scope.launch { extractOutline() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val pageCount: Int get() = pdfRenderer?.pageCount ?: 0

    fun getBookmarks(): List<Bookmark> = bookmarks.toList()
    
    fun addBookmark(pageIndex: Int, title: String) {
        bookmarks.removeAll { it.pageIndex == pageIndex }
        bookmarks.add(Bookmark(pageIndex, title))
        bookmarks.sortBy { it.pageIndex }
    }
    
    fun removeBookmark(pageIndex: Int) {
        bookmarks.removeAll { it.pageIndex == pageIndex }
    }
    
    fun isBookmarked(pageIndex: Int): Boolean = bookmarks.any { it.pageIndex == pageIndex }
    
    fun getOutline(): List<OutlineItem> = outline

    private suspend fun extractOutline() = withContext(Dispatchers.IO) {
        // Native PdfRenderer does not expose bookmark/outline trees.
        // Outline stays empty unless a third-party library is added in a future phase.
        outline = emptyList()
    }

    fun getPageAspectRatioCached(pageIndex: Int): Float {
        return aspectRatioCache[pageIndex] ?: 0.707f
    }

    suspend fun getPageAspectRatio(pageIndex: Int): Float = withContext(Dispatchers.IO) {
        aspectRatioCache[pageIndex]?.let { return@withContext it }

        mutex.withLock {
            aspectRatioCache[pageIndex]?.let { return@withLock it }

            val renderer = pdfRenderer ?: return@withLock 0.707f
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withLock 0.707f
            val page = renderer.openPage(pageIndex)
            val aspect = page.width.toFloat() / page.height.toFloat()
            page.close()
            aspectRatioCache[pageIndex] = aspect
            aspect
        }
    }

    suspend fun renderPage(pageIndex: Int, targetWidth: Int, targetHeight: Int, theme: ReadingTheme = ReadingTheme.LIGHT): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "${pageIndex}_${targetWidth}_${targetHeight}_${theme.name}"

        bitmapCache?.get(cacheKey)?.let { return@withContext it }

        mutex.withLock {
            bitmapCache?.get(cacheKey)?.let { return@withLock it }

            val renderer = pdfRenderer ?: return@withLock null
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withLock null

            var safeWidth = targetWidth.coerceAtMost(2048)
            var safeHeight = targetHeight.coerceAtMost(2048)

            if (safeWidth > 2048 || safeHeight > 2048) {
                val ratio = minOf(2048f / safeWidth, 2048f / safeHeight)
                safeWidth = (safeWidth * ratio).toInt()
                safeHeight = (safeHeight * ratio).toInt()
            }

            safeWidth = safeWidth.coerceAtLeast(1)
            safeHeight = safeHeight.coerceAtLeast(1)

            val bitmap = acquireBitmap(safeWidth, safeHeight)
            
            // Always render on white to ensure black text and white background are captured before inversion
            bitmap.eraseColor(Color.WHITE)

            val page = renderer.openPage(pageIndex)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            // Apply theme post-processing
            val themedBitmap = if (theme != ReadingTheme.LIGHT) {
                val t = applyTheme(bitmap, theme)
                releaseBitmap(bitmap)
                t
            } else {
                bitmap
            }

            bitmapCache?.put(cacheKey, themedBitmap)

            if (!aspectRatioCache.containsKey(pageIndex)) {
                aspectRatioCache[pageIndex] = safeWidth.toFloat() / safeHeight.toFloat()
            }

            themedBitmap
        }
    }

    private fun applyTheme(bitmap: Bitmap, theme: ReadingTheme): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val paint = Paint()

        when (theme) {
            ReadingTheme.DARK -> {
                // Comfortable dark mode matrix (not pure inversion)
                // Inverts but keeps text slightly softer than pure white
                val invertMatrix = android.graphics.ColorMatrix(floatArrayOf(
                    -0.85f, 0f,    0f,    0f, 235f,
                    0f,    -0.85f, 0f,    0f, 235f,
                    0f,    0f,    -0.85f, 0f, 235f,
                    0f,    0f,    0f,     1f, 0f
                ))
                paint.colorFilter = android.graphics.ColorMatrixColorFilter(invertMatrix)
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
            }
            ReadingTheme.SEPIA -> {
                // Warm sepia tint matrix
                val sepiaMatrix = android.graphics.ColorMatrix(floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f,     0f,     0f,     1f, 0f
                ))
                paint.colorFilter = android.graphics.ColorMatrixColorFilter(sepiaMatrix)
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
            }
            else -> {}
        }
        
        return result
    }

    suspend fun renderThumbnail(pageIndex: Int, size: Int = 256): Bitmap? = withContext(Dispatchers.IO) {
        thumbnailCache?.get(pageIndex)?.let { return@withContext it }

        mutex.withLock {
            thumbnailCache?.get(pageIndex)?.let { return@withLock it }

            val renderer = pdfRenderer ?: return@withLock null
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withLock null

            val page = renderer.openPage(pageIndex)
            val aspect = page.width.toFloat() / page.height.toFloat()
            
            val width: Int
            val height: Int
            if (aspect > 1) {
                width = size
                height = (size / aspect).toInt()
            } else {
                height = size
                width = (size * aspect).toInt()
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            thumbnailCache?.put(pageIndex, bitmap)
            bitmap
        }
    }

    suspend fun prefetchPages(currentPage: Int, range: Int = 3) = withContext(Dispatchers.IO) {
        val pagesToPrefetch = buildList {
            for (i in -range..range) {
                val page = currentPage + i
                if (page in 0 until pageCount && !prefetchJobs.containsKey(page)) {
                    add(page)
                }
            }
        }

        pagesToPrefetch.forEach { page ->
            if (!prefetchJobs.containsKey(page)) {
                val job = scope.launch {
                    renderPage(page, 1024, 1448)
                }
                prefetchJobs[page] = job
            }
        }

        // Cancel far-away prefetch jobs
        prefetchJobs.entries.removeIf { (page, job) ->
            val isFar = kotlin.math.abs(page - currentPage) > range * 2
            if (isFar) {
                job.cancel()
                true
            } else {
                false
            }
        }
    }

    fun cancelPrefetch() {
        prefetchJobs.values.forEach { it.cancel() }
        prefetchJobs.clear()
    }

    /**
     * Renders a specific region (tile) of a page.
     */
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

            val renderer = pdfRenderer ?: return@withLock null
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withLock null

            val bitmap = acquireBitmap(tileWidth, tileHeight)
            
            // Always render on white to ensure black text and white background are captured before inversion
            bitmap.eraseColor(Color.WHITE)

            val page = renderer.openPage(pageIndex)

            val matrix = Matrix()
            matrix.postScale(zoom, zoom)
            matrix.postTranslate(-tileOffsetX, -tileOffsetY)

            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            // Apply theme post-processing to tiles
            val themedBitmap = if (theme != ReadingTheme.LIGHT) {
                val t = applyTheme(bitmap, theme)
                if (t != bitmap) releaseBitmap(bitmap)
                t
            } else {
                bitmap
            }

            bitmapCache?.put(cacheKey, themedBitmap)
            themedBitmap
        }
    }

    private fun acquireBitmap(width: Int, height: Int): Bitmap {
        synchronized(bitmapPool) {
            val it = bitmapPool.iterator()
            while (it.hasNext()) {
                val b = it.next()
                if (b.width == width && b.height == height && !b.isRecycled) {
                    it.remove()
                    return b
                }
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    fun releaseBitmap(bitmap: Bitmap) {
        if (bitmapPool.size < 20 && !bitmap.isRecycled) {
            bitmapPool.add(bitmap)
        } else if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    fun close() {
        scope.cancel()
        cancelPrefetch()
        bitmapCache?.evictAll()
        thumbnailCache?.evictAll()
        synchronized(bitmapPool) {
            bitmapPool.forEach { if (!it.isRecycled) it.recycle() }
            bitmapPool.clear()
        }
        pdfRenderer?.close()
        fileDescriptor?.close()
    }
}
