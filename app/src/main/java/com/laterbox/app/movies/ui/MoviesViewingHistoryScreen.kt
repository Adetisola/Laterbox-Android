package com.laterbox.app.movies.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.WatchHistoryItem
import com.laterbox.app.ui.components.LaterboxIconButton
import com.laterbox.app.ui.components.LaterboxIcons
import com.laterbox.app.ui.theme.LaterboxTheme
import java.text.DateFormat
import java.util.Date

private enum class ViewingHistoryTab(val label: String, val tag: String) {
    ALL("All", "viewing_history_tab_all"),
    MOVIES("Movies", "viewing_history_tab_movies"),
    EPISODES("Episodes", "viewing_history_tab_episodes"),
    TRAILERS("Trailers", "viewing_history_tab_trailers"),
    RECENTLY_VIEWED("Recently Viewed", "viewing_history_tab_recent"),
    HIDDEN("Hidden", "viewing_history_tab_hidden")
}

@Composable
fun MoviesViewingHistoryScreen(
    items: List<WatchHistoryItem>,
    onBackClick: () -> Unit,
    onOpenDetail: (providerId: String, titleId: String) -> Unit,
    onOpenPlayer: (providerId: String, titleId: String, seasonNumber: Int?, episodeNumber: Int?) -> Unit,
    onDeleteItem: (historyKey: String) -> Unit,
    onClearHistory: (trailersOnly: Boolean?) -> Unit,
    recentlyViewed: List<MovieTitleSummary> = emptyList(),
    hiddenTitles: List<WatchHistoryItem> = emptyList(),
    onUnhideTitle: (providerId: String, titleId: String) -> Unit = { _, _ -> },
    onRemoveRecentlyViewed: (providerId: String, titleId: String) -> Unit = { _, _ -> },
    onClearRecentlyViewed: () -> Unit = {},
    isLoading: Boolean = false,
    seriesMetadataByKey: Map<String, ViewingHistorySeriesMetadata> = emptyMap(),
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    var selectedTab by rememberSaveable { mutableStateOf(ViewingHistoryTab.ALL) }
    var selectedSeriesKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSeasonKey by rememberSaveable { mutableStateOf<String?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    var pendingRemoveViewed by remember { mutableStateOf<MovieTitleSummary?>(null) }

    val filteredHistoryItems = remember(items, selectedTab) {
        when (selectedTab) {
            ViewingHistoryTab.ALL -> items
            ViewingHistoryTab.MOVIES -> items.filter { !it.isTrailer && it.kind == MediaKind.MOVIE }
            ViewingHistoryTab.EPISODES -> items.filter { !it.isTrailer && it.kind.isEpisodic }
            ViewingHistoryTab.TRAILERS -> items.filter { it.isTrailer }
            ViewingHistoryTab.RECENTLY_VIEWED, ViewingHistoryTab.HIDDEN -> emptyList()
        }
    }

    val topLevelEntries = remember(filteredHistoryItems, selectedTab, seriesMetadataByKey) {
        when (selectedTab) {
            ViewingHistoryTab.ALL, ViewingHistoryTab.EPISODES ->
                ViewingHistoryHierarchyBuilder.buildTopLevelEntries(
                    items = filteredHistoryItems,
                    metadataBySeriesKey = seriesMetadataByKey
                )
            ViewingHistoryTab.MOVIES, ViewingHistoryTab.TRAILERS ->
                filteredHistoryItems.map { ViewingHistoryTopLevelEntry.Individual(it) }
            ViewingHistoryTab.RECENTLY_VIEWED, ViewingHistoryTab.HIDDEN ->
                emptyList()
        }
    }

    val activeSeriesGroup = remember(topLevelEntries, selectedSeriesKey) {
        val key = selectedSeriesKey ?: return@remember null
        topLevelEntries.asSequence()
            .filterIsInstance<ViewingHistoryTopLevelEntry.SeriesGroup>()
            .firstOrNull { it.series.seriesKey == key }
            ?.series
    }

    val activeSeasonGroup = remember(activeSeriesGroup, selectedSeasonKey) {
        val series = activeSeriesGroup ?: return@remember null
        if (!series.shouldGroupBySeason) return@remember null
        val sKey = selectedSeasonKey ?: return@remember null
        series.seasons.firstOrNull { it.seasonKey == sKey }
    }

    LaunchedEffect(selectedSeriesKey, activeSeriesGroup, activeSeasonGroup) {
        if (selectedSeriesKey != null && activeSeriesGroup == null) {
            selectedSeasonKey = null
            selectedSeriesKey = null
        } else if (selectedSeasonKey != null && activeSeasonGroup == null) {
            selectedSeasonKey = null
        }
    }

    val navigateUpHierarchy: () -> Unit = {
        when {
            activeSeasonGroup != null -> selectedSeasonKey = null
            activeSeriesGroup != null -> {
                selectedSeasonKey = null
                selectedSeriesKey = null
            }
            else -> onBackClick()
        }
    }

    BackHandler(enabled = activeSeriesGroup != null) {
        navigateUpHierarchy()
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = colors.surfaceElevated,
            title = {
                Text(
                    text = if (selectedTab == ViewingHistoryTab.RECENTLY_VIEWED) {
                        "Clear Recently Viewed"
                    } else {
                        "Clear Viewing History"
                    },
                    style = typography.sheetTitle,
                    color = colors.textPrimary
                )
            },
            text = {
                Text(
                    text = if (selectedTab == ViewingHistoryTab.RECENTLY_VIEWED) {
                        "Remove all titles from your Recently Viewed list?"
                    } else {
                        "Choose whether to remove only watched trailers or clear all watched movies, episodes, and trailers."
                    },
                    style = typography.bodySecondary,
                    color = colors.textSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (selectedTab == ViewingHistoryTab.RECENTLY_VIEWED) {
                            onClearRecentlyViewed()
                        } else {
                            onClearHistory(null)
                        }
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
                    if (selectedTab != ViewingHistoryTab.RECENTLY_VIEWED && items.any { it.isTrailer }) {
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

    pendingRemoveViewed?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRemoveViewed = null },
            containerColor = colors.surfaceElevated,
            title = {
                Text(
                    text = "Remove from Viewed",
                    style = typography.titleLarge,
                    color = colors.textPrimary
                )
            },
            text = {
                Text(
                    text = "Remove \"${target.title}\" from your Recently Viewed list? Your watch history, progress, and watchlist will not be affected.",
                    style = typography.bodySecondary,
                    color = colors.textSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveRecentlyViewed(target.providerId, target.id)
                        pendingRemoveViewed = null
                    },
                    modifier = Modifier.testTag("viewing_history_confirm_remove_viewed")
                ) {
                    Text("Remove", color = colors.statusDanger)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingRemoveViewed = null },
                    modifier = Modifier.testTag("viewing_history_cancel_remove_viewed")
                ) {
                    Text("Cancel", color = colors.textSecondary)
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
                    onClick = navigateUpHierarchy
                )
                Column {
                    val headerTitle = when {
                        activeSeasonGroup != null && activeSeriesGroup != null ->
                            "${activeSeriesGroup.title} · ${activeSeasonGroup.label}"
                        activeSeriesGroup != null -> activeSeriesGroup.title
                        else -> "Viewing History"
                    }
                    Text(
                        text = headerTitle,
                        style = typography.wordmark,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (activeSeriesGroup != null) {
                TextButton(
                    onClick = { onOpenDetail(activeSeriesGroup.providerId, activeSeriesGroup.titleId) },
                    modifier = Modifier.testTag("viewing_history_series_view_details")
                ) {
                    Text("View details", color = colors.accentPrimary)
                }
            } else {
                val canClear = when (selectedTab) {
                    ViewingHistoryTab.RECENTLY_VIEWED -> recentlyViewed.isNotEmpty()
                    ViewingHistoryTab.HIDDEN -> false
                    else -> items.isNotEmpty()
                }
                if (canClear) {
                    TextButton(
                        onClick = { showClearDialog = true },
                        modifier = Modifier.testTag("viewing_history_clear_button")
                    ) {
                        Text("Clear", color = colors.statusDanger)
                    }
                }
            }
        }

        // Scrollable Filter Tabs (shown at top level)
        if (activeSeriesGroup == null) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("viewing_history_tabs_row"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(ViewingHistoryTab.entries, key = { it.name }) { tab ->
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
                            .clickable {
                                selectedSeriesKey = null
                                selectedSeasonKey = null
                                selectedTab = tab
                            }
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
        }

        if (isLoading && items.isEmpty() && recentlyViewed.isEmpty() && hiddenTitles.isEmpty()) {
            com.laterbox.app.ui.components.ViewingHistorySkeletonList(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("viewing_history_skeleton")
            )
        } else when (selectedTab) {
            ViewingHistoryTab.RECENTLY_VIEWED -> {
                if (recentlyViewed.isEmpty()) {
                    ViewingHistoryEmptyState(
                        title = "No recently viewed titles",
                        subtitle = "Titles whose detail pages you open appear here."
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("viewing_history_recent_list"),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(recentlyViewed, key = { "recent_${it.compositeId}" }) { summary ->
                            RecentlyViewedRowCard(
                                item = summary,
                                onOpenDetail = { onOpenDetail(summary.providerId, summary.id) },
                                onRemove = { pendingRemoveViewed = summary }
                            )
                        }
                    }
                }
            }
            ViewingHistoryTab.HIDDEN -> {
                if (hiddenTitles.isEmpty()) {
                    ViewingHistoryEmptyState(
                        title = "No hidden titles",
                        subtitle = "Titles you choose to hide from Home or Catalogue can be restored here."
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("viewing_history_hidden_list"),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(hiddenTitles, key = { "hidden_${it.compositeId}" }) { item ->
                            HiddenTitleRowCard(
                                item = item,
                                onOpenDetail = { onOpenDetail(item.providerId, item.titleId) },
                                onUnhide = { onUnhideTitle(item.providerId, item.titleId) }
                            )
                        }
                    }
                }
            }
            else -> {
                when {
                    activeSeasonGroup != null -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("viewing_history_season_episodes_list"),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(
                                items = activeSeasonGroup.episodes,
                                key = { ViewingHistoryHierarchyBuilder.individualStableKey(it) }
                            ) { episode ->
                                ViewingHistoryRowCard(
                                    item = episode,
                                    onOpen = {
                                        onOpenPlayer(
                                            episode.providerId,
                                            episode.titleId,
                                            episode.seasonNumber,
                                            episode.episodeNumber
                                        )
                                    },
                                    onOpenDetail = {
                                        onOpenDetail(episode.providerId, episode.titleId)
                                    },
                                    onDelete = {
                                        val key = episode.historyKey.ifBlank { episode.compositeId }
                                        onDeleteItem(key)
                                    }
                                )
                            }
                        }
                    }
                    activeSeriesGroup != null && activeSeriesGroup.shouldGroupBySeason -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("viewing_history_seasons_list"),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(
                                items = activeSeriesGroup.seasons,
                                key = { "season_${activeSeriesGroup.seriesKey}_${it.seasonKey}" }
                            ) { season ->
                                ViewingHistorySeasonCard(
                                    seriesKey = activeSeriesGroup.seriesKey,
                                    season = season,
                                    onOpenSeason = { selectedSeasonKey = season.seasonKey }
                                )
                            }
                        }
                    }
                    activeSeriesGroup != null -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("viewing_history_series_episodes_list"),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(
                                items = activeSeriesGroup.allEpisodesSorted,
                                key = { ViewingHistoryHierarchyBuilder.individualStableKey(it) }
                            ) { episode ->
                                ViewingHistoryRowCard(
                                    item = episode,
                                    onOpen = {
                                        onOpenPlayer(
                                            episode.providerId,
                                            episode.titleId,
                                            episode.seasonNumber,
                                            episode.episodeNumber
                                        )
                                    },
                                    onOpenDetail = {
                                        onOpenDetail(episode.providerId, episode.titleId)
                                    },
                                    onDelete = {
                                        val key = episode.historyKey.ifBlank { episode.compositeId }
                                        onDeleteItem(key)
                                    }
                                )
                            }
                        }
                    }
                    topLevelEntries.isEmpty() -> {
                        ViewingHistoryEmptyState(
                            title = when (selectedTab) {
                                ViewingHistoryTab.ALL -> "No viewing history yet"
                                ViewingHistoryTab.MOVIES -> "No movies watched yet"
                                ViewingHistoryTab.EPISODES -> "No episodes watched yet"
                                ViewingHistoryTab.TRAILERS -> "No trailers watched yet"
                                else -> "No viewing history yet"
                            },
                            subtitle = "Titles and trailers you watch in Movies & TV will show up here."
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("viewing_history_list"),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(
                                items = topLevelEntries,
                                key = { it.stableKey }
                            ) { entry ->
                                when (entry) {
                                    is ViewingHistoryTopLevelEntry.Individual -> {
                                        val item = entry.item
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
                                    is ViewingHistoryTopLevelEntry.SeriesGroup -> {
                                        val series = entry.series
                                        ViewingHistorySeriesCard(
                                            series = series,
                                            onOpenSeries = {
                                                selectedSeriesKey = series.seriesKey
                                                selectedSeasonKey = null
                                            },
                                            onOpenDetail = {
                                                onOpenDetail(series.providerId, series.titleId)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewingHistoryEmptyState(
    title: String,
    subtitle: String
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
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
                text = title,
                style = typography.titleLarge,
                color = colors.textPrimary
            )
            Text(
                text = subtitle,
                style = typography.bodySecondary,
                color = colors.textSecondary
            )
        }
    }
}

@kotlin.OptIn(ExperimentalLayoutApi::class)
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
                        item.kind == MediaKind.SHORT_DRAMA -> "Short Drama"
                        item.kind == MediaKind.TV_SERIES -> "Episode"
                        else -> "Movie"
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

                if (item.isCompleted || item.rewatchCount > 0) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (item.isCompleted) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = colors.accentContainer
                            ) {
                                Text(
                                    text = "Completed",
                                    style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                    color = colors.accentPrimary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (item.rewatchCount > 0) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = colors.surfaceSecondary,
                                modifier = Modifier.testTag("viewing_history_rewatch_${item.historyKey}")
                            ) {
                                Text(
                                    text = "Rewatched ×${item.rewatchCount}",
                                    style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                    color = colors.textSecondary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "View details",
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

@Composable
private fun ViewingHistorySeriesCard(
    series: ViewingHistorySeriesGroup,
    onOpenSeries: () -> Unit,
    onOpenDetail: () -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val formattedDate = remember(series.latestWatchedAt) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(series.latestWatchedAt))
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surfacePrimary,
        border = BorderStroke(0.75.dp, colors.borderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpenSeries)
            .testTag("viewing_history_series_${series.seriesKey}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(colors.surfaceSecondary),
                contentAlignment = Alignment.Center
            ) {
                if (!series.artworkUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = series.artworkUrl,
                        contentDescription = series.title,
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

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Black.copy(alpha = 0.70f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                ) {
                    Text(
                        text = if (series.kind == MediaKind.SHORT_DRAMA) "Short Drama" else "TV Series",
                        style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                val pct = series.completionPercent
                if (pct != null) {
                    LinearProgressIndicator(
                        progress = { (pct / 100f).coerceIn(0f, 1f) },
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
                    text = series.title,
                    style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val summaryParts = buildList {
                    val epText = if (series.knownTotalEpisodes != null && series.completionPercent != null) {
                        "${series.watchedEpisodeCount} of ${series.knownTotalEpisodes} episodes · ${series.completionPercent}%"
                    } else {
                        "${series.watchedEpisodeCount} episodes watched"
                    }
                    add(epText)
                    if (series.shouldGroupBySeason) {
                        add("${series.seasonCountInHistory} seasons")
                    }
                }.joinToString(" · ")
                Text(
                    text = summaryParts,
                    style = typography.caption,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val latestLabel = ViewingHistoryHierarchyBuilder.formatEpisodeShortLabel(series.latestEpisode)
                Text(
                    text = "Latest: $latestLabel · $formattedDate",
                    style = typography.caption,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = "View details",
                    style = typography.caption.copy(fontWeight = FontWeight.Medium),
                    color = colors.accentPrimary,
                    modifier = Modifier
                        .clickable(onClick = onOpenDetail)
                        .padding(top = 2.dp)
                )
            }

            Icon(
                painter = painterResource(LaterboxIcons.ChevronRight),
                contentDescription = "Open ${series.title}",
                tint = colors.textTertiary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ViewingHistorySeasonCard(
    seriesKey: String,
    season: ViewingHistorySeasonGroup,
    onOpenSeason: () -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val formattedDate = remember(season.latestWatchedAt) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(season.latestWatchedAt))
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surfacePrimary,
        border = BorderStroke(0.75.dp, colors.borderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpenSeason)
            .testTag("viewing_history_season_${seriesKey}_${season.seasonKey}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(colors.surfaceSecondary),
                contentAlignment = Alignment.Center
            ) {
                if (!season.artworkUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = season.artworkUrl,
                        contentDescription = season.label,
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

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Black.copy(alpha = 0.70f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                ) {
                    Text(
                        text = season.label,
                        style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                val pct = season.completionPercent
                if (pct != null) {
                    LinearProgressIndicator(
                        progress = { (pct / 100f).coerceIn(0f, 1f) },
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
                    text = season.label,
                    style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val countSummary = if (season.knownTotalEpisodes != null && season.completionPercent != null) {
                    "${season.watchedEpisodeCount} of ${season.knownTotalEpisodes} episodes · ${season.completionPercent}%"
                } else {
                    "${season.watchedEpisodeCount} ${if (season.watchedEpisodeCount == 1) "episode" else "episodes"} watched"
                }
                Text(
                    text = countSummary,
                    style = typography.caption,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val latestLabel = ViewingHistoryHierarchyBuilder.formatEpisodeShortLabel(season.latestEpisode)
                Text(
                    text = "Latest: $latestLabel · $formattedDate",
                    style = typography.caption,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                painter = painterResource(LaterboxIcons.ChevronRight),
                contentDescription = "Open ${season.label}",
                tint = colors.textTertiary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun RecentlyViewedRowCard(
    item: MovieTitleSummary,
    onOpenDetail: () -> Unit,
    onRemove: () -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surfacePrimary,
        border = BorderStroke(0.75.dp, colors.borderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpenDetail)
            .testTag("viewing_history_recent_item_${item.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(50.dp)
                    .height(72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceSecondary),
                contentAlignment = Alignment.Center
            ) {
                if (!item.posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = item.posterUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        painter = painterResource(LaterboxIcons.Movies),
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(20.dp)
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
                val meta = buildList {
                    add(item.kind.label)
                    item.releaseYear?.let { add(it.toString()) }
                    item.genres.firstOrNull()?.let { add(it) }
                }.joinToString(" · ")
                Text(
                    text = meta,
                    style = typography.caption,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.testTag("viewing_history_remove_recent_${item.id}")
            ) {
                Icon(
                    painter = painterResource(LaterboxIcons.Close),
                    contentDescription = "Remove from recently viewed",
                    tint = colors.textTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun HiddenTitleRowCard(
    item: WatchHistoryItem,
    onOpenDetail: () -> Unit,
    onUnhide: () -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surfacePrimary,
        border = BorderStroke(0.75.dp, colors.borderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpenDetail)
            .testTag("viewing_history_hidden_item_${item.titleId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = item.title,
                    style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.kind.label,
                    style = typography.caption,
                    color = colors.textSecondary
                )
            }
            TextButton(
                onClick = onUnhide,
                modifier = Modifier.testTag("viewing_history_unhide_${item.titleId}")
            ) {
                Text("Unhide", color = colors.accentPrimary)
            }
        }
    }
}
