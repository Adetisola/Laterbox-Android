package com.laterbox.app.movies.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.WatchHistoryItem
import com.laterbox.app.ui.components.LaterboxIconButton
import com.laterbox.app.ui.components.LaterboxIcons
import com.laterbox.app.ui.theme.LaterboxTheme
import java.text.DateFormat
import java.util.Date

private enum class ViewingHistoryTab(val label: String, val tag: String) {
    ALL("All", "viewing_history_tab_all"),
    TITLES("Movies & Episodes", "viewing_history_tab_titles"),
    TRAILERS("Trailers", "viewing_history_tab_trailers")
}

@Composable
fun MoviesViewingHistoryScreen(
    items: List<WatchHistoryItem>,
    onBackClick: () -> Unit,
    onOpenDetail: (providerId: String, titleId: String) -> Unit,
    onOpenPlayer: (providerId: String, titleId: String, seasonNumber: Int?, episodeNumber: Int?) -> Unit,
    onDeleteItem: (historyKey: String) -> Unit,
    onClearHistory: (trailersOnly: Boolean?) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    var selectedTab by remember { mutableStateOf(ViewingHistoryTab.ALL) }
    var showClearDialog by remember { mutableStateOf(false) }

    val filteredItems = remember(items, selectedTab) {
        when (selectedTab) {
            ViewingHistoryTab.ALL -> items
            ViewingHistoryTab.TITLES -> items.filter { !it.isTrailer }
            ViewingHistoryTab.TRAILERS -> items.filter { it.isTrailer }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = colors.surfaceElevated,
            title = {
                Text(
                    text = "Clear Viewing History",
                    style = typography.sheetTitle,
                    color = colors.textPrimary
                )
            },
            text = {
                Text(
                    text = "Choose whether to remove only watched trailers or clear all watched movies, episodes, and trailers.",
                    style = typography.bodySecondary,
                    color = colors.textSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearHistory(null)
                        showClearDialog = false
                    },
                    modifier = Modifier.testTag("viewing_history_confirm_clear_all")
                ) {
                    Text("Clear All", color = colors.statusDanger)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text("Cancel", color = colors.textSecondary)
                    }
                    if (items.any { it.isTrailer }) {
                        TextButton(
                            onClick = {
                                onClearHistory(true)
                                showClearDialog = false
                            },
                            modifier = Modifier.testTag("viewing_history_confirm_clear_trailers")
                        ) {
                            Text("Clear Trailers", color = colors.accentPrimary)
                        }
                    }
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backgroundPrimary)
            .navigationBarsPadding()
            .testTag("movies_viewing_history_screen")
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                LaterboxIconButton(
                    iconRes = LaterboxIcons.ArrowLeft,
                    contentDescription = "Back",
                    onClick = onBackClick
                )
                Column {
                    Text(
                        text = "Viewing History",
                        style = typography.wordmark,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "${items.size} recorded ${if (items.size == 1) "entry" else "entries"}",
                        style = typography.metaText,
                        color = colors.textSecondary
                    )
                }
            }

            if (items.isNotEmpty()) {
                TextButton(
                    onClick = { showClearDialog = true },
                    modifier = Modifier.testTag("viewing_history_clear_button")
                ) {
                    Text("Clear", color = colors.statusDanger)
                }
            }
        }

        // Filter Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ViewingHistoryTab.entries.forEach { tab ->
                val isSelected = selectedTab == tab
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isSelected) colors.accentPrimary else colors.surfacePrimary,
                    border = BorderStroke(
                        0.75.dp,
                        if (isSelected) colors.accentPrimary else colors.borderSubtle
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { selectedTab = tab }
                        .testTag(tab.tag)
                ) {
                    Text(
                        text = tab.label,
                        style = typography.caption.copy(
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                        ),
                        color = if (isSelected) colors.onAccentPrimary else colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }
        }

        if (filteredItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp)
                    .testTag("viewing_history_empty_state"),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(LaterboxIcons.Clock),
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(34.dp)
                    )
                    Text(
                        text = when (selectedTab) {
                            ViewingHistoryTab.ALL -> "No viewing history yet"
                            ViewingHistoryTab.TITLES -> "No movies or episodes watched yet"
                            ViewingHistoryTab.TRAILERS -> "No trailers watched yet"
                        },
                        style = typography.titleLarge,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Titles and trailers you watch in Movies & TV will show up here.",
                        style = typography.bodySecondary,
                        color = colors.textSecondary
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("viewing_history_list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    items = filteredItems,
                    key = { it.historyKey.ifBlank { "${it.compositeId}_${it.lastWatchedAt}_${it.isTrailer}" } }
                ) { item ->
                    ViewingHistoryRowCard(
                        item = item,
                        onOpen = {
                            if (item.isTrailer) {
                                onOpenDetail(item.providerId, item.titleId)
                            } else {
                                onOpenPlayer(
                                    item.providerId,
                                    item.titleId,
                                    item.seasonNumber,
                                    item.episodeNumber
                                )
                            }
                        },
                        onOpenDetail = {
                            onOpenDetail(item.providerId, item.titleId)
                        },
                        onDelete = {
                            val key = item.historyKey.ifBlank { item.compositeId }
                            onDeleteItem(key)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewingHistoryRowCard(
    item: WatchHistoryItem,
    onOpen: () -> Unit,
    onOpenDetail: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val formattedDate = remember(item.lastWatchedAt) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.lastWatchedAt))
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surfacePrimary,
        border = BorderStroke(0.75.dp, colors.borderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .testTag("viewing_history_item_${item.historyKey}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Crisp 16:9 Thumbnail
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(colors.surfaceSecondary),
                contentAlignment = Alignment.Center
            ) {
                val thumbUrl = item.backdropUrl ?: item.posterUrl
                if (!thumbUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = thumbUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        painter = painterResource(LaterboxIcons.Movies),
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Kind / Trailer badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Black.copy(alpha = 0.70f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                ) {
                    val badgeText = when {
                        item.isTrailer -> "Trailer"
                        item.kind == MediaKind.TV_SERIES -> "Episode"
                        else -> "Film"
                    }
                    Text(
                        text = badgeText,
                        style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                        color = if (item.isTrailer) colors.accentPrimary else Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                if (!item.isTrailer && item.durationMs > 0L) {
                    LinearProgressIndicator(
                        progress = { item.progressFraction },
                        color = colors.accentPrimary,
                        trackColor = Color.White.copy(alpha = 0.28f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .align(Alignment.BottomCenter)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = item.title,
                    style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitleLine = buildList {
                    if (item.isTrailer) {
                        add("Trailer")
                    } else if (!item.episodeLabel.isNullOrBlank()) {
                        add(item.episodeLabel)
                    } else {
                        add(item.kind.label)
                    }
                    if (!item.isTrailer && item.durationMs > 0L) {
                        add("${(item.progressFraction * 100f).toInt().coerceIn(0, 100)}%")
                    }
                    add(formattedDate)
                }.joinToString(" · ")
                Text(
                    text = subtitleLine,
                    style = typography.caption,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = if (item.isTrailer) "View details" else "View details",
                    style = typography.caption.copy(fontWeight = FontWeight.Medium),
                    color = colors.accentPrimary,
                    modifier = Modifier
                        .clickable(onClick = onOpenDetail)
                        .padding(top = 2.dp)
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(34.dp)
                    .testTag("viewing_history_delete_${item.historyKey}")
            ) {
                Icon(
                    painter = painterResource(LaterboxIcons.Delete),
                    contentDescription = "Remove from viewing history",
                    tint = colors.textTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
