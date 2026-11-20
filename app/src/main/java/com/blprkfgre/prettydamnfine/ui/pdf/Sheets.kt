package com.blprkfgre.prettydamnfine.ui.pdf

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.blprkfgre.prettydamnfine.pdf.Bookmark
import com.blprkfgre.prettydamnfine.pdf.OutlineItem
import com.blprkfgre.prettydamnfine.pdf.ReadingTheme
import com.blprkfgre.prettydamnfine.ui.components.IconSizes
import com.blprkfgre.prettydamnfine.ui.components.PdfEmptyState
import com.blprkfgre.prettydamnfine.ui.components.Radii
import com.blprkfgre.prettydamnfine.ui.components.Spacing
import com.blprkfgre.prettydamnfine.ui.components.TouchTargets

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(bottom = Spacing.Md)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSelectorSheet(
    currentTheme: ReadingTheme,
    onThemeSelected: (ReadingTheme) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Lg)
                .padding(bottom = Spacing.Xl)
        ) {
            SheetTitle("Reading Theme")

            ThemeOption(
                title = "Light",
                subtitle = "Classic white background",
                isSelected = currentTheme == ReadingTheme.LIGHT,
                onClick = { onThemeSelected(ReadingTheme.LIGHT) }
            )

            ThemeOption(
                title = "Dark",
                subtitle = "Easy on the eyes at night",
                isSelected = currentTheme == ReadingTheme.DARK,
                onClick = { onThemeSelected(ReadingTheme.DARK) }
            )

            ThemeOption(
                title = "Sepia",
                subtitle = "Warm, book-like reading",
                isSelected = currentTheme == ReadingTheme.SEPIA,
                onClick = { onThemeSelected(ReadingTheme.SEPIA) }
            )

            ThemeOption(
                title = "Auto",
                subtitle = "Follows system setting",
                isSelected = currentTheme == ReadingTheme.AUTO,
                onClick = { onThemeSelected(ReadingTheme.AUTO) }
            )
        }
    }
}

@Composable
private fun ThemeOption(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.Xs)
            .clip(RoundedCornerShape(Radii.Md))
            .background(backgroundColor)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .padding(Spacing.Md)
            .heightIn(min = TouchTargets.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.PictureAsPdf,
            contentDescription = null,
            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconSizes.Large)
        )
        Spacer(modifier = Modifier.width(Spacing.Md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksSheet(
    bookmarks: List<Bookmark>,
    currentPage: Int,
    onBookmarkSelected: (Bookmark) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Lg)
                .padding(bottom = Spacing.Xl)
        ) {
            SheetTitle("Bookmarks")

            if (bookmarks.isEmpty()) {
                PdfEmptyState(
                    icon = Icons.Filled.FavoriteBorder,
                    title = "No bookmarks yet",
                    message = "Bookmark pages to find them again quickly.",
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Sm)
                ) {
                    items(bookmarks) { bookmark ->
                        BookmarkItem(
                            bookmark = bookmark,
                            isCurrentPage = bookmark.pageIndex == currentPage,
                            onClick = { onBookmarkSelected(bookmark) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarkItem(
    bookmark: Bookmark,
    isCurrentPage: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.Md))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(Spacing.Md)
            .heightIn(min = TouchTargets.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = null,
            tint = if (isCurrentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconSizes.Standard)
        )
        Spacer(modifier = Modifier.width(Spacing.Md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = bookmark.title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Page ${bookmark.pageIndex + 1}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutlineSheet(
    outline: List<OutlineItem>,
    onItemSelected: (OutlineItem) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Lg)
                .padding(bottom = Spacing.Xl)
        ) {
            SheetTitle("Table of Contents")

            if (outline.isEmpty()) {
                PdfEmptyState(
                    icon = Icons.AutoMirrored.Filled.List,
                    title = "No table of contents",
                    message = "This document doesn't include an outline.",
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Xs)
                ) {
                    items(flattenOutline(outline)) { item ->
                        OutlineItemView(
                            item = item,
                            onClick = { onItemSelected(item) }
                        )
                    }
                }
            }
        }
    }
}

private fun flattenOutline(items: List<OutlineItem>): List<OutlineItem> {
    val result = mutableListOf<OutlineItem>()
    fun addAll(list: List<OutlineItem>) {
        for (item in list) {
            result.add(item)
            addAll(item.children)
        }
    }
    addAll(items)
    return result
}

@Composable
private fun OutlineItemView(
    item: OutlineItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.Sm))
            .clickable(onClick = onClick)
            .padding(
                start = (item.level * 16 + 8).dp,
                top = Spacing.Sm + Spacing.Xs,
                bottom = Spacing.Sm + Spacing.Xs,
                end = Spacing.Sm
            )
            .heightIn(min = TouchTargets.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${item.pageIndex + 1}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
