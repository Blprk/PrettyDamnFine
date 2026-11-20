package com.blprkfgre.prettydamnfine.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The single icon-button primitive used across the app: a guaranteed 48dp
 * touch target, a tokenised 24dp icon and a mandatory accessible name.
 */
@Composable
fun ActionIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(TouchTargets.Min)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(IconSizes.Standard),
            tint = tint ?: LocalContentColor.current
        )
    }
}

/** A lightweight section label used by the library and bottom sheets. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(vertical = Spacing.Xs)
    )
}

/** Consistent, accessible empty state with an optional primary action. */
@Composable
fun PdfEmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier.padding(Spacing.Lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(IconSizes.Hero),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(Spacing.Md))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.Sm))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Spacing.Lg))
            FilledTonalButton(onClick = onAction) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(Spacing.Sm))
                Text(actionLabel)
            }
        }
    }
}

/** Viewer top bar in normal (non-search) mode. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfTopBar(
    title: String,
    currentPage: Int,
    pageCount: Int,
    onBack: () -> Unit,
    onOpenPageJump: () -> Unit,
    onSaveCopy: () -> Unit,
    onShare: () -> Unit,
    onSearch: () -> Unit,
    onThemeSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Column(
                modifier = Modifier
                    .clickable(onClick = onOpenPageJump)
                    .padding(vertical = Spacing.Xs)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Go to page",
                        modifier = Modifier.size(IconSizes.Small),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "Page ${currentPage + 1} of $pageCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        navigationIcon = {
            ActionIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back to library",
                onClick = onBack
            )
        },
        actions = {
            ActionIconButton(Icons.Filled.Download, "Save a copy to storage", onSaveCopy)
            ActionIconButton(Icons.Filled.Share, "Share document", onShare)
            ActionIconButton(Icons.Filled.Search, "Search text", onSearch)
            ActionIconButton(Icons.Filled.Settings, "Reading settings", onThemeSettings)
        }
    )
}

/** Viewer top bar while text search is active. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfSearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    resultCount: Int,
    currentIndex: Int,
    onClose: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    TopAppBar(
        modifier = modifier,
        title = {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search in document") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {}),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                )
            )
        },
        navigationIcon = {
            ActionIconButton(Icons.Filled.Close, "Close search", onClose)
        },
        actions = {
            if (resultCount > 0) {
                Text(
                    text = "${currentIndex + 1}/$resultCount",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = Spacing.Sm)
                )
            }
            ActionIconButton(
                Icons.Filled.KeyboardArrowUp,
                "Previous result",
                onPrevious,
                enabled = resultCount > 0
            )
            ActionIconButton(
                Icons.Filled.KeyboardArrowDown,
                "Next result",
                onNext,
                enabled = resultCount > 0
            )
        }
    )
}

/**
 * The bottom contextual bar: outline, zoom controls and save-annotations.
 * Share/save-a-copy live in the top bar, so this bar stays narrow enough for
 * small phones and can never collide with the read-mode FAB.
 */
@Composable
fun ViewerBottomBar(
    scale: Float,
    onOutline: () -> Unit,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onSaveAnnotations: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(ViewerChrome.BarHeight)
            .wrapContentWidth(),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = Elevation.Panel,
        shadowElevation = Elevation.PanelShadow
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Xs)
        ) {
            ActionIconButton(Icons.AutoMirrored.Filled.List, "Table of contents", onOutline)
            BarDivider()
            ActionIconButton(Icons.Filled.Remove, "Zoom out", onZoomOut)
            Text(
                text = "${(scale * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(52.dp),
                textAlign = TextAlign.Center
            )
            ActionIconButton(Icons.Filled.Add, "Zoom in", onZoomIn)
            BarDivider()
            ActionIconButton(Icons.Filled.Save, "Save annotations", onSaveAnnotations)
        }
    }
}

@Composable
private fun BarDivider() {
    Box(
        Modifier
            .height(Spacing.Lg)
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/** Contextual selection actions rendered as a single anchored panel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SelectionToolbar(
    canCall: Boolean,
    canEmail: Boolean,
    onCall: () -> Unit,
    onEmail: () -> Unit,
    onCopy: () -> Unit,
    onHighlight: () -> Unit,
    onRemoveHighlight: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radii.Xl),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = Elevation.Panel,
        shadowElevation = Elevation.PanelShadow
    ) {
        // FlowRow keeps every action at a 48dp target while wrapping onto a
        // second line on narrow phones instead of overflowing the panel.
        FlowRow(
            modifier = Modifier.padding(horizontal = Spacing.Sm, vertical = Spacing.Xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.Xs)
        ) {
            if (canCall) {
                ActionIconButton(Icons.Filled.Phone, "Call selected number", onCall)
            }
            if (canEmail) {
                ActionIconButton(Icons.Filled.Email, "Email selected address", onEmail)
            }
            ActionIconButton(Icons.Filled.ContentCopy, "Copy selection", onCopy)
            ActionIconButton(Icons.Filled.Edit, "Highlight selection", onHighlight)
            ActionIconButton(Icons.Filled.Delete, "Remove highlight", onRemoveHighlight)
            ActionIconButton(Icons.Filled.Close, "Close selection", onClose)
        }
    }
}
