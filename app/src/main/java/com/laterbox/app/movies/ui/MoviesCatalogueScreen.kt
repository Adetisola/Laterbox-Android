package com.laterbox.app.movies.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesDiscoveryShortcut
import com.laterbox.app.movies.domain.model.RuntimeBucket
import com.laterbox.app.movies.domain.model.SearchSuggestion
import com.laterbox.app.ui.components.LaterboxIcons
import com.laterbox.app.ui.theme.LaterboxTheme
import kotlinx.coroutines.delay

private val CATALOGUE_YEAR_OPTIONS = listOf("All", "2025", "2024", "2020s", "2010s", "2000s", "Classic")

@Composable
fun MoviesCatalogueScreen(
    uiState: MoviesCatalogueUiState,
    onBackClick: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearchFocusChange: (Boolean) -> Unit,
    onSubmitSearch: (String?) -> Unit,
    onClearSearch: () -> Unit,
    onSelectKind: (MediaKind?) -> Unit,
    onSelectGenre: (String) -> Unit,
    onSelectSort: (CatalogueSort) -> Unit,
    onLoadNextPage: () -> Unit,
    onRetry: () -> Unit,
    onClearSearchHistory: () -> Unit,
    onOpenDetail: (providerId: String, titleId: String) -> Unit,
    onSelectCountry: (String) -> Unit = {},
    onSelectShortcut: (MoviesDiscoveryShortcut) -> Unit = {},
    onResetFilters: () -> Unit = {},
    onSelectLanguage: (String) -> Unit = {},
    onSelectYear: (String) -> Unit = {},
    onSelectRuntimeBucket: (RuntimeBucket) -> Unit = {},
    onToggleWatchlist: (MovieTitleSummary) -> Unit = {},
    onMarkWatched: (MovieTitleSummary) -> Unit = {},
    onHideTitle: (MovieTitleSummary) -> Unit = {},
    onAddToCollection: (MovieTitleSummary, Long) -> Unit = { _, _ -> },
    onCreateAndAddToCollection: (MovieTitleSummary, String) -> Unit = { _, _ -> },
    onClearActionFeedback: () -> Unit = {},
    autoFocusSearch: Boolean = false,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var showExpandedFilters by remember { mutableStateOf(false) }
    var collectionPickerTarget by remember { mutableStateOf<MovieTitleSummary?>(null) }

    LaunchedEffect(uiState.actionFeedbackMessage) {
        if (uiState.actionFeedbackMessage != null) {
            delay(2_600L)
            onClearActionFeedback()
        }
    }

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

    val dismissSearch: () -> Unit = {
        keyboardController?.hide()
        runCatching { focusManager.clearFocus(force = true) }
        onSearchFocusChange(false)
    }

    val dropdownHasContent = uiState.query.trim().length >= 2 ||
        uiState.isLoadingSuggestions ||
        uiState.suggestionsError != null ||
        uiState.suggestions.isNotEmpty() ||
        uiState.recentSearches.isNotEmpty()
    val isDropdownOpen = uiState.showSuggestionsDropdown && dropdownHasContent

    BackHandler(enabled = isDropdownOpen) {
        dismissSearch()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backgroundPrimary)
            .navigationBarsPadding()
            .imePadding()
            .testTag("movies_catalogue_screen")
    ) {
        // Search + Back Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = isDropdownOpen,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = dismissSearch
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = {
                    if (isDropdownOpen) {
                        dismissSearch()
                    } else {
                        onBackClick()
                    }
                },
                modifier = Modifier.testTag("catalogue_back_button")
            ) {
                Icon(
                    painter = painterResource(LaterboxIcons.ArrowLeft),
                    contentDescription = "Back",
                    tint = colors.textPrimary
                )
            }

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = RoundedCornerShape(14.dp),
                color = colors.surfacePrimary,
                border = BorderStroke(1.dp, colors.borderSubtle)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(LaterboxIcons.Search),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = colors.textTertiary
                    )
                    val searchFocusRequester = remember { FocusRequester() }
                    LaunchedEffect(autoFocusSearch) {
                        if (autoFocusSearch) {
                            delay(60L)
                            runCatching { searchFocusRequester.requestFocus() }
                            onSearchFocusChange(true)
                        }
                    }
                    val searchInteractionSource = remember { MutableInteractionSource() }
                    LaunchedEffect(searchInteractionSource) {
                        searchInteractionSource.interactions.collect { interaction ->
                            if (interaction is PressInteraction.Release) {
                                onSearchFocusChange(true)
                            }
                        }
                    }
                    val searchController = com.laterbox.app.ui.components.rememberSyncedTextFieldController(uiState.query)
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchController.value.text.isEmpty()) {
                            Text(
                                text = "Search movies & series...",
                                style = typography.bodySecondary,
                                color = colors.textTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = searchController.value,
                            onValueChange = { next ->
                                searchController.onLocalValueChange(next, onQueryChange)
                            },
                            interactionSource = searchInteractionSource,
                            singleLine = true,
                            textStyle = typography.bodySecondary.copy(color = colors.textPrimary),
                            cursorBrush = SolidColor(colors.accentPrimary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    keyboardController?.hide()
                                    runCatching { focusManager.clearFocus(force = true) }
                                    onSubmitSearch(null)
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester)
                                .onFocusChanged { onSearchFocusChange(it.isFocused) }
                                .testTag("catalogue_search_input")
                        )
                    }
                    if (searchController.value.text.isNotEmpty() || uiState.query.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                searchController.clearLocal(onClearSearch)
                                keyboardController?.hide()
                                runCatching { focusManager.clearFocus(force = true) }
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("catalogue_search_clear")
                        ) {
                            Icon(
                                painter = painterResource(LaterboxIcons.Close),
                                contentDescription = "Clear search",
                                modifier = Modifier.size(15.dp),
                                tint = colors.textSecondary
                            )
                        }
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxSize()) {

        uiState.actionFeedbackMessage?.let { feedback ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = colors.accentContainer,
                border = BorderStroke(0.75.dp, colors.accentPrimary.copy(alpha = 0.3f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("catalogue_feedback_banner")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = feedback,
                        style = typography.metaText.copy(fontWeight = FontWeight.Medium),
                        color = colors.accentPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onClearActionFeedback) {
                        Text("Dismiss", color = colors.accentPrimary)
                    }
                }
            }
        }

        // Kind Filters + Expanded Filters Toggle + Reset Row
        val hasActiveFilters = uiState.selectedKind != null ||
            uiState.selectedGenre != "All" ||
            uiState.selectedCountry != "All" ||
            uiState.selectedLanguage != "All" ||
            uiState.selectedYear != "All" ||
            uiState.selectedRuntimeBucket != RuntimeBucket.ALL ||
            uiState.activeShortcut != null ||
            uiState.sort != CatalogueSort.POPULAR
        val expandedCount = listOf(
            uiState.selectedLanguage != "All",
            uiState.selectedYear != "All",
            uiState.selectedRuntimeBucket != RuntimeBucket.ALL
        ).count { it }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(
                    null to "All",
                    MediaKind.MOVIE to "Movies",
                    MediaKind.TV_SERIES to "TV Series"
                ).forEach { (kind, label) ->
                    val selected = uiState.selectedKind == kind
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectKind(kind) },
                        label = {
                            Text(
                                text = label,
                                style = typography.uiLabel.copy(fontSize = 12.sp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.accentContainer,
                            selectedLabelColor = colors.accentPrimary,
                            containerColor = colors.surfacePrimary,
                            labelColor = colors.textSecondary
                        ),
                        modifier = Modifier.testTag("filter_kind_${kind?.wireValue ?: "all"}")
                    )
                }
                FilterChip(
                    selected = showExpandedFilters || expandedCount > 0,
                    onClick = { showExpandedFilters = !showExpandedFilters },
                    label = {
                        Text(
                            text = if (expandedCount > 0) "Filters ($expandedCount)" else "Filters",
                            style = typography.uiLabel.copy(fontSize = 12.sp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.accentContainer,
                        selectedLabelColor = colors.accentPrimary,
                        containerColor = colors.surfacePrimary,
                        labelColor = colors.textSecondary
                    ),
                    modifier = Modifier.testTag("catalogue_filters_toggle")
                )
            }
            if (hasActiveFilters) {
                TextButton(
                    onClick = onResetFilters,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.testTag("catalogue_reset_filters")
                ) {
                    Text(
                        text = "Reset",
                        style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.accentPrimary
                    )
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
                    .testTag("catalogue_expanded_filters_panel")
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item(key = "cat_year_lbl") {
                            Text(
                                text = "Year",
                                style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.textSecondary,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                        items(CATALOGUE_YEAR_OPTIONS, key = { "cat_yr_$it" }) { yr ->
                            val selected = uiState.selectedYear == yr
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = if (selected) colors.accentPrimary else colors.surfaceSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .clickable { onSelectYear(yr) }
                                    .testTag("catalogue_year_${yr.lowercase()}")
                            ) {
                                Text(
                                    text = yr,
                                    style = typography.uiLabel.copy(fontSize = 11.sp),
                                    color = if (selected) colors.onAccentPrimary else colors.textPrimary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item(key = "cat_rt_lbl") {
                            Text(
                                text = "Length",
                                style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.textSecondary,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                        items(RuntimeBucket.entries, key = { "cat_rt_${it.name}" }) { bucket ->
                            val selected = uiState.selectedRuntimeBucket == bucket
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = if (selected) colors.accentPrimary else colors.surfaceSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .clickable { onSelectRuntimeBucket(bucket) }
                                    .testTag("catalogue_runtime_${bucket.name.lowercase()}")
                            ) {
                                Text(
                                    text = bucket.label,
                                    style = typography.uiLabel.copy(fontSize = 11.sp),
                                    color = if (selected) colors.onAccentPrimary else colors.textPrimary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                    if (uiState.availableLanguages.size > 1) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            item(key = "cat_lang_lbl") {
                                Text(
                                    text = "Language",
                                    style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                    color = colors.textSecondary,
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                            }
                            items(uiState.availableLanguages, key = { "cat_lang_$it" }) { lang ->
                                val selected = uiState.selectedLanguage == lang
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = if (selected) colors.accentPrimary else colors.surfaceSecondary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(18.dp))
                                        .clickable { onSelectLanguage(lang) }
                                        .testTag("catalogue_lang_${lang.lowercase().replace(' ', '_')}")
                                ) {
                                    Text(
                                        text = lang,
                                        style = typography.uiLabel.copy(fontSize = 11.sp),
                                        color = if (selected) colors.onAccentPrimary else colors.textPrimary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (uiState.query.isBlank()) {
            // Discovery Shortcuts & Country Row
            LazyRow(
                modifier = Modifier.testTag("catalogue_shortcuts_row"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(MoviesDiscoveryShortcut.entries, key = { "shortcut_${it.id}" }) { shortcut ->
                    val selected = uiState.activeShortcut == shortcut
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (selected) colors.accentPrimary else colors.surfacePrimary,
                        border = BorderStroke(0.75.dp, if (selected) colors.accentPrimary else colors.borderSubtle),
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { onSelectShortcut(shortcut) }
                            .testTag("catalogue_shortcut_${shortcut.id}")
                    ) {
                        Text(
                            text = shortcut.label,
                            style = typography.uiLabel.copy(
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                            ),
                            color = if (selected) colors.onAccentPrimary else colors.textPrimary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
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
                    val selected = uiState.selectedCountry.equals(country, ignoreCase = true)
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (selected) colors.accentContainer else colors.surfacePrimary,
                        border = BorderStroke(0.75.dp, if (selected) colors.accentPrimary else colors.borderSubtle),
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { onSelectCountry(country) }
                            .testTag("catalogue_country_${country.lowercase().replace(' ', '_')}")
                    ) {
                        Text(
                            text = if (country == "All") "All Regions" else country,
                            style = typography.uiLabel.copy(
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                            ),
                            color = if (selected) colors.accentPrimary else colors.textPrimary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // Sort + Genre Row
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(CatalogueSort.entries, key = { "sort_${it.name}" }) { sort ->
                    val selected = uiState.sort == sort
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) colors.textPrimary else colors.surfaceSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelectSort(sort) }
                            .testTag("sort_${sort.wireValue}")
                    ) {
                        Text(
                            text = sort.label,
                            style = typography.metaText.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                            color = if (selected) colors.backgroundPrimary else colors.textSecondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                items(
                    uiState.availableGenres.distinct(),
                    key = { "genre_$it" }
                ) { genre ->
                    val selected = uiState.selectedGenre.equals(genre, ignoreCase = true)
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (selected) colors.accentPrimary else colors.surfacePrimary,
                        border = BorderStroke(0.75.dp, if (selected) colors.accentPrimary else colors.borderSubtle),
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { onSelectGenre(genre) }
                            .testTag("genre_chip_$genre")
                    ) {
                        Text(
                            text = genre,
                            style = typography.uiLabel.copy(fontSize = 12.sp),
                            color = if (selected) colors.onAccentPrimary else colors.textPrimary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        when {
            uiState.isLoading && uiState.items.isEmpty() -> {
                com.laterbox.app.ui.components.MediaCatalogueSkeletonGrid(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("catalogue_loading_skeleton")
                )
            }
            uiState.errorMessage != null && uiState.items.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = uiState.errorMessage,
                            style = typography.bodySecondary,
                            color = colors.textSecondary
                        )
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accentPrimary)
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }
            uiState.items.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .testTag("catalogue_empty_state"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(LaterboxIcons.Movies),
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = colors.textTertiary
                        )
                        Text(
                            text = "No matching titles",
                            style = typography.titleLarge,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "Try a different keyword or reset your filters.",
                            style = typography.bodySecondary,
                            color = colors.textSecondary
                        )
                    }
                }
            }
            else -> {
                val offlineLookup = remember(uiState.offlineMatches) {
                    OfflineMediaLookup.from(uiState.offlineMatches)
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 112.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("catalogue_grid")
                ) {
                    itemsIndexed(
                        items = uiState.items,
                        key = { index, item -> "grid_${index}_${item.compositeId}" }
                    ) { index, item ->
                        LaunchedEffect(index, uiState.items.size, uiState.hasMore, uiState.isLoadingMore) {
                            if (index >= uiState.items.lastIndex - 3 && uiState.hasMore && !uiState.isLoadingMore) {
                                onLoadNextPage()
                            }
                        }
                        val isOffline = offlineLookup.hasTitle(item.providerId, item.id)
                        MoviePosterCard(
                            item = item,
                            onClick = { onOpenDetail(item.providerId, item.id) },
                            modifier = Modifier.fillMaxWidth(),
                            isOfflineDownloaded = isOffline,
                            isInWatchlist = item.compositeId in uiState.watchlistIds,
                            onToggleWatchlist = onToggleWatchlist,
                            onAddToCollection = { collectionPickerTarget = it },
                            onMarkWatched = onMarkWatched,
                            onHideTitle = onHideTitle
                        )
                    }

                    if (uiState.isLoadingMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    color = colors.accentPrimary
                                )
                            }
                        }
                    } else if (uiState.hasMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                TextButton(
                                    onClick = onLoadNextPage,
                                    modifier = Modifier.testTag("catalogue_load_more")
                                ) {
                                    Text("Load more", color = colors.accentPrimary)
                                }
                            }
                        }
                    }
                }
            }
        }
            }

            if (isDropdownOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = dismissSearch
                        )
                        .testTag("catalogue_search_scrim")
                )
                MoviesSearchSuggestionsDropdown(
                    query = uiState.query,
                    suggestions = uiState.suggestions,
                    recentSearches = uiState.recentSearches,
                    isLoading = uiState.isLoadingSuggestions,
                    errorMessage = uiState.suggestionsError,
                    containerTag = "catalogue_suggestions_box",
                    suggestionTagPrefix = "suggestion_item_",
                    onSuggestionClick = { suggestion ->
                        dismissSearch()
                        val cleanTitle = suggestion.title.trim()
                        if (suggestion.hasNavigableId) {
                            onOpenDetail(suggestion.providerId.trim(), suggestion.id.trim())
                        } else if (cleanTitle.isNotBlank()) {
                            onSubmitSearch(cleanTitle)
                        }
                    },
                    onSubmitQuery = { q ->
                        keyboardController?.hide()
                        runCatching { focusManager.clearFocus(force = true) }
                        onSubmitSearch(q)
                    },
                    onClearRecent = onClearSearchHistory,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
    }
}
