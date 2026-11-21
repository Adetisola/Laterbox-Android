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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.laterbox.app.domain.model.Collection
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.OfflineMediaMatch
import com.laterbox.app.ui.components.LaterboxIconButton
import com.laterbox.app.ui.components.LaterboxIcons
import com.laterbox.app.ui.components.MediaCatalogueSkeletonGrid
import com.laterbox.app.ui.theme.LaterboxTheme

private enum class WatchlistKindFilter(val label: String, val tag: String) {
    ALL("All", "watchlist_filter_all"),
    MOVIES("Movies", "watchlist_filter_movies"),
    TV_SERIES("TV Series", "watchlist_filter_tv"),
    SHORT_DRAMAS("Short Dramas", "watchlist_filter_short_drama")
}

@Composable
fun MoviesWatchlistScreen(
    watchlist: List<MovieTitleSummary>,
    offlineMatches: List<OfflineMediaMatch> = emptyList(),
    collections: List<Collection> = emptyList(),
    isLoading: Boolean = false,
    onBackClick: () -> Unit,
    onExploreClick: () -> Unit,
    onOpenDetail: (providerId: String, titleId: String) -> Unit,
    onToggleWatchlist: (MovieTitleSummary) -> Unit,
    onMarkWatched: (MovieTitleSummary) -> Unit = {},
    onHideTitle: (MovieTitleSummary) -> Unit = {},
    onAddToCollection: (MovieTitleSummary, Long) -> Unit = { _, _ -> },
    onCreateAndAddToCollection: (MovieTitleSummary, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    var kindFilter by remember { mutableStateOf(WatchlistKindFilter.ALL) }
    var collectionPickerTarget by remember { mutableStateOf<MovieTitleSummary?>(null) }

    val offlineLookup = remember(offlineMatches) { OfflineMediaLookup.from(offlineMatches) }

    val filteredItems = remember(watchlist, kindFilter) {
        when (kindFilter) {
            WatchlistKindFilter.ALL -> watchlist
            WatchlistKindFilter.MOVIES -> watchlist.filter { it.kind == MediaKind.MOVIE }
            WatchlistKindFilter.TV_SERIES -> watchlist.filter { it.kind == MediaKind.TV_SERIES }
            WatchlistKindFilter.SHORT_DRAMAS -> watchlist.filter { it.kind == MediaKind.SHORT_DRAMA }
        }
    }

    collectionPickerTarget?.let { target ->
        MovieCollectionPickerDialog(
            title = target.title,
            collections = collections,
            onSelectCollection = { colId ->
                onAddToCollection(target, colId)
                collectionPickerTarget = null
            },
            onCreateAndAddCollection = { name ->
                onCreateAndAddToCollection(target, name)
                collectionPickerTarget = null
            },
            onDismiss = { collectionPickerTarget = null }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backgroundPrimary)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("movies_watchlist_screen")
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LaterboxIconButton(
                iconRes = LaterboxIcons.ArrowLeft,
                contentDescription = "Back",
                onClick = onBackClick,
                modifier = Modifier.testTag("watchlist_back_button")
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Watchlist",
                    style = typography.sheetTitle,
                    color = colors.textPrimary
                )
                Text(
                    text = "${filteredItems.size} saved title${if (filteredItems.size == 1) "" else "s"}",
                    style = typography.metaText,
                    color = colors.textSecondary
                )
            }
        }

        // Filter Chips (All / Movies / TV Series)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WatchlistKindFilter.entries.forEach { option ->
                val selected = kindFilter == option
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (selected) colors.accentPrimary else colors.surfacePrimary,
                    border = BorderStroke(
                        0.75.dp,
                        if (selected) colors.accentPrimary else colors.borderSubtle
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { kindFilter = option }
                        .testTag(option.tag)
                ) {
                    Text(
                        text = option.label,
                        style = typography.caption.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                        ),
                        color = if (selected) colors.onAccentPrimary else colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }
        }

        when {
            isLoading && watchlist.isEmpty() -> {
                MediaCatalogueSkeletonGrid(
                    columns = 2,
                    rows = 3,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("movies_watchlist_skeleton")
                )
            }

            filteredItems.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp)
                        .testTag("movies_watchlist_empty_state"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(LaterboxIcons.Bookmark),
                            contentDescription = null,
                            tint = colors.textTertiary,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = if (watchlist.isEmpty()) "Your watchlist is empty" else "No matching saved titles",
                            style = typography.titleLarge,
                            color = colors.textPrimary
                        )
                        Text(
                            text = if (watchlist.isEmpty()) {
                                "Bookmark movies and series from discovery or detail pages to watch later."
                            } else {
                                "Try switching filters or exploring more titles."
                            },
                            style = typography.bodySecondary,
                            color = colors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Button(
                            onClick = onExploreClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.accentPrimary,
                                contentColor = colors.onAccentPrimary
                            ),
                            modifier = Modifier.testTag("watchlist_explore_cta")
                        ) {
                            Text("Explore Movies & TV")
                        }
                    }
                }
            }

            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("movies_watchlist_grid")
                ) {
                    items(filteredItems, key = { "wl_grid_${it.compositeId}" }) { item ->
                        val isOffline = offlineLookup.hasTitle(item.providerId, item.id)
                        val watchlistContext = androidx.compose.ui.platform.LocalContext.current
                        MoviePosterCard(
                            item = item,
                            onClick = { onOpenDetail(item.providerId, item.id) },
                            modifier = Modifier.fillMaxWidth(),
                            isOfflineDownloaded = isOffline,
                            isInWatchlist = true,
                            onToggleWatchlist = onToggleWatchlist,
                            onAddToCollection = { collectionPickerTarget = it },
                            onMarkWatched = onMarkWatched,
                            onHideTitle = onHideTitle,
                            onShareTitle = { com.laterbox.app.media.share.LaterboxShareHelper.shareTitle(watchlistContext, it) },
                            menuButtonTestTag = "watchlist_card_menu_${item.id}"
                        )
                    }
                }
            }
        }
    }
}
