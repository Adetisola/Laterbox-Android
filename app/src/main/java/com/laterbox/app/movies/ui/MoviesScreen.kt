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
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
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
    onSaveTmdbApiKey: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit = {},
    onSearchFocusChange: (Boolean) -> Unit = {},
    onSubmitSearch: (String?) -> Unit = {},
    onClearSearch: () -> Unit = {},
    onClearSearchHistory: () -> Unit = {},
    onSelectKind: (MediaKind?) -> Unit = {},
    onSelectGenre: (String) -> Unit = {},
    onSelectSort: (CatalogueSort) -> Unit = {},
    onResetFilters: () -> Unit = {},
    onLoadMoreFiltered: () -> Unit = {},
    onDismissTmdbPrompt: () -> Unit = {},
    onOpenExternalUrl: (String) -> Unit = {},
    onMenuClick: (() -> Unit)? = null,
    bottomPaddingDp: Int = 96,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var showSourcesDialog by remember { mutableStateOf(false) }

    if (showSourcesDialog) {
        MoviesProviderSourceDialog(
            useSampleFixturesOnly = uiState.useSampleFixturesOnly,
            currentTmdbKey = uiState.currentTmdbApiKey,
            onToggleSampleFixtures = onToggleSampleFixtures,
            onSaveTmdbKey = onSaveTmdbApiKey,
            onDismiss = { showSourcesDialog = false },
            onOpenExternalUrl = onOpenExternalUrl
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
                            onClearSearch()
                            focusManager.clearFocus()
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

        // Live Suggestions & Recent Search Dropdown
        if (uiState.showSearchDropdown) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.surfaceElevated,
                border = BorderStroke(1.dp, colors.borderSubtle),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("movies_home_suggestions")
            ) {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    if (uiState.suggestions.isNotEmpty()) {
                        uiState.suggestions.forEachIndexed { idx, suggestion ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                        onSearchFocusChange(false)
                                        onOpenDetail(suggestion.providerId, suggestion.id)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .testTag("movies_home_suggestion_${suggestion.id}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = suggestion.title,
                                        style = typography.uiLabel,
                                        color = colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val subMeta = listOfNotNull(
                                        suggestion.kind.label,
                                        suggestion.releaseYear?.toString()
                                    ).joinToString(" · ")
                                    Text(
                                        text = subMeta,
                                        style = typography.caption,
                                        color = colors.textSecondary
                                    )
                                }
                                Icon(
                                    painter = painterResource(LaterboxIcons.ArrowUpRight),
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = colors.textTertiary
                                )
                            }
                            if (idx < uiState.suggestions.lastIndex) {
                                HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.5f))
                            }
                        }
                    }

                    if (uiState.recentSearches.isNotEmpty()) {
                        if (uiState.suggestions.isNotEmpty()) {
                            HorizontalDivider(color = colors.borderSubtle)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Recent searches",
                                style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.textSecondary
                            )
                            TextButton(
                                onClick = onClearSearchHistory,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text("Clear", style = typography.caption, color = colors.accentPrimary)
                            }
                        }
                        FlowRow(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            uiState.recentSearches.forEach { query ->
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = colors.surfaceSecondary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable {
                                            keyboardController?.hide()
                                            focusManager.clearFocus()
                                            onSubmitSearch(query)
                                        }
                                ) {
                                    Text(
                                        text = query,
                                        style = typography.caption,
                                        color = colors.textPrimary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

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
                val genresList = remember(feed.genres) {
                    if (feed.genres.any { it.equals("All", ignoreCase = true) }) {
                        feed.genres
                    } else {
                        listOf("All") + feed.genres
                    }
                }

                // Inline Kind + Sort + Genre Filter Bar
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
                                        add(uiState.selectedKind?.label ?: "All Titles")
                                        if (uiState.selectedGenre != "All") add(uiState.selectedGenre)
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

                        // Dismissible Guided Free TMDB API Setup Card
                        if (!uiState.tmdbApiKeyConfigured && !uiState.useSampleFixturesOnly && !uiState.tmdbPromptDismissed) {
                            item(key = "tmdb_setup_card") {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = colors.surfacePrimary,
                                    border = BorderStroke(0.75.dp, colors.accentPrimary.copy(alpha = 0.32f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                        .testTag("movies_tmdb_setup_card")
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Unlock Trending Cinema with Free TMDB",
                                                style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                                                color = colors.textPrimary,
                                                modifier = Modifier.weight(1f)
                                            )
                                            IconButton(
                                                onClick = onDismissTmdbPrompt,
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .testTag("movies_tmdb_dismiss_button")
                                            ) {
                                                Icon(
                                                    painter = painterResource(LaterboxIcons.Close),
                                                    contentDescription = "Dismiss TMDB prompt",
                                                    tint = colors.textSecondary,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = "Connect a free personal TMDB v3 developer key to add mainstream theatrical releases, posters, and recommendations.",
                                            style = typography.metaText,
                                            color = colors.textSecondary
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                onClick = { showSourcesDialog = true },
                                                colors = ButtonDefaults.buttonColors(containerColor = colors.accentPrimary),
                                                shape = RoundedCornerShape(10.dp),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                                modifier = Modifier.height(34.dp)
                                            ) {
                                                Text(
                                                    text = "Add TMDB Key",
                                                    style = typography.caption.copy(fontWeight = FontWeight.SemiBold)
                                                )
                                            }
                                            OutlinedButton(
                                                onClick = { onOpenExternalUrl("https://www.themoviedb.org/settings/api") },
                                                border = BorderStroke(0.75.dp, colors.borderSubtle),
                                                shape = RoundedCornerShape(10.dp),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                                modifier = Modifier.height(34.dp)
                                            ) {
                                                Text(
                                                    text = "Get Free Key",
                                                    style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                                    color = colors.textPrimary
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
