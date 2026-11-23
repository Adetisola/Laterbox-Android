package com.laterbox.app.movies.domain.model

enum class MediaKind(val wireValue: String, val label: String) {
    MOVIE("movie", "Movie"),
    TV_SERIES("tv_series", "TV Series"),
    SHORT_DRAMA("short_drama", "Short Drama");

    val isEpisodic: Boolean
        get() = this == TV_SERIES || this == SHORT_DRAMA

    companion object {
        fun fromWire(value: String?): MediaKind = when (value?.trim()?.lowercase()) {
            "movie", "movies", "film" -> MOVIE
            "tv_series", "tv", "series" -> TV_SERIES
            "short_drama", "short_tv", "shorttv", "short" -> SHORT_DRAMA
            else -> entries.firstOrNull { it.wireValue.equals(value?.trim(), ignoreCase = true) } ?: MOVIE
        }
    }
}

enum class DataSourceBadge(
    val displayLabel: String,
    val isLiveNetwork: Boolean,
    val isSampleFixture: Boolean = false
) {
    LIVE_MOVIEBOX("Live Catalogue", isLiveNetwork = true),
    LIVE_INTERNET_ARCHIVE("Internet Archive", isLiveNetwork = true),
    LIVE_TVMAZE("TV Guide", isLiveNetwork = true),
    LIVE_TMDB("Film Guide", isLiveNetwork = true),
    SAMPLE_FIXTURE("Sample Fixture", isLiveNetwork = false, isSampleFixture = true);

    companion object {
        fun fromName(value: String?): DataSourceBadge =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SAMPLE_FIXTURE
    }
}

enum class CatalogueSort(val wireValue: String, val label: String) {
    POPULAR("popular", "Popular"),
    TOP_RATED("top_rated", "Top Rated"),
    NEWEST("newest", "Newest");

    companion object {
        fun fromWire(value: String?): CatalogueSort? =
            entries.firstOrNull { it.wireValue.equals(value?.trim(), ignoreCase = true) }
    }
}

enum class MoviesDiscoveryShortcut(
    val id: String,
    val label: String,
    val kind: MediaKind,
    val genre: String? = null,
    val country: String? = null
) {
    HOLLYWOOD("hollywood", "Hollywood", MediaKind.MOVIE, genre = null, country = "United States"),
    BOLLYWOOD("bollywood", "Bollywood", MediaKind.MOVIE, genre = null, country = "India"),
    WESTERN("western", "Western", MediaKind.MOVIE, genre = "Western", country = null),
    K_DRAMA("k_drama", "K-Drama", MediaKind.TV_SERIES, genre = "Drama", country = "Korea")
}

enum class RuntimeBucket(val label: String, val minMinutes: Int?, val maxMinutes: Int?) {
    ALL("Any length", null, null),
    QUICK("Quick (<90m)", 1, 89),
    FEATURE("Feature (90–140m)", 90, 140),
    EPIC("Epic (140m+)", 141, null);

    fun matches(durationMinutes: Int?): Boolean {
        if (this == ALL) return true
        val mins = durationMinutes?.takeIf { it > 0 } ?: return false
        val minOk = minMinutes == null || mins >= minMinutes
        val maxOk = maxMinutes == null || mins <= maxMinutes
        return minOk && maxOk
    }
}

enum class SectionStyle {
    HERO,
    RAIL,
    COLLECTION
}

data class MovieTitleSummary(
    val id: String,
    val providerId: String,
    val kind: MediaKind,
    val title: String,
    val releaseYear: Int? = null,
    val rating: Double? = null,
    val durationMinutes: Int? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val genres: List<String> = emptyList(),
    val summary: String? = null,
    val language: String? = null,
    val country: String? = null,
    val detailPath: String? = null,
    val trailerUrl: String? = null,
    val trailerHeaders: Map<String, String> = emptyMap(),
    val sourceBadge: DataSourceBadge = DataSourceBadge.LIVE_INTERNET_ARCHIVE,
    val isPlayableHint: Boolean = false,
    val imdbId: String? = null,
    val tvmazeId: String? = null,
    val canonicalId: String? = null,
    val releaseDate: String? = null,
    val addedAtMs: Long? = null,
    val subjectType: Int? = null,
    val posterBlurHash: String? = null,
    val backdropBlurHash: String? = null
) {
    val compositeId: String get() = "$providerId:$id"
    val stableMediaKey: String get() = canonicalId?.takeIf { it.isNotBlank() } ?: compositeId
}

data class ProviderLinkRecord(
    val canonicalId: String,
    val providerId: String,
    val providerTitleId: String,
    val confidence: String,
    val updatedAt: Long
)

data class BrowseSection(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val style: SectionStyle = SectionStyle.RAIL,
    val items: List<MovieTitleSummary> = emptyList()
)

data class MoviesHomeFeed(
    val featuredBanners: List<MovieTitleSummary> = emptyList(),
    val sections: List<BrowseSection> = emptyList(),
    val genres: List<String> = emptyList(),
    val activeProviderNotice: String? = null,
    val isOfflineCached: Boolean = false,
    val containsSampleFixtures: Boolean = false
)

data class CataloguePage(
    val items: List<MovieTitleSummary> = emptyList(),
    val page: Int = 1,
    val pageSize: Int = 20,
    val totalCount: Int? = null,
    val hasMore: Boolean = false,
    val primaryCount: Int = items.size,
    val activeProviderNotice: String? = null
)

data class SearchSuggestion(
    val id: String,
    val providerId: String,
    val title: String,
    val kind: MediaKind? = null,
    val releaseYear: Int? = null,
    val posterUrl: String? = null
) {
    val hasNavigableId: Boolean
        get() = id.isNotBlank() &&
            providerId.isNotBlank() &&
            !id.trim().equals("0", ignoreCase = true) &&
            !id.trim().equals("None", ignoreCase = true) &&
            !id.trim().equals("null", ignoreCase = true) &&
            !providerId.trim().equals("None", ignoreCase = true) &&
            !providerId.trim().equals("null", ignoreCase = true)
}

data class LibrarySearchSuggestion(
    val summary: MovieTitleSummary,
    val sourceLabel: String,
    val isDownloaded: Boolean = false
)

data class CastMember(
    val name: String,
    val role: String? = null,
    val imageUrl: String? = null,
    val personId: String? = null,
    val providerId: String? = null
)

data class PersonFilmography(
    val personId: String,
    val providerId: String,
    val name: String,
    val imageUrl: String? = null,
    val credits: List<MovieTitleSummary> = emptyList()
)

data class AudioDubInfo(
    val language: String,
    val label: String,
    val referenceId: String? = null
)

data class SeasonSummary(
    val seasonNumber: Int,
    val title: String = "Season $seasonNumber",
    val episodeCount: Int = 0,
    val imageUrl: String? = null,
    val availableEpisodes: List<Int> = emptyList(),
    val availableResolutions: List<Int> = emptyList()
)

data class EpisodeItem(
    val id: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val overview: String? = null,
    val runtimeMinutes: Int? = null,
    val airDate: String? = null,
    val stillUrl: String? = null,
    val sizesMbByQuality: Map<Int, Double> = emptyMap()
)

data class MediaDetails(
    val summary: MovieTitleSummary,
    val fullDescription: String = "",
    val directorOrCreator: String? = null,
    val directorPersonId: String? = null,
    val networkOrStudio: String? = null,
    val cast: List<CastMember> = emptyList(),
    val seasons: List<SeasonSummary> = emptyList(),
    val audioDubs: List<AudioDubInfo> = emptyList(),
    val availableSubtitles: List<SubtitleTrack> = emptyList(),
    val relatedTitles: List<MovieTitleSummary> = emptyList(),
    val externalInfoUrl: String? = null
)

data class PlaybackMarkerWindow(
    val startMs: Long,
    val endMs: Long
) {
    fun contains(positionMs: Long): Boolean =
        endMs > startMs && startMs >= 0L && positionMs in startMs..endMs
}

data class PlaybackSource(
    val id: String,
    val url: String,
    val format: String,
    val qualityLabel: String,
    val qualityHeight: Int,
    val sizeMb: Double? = null,
    val codec: String? = null,
    val durationSeconds: Long? = null,
    val headers: Map<String, String> = emptyMap(),
    val isAuthorised: Boolean = true,
    val providerName: String,
    val isOfflineLocal: Boolean = false
)

data class SubtitleTrack(
    val id: String,
    val language: String,
    val languageCode: String,
    val label: String,
    val url: String,
    val format: String = "srt",
    val headers: Map<String, String> = emptyMap(),
    val isOfflineLocal: Boolean = false
)

sealed interface PlaybackResolution {
    data class Available(
        val title: String,
        val subtitleHeading: String? = null,
        val sources: List<PlaybackSource>,
        val subtitles: List<SubtitleTrack> = emptyList(),
        val startPositionMs: Long = 0L,
        val providerName: String = "",
        val sourceBadge: DataSourceBadge = DataSourceBadge.LIVE_INTERNET_ARCHIVE,
        val introWindow: PlaybackMarkerWindow? = null,
        val recapWindow: PlaybackMarkerWindow? = null
    ) : PlaybackResolution

    data class Unavailable(
        val title: String,
        val reason: String,
        val providerName: String,
        val externalReferenceUrl: String? = null,
        val sourceBadge: DataSourceBadge = DataSourceBadge.LIVE_TVMAZE
    ) : PlaybackResolution
}

data class WatchHistoryItem(
    val compositeId: String,
    val providerId: String,
    val titleId: String,
    val kind: MediaKind,
    val title: String,
    val episodeLabel: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val positionMs: Long,
    val durationMs: Long,
    val lastWatchedAt: Long,
    val historyKey: String = "",
    val isTrailer: Boolean = false,
    val canonicalId: String? = null,
    val isCompleted: Boolean = false,
    val completedAt: Long? = null,
    val rewatchCount: Int = 0,
    val hiddenFromContinueWatching: Boolean = false,
    val isHiddenTitle: Boolean = false
) {
    val stableMediaKey: String get() = canonicalId?.takeIf { it.isNotBlank() } ?: compositeId
    val progressFraction: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
}

data class EpisodeProgressState(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val isCompleted: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val rewatchCount: Int = 0,
    val lastWatchedAt: Long = 0L
)

data class SeriesProgressSummary(
    val mediaKey: String,
    val compositeId: String,
    val providerId: String,
    val titleId: String,
    val title: String,
    val totalWatchedEpisodes: Int = 0,
    val lastWatchedSeason: Int? = null,
    val lastWatchedEpisode: Int? = null,
    val lastWatchedAt: Long = 0L,
    val nextUnwatchedSeason: Int? = null,
    val nextUnwatchedEpisode: Int? = null,
    val episodeStates: Map<Pair<Int, Int>, EpisodeProgressState> = emptyMap()
)

data class SeriesPreferences(
    val mediaKey: String,
    val preferredAudioLanguage: String? = null,
    val preferredAudioLabel: String? = null,
    val preferredSubtitleLanguage: String? = null,
    val preferredSubtitleLabel: String? = null,
    val subtitlesDisabled: Boolean = false,
    val remindersMuted: Boolean = false,
    val updatedAt: Long = 0L
)

data class ViewingReminderItem(
    val mediaKey: String,
    val compositeId: String,
    val providerId: String,
    val titleId: String,
    val canonicalId: String? = null,
    val kind: MediaKind,
    val title: String,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val posterUrl: String? = null,
    val note: String? = null,
    val triggerAtMillis: Long,
    val isDismissed: Boolean = false
)

data class OfflineMediaMatch(
    val assetId: String,
    val sourceRef: String,
    val localPath: String,
    val qualityLabel: String?,
    val fileSizeBytes: Long,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null
)

data class CollectionMediaItem(
    val collectionId: Long,
    val mediaKey: String,
    val summary: MovieTitleSummary,
    val addedAt: Long
)

data class BecauseYouWatchedGroup(
    val anchorTitle: String,
    val anchorKind: MediaKind,
    val items: List<MovieTitleSummary>
)

data class SurprisePickResult(
    val pick: MovieTitleSummary,
    val rationale: String
)
