package com.blprkfgre.prettydamnfine.ui.pdf

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.blprkfgre.prettydamnfine.pdf.PdfEngine
import com.blprkfgre.prettydamnfine.pdf.ReadingTheme
import com.blprkfgre.prettydamnfine.ui.components.Radii
import com.blprkfgre.prettydamnfine.ui.components.Spacing
import com.blprkfgre.prettydamnfine.ui.theme.pageBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageOverviewSheet(
    engine: PdfEngine,
    currentPage: Int,
    theme: ReadingTheme = ReadingTheme.LIGHT,
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
                .padding(horizontal = Spacing.Md)
        ) {
            Text(
                text = "Pages",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = Spacing.Md)
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
                verticalArrangement = Arrangement.spacedBy(Spacing.Md),
                modifier = Modifier.fillMaxSize()
            ) {
                items(engine.pageCount) { index ->
                    PageThumbnail(
                        engine = engine,
                        pageIndex = index,
                        theme = theme,
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
    theme: ReadingTheme = ReadingTheme.LIGHT,
    isCurrentPage: Boolean,
    onClick: () -> Unit
) {
    var thumbnail by remember { mutableStateOf<ImageBitmap?>(null) }
    var ratio by remember { mutableFloatStateOf(0.707f) }

    LaunchedEffect(engine, pageIndex, theme) {
        val pageRatio = withContext(Dispatchers.IO) { engine.getPageAspectRatio(pageIndex) }
        ratio = pageRatio
        withContext(Dispatchers.IO) {
            val thumbWidth = 200
            val thumbHeight = (thumbWidth / pageRatio).toInt()
            val bmp = engine.renderPage(pageIndex, thumbWidth, thumbHeight, theme)
            bmp?.let { thumbnail = it.asImageBitmap() }
        }
    }

    val borderColor = if (isCurrentPage)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.outlineVariant

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClickLabel = "Go to page ${pageIndex + 1}", onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(Radii.Sm))
                .border(2.dp, borderColor, RoundedCornerShape(Radii.Sm))
                .background(theme.pageBackground),
            contentAlignment = Alignment.Center
        ) {
            thumbnail?.let { bmp ->
                Image(
                    bitmap = bmp,
                    contentDescription = "Page ${pageIndex + 1} thumbnail",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } ?: CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.height(Spacing.Xs))
        Text(
            text = "${pageIndex + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = if (isCurrentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}
