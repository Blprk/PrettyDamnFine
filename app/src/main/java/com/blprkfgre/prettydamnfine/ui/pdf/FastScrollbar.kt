package com.blprkfgre.prettydamnfine.ui.pdf

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * iOS-style fast scroll scrubber.
 * Shows a draggable thumb on the right edge with a page number bubble.
 */
@Composable
fun FastScrollbar(
    listState: LazyListState,
    totalItems: Int,
    modifier: Modifier = Modifier
) {
    if (totalItems <= 1) return

    val coroutineScope = rememberCoroutineScope()
    var isDragging by remember { mutableStateOf(false) }
    var dragY by remember { mutableFloatStateOf(0f) }

    val scrollFraction by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex.toFloat() / maxOf(1, totalItems - 1)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(44.dp)
    ) {
        val trackHeight = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        val thumbHeightPx = with(density) { 44.dp.toPx() }

        // Touch target for dragging (wider than visible track for easier grabbing)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(totalItems) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            dragY = offset.y
                            val fraction = (offset.y / trackHeight).coerceIn(0f, 1f)
                            val target = (fraction * (totalItems - 1)).toInt()
                            coroutineScope.launch { listState.scrollToItem(target) }
                        },
                        onDrag = { change, _ ->
                            dragY = change.position.y.coerceIn(0f, trackHeight)
                            val fraction = (dragY / trackHeight).coerceIn(0f, 1f)
                            val target = (fraction * (totalItems - 1)).toInt()
                            coroutineScope.launch { listState.scrollToItem(target) }
                        },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false }
                    )
                }
        ) {
            // Track line (always visible, subtle)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(
                        Color.White.copy(alpha = 0.15f),
                        RoundedCornerShape(2.dp)
                    )
            )

            // Thumb position
            val currentFraction = if (isDragging) {
                (dragY / trackHeight).coerceIn(0f, 1f)
            } else {
                scrollFraction
            }
            val thumbOffsetY = (currentFraction * (trackHeight - thumbHeightPx)).coerceAtLeast(0f)

            // Thumb indicator
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp)
                    .offset { IntOffset(0, thumbOffsetY.toInt()) }
                    .width(if (isDragging) 8.dp else 5.dp)
                    .height(44.dp)
                    .background(
                        Color.White.copy(alpha = if (isDragging) 0.9f else 0.45f),
                        RoundedCornerShape(4.dp)
                    )
            )

            // Page number bubble (shown only while dragging)
            if (isDragging) {
                val currentPage = (currentFraction * (totalItems - 1)).toInt() + 1
                val bubbleOffsetY = (thumbOffsetY - with(density) { 4.dp.toPx() }).coerceAtLeast(0f)

                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(with(density) { (-52).dp.roundToPx() }, bubbleOffsetY.toInt()) }
                        .background(
                            Color.Black.copy(alpha = 0.85f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "$currentPage",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}
