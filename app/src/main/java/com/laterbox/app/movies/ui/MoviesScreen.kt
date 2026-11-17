package com.laterbox.app.movies.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laterbox.app.BuildConfig
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesDiscoveryShortcut
import com.laterbox.app.movies.domain.model.SearchSuggestion
import com.laterbox.app.ui.components.LaterboxIcons
import com.laterbox.app.ui.theme.LaterboxTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoviesScreen(
    uiState: MoviesHomeUiState,
    onRefresh: () -> Unit,
    onOpenCatalogue: (kind: MediaKind?, genre: String?) -> Unit,
    onOpenDetail: (providerId: String, titleId: String) -> Unit,
    onOpenPlayer: (providerId: String, titleId: String, seasonNumber: Int?, episodeNumber: Int?) -> Unit,
    onToggleWatchlist: (MovieTitleSummary) -> Unit,
    onToggleSampleFixtures: (Boolean) -> Unit,
    onSaveMovieBoxConfig: (baseUrl: String, apiKey: String) -> Unit = { _, _ -> },
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
    onMenuClick: (() -> Unit)? = null,
    bottomPaddingDp: Int = 96,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var showSourcesDialog by remember { mutableStateOf(false) }

    val dismissSearch: () -> Unit = {
        keyboardController?.hide()
        focusManager.clearFocus()
        onSearchFocusChange(false)
    }

    BackHandler(enabled = uiState.showSearchDropdown) {
        dismissSearch()
    }

    if (showSourcesDialog) {
        MoviesProviderSourceDialog(
            useSampleFixturesOnly = uiState.useSampleFixturesOnly,
            onToggleSampleFixtures = onToggleSampleFixtures,
            onDismiss = { showSourcesDialog = false },
            currentMovieBoxBaseUrl = uiState.movieBoxApiBaseUrl,
            effectiveMovieBoxBaseUrl = uiState.effectiveMovieBoxBaseUrl,
            currentMovieBoxApiKey = uiState.movieBoxApiKey,
            onSaveMovieBoxConfig = onSaveMovieBoxConfig
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
                .clickable(
                    enabled = uiState.showSearchDropdown,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = dismissSearch
                )
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
                        "Discover films & series"
                    },
                    style = typography.metaText,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = {
                    val genreArg = uiState.selectedGenre.takeUnless { it == "All" }
                    onOpenCatalogue(uiState.selectedKind, genreArg)
                },
                modifier = Modifier.testTag("movies_search_button")
            ) {
                Icon(
                    painter = painterResource(LaterboxIcons.Search),
                    contentDescription = "Full catalogue search",
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

        // Inline Search Field
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = {
                Text(
                    text = "Search movies, series, classic films...",
                    style = typography.bodySecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(LaterboxIcons.Search),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = colors.textSecondary
                )
            },
            trailingIcon = {
                if (uiState.searchQuery.isNotEmpty() || uiState.activeSearchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            onClearSearch()
                        },
                        modifier = Modifier.testTag("movies_home_clear_search")
                    ) {
                        Icon(
                            painter = painterResource(LaterboxIcons.Close),
                            contentDescription = "Clear search",
                            modifier = Modifier.size(16.dp),
                            tint = colors.textSecondary
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = colors.surfacePrimary,
                unfocusedContainerColor = colors.surfacePrimary,
                focusedBorderColor = colors.accentPrimary,
                unfocusedBorderColor = colors.borderSubtle,
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    onSubmitSearch(null)
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .onFocusChanged { onSearchFocusChange(it.isFocused) }
                .testTag("movies_home_search_field")
        )

        if (uiState.isRefreshing) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
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
                                    onClick = { showSourcesDialog = true },
                                    border = BorderStroke(1.dp, colors.borderSubtle)
                                ) {
                                    Text("Sources", color = colors.textPrimary)
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
                val genresList = remember(feed.genres) {
                    val cleaned = feed.genres.filterNot { it.equals("Fantasy", ignoreCase = true) }
                    if (cleaned.any { it.equals("All", ignoreCase = true) }) {
                        cleaned
                    } else {
                        listOf("All") + cleaned
                    }
                }

                // Inline Kind + Shortcuts/Countries + Sort/Genre Filter Bar
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChipPill(
                            label = "All",
                            selected = uiState.selectedKind == null,
                            onClick = { onSelectKind(null) },
                            modifier = Modifier.testTag("movies_home_kind_all")
                        )
                        FilterChipPill(
                            label = "Movies",
                            selected = uiState.selectedKind == MediaKind.MOVIE,
                            onClick = { onSelectKind(MediaKind.MOVIE) },
                            modifier = Modifier.testTag("movies_home_kind_movie")
                        )
                        FilterChipPill(
                            label = "TV Series",
                            selected = uiState.selectedKind == MediaKind.TV_SERIES,
                            onClick = { onSelectKind(MediaKind.TV_SERIES) },
                            modifier = Modifier.testTag("movies_home_kind_tv")
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        if (uiState.isFilteredMode) {
                            TextButton(
                                onClick = {
                                    focusManager.clearFocus()
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

                    // Discovery Shortcuts + Countries row
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(MoviesDiscoveryShortcut.entries, key = { "shortcut_${it.id}" }) { shortcut ->
                            FilterChipPill(
                                label = shortcut.label,
                                selected = uiState.activeShortcut == shortcut,
                                onClick = { onSelectShortcut(shortcut) },
                                modifier = Modifier.testTag("movies_home_shortcut_${shortcut.id}")
                            )
                        }
                        item(key = "shortcut_country_divider") {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(18.dp)
                                    .background(colors.borderSubtle)
                            )
                        }
                        items(uiState.availableCountries, key = { "country_$it" }) { country ->
                            FilterChipPill(
                                label = if (country == "All") "All Regions" else country,
                                selected = uiState.selectedCountry == country,
                                onClick = { onSelectCountry(country) },
                                modifier = Modifier.testTag("movies_home_country_${country.lowercase().replace(' ', '_')}")
                            )
                        }
                    }

                    // Sort + Genres row
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(CatalogueSort.entries, key = { "sort_${it.wireValue}" }) { sort ->
                            FilterChipPill(
                                label = sort.label,
                                selected = uiState.selectedSort == sort,
                                onClick = { onSelectSort(sort) },
                                modifier = Modifier.testTag("movies_home_sort_${sort.wireValue}")
                            )
                        }
                        if (genresList.size > 1) {
                            item(key = "sort_genre_divider") {
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(18.dp)
                                        .background(colors.borderSubtle)
                                )
                            }
                            items(genresList, key = { "genre_$it" }) { genre ->
                                FilterChipPill(
                                    label = genre,
                                    selected = uiState.selectedGenre == genre,
                                    onClick = { onSelectGenre(genre) },
                                    modifier = Modifier.testTag("movies_home_genre_${genre.lowercase().replace(' ', '_')}")
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                if (uiState.isFilteredMode) {
                    // Inline Filtered / Search Results
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("movies_home_filtered_list"),
                        contentPadding = PaddingValues(top = 8.dp, bottom = bottomPaddingDp.dp),
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
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(40.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(color = colors.accentPrimary)
                                    }
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
                                                text = "Try a different keyword, genre, or reset filters.",
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
                                items(rows, key = { row -> row.joinToString("_") { it.compositeId } }) { rowItems ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        rowItems.forEach { item ->
                                            MoviePosterCard(
                                                item = item,
                                                onClick = { onOpenDetail(item.providerId, item.id) },
                                                modifier = Modifier.weight(1f)
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
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("movies_home_list"),
                        contentPadding = PaddingValues(top = 6.dp, bottom = bottomPaddingDp.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        // Notice banner for offline cache / provider fallback / sample fixtures
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

                        // Featured Hero Carousel
                        if (feed.featuredBanners.isNotEmpty()) {
                            item(key = "hero_carousel") {
                                MoviesHeroCarousel(
                                    banners = feed.featuredBanners,
                                    watchlistIds = watchlistIds,
                                    onBannerClick = { item -> onOpenDetail(item.providerId, item.id) },
                                    onPlayClick = { item ->
                                        val defaultSeason = if (item.kind == MediaKind.TV_SERIES) 1 else null
                                        val defaultEpisode = if (item.kind == MediaKind.TV_SERIES) 1 else null
                                        onOpenPlayer(item.providerId, item.id, defaultSeason, defaultEpisode)
                                    },
                                    onToggleWatchlist = onToggleWatchlist
                                )
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

                        // Top Rated Rail
                        if (uiState.topRatedRail.isNotEmpty()) {
                            item(key = "top_rated_rail") {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.testTag("movies_home_top_rated_rail")
                                ) {
                                    SectionHeaderRow(
                                        title = "Top Rated",
                                        subtitle = "Highest-rated films & series",
                                        onSeeAll = { onOpenCatalogueWithSort(null, null, CatalogueSort.TOP_RATED, null) }
                                    )
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        items(uiState.topRatedRail, key = { "tr_${it.compositeId}" }) { item ->
                                            MoviePosterCard(
                                                item = item,
                                                onClick = { onOpenDetail(item.providerId, item.id) }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Newest Rail
                        if (uiState.newestRail.isNotEmpty()) {
                            item(key = "newest_rail") {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.testTag("movies_home_newest_rail")
                                ) {
                                    SectionHeaderRow(
                                        title = "Newest",
                                        subtitle = "Latest releases added",
                                        onSeeAll = { onOpenCatalogueWithSort(null, null, CatalogueSort.NEWEST, null) }
                                    )
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        items(uiState.newestRail, key = { "nw_${it.compositeId}" }) { item ->
                                            MoviePosterCard(
                                                item = item,
                                                onClick = { onOpenDetail(item.providerId, item.id) }
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
                    }
                }
            }
        }
            }

            if (uiState.showSearchDropdown) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = dismissSearch
                        )
                        .testTag("movies_home_search_scrim")
                )
                MoviesSearchSuggestionsDropdown(
                    query = uiState.searchQuery,
                    suggestions = uiState.suggestions,
                    recentSearches = uiState.recentSearches,
                    isLoading = uiState.isLoadingSuggestions,
                    errorMessage = uiState.suggestionsError,
                    containerTag = "movies_home_suggestions",
                    suggestionTagPrefix = "movies_home_suggestion_",
                    onSuggestionClick = { suggestion ->
                        dismissSearch()
                        if (suggestion.hasNavigableId) {
                            onOpenDetail(suggestion.providerId, suggestion.id)
                        } else {
                            onSubmitSearch(suggestion.title)
                        }
                    },
                    onSubmitQuery = { q ->
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        onSubmitSearch(q)
                    },
                    onClearRecent = onClearSearchHistory,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }
    }
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
