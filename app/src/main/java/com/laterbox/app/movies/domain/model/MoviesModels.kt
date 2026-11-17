package com.laterbox.app.movies.domain.model

enum class MediaKind(val wireValue: String, val label: String) {
    MOVIE("movie", "Movie"),
    TV_SERIES("tv_series", "TV Series");

    companion object {
        fun fromWire(value: String?): MediaKind =
            entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) } ?: MOVIE
    }
}

enum class DataSourceBadge(
    val displayLabel: String,
    val isLiveNetwork: Boolean,
    val isSampleFixture: Boolean = false
) {
    LIVE_MOVIEBOX("MovieBox", isLiveNetwork = true),
    LIVE_INTERNET_ARCHIVE("Internet Archive", isLiveNetwork = true),
    LIVE_TVMAZE("TVMaze", isLiveNetwork = true),
    LIVE_TMDB("TMDB", isLiveNetwork = true),
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
    val sourceBadge: DataSourceBadge = DataSourceBadge.LIVE_INTERNET_ARCHIVE,
    val isPlayableHint: Boolean = false,
    val imdbId: String? = null,
    val tvmazeId: String? = null,
    val canonicalId: String? = null
) {
    val compositeId: String get() = "$providerId:$id"
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
    val hasMore: Boolean = false
)

data class SearchSuggestion(
    val id: String,
    val providerId: String,
    val title: String,
    val kind: MediaKind = MediaKind.MOVIE,
    val releaseYear: Int? = null,
    val posterUrl: String? = null
) {
    val hasNavigableId: Boolean
        get() = id.isNotBlank() &&
            !id.equals("None", ignoreCase = true) &&
            !id.equals("null", ignoreCase = true)
}

data class CastMember(
    val name: String,
    val role: String? = null,
    val imageUrl: String? = null
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
    val imageUrl: String? = null
)

data class EpisodeItem(
    val id: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val overview: String? = null,
    val runtimeMinutes: Int? = null,
    val airDate: String? = null,
    val stillUrl: String? = null
)

data class MediaDetails(
    val summary: MovieTitleSummary,
    val fullDescription: String = "",
    val directorOrCreator: String? = null,
    val networkOrStudio: String? = null,
    val cast: List<CastMember> = emptyList(),
    val seasons: List<SeasonSummary> = emptyList(),
    val audioDubs: List<AudioDubInfo> = emptyList(),
    val availableSubtitles: List<SubtitleTrack> = emptyList(),
    val relatedTitles: List<MovieTitleSummary> = emptyList(),
    val externalInfoUrl: String? = null
)

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
    val providerName: String
)

data class SubtitleTrack(
    val id: String,
    val language: String,
    val languageCode: String,
    val label: String,
    val url: String,
    val format: String = "srt"
)

sealed interface PlaybackResolution {
    data class Available(
        val title: String,
        val subtitleHeading: String? = null,
        val sources: List<PlaybackSource>,
        val subtitles: List<SubtitleTrack> = emptyList(),
        val startPositionMs: Long = 0L,
        val providerName: String = "",
        val sourceBadge: DataSourceBadge = DataSourceBadge.LIVE_INTERNET_ARCHIVE
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
    val positionMs: Long,
    val durationMs: Long,
    val lastWatchedAt: Long
) {
    val progressFraction: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
}
