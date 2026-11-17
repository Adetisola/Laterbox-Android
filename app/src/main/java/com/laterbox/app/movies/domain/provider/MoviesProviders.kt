package com.laterbox.app.movies.domain.provider

import com.laterbox.app.movies.domain.model.CataloguePage
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.EpisodeItem
import com.laterbox.app.movies.domain.model.MediaDetails
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MoviesHomeFeed
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.SearchSuggestion
import com.laterbox.app.movies.domain.model.SeasonSummary
import com.laterbox.app.movies.domain.model.SubtitleTrack

interface MoviesProviderIdentity {
    val providerId: String
    val displayName: String
    val sourceBadge: DataSourceBadge
}

interface MoviesCatalogueProvider : MoviesProviderIdentity {
    suspend fun getHomeFeed(): Result<MoviesHomeFeed>

    suspend fun getCatalogue(
        kind: MediaKind,
        page: Int = 1,
        pageSize: Int = 20,
        genre: String? = null,
        sort: CatalogueSort = CatalogueSort.POPULAR
    ): Result<CataloguePage>

    suspend fun getGenres(): Result<List<String>>
}

interface MoviesSearchProvider : MoviesProviderIdentity {
    suspend fun search(
        query: String,
        kind: MediaKind? = null,
        page: Int = 1,
        pageSize: Int = 20
    ): Result<CataloguePage>

    suspend fun getSuggestions(
        query: String,
        limit: Int = 8
    ): Result<List<SearchSuggestion>>
}

interface MovieDetailsProvider : MoviesProviderIdentity {
    suspend fun getDetails(titleId: String): Result<MediaDetails>
}

interface SeriesEpisodeProvider : MoviesProviderIdentity {
    suspend fun getSeasons(seriesId: String): Result<List<SeasonSummary>>

    suspend fun getEpisodes(seriesId: String, seasonNumber: Int): Result<List<EpisodeItem>>
}

interface PlaybackSourceProvider : MoviesProviderIdentity {
    suspend fun resolvePlaybackSources(
        titleId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ): Result<PlaybackResolution>
}

interface SubtitleProvider : MoviesProviderIdentity {
    suspend fun getSubtitles(
        titleId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ): Result<List<SubtitleTrack>>
}
