package com.laterbox.app.movies.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "movie_cache_entries",
    indices = [
        Index(value = ["provider_id"]),
        Index(value = ["expires_at"])
    ]
)
data class MovieCacheEntryEntity(
    @PrimaryKey
    @ColumnInfo(name = "cache_key")
    val cacheKey: String,
    @ColumnInfo(name = "provider_id")
    val providerId: String,
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long
)

@Entity(
    tableName = "movie_watchlist_items",
    indices = [
        Index(value = ["added_at"]),
        Index(value = ["kind"]),
        Index(value = ["canonical_id"])
    ]
)
data class MovieWatchlistEntity(
    @PrimaryKey
    @ColumnInfo(name = "composite_id")
    val compositeId: String,
    @ColumnInfo(name = "provider_id")
    val providerId: String,
    @ColumnInfo(name = "title_id")
    val titleId: String,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "poster_url")
    val posterUrl: String?,
    @ColumnInfo(name = "backdrop_url")
    val backdropUrl: String?,
    @ColumnInfo(name = "release_year")
    val releaseYear: Int?,
    @ColumnInfo(name = "rating")
    val rating: Double?,
    @ColumnInfo(name = "genres_csv")
    val genresCsv: String,
    @ColumnInfo(name = "summary")
    val summary: String?,
    @ColumnInfo(name = "source_badge")
    val sourceBadge: String,
    @ColumnInfo(name = "is_playable_hint")
    val isPlayableHint: Boolean,
    @ColumnInfo(name = "added_at")
    val addedAt: Long,
    @ColumnInfo(name = "canonical_id")
    val canonicalId: String? = null
)

@Entity(
    tableName = "movie_watch_history",
    indices = [
        Index(value = ["composite_id"]),
        Index(value = ["last_watched_at"]),
        Index(value = ["canonical_id"]),
        Index(value = ["is_trailer"]),
        Index(value = ["is_completed"]),
        Index(value = ["is_hidden_title"]),
        Index(value = ["hidden_from_continue_watching"]),
        Index(value = ["is_trailer", "hidden_from_continue_watching", "is_hidden_title", "last_watched_at"]),
        Index(value = ["canonical_id", "is_trailer", "last_watched_at"])
    ]
)
data class MovieWatchHistoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "history_key")
    val historyKey: String,
    @ColumnInfo(name = "composite_id")
    val compositeId: String,
    @ColumnInfo(name = "provider_id")
    val providerId: String,
    @ColumnInfo(name = "title_id")
    val titleId: String,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "episode_label")
    val episodeLabel: String?,
    @ColumnInfo(name = "season_number")
    val seasonNumber: Int?,
    @ColumnInfo(name = "episode_number")
    val episodeNumber: Int?,
    @ColumnInfo(name = "poster_url")
    val posterUrl: String?,
    @ColumnInfo(name = "backdrop_url")
    val backdropUrl: String? = null,
    @ColumnInfo(name = "position_ms")
    val positionMs: Long,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "last_watched_at")
    val lastWatchedAt: Long,
    @ColumnInfo(name = "canonical_id")
    val canonicalId: String? = null,
    @ColumnInfo(name = "is_trailer", defaultValue = "0")
    val isTrailer: Boolean = false,
    @ColumnInfo(name = "is_completed", defaultValue = "0")
    val isCompleted: Boolean = false,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null,
    @ColumnInfo(name = "rewatch_count", defaultValue = "0")
    val rewatchCount: Int = 0,
    @ColumnInfo(name = "hidden_from_continue_watching", defaultValue = "0")
    val hiddenFromContinueWatching: Boolean = false,
    @ColumnInfo(name = "is_hidden_title", defaultValue = "0")
    val isHiddenTitle: Boolean = false
)

@Entity(
    tableName = "movie_search_history",
    indices = [
        Index(value = ["searched_at"])
    ]
)
data class MovieSearchHistoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "query")
    val query: String,
    @ColumnInfo(name = "searched_at")
    val searchedAt: Long
)

@Entity(
    tableName = "canonical_media",
    indices = [
        Index(value = ["norm_title"]),
        Index(value = ["kind", "norm_title"]),
        Index(value = ["imdb_id"]),
        Index(value = ["tvmaze_id"])
    ]
)
data class CanonicalMediaEntity(
    @PrimaryKey
    @ColumnInfo(name = "canonical_id")
    val canonicalId: String,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "norm_title")
    val normTitle: String,
    @ColumnInfo(name = "release_year")
    val releaseYear: Int?,
    @ColumnInfo(name = "imdb_id")
    val imdbId: String? = null,
    @ColumnInfo(name = "tvmaze_id")
    val tvmazeId: String? = null,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)

@Entity(
    tableName = "media_provider_links",
    primaryKeys = ["provider_id", "provider_title_id"],
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = CanonicalMediaEntity::class,
            parentColumns = ["canonical_id"],
            childColumns = ["canonical_id"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["canonical_id"])
    ]
)
data class MediaProviderLinkEntity(
    @ColumnInfo(name = "canonical_id")
    val canonicalId: String,
    @ColumnInfo(name = "provider_id")
    val providerId: String,
    @ColumnInfo(name = "provider_title_id")
    val providerTitleId: String,
    @ColumnInfo(name = "confidence")
    val confidence: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)

@Entity(
    tableName = "movie_recently_viewed",
    indices = [
        Index(value = ["viewed_at"]),
        Index(value = ["canonical_id"]),
        Index(value = ["composite_id"]),
        Index(value = ["provider_id", "title_id"])
    ]
)
data class MovieRecentlyViewedEntity(
    @PrimaryKey
    @ColumnInfo(name = "media_key")
    val mediaKey: String,
    @ColumnInfo(name = "composite_id")
    val compositeId: String,
    @ColumnInfo(name = "provider_id")
    val providerId: String,
    @ColumnInfo(name = "title_id")
    val titleId: String,
    @ColumnInfo(name = "canonical_id")
    val canonicalId: String? = null,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "poster_url")
    val posterUrl: String? = null,
    @ColumnInfo(name = "backdrop_url")
    val backdropUrl: String? = null,
    @ColumnInfo(name = "release_year")
    val releaseYear: Int? = null,
    @ColumnInfo(name = "rating")
    val rating: Double? = null,
    @ColumnInfo(name = "genres_csv")
    val genresCsv: String = "",
    @ColumnInfo(name = "source_badge")
    val sourceBadge: String,
    @ColumnInfo(name = "is_playable_hint", defaultValue = "0")
    val isPlayableHint: Boolean = false,
    @ColumnInfo(name = "viewed_at")
    val viewedAt: Long
)

@Entity(
    tableName = "movie_series_preferences"
)
data class MovieSeriesPreferencesEntity(
    @PrimaryKey
    @ColumnInfo(name = "media_key")
    val mediaKey: String,
    @ColumnInfo(name = "preferred_audio_language")
    val preferredAudioLanguage: String? = null,
    @ColumnInfo(name = "preferred_audio_label")
    val preferredAudioLabel: String? = null,
    @ColumnInfo(name = "preferred_subtitle_language")
    val preferredSubtitleLanguage: String? = null,
    @ColumnInfo(name = "preferred_subtitle_label")
    val preferredSubtitleLabel: String? = null,
    @ColumnInfo(name = "subtitles_disabled", defaultValue = "0")
    val subtitlesDisabled: Boolean = false,
    @ColumnInfo(name = "reminders_muted", defaultValue = "0")
    val remindersMuted: Boolean = false,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)

@Entity(
    tableName = "media_collection_cross_ref",
    primaryKeys = ["media_key", "collection_id"],
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = com.laterbox.app.data.local.entity.CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collection_id"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["collection_id"]),
        Index(value = ["media_key"]),
        Index(value = ["added_at"])
    ]
)
data class MediaCollectionCrossRef(
    @ColumnInfo(name = "media_key")
    val mediaKey: String,
    @ColumnInfo(name = "collection_id")
    val collectionId: Long,
    @ColumnInfo(name = "composite_id")
    val compositeId: String,
    @ColumnInfo(name = "provider_id")
    val providerId: String,
    @ColumnInfo(name = "title_id")
    val titleId: String,
    @ColumnInfo(name = "canonical_id")
    val canonicalId: String? = null,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "poster_url")
    val posterUrl: String? = null,
    @ColumnInfo(name = "backdrop_url")
    val backdropUrl: String? = null,
    @ColumnInfo(name = "release_year")
    val releaseYear: Int? = null,
    @ColumnInfo(name = "rating")
    val rating: Double? = null,
    @ColumnInfo(name = "genres_csv")
    val genresCsv: String = "",
    @ColumnInfo(name = "source_badge")
    val sourceBadge: String,
    @ColumnInfo(name = "is_playable_hint", defaultValue = "0")
    val isPlayableHint: Boolean = false,
    @ColumnInfo(name = "added_at")
    val addedAt: Long
)

@Entity(
    tableName = "movie_viewing_reminders",
    indices = [
        Index(value = ["trigger_at_millis"]),
        Index(value = ["is_dismissed"])
    ]
)
data class MovieViewingReminderEntity(
    @PrimaryKey
    @ColumnInfo(name = "media_key")
    val mediaKey: String,
    @ColumnInfo(name = "composite_id")
    val compositeId: String,
    @ColumnInfo(name = "provider_id")
    val providerId: String,
    @ColumnInfo(name = "title_id")
    val titleId: String,
    @ColumnInfo(name = "canonical_id")
    val canonicalId: String? = null,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "season_number")
    val seasonNumber: Int? = null,
    @ColumnInfo(name = "episode_number")
    val episodeNumber: Int? = null,
    @ColumnInfo(name = "poster_url")
    val posterUrl: String? = null,
    @ColumnInfo(name = "note")
    val note: String? = null,
    @ColumnInfo(name = "trigger_at_millis")
    val triggerAtMillis: Long,
    @ColumnInfo(name = "is_dismissed", defaultValue = "0")
    val isDismissed: Boolean = false,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)

@Entity(
    tableName = "hidden_titles",
    indices = [
        Index(value = ["provider_id", "title_id", "kind"], unique = true),
        Index(value = ["composite_id"]),
        Index(value = ["canonical_id"]),
        Index(value = ["hidden_at"])
    ]
)
data class HiddenTitleEntity(
    @PrimaryKey
    @ColumnInfo(name = "media_key")
    val mediaKey: String,
    @ColumnInfo(name = "composite_id")
    val compositeId: String,
    @ColumnInfo(name = "provider_id")
    val providerId: String,
    @ColumnInfo(name = "title_id")
    val titleId: String,
    @ColumnInfo(name = "canonical_id")
    val canonicalId: String? = null,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "poster_url")
    val posterUrl: String? = null,
    @ColumnInfo(name = "hidden_at")
    val hiddenAt: Long
)
