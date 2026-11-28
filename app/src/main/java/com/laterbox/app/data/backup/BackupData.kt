package com.laterbox.app.data.backup

import com.laterbox.app.domain.model.ContentType

data class BackupCollection(
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class BackupLink(
    val originalUrl: String,
    val normalizedUrl: String,
    val title: String,
    val description: String? = null,
    val siteName: String? = null,
    val domain: String,
    val faviconUrl: String? = null,
    val previewImageUrl: String? = null,
    val contentType: ContentType = ContentType.WEBSITE,
    val personalNote: String? = null,
    val collectionName: String? = null,
    val collectionNames: List<String> = emptyList(),
    val isRead: Boolean = false,
    val isArchived: Boolean = false,
    val reminderAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class BackupTasteFacet(
    val facetKey: String,
    val facetType: String,
    val surfaceScope: String,
    val displayLabel: String,
    val score: Double,
    val confidence: Double,
    val provenance: String,
    val evidenceSummary: String,
    val includeInAssistantContext: Boolean = false,
    val isSuppressedByUser: Boolean = false,
    val firstObservedAtMs: Long,
    val lastReinforcedAtMs: Long,
    val expiresAtMs: Long? = null,
    val hasBehavioralEvidence: Boolean = true,
    val independentEvidenceCount: Int = 1,
    val supportingSignalTypesCsv: String = ""
)

data class BackupIntelligenceSignal(
    val signalType: String,
    val surfaceScope: String,
    val targetKey: String,
    val topicsCsv: String,
    val isMediaCue: Boolean = false,
    val strength: Double = 1.0,
    val createdAtMs: Long
)

data class BackupTitleFeedback(
    val mediaKey: String,
    val compositeId: String,
    val providerId: String,
    val titleId: String,
    val canonicalId: String? = null,
    val kind: String,
    val reaction: String,
    val genresCsv: String = "",
    val updatedAtMs: Long
)

data class BackupSuggestionDismissal(
    val suggestionType: String,
    val linkIdentityHash: String,
    val dismissedAtMs: Long,
    val suppressUntilMs: Long? = null
)

data class BackupMovieWatchlistItem(
    val compositeId: String,
    val providerId: String,
    val titleId: String,
    val kind: String,
    val title: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val releaseYear: Int? = null,
    val rating: Double? = null,
    val genresCsv: String = "",
    val summary: String? = null,
    val sourceBadge: String = "OPEN_ARCHIVE",
    val isPlayableHint: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    val canonicalId: String? = null
)

data class BackupPersonalizationPreferences(
    val assistantPersonalContextEnabled: Boolean = false
)

data class LaterboxBackup(
    val version: Int = CURRENT_BACKUP_VERSION,
    val exportedAt: Long = System.currentTimeMillis(),
    val appName: String = "Laterbox",
    val collections: List<BackupCollection> = emptyList(),
    val links: List<BackupLink> = emptyList(),
    val userTasteFacets: List<BackupTasteFacet> = emptyList(),
    val intelligenceSignals: List<BackupIntelligenceSignal> = emptyList(),
    val titleFeedback: List<BackupTitleFeedback> = emptyList(),
    val suggestionDismissals: List<BackupSuggestionDismissal> = emptyList(),
    val movieWatchlist: List<BackupMovieWatchlistItem> = emptyList(),
    val personalizationPreferences: BackupPersonalizationPreferences? = null
) {
    companion object {
        const val CURRENT_BACKUP_VERSION = 3
    }
}

data class BackupRestoreResult(
    val restoredLinksCount: Int,
    val skippedDuplicatesCount: Int,
    val restoredCollectionsCount: Int,
    val restoredFacetsCount: Int = 0,
    val restoredWatchlistCount: Int = 0,
    val restoredSignalsCount: Int = 0,
    val restoredDismissalsCount: Int = 0,
    val restoredTitleFeedbackCount: Int = 0
)
