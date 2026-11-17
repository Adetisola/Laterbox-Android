package com.laterbox.app.movies.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.ui.components.LaterboxIcons
import com.laterbox.app.ui.theme.LaterboxTheme

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
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backgroundPrimary)
            .navigationBarsPadding()
            .testTag("movies_catalogue_screen")
    ) {
        // Search + Back Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onBackClick,
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
                    Box(modifier = Modifier.weight(1f)) {
                        if (uiState.query.isEmpty()) {
                            Text(
                                text = "Search movies & series...",
                                style = typography.bodySecondary,
                                color = colors.textTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = uiState.query,
                            onValueChange = onQueryChange,
                            singleLine = true,
                            textStyle = typography.bodySecondary.copy(color = colors.textPrimary),
                            cursorBrush = SolidColor(colors.accentPrimary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { onSubmitSearch(null) }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { onSearchFocusChange(it.isFocused) }
                                .testTag("catalogue_search_input")
                        )
                    }
                    if (uiState.query.isNotEmpty()) {
                        IconButton(
                            onClick = onClearSearch,
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

        // Suggestions & Recent Searches Dropdown
        if (uiState.showSuggestionsDropdown &&
            (uiState.suggestions.isNotEmpty() || (uiState.query.isBlank() && uiState.recentSearches.isNotEmpty()))
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("catalogue_suggestions_box"),
                shape = RoundedCornerShape(14.dp),
                color = colors.surfaceElevated,
                border = BorderStroke(1.dp, colors.borderSubtle),
                shadowElevation = 4.dp
            ) {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    if (uiState.query.isBlank() && uiState.recentSearches.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Recent searches",
                                style = typography.metaText,
                                color = colors.textSecondary
                            )
                            TextButton(onClick = onClearSearchHistory) {
                                Text("Clear", style = typography.caption, color = colors.accentPrimary)
                            }
                        }
                        uiState.recentSearches.take(5).forEach { recent ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSubmitSearch(recent) }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    painter = painterResource(LaterboxIcons.Clock),
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = colors.textTertiary
                                )
                                Text(
                                    text = recent,
                                    style = typography.bodySecondary,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    } else {
                        uiState.suggestions.forEachIndexed { idx, suggestion ->
                            val suggestionTagSuffix = if (suggestion.hasNavigableId) suggestion.id else "idx_$idx"
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (suggestion.hasNavigableId) {
                                            onOpenDetail(suggestion.providerId, suggestion.id)
                                        } else {
                                            onSubmitSearch(suggestion.title)
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                                    .testTag("suggestion_item_$suggestionTagSuffix"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = suggestion.title,
                                    style = typography.bodySecondary.copy(fontWeight = FontWeight.Medium),
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                val suffix = buildList {
                                    suggestion.releaseYear?.let { add(it.toString()) }
                                    add(suggestion.kind.label)
                                }.joinToString(" · ")
                                Text(
                                    text = suffix,
                                    style = typography.caption,
                                    color = colors.textSecondary
                                )
                            }
                            if (idx < uiState.suggestions.lastIndex) {
                                HorizontalDivider(thickness = 0.5.dp, color = colors.borderSubtle)
                            }
                        }
                    }
                }
            }
        }

        // Kind + Sort Filters Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
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
        }

        // Sort + Genre Row
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (uiState.query.isBlank()) {
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
            }

            items(uiState.availableGenres, key = { "genre_$it" }) { genre ->
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

        Spacer(modifier = Modifier.height(6.dp))

        when {
            uiState.isLoading && uiState.items.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accentPrimary)
                }
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
                        key = { _, item -> "grid_${item.compositeId}" }
                    ) { index, item ->
                        if (index >= uiState.items.lastIndex - 3 && uiState.hasMore && !uiState.isLoadingMore) {
                            onLoadNextPage()
                        }
                        MoviePosterCard(
                            item = item,
                            onClick = { onOpenDetail(item.providerId, item.id) },
                            modifier = Modifier.fillMaxWidth()
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
}
