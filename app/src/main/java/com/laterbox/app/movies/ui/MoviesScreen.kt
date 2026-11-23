package com.laterbox.app.movies.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.laterbox.app.BuildConfig
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesDiscoveryShortcut
import com.laterbox.app.movies.domain.model.RuntimeBucket
import com.laterbox.app.movies.domain.model.WatchHistoryItem
import com.laterbox.app.ui.components.LaterboxIconButton
import com.laterbox.app.ui.components.LaterboxIcons
import com.laterbox.app.ui.components.LaterboxPrimaryButton
import com.laterbox.app.ui.components.LaterboxSecondaryButton
import com.laterbox.app.ui.components.LaterboxTabHeader
import com.laterbox.app.ui.theme.LaterboxTheme
import kotlinx.coroutines.delay
import kotlin.math.abs

private val YEAR_FILTER_OPTIONS = listOf("All", "2025", "2024", "2020s", "2010s", "2000s", "Classic")
private val PRIORITY_GENRE_ORDER = listOf("Anime", "Action", "Adventure", "Biography")

@Composable
fun MoviesScreen(
    uiState: MoviesHomeUiState,
    onRefresh: () -> Unit,
    onOpenCatalogue: (kind: MediaKind?, genre: String?) -> Unit,
    onOpenDetail: (providerId: String, titleId: String) -> Unit,
    onOpenPlayer: (providerId: String, titleId: String, seasonNumber: Int?, episodeNumber: Int?) -> Unit,
    onToggleWatchlist: (MovieTitleSummary) -> Unit,
    onToggleSampleFixtures: (Boolean) -> Unit,
    onSaveMovieBoxBaseUrl: (baseUrl: String) -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onSearchFocusChange: (Boolean) -> Unit = {},
    onSubmitSearch: (String?) -> Unit = {},
    onClearSearch: () -> Unit = {},
    onClearSearchHistory: () -> Unit = {},
    onSelectKind: (MediaKind?) -> Unit = {},
    onSelectGenre: (String) -> Unit = {},
    onSelectCountry: (String) -> Unit = {},
    onSelectShortcut: (MoviesDiscoveryShortcut) -> Unit = {},
    onSelectSort: (CatalogueSort) -> Unit = {},
    onResetFilters: () -> Unit = {},
    onLoadMoreFiltered: () -> Unit = {},
    onOpenCatalogueWithSort: (kind: MediaKind?, genre: String?, sort: CatalogueSort?, country: String?) -> Unit = { k, g, _, _ ->
        onOpenCatalogue(k, g)
    },
    onSelectLanguage: (String) -> Unit = {},
    onSelectYear: (String) -> Unit = {},
    onSelectRuntimeBucket: (RuntimeBucket) -> Unit = {},
    onRerollSurprise: (MediaKind?) -> Unit = {},
    onMarkWatched: (MovieTitleSummary) -> Unit = {},
    onHideTitle: (MovieTitleSummary) -> Unit = {},
    onAddToCollection: (MovieTitleSummary, Long) -> Unit = { _, _ -> },
    onCreateAndAddToCollection: (MovieTitleSummary, String) -> Unit = { _, _ -> },
    onRestartContinueWatching: (WatchHistoryItem) -> Unit = {},
    onRemoveContinueWatching: (WatchHistoryItem) -> Unit = {},
    onMarkContinueWatchingWatched: (WatchHistoryItem) -> Unit = {},
    onRemoveRecentlyViewed: (MovieTitleSummary) -> Unit = {},
    onOpenCollection: (collectionId: Long, collectionName: String) -> Unit = { _, _ -> },
    onOpenWatchlist: () -> Unit = {},
    onClearActionFeedback: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onMenuClick: (() -> Unit)? = null,
    bottomPaddingDp: Int = 96,
    remoteConfig: com.laterbox.app.remoteconfig.RemoteConfigSnapshot = com.laterbox.app.remoteconfig.RemoteConfigSnapshot.default(),
    recommendationsEnabled: Boolean = true,
    dismissedAnnouncementIds: Set<String> = emptySet(),
    onRemoteAction: (String) -> Unit = {},
    onDismissRemoteAnnouncement: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val focusManager = LocalFocusManager.current
    val moviesContext = androidx.compose.ui.platform.LocalContext.current

    val homeListState = rememberLazyListState()
    val filteredListState = rememberLazyListState()
    val activeListState = if (uiState.isFilteredMode) filteredListState else homeListState

    var isFilterRowVisible by remember { mutableStateOf(true) }
    var showExpandedFilters by remember { mutableStateOf(false) }
    var isGenresExpanded by rememberSaveable { mutableStateOf(false) }
    var isRegionsExpanded by rememberSaveable { mutableStateOf(false) }
    var collectionPickerTarget by remember { mutableStateOf<MovieTitleSummary?>(null) }
    var pendingRemoveViewed by remember { mutableStateOf<MovieTitleSummary?>(null) }
    var prevIndex by remember(uiState.isFilteredMode) { mutableIntStateOf(0) }
    var prevOffset by remember(uiState.isFilteredMode) { mutableIntStateOf(0) }

    collectionPickerTarget?.let { target ->
        MovieCollectionPickerDialog(
            title = target.title,
            collections = uiState.collections,
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

    pendingRemoveViewed?.let { target ->
        androidx.compose.material3.AlertDialog(
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
                androidx.compose.material3.TextButton(
                    onClick = {
                        onRemoveRecentlyViewed(target)
                        pendingRemoveViewed = null
                    },
                    modifier = Modifier.testTag("movies_home_confirm_remove_viewed")
                ) {
                    Text("Remove", color = colors.statusDanger)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { pendingRemoveViewed = null },
                    modifier = Modifier.testTag("movies_home_cancel_remove_viewed")
                ) {
                    Text("Cancel", color = colors.textSecondary)
                }
            }
        )
    }

    LaunchedEffect(activeListState) {
        snapshotFlow {
            activeListState.firstVisibleItemIndex to activeListState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            when {
                index == 0 && offset < 24 -> {
                    isFilterRowVisible = true
                }
                index > prevIndex || (index == prevIndex && offset > prevOffset + 18) -> {
                    isFilterRowVisible = false
                }
                index < prevIndex || (index == prevIndex && offset < prevOffset - 18) -> {
                    isFilterRowVisible = true
                }
            }
            if (index != prevIndex || abs(offset - prevOffset) > 18) {
                prevIndex = index
                prevOffset = offset
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backgroundPrimary)
            .testTag("movies_home_screen")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
        LaterboxTabHeader(
            title = "Movies & TV",
            subtitle = if (uiState.useSampleFixturesOnly) {
                "Offline sample cinema mode"
            } else {
                "Discover films & series"
            },
            onMenuClick = onMenuClick,
            menuButtonTestTag = "movies_menu_button"
        ) {
            LaterboxIconButton(
                iconRes = LaterboxIcons.Search,
                contentDescription = "Search movies and TV",
                onClick = {
                    val genreArg = uiState.selectedGenre.takeUnless { it == "All" }
                    onOpenCatalogue(uiState.selectedKind, genreArg)
                },
                modifier = Modifier.testTag("movies_search_button")
            )
            LaterboxIconButton(
                iconRes = LaterboxIcons.Settings,
                contentDescription = "Settings",
                onClick = onSettingsClick,
                modifier = Modifier.testTag("movies_settings_button")
            )
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

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxSize()) {
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
                                        text = "Could not load catalogue",
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
                                        if (BuildConfig.DEBUG) {
                                            OutlinedButton(
                                                onClick = { onToggleSampleFixtures(true) },
                                                border = BorderStroke(1.dp, colors.borderSubtle)
                                            ) {
                                                Text("Fixtures", color = colors.textPrimary)
                                            }
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
                        val offlineLookup = remember(uiState.offlineMatches) {
                            OfflineMediaLookup.from(uiState.offlineMatches)
                        }
                        val orderedGenres = remember(feed.genres) {
                            val raw = feed.genres
                                .map { it.trim() }
                                .filter {
                                    it.isNotBlank() &&
                                        !it.equals("All", ignoreCase = true) &&
                                        !it.equals("Western", ignoreCase = true)
                                }
                                .distinctBy { it.lowercase() }
                            val priority = PRIORITY_GENRE_ORDER.mapNotNull { target ->
                                raw.firstOrNull { it.equals(target, ignoreCase = true) }
                            }
                            val remaining = raw.filter { candidate ->
                                PRIORITY_GENRE_ORDER.none { it.equals(candidate, ignoreCase = true) }
                            }
                            priority + remaining
                        }
                        val regionFilterItems = remember(uiState.availableCountries) {
                            val shortcuts = MoviesDiscoveryShortcut.entries.map { shortcut ->
                                RegionFilterItem.Shortcut(shortcut)
                            }
                            val countries = uiState.availableCountries
                                .map { it.trim() }
                                .filter { it.isNotBlank() && !it.equals("All", ignoreCase = true) }
                                .distinctBy { it.lowercase() }
                                .filter { country ->
                                    MoviesDiscoveryShortcut.entries.none { it.label.equals(country, ignoreCase = true) }
                                }
                                .map { country -> RegionFilterItem.Country(country) }
                            shortcuts + countries
                        }

                        // Single consolidated horizontal filter row that collapses on scroll down and reveals on scroll up
                        val expandedFilterCount = remember(
                            uiState.selectedLanguage,
                            uiState.selectedYear,
                            uiState.selectedRuntimeBucket
                        ) {
                            listOf(
                                uiState.selectedLanguage != "All",
                                uiState.selectedYear != "All",
                                uiState.selectedRuntimeBucket != RuntimeBucket.ALL
                            ).count { it }
                        }
                        AnimatedVisibility(
                            visible = isFilterRowVisible,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column {
                                LazyRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 2.dp, bottom = 8.dp)
                                        .testTag("movies_home_filters_row"),
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (uiState.isFilteredMode) {
                                        item(key = "filter_reset") {
                                            TextButton(
                                                onClick = {
                                                    runCatching { focusManager.clearFocus(force = true) }
                                                    onResetFilters()
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.testTag("movies_home_reset_filters")
                                            ) {
                                                Text(
                                                    text = "Reset",
                                                    style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                                    color = colors.accentPrimary
                                                )
                                            }
                                        }
                                    }

                                    item(key = "filters_toggle") {
                                        FilterChipPill(
                                            label = if (expandedFilterCount > 0) "Filters ($expandedFilterCount)" else "Filters",
                                            selected = showExpandedFilters || expandedFilterCount > 0,
                                            onClick = { showExpandedFilters = !showExpandedFilters },
                                            modifier = Modifier.testTag("movies_filters_toggle")
                                        )
                                    }

                                    // Group 1: Toggleable Content Type (All Movies / TV Series / Short Dramas)
                                    item(key = "kind_movie") {
                                        val isSelected = uiState.selectedKind == MediaKind.MOVIE
                                        FilterChipPill(
                                            label = "All Movies",
                                            selected = isSelected,
                                            onClick = {
                                                onSelectKind(if (isSelected) null else MediaKind.MOVIE)
                                            },
                                            modifier = Modifier.testTag("movies_home_kind_movie")
                                        )
                                    }
                                    item(key = "kind_tv") {
                                        val isSelected = uiState.selectedKind == MediaKind.TV_SERIES
                                        FilterChipPill(
                                            label = "TV Series",
                                            selected = isSelected,
                                            onClick = {
                                                onSelectKind(if (isSelected) null else MediaKind.TV_SERIES)
                                            },
                                            modifier = Modifier.testTag("movies_home_kind_tv")
                                        )
                                    }
                                    item(key = "kind_short_drama") {
                                        val isSelected = uiState.selectedKind == MediaKind.SHORT_DRAMA
                                        FilterChipPill(
                                            label = "Short Dramas",
                                            selected = isSelected,
                                            onClick = {
                                                onSelectKind(if (isSelected) null else MediaKind.SHORT_DRAMA)
                                            },
                                            modifier = Modifier.testTag("movies_home_kind_short_drama")
                                        )
                                    }

                                    item(key = "kind_sort_divider") {
                                        FilterRowVerticalDivider()
                                    }

                                    // Group 2: Popularity / Recency Sort (Popular, Top Rated, Newest)
                                    items(CatalogueSort.entries, key = { "sort_${it.wireValue}" }) { sort ->
                                        FilterChipPill(
                                            label = sort.label,
                                            selected = uiState.selectedSort == sort,
                                            onClick = { onSelectSort(sort) },
                                            modifier = Modifier.testTag("movies_home_sort_${sort.wireValue}")
                                        )
                                    }

                                    // Group 3: Actual Genres (max 3 visible initially + minimal arrow expand/collapse)
                                    if (orderedGenres.isNotEmpty()) {
                                        item(key = "sort_genre_divider") {
                                            FilterRowVerticalDivider()
                                        }
                                        val visibleGenres = if (isGenresExpanded) orderedGenres else orderedGenres.take(3)
                                        items(visibleGenres, key = { "genre_$it" }) { genre ->
                                            FilterChipPill(
                                                label = genre,
                                                selected = uiState.selectedGenre == genre,
                                                onClick = { onSelectGenre(genre) },
                                                modifier = Modifier.testTag("movies_home_genre_${genre.lowercase().replace(' ', '_')}")
                                            )
                                        }
                                        if (orderedGenres.size > 3) {
                                            item(key = "genres_expand_toggle") {
                                                FilterExpandArrowChip(
                                                    expanded = isGenresExpanded,
                                                    onClick = { isGenresExpanded = !isGenresExpanded },
                                                    contentDescription = if (isGenresExpanded) "Collapse genres" else "Expand genres",
                                                    modifier = Modifier.testTag("movies_home_genres_expand_toggle")
                                                )
                                            }
                                        }
                                    }

                                    // Group 4: Regional / Industry (Hollywood, Bollywood, Western, K-Drama + countries; max 3 visible + expand arrow)
                                    if (regionFilterItems.isNotEmpty()) {
                                        item(key = "genre_region_divider") {
                                            FilterRowVerticalDivider()
                                        }
                                        val visibleRegions = if (isRegionsExpanded) regionFilterItems else regionFilterItems.take(3)
                                        items(visibleRegions, key = { it.key }) { regionItem ->
                                            when (regionItem) {
                                                is RegionFilterItem.Shortcut -> {
                                                    FilterChipPill(
                                                        label = regionItem.shortcut.label,
                                                        selected = uiState.activeShortcut == regionItem.shortcut,
                                                        onClick = { onSelectShortcut(regionItem.shortcut) },
                                                        modifier = Modifier.testTag("movies_home_shortcut_${regionItem.shortcut.id}")
                                                    )
                                                }
                                                is RegionFilterItem.Country -> {
                                                    FilterChipPill(
                                                        label = regionItem.country,
                                                        selected = uiState.selectedCountry == regionItem.country,
                                                        onClick = { onSelectCountry(regionItem.country) },
                                                        modifier = Modifier.testTag("movies_home_country_${regionItem.country.lowercase().replace(' ', '_')}")
                                                    )
                                                }
                                            }
                                        }
                                        if (regionFilterItems.size > 3) {
                                            item(key = "regions_expand_toggle") {
                                                FilterExpandArrowChip(
                                                    expanded = isRegionsExpanded,
                                                    onClick = { isRegionsExpanded = !isRegionsExpanded },
                                                    contentDescription = if (isRegionsExpanded) "Collapse regions" else "Expand regions",
                                                    modifier = Modifier.testTag("movies_home_regions_expand_toggle")
                                                )
                                            }
                                        }
                                    }
                                }

                                AnimatedVisibility(visible = showExpandedFilters) {
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = colors.surfacePrimary,
                                        border = BorderStroke(0.75.dp, colors.borderSubtle),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 4.dp)
                                            .testTag("movies_expanded_filters_panel")
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(vertical = 10.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            // Year / Era row
                                            LazyRow(
                                                contentPadding = PaddingValues(horizontal = 12.dp),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                item(key = "year_label") {
                                                    Text(
                                                        text = "Year",
                                                        style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                                        color = colors.textSecondary,
                                                        modifier = Modifier.padding(end = 4.dp)
                                                    )
                                                }
                                                items(YEAR_FILTER_OPTIONS, key = { "year_$it" }) { yr ->
                                                    FilterChipPill(
                                                        label = yr,
                                                        selected = uiState.selectedYear == yr,
                                                        onClick = { onSelectYear(yr) },
                                                        modifier = Modifier.testTag("movies_home_year_${yr.lowercase()}")
                                                    )
                                                }
                                            }

                                            // Runtime row
                                            LazyRow(
                                                contentPadding = PaddingValues(horizontal = 12.dp),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                item(key = "runtime_label") {
                                                    Text(
                                                        text = "Length",
                                                        style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                                        color = colors.textSecondary,
                                                        modifier = Modifier.padding(end = 4.dp)
                                                    )
                                                }
                                                items(RuntimeBucket.entries, key = { "rt_${it.name}" }) { bucket ->
                                                    FilterChipPill(
                                                        label = bucket.label,
                                                        selected = uiState.selectedRuntimeBucket == bucket,
                                                        onClick = { onSelectRuntimeBucket(bucket) },
                                                        modifier = Modifier.testTag("movies_home_runtime_${bucket.name.lowercase()}")
                                                    )
                                                }
                                            }

                                            // Language row (only when languages exist in catalogue)
                                            if (uiState.availableLanguages.size > 1) {
                                                LazyRow(
                                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    item(key = "lang_label") {
                                                        Text(
                                                            text = "Language",
                                                            style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                                            color = colors.textSecondary,
                                                            modifier = Modifier.padding(end = 4.dp)
                                                        )
                                                    }
                                                    items(uiState.availableLanguages, key = { "lang_$it" }) { lang ->
                                                        FilterChipPill(
                                                            label = lang,
                                                            selected = uiState.selectedLanguage == lang,
                                                            onClick = { onSelectLanguage(lang) },
                                                            modifier = Modifier.testTag("movies_home_lang_${lang.lowercase().replace(' ', '_')}")
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (uiState.isFilteredMode) {
                            // Inline Filtered Results
                            LazyColumn(
                                state = filteredListState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("movies_home_filtered_list"),
                                contentPadding = PaddingValues(top = 6.dp, bottom = bottomPaddingDp.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                item(key = "filtered_header") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val heading = if (uiState.activeSearchQuery.isNotBlank()) {
                                            "Results for \"${uiState.activeSearchQuery}\""
                                        } else {
                                            buildList {
                                                val shortcut = uiState.activeShortcut
                                                if (shortcut != null) {
                                                    add(shortcut.label)
                                                } else {
                                                    add(uiState.selectedKind?.label ?: "All Titles")
                                                    if (uiState.selectedGenre != "All") add(uiState.selectedGenre)
                                                    if (uiState.selectedCountry != "All") add(uiState.selectedCountry)
                                                    if (uiState.selectedYear != "All") add(uiState.selectedYear)
                                                    if (uiState.selectedLanguage != "All") add(uiState.selectedLanguage)
                                                    if (uiState.selectedRuntimeBucket != RuntimeBucket.ALL) {
                                                        add(uiState.selectedRuntimeBucket.label)
                                                    }
                                                }
                                                add(uiState.selectedSort.label)
                                            }.joinToString(" · ")
                                        }
                                        Text(
                                            text = heading,
                                            style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                                            color = colors.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = {
                                                onOpenCatalogue(
                                                    uiState.selectedKind,
                                                    uiState.selectedGenre.takeUnless { it == "All" }
                                                )
                                            }
                                        ) {
                                            Text(
                                                text = "Full View",
                                                style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                                color = colors.accentPrimary
                                            )
                                        }
                                    }
                                }

                                when {
                                    uiState.isLoadingFiltered && uiState.filteredItems.isEmpty() -> {
                                        item(key = "filtered_loading") {
                                            com.laterbox.app.ui.components.MediaCatalogueSkeletonGrid(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .testTag("movies_filtered_loading_skeleton")
                                            )
                                        }
                                    }
                                    uiState.filteredError != null && uiState.filteredItems.isEmpty() -> {
                                        item(key = "filtered_error") {
                                            Surface(
                                                shape = RoundedCornerShape(16.dp),
                                                color = colors.surfacePrimary,
                                                border = BorderStroke(1.dp, colors.borderSubtle),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp)
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(16.dp),
                                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Text(
                                                        text = uiState.filteredError,
                                                        style = typography.bodySecondary,
                                                        color = colors.textSecondary
                                                    )
                                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        Button(
                                                            onClick = onRefresh,
                                                            colors = ButtonDefaults.buttonColors(containerColor = colors.accentPrimary)
                                                        ) {
                                                            Text("Retry")
                                                        }
                                                        OutlinedButton(onClick = onResetFilters) {
                                                            Text("Reset", color = colors.textPrimary)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    uiState.filteredItems.isEmpty() -> {
                                        item(key = "filtered_empty") {
                                            Surface(
                                                shape = RoundedCornerShape(16.dp),
                                                color = colors.surfacePrimary,
                                                border = BorderStroke(0.75.dp, colors.borderSubtle),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp)
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(20.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Text(
                                                        text = "No matching titles found",
                                                        style = typography.titleLarge,
                                                        color = colors.textPrimary
                                                    )
                                                    Text(
                                                        text = "Try a different filter or reset your selection.",
                                                        style = typography.bodySecondary,
                                                        color = colors.textSecondary
                                                    )
                                                    OutlinedButton(onClick = onResetFilters) {
                                                        Text("Reset Filters", color = colors.textPrimary)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    else -> {
                                        val rows = uiState.filteredItems.chunked(3)
                                        items(rows, key = { row -> row.first().compositeId }) { rowItems ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp),
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                rowItems.forEach { item ->
                                                    val isOffline = offlineLookup.hasTitle(item.providerId, item.id)
                                                    MoviePosterCard(
                                                        item = item,
                                                        onClick = { onOpenDetail(item.providerId, item.id) },
                                                        modifier = Modifier.weight(1f),
                                                        isOfflineDownloaded = isOffline,
                                                        isInWatchlist = item.compositeId in watchlistIds,
                                                        onToggleWatchlist = onToggleWatchlist,
                                                        onAddToCollection = { collectionPickerTarget = it },
                                                        onMarkWatched = onMarkWatched,
                                                        onHideTitle = onHideTitle,
                                                        onShareTitle = { com.laterbox.app.media.share.LaterboxShareHelper.shareTitle(moviesContext, it) }
                                                    )
                                                }
                                                repeat(3 - rowItems.size) {
                                                    Spacer(modifier = Modifier.weight(1f))
                                                }
                                            }
                                        }

                                        if (uiState.filteredHasMore) {
                                            item(key = "filtered_load_more") {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 8.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (uiState.isLoadingMoreFiltered) {
                                                        CircularProgressIndicator(
                                                            modifier = Modifier.size(24.dp),
                                                            color = colors.accentPrimary
                                                        )
                                                    } else {
                                                        OutlinedButton(
                                                            onClick = onLoadMoreFiltered,
                                                            border = BorderStroke(1.dp, colors.borderSubtle)
                                                        ) {
                                                            Text("Load More", color = colors.textPrimary)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // Curated Discovery Feed
                            val visibleBanners = remember(feed.featuredBanners, uiState.hiddenMediaKeys) {
                                feed.featuredBanners.filterNot { it.stableMediaKey in uiState.hiddenMediaKeys }
                            }
                            val visibleTopRated = remember(uiState.topRatedRail, uiState.hiddenMediaKeys) {
                                uiState.topRatedRail.filterNot { it.stableMediaKey in uiState.hiddenMediaKeys }
                            }
                            val visibleNewest = remember(uiState.newestRail, uiState.hiddenMediaKeys) {
                                uiState.newestRail.filterNot { it.stableMediaKey in uiState.hiddenMediaKeys }
                            }
                            val visibleSections = remember(feed.sections, uiState.hiddenMediaKeys) {
                                feed.sections.mapNotNull { section ->
                                    val filtered = section.items.filterNot { it.stableMediaKey in uiState.hiddenMediaKeys }
                                    if (filtered.isEmpty()) null else section to filtered
                                }
                            }

                            LazyColumn(
                                state = homeListState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("movies_home_list"),
                                contentPadding = PaddingValues(top = 4.dp, bottom = bottomPaddingDp.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp)
                            ) {
                                // Notice banner for offline cache / sample fixtures
                                if (feed.isOfflineCached || feed.containsSampleFixtures || !feed.activeProviderNotice.isNullOrBlank()) {
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

                                // Server-driven allowlisted UI components & ordered rails
                                val renderSlots = com.laterbox.app.remoteconfig.RemoteConfigLayoutHelper.buildMoviesHomeSlots(
                                    snapshot = remoteConfig,
                                    recommendationsEnabled = recommendationsEnabled
                                )
                                renderSlots.forEach { slot ->
                                    when (slot.kind) {
                                        com.laterbox.app.remoteconfig.MoviesHomeSlotKind.ANNOUNCEMENT_BANNER -> Unit

                                        com.laterbox.app.remoteconfig.MoviesHomeSlotKind.FEATURED_CALLOUT_CARD -> {
                                            val comp = slot.component ?: return@forEach
                                            item(key = slot.key) {
                                                com.laterbox.app.remoteconfig.RemoteFeaturedCalloutCard(
                                                    component = comp,
                                                    onActionClick = onRemoteAction,
                                                    modifier = Modifier.padding(horizontal = 16.dp)
                                                )
                                            }
                                        }

                                        com.laterbox.app.remoteconfig.MoviesHomeSlotKind.HERO_CAROUSEL -> {
                                            val heroSlides = slot.component?.slides.orEmpty()
                                            val cappedBanners = visibleBanners.take(slot.maxItems)
                                            if (heroSlides.any { it.enabled } || cappedBanners.isNotEmpty()) {
                                                item(key = "hero_carousel") {
                                                    MoviesHeroCarousel(
                                                        banners = cappedBanners,
                                                        watchlistIds = watchlistIds,
                                                        onBannerClick = { item -> onOpenDetail(item.providerId, item.id) },
                                                        onPlayClick = { item ->
                                                            val defaultSeason = if (item.kind.isEpisodic) 1 else null
                                                            val defaultEpisode = if (item.kind.isEpisodic) 1 else null
                                                            onOpenPlayer(item.providerId, item.id, defaultSeason, defaultEpisode)
                                                        },
                                                        onToggleWatchlist = onToggleWatchlist,
                                                        autoAdvanceIntervalMs = (slot.component?.autoAdvanceMs?.toLong() ?: 6_000L),
                                                        customSlides = heroSlides,
                                                        onCustomSlideAction = onRemoteAction,
                                                    )
                                                }
                                            }
                                        }

                                        com.laterbox.app.remoteconfig.MoviesHomeSlotKind.CONTENT_RAIL -> when (slot.railId) {
                                            "continue_watching" -> if (uiState.continueWatching.isNotEmpty()) {
                                                item(key = "continue_watching_rail") {
                                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                        SectionHeaderRow(
                                                            title = slot.customTitle.ifBlank { "Continue Watching" },
                                                            subtitle = "Pick up where you left off",
                                                            onSeeAll = null
                                                        )
                                                        LazyRow(
                                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                        ) {
                                                            items(
                                                                uiState.continueWatching.take(slot.maxItems),
                                                                key = { it.compositeId + (it.episodeLabel ?: "") }
                                                            ) { entry ->
                                                                val isOffline = if (entry.seasonNumber != null && entry.episodeNumber != null) {
                                                                    offlineLookup.hasEpisode(
                                                                        entry.providerId,
                                                                        entry.titleId,
                                                                        entry.seasonNumber,
                                                                        entry.episodeNumber
                                                                    )
                                                                } else {
                                                                    offlineLookup.hasTitle(entry.providerId, entry.titleId)
                                                                }
                                                                ContinueWatchingCard(
                                                                    item = entry,
                                                                    onClick = {
                                                                        onOpenPlayer(
                                                                            entry.providerId,
                                                                            entry.titleId,
                                                                            entry.seasonNumber,
                                                                            entry.episodeNumber
                                                                        )
                                                                    },
                                                                    isOfflineDownloaded = isOffline,
                                                                    onOpenDetail = { onOpenDetail(entry.providerId, entry.titleId) },
                                                                    onRestartFromBeginning = { onRestartContinueWatching(entry) },
                                                                    onMarkWatched = { onMarkContinueWatchingWatched(entry) },
                                                                    onRemoveFromContinueWatching = { onRemoveContinueWatching(entry) }
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            "watchlist" -> if (uiState.watchlist.isNotEmpty()) {
                                                item(key = "watchlist_rail") {
                                                    Column(
                                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                                        modifier = Modifier.testTag("movies_home_watchlist_rail")
                                                    ) {
                                                        SectionHeaderRow(
                                                            title = slot.customTitle.ifBlank { "Your Watchlist" },
                                                            subtitle = "${uiState.watchlist.size} saved titles",
                                                            onSeeAll = onOpenWatchlist
                                                        )
                                                        LazyRow(
                                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                        ) {
                                                            items(uiState.watchlist.take(slot.maxItems), key = { "wl_${it.compositeId}" }) { item ->
                                                                val isOffline = offlineLookup.hasTitle(item.providerId, item.id)
                                                                MoviePosterCard(
                                                                    item = item,
                                                                    onClick = { onOpenDetail(item.providerId, item.id) },
                                                                    isOfflineDownloaded = isOffline,
                                                                    isInWatchlist = true,
                                                                    onToggleWatchlist = onToggleWatchlist,
                                                                    onAddToCollection = { collectionPickerTarget = it },
                                                                    onMarkWatched = onMarkWatched,
                                                                    onHideTitle = onHideTitle,
                                                                    onShareTitle = { com.laterbox.app.media.share.LaterboxShareHelper.shareTitle(moviesContext, it) }
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            "recently_viewed" -> if (uiState.recentlyViewed.isNotEmpty()) {
                                                item(key = "recently_viewed_rail") {
                                                    Column(
                                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                                        modifier = Modifier.testTag("movies_home_recently_viewed_rail")
                                                    ) {
                                                        SectionHeaderRow(
                                                            title = slot.customTitle.ifBlank { "Recently Viewed" },
                                                            subtitle = "Titles you checked out recently",
                                                            onSeeAll = null
                                                        )
                                                        LazyRow(
                                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                        ) {
                                                            items(
                                                                uiState.recentlyViewed.take(slot.maxItems),
                                                                key = { "rv_${it.compositeId}" }
                                                            ) { item ->
                                                                val isOffline = offlineLookup.hasTitle(item.providerId, item.id)
                                                                MoviePosterCard(
                                                                    item = item,
                                                                    onClick = { onOpenDetail(item.providerId, item.id) },
                                                                    isOfflineDownloaded = isOffline,
                                                                    isInWatchlist = item.compositeId in watchlistIds,
                                                                    onToggleWatchlist = onToggleWatchlist,
                                                                    onAddToCollection = { collectionPickerTarget = it },
                                                                    onMarkWatched = onMarkWatched,
                                                                    onHideTitle = onHideTitle,
                                                                    onShareTitle = { com.laterbox.app.media.share.LaterboxShareHelper.shareTitle(moviesContext, it) },
                                                                    onRemoveFromViewed = { pendingRemoveViewed = it },
                                                                    menuButtonTestTag = "poster_menu_recent_${item.id}"
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            "because_you_watched" -> if (recommendationsEnabled) {
                                                uiState.becauseYouWatchedGroups.forEachIndexed { idx, group ->
                                                    if (group.items.isNotEmpty()) {
                                                        item(key = "byw_${idx}_${group.anchorTitle}") {
                                                            Column(
                                                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                                                modifier = Modifier.testTag("movies_home_byw_rail_$idx")
                                                            ) {
                                                                SectionHeaderRow(
                                                                    title = "Because You Watched ${group.anchorTitle}",
                                                                    subtitle = "Recommended from your viewing history",
                                                                    onSeeAll = null
                                                                )
                                                                LazyRow(
                                                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                                ) {
                                                                    items(group.items.take(slot.maxItems), key = { "byw_${idx}_${it.compositeId}" }) { item ->
                                                                        val isOffline = offlineLookup.hasTitle(item.providerId, item.id)
                                                                        MoviePosterCard(
                                                                            item = item,
                                                                            onClick = { onOpenDetail(item.providerId, item.id) },
                                                                            isOfflineDownloaded = isOffline,
                                                                            isInWatchlist = item.compositeId in watchlistIds,
                                                                            onToggleWatchlist = onToggleWatchlist,
                                                                            onAddToCollection = { collectionPickerTarget = it },
                                                                            onMarkWatched = onMarkWatched,
                                                                            onHideTitle = onHideTitle,
                                                                            onShareTitle = { com.laterbox.app.media.share.LaterboxShareHelper.shareTitle(moviesContext, it) }
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                uiState.surprisePick?.let { surprise ->
                                                    item(key = "surprise_me_card") {
                                                        Surface(
                                                            shape = RoundedCornerShape(16.dp),
                                                            color = colors.surfacePrimary,
                                                            border = BorderStroke(0.75.dp, colors.accentPrimary.copy(alpha = 0.28f)),
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(horizontal = 16.dp)
                                                                .testTag("movies_home_surprise_card")
                                                        ) {
                                                            Row(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .clickable { onOpenDetail(surprise.pick.providerId, surprise.pick.id) }
                                                                    .padding(12.dp),
                                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Box(
                                                                    modifier = Modifier
                                                                        .width(72.dp)
                                                                        .height(104.dp)
                                                                        .clip(RoundedCornerShape(10.dp))
                                                                        .background(colors.surfaceSecondary),
                                                                    contentAlignment = Alignment.Center
                                                                ) {
                                                                    if (!surprise.pick.posterUrl.isNullOrBlank()) {
                                                                        AsyncImage(
                                                                            model = surprise.pick.posterUrl,
                                                                            contentDescription = surprise.pick.title,
                                                                            contentScale = ContentScale.Crop,
                                                                            modifier = Modifier.fillMaxSize()
                                                                        )
                                                                    } else {
                                                                        Icon(
                                                                            painter = painterResource(LaterboxIcons.Sparkles),
                                                                            contentDescription = null,
                                                                            tint = colors.accentPrimary
                                                                        )
                                                                    }
                                                                }
                                                                Column(
                                                                    modifier = Modifier.weight(1f),
                                                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                                                ) {
                                                                    Text(
                                                                        text = "SURPRISE ME",
                                                                        style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                                                        color = colors.accentPrimary
                                                                    )
                                                                    Text(
                                                                        text = surprise.pick.title,
                                                                        style = typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                                                                        color = colors.textPrimary,
                                                                        maxLines = 2,
                                                                        overflow = TextOverflow.Ellipsis
                                                                    )
                                                                    Text(
                                                                        text = surprise.rationale,
                                                                        style = typography.metaText,
                                                                        color = colors.textSecondary,
                                                                        maxLines = 2,
                                                                        overflow = TextOverflow.Ellipsis
                                                                    )
                                                                    @OptIn(ExperimentalLayoutApi::class)
                                                                    FlowRow(
                                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                                                        modifier = Modifier.padding(top = 4.dp)
                                                                    ) {
                                                                        LaterboxPrimaryButton(
                                                                            text = "Open",
                                                                            onClick = { onOpenDetail(surprise.pick.providerId, surprise.pick.id) },
                                                                            leadingIcon = LaterboxIcons.Play,
                                                                            modifier = Modifier.testTag("movies_surprise_open")
                                                                        )
                                                                        LaterboxSecondaryButton(
                                                                            text = "Pick another",
                                                                            onClick = { onRerollSurprise(null) },
                                                                            leadingIcon = LaterboxIcons.Refresh,
                                                                            modifier = Modifier.testTag("movies_surprise_reroll")
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            "quick_picks" -> if (recommendationsEnabled && uiState.quickRuntimePicks.isNotEmpty()) {
                                                item(key = "quick_picks_rail") {
                                                    Column(
                                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                                        modifier = Modifier.testTag("movies_home_quick_picks_rail")
                                                    ) {
                                                        SectionHeaderRow(
                                                            title = slot.customTitle.ifBlank { "Quick Picks (<90m)" },
                                                            subtitle = "Easy to finish tonight",
                                                            onSeeAll = null
                                                        )
                                                        LazyRow(
                                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                        ) {
                                                            items(uiState.quickRuntimePicks.take(slot.maxItems), key = { "qp_${it.compositeId}" }) { item ->
                                                                val isOffline = offlineLookup.hasTitle(item.providerId, item.id)
                                                                MoviePosterCard(
                                                                    item = item,
                                                                    onClick = { onOpenDetail(item.providerId, item.id) },
                                                                    isOfflineDownloaded = isOffline,
                                                                    isInWatchlist = item.compositeId in watchlistIds,
                                                                    onToggleWatchlist = onToggleWatchlist,
                                                                    onAddToCollection = { collectionPickerTarget = it },
                                                                    onMarkWatched = onMarkWatched,
                                                                    onHideTitle = onHideTitle,
                                                                    onShareTitle = { com.laterbox.app.media.share.LaterboxShareHelper.shareTitle(moviesContext, it) }
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            "recently_added" -> if (uiState.recentlyAddedRail.isNotEmpty()) {
                                                item(key = "recently_added_rail") {
                                                    Column(
                                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                                        modifier = Modifier.testTag("movies_home_recently_added_rail")
                                                    ) {
                                                        SectionHeaderRow(
                                                            title = slot.customTitle.ifBlank { "Recently Added" },
                                                            subtitle = "Latest verified additions & releases",
                                                            onSeeAll = null
                                                        )
                                                        LazyRow(
                                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                        ) {
                                                            items(uiState.recentlyAddedRail.take(slot.maxItems), key = { "ra_${it.compositeId}" }) { item ->
                                                                val isOffline = offlineLookup.hasTitle(item.providerId, item.id)
                                                                MoviePosterCard(
                                                                    item = item,
                                                                    onClick = { onOpenDetail(item.providerId, item.id) },
                                                                    isOfflineDownloaded = isOffline,
                                                                    isInWatchlist = item.compositeId in watchlistIds,
                                                                    onToggleWatchlist = onToggleWatchlist,
                                                                    onAddToCollection = { collectionPickerTarget = it },
                                                                    onMarkWatched = onMarkWatched,
                                                                    onHideTitle = onHideTitle,
                                                                    onShareTitle = { com.laterbox.app.media.share.LaterboxShareHelper.shareTitle(moviesContext, it) }
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            "top_rated" -> if (visibleTopRated.isNotEmpty()) {
                                                item(key = "top_rated_rail") {
                                                    Column(
                                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                                        modifier = Modifier.testTag("movies_home_top_rated_rail")
                                                    ) {
                                                        SectionHeaderRow(
                                                            title = slot.customTitle.ifBlank { "Top Rated" },
                                                            subtitle = "Highest-rated films & series",
                                                            onSeeAll = { onOpenCatalogueWithSort(null, null, CatalogueSort.TOP_RATED, null) }
                                                        )
                                                        LazyRow(
                                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                        ) {
                                                            items(visibleTopRated.take(slot.maxItems), key = { "tr_${it.compositeId}" }) { item ->
                                                                val isOffline = offlineLookup.hasTitle(item.providerId, item.id)
                                                                MoviePosterCard(
                                                                    item = item,
                                                                    onClick = { onOpenDetail(item.providerId, item.id) },
                                                                    isOfflineDownloaded = isOffline,
                                                                    isInWatchlist = item.compositeId in watchlistIds,
                                                                    onToggleWatchlist = onToggleWatchlist,
                                                                    onAddToCollection = { collectionPickerTarget = it },
                                                                    onMarkWatched = onMarkWatched,
                                                                    onHideTitle = onHideTitle,
                                                                    onShareTitle = { com.laterbox.app.media.share.LaterboxShareHelper.shareTitle(moviesContext, it) }
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            "newest" -> if (visibleNewest.isNotEmpty()) {
                                                item(key = "newest_rail") {
                                                    Column(
                                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                                        modifier = Modifier.testTag("movies_home_newest_rail")
                                                    ) {
                                                        SectionHeaderRow(
                                                            title = slot.customTitle.ifBlank { "Newest" },
                                                            subtitle = "Latest releases added",
                                                            onSeeAll = { onOpenCatalogueWithSort(null, null, CatalogueSort.NEWEST, null) }
                                                        )
                                                        LazyRow(
                                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                        ) {
                                                            items(visibleNewest.take(slot.maxItems), key = { "nw_${it.compositeId}" }) { item ->
                                                                val isOffline = offlineLookup.hasTitle(item.providerId, item.id)
                                                                MoviePosterCard(
                                                                    item = item,
                                                                    onClick = { onOpenDetail(item.providerId, item.id) },
                                                                    isOfflineDownloaded = isOffline,
                                                                    isInWatchlist = item.compositeId in watchlistIds,
                                                                    onToggleWatchlist = onToggleWatchlist,
                                                                    onAddToCollection = { collectionPickerTarget = it },
                                                                    onMarkWatched = onMarkWatched,
                                                                    onHideTitle = onHideTitle,
                                                                    onShareTitle = { com.laterbox.app.media.share.LaterboxShareHelper.shareTitle(moviesContext, it) }
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            "discovery_sections" -> {
                                                items(visibleSections, key = { (section, _) -> section.id }) { (section, sectionItems) ->
                                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                        val inferKind = sectionItems.firstOrNull()?.kind
                                                        SectionHeaderRow(
                                                            title = section.title,
                                                            subtitle = section.subtitle,
                                                            onSeeAll = { onOpenCatalogue(inferKind, null) }
                                                        )
                                                        LazyRow(
                                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                        ) {
                                                            items(sectionItems.take(slot.maxItems), key = { "${section.id}_${it.compositeId}" }) { item ->
                                                                val isOffline = offlineLookup.hasTitle(item.providerId, item.id)
                                                                MoviePosterCard(
                                                                    item = item,
                                                                    onClick = { onOpenDetail(item.providerId, item.id) },
                                                                    isOfflineDownloaded = isOffline,
                                                                    isInWatchlist = item.compositeId in watchlistIds,
                                                                    onToggleWatchlist = onToggleWatchlist,
                                                                    onAddToCollection = { collectionPickerTarget = it },
                                                                    onMarkWatched = onMarkWatched,
                                                                    onHideTitle = onHideTitle,
                                                                    onShareTitle = { com.laterbox.app.media.share.LaterboxShareHelper.shareTitle(moviesContext, it) }
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
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun FilterRowVerticalDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(18.dp)
            .background(LaterboxTheme.colors.borderSubtle)
    )
}

@Composable
private fun FilterChipPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) colors.accentPrimary else colors.surfacePrimary,
        border = BorderStroke(
            0.75.dp,
            if (selected) colors.accentPrimary else colors.borderSubtle
        )
    ) {
        Text(
            text = label,
            style = LaterboxTheme.typography.uiLabel.copy(
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            ),
            color = if (selected) colors.onAccentPrimary else colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
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

private sealed interface RegionFilterItem {
    val key: String

    data class Shortcut(val shortcut: MoviesDiscoveryShortcut) : RegionFilterItem {
        override val key: String = "region_shortcut_${shortcut.id}"
    }

    data class Country(val country: String) : RegionFilterItem {
        override val key: String = "region_country_${country.lowercase()}"
    }
}

@Composable
private fun FilterExpandArrowChip(
    expanded: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    Surface(
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = colors.surfacePrimary,
        border = BorderStroke(0.75.dp, colors.borderSubtle)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(LaterboxIcons.ChevronRight),
                contentDescription = contentDescription,
                tint = colors.textSecondary,
                modifier = Modifier
                    .size(14.dp)
                    .rotate(if (expanded) 180f else 0f)
            )
        }
    }
}

