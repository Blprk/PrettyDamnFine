package com.blprkfgre.prettydamnfine.ui.pdf

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.blprkfgre.prettydamnfine.pdf.PdfEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageOverviewSheet(
    engine: PdfEngine,
    currentPage: Int,
    onPageSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "Pages",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(engine.pageCount) { index ->
                    PageThumbnail(
                        engine = engine,
                        pageIndex = index,
                        isCurrentPage = index == currentPage,
                        onClick = {
                            onPageSelected(index)
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun PageThumbnail(
    engine: PdfEngine,
    pageIndex: Int,
    isCurrentPage: Boolean,
    onClick: () -> Unit
) {
    var thumbnail by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(engine, pageIndex) {
        withContext(Dispatchers.IO) {
            val ratio = engine.getPageAspectRatio(pageIndex)
            val thumbWidth = 200
            val thumbHeight = (thumbWidth / ratio).toInt()
            val bmp = engine.renderPage(pageIndex, thumbWidth, thumbHeight)
            bmp?.let { thumbnail = it.asImageBitmap() }
        }
    }

    val borderColor = if (isCurrentPage)
        MaterialTheme.colorScheme.primary
    else
        Color.Transparent

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(0.707f) // A4 portrait
                .clip(RoundedCornerShape(8.dp))
                .border(2.dp, borderColor, RoundedCornerShape(8.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            thumbnail?.let { bmp ->
                Image(
                    bitmap = bmp,
                    contentDescription = "Page ${pageIndex + 1}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } ?: CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${pageIndex + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = if (isCurrentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}
