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
    val expiresAtMs: Long? = null
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
    val personalizationEnabled: Boolean = true,
    val assistantPersonalContextEnabled: Boolean = false
)

data class LaterboxBackup(
    val version: Int = CURRENT_BACKUP_VERSION,
    val exportedAt: Long = System.currentTimeMillis(),
    val appName: String = "Laterbox",
    val collections: List<BackupCollection> = emptyList(),
    val links: List<BackupLink> = emptyList(),
    val userTasteFacets: List<BackupTasteFacet> = emptyList(),
    val movieWatchlist: List<BackupMovieWatchlistItem> = emptyList(),
    val personalizationPreferences: BackupPersonalizationPreferences? = null
) {
    companion object {
        const val CURRENT_BACKUP_VERSION = 2
    }
}

data class BackupRestoreResult(
    val restoredLinksCount: Int,
    val skippedDuplicatesCount: Int,
    val restoredCollectionsCount: Int,
    val restoredFacetsCount: Int = 0,
    val restoredWatchlistCount: Int = 0
)
