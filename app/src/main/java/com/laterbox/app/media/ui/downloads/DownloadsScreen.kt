package com.laterbox.app.media.ui.downloads

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laterbox.app.media.domain.model.DownloadState
import com.laterbox.app.media.domain.model.DownloadsFilterCategory
import com.laterbox.app.media.domain.model.MediaAsset
import com.laterbox.app.media.domain.model.MediaType
import com.laterbox.app.media.ui.viewer.DocumentOpener
import com.laterbox.app.media.ui.viewer.ImageViewer
import com.laterbox.app.media.ui.viewer.MediaPlayerScreen
import com.laterbox.app.ui.components.EmptyStateView
import com.laterbox.app.ui.components.LaterboxBadge
import com.laterbox.app.ui.components.LaterboxBadgeVariant
import com.laterbox.app.ui.components.LaterboxConfirmDialog
import com.laterbox.app.ui.components.LaterboxFilterChip
import com.laterbox.app.ui.components.LaterboxIconButton
import com.laterbox.app.ui.components.LaterboxIcons
import com.laterbox.app.ui.components.LaterboxShapeTokens
import com.laterbox.app.ui.theme.LaterboxTheme

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenLinkDetail: (Long) -> Unit = {},
    onMenuClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    var activeViewerAsset by remember { mutableStateOf<MediaAsset?>(null) }

    DownloadsScreenContent(
        uiState = uiState,
        onSettingsClick = onSettingsClick,
        onOpenLinkDetail = onOpenLinkDetail,
        onMenuClick = onMenuClick,
        onSearchQueryChange = viewModel::onSearchQueryChange,
        onCategorySelected = viewModel::onCategorySelected,
        onTabSelected = viewModel::onTabSelected,
        onToggleAssetSelection = viewModel::onToggleAssetSelection,
        onSelectAllAssets = { viewModel.onSelectAllAssets(uiState.assets) },
        onClearSelection = viewModel::onClearSelection,
        onDeleteSelectedAssets = viewModel::onDeleteSelectedAssets,
        onExportSelectedAssets = {
            viewModel.onExportSelectedAssets { succeeded, total ->
                Toast.makeText(
                    context,
                    if (succeeded > 0) "Exported $succeeded of $total file${if (total == 1) "" else "s"}" else "Export failed",
                    Toast.LENGTH_SHORT
                ).show()
            }
        },
        onOpenAsset = { asset ->
            when (asset.mediaType) {
                MediaType.IMAGE -> activeViewerAsset = asset
                MediaType.VIDEO, MediaType.AUDIO -> activeViewerAsset = asset
                MediaType.DOCUMENT, MediaType.OTHER -> DocumentOpener.openDocument(context, asset)
            }
        },
        onExportAsset = { asset ->
            viewModel.onExportAsset(asset.id) { success ->
                Toast.makeText(
                    context,
                    if (success) "Saved to device" else "Failed to save to device",
                    Toast.LENGTH_SHORT
                ).show()
            }
        },
        onDeleteAsset = { asset -> viewModel.onDeleteAsset(asset.id) },
        onToggleTaskSelection = viewModel::onToggleTaskSelection,
        onSelectAllTasks = { viewModel.onSelectAllTasks(uiState.queueTasks) },
        onClearTaskSelection = viewModel::onClearTaskSelection,
        onCancelSelectedTasks = viewModel::onCancelSelectedTasks,
        onRetrySelectedTasks = viewModel::onRetrySelectedTasks,
        onDismissSelectedTasks = viewModel::onDismissSelectedTasks,
        onOverrideCellular = viewModel::onOverrideCellular,
        onPauseTask = viewModel::onPauseTask,
        onResumeTask = viewModel::onResumeTask,
        onCancelTask = viewModel::onCancelTask,
        onRetryTask = viewModel::onRetryTask,
        onDismissTask = viewModel::onDismissTask,
        modifier = modifier
    )

    // Active Viewer Overlay
    activeViewerAsset?.let { asset ->
        when (asset.mediaType) {
            MediaType.IMAGE -> {
                ImageViewer(
                    asset = asset,
                    onDismiss = { activeViewerAsset = null },
                    onExport = {
                        viewModel.onExportAsset(asset.id) { success ->
                            Toast.makeText(
                                context,
                                if (success) "Exported to gallery" else "Export failed",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }
            MediaType.VIDEO, MediaType.AUDIO -> {
                MediaPlayerScreen(
                    asset = asset,
                    onDismiss = { activeViewerAsset = null },
                    onExport = {
                        viewModel.onExportAsset(asset.id) { success ->
                            Toast.makeText(
                                context,
                                if (success) "Exported to storage" else "Export failed",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }
            else -> {
                activeViewerAsset = null
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun DownloadsScreenContent(
    uiState: DownloadsUiState,
    onSettingsClick: () -> Unit,
    onOpenLinkDetail: (Long) -> Unit,
    onMenuClick: (() -> Unit)?,
    onSearchQueryChange: (String) -> Unit,
    onCategorySelected: (DownloadsFilterCategory) -> Unit,
    onTabSelected: (DownloadsTab) -> Unit,
    onToggleAssetSelection: (String) -> Unit,
    onSelectAllAssets: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelectedAssets: () -> Unit,
    onExportSelectedAssets: () -> Unit,
    onOpenAsset: (MediaAsset) -> Unit,
    onExportAsset: (MediaAsset) -> Unit,
    onDeleteAsset: (MediaAsset) -> Unit,
    onToggleTaskSelection: (String) -> Unit,
    onSelectAllTasks: () -> Unit,
    onClearTaskSelection: () -> Unit,
    onCancelSelectedTasks: () -> Unit,
    onRetrySelectedTasks: () -> Unit,
    onDismissSelectedTasks: () -> Unit,
    onOverrideCellular: (String) -> Unit,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onDismissTask: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    var isSearchVisible by remember { mutableStateOf(false) }
    var showStorageSheet by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val effectiveStorageOpen = showStorageSheet || uiState.selectedTab == DownloadsTab.STORAGE

    LaunchedEffect(uiState.selectedTab) {
        when (uiState.selectedTab) {
            DownloadsTab.QUEUE -> listState.animateScrollToItem(0)
            DownloadsTab.STORAGE -> showStorageSheet = true
            DownloadsTab.LIBRARY -> Unit
        }
    }

    val activeQueueCount = uiState.queueTasks.count {
        it.status in listOf(
            DownloadState.QUEUED,
            DownloadState.DOWNLOADING,
            DownloadState.PROCESSING,
            DownloadState.WAITING_FOR_WIFI
        )
    }

    BackHandler(enabled = uiState.isSelectionMode || uiState.isQueueSelectionMode) {
        if (uiState.isQueueSelectionMode) onClearTaskSelection()
        if (uiState.isSelectionMode) onClearSelection()
    }

    if (showDeleteConfirmDialog) {
        LaterboxConfirmDialog(
            title = "Delete ${uiState.selectedAssetIds.size} ${if (uiState.selectedAssetIds.size == 1) "file" else "files"}?",
            message = "This permanently removes the downloaded files from your device storage.",
            confirmText = "Delete",
            onConfirm = {
                showDeleteConfirmDialog = false
                onDeleteSelectedAssets()
            },
            onDismiss = { showDeleteConfirmDialog = false },
            isDestructive = true
        )
    }

    if (effectiveStorageOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                showStorageSheet = false
                if (uiState.selectedTab == DownloadsTab.STORAGE) {
                    onTabSelected(DownloadsTab.LIBRARY)
                }
            },
            sheetState = sheetState,
            containerColor = colors.surfaceElevated,
            modifier = Modifier.testTag("downloads_storage_sheet")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 280.dp, max = 560.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.base, vertical = spacing.xs),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Storage Usage",
                        style = typography.sheetTitle,
                        color = colors.textPrimary
                    )
                    TextButton(
                        onClick = {
                            showStorageSheet = false
                            if (uiState.selectedTab == DownloadsTab.STORAGE) {
                                onTabSelected(DownloadsTab.LIBRARY)
                            }
                        }
                    ) {
                        Text("Close", color = colors.accentPrimary)
                    }
                }
                StorageTab(
                    totalStorageBytes = uiState.totalStorageBytes,
                    storageBreakdown = uiState.storageBreakdown,
                    assets = uiState.assets,
                    selectedAssetIds = uiState.selectedAssetIds,
                    isSelectionMode = uiState.isSelectionMode,
                    onToggleAssetSelection = onToggleAssetSelection,
                    onClearSelection = onClearSelection,
                    onDeleteSelectedAssets = onDeleteSelectedAssets
                )
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backgroundPrimary)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.base, vertical = spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (onMenuClick != null) {
                        LaterboxIconButton(
                            iconRes = LaterboxIcons.Menu,
                            contentDescription = "Open navigation menu",
                            onClick = onMenuClick
                        )
                        Spacer(modifier = Modifier.width(spacing.sm))
                    }
                    Column {
                        Text(
                            text = "Downloads",
                            style = typography.displayLarge,
                            color = colors.textPrimary
                        )
                        val summaryLine = buildString {
                            if (activeQueueCount > 0) {
                                append("$activeQueueCount active · ")
                            }
                            append("${uiState.assets.size} saved")
                            if (uiState.totalStorageBytes > 0L) {
                                append(" · ${formatBytes(uiState.totalStorageBytes)}")
                            }
                        }
                        Text(
                            text = summaryLine,
                            style = typography.metaText,
                            color = colors.textSecondary
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = colors.surfacePrimary,
                        border = BorderStroke(spacing.hairline, colors.borderSubtle),
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { showStorageSheet = true }
                            .testTag("downloads_storage_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                painter = painterResource(LaterboxIcons.Folder),
                                contentDescription = "Open storage usage",
                                tint = colors.accentPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Storage",
                                style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.textPrimary
                            )
                        }
                    }

                    LaterboxIconButton(
                        iconRes = LaterboxIcons.Search,
                        contentDescription = "Search downloads",
                        selected = isSearchVisible,
                        onClick = { isSearchVisible = !isSearchVisible }
                    )
                    LaterboxIconButton(
                        iconRes = LaterboxIcons.Settings,
                        contentDescription = "Settings",
                        onClick = onSettingsClick
                    )
                }
            }

            // Expandable Search Bar
            AnimatedVisibility(
                visible = isSearchVisible,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.base, vertical = spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(LaterboxShapeTokens.InputField)
                            .background(colors.surfacePrimary)
                            .border(spacing.hairline, colors.borderSubtle, LaterboxShapeTokens.InputField)
                            .padding(horizontal = spacing.md),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(id = LaterboxIcons.Search),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = colors.textSecondary
                            )
                            Spacer(modifier = Modifier.width(spacing.sm))

                            Box(modifier = Modifier.weight(1f)) {
                                if (uiState.searchQuery.isEmpty()) {
                                    Text(
                                        text = "Search title, format, platform...",
                                        style = typography.bodySecondary,
                                        color = colors.textSecondary.copy(alpha = 0.7f)
                                    )
                                }
                                BasicTextField(
                                    value = uiState.searchQuery,
                                    onValueChange = onSearchQueryChange,
                                    singleLine = true,
                                    textStyle = typography.bodyPrimary.copy(color = colors.textPrimary),
                                    cursorBrush = SolidColor(colors.accentPrimary),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            if (uiState.searchQuery.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(LaterboxShapeTokens.IconButton)
                                        .clickable(
                                            role = Role.Button,
                                            onClickLabel = "Clear search",
                                            onClick = { onSearchQueryChange("") }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = LaterboxIcons.Close),
                                        contentDescription = "Clear search",
                                        modifier = Modifier.size(16.dp),
                                        tint = colors.textSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Contextual Queue Selection Toolbar
            if (uiState.isQueueSelectionMode) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.surfacePrimary,
                    tonalElevation = 2.dp,
                    border = BorderStroke(spacing.hairline, colors.borderSubtle)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = spacing.base, vertical = spacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LaterboxIconButton(
                            iconRes = LaterboxIcons.Close,
                            contentDescription = "Cancel queue selection",
                            onClick = onClearTaskSelection
                        )
                        Spacer(modifier = Modifier.width(spacing.sm))
                        Text(
                            text = "${uiState.selectedTaskIds.size} queued selected",
                            style = typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onSelectAllTasks) {
                            Text("Select all", style = typography.buttonLabel.copy(fontSize = 13.sp), color = colors.accentPrimary)
                        }
                        LaterboxIconButton(
                            iconRes = LaterboxIcons.Refresh,
                            contentDescription = "Retry selected tasks",
                            onClick = onRetrySelectedTasks
                        )
                        Spacer(modifier = Modifier.width(spacing.xs))
                        LaterboxIconButton(
                            iconRes = LaterboxIcons.Close,
                            contentDescription = "Cancel selected tasks",
                            onClick = onCancelSelectedTasks
                        )
                        Spacer(modifier = Modifier.width(spacing.xs))
                        LaterboxIconButton(
                            iconRes = LaterboxIcons.Delete,
                            contentDescription = "Dismiss selected tasks",
                            onClick = onDismissSelectedTasks
                        )
                    }
                }
            }

            // Contextual Library Selection Toolbar
            if (uiState.isSelectionMode) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.surfacePrimary,
                    tonalElevation = 2.dp,
                    border = BorderStroke(spacing.hairline, colors.borderSubtle)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = spacing.base, vertical = spacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LaterboxIconButton(
                            iconRes = LaterboxIcons.Close,
                            contentDescription = "Cancel selection",
                            onClick = onClearSelection
                        )
                        Spacer(modifier = Modifier.width(spacing.sm))
                        Text(
                            text = "${uiState.selectedAssetIds.size} selected",
                            style = typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onSelectAllAssets) {
                            Text("Select all", style = typography.buttonLabel.copy(fontSize = 13.sp), color = colors.accentPrimary)
                        }
                        LaterboxIconButton(
                            iconRes = LaterboxIcons.Download,
                            contentDescription = "Export selected",
                            onClick = onExportSelectedAssets
                        )
                        Spacer(modifier = Modifier.width(spacing.xs))
                        LaterboxIconButton(
                            iconRes = LaterboxIcons.Delete,
                            contentDescription = "Delete selected",
                            onClick = { showDeleteConfirmDialog = true }
                        )
                    }
                }
            }

            val isCompletelyEmpty = uiState.queueTasks.isEmpty() &&
                uiState.assets.isEmpty() &&
                uiState.readyToDownloadItems.isEmpty() &&
                uiState.selectedCategory == DownloadsFilterCategory.ALL &&
                uiState.searchQuery.isBlank()

            if (isCompletelyEmpty) {
                EmptyStateView(
                    title = "No downloads yet",
                    subtitle = "Authorised movies, series episodes, and saved media files will show live progress and offline access here.",
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("downloads_empty_state")
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("downloads_unified_list"),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 1. Active Queue Section on top
                    if (uiState.queueTasks.isNotEmpty()) {
                        item(key = "queue_section_header") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .testTag("downloads_queue_header"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Active Queue",
                                    style = typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = colors.textPrimary
                                )
                                LaterboxBadge(
                                    text = "${uiState.queueTasks.size}",
                                    variant = LaterboxBadgeVariant.ACCENT,
                                    isMonospace = true
                                )
                            }
                        }

                        items(uiState.queueTasks, key = { "q_${it.id}" }) { task ->
                            val isSelected = uiState.selectedTaskIds.contains(task.id)
                            QueueTaskCard(
                                task = task,
                                onOverrideCellular = { onOverrideCellular(task.id) },
                                onPauseTask = { onPauseTask(task.id) },
                                onResumeTask = { onResumeTask(task.id) },
                                onCancelTask = { onCancelTask(task.id) },
                                onRetryTask = { onRetryTask(task.id) },
                                onDismissTask = { onDismissTask(task.id) },
                                isSelected = isSelected,
                                isInSelectionMode = uiState.isQueueSelectionMode,
                                onClick = {
                                    if (uiState.isQueueSelectionMode) {
                                        onToggleTaskSelection(task.id)
                                    }
                                },
                                onLongClick = { onToggleTaskSelection(task.id) }
                            )
                        }
                    }

                    // 2. Ready to download carousel
                    if (uiState.readyToDownloadItems.isNotEmpty()) {
                        item(key = "ready_to_download_section") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = spacing.xs)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = spacing.xxs),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Ready to download",
                                        style = typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = colors.textPrimary
                                    )
                                    Text(
                                        text = "${uiState.readyToDownloadItems.size} ready",
                                        style = typography.metaText,
                                        color = colors.accentPrimary
                                    )
                                }
                                Spacer(modifier = Modifier.height(spacing.xxs))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                                ) {
                                    uiState.readyToDownloadItems.forEach { item ->
                                        ReadyToDownloadCard(
                                            item = item,
                                            onClick = { onOpenLinkDetail(item.savedLinkId) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. Sticky Library Category Filters
                    stickyHeader(key = "library_filter_chips") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = colors.backgroundPrimary
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = spacing.xs),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Completed Library",
                                        style = typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = colors.textPrimary
                                    )
                                    Text(
                                        text = "${uiState.assets.size} items",
                                        style = typography.metaText,
                                        color = colors.textSecondary
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(vertical = spacing.xs),
                                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                                ) {
                                    DownloadsFilterCategory.entries.forEach { category ->
                                        LaterboxFilterChip(
                                            text = category.label,
                                            isSelected = uiState.selectedCategory == category,
                                            onClick = { onCategorySelected(category) },
                                            modifier = Modifier.testTag("downloads_filter_${category.name.lowercase()}")
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 4. Library Asset Cards
                    if (uiState.assets.isEmpty()) {
                        item(key = "empty_filter_state") {
                            EmptyStateView(
                                title = "No ${uiState.selectedCategory.label.lowercase()} downloads",
                                subtitle = if (uiState.searchQuery.isNotBlank()) {
                                    "Try clearing your search or switching the category filter."
                                } else {
                                    "Completed ${uiState.selectedCategory.label.lowercase()} downloads will appear here."
                                }
                            )
                        }
                    } else {
                        items(uiState.assets, key = { "lib_${it.id}" }) { asset ->
                            val isSelected = uiState.selectedAssetIds.contains(asset.id)
                            LibraryAssetCard(
                                asset = asset,
                                onOpen = {
                                    if (uiState.isSelectionMode) {
                                        onToggleAssetSelection(asset.id)
                                    } else {
                                        onOpenAsset(asset)
                                    }
                                },
                                onExport = { onExportAsset(asset) },
                                onDelete = { onDeleteAsset(asset) },
                                isSelected = isSelected,
                                isInSelectionMode = uiState.isSelectionMode,
                                onLongClick = { onToggleAssetSelection(asset.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}
