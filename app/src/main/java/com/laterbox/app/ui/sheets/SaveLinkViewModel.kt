package com.laterbox.app.ui.sheets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laterbox.app.data.metadata.ExtractedMetadata
import com.laterbox.app.data.metadata.JsoupMetadataExtractor
import com.laterbox.app.data.repository.CollectionRepository
import com.laterbox.app.data.repository.LinkRepository
import com.laterbox.app.domain.model.Collection
import com.laterbox.app.domain.model.SavedLink
import com.laterbox.app.domain.util.UrlValidator
import com.laterbox.app.media.data.inbox.InboxLinkRouter
import com.laterbox.app.media.data.inbox.LaterboxInboxResolverClient
import com.laterbox.app.media.domain.model.InspectionResult
import com.laterbox.app.media.domain.model.MediaCandidate
import com.laterbox.app.media.domain.model.MediaType
import com.laterbox.app.media.domain.repository.MediaRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class MetadataPreviewStatus {
    IDLE,
    LOADING,
    READY,
    FAILED
}

sealed interface InboxPasteResolutionState {
    data object Idle : InboxPasteResolutionState
    data class Loading(val platformLabel: String) : InboxPasteResolutionState
    data class Resolved(
        val platformLabel: String,
        val thumbnailUrl: String?,
        val mediaSummary: String,
        val candidates: List<MediaCandidate>
    ) : InboxPasteResolutionState
    data class OpenInBrowserFallback(
        val platformLabel: String,
        val rawUrl: String
    ) : InboxPasteResolutionState
}

data class SaveLinkUiState(
    val url: String = "",
    val urlError: String? = null,
    val title: String = "",
    val personalNote: String = "",
    val selectedCollectionIds: Set<Long> = emptySet(),
    val collections: List<Collection> = emptyList(),
    val duplicateLink: SavedLink? = null,
    val isSaving: Boolean = false,
    val isSavedSuccess: Boolean = false,
    val reminderAt: Long? = null,
    val metadataStatus: MetadataPreviewStatus = MetadataPreviewStatus.IDLE,
    val previewImageUrl: String? = null,
    val inboxResolution: InboxPasteResolutionState = InboxPasteResolutionState.Idle,
    val isDownloadingMedia: Boolean = false,
    val maxNoteLength: Int = 500,
    val maxTitleLength: Int = 200
) {
    val selectedCollectionId: Long?
        get() = selectedCollectionIds.firstOrNull()

    val canSave: Boolean
        get() = !isSaving && !isSavedSuccess
}

class SaveLinkViewModel(
    private val linkRepository: LinkRepository,
    private val collectionRepository: CollectionRepository,
    private val metadataExtractor: (suspend (String) -> Result<ExtractedMetadata>)? = { url ->
        JsoupMetadataExtractor.extract(url)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val inboxResolverClient: LaterboxInboxResolverClient? = null,
    private val mediaRepository: MediaRepository? = null
) : ViewModel() {

    private val _url = MutableStateFlow("")
    private val _urlError = MutableStateFlow<String?>(null)
    private val _title = MutableStateFlow("")
    private val _personalNote = MutableStateFlow("")
    private val _selectedCollectionIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _duplicateLink = MutableStateFlow<SavedLink?>(null)
    private val _isSaving = MutableStateFlow(false)
    private val _isSavedSuccess = MutableStateFlow(false)
    private val _reminderAt = MutableStateFlow<Long?>(null)
    private val _metadataStatus = MutableStateFlow(MetadataPreviewStatus.IDLE)
    private val _previewImageUrl = MutableStateFlow<String?>(null)
    private val _inboxResolution = MutableStateFlow<InboxPasteResolutionState>(InboxPasteResolutionState.Idle)
    private val _isDownloadingMedia = MutableStateFlow(false)

    private var duplicateCheckJob: Job? = null
    private var metadataFetchJob: Job? = null
    private var inboxResolveJob: Job? = null

    val uiState: StateFlow<SaveLinkUiState> = combine(
        combine(
            _url,
            _urlError,
            _title,
            _personalNote,
            _selectedCollectionIds
        ) { p1, p2, p3, p4, p5 ->
            Tuple5(p1, p2, p3, p4, p5)
        },
        combine(
            _duplicateLink,
            _isSaving,
            _isSavedSuccess,
            _reminderAt,
            _metadataStatus
        ) { p6, p7, p8, p9, p10 ->
            Tuple5(p6, p7, p8, p9, p10)
        },
        combine(
            _previewImageUrl,
            collectionRepository.collections,
            _inboxResolution,
            _isDownloadingMedia
        ) { p11, p12, p13, p14 ->
            Tuple4(p11, p12, p13, p14)
        }
    ) { group1, group2, group3 ->
        SaveLinkUiState(
            url = group1.a,
            urlError = group1.b,
            title = group1.c,
            personalNote = group1.d,
            selectedCollectionIds = group1.e,
            duplicateLink = group2.a,
            isSaving = group2.b,
            isSavedSuccess = group2.c,
            reminderAt = group2.d,
            metadataStatus = group2.e,
            previewImageUrl = group3.a,
            collections = group3.b,
            inboxResolution = group3.c,
            isDownloadingMedia = group3.d
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SaveLinkUiState()
    )

    fun prefill(url: String, title: String? = null, note: String? = null) {
        val normalized = UrlValidator.normalize(url)
        _url.value = normalized
        _urlError.value = null
        if (!title.isNullOrBlank()) {
            _title.value = title.take(200)
        }
        if (!note.isNullOrBlank()) {
            _personalNote.value = note.take(500)
        }
        checkDuplicate(normalized)
        val platform = InboxLinkRouter.routeForUrl(normalized)
        if (platform != null) {
            fetchInboxResolutionPreview(normalized)
        } else if (title.isNullOrBlank()) {
            fetchMetadataPreview(normalized)
        }
    }

    fun onUrlChange(newUrl: String) {
        _url.value = newUrl
        if (_urlError.value != null) {
            _urlError.value = null
        }
        checkDuplicate(newUrl)
        val normalized = UrlValidator.normalize(newUrl)
        val platform = if (UrlValidator.isValidUrl(normalized)) InboxLinkRouter.routeForUrl(normalized) else null
        if (platform != null) {
            metadataFetchJob?.cancel()
            fetchInboxResolutionPreview(normalized)
        } else {
            inboxResolveJob?.cancel()
            _inboxResolution.value = InboxPasteResolutionState.Idle
            if (_title.value.isBlank() && UrlValidator.isValidUrl(newUrl)) {
                fetchMetadataPreview(newUrl)
            }
        }
    }

    fun onTitleChange(newTitle: String) {
        _title.value = newTitle.take(200)
    }

    fun onPersonalNoteChange(newNote: String) {
        _personalNote.value = newNote.take(500)
    }

    fun onSelectCollection(collectionId: Long?) {
        if (collectionId == null) {
            _selectedCollectionIds.value = emptySet()
        } else {
            onToggleCollection(collectionId)
        }
    }

    fun onToggleCollection(collectionId: Long) {
        val current = _selectedCollectionIds.value.toMutableSet()
        if (current.contains(collectionId)) {
            current.remove(collectionId)
        } else {
            current.add(collectionId)
        }
        _selectedCollectionIds.value = current
    }

    fun onCreateCollection(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val newId = collectionRepository.createCollection(trimmed)
            _selectedCollectionIds.value = _selectedCollectionIds.value + newId
        }
    }

    fun onReminderAtChange(reminderAt: Long?) {
        _reminderAt.value = reminderAt
    }

    private fun checkDuplicate(rawUrl: String) {
        duplicateCheckJob?.cancel()
        val trimmed = rawUrl.trim()
        if (trimmed.length < 5) {
            _duplicateLink.value = null
            return
        }

        duplicateCheckJob = viewModelScope.launch {
            delay(250) // Debounce
            val formatted = UrlValidator.normalize(trimmed)
            val existing = linkRepository.findDuplicate(formatted)
            _duplicateLink.value = existing
            if (existing != null) {
                if (_title.value.isBlank()) {
                    _title.value = existing.title
                }
                if (_personalNote.value.isBlank() && !existing.personalNote.isNullOrBlank()) {
                    _personalNote.value = existing.personalNote
                }
            }
        }
    }

    private fun fetchInboxResolutionPreview(normalizedUrl: String) {
        val client = inboxResolverClient ?: return
        val platform = InboxLinkRouter.routeForUrl(normalizedUrl) ?: return
        inboxResolveJob?.cancel()
        inboxResolveJob = viewModelScope.launch {
            _inboxResolution.value = InboxPasteResolutionState.Loading(platform.displayName)
            _metadataStatus.value = MetadataPreviewStatus.LOADING
            val result = withContext(ioDispatcher) {
                client.resolveToInspectionResult(normalizedUrl)
            }
            when (result) {
                is InspectionResult.Available -> {
                    if (result.candidates.isNotEmpty()) {
                        val thumb = result.thumbnailUrl
                        if (!thumb.isNullOrBlank()) {
                            _previewImageUrl.value = thumb
                        }
                        val isCarousel = result.candidates.size > 1 &&
                            result.candidates.first().candidateId.startsWith("inbox_carousel_")
                        val summary = if (isCarousel) {
                            "${platform.displayName} · Carousel (${result.candidates.size} items)"
                        } else {
                            "${platform.displayName} · ${result.candidates.first().formatLabel}"
                        }
                        _inboxResolution.value = InboxPasteResolutionState.Resolved(
                            platformLabel = platform.displayName,
                            thumbnailUrl = thumb,
                            mediaSummary = summary,
                            candidates = result.candidates
                        )
                        _metadataStatus.value = MetadataPreviewStatus.READY
                    } else {
                        _inboxResolution.value = InboxPasteResolutionState.OpenInBrowserFallback(
                            platformLabel = platform.displayName,
                            rawUrl = normalizedUrl
                        )
                        _metadataStatus.value = MetadataPreviewStatus.FAILED
                    }
                }
                else -> {
                    _inboxResolution.value = InboxPasteResolutionState.OpenInBrowserFallback(
                        platformLabel = platform.displayName,
                        rawUrl = normalizedUrl
                    )
                    _metadataStatus.value = MetadataPreviewStatus.FAILED
                }
            }
        }
    }

    fun downloadResolvedMedia(onCompleted: (enqueuedCount: Int) -> Unit = {}) {
        val resolved = _inboxResolution.value as? InboxPasteResolutionState.Resolved ?: return
        if (_isDownloadingMedia.value || _isSaving.value) return
        val repo = mediaRepository ?: return
        val rawUrl = _url.value.trim()
        if (rawUrl.isBlank() || !UrlValidator.isValidUrl(rawUrl)) return
        val finalUrl = UrlValidator.normalize(rawUrl)

        viewModelScope.launch {
            _isDownloadingMedia.value = true
            try {
                val linkId = saveOrUpdateInternal(finalUrl).first
                val isCarousel = resolved.candidates.firstOrNull()?.candidateId?.startsWith("inbox_carousel_") == true
                val toEnqueue = if (isCarousel) resolved.candidates else listOf(resolved.candidates.first())
                val title = _title.value.trim().ifBlank { "${resolved.platformLabel} media" }
                var count = 0
                for (candidate in toEnqueue) {
                    val itemTitle = if (toEnqueue.size > 1) "$title (${count + 1}/${toEnqueue.size})" else title
                    if (linkId > 0L) {
                        repo.enqueueDownload(
                            linkId = linkId,
                            candidate = candidate,
                            sourcePlatform = resolved.platformLabel,
                            title = itemTitle,
                            artworkUrl = resolved.thumbnailUrl
                        )
                    } else {
                        repo.enqueueStandaloneDownload(
                            candidate = candidate,
                            sourcePlatform = resolved.platformLabel,
                            title = itemTitle,
                            artworkUrl = resolved.thumbnailUrl
                        )
                    }
                    count++
                }
                _isSavedSuccess.value = true
                onCompleted(count)
            } finally {
                _isDownloadingMedia.value = false
            }
        }
    }

    private fun fetchMetadataPreview(rawUrl: String) {
        val extractor = metadataExtractor ?: return
        metadataFetchJob?.cancel()
        val normalized = UrlValidator.normalize(rawUrl)
        if (!UrlValidator.isValidUrl(normalized)) return

        metadataFetchJob = viewModelScope.launch {
            _metadataStatus.value = MetadataPreviewStatus.LOADING
            try {
                val result = withTimeoutOrNull(4000) {
                    withContext(ioDispatcher) {
                        extractor(normalized)
                    }
                }
                val meta = result?.getOrNull()
                if (meta != null && !meta.title.isNullOrBlank()) {
                    if (_title.value.isBlank()) {
                        _title.value = meta.title.take(200)
                    }
                    _previewImageUrl.value = meta.previewImageUrl
                    _metadataStatus.value = MetadataPreviewStatus.READY
                } else {
                    _metadataStatus.value = MetadataPreviewStatus.FAILED
                }
            } catch (_: Exception) {
                _metadataStatus.value = MetadataPreviewStatus.FAILED
            }
        }
    }

    fun onSave(onSuccess: (isUpdate: Boolean) -> Unit = {}) {
        if (_isSaving.value || _isSavedSuccess.value) return

        val rawUrl = _url.value.trim()
        if (rawUrl.isBlank()) {
            _urlError.value = "Please enter a website address"
            return
        }

        if (!UrlValidator.isValidUrl(rawUrl)) {
            _urlError.value = "Please enter a valid website address"
            return
        }

        _urlError.value = null
        val finalUrl = UrlValidator.normalize(rawUrl)

        viewModelScope.launch {
            _isSaving.value = true
            try {
                val (_, isUpdate) = saveOrUpdateInternal(finalUrl)
                _isSavedSuccess.value = true
                onSuccess(isUpdate)
            } finally {
                _isSaving.value = false
            }
        }
    }

    private suspend fun saveOrUpdateInternal(finalUrl: String): Pair<Long, Boolean> {
        val existing = _duplicateLink.value
        val isUpdate = existing != null
        val chosenColIds = _selectedCollectionIds.value.toList()
        val chosenReminder = _reminderAt.value
        val linkId: Long
        if (existing != null) {
            val mergedColIds = (existing.collectionIds + chosenColIds).distinct()
            linkRepository.updateLink(
                existing.copy(
                    title = if (_title.value.isNotBlank()) _title.value.trim() else existing.title,
                    personalNote = if (_personalNote.value.isNotBlank()) _personalNote.value.trim() else existing.personalNote,
                    collectionIds = mergedColIds,
                    collectionId = mergedColIds.firstOrNull(),
                    reminderAt = chosenReminder ?: existing.reminderAt,
                    isArchived = false // Restores to inbox if it was archived
                )
            )
            linkRepository.setLinkCollections(existing.id, mergedColIds)
            if (chosenReminder != null) {
                linkRepository.setReminder(existing.id, chosenReminder)
            }
            linkId = existing.id
        } else {
            linkId = linkRepository.saveLink(
                originalUrl = finalUrl,
                title = _title.value.ifBlank { null },
                personalNote = _personalNote.value.ifBlank { null },
                collectionIds = chosenColIds,
                reminderAt = chosenReminder
            )
        }
        return linkId to isUpdate
    }

    fun reset() {
        inboxResolveJob?.cancel()
        metadataFetchJob?.cancel()
        duplicateCheckJob?.cancel()
        _url.value = ""
        _urlError.value = null
        _title.value = ""
        _personalNote.value = ""
        _selectedCollectionIds.value = emptySet()
        _duplicateLink.value = null
        _reminderAt.value = null
        _isSaving.value = false
        _isSavedSuccess.value = false
        _metadataStatus.value = MetadataPreviewStatus.IDLE
        _previewImageUrl.value = null
        _inboxResolution.value = InboxPasteResolutionState.Idle
        _isDownloadingMedia.value = false
    }

    private data class Tuple4<A, B, C, D>(
        val a: A,
        val b: B,
        val c: C,
        val d: D
    )

    private data class Tuple5<A, B, C, D, E>(
        val a: A,
        val b: B,
        val c: C,
        val d: D,
        val e: E
    )
}
