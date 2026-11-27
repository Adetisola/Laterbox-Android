package com.laterbox.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TasteFacetType {
    EXPLICIT_LIKE,
    EXPLICIT_DISLIKE,
    ENDURING_INTEREST,
    ACTIVE_GOAL,
    SHORT_TERM_INTENT
}

enum class IntelligenceSurfaceScope {
    ALL,
    MOVIES,
    LIBRARY
}

@Entity(
    tableName = "user_taste_facets",
    indices = [
        Index(value = ["surface_scope"]),
        Index(value = ["facet_type"]),
        Index(value = ["last_reinforced_at_ms"]),
        Index(value = ["expires_at_ms"]),
        Index(value = ["is_suppressed_by_user"])
    ]
)
data class UserTasteFacetEntity(
    @PrimaryKey
    @ColumnInfo(name = "facet_key")
    val facetKey: String,
    @ColumnInfo(name = "facet_type")
    val facetType: String,
    @ColumnInfo(name = "surface_scope")
    val surfaceScope: String,
    @ColumnInfo(name = "display_label")
    val displayLabel: String,
    @ColumnInfo(name = "score")
    val score: Double,
    @ColumnInfo(name = "confidence")
    val confidence: Double,
    @ColumnInfo(name = "provenance")
    val provenance: String,
    @ColumnInfo(name = "evidence_summary")
    val evidenceSummary: String,
    @ColumnInfo(name = "include_in_assistant_context", defaultValue = "0")
    val includeInAssistantContext: Boolean = false,
    @ColumnInfo(name = "is_suppressed_by_user", defaultValue = "0")
    val isSuppressedByUser: Boolean = false,
    @ColumnInfo(name = "first_observed_at_ms")
    val firstObservedAtMs: Long,
    @ColumnInfo(name = "last_reinforced_at_ms")
    val lastReinforcedAtMs: Long,
    @ColumnInfo(name = "expires_at_ms")
    val expiresAtMs: Long? = null
)

@Entity(
    tableName = "intelligence_signals",
    indices = [
        Index(value = ["created_at_ms"]),
        Index(value = ["signal_type"]),
        Index(value = ["surface_scope"]),
        Index(value = ["is_media_cue"])
    ]
)
data class IntelligenceSignalEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "signal_type")
    val signalType: String,
    @ColumnInfo(name = "surface_scope")
    val surfaceScope: String,
    @ColumnInfo(name = "target_key")
    val targetKey: String,
    @ColumnInfo(name = "topics_csv")
    val topicsCsv: String,
    @ColumnInfo(name = "is_media_cue", defaultValue = "0")
    val isMediaCue: Boolean = false,
    @ColumnInfo(name = "strength", defaultValue = "1.0")
    val strength: Double = 1.0,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long
)
