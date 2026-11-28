package com.laterbox.app.ui.archive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laterbox.app.ai.RemoteCapabilities
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.data.repository.LinkRepository
import com.laterbox.app.domain.model.SavedLink
import com.laterbox.app.intelligence.SearchTieBreaker
import com.laterbox.app.intelligence.UserUnderstandingRepository
import com.laterbox.app.remoteconfig.RemoteConfigManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ArchiveViewModel(
    private val linkRepository: LinkRepository,
    private val userUnderstandingRepository: UserUnderstandingRepository? = null,
    private val userPreferencesRepository: UserPreferencesRepository? = null,
    private val remoteConfigManager: RemoteConfigManager? = null
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _linkToDelete = MutableStateFlow<SavedLink?>(null)

    private val facetsFlow = userUnderstandingRepository?.observeAllFacets() ?: flowOf(emptyList())
    private val remoteIntelEnabledFlow = remoteConfigManager?.state
        ?.map { it.activeConfig.isFeatureEnabled(RemoteCapabilities.KEY_EXPERIENCE_INTELLIGENCE, true) }
        ?.distinctUntilChanged()
        ?: flowOf(true)

    val uiState: StateFlow<ArchiveUiState> = combine(
        combine(linkRepository.archivedLinks, _searchQuery, _linkToDelete) { archived, query, toDelete ->
            Triple(archived, query, toDelete)
        },
        combine(facetsFlow, remoteIntelEnabledFlow) { facets, remoteEnabled ->
            facets to remoteEnabled
        }
    ) { (archived, query, toDelete), (facets, remoteEnabled) ->
        val filtered = if (query.isBlank()) {
            archived
        } else {
            val matched = archived.filter { link ->
                link.title.contains(query, ignoreCase = true) ||
                    link.domain.contains(query, ignoreCase = true) ||
                    link.originalUrl.contains(query, ignoreCase = true) ||
                    (link.siteName?.contains(query, ignoreCase = true) == true) ||
                    (link.description?.contains(query, ignoreCase = true) == true) ||
                    (link.personalNote?.contains(query, ignoreCase = true) == true)
            }
            SearchTieBreaker.rankLinks(
                links = matched,
                query = query,
                facets = facets,
                applyTieBreaker = remoteEnabled
            )
        }
        ArchiveUiState(
            links = filtered,
            isLoading = false,
            searchQuery = query,
            linkToDelete = toDelete
        )
    }.distinctUntilChanged().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ArchiveUiState(isLoading = true)
    )

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun onClearSearch() {
        _searchQuery.value = ""
    }

    fun onRestoreLink(linkId: Long) {
        viewModelScope.launch {
            linkRepository.setArchived(linkId, false)
        }
    }

    fun onOpenDeleteDialog(link: SavedLink) {
        _linkToDelete.value = link
    }

    fun onCloseDeleteDialog() {
        _linkToDelete.value = null
    }

    fun onPermanentDelete(linkId: Long) {
        viewModelScope.launch {
            linkRepository.deleteLink(linkId)
            _linkToDelete.value = null
        }
    }
}
