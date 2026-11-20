package com.laterbox.app.media.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import com.laterbox.app.media.data.db.entity.DownloadPlaybackProgressEntity
import com.laterbox.app.ui.components.EmptyStateView
import com.laterbox.app.ui.components.LaterboxFilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.laterbox.app.media.domain.model.AssetFileStatus
import com.laterbox.app.media.domain.model.MediaAsset
import com.laterbox.app.media.domain.model.MediaType
import com.laterbox.app.ui.components.LaterboxBadge
import com.laterbox.app.ui.components.LaterboxBadgeVariant
import com.laterbox.app.ui.components.LaterboxCard
import com.laterbox.app.ui.components.LaterboxIconButton
import com.laterbox.app.ui.components.LaterboxIcons
import com.laterbox.app.ui.components.LaterboxShapeTokens
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import com.laterbox.app.ui.components.LaterboxConfirmDialog
import com.laterbox.app.ui.theme.LaterboxTheme
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryTab(
    assets: List<MediaAsset>,
    selectedMediaType: MediaType?,
    onMediaTypeSelected: (MediaType?) -> Unit,
    onOpenAsset: (MediaAsset) -> Unit,
    onExportAsset: (MediaAsset) -> Unit,
    onDeleteAsset: (MediaAsset) -> Unit,
    modifier: Modifier = Modifier,
    readyToDownloadItems: List<ReadyToDownloadItem> = emptyList(),
    onOpenLinkDetail: (Long) -> Unit = {},
    selectedAssetIds: Set<String> = emptySet(),
    isSelectionMode: Boolean = false,
    onToggleAssetSelection: (String) -> Unit = {},
    onSelectAllAssets: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onDeleteSelectedAssets: () -> Unit = {},
    onExportSelectedAssets: () -> Unit = {},
    playbackProgressByAssetId: Map<String, com.laterbox.app.media.data.db.entity.DownloadPlaybackProgressEntity> = emptyMap()
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = isSelectionMode) {
        onClearSelection()
    }

    if (showDeleteConfirmDialog) {
        LaterboxConfirmDialog(
            title = "Delete ${selectedAssetIds.size} ${if (selectedAssetIds.size == 1) "file" else "files"}?",
            message = "This permanently removes the downloaded files from your device storage.",
            confirmText = "Delete",
            onConfirm = {
                showDeleteConfirmDialog = false
                onDeleteSelectedAssets()
            },
            onDismiss = {
                showDeleteConfirmDialog = false
            },
            isDestructive = true
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Contextual selection toolbar
        if (isSelectionMode) {
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
                        text = "${selectedAssetIds.size} selected",
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

        if (assets.isEmpty() && readyToDownloadItems.isEmpty()) {
            EmptyStateView(
                title = "No downloaded media",
                subtitle = "Downloaded media files from your saved links will appear here for offline access."
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 112.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Ready to download horizontal carousel in scrolling content
                if (readyToDownloadItems.isNotEmpty()) {
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
                                    text = "${readyToDownloadItems.size} ready",
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
                                readyToDownloadItems.forEach { item ->
                                    ReadyToDownloadCard(
                                        item = item,
                                        onClick = { onOpenLinkDetail(item.savedLinkId) }
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. Sticky Media Type Filter Chips
                stickyHeader(key = "media_type_filter_chips") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.backgroundPrimary
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = spacing.xs),
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            LaterboxFilterChip(
                                text = "All",
                                isSelected = selectedMediaType == null,
                                onClick = { onMediaTypeSelected(null) }
                            )
                            MediaType.entries.filter { it != MediaType.OTHER }.forEach { type ->
                                LaterboxFilterChip(
                                    text = type.name.lowercase().replaceFirstChar { it.uppercase() },
                                    isSelected = selectedMediaType == type,
                                    onClick = { onMediaTypeSelected(type) }
                                )
                            }
                        }
                    }
                }

                // 3. Asset Cards
                if (assets.isEmpty()) {
                    item(key = "empty_filter_state") {
                        EmptyStateView(
                            title = "No ${selectedMediaType?.name?.lowercase() ?: "media"} files",
                            subtitle = "Try changing the filter or downloading media from your inbox links."
                        )
                    }
                } else {
                    items(assets, key = { it.id }) { asset ->
                        val isSelected = selectedAssetIds.contains(asset.id)
                        LibraryAssetCard(
                            asset = asset,
                            onOpen = {
                                if (isSelectionMode) {
                                    onToggleAssetSelection(asset.id)
                                } else {
                                    onOpenAsset(asset)
                                }
                            },
                            onExport = { onExportAsset(asset) },
                            onDelete = { onDeleteAsset(asset) },
                            isSelected = isSelected,
                            isInSelectionMode = isSelectionMode,
                            onLongClick = { onToggleAssetSelection(asset.id) },
                            playbackProgress = playbackProgressByAssetId[asset.id]
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LibraryAssetCard(
    asset: MediaAsset,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    isInSelectionMode: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    subtitleSummary: com.laterbox.app.movies.data.download.AssetSubtitleSummary? = null,
    isRetryingSubtitles: Boolean = false,
    onRetrySubtitles: (() -> Unit)? = null,
    playbackProgress: DownloadPlaybackProgressEntity? = null
) {
    val context = LocalContext.current
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing
    val file = File(context.filesDir, "media/${asset.localPath}")

    LaterboxCard(
        onClick = onOpen,
        onLongClick = onLongClick,
        containerColor = if (isSelected) colors.accentContainer.copy(alpha = 0.45f) else Color.Unspecified,
        borderColor = if (isSelected) colors.accentPrimary else Color.Unspecified,
        elevation = LaterboxTheme.elevations.card,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail or Media Type Icon
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(LaterboxTheme.shapes.Thumbnail)
                    .background(colors.surfaceSecondary),
                contentAlignment = Alignment.Center
            ) {
                val thumbFile = asset.thumbnailPath?.let { File(context.filesDir, it) }
                val imageModel: Any? = when {
                    thumbFile?.exists() == true -> thumbFile
                    asset.mediaType == MediaType.IMAGE && file.exists() -> file
                    !asset.artworkUrl.isNullOrBlank() -> asset.artworkUrl
                    else -> null
                }

                if (imageModel != null) {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = asset.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        painter = painterResource(
                            when (asset.mediaType) {
                                MediaType.IMAGE -> LaterboxIcons.Globe
                                MediaType.VIDEO -> LaterboxIcons.ViewCard
                                MediaType.AUDIO -> LaterboxIcons.Bell
                                MediaType.DOCUMENT -> LaterboxIcons.Folder
                                else -> LaterboxIcons.Folder
                            }
                        ),
                        contentDescription = null,
                        tint = colors.accentPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = asset.title ?: file.name,
                    style = typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!asset.artist.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = "${asset.artist}${if (!asset.album.isNullOrBlank()) " • ${asset.album}" else ""}",
                        style = typography.bodySmall,
                        color = colors.accentPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(spacing.xxs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val metaLine = buildString {
                        append("${asset.mediaType.name} · ${formatBytes(asset.fileSize)}")
                        if (!asset.versionTag.isNullOrBlank()) {
                            append(" · ${asset.versionTag}")
                        }
                    }
                    Text(
                        text = metaLine,
                        style = typography.bodySmall,
                        color = colors.textSecondary
                    )
                    if (asset.fileStatus == AssetFileStatus.MISSING) {
                        Spacer(modifier = Modifier.width(spacing.xs))
                        LaterboxBadge(
                            text = "Missing",
                            variant = LaterboxBadgeVariant.DANGER
                        )
                    }
                }
                if (asset.mediaType == MediaType.VIDEO &&
                    playbackProgress != null &&
                    playbackProgress.durationMs > 0L &&
                    playbackProgress.positionMs > 0L
                ) {
                    val fraction = (playbackProgress.positionMs.toFloat() / playbackProgress.durationMs.toFloat())
                        .coerceIn(0f, 1f)
                    val label = if (fraction >= 0.95f) {
                        "Watched"
                    } else {
                        "${formatPlaybackDuration(playbackProgress.positionMs)} / ${formatPlaybackDuration(playbackProgress.durationMs)}"
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("asset_playback_progress_${asset.id}")
                    ) {
                        Text(
                            text = label,
                            style = typography.metaText,
                            color = colors.accentPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(LaterboxTheme.shapes.Badge),
                            color = colors.accentPrimary,
                            trackColor = colors.surfaceSecondary
                        )
                    }
                }
                if (subtitleSummary != null && subtitleSummary.totalCount > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        LaterboxBadge(
                            text = subtitleSummary.formatBadgeText(),
                            variant = if (subtitleSummary.hasFailures) {
                                LaterboxBadgeVariant.WARNING
                            } else {
                                LaterboxBadgeVariant.SUCCESS
                            },
                            modifier = Modifier.testTag("asset_subtitle_status_${asset.id}")
                        )
                        if (subtitleSummary.hasFailures && onRetrySubtitles != null) {
                            Surface(
                                onClick = { if (!isRetryingSubtitles) onRetrySubtitles() },
                                shape = LaterboxTheme.shapes.Badge,
                                color = colors.accentContainer,
                                modifier = Modifier.testTag("retry_subtitles_${asset.id}")
                            ) {
                                Text(
                                    text = if (isRetryingSubtitles) "Retrying..." else "Retry subs",
                                    style = typography.bodySmall,
                                    color = colors.accentPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (!isInSelectionMode) {
                var showMoreMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(
                        onClick = { showMoreMenu = true },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("asset_more_button_${asset.id}")
                    ) {
                        Icon(
                            painter = painterResource(LaterboxIcons.MoreVertical),
                            contentDescription = "More actions",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false },
                        containerColor = colors.surfaceElevated
                    ) {
                        DropdownMenuItem(
                            modifier = Modifier.testTag("asset_menu_export_${asset.id}"),
                            text = {
                                Text(
                                    text = "Save to device",
                                    style = typography.bodyPrimary,
                                    color = colors.textPrimary
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(LaterboxIcons.Download),
                                    contentDescription = null,
                                    tint = colors.accentPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                showMoreMenu = false
                                onExport()
                            }
                        )
                        DropdownMenuItem(
                            modifier = Modifier.testTag("asset_menu_delete_${asset.id}"),
                            text = {
                                Text(
                                    text = "Delete",
                                    style = typography.bodyPrimary,
                                    color = colors.statusDanger
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(LaterboxIcons.Delete),
                                    contentDescription = null,
                                    tint = colors.statusDanger,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                showMoreMenu = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ReadyToDownloadCard(
    item: ReadyToDownloadItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    LaterboxCard(
        onClick = onClick,
        elevation = LaterboxTheme.elevations.card,
        modifier = modifier.width(220.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.sm)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(LaterboxTheme.shapes.Thumbnail)
                    .background(colors.surfaceSecondary),
                contentAlignment = Alignment.Center
            ) {
                if (!item.thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = item.thumbnailUrl,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        painter = painterResource(
                            when (item.mediaType) {
                                MediaType.IMAGE -> LaterboxIcons.Globe
                                MediaType.VIDEO -> LaterboxIcons.ViewCard
                                MediaType.AUDIO -> LaterboxIcons.Bell
                                else -> LaterboxIcons.Folder
                            }
                        ),
                        contentDescription = null,
                        tint = colors.accentPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(spacing.xs)
                ) {
                    LaterboxBadge(
                        text = "${item.candidateCount} ${if (item.candidateCount == 1) "format" else "formats"}",
                        variant = LaterboxBadgeVariant.ACCENT,
                        isMonospace = true
                    )
                }
            }

            Spacer(modifier = Modifier.height(spacing.xs))

            Text(
                text = item.title,
                style = typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(spacing.xxs))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(LaterboxIcons.Download),
                    contentDescription = null,
                    tint = colors.accentPrimary,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Tap to choose & download",
                    style = typography.metaText,
                    color = colors.accentPrimary
                )
            }
        }
    }
}

private fun formatPlaybackDuration(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
    }
}

