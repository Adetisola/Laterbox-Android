package com.laterbox.app.transcript.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.transcript.data.TranscriptExtractionOutcome
import com.laterbox.app.transcript.data.YouTubeTranscriptExtractor
import com.laterbox.app.transcript.domain.ExportFormat
import com.laterbox.app.transcript.domain.MultiFormatExportResult
import com.laterbox.app.transcript.domain.TranscriptDocument
import com.laterbox.app.transcript.domain.TranscriptExtractionProgress
import com.laterbox.app.transcript.domain.TranscriptExtractionStage
import com.laterbox.app.transcript.domain.TranscriptTargetPreference
import com.laterbox.app.transcript.domain.YouTubeUrlParser
import com.laterbox.app.transcript.domain.YouTubeUrlTarget
import com.laterbox.app.transcript.export.ExportDestination
import com.laterbox.app.transcript.export.TranscriptExportManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class YouTubeTranscriptUiState(
    val isSheetVisible: Boolean = false,
    val sourceUrl: String = "",
    val manualUrlInput: String = "",
    val allowManualUrlEntry: Boolean = false,
    val parsedTarget: YouTubeUrlTarget? = null,
    val selectedPreference: TranscriptTargetPreference = TranscriptTargetPreference.VIDEO,
    val needsTargetChoice: Boolean = false,
    val isExtracting: Boolean = false,
    val progress: TranscriptExtractionProgress = TranscriptExtractionProgress(),
    val document: TranscriptDocument? = null,
    val errorMessage: String? = null,
    val wasCancelled: Boolean = false,
    val selectedFormats: Set<ExportFormat> = setOf(ExportFormat.MARKDOWN),
    val isExporting: Boolean = false,
    val exportResult: MultiFormatExportResult? = null,
    val exportStatusNotice: String? = null,
    val savedExportFolderUri: String? = null,
    val savedExportFolderName: String? = null
) {
    val canExport: Boolean
        get() = document?.hasAnySuccessfulTranscript == true && selectedFormats.isNotEmpty() && !isExporting && !isExtracting
}

class YouTubeTranscriptViewModel(
    private val extractor: YouTubeTranscriptExtractor,
    private val exportManager: TranscriptExportManager,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(YouTubeTranscriptUiState())
    val uiState: StateFlow<YouTubeTranscriptUiState> = _uiState.asStateFlow()

    private var extractionJob: Job? = null

    init {
        viewModelScope.launch {
            userPreferencesRepository.userPreferencesFlow.collectLatest { prefs ->
                _uiState.update { state ->
                    state.copy(
                        savedExportFolderUri = prefs.customExportDirectoryUri,
                        savedExportFolderName = prefs.customExportDirectoryName
                    )
                }
            }
        }
    }

    /**
     * Read-only Assistant entry point: opens the transcript sheet (optionally prefilled with a URL)
     * without automatically firing extraction or mutating user library files without user interaction.
     */
    fun openFromAssistant(url: String? = null) {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isNotBlank() && _uiState.value.sourceUrl == trimmed &&
            (_uiState.value.isExtracting || _uiState.value.document != null)
        ) {
            _uiState.update { it.copy(isSheetVisible = true) }
            return
        }

        extractionJob?.cancel()
        val parsed = if (trimmed.isNotBlank()) YouTubeUrlParser.parse(trimmed) else null
        val defaultPref = when (parsed) {
            is YouTubeUrlTarget.Playlist -> TranscriptTargetPreference.PLAYLIST
            is YouTubeUrlTarget.VideoInPlaylist -> if (parsed.isAutoMix) TranscriptTargetPreference.VIDEO else TranscriptTargetPreference.PLAYLIST
            else -> TranscriptTargetPreference.VIDEO
        }

        _uiState.update { state ->
            YouTubeTranscriptUiState(
                isSheetVisible = true,
                sourceUrl = trimmed,
                manualUrlInput = trimmed,
                allowManualUrlEntry = true,
                parsedTarget = parsed,
                selectedPreference = defaultPref,
                needsTargetChoice = parsed is YouTubeUrlTarget.VideoInPlaylist && !parsed.isAutoMix,
                savedExportFolderUri = state.savedExportFolderUri,
                savedExportFolderName = state.savedExportFolderName
            )
        }
    }

    fun onManualUrlChange(newUrl: String) {
        _uiState.update {
            it.copy(
                manualUrlInput = newUrl,
                errorMessage = null
            )
        }
    }

    fun submitManualUrlExtraction() {
        val raw = _uiState.value.manualUrlInput.trim()
        if (raw.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Enter a YouTube video or playlist URL.") }
            return
        }
        openForUrl(raw)
    }

    fun openForUrl(url: String) {
        val current = _uiState.value
        if (current.sourceUrl == url && (current.isExtracting || current.document != null)) {
            _uiState.update { it.copy(isSheetVisible = true) }
            return
        }

        extractionJob?.cancel()
        val parsed = YouTubeUrlParser.parse(url)
        val isAmbiguousPlaylist = parsed is YouTubeUrlTarget.VideoInPlaylist && !parsed.isAutoMix

        if (parsed is YouTubeUrlTarget.Invalid) {
            _uiState.update {
                it.copy(
                    isSheetVisible = true,
                    sourceUrl = url,
                    manualUrlInput = url,
                    parsedTarget = parsed,
                    needsTargetChoice = false,
                    isExtracting = false,
                    document = null,
                    errorMessage = parsed.reason,
                    wasCancelled = false,
                    exportResult = null,
                    exportStatusNotice = null
                )
            }
            return
        }

        val defaultPreference = when (parsed) {
            is YouTubeUrlTarget.Playlist -> TranscriptTargetPreference.PLAYLIST
            is YouTubeUrlTarget.VideoInPlaylist -> if (parsed.isAutoMix) TranscriptTargetPreference.VIDEO else TranscriptTargetPreference.PLAYLIST
            else -> TranscriptTargetPreference.VIDEO
        }

        _uiState.update {
            it.copy(
                isSheetVisible = true,
                sourceUrl = url,
                manualUrlInput = url,
                parsedTarget = parsed,
                selectedPreference = defaultPreference,
                needsTargetChoice = isAmbiguousPlaylist,
                isExtracting = false,
                progress = TranscriptExtractionProgress(),
                document = null,
                errorMessage = null,
                wasCancelled = false,
                exportResult = null,
                exportStatusNotice = null
            )
        }

        if (!isAmbiguousPlaylist) {
            startExtraction(url, defaultPreference)
        }
    }

    fun chooseTargetAndExtract(preference: TranscriptTargetPreference) {
        val url = _uiState.value.sourceUrl.ifBlank { _uiState.value.manualUrlInput.trim() }
        if (url.isBlank()) return
        _uiState.update {
            it.copy(
                sourceUrl = url,
                selectedPreference = preference,
                needsTargetChoice = false,
                errorMessage = null
            )
        }
        startExtraction(url, preference)
    }

    fun retryExtraction() {
        val state = _uiState.value
        val url = state.sourceUrl.ifBlank { state.manualUrlInput.trim() }
        if (url.isBlank()) return
        startExtraction(url, state.selectedPreference)
    }

    private fun startExtraction(url: String, preference: TranscriptTargetPreference) {
        extractionJob?.cancel()
        extractionJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isExtracting = true,
                    wasCancelled = false,
                    errorMessage = null,
                    document = null,
                    exportResult = null,
                    exportStatusNotice = null,
                    progress = TranscriptExtractionProgress(
                        stage = TranscriptExtractionStage.RESOLVING_TARGET,
                        message = "Preparing transcript extraction..."
                    )
                )
            }

            val outcome = extractor.extract(
                rawUrl = url,
                preference = preference,
                onProgress = { prog ->
                    _uiState.update { it.copy(progress = prog) }
                }
            )

            when (outcome) {
                is TranscriptExtractionOutcome.Completed -> {
                    _uiState.update {
                        it.copy(
                            isExtracting = false,
                            document = outcome.document,
                            errorMessage = null,
                            wasCancelled = false
                        )
                    }
                }
                is TranscriptExtractionOutcome.Cancelled -> {
                    _uiState.update {
                        it.copy(
                            isExtracting = false,
                            document = outcome.partialDocument,
                            wasCancelled = true,
                            errorMessage = if (outcome.partialDocument == null) {
                                "Transcript extraction was cancelled."
                            } else null
                        )
                    }
                }
                is TranscriptExtractionOutcome.Failed -> {
                    _uiState.update {
                        it.copy(
                            isExtracting = false,
                            errorMessage = outcome.message,
                            wasCancelled = false
                        )
                    }
                }
            }
        }
    }

    fun cancelExtraction() {
        extractionJob?.cancel()
    }

    fun toggleFormat(format: ExportFormat) {
        _uiState.update { state ->
            val updated = state.selectedFormats.toMutableSet()
            if (updated.contains(format)) {
                updated.remove(format)
            } else {
                updated.add(format)
            }
            state.copy(
                selectedFormats = updated,
                exportStatusNotice = null
            )
        }
    }

    fun setSelectAllFormats(selectAll: Boolean) {
        _uiState.update { state ->
            state.copy(
                selectedFormats = if (selectAll) ExportFormat.entries.toSet() else emptySet(),
                exportStatusNotice = null
            )
        }
    }

    fun hasWritableConfiguredSafFolder(context: Context): Boolean {
        return exportManager.isTreeUriWritable(context, _uiState.value.savedExportFolderUri)
    }

    fun saveToLaterboxLibrary() {
        runExport(ExportDestination.LaterboxLibrary)
    }

    fun exportToSafTree(
        treeUri: Uri,
        displayName: String?,
        persistAsDefault: Boolean = true
    ) {
        val state = _uiState.value
        val doc = state.document ?: return
        if (state.selectedFormats.isEmpty() || state.isExporting) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isExporting = true,
                    exportStatusNotice = null
                )
            }

            if (persistAsDefault) {
                try {
                    userPreferencesRepository.setCustomExportDirectory(
                        uriString = treeUri.toString(),
                        displayName = displayName ?: "Selected Folder"
                    )
                } catch (_: Throwable) {}
            }

            val result = exportManager.exportSelectedFormats(
                document = doc,
                formats = state.selectedFormats,
                destination = ExportDestination.SafTree(treeUri, displayName)
            )

            _uiState.update {
                it.copy(
                    isExporting = false,
                    exportResult = result,
                    exportStatusNotice = null
                )
            }
        }
    }

    fun exportToDownloadsFolder() {
        runExport(ExportDestination.DownloadsFolder)
    }

    private fun runExport(destination: ExportDestination) {
        val state = _uiState.value
        val doc = state.document ?: return
        if (state.selectedFormats.isEmpty() || state.isExporting) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isExporting = true,
                    exportStatusNotice = null
                )
            }

            val result = exportManager.exportSelectedFormats(
                document = doc,
                formats = state.selectedFormats,
                destination = destination
            )

            _uiState.update {
                it.copy(
                    isExporting = false,
                    exportResult = result,
                    exportStatusNotice = null
                )
            }
        }
    }

    fun notifyDestinationPickerCancelled() {
        _uiState.update {
            it.copy(
                isExporting = false,
                exportStatusNotice = "Folder selection was cancelled. Your extracted transcript is still ready to export."
            )
        }
    }

    fun dismissSheet() {
        _uiState.update { it.copy(isSheetVisible = false) }
    }

    fun resetState() {
        extractionJob?.cancel()
        _uiState.update { state ->
            YouTubeTranscriptUiState(
                savedExportFolderUri = state.savedExportFolderUri,
                savedExportFolderName = state.savedExportFolderName
            )
        }
    }
}
