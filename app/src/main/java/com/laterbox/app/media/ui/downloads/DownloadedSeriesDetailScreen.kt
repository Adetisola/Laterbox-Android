package com.laterbox.app.media.ui.downloads

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.laterbox.app.media.data.db.entity.DownloadPlaybackProgressEntity
import com.laterbox.app.media.domain.model.MediaAsset
import com.laterbox.app.movies.data.download.AssetSubtitleSummary
import com.laterbox.app.ui.components.LaterboxBadge
import com.laterbox.app.ui.components.LaterboxBadgeVariant
import com.laterbox.app.ui.components.LaterboxConfirmDialog
import com.laterbox.app.ui.components.LaterboxIconButton
import com.laterbox.app.ui.components.LaterboxIcons
import com.laterbox.app.ui.theme.LaterboxTheme

@Composable
fun DownloadedSeriesDetailScreen(
    seriesGroup: DownloadLibraryEntry.SeriesGroup?,
    subtitleSummaries: Map<String, AssetSubtitleSummary>,
    retryingSubtitleAssetIds: Set<String>,
    playbackProgressByAssetId: Map<String, DownloadPlaybackProgressEntity>,
    onBackClick: () -> Unit,
    onOpenEpisode: (MediaAsset) -> Unit,
    onExportEpisode: (MediaAsset) -> Unit,
    onDeleteEpisode: (MediaAsset) -> Unit,
    onExportAllSeries: (DownloadLibraryEntry.SeriesGroup) -> Unit,
    onDeleteSeries: (DownloadLibraryEntry.SeriesGroup) -> Unit,
    onRetrySubtitles: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing
    val context = LocalContext.current

    var showHeaderMenu by remember { mutableStateOf(false) }
    var pendingDeleteEpisode by remember { mutableStateOf<MediaAsset?>(null) }
    var showDeleteSeriesDialog by remember { mutableStateOf(false) }

    LaunchedEffect(seriesGroup) {
        if (seriesGroup == null || seriesGroup.allAssets.isEmpty()) {
            onBackClick()
        }
    }

    if (seriesGroup == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(colors.backgroundPrimary)
        )
        return
    }

    pendingDeleteEpisode?.let { asset ->
        LaterboxConfirmDialog(
            title = "Delete downloaded episode?",
            message = "This permanently removes \"${asset.title ?: "this episode"}\" from your device storage.",
            confirmText = "Delete",
            onConfirm = {
                pendingDeleteEpisode = null
                onDeleteEpisode(asset)
            },
            onDismiss = { pendingDeleteEpisode = null },
            isDestructive = true
        )
    }

    if (showDeleteSeriesDialog) {
        val count = seriesGroup.allAssets.size
        LaterboxConfirmDialog(
            title = "Delete series downloads?",
            message = "This permanently removes all $count downloaded ${if (count == 1) "file" else "files"} for \"${seriesGroup.seriesTitle}\" from your device storage.",
            confirmText = "Delete Series",
            onConfirm = {
                showDeleteSeriesDialog = false
                onDeleteSeries(seriesGroup)
            },
            onDismiss = { showDeleteSeriesDialog = false },
            isDestructive = true
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backgroundPrimary)
            .testTag("downloaded_series_detail_screen")
    ) {
        // Top Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.base, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LaterboxIconButton(
                iconRes = LaterboxIcons.ArrowLeft,
                contentDescription = "Back",
                onClick = onBackClick,
                modifier = Modifier.testTag("downloaded_series_back_button")
            )
            Spacer(modifier = Modifier.width(spacing.sm))

            if (!seriesGroup.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = seriesGroup.posterUrl,
                    contentDescription = seriesGroup.seriesTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(36.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceSecondary)
                )
                Spacer(modifier = Modifier.width(spacing.sm))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = seriesGroup.seriesTitle,
                    style = typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val sizeLabel = Formatter.formatShortFileSize(context, seriesGroup.totalBytes.coerceAtLeast(0L))
                val epLabel = if (seriesGroup.distinctEpisodeCount == 1) "1 episode" else "${seriesGroup.distinctEpisodeCount} episodes"
                val fileSuffix = if (seriesGroup.totalFileCount > seriesGroup.distinctEpisodeCount) {
                    " · ${seriesGroup.totalFileCount} files"
                } else {
                    ""
                }
                Text(
                    text = "TV Series · $epLabel$fileSuffix · $sizeLabel",
                    style = typography.metaText,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box {
                IconButton(
                    onClick = { showHeaderMenu = true },
                    modifier = Modifier.testTag("downloaded_series_more_button")
                ) {
                    Icon(
                        painter = painterResource(LaterboxIcons.MoreVertical),
                        contentDescription = "Series download actions",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                DropdownMenu(
                    expanded = showHeaderMenu,
                    onDismissRequest = { showHeaderMenu = false },
                    containerColor = colors.surfaceElevated
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Save All to Device",
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
                            showHeaderMenu = false
                            onExportAllSeries(seriesGroup)
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Delete Series",
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
                            showHeaderMenu = false
                            showDeleteSeriesDialog = true
                        }
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("downloaded_series_episodes_list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            seriesGroup.seasons.forEach { season ->
                item(key = "season_header_${season.seasonNumber}") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Season ${season.seasonNumber}",
                            style = typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textPrimary
                        )
                        val count = season.episodes.size
                        LaterboxBadge(
                            text = if (count == 1) "1 file" else "$count files",
                            variant = LaterboxBadgeVariant.NEUTRAL,
                            isMonospace = true
                        )
                    }
                }

                items(
                    items = season.episodes,
                    key = { "ep_${it.asset.id}" }
                ) { episodeEntry ->
                    val asset = episodeEntry.asset
                    LibraryAssetCard(
                        asset = asset,
                        onOpen = { onOpenEpisode(asset) },
                        onExport = { onExportEpisode(asset) },
                        onDelete = { pendingDeleteEpisode = asset },
                        isSelected = false,
                        isInSelectionMode = false,
                        onLongClick = {},
                        subtitleSummary = subtitleSummaries[asset.id],
                        isRetryingSubtitles = retryingSubtitleAssetIds.contains(asset.id),
                        onRetrySubtitles = { onRetrySubtitles(asset.id) },
                        playbackProgress = playbackProgressByAssetId[asset.id]
                    )
                }
            }
            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(64.dp))
            }
        }
    }
}
