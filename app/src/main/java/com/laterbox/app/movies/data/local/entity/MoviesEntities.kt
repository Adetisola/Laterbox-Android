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
        Index(value = ["canonical_id"])
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
    @ColumnInfo(name = "position_ms")
    val positionMs: Long,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "last_watched_at")
    val lastWatchedAt: Long,
    @ColumnInfo(name = "canonical_id")
    val canonicalId: String? = null
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
