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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.ui.components.LaterboxIcons
import com.laterbox.app.ui.theme.LaterboxTheme

@Composable
fun MoviesScreen(
    uiState: MoviesHomeUiState,
    onRefresh: () -> Unit,
    onOpenCatalogue: (kind: MediaKind?, genre: String?) -> Unit,
    onOpenDetail: (providerId: String, titleId: String) -> Unit,
    onOpenPlayer: (providerId: String, titleId: String, seasonNumber: Int?, episodeNumber: Int?) -> Unit,
    onToggleWatchlist: (MovieTitleSummary) -> Unit,
    onToggleSampleFixtures: (Boolean) -> Unit,
    onSaveTmdbApiKey: (String) -> Unit,
    onMenuClick: (() -> Unit)? = null,
    bottomPaddingDp: Int = 96,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    var showSourcesDialog by remember { mutableStateOf(false) }

    if (showSourcesDialog) {
        MoviesProviderSourceDialog(
            useSampleFixturesOnly = uiState.useSampleFixturesOnly,
            currentTmdbKey = uiState.currentTmdbApiKey,
            onToggleSampleFixtures = onToggleSampleFixtures,
            onSaveTmdbKey = onSaveTmdbApiKey,
            onDismiss = { showSourcesDialog = false }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backgroundPrimary)
            .testTag("movies_home_screen")
    ) {
        // Editorial Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onMenuClick != null) {
                IconButton(
                    onClick = onMenuClick,
                    modifier = Modifier.testTag("movies_menu_button")
                ) {
                    Icon(
                        painter = painterResource(LaterboxIcons.Menu),
                        contentDescription = "Open menu",
                        tint = colors.textPrimary
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Movies & TV",
                    style = typography.editorialDisplay.copy(fontSize = 26.sp, lineHeight = 30.sp),
                    color = colors.textPrimary
                )
                Text(
                    text = if (uiState.useSampleFixturesOnly) {
                        "Offline sample cinema mode"
                    } else {
                        "Open cinema & series discovery"
                    },
                    style = typography.metaText,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = { onOpenCatalogue(null, null) },
                modifier = Modifier.testTag("movies_search_button")
            ) {
                Icon(
                    painter = painterResource(LaterboxIcons.Search),
                    contentDescription = "Search Movies & TV",
                    tint = colors.textPrimary
                )
            }
            IconButton(
                onClick = { showSourcesDialog = true },
                modifier = Modifier.testTag("movies_sources_button")
            ) {
                Icon(
                    painter = painterResource(LaterboxIcons.Filter),
                    contentDescription = "Data sources",
                    tint = colors.textPrimary
                )
            }
        }

        if (uiState.isRefreshing) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = colors.accentPrimary,
                trackColor = colors.surfaceSecondary
            )
        }

        when {
            uiState.isLoading && uiState.feed == null -> {
                MoviesHomeSkeleton()
            }
            uiState.feed == null && uiState.errorMessage != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = colors.surfacePrimary,
                        border = BorderStroke(1.dp, colors.borderSubtle),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                painter = painterResource(LaterboxIcons.Movies),
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = colors.accentPrimary
                            )
                            Text(
                                text = "Could not load live catalogue",
                                style = typography.titleLarge,
                                color = colors.textPrimary
                            )
                            Text(
                                text = uiState.errorMessage,
                                style = typography.bodySecondary,
                                color = colors.textSecondary
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = onRefresh,
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.accentPrimary),
                                    modifier = Modifier.testTag("movies_retry_button")
                                ) {
                                    Text("Retry")
                                }
                                OutlinedButton(
                                    onClick = { onToggleSampleFixtures(true) },
                                    border = BorderStroke(1.dp, colors.borderSubtle)
                                ) {
                                    Text("Sample Fixtures", color = colors.textPrimary)
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                val feed = uiState.feed ?: return@Column
                val watchlistIds = remember(uiState.watchlist) {
                    uiState.watchlist.map { it.compositeId }.toSet()
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("movies_home_list"),
                    contentPadding = PaddingValues(top = 6.dp, bottom = bottomPaddingDp.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Notice banner for offline cache / sample fixtures
                    if (feed.isOfflineCached || feed.containsSampleFixtures) {
                        item(key = "notice_banner") {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (feed.containsSampleFixtures) {
                                    colors.statusWarning.copy(alpha = 0.14f)
                                } else {
                                    colors.accentContainer
                                },
                                border = BorderStroke(
                                    0.75.dp,
                                    if (feed.containsSampleFixtures) colors.statusWarning.copy(alpha = 0.35f)
                                    else colors.accentPrimary.copy(alpha = 0.25f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .testTag("movies_notice_banner")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = feed.activeProviderNotice ?: "Showing cached items",
                                        style = typography.metaText,
                                        color = colors.textPrimary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (feed.containsSampleFixtures) {
                                        TextButton(onClick = { onToggleSampleFixtures(false) }) {
                                            Text("Use Live", color = colors.accentPrimary)
                                        }
                                    } else {
                                        TextButton(onClick = onRefresh) {
                                            Text("Refresh", color = colors.accentPrimary)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Featured Hero Carousel
                    if (feed.featuredBanners.isNotEmpty()) {
                        item(key = "hero_carousel") {
                            MoviesHeroCarousel(
                                banners = feed.featuredBanners,
                                watchlistIds = watchlistIds,
                                onBannerClick = { item -> onOpenDetail(item.providerId, item.id) },
                                onPlayClick = { item -> onOpenPlayer(item.providerId, item.id, null, null) },
                                onToggleWatchlist = onToggleWatchlist
                            )
                        }
                    }

                    // Quick Catalogue Shortcuts & Genre Filter Chips
                    item(key = "quick_browse_bar") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                QuickCategoryPill(
                                    label = "All Catalogue",
                                    onClick = { onOpenCatalogue(null, null) },
                                    modifier = Modifier.weight(1f).testTag("browse_all_pill")
                                )
                                QuickCategoryPill(
                                    label = "Movies",
                                    onClick = { onOpenCatalogue(MediaKind.MOVIE, null) },
                                    modifier = Modifier.weight(1f).testTag("browse_movies_pill")
                                )
                                QuickCategoryPill(
                                    label = "TV Series",
                                    onClick = { onOpenCatalogue(MediaKind.TV_SERIES, null) },
                                    modifier = Modifier.weight(1f).testTag("browse_tv_pill")
                                )
                            }

                            if (feed.genres.isNotEmpty()) {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(feed.genres.filterNot { it.equals("All", ignoreCase = true) }) { genre ->
                                        Surface(
                                            shape = RoundedCornerShape(20.dp),
                                            color = colors.surfacePrimary,
                                            border = BorderStroke(0.75.dp, colors.borderSubtle),
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(20.dp))
                                                .clickable { onOpenCatalogue(null, genre) }
                                        ) {
                                            Text(
                                                text = genre,
                                                style = typography.uiLabel.copy(fontSize = 12.sp),
                                                color = colors.textPrimary,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Continue Watching Rail
                    if (uiState.continueWatching.isNotEmpty()) {
                        item(key = "continue_watching_rail") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SectionHeaderRow(
                                    title = "Continue Watching",
                                    subtitle = "Pick up where you left off",
                                    onSeeAll = null
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(uiState.continueWatching, key = { it.compositeId + (it.episodeLabel ?: "") }) { entry ->
                                        ContinueWatchingCard(
                                            item = entry,
                                            onClick = {
                                                onOpenPlayer(
                                                    entry.providerId,
                                                    entry.titleId,
                                                    entry.seasonNumber,
                                                    entry.episodeNumber
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Watchlist Rail
                    if (uiState.watchlist.isNotEmpty()) {
                        item(key = "watchlist_rail") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SectionHeaderRow(
                                    title = "Your Watchlist",
                                    subtitle = "${uiState.watchlist.size} saved titles",
                                    onSeeAll = null
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(uiState.watchlist, key = { "wl_${it.compositeId}" }) { item ->
                                        MoviePosterCard(
                                            item = item,
                                            onClick = { onOpenDetail(item.providerId, item.id) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Discovery Feed Rails
                    items(feed.sections, key = { it.id }) { section ->
                        if (section.items.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                val inferKind = section.items.firstOrNull()?.kind
                                SectionHeaderRow(
                                    title = section.title,
                                    subtitle = section.subtitle,
                                    onSeeAll = { onOpenCatalogue(inferKind, null) }
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(section.items, key = { "${section.id}_${it.compositeId}" }) { item ->
                                        MoviePosterCard(
                                            item = item,
                                            onClick = { onOpenDetail(item.providerId, item.id) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Attribution footer
                    item(key = "provider_footer") {
                        Text(
                            text = feed.activeProviderNotice ?: "Powered by Internet Archive & TVMaze",
                            style = typography.caption,
                            color = colors.textTertiary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickCategoryPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = colors.surfacePrimary,
        border = BorderStroke(0.75.dp, colors.borderSubtle)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = LaterboxTheme.typography.uiLabel.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SectionHeaderRow(
    title: String,
    subtitle: String?,
    onSeeAll: (() -> Unit)?
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = typography.metaText,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (onSeeAll != null) {
            TextButton(onClick = onSeeAll) {
                Text(
                    text = "See all",
                    style = typography.uiLabel.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.accentPrimary
                )
            }
        }
    }
}
