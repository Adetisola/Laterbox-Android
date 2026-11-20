package com.laterbox.app.movies.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laterbox.app.data.repository.CollectionRepository
import com.laterbox.app.domain.model.Collection
import com.laterbox.app.movies.data.provider.MovieBoxProvider
import com.laterbox.app.movies.domain.engine.MoviesDiscoveryEngine
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.LibrarySearchSuggestion
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesDiscoveryShortcut
import com.laterbox.app.movies.domain.model.OfflineMediaMatch
import com.laterbox.app.movies.domain.model.RuntimeBucket
import com.laterbox.app.movies.domain.model.SearchSuggestion
import com.laterbox.app.movies.domain.repository.MoviesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MoviesCatalogueUiState(
    val query: String = "",
    val selectedKind: MediaKind? = null,
    val selectedGenre: String = "All",
    val selectedCountry: String = "All",
    val activeShortcut: MoviesDiscoveryShortcut? = null,
    val sort: CatalogueSort = CatalogueSort.POPULAR,
    val availableGenres: List<String> = DEFAULT_GENRES,
    val availableCountries: List<String> = listOf("All") + MovieBoxProvider.VERIFIED_COUNTRIES,
    val items: List<MovieTitleSummary> = emptyList(),
    val supplementaryItems: List<MovieTitleSummary> = emptyList(),
    val page: Int = 1,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
    val suggestions: List<SearchSuggestion> = emptyList(),
    val librarySuggestions: List<LibrarySearchSuggestion> = emptyList(),
    val isLoadingSuggestions: Boolean = false,
    val suggestionsError: String? = null,
    val recentSearches: List<String> = emptyList(),
    val isSearchFocused: Boolean = false,
    val showSuggestionsDropdown: Boolean = false,
    val availableLanguages: List<String> = listOf("All"),
    val selectedLanguage: String = "All",
    val selectedYear: String = "All",
    val selectedRuntimeBucket: RuntimeBucket = RuntimeBucket.ALL,
    val offlineMatches: List<OfflineMediaMatch> = emptyList(),
    val hiddenMediaKeys: Set<String> = emptySet(),
    val watchlistIds: Set<String> = emptySet(),
    val collections: List<Collection> = emptyList(),
    val actionFeedbackMessage: String? = null,
    val actionErrorMessage: String? = null
) {
    companion object {
        val DEFAULT_GENRES = MovieBoxProvider.DEFAULT_GENRES
    }
}

class MoviesCatalogueViewModel(
    private val moviesRepository: MoviesRepository,
    private val collectionRepository: CollectionRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(MoviesCatalogueUiState())
    val uiState: StateFlow<MoviesCatalogueUiState> = _uiState.asStateFlow()

    private var fetchJob: Job? = null
    private var suggestJob: Job? = null
    private var librarySuggestJob: Job? = null
    private var suggestRequestSeq: Long = 0L
    private var librarySuggestSeq: Long = 0L
    private var pageRequestSeq: Long = 0L
    private var lastInitializedKey: String? = null
    private var lastSubmittedOrClearedSearch: String = ""
    private var activeSuggestedNormalized: String? = null
    private var lastSuggestedNormalized: String? = null
    private var lastLibrarySuggestedNormalized: String? = null
    private var rawItems: List<MovieTitleSummary> = emptyList()
    private var rawPrimaryItems: List<MovieTitleSummary> = emptyList()
    private var rawSupplementaryItems: List<MovieTitleSummary> = emptyList()
    private var rawHiddenKeys: Set<String> = emptySet()
    private var rawWatchlistIds: Set<String> = emptySet()
    private val pendingHiddenKeys = mutableSetOf<String>()
    private val pendingWatchlistAdded = mutableSetOf<String>()
    private val pendingWatchlistRemoved = mutableSetOf<String>()
    private val inFlightMutationKeys = mutableSetOf<String>()

    private fun effectiveHiddenKeys(): Set<String> =
        if (pendingHiddenKeys.isEmpty()) rawHiddenKeys else rawHiddenKeys + pendingHiddenKeys

    private fun effectiveWatchlistIds(): Set<String> =
        (rawWatchlistIds - pendingWatchlistRemoved) + pendingWatchlistAdded

    init {
        viewModelScope.launch {
            moviesRepository.observeRecentSearches(10).collect { recent ->
                _uiState.update { current ->
                    val shouldShow = current.isSearchFocused &&
                        (current.query.trim().length >= 2 || recent.isNotEmpty() || current.librarySuggestions.isNotEmpty())
                    current.copy(
                        recentSearches = recent,
                        showSuggestionsDropdown = if (current.isSearchFocused) shouldShow else current.showSuggestionsDropdown
                    )
                }
            }
        }
        viewModelScope.launch {
            moviesRepository.observeOfflineAvailability().collect { matches ->
                _uiState.update { it.copy(offlineMatches = matches) }
            }
        }
        viewModelScope.launch {
            moviesRepository.observeWatchlist().collect { list ->
                rawWatchlistIds = list.mapTo(LinkedHashSet()) { m -> m.compositeId }
                _uiState.update { it.copy(watchlistIds = effectiveWatchlistIds()) }
            }
        }
        viewModelScope.launch {
            moviesRepository.observeHiddenHistory(100).collect { hiddenList ->
                rawHiddenKeys = hiddenList.flatMapTo(LinkedHashSet()) {
                    listOfNotNull(it.stableMediaKey, it.compositeId.takeIf { c -> c.isNotBlank() })
                }
                pendingHiddenKeys.removeAll(rawHiddenKeys)
                _uiState.update { it.copy(hiddenMediaKeys = effectiveHiddenKeys()) }
                applyClientFilters()
            }
        }
        if (collectionRepository != null) {
            viewModelScope.launch {
                collectionRepository.collections.collect { cols ->
                    _uiState.update { it.copy(collections = cols) }
                }
            }
        }
        viewModelScope.launch {
            moviesRepository.getGenres().onSuccess { genres ->
                val normalized = genres.mapNotNull { raw ->
                    if (raw.equals("All", ignoreCase = true)) {
                        "All"
                    } else {
                        MovieBoxProvider.normalizeVerifiedGenre(raw) ?: raw.trim().takeIf { it.isNotBlank() }
                    }
                }
                val merged = (
                    listOf("All") +
                        normalized.filterNot { it.equals("All", ignoreCase = true) } +
                        MovieBoxProvider.VERIFIED_GENRES
                    ).distinct()
                if (merged.isNotEmpty()) {
                    _uiState.update { it.copy(availableGenres = merged) }
                }
            }
        }
    }

    fun initialize(
        sessionKey: String,
        initialKind: MediaKind? = null,
        initialGenre: String? = null,
        initialQuery: String? = null,
        initialSort: CatalogueSort? = null,
        initialCountry: String? = null
    ) {
        if (lastInitializedKey == sessionKey && rawItems.isNotEmpty()) return
        lastInitializedKey = sessionKey
        val cleanGenre = initialGenre?.takeIf { g -> g.isNotBlank() } ?: "All"
        val cleanCountry = initialCountry?.takeIf { c -> c.isNotBlank() } ?: "All"
        _uiState.update {
            it.copy(
                selectedKind = initialKind,
                selectedGenre = cleanGenre,
                selectedCountry = cleanCountry,
                selectedLanguage = "All",
                selectedYear = "All",
                selectedRuntimeBucket = RuntimeBucket.ALL,
                activeShortcut = matchingShortcut(initialKind, cleanGenre, cleanCountry),
                sort = initialSort ?: CatalogueSort.POPULAR,
                query = initialQuery.orEmpty(),
                isSearchFocused = false,
                showSuggestionsDropdown = false,
                isLoadingSuggestions = false,
                suggestionsError = null,
                suggestions = emptyList(),
                librarySuggestions = emptyList()
            )
        }
        loadPage(page = 1, append = false)
    }

    private fun normalizeQuery(raw: String): String =
        raw.trim().replace(MULTI_SPACE_REGEX, " ")

    private fun refreshLibrarySuggestions(normalized: String, force: Boolean = false) {
        if (!force && normalized == lastLibrarySuggestedNormalized && librarySuggestJob?.isActive != true) {
            return
        }
        val seq = ++librarySuggestSeq
        librarySuggestJob?.cancel()
        librarySuggestJob = viewModelScope.launch {
            val matches = runCatching {
                moviesRepository.searchLibrarySuggestions(normalized, limit = 6)
            }.getOrDefault(emptyList())
            if (seq != librarySuggestSeq || normalizeQuery(_uiState.value.query) != normalized) {
                return@launch
            }
            lastLibrarySuggestedNormalized = normalized
            _uiState.update { current ->
                val shouldShow = current.isSearchFocused && (
                    normalizeQuery(current.query).length >= 2 ||
                        current.isLoadingSuggestions ||
                        current.suggestionsError != null ||
                        current.recentSearches.isNotEmpty() ||
                        matches.isNotEmpty()
                    )
                current.copy(
                    librarySuggestions = matches,
                    showSuggestionsDropdown = if (current.isSearchFocused) shouldShow else current.showSuggestionsDropdown
                )
            }
        }
    }

    fun onQueryChange(newQuery: String) {
        val wasNotBlank = _uiState.value.query.isNotBlank()
        val normalized = normalizeQuery(newQuery)
        if (normalized.length < 2) {
            activeSuggestedNormalized = null
            lastSuggestedNormalized = null
            val reqSeq = ++suggestRequestSeq
            suggestJob?.cancel()
            refreshLibrarySuggestions(normalized)
            _uiState.update {
                it.copy(
                    query = newQuery,
                    isSearchFocused = true,
                    showSuggestionsDropdown = it.recentSearches.isNotEmpty() || it.librarySuggestions.isNotEmpty(),
                    suggestions = emptyList(),
                    isLoadingSuggestions = false,
                    suggestionsError = null
                )
            }
            if (newQuery.isBlank() && (wasNotBlank || lastSubmittedOrClearedSearch.isNotEmpty())) {
                lastSubmittedOrClearedSearch = ""
                loadPage(page = 1, append = false)
            }
            return
        }

        refreshLibrarySuggestions(normalized)

        val currentState = _uiState.value
        if (normalized == lastSuggestedNormalized && currentState.suggestionsError == null && suggestJob?.isActive != true) {
            _uiState.update {
                it.copy(
                    query = newQuery,
                    isSearchFocused = true,
                    showSuggestionsDropdown = true,
                    isLoadingSuggestions = false
                )
            }
            return
        }
        if (normalized == activeSuggestedNormalized && suggestJob?.isActive == true) {
            _uiState.update {
                it.copy(
                    query = newQuery,
                    isSearchFocused = true,
                    showSuggestionsDropdown = true,
                    isLoadingSuggestions = true
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                query = newQuery,
                isSearchFocused = true,
                showSuggestionsDropdown = true,
                isLoadingSuggestions = true,
                suggestionsError = null
            )
        }
        val reqSeq = ++suggestRequestSeq
        activeSuggestedNormalized = normalized
        suggestJob?.cancel()
        suggestJob = viewModelScope.launch {
            delay(260L)
            if (reqSeq != suggestRequestSeq || normalizeQuery(_uiState.value.query) != normalized) {
                return@launch
            }
            moviesRepository.getSuggestions(normalized, limit = 8).fold(
                onSuccess = { list ->
                    if (reqSeq != suggestRequestSeq || normalizeQuery(_uiState.value.query) != normalized) {
                        return@fold
                    }
                    activeSuggestedNormalized = null
                    lastSuggestedNormalized = normalized
                    _uiState.update {
                        it.copy(
                            suggestions = list,
                            isLoadingSuggestions = false,
                            suggestionsError = null
                        )
                    }
                },
                onFailure = { err ->
                    if (reqSeq != suggestRequestSeq || normalizeQuery(_uiState.value.query) != normalized) {
                        return@fold
                    }
                    activeSuggestedNormalized = null
                    lastSuggestedNormalized = null
                    _uiState.update {
                        it.copy(
                            suggestions = emptyList(),
                            isLoadingSuggestions = false,
                            suggestionsError = err.message?.takeIf { m -> m.isNotBlank() }
                                ?: "Couldn't load suggestions"
                        )
                    }
                }
            )
        }
    }

    fun onSearchFocusChange(focused: Boolean) {
        if (!focused) {
            dismissSuggestions()
        } else {
            val normalized = normalizeQuery(_uiState.value.query)
            refreshLibrarySuggestions(normalized, force = true)
            _uiState.update { current ->
                current.copy(
                    isSearchFocused = true,
                    showSuggestionsDropdown = normalized.length >= 2 ||
                        current.isLoadingSuggestions ||
                        current.suggestionsError != null ||
                        current.recentSearches.isNotEmpty() ||
                        current.librarySuggestions.isNotEmpty()
                )
            }
        }
    }

    fun setSuggestionsVisible(visible: Boolean) {
        onSearchFocusChange(visible)
    }

    fun dismissSuggestions() {
        ++suggestRequestSeq
        ++librarySuggestSeq
        activeSuggestedNormalized = null
        suggestJob?.cancel()
        librarySuggestJob?.cancel()
        _uiState.update {
            it.copy(
                isSearchFocused = false,
                isLoadingSuggestions = false,
                showSuggestionsDropdown = false
            )
        }
    }

    fun submitSearch(overrideQuery: String? = null) {
        val q = normalizeQuery(overrideQuery ?: _uiState.value.query)
        ++suggestRequestSeq
        ++librarySuggestSeq
        activeSuggestedNormalized = null
        lastSuggestedNormalized = null
        lastLibrarySuggestedNormalized = null
        suggestJob?.cancel()
        librarySuggestJob?.cancel()
        lastSubmittedOrClearedSearch = q
        _uiState.update {
            it.copy(
                query = q,
                isSearchFocused = false,
                isLoadingSuggestions = false,
                suggestionsError = null,
                showSuggestionsDropdown = false,
                suggestions = emptyList()
            )
        }
        if (q.isNotBlank()) {
            viewModelScope.launch {
                moviesRepository.recordSearchQuery(q)
            }
        }
        loadPage(page = 1, append = false)
    }

    fun clearSearch() {
        ++suggestRequestSeq
        ++librarySuggestSeq
        activeSuggestedNormalized = null
        lastSuggestedNormalized = null
        lastLibrarySuggestedNormalized = null
        suggestJob?.cancel()
        librarySuggestJob?.cancel()
        lastSubmittedOrClearedSearch = ""
        _uiState.update {
            it.copy(
                query = "",
                suggestions = emptyList(),
                librarySuggestions = emptyList(),
                isSearchFocused = false,
                isLoadingSuggestions = false,
                suggestionsError = null,
                showSuggestionsDropdown = false
            )
        }
        loadPage(page = 1, append = false)
    }

    fun selectKind(kind: MediaKind?) {
        if (_uiState.value.selectedKind == kind) return
        _uiState.update { current ->
            current.copy(
                selectedKind = kind,
                activeShortcut = matchingShortcut(kind, current.selectedGenre, current.selectedCountry)
            )
        }
        loadPage(page = 1, append = false)
    }

    fun selectGenre(genre: String) {
        if (_uiState.value.selectedGenre == genre) return
        _uiState.update { current ->
            current.copy(
                selectedGenre = genre,
                activeShortcut = matchingShortcut(current.selectedKind, genre, current.selectedCountry)
            )
        }
        loadPage(page = 1, append = false)
    }

    fun selectCountry(country: String) {
        if (_uiState.value.selectedCountry == country) return
        _uiState.update { current ->
            current.copy(
                selectedCountry = country,
                activeShortcut = matchingShortcut(current.selectedKind, current.selectedGenre, country)
            )
        }
        loadPage(page = 1, append = false)
    }

    fun selectShortcut(shortcut: MoviesDiscoveryShortcut) {
        val isTogglingOff = _uiState.value.activeShortcut == shortcut
        _uiState.update {
            if (isTogglingOff) {
                it.copy(
                    activeShortcut = null,
                    selectedKind = null,
                    selectedGenre = "All",
                    selectedCountry = "All"
                )
            } else {
                it.copy(
                    activeShortcut = shortcut,
                    selectedKind = shortcut.kind,
                    selectedGenre = shortcut.genre ?: "All",
                    selectedCountry = shortcut.country ?: "All"
                )
            }
        }
        loadPage(page = 1, append = false)
    }

    fun selectSort(sort: CatalogueSort) {
        if (_uiState.value.sort == sort) return
        _uiState.update { it.copy(sort = sort) }
        loadPage(page = 1, append = false)
    }

    fun selectLanguage(language: String) {
        if (_uiState.value.selectedLanguage == language) return
        _uiState.update { it.copy(selectedLanguage = language) }
        applyClientFilters()
    }

    fun selectYear(year: String) {
        if (_uiState.value.selectedYear == year) return
        _uiState.update { it.copy(selectedYear = year) }
        loadPage(page = 1, append = false)
    }

    fun selectRuntimeBucket(bucket: RuntimeBucket) {
        if (_uiState.value.selectedRuntimeBucket == bucket) return
        _uiState.update { it.copy(selectedRuntimeBucket = bucket) }
        applyClientFilters()
    }

    fun toggleWatchlist(item: MovieTitleSummary) {
        val opKey = "wl:${item.compositeId}"
        if (!inFlightMutationKeys.add(opKey)) return
        val currentlyInWatchlist = item.compositeId in effectiveWatchlistIds()
        if (currentlyInWatchlist) {
            pendingWatchlistAdded.remove(item.compositeId)
            pendingWatchlistRemoved.add(item.compositeId)
        } else {
            pendingWatchlistRemoved.remove(item.compositeId)
            pendingWatchlistAdded.add(item.compositeId)
        }
        _uiState.update {
            it.copy(
                watchlistIds = effectiveWatchlistIds(),
                actionFeedbackMessage = if (currentlyInWatchlist) {
                    "Removed ${item.title} from Watchlist"
                } else {
                    "Added ${item.title} to Watchlist"
                },
                actionErrorMessage = null
            )
        }
        viewModelScope.launch {
            try {
                val res = runCatching { moviesRepository.toggleWatchlist(item) }
                if (res.isFailure) {
                    if (currentlyInWatchlist) {
                        pendingWatchlistRemoved.remove(item.compositeId)
                    } else {
                        pendingWatchlistAdded.remove(item.compositeId)
                    }
                    _uiState.update {
                        it.copy(
                            watchlistIds = effectiveWatchlistIds(),
                            actionFeedbackMessage = null,
                            actionErrorMessage = "Couldn't update Watchlist for ${item.title}."
                        )
                    }
                } else {
                    pendingWatchlistAdded.remove(item.compositeId)
                    pendingWatchlistRemoved.remove(item.compositeId)
                }
            } finally {
                inFlightMutationKeys.remove(opKey)
            }
        }
    }

    fun markWatched(item: MovieTitleSummary) {
        viewModelScope.launch {
            moviesRepository.markWatched(item = item, durationMs = (item.durationMinutes ?: 90) * 60_000L)
            _uiState.update { it.copy(actionFeedbackMessage = "Marked ${item.title} as watched") }
        }
    }

    fun hideTitle(item: MovieTitleSummary) {
        val opKey = "hide:${item.compositeId}"
        if (!inFlightMutationKeys.add(opKey)) return
        val keys = listOf(item.stableMediaKey, item.compositeId)
        pendingHiddenKeys.addAll(keys)
        _uiState.update {
            it.copy(
                hiddenMediaKeys = effectiveHiddenKeys(),
                actionFeedbackMessage = "Hidden ${item.title}",
                actionErrorMessage = null
            )
        }
        applyClientFilters()
        viewModelScope.launch {
            try {
                val res = runCatching { moviesRepository.setTitleHidden(item, true) }
                if (res.isFailure) {
                    pendingHiddenKeys.removeAll(keys.toSet())
                    _uiState.update {
                        it.copy(
                            hiddenMediaKeys = effectiveHiddenKeys(),
                            actionFeedbackMessage = null,
                            actionErrorMessage = "Couldn't hide ${item.title}. Please try again."
                        )
                    }
                    applyClientFilters()
                }
            } finally {
                inFlightMutationKeys.remove(opKey)
            }
        }
    }

    fun addToCollection(item: MovieTitleSummary, collectionId: Long) {
        viewModelScope.launch {
            moviesRepository.addMediaToCollection(item, collectionId)
            val colName = _uiState.value.collections.firstOrNull { it.id == collectionId }?.name ?: "collection"
            _uiState.update { it.copy(actionFeedbackMessage = "Added ${item.title} to $colName") }
        }
    }

    fun createAndAddToCollection(item: MovieTitleSummary, name: String) {
        val clean = name.trim()
        if (clean.isEmpty() || collectionRepository == null) return
        viewModelScope.launch {
            val id = collectionRepository.createCollection(clean)
            moviesRepository.addMediaToCollection(item, id)
            _uiState.update { it.copy(actionFeedbackMessage = "Added ${item.title} to $clean") }
        }
    }

    fun clearActionFeedback() {
        _uiState.update { it.copy(actionFeedbackMessage = null) }
    }

    fun clearActionError() {
        _uiState.update { it.copy(actionErrorMessage = null) }
    }

    fun resetFilters() {
        suggestJob?.cancel()
        _uiState.update {
            it.copy(
                query = "",
                selectedKind = null,
                selectedGenre = "All",
                selectedCountry = "All",
                selectedLanguage = "All",
                selectedYear = "All",
                selectedRuntimeBucket = RuntimeBucket.ALL,
                activeShortcut = null,
                sort = CatalogueSort.POPULAR,
                isSearchFocused = false,
                showSuggestionsDropdown = false,
                isLoadingSuggestions = false,
                suggestionsError = null,
                suggestions = emptyList()
            )
        }
        loadPage(page = 1, append = false)
    }

    fun loadNextPage() {
        val current = _uiState.value
        if (current.isLoading || current.isLoadingMore || !current.hasMore) return
        loadPage(page = current.page + 1, append = true)
    }

    fun retry() {
        loadPage(page = 1, append = false)
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            moviesRepository.clearSearchHistory()
        }
    }

    private fun applyClientFilters() {
        val state = _uiState.value
        val filteredPrimary = MoviesDiscoveryEngine.filterTitles(
            items = rawPrimaryItems,
            kind = state.selectedKind,
            genre = state.selectedGenre,
            language = state.selectedLanguage,
            country = state.selectedCountry,
            year = state.selectedYear,
            runtimeBucket = state.selectedRuntimeBucket,
            hiddenMediaKeys = state.hiddenMediaKeys
        )
        val filteredSupp = MoviesDiscoveryEngine.filterTitles(
            items = rawSupplementaryItems,
            kind = state.selectedKind,
            genre = state.selectedGenre,
            language = state.selectedLanguage,
            country = state.selectedCountry,
            year = state.selectedYear,
            runtimeBucket = state.selectedRuntimeBucket,
            hiddenMediaKeys = state.hiddenMediaKeys
        )
        _uiState.update { it.copy(items = filteredPrimary, supplementaryItems = filteredSupp) }
    }

    private fun matchingShortcut(
        kind: MediaKind?,
        genre: String,
        country: String
    ): MoviesDiscoveryShortcut? = MoviesDiscoveryShortcut.entries.firstOrNull { shortcut ->
        shortcut.kind == kind &&
            (shortcut.genre ?: "All").equals(genre, ignoreCase = true) &&
            (shortcut.country ?: "All").equals(country, ignoreCase = true)
    }

    private fun loadPage(page: Int, append: Boolean) {
        val reqSeq = ++pageRequestSeq
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            _uiState.update {
                if (append) {
                    it.copy(isLoadingMore = true, errorMessage = null)
                } else {
                    it.copy(isLoading = true, isLoadingMore = false, errorMessage = null)
                }
            }
            val state = _uiState.value
            val query = normalizeQuery(state.query)
            val exactYearParam = state.selectedYear.takeIf { Regex("\\d{4}").matches(it) }
            val result = if (query.isNotBlank()) {
                moviesRepository.search(query = query, kind = state.selectedKind, page = page, pageSize = 20)
            } else {
                moviesRepository.getCatalogue(
                    kind = state.selectedKind,
                    page = page,
                    pageSize = 20,
                    genre = state.selectedGenre.takeUnless { it == "All" },
                    sort = state.sort,
                    country = state.selectedCountry.takeUnless { it == "All" },
                    year = exactYearParam
                )
            }

            result.fold(
                onSuccess = { pageResult ->
                    if (reqSeq != pageRequestSeq) return@fold
                    val splitIndex = pageResult.primaryCount.coerceIn(0, pageResult.items.size)
                    val pagePrimary = pageResult.items.take(splitIndex)
                    val pageSupp = pageResult.items.drop(splitIndex)

                    val mergedPrimary = if (append) {
                        (rawPrimaryItems + pagePrimary).distinctBy { it.compositeId }
                    } else {
                        pagePrimary.distinctBy { it.compositeId }
                    }
                    val primaryKeys = buildSet {
                        mergedPrimary.forEach { item ->
                            add(item.compositeId)
                            add(item.stableMediaKey)
                            item.canonicalId?.let(::add)
                        }
                    }
                    val mergedSupp = if (append) {
                        (rawSupplementaryItems + pageSupp)
                    } else {
                        pageSupp
                    }.filter { item ->
                        item.compositeId !in primaryKeys &&
                            item.stableMediaKey !in primaryKeys &&
                            (item.canonicalId == null || item.canonicalId !in primaryKeys)
                    }.distinctBy { it.compositeId }

                    rawPrimaryItems = mergedPrimary
                    rawSupplementaryItems = mergedSupp
                    val mergedRaw = mergedPrimary + mergedSupp
                    rawItems = mergedRaw
                    val latest = _uiState.value
                    val langs = MoviesDiscoveryEngine.extractAvailableLanguages(mergedRaw)
                    val filteredPrimary = MoviesDiscoveryEngine.filterTitles(
                        items = mergedPrimary,
                        kind = latest.selectedKind,
                        genre = latest.selectedGenre,
                        language = latest.selectedLanguage,
                        country = latest.selectedCountry,
                        year = latest.selectedYear,
                        runtimeBucket = latest.selectedRuntimeBucket,
                        hiddenMediaKeys = latest.hiddenMediaKeys
                    )
                    val filteredSupp = MoviesDiscoveryEngine.filterTitles(
                        items = mergedSupp,
                        kind = latest.selectedKind,
                        genre = latest.selectedGenre,
                        language = latest.selectedLanguage,
                        country = latest.selectedCountry,
                        year = latest.selectedYear,
                        runtimeBucket = latest.selectedRuntimeBucket,
                        hiddenMediaKeys = latest.hiddenMediaKeys
                    )
                    _uiState.update { current ->
                        current.copy(
                            items = filteredPrimary,
                            supplementaryItems = filteredSupp,
                            availableLanguages = langs,
                            page = page,
                            hasMore = pageResult.hasMore,
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = null
                        )
                    }
                },
                onFailure = { err ->
                    if (reqSeq != pageRequestSeq) return@fold
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = err.message?.takeIf { m -> m.isNotBlank() }
                                ?: "Unable to load catalogue results."
                        )
                    }
                }
            )
        }
    }

    companion object {
        private val MULTI_SPACE_REGEX = Regex("\\s+")
    }
}
