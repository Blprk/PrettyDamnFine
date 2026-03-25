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
import androidx.compose.ui.graphics.Color
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
import com.blprkfgre.prettydamnfine.pdf.ReadingTheme
import kotlinx.coroutines.delay

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
    modifier: Modifier = Modifier
) {
    val pageModifier = modifier
    var aspectRatio by remember { mutableFloatStateOf(engine.getPageAspectRatioCached(pageIndex)) }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var renderedScale by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(engine, pageIndex) {
        aspectRatio = engine.getPageAspectRatio(pageIndex)
    }

    val baseHeightPx = (baseWidthPx / aspectRatio).toInt()
    val targetWidthPx = (baseWidthPx * scale).toInt()
    val targetHeightPx = (baseHeightPx * scale).toInt()

    LaunchedEffect(engine, pageIndex, scale, theme) {
        if (renderedScale != 0f) delay(150)
        val newBitmap = engine.renderPage(pageIndex, targetWidthPx, targetHeightPx, theme)
        newBitmap?.let {
            bitmap = it.asImageBitmap()
            renderedScale = scale
        }
    }

    val density = LocalDensity.current
    
    // Track if we're currently selecting text to prevent tap from clearing
    var isSelecting by remember { mutableStateOf(false) }
    
    Box(
        modifier = pageModifier
            .width(with(density) { baseWidthPx.toDp() })
            .aspectRatio(aspectRatio)
            .background(when(theme) {
                ReadingTheme.DARK -> Color(0xFF1A1A1A)
                ReadingTheme.SEPIA -> Color(0xFFF4ECD8)
                else -> Color.White
            })
            .pointerInput(pageIndex, charPositions) {
                detectTapGestures(
                    onTap = { 
                        // Only clear selection if we're not actively selecting
                        if (!isSelecting) {
                            onClearSelection()
                        }
                    },
                    onDoubleTap = { }
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
                            color = Color.Yellow.copy(alpha = 0.4f),
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
                            color = Color.Blue.copy(alpha = 0.3f),
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
