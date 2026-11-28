package com.laterbox.app.ui.sheets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laterbox.app.ai.RemoteCapabilities
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.data.repository.CollectionRepository
import com.laterbox.app.data.repository.LinkRepository
import com.laterbox.app.domain.model.Collection
import com.laterbox.app.domain.model.MetadataStatus
import com.laterbox.app.domain.model.SavedLink
import com.laterbox.app.intelligence.CollectionSuggester
import com.laterbox.app.intelligence.LinkCollectionSuggestion
import com.laterbox.app.media.data.adapters.direct.DirectUrlAdapter
import com.laterbox.app.media.domain.model.DownloadTask
import com.laterbox.app.media.domain.model.InspectionResult
import com.laterbox.app.media.domain.model.MediaAsset
import com.laterbox.app.media.domain.model.MediaCandidate
import com.laterbox.app.media.domain.repository.MediaRepository
import com.laterbox.app.remoteconfig.RemoteConfigManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

data class SavedItemDetailUiState(
    val link: SavedLink? = null,
    val editedTitle: String = "",
    val editedNote: String = "",
    val selectedCollectionIds: Set<Long> = emptySet(),
    val collections: List<Collection> = emptyList(),
    val isSaving: Boolean = false,
    val assets: List<MediaAsset> = emptyList(),
    val activeTasks: List<DownloadTask> = emptyList(),
    val inspectionResult: InspectionResult? = null,
    val isInspecting: Boolean = false,
    val suggestedCollection: LinkCollectionSuggestion? = null
) {
    val selectedCollectionId: Long?
        get() = selectedCollectionIds.firstOrNull()
}

@OptIn(ExperimentalCoroutinesApi::class)
class SavedItemDetailViewModel(
    private val linkRepository: LinkRepository,
    private val collectionRepository: CollectionRepository,
    private val mediaRepository: MediaRepository,
    private val platformResolver: com.laterbox.app.media.domain.adapter.PlatformResolver,
    private val userPreferencesRepository: UserPreferencesRepository? = null,
    private val remoteConfigManager: RemoteConfigManager? = null,
    private val userUnderstandingRepository: com.laterbox.app.intelligence.UserUnderstandingRepository? = null
) : ViewModel() {

    private val _currentLink = MutableStateFlow<SavedLink?>(null)
    private val _editedTitle = MutableStateFlow("")
    private val _editedNote = MutableStateFlow("")
    private val _selectedCollectionIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _isSaving = MutableStateFlow(false)
    private val _dismissedSuggestionLinkIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _assets = _currentLink.flatMapLatest { link ->
        if (link == null) flowOf(emptyList()) else mediaRepository.getAssetsForLink(link.id)
    }

    private val _tasks = _currentLink.flatMapLatest { link ->
        if (link == null) flowOf(emptyList()) else mediaRepository.getTasksForLink(link.id)
    }

    private val _inspectionResult = _currentLink.flatMapLatest { link ->
        if (link == null) flowOf(null) else mediaRepository.getInspectionForLink(link.id)
    }

    private val _isInspecting = _currentLink.flatMapLatest { link ->
        if (link == null) flowOf(false) else mediaRepository.isInspectingLink(link.id)
    }

    private val activeDismissalsFlow = userUnderstandingRepository?.observeActiveDismissals() ?: flowOf(emptyList())
    private val remoteIntelEnabledFlow = remoteConfigManager?.state
        ?.map { it.activeConfig.isFeatureEnabled(RemoteCapabilities.KEY_EXPERIENCE_INTELLIGENCE, true) }
        ?.distinctUntilChanged()
        ?: flowOf(true)

    private val suggestionContextFlow = combine(
        linkRepository.inboxLinks,
        _dismissedSuggestionLinkIds,
        activeDismissalsFlow,
        remoteIntelEnabledFlow
    ) { inboxLinks, dismissedIds, persistedDismissals, remoteEnabled ->
        val nowMs = System.currentTimeMillis()
        val persistedCollectionDismissals = persistedDismissals.filter {
            it.suggestionType == com.laterbox.app.data.local.entity.SuggestionDismissalType.COLLECTION_SUGGESTION.name &&
                (it.suppressUntilMs == null || it.suppressUntilMs > nowMs)
        }
        val mergedIds = dismissedIds + persistedCollectionDismissals.mapNotNull { it.linkId.takeIf { id -> id > 0L } }
        val hashes = persistedCollectionDismissals.map { it.linkIdentityHash }.toSet()
        listOf(inboxLinks, mergedIds, hashes, remoteEnabled)
    }

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<SavedItemDetailUiState> = combine(
        combine(_currentLink, _editedTitle, _editedNote, _selectedCollectionIds, _isSaving) { link, title, note, colIds, saving ->
            SavedItemDetailUiState(
                link = link,
                editedTitle = title,
                editedNote = note,
                selectedCollectionIds = colIds,
                isSaving = saving
            )
        },
        collectionRepository.collections,
        _assets,
        _tasks,
        combine(_inspectionResult, _isInspecting, suggestionContextFlow) { res, inspecting, sCtx ->
            Triple(res, inspecting, sCtx)
        }
    ) { base, collections, assets, tasks, (res, inspecting, suggestionContext) ->
        val inboxLinks = suggestionContext[0] as List<SavedLink>
        val dismissedLinkIds = suggestionContext[1] as Set<Long>
        val dismissedLinkHashes = suggestionContext[2] as Set<String>
        val intelEnabled = suggestionContext[3] as Boolean
        val link = base.link
        val suggested = if (
            intelEnabled &&
            link != null &&
            base.selectedCollectionIds.isEmpty()
        ) {
            CollectionSuggester.suggestForLink(
                targetLink = link,
                collections = collections,
                allLinks = inboxLinks,
                dismissedLinkIds = dismissedLinkIds,
                dismissedLinkHashes = dismissedLinkHashes
            )
        } else {
            null
        }
        base.copy(
            collections = collections,
            assets = assets,
            activeTasks = tasks,
            inspectionResult = res,
            isInspecting = inspecting,
            suggestedCollection = suggested
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SavedItemDetailUiState()
    )

    fun setLink(link: SavedLink) {
        _currentLink.value = link
        _editedTitle.value = link.title
        _editedNote.value = link.personalNote.orEmpty()
        _selectedCollectionIds.value = link.collectionIds.toSet()
        viewModelScope.launch {
            mediaRepository.recoverOrphanedInspection(link.id)
            if (com.laterbox.app.media.data.inbox.InboxLinkRouter.routeForUrl(link.originalUrl) != null) {
                mediaRepository.startInspection(
                    linkId = link.id,
                    url = link.originalUrl,
                    previewImageUrl = link.previewImageUrl,
                    forceRefresh = false
                )
            }
        }
    }

    fun onTitleChange(newTitle: String) {
        _editedTitle.value = newTitle
    }

    fun onNoteChange(newNote: String) {
        _editedNote.value = newNote
    }

    fun onToggleCollection(collectionId: Long) {
        val link = _currentLink.value ?: return
        val currentIds = _selectedCollectionIds.value.toMutableSet()
        val willHave = !currentIds.contains(collectionId)
        if (willHave) {
            currentIds.add(collectionId)
        } else {
            currentIds.remove(collectionId)
        }
        _selectedCollectionIds.value = currentIds
        viewModelScope.launch {
            linkRepository.toggleLinkCollection(link.id, collectionId)
            val updatedNames = if (willHave) {
                val col = collectionRepository.getCollectionById(collectionId).first()
                if (col != null) link.collectionNames + col.name else link.collectionNames
            } else {
                val col = collectionRepository.getCollectionById(collectionId).first()
                if (col != null) link.collectionNames.filter { it != col.name } else link.collectionNames
            }
            _currentLink.value = link.copy(
                collectionIds = currentIds.toList(),
                collectionNames = updatedNames
            )
        }
    }

    fun onCreateAndAssignCollection(name: String) {
        val link = _currentLink.value ?: return
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val newId = collectionRepository.createCollection(trimmed)
            linkRepository.toggleLinkCollection(link.id, newId)
            val newSet = _selectedCollectionIds.value + newId
            _selectedCollectionIds.value = newSet
            _currentLink.value = link.copy(
                collectionIds = newSet.toList(),
                collectionNames = link.collectionNames + trimmed
            )
        }
    }

    fun onSaveEdits(onSuccess: () -> Unit) {
        val link = _currentLink.value ?: return
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val updated = link.copy(
                    title = if (_editedTitle.value.isNotBlank()) _editedTitle.value.trim() else link.domain,
                    personalNote = _editedNote.value.trim().ifEmpty { null },
                    collectionIds = _selectedCollectionIds.value.toList()
                )
                linkRepository.updateLink(updated)
                linkRepository.setLinkCollections(link.id, _selectedCollectionIds.value.toList())
                _currentLink.value = updated
                onSuccess()
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun onToggleRead() {
        val link = _currentLink.value ?: return
        viewModelScope.launch {
            val updated = link.copy(isRead = !link.isRead)
            linkRepository.updateLink(updated)
            _currentLink.value = updated
        }
    }

    fun onToggleArchive(onComplete: () -> Unit) {
        val link = _currentLink.value ?: return
        viewModelScope.launch {
            linkRepository.setArchived(link.id, !link.isArchived)
            onComplete()
        }
    }

    fun onDelete(deleteFiles: Boolean = false, onDeleted: () -> Unit) {
        val link = _currentLink.value ?: return
        viewModelScope.launch {
            if (deleteFiles) {
                mediaRepository.deleteAssetsForLink(link.id, deleteFiles = true)
            }
            linkRepository.deleteLink(link.id)
            _currentLink.value = null
            onDeleted()
        }
    }

    fun deleteWithUndo(deleteFiles: Boolean = false, onDeleted: (SavedLink) -> Unit) {
        val link = _currentLink.value ?: return
        viewModelScope.launch {
            if (deleteFiles) {
                mediaRepository.deleteAssetsForLink(link.id, deleteFiles = true)
            }
            linkRepository.deleteLink(link.id)
            _currentLink.value = null
            onDeleted(link)
        }
    }

    fun onRetryMetadata() {
        val link = _currentLink.value ?: return
        val targetLinkId = link.id
        viewModelScope.launch {
            _currentLink.value = link.copy(metadataStatus = MetadataStatus.FETCHING)
            linkRepository.retrySingleLink(targetLinkId)
            val updated = linkRepository.getLinkById(targetLinkId).first()
            if (updated != null && _currentLink.value?.id == targetLinkId) {
                _currentLink.value = updated
                if (_editedTitle.value == link.title || _editedTitle.value == link.domain) {
                    _editedTitle.value = updated.title
                }
            }
        }
    }

    fun onSetReminder(reminderAt: Long?) {
        val link = _currentLink.value ?: return
        viewModelScope.launch {
            linkRepository.setReminder(link.id, reminderAt)
            _currentLink.value = link.copy(reminderAt = reminderAt, nudgeDismissed = false)
        }
    }

    // Media library actions
    fun inspectLink(forceRefresh: Boolean = false) {
        if (!forceRefresh && uiState.value.isInspecting) return
        val link = _currentLink.value ?: return
        mediaRepository.startInspection(
            linkId = link.id,
            url = link.originalUrl,
            previewImageUrl = link.previewImageUrl,
            forceRefresh = forceRefresh
        )
    }

    fun retryInspection() {
        inspectLink(forceRefresh = true)
    }

    fun cancelInspection() {
        val link = _currentLink.value ?: return
        viewModelScope.launch {
            mediaRepository.cancelInspection(link.id)
        }
    }

    fun downloadCandidate(candidate: MediaCandidate) {
        val link = _currentLink.value ?: return
        val available = uiState.value.inspectionResult as? InspectionResult.Available
        val platform = available?.platform ?: "Direct"
        val title = available?.title ?: link.title

        viewModelScope.launch {
            try {
                mediaRepository.enqueueDownload(
                    linkId = link.id,
                    candidate = candidate,
                    sourcePlatform = platform,
                    title = title,
                    artist = available?.artist,
                    album = available?.album,
                    artworkUrl = available?.thumbnailUrl,
                    spotifyTrackId = available?.spotifyTrackId
                )
            } catch (t: Throwable) {
                android.util.Log.e("SavedItemDetailViewModel", "Failed to enqueue download: ${t.message}", t)
            }
        }
    }

    fun overrideCellular(taskId: String) {
        viewModelScope.launch {
            mediaRepository.overrideCellularRequirement(taskId)
        }
    }

    fun cancelTask(taskId: String) {
        viewModelScope.launch {
            mediaRepository.cancelDownload(taskId)
        }
    }

    fun dismissTask(taskId: String) {
        viewModelScope.launch {
            mediaRepository.dismissTask(taskId)
        }
    }

    fun deleteAsset(assetId: String) {
        viewModelScope.launch {
            mediaRepository.deleteAsset(assetId)
        }
    }

    fun exportAsset(assetId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val result = mediaRepository.exportAsset(assetId)
            onResult(result.isSuccess)
        }
    }

    fun onAcceptCollectionSuggestion(suggestion: LinkCollectionSuggestion) {
        _dismissedSuggestionLinkIds.update { it + suggestion.link.id }
        if (!_selectedCollectionIds.value.contains(suggestion.collection.id)) {
            onToggleCollection(suggestion.collection.id)
        }
    }

    fun onDismissCollectionSuggestion(suggestion: LinkCollectionSuggestion) {
        _dismissedSuggestionLinkIds.update { it + suggestion.link.id }
        viewModelScope.launch {
            userUnderstandingRepository?.recordSuggestionDismissal(
                type = com.laterbox.app.data.local.entity.SuggestionDismissalType.COLLECTION_SUGGESTION,
                linkId = suggestion.link.id,
                normalizedUrl = suggestion.link.normalizedUrl
            )
        }
    }
}
