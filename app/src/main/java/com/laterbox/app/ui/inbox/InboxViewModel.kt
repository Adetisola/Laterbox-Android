package com.laterbox.app.ui.inbox

import android.content.ClipboardManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laterbox.app.ai.RemoteCapabilities
import com.laterbox.app.capture.ClipboardSuggestion
import com.laterbox.app.capture.ClipboardSuggestionManager
import com.laterbox.app.data.local.entity.UserTasteFacetEntity
import com.laterbox.app.data.preferences.InboxViewMode
import com.laterbox.app.data.preferences.UserPreferences
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.data.repository.CollectionRepository
import com.laterbox.app.data.repository.LinkRepository
import com.laterbox.app.data.repository.NotificationHistoryRepository
import com.laterbox.app.domain.model.Collection
import com.laterbox.app.domain.model.ContentType
import com.laterbox.app.domain.model.MetadataStatus
import com.laterbox.app.domain.model.SavedLink
import com.laterbox.app.intelligence.CollectionSuggester
import com.laterbox.app.intelligence.InboxResurfacer
import com.laterbox.app.intelligence.LinkCollectionSuggestion
import com.laterbox.app.intelligence.ResurfacedLinkSuggestion
import com.laterbox.app.intelligence.SearchTieBreaker
import com.laterbox.app.intelligence.UserUnderstandingRepository
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

private data class InboxIntelligenceInputs(
    val facets: List<UserTasteFacetEntity> = emptyList(),
    val collections: List<Collection> = emptyList(),
    val remoteIntelligenceEnabled: Boolean = true,
    val dismissedResurfacedIds: Set<Long> = emptySet(),
    val dismissedCollectionPairs: Set<Pair<Long, Long>> = emptySet()
)

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModel(
    private val linkRepository: LinkRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val notificationHistoryRepository: NotificationHistoryRepository? = null,
    private val inspectionDao: com.laterbox.app.media.data.db.dao.MediaInspectionDao? = null,
    private val intelligenceSignalLogger: com.laterbox.app.intelligence.IntelligenceSignalLogger? = null,
    private val userUnderstandingRepository: UserUnderstandingRepository? = null,
    private val collectionRepository: CollectionRepository? = null,
    private val remoteConfigManager: RemoteConfigManager? = null
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _activeFilterType = MutableStateFlow<ContentType?>(null)
    val activeFilterType: StateFlow<ContentType?> = _activeFilterType

    private val _activeDateRangeFilter = MutableStateFlow(DateRangeFilter.ALL)
    val activeDateRangeFilter: StateFlow<DateRangeFilter> = _activeDateRangeFilter

    private val _activeQuickFilter = MutableStateFlow(InboxQuickFilter.ALL)
    val activeQuickFilter: StateFlow<InboxQuickFilter> = _activeQuickFilter

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _clipboardSuggestion = MutableStateFlow<ClipboardSuggestion?>(null)
    val clipboardSuggestion: StateFlow<ClipboardSuggestion?> = _clipboardSuggestion

    private val baseLinksFlow = _searchQuery.flatMapLatest { query ->
        if (query.isBlank()) {
            linkRepository.inboxLinks
        } else {
            linkRepository.searchLinks(query)
        }
    }

    private val unreadNotificationsFlow = notificationHistoryRepository?.unreadCount ?: flowOf(0)
    private val mediaReadyFlow = inspectionDao?.getCompletedInspectionLinkIds() ?: flowOf<List<Long>>(emptyList())

    private val _selectedLinkIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedLinkIds: StateFlow<Set<Long>> = _selectedLinkIds

    private val _dismissedResurfacedIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _dismissedCollectionPairs = MutableStateFlow<Set<Pair<Long, Long>>>(emptySet())

    private val facetsFlow = userUnderstandingRepository?.observeAllFacets() ?: flowOf(emptyList())
    private val collectionsFlow = collectionRepository?.collections ?: flowOf(emptyList())
    private val remoteIntelEnabledFlow = remoteConfigManager?.state
        ?.map { it.activeConfig.isFeatureEnabled(RemoteCapabilities.KEY_EXPERIENCE_INTELLIGENCE, true) }
        ?.distinctUntilChanged()
        ?: flowOf(true)

    private val intelligenceInputsFlow = combine(
        facetsFlow,
        collectionsFlow,
        remoteIntelEnabledFlow,
        _dismissedResurfacedIds,
        _dismissedCollectionPairs
    ) { facets, collections, remoteEnabled, dismissedResurfaced, dismissedColPairs ->
        InboxIntelligenceInputs(
            facets = facets,
            collections = collections,
            remoteIntelligenceEnabled = remoteEnabled,
            dismissedResurfacedIds = dismissedResurfaced,
            dismissedCollectionPairs = dismissedColPairs
        )
    }

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<InboxUiState> = combine(
        baseLinksFlow,
        _searchQuery,
        _activeFilterType,
        _activeDateRangeFilter,
        _activeQuickFilter,
        _isRefreshing,
        _clipboardSuggestion,
        userPreferencesRepository.userPreferences,
        unreadNotificationsFlow,
        mediaReadyFlow,
        _selectedLinkIds,
        intelligenceInputsFlow
    ) { args: Array<Any?> ->
        val rawLinks = args[0] as List<SavedLink>
        val query = args[1] as String
        val filterType = args[2] as ContentType?
        val dateFilter = args[3] as DateRangeFilter
        val quickFilter = args[4] as InboxQuickFilter
        val refreshing = args[5] as Boolean
        val suggestion = args[6] as ClipboardSuggestion?
        val prefs = args[7] as UserPreferences
        val unreadNotifs = args[8] as Int
        val mediaReadyIds = (args[9] as? List<Long>)?.toSet() ?: emptySet()
        val selectedIds = args[10] as Set<Long>
        val intel = args[11] as InboxIntelligenceInputs

        // If searching, filter out archived links since searchLinks searches all
        val unarchived = rawLinks.filter { !it.isArchived }

        val dateFiltered = if (dateFilter != DateRangeFilter.ALL) {
            unarchived.filter { dateFilter.matches(it.createdAt) }
        } else {
            unarchived
        }

        val quickFiltered = when (quickFilter) {
            InboxQuickFilter.ALL -> dateFiltered
            InboxQuickFilter.UNREAD -> dateFiltered.filter { !it.isRead }
            InboxQuickFilter.MEDIA_READY -> dateFiltered.filter { it.id in mediaReadyIds }
            InboxQuickFilter.SCHEDULED -> dateFiltered.filter { it.reminderAt != null }
        }

        val filtered = if (filterType != null) {
            quickFiltered.filter { it.contentType == filterType }
        } else {
            quickFiltered
        }

        val personalizationActive = prefs.personalizationEnabled && intel.remoteIntelligenceEnabled
        // Chronological order preserved whenever search is blank; lexical-first SearchTieBreaker only during active search.
        val orderedLinks = if (query.isNotBlank()) {
            SearchTieBreaker.rankLinks(
                links = filtered,
                query = query,
                facets = intel.facets,
                applyTieBreaker = personalizationActive
            )
        } else {
            filtered
        }

        val isIdle = query.isBlank() &&
            filterType == null &&
            dateFilter == DateRangeFilter.ALL &&
            quickFilter == InboxQuickFilter.ALL

        val resurfaced = if (personalizationActive && isIdle) {
            InboxResurfacer.selectSuggestions(
                inboxLinks = unarchived,
                facets = intel.facets,
                dismissedLinkIds = intel.dismissedResurfacedIds
            )
        } else {
            emptyList()
        }

        val colSuggestions = if (personalizationActive && isIdle) {
            CollectionSuggester.suggestForLinks(
                links = unarchived,
                collections = intel.collections,
                dismissedPairs = intel.dismissedCollectionPairs,
                maxSuggestions = 2
            )
        } else {
            emptyList()
        }

        val pendingCount = unarchived.count {
            it.metadataStatus == MetadataStatus.PENDING || it.metadataStatus == MetadataStatus.FETCHING
        }
        val readCount = unarchived.count { it.isRead }

        InboxUiState(
            links = orderedLinks,
            isLoading = false,
            isRefreshing = refreshing,
            searchQuery = query,
            activeFilterType = filterType,
            activeDateRangeFilter = dateFilter,
            activeQuickFilter = quickFilter,
            readInboxCount = readCount,
            pendingMetadataCount = pendingCount,
            viewMode = prefs.inboxViewMode,
            hasCompletedOnboarding = prefs.hasCompletedOnboarding,
            clipboardSuggestion = suggestion,
            unreadNotificationCount = unreadNotifs,
            mediaReadyLinkIds = mediaReadyIds,
            selectedLinkIds = selectedIds,
            resurfacedLinks = resurfaced,
            collectionSuggestions = colSuggestions
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = InboxUiState(isLoading = true)
    )

    fun checkClipboard(clipboardManager: ClipboardManager?) {
        viewModelScope.launch {
            val clipMeta = ClipboardSuggestionManager.extractClipMetadata(clipboardManager) ?: run {
                _clipboardSuggestion.value = null
                return@launch
            }

            val prefs = userPreferencesRepository.userPreferences.first()
            if (!prefs.clipboardSuggestionEnabled) {
                _clipboardSuggestion.value = null
                return@launch
            }

            val candidate = ClipboardSuggestionManager.evaluateCandidate(
                rawText = clipMeta.text,
                isSensitive = clipMeta.isSensitive,
                clipTimestampMs = clipMeta.timestampMs,
                clipboardEnabled = prefs.clipboardSuggestionEnabled,
                recentHistoryEntries = prefs.recentClipboardHistory,
                isAlreadySaved = { false }
            )

            if (candidate != null) {
                val existing = linkRepository.findDuplicate(candidate.url)
                if (existing != null) {
                    userPreferencesRepository.recordHandledClipboardHash(candidate.hash)
                    _clipboardSuggestion.value = null
                } else {
                    _clipboardSuggestion.value = candidate
                }
            } else {
                _clipboardSuggestion.value = null
            }
        }
    }

    fun dismissClipboardSuggestion(suggestion: ClipboardSuggestion): kotlinx.coroutines.Job {
        _clipboardSuggestion.value = null
        return viewModelScope.launch {
            userPreferencesRepository.recordHandledClipboardHash(suggestion.hash)
        }
    }

    fun saveClipboardSuggestion(suggestion: ClipboardSuggestion, onSuccess: () -> Unit = {}): kotlinx.coroutines.Job {
        _clipboardSuggestion.value = null
        return viewModelScope.launch {
            userPreferencesRepository.recordHandledClipboardHash(suggestion.hash)
            linkRepository.saveLink(originalUrl = suggestion.url)
            onSuccess()
        }
    }

    private var searchSignalJob: kotlinx.coroutines.Job? = null

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        searchSignalJob?.cancel()
        val clean = query.trim()
        if (clean.length >= 3 && intelligenceSignalLogger != null) {
            searchSignalJob = viewModelScope.launch {
                kotlinx.coroutines.delay(900L)
                intelligenceSignalLogger.logSearch(
                    surfaceScope = com.laterbox.app.data.local.entity.IntelligenceSurfaceScope.LIBRARY,
                    query = clean
                )
            }
        }
    }

    fun onClearSearch() {
        _searchQuery.value = ""
        _activeDateRangeFilter.value = DateRangeFilter.ALL
        _activeQuickFilter.value = InboxQuickFilter.ALL
    }

    fun onFilterContentType(type: ContentType?) {
        _activeFilterType.value = if (_activeFilterType.value == type) null else type
    }

    fun onFilterDateRange(filter: DateRangeFilter) {
        _activeDateRangeFilter.value = if (_activeDateRangeFilter.value == filter) DateRangeFilter.ALL else filter
    }

    fun onFilterQuickFilter(filter: InboxQuickFilter) {
        _activeQuickFilter.value = if (_activeQuickFilter.value == filter) InboxQuickFilter.ALL else filter
    }

    fun onArchiveAllRead(onArchived: (List<Long>) -> Unit = {}): kotlinx.coroutines.Job =
        viewModelScope.launch {
            val archivedIds = linkRepository.archiveAllReadInboxLinks()
            if (archivedIds.isNotEmpty()) {
                onArchived(archivedIds)
            }
        }

    fun unarchiveBatch(linkIds: kotlin.collections.Collection<Long>): kotlinx.coroutines.Job =
        viewModelScope.launch {
            if (linkIds.isNotEmpty()) {
                linkRepository.setArchivedBatch(linkIds, false)
            }
        }

    fun onToggleRead(linkId: Long, currentIsRead: Boolean): kotlinx.coroutines.Job =
        viewModelScope.launch {
            linkRepository.setRead(linkId, !currentIsRead)
        }

    fun onArchive(linkId: Long): kotlinx.coroutines.Job =
        viewModelScope.launch {
            linkRepository.setArchived(linkId, true)
        }

    fun unarchive(linkId: Long): kotlinx.coroutines.Job =
        viewModelScope.launch {
            linkRepository.setArchived(linkId, false)
        }

    fun onDelete(linkId: Long): kotlinx.coroutines.Job =
        viewModelScope.launch {
            linkRepository.deleteLink(linkId)
        }

    fun onToggleSelectLink(linkId: Long) {
        val current = _selectedLinkIds.value
        _selectedLinkIds.value = if (current.contains(linkId)) {
            current - linkId
        } else {
            current + linkId
        }
    }

    fun onSelectAll(links: List<SavedLink>) {
        _selectedLinkIds.value = links.map { it.id }.toSet()
    }

    fun onClearSelection() {
        _selectedLinkIds.value = emptySet()
    }

    fun onArchiveSelected(): kotlinx.coroutines.Job = viewModelScope.launch {
        val ids = _selectedLinkIds.value
        if (ids.isNotEmpty()) {
            linkRepository.setArchivedBatch(ids, true)
            _selectedLinkIds.value = emptySet()
        }
    }

    fun onToggleReadSelected(links: List<SavedLink>): kotlinx.coroutines.Job = viewModelScope.launch {
        val ids = _selectedLinkIds.value
        if (ids.isNotEmpty()) {
            val selectedLinks = links.filter { ids.contains(it.id) }
            val allRead = selectedLinks.isNotEmpty() && selectedLinks.all { it.isRead }
            linkRepository.setReadBatch(ids, !allRead)
            _selectedLinkIds.value = emptySet()
        }
    }

    fun onDeleteSelected(): kotlinx.coroutines.Job = viewModelScope.launch {
        val ids = _selectedLinkIds.value
        if (ids.isNotEmpty()) {
            linkRepository.deleteLinks(ids)
            _selectedLinkIds.value = emptySet()
        }
    }

    fun onRestore(link: SavedLink) {
        viewModelScope.launch {
            linkRepository.restoreLink(link)
        }
    }

    fun restoreLink(link: SavedLink) = onRestore(link)

    fun onLinkOpened(linkId: Long) {
        viewModelScope.launch {
            val autoMark = userPreferencesRepository.userPreferences.first().autoMarkAsRead
            if (autoMark) {
                linkRepository.setRead(linkId, true)
            }
        }
    }

    fun refresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            val startTime = System.currentTimeMillis()
            try {
                linkRepository.retryPendingAndFailedFetches()
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed < 450L) {
                    kotlinx.coroutines.delay(450L - elapsed)
                }
            } catch (_: Exception) {
                // Ensure pull-to-refresh spinner always finishes gracefully
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun onToggleViewMode() {
        viewModelScope.launch {
            val currentMode = userPreferencesRepository.userPreferences.first().inboxViewMode
            val nextMode = when (currentMode) {
                InboxViewMode.HERO -> InboxViewMode.COMPACT
                InboxViewMode.COMPACT -> InboxViewMode.EDITORIAL
                InboxViewMode.EDITORIAL -> InboxViewMode.HERO
            }
            userPreferencesRepository.setInboxViewMode(nextMode)
        }
    }

    fun onCompleteOnboarding() {
        viewModelScope.launch {
            userPreferencesRepository.setHasCompletedOnboarding(true)
        }
    }

    fun completeOnboardingWithOptions(seedStarterLink: Boolean, clipboardUrl: String? = null) {
        viewModelScope.launch {
            userPreferencesRepository.setHasCompletedOnboarding(true)
            if (seedStarterLink) {
                linkRepository.seedStarterGuideIfMissing()
            }
            if (!clipboardUrl.isNullOrBlank()) {
                runCatching { linkRepository.saveLink(originalUrl = clipboardUrl) }
            }
        }
    }

    fun seedStarterGuide() {
        viewModelScope.launch {
            linkRepository.seedStarterGuideIfMissing()
        }
    }

    fun onResurfacedLinkOpened(suggestion: ResurfacedLinkSuggestion) {
        _dismissedResurfacedIds.update { it + suggestion.link.id }
        onLinkOpened(suggestion.link.id)
        viewModelScope.launch {
            intelligenceSignalLogger?.logResurfaceInteraction(suggestion.link, opened = true)
        }
    }

    fun onDismissResurfacedLink(link: SavedLink) {
        _dismissedResurfacedIds.update { it + link.id }
        viewModelScope.launch {
            intelligenceSignalLogger?.logResurfaceInteraction(link, opened = false)
        }
    }

    fun onAcceptCollectionSuggestion(suggestion: LinkCollectionSuggestion) {
        _dismissedCollectionPairs.update { it + (suggestion.link.id to suggestion.collection.id) }
        viewModelScope.launch {
            linkRepository.addLinkToCollection(suggestion.link.id, suggestion.collection.id)
        }
    }

    fun onDismissCollectionSuggestion(suggestion: LinkCollectionSuggestion) {
        _dismissedCollectionPairs.update { it + (suggestion.link.id to suggestion.collection.id) }
    }
}
