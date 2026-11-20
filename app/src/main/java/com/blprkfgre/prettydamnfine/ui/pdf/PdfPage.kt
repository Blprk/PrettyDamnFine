package com.blprkfgre.prettydamnfine.ui.pdf

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import com.blprkfgre.prettydamnfine.pdf.CharPos
import com.blprkfgre.prettydamnfine.pdf.PdfEngine
import com.blprkfgre.prettydamnfine.pdf.PdfLink
import com.blprkfgre.prettydamnfine.pdf.ReadingTheme
import com.blprkfgre.prettydamnfine.ui.theme.PdfHighlight
import com.blprkfgre.prettydamnfine.ui.theme.PdfSelectionDark
import com.blprkfgre.prettydamnfine.ui.theme.PdfSelectionLight
import com.blprkfgre.prettydamnfine.ui.theme.pageBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun PdfPage(
    engine: PdfEngine,
    pageIndex: Int,
    baseWidthPx: Int,
    scale: Float,
    theme: ReadingTheme = ReadingTheme.LIGHT,
    highlights: List<RectF> = emptyList(),
    charPositions: List<CharPos> = emptyList(),
    selection: TextSelection? = null,
    onSelectionChange: (Int, Int) -> Unit = { _, _ -> },
    onRequestCharPositions: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onLinkClick: (PdfLink) -> Unit = {},
    onDoubleTap: (Offset) -> Unit = {},
    revision: Int = 0,
    modifier: Modifier = Modifier
) {
    val pageModifier = modifier
    var aspectRatio by remember { mutableFloatStateOf(engine.getPageAspectRatioCached(pageIndex)) }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var renderedScale by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(engine, pageIndex) {
        aspectRatio = engine.getPageAspectRatio(pageIndex)
    }

    // Zoom is applied to the whole page list with graphicsLayer. Rendering at the
    // same resolution keeps text crisp instead of upscaling a 1x bitmap. Cap the
    // render scale so a 5x zoom cannot allocate an unsafe bitmap.
    val renderScale = scale.coerceIn(1f, 2.5f)
    val baseHeightPx = (baseWidthPx / aspectRatio).toInt()
    val targetWidthPx = (baseWidthPx * renderScale).toInt().coerceAtLeast(1)
    val targetHeightPx = (baseHeightPx * renderScale).toInt().coerceAtLeast(1)

    LaunchedEffect(engine, pageIndex, renderScale, theme, revision) {
        if (renderedScale != 0f) delay(150)
        val newBitmap = if (renderScale > 1.05f) {
            engine.renderSharpPage(pageIndex, targetWidthPx, targetHeightPx, theme)
        } else {
            engine.renderPage(pageIndex, targetWidthPx, targetHeightPx, theme)
        }
        newBitmap?.let {
            bitmap = it.asImageBitmap()
            renderedScale = renderScale
        }
    }

    var links by remember { mutableStateOf<List<PdfLink>>(emptyList()) }
    LaunchedEffect(engine, pageIndex, revision) {
        links = withContext(Dispatchers.IO) { engine.getPageLinks(pageIndex) }
    }

    val density = LocalDensity.current
    
    // Track if we're currently selecting text to prevent tap from clearing
    var isSelecting by remember { mutableStateOf(false) }
    
    Box(
        modifier = pageModifier
            .width(with(density) { baseWidthPx.toDp() })
            .aspectRatio(aspectRatio)
            .background(theme.pageBackground)
            .pointerInput(pageIndex, charPositions, links) {
                detectTapGestures(
                    onTap = { pos ->
                        // A tap inside a PDF link takes precedence over clearing a
                        // selection. Long-press/drag selection is handled below.
                        val hit = links.firstOrNull { link ->
                            val r = link.bounds
                            pos.x >= r.left * size.width && pos.x <= r.right * size.width &&
                                pos.y >= r.top * size.height && pos.y <= r.bottom * size.height
                        }
                        if (hit != null) {
                            isSelecting = false
                            onLinkClick(hit)
                        } else if (!isSelecting) {
                            onClearSelection()
                        }
                    },
                    onDoubleTap = { pos -> onDoubleTap(pos) }
                )
            }
            .pointerInput(pageIndex, charPositions) {
                var gestureStartIndex = -1
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        // Don't start selection if no char positions available
                        if (charPositions.isEmpty()) {
                            onRequestCharPositions()
                            return@detectDragGesturesAfterLongPress
                        }
                        
                        isSelecting = true
                        val canvasWidth = size.width.toFloat()
                        val canvasHeight = size.height.toFloat()
                        val startIndex = findClosestCharIndex(charPositions, offset.x / canvasWidth, offset.y / canvasHeight)
                        if (startIndex != -1) {
                            gestureStartIndex = startIndex
                            onSelectionChange(startIndex, startIndex)
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        
                        // Skip if no char positions or not in selection mode
                        if (charPositions.isEmpty() || gestureStartIndex == -1) return@detectDragGesturesAfterLongPress
                        
                        val canvasWidth = size.width.toFloat()
                        val canvasHeight = size.height.toFloat()
                        val endIndex = findClosestCharIndex(charPositions, change.position.x / canvasWidth, change.position.y / canvasHeight)
                        if (endIndex != -1) {
                            onSelectionChange(gestureStartIndex, endIndex)
                        }
                    },
                    onDragEnd = { 
                        gestureStartIndex = -1
                        isSelecting = false
                    },
                    onDragCancel = { 
                        gestureStartIndex = -1
                        isSelecting = false
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let { img ->
            Image(
                bitmap = img,
                contentDescription = "PDF Page ${pageIndex + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )

            if (highlights.isNotEmpty()) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    highlights.forEach { rect ->
                        drawRect(
                            color = PdfHighlight.copy(alpha = 0.4f),
                            topLeft = Offset(rect.left * size.width, rect.top * size.height),
                            size = Size((rect.right - rect.left) * size.width, (rect.bottom - rect.top) * size.height)
                        )
                    }
                }
            }
            
            if (selection != null && charPositions.isNotEmpty()) {
                // Cache selection rectangles to avoid recomputing every frame
                val selectionRects = remember(selection, charPositions) {
                    val start = minOf(selection.startCharIndex, selection.endCharIndex)
                    val end = maxOf(selection.startCharIndex, selection.endCharIndex)
                    (start..end).mapNotNull { i ->
                        if (i in charPositions.indices) charPositions[i].rect else null
                    }
                }
                
                Canvas(modifier = Modifier.fillMaxSize()) {
                    selectionRects.forEach { rect ->
                        drawRect(
                            color = (if (theme == ReadingTheme.DARK) PdfSelectionDark else PdfSelectionLight)
                                .copy(alpha = 0.35f),
                            topLeft = Offset(rect.left * size.width, rect.top * size.height),
                            size = Size((rect.right - rect.left) * size.width, (rect.bottom - rect.top) * size.height)
                        )
                    }
                }
            }
        } ?: run {
            CircularProgressIndicator()
        }
    }
}

private fun findClosestCharIndex(chars: List<CharPos>, x: Float, y: Float): Int {
    if (chars.isEmpty()) return -1
    
    // Fast path: check for direct hit first (most common case)
    val hit = chars.find { x >= it.rect.left && x <= it.rect.right && y >= it.rect.top && y <= it.rect.bottom }
    if (hit != null) return hit.index
    
    // Slow path: find closest character by center point distance
    // Use squared distance to avoid sqrt - faster
    var minDist = Float.MAX_VALUE
    var closestIndex = -1
    
    for (char in chars) {
        val cx = char.rect.centerX()
        val cy = char.rect.centerY()
        val distSq = (cx - x) * (cx - x) + (cy - y) * (cy - y)
        if (distSq < minDist) {
            minDist = distSq
            closestIndex = char.index
        }
    }
    
    return closestIndex
}
