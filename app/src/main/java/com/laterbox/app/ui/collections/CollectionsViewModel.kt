package com.laterbox.app.ui.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laterbox.app.ai.RemoteCapabilities
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.data.repository.CollectionRepository
import com.laterbox.app.domain.model.Collection
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

class CollectionsViewModel(
    private val collectionRepository: CollectionRepository,
    private val userUnderstandingRepository: UserUnderstandingRepository? = null,
    private val userPreferencesRepository: UserPreferencesRepository? = null,
    private val remoteConfigManager: RemoteConfigManager? = null
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _showCreateDialog = MutableStateFlow(false)
    private val _collectionToRename = MutableStateFlow<Collection?>(null)
    private val _collectionToDelete = MutableStateFlow<Collection?>(null)

    private val facetsFlow = userUnderstandingRepository?.observeAllFacets() ?: flowOf(emptyList())
    private val localPersonalizationFlow = userPreferencesRepository?.userPreferences
        ?.map { it.personalizationEnabled }
        ?.distinctUntilChanged()
        ?: flowOf(true)
    private val remoteIntelEnabledFlow = remoteConfigManager?.state
        ?.map { it.activeConfig.isFeatureEnabled(RemoteCapabilities.KEY_EXPERIENCE_INTELLIGENCE, true) }
        ?.distinctUntilChanged()
        ?: flowOf(true)

    val uiState: StateFlow<CollectionsUiState> = combine(
        combine(
            collectionRepository.collectionsWithCounts,
            _searchQuery,
            _showCreateDialog,
            _collectionToRename,
            _collectionToDelete
        ) { collections, query, showCreate, toRename, toDelete ->
            CollectionsUiState(
                collections = collections,
                isLoading = false,
                searchQuery = query,
                showCreateDialog = showCreate,
                collectionToRename = toRename,
                collectionToDelete = toDelete
            )
        },
        combine(facetsFlow, localPersonalizationFlow, remoteIntelEnabledFlow) { facets, localEnabled, remoteEnabled ->
            Triple(facets, localEnabled, remoteEnabled)
        }
    ) { baseState, (facets, localEnabled, remoteEnabled) ->
        val query = baseState.searchQuery.trim()
        val rankedCollections = if (query.isBlank()) {
            baseState.collections
        } else {
            val matched = baseState.collections.filter { entry ->
                entry.collection.name.contains(query, ignoreCase = true)
            }
            SearchTieBreaker.rankCollections(
                collections = matched,
                query = query,
                facets = facets,
                applyTieBreaker = localEnabled && remoteEnabled
            )
        }
        baseState.copy(collections = rankedCollections)
    }.distinctUntilChanged().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CollectionsUiState(isLoading = true)
    )

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun onClearSearch() {
        _searchQuery.value = ""
    }

    fun onOpenCreateDialog() {
        _showCreateDialog.value = true
    }

    fun onCloseCreateDialog() {
        _showCreateDialog.value = false
    }

    fun onCreateCollection(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                collectionRepository.createCollection(trimmed)
                _showCreateDialog.value = false
            } catch (e: Exception) {
                // Ignore unique constraint or duplicate
            }
        }
    }

    fun onOpenRenameDialog(collection: Collection) {
        _collectionToRename.value = collection
    }

    fun onCloseRenameDialog() {
        _collectionToRename.value = null
    }

    fun onRenameCollection(id: Long, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                collectionRepository.updateCollection(id, trimmed)
                _collectionToRename.value = null
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun onOpenDeleteDialog(collection: Collection) {
        _collectionToDelete.value = collection
    }

    fun onCloseDeleteDialog() {
        _collectionToDelete.value = null
    }

    fun onDeleteCollection(id: Long) {
        viewModelScope.launch {
            collectionRepository.deleteCollection(id)
            _collectionToDelete.value = null
        }
    }
}
