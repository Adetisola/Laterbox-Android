package com.laterbox.app.movies.domain.repository

import com.laterbox.app.movies.domain.model.CataloguePage
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.EpisodeItem
import com.laterbox.app.movies.domain.model.MediaDetails
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesHomeFeed
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.PersonFilmography
import com.laterbox.app.movies.domain.model.ProviderLinkRecord
import com.laterbox.app.movies.domain.model.SearchSuggestion
import com.laterbox.app.movies.domain.model.SeasonSummary
import com.laterbox.app.movies.domain.model.SubtitleTrack
import com.laterbox.app.movies.domain.model.WatchHistoryItem
import kotlinx.coroutines.flow.Flow

interface MoviesRepository {
    suspend fun getHomeFeed(forceRefresh: Boolean = false): Result<MoviesHomeFeed>

    suspend fun getCatalogue(
        kind: MediaKind?,
        page: Int = 1,
        pageSize: Int = 20,
        genre: String? = null,
        sort: CatalogueSort = CatalogueSort.POPULAR,
        country: String? = null,
        year: String? = null
    ): Result<CataloguePage>

    suspend fun getGenres(): Result<List<String>>

    suspend fun search(
        query: String,
        kind: MediaKind? = null,
        page: Int = 1,
        pageSize: Int = 20
    ): Result<CataloguePage>

    suspend fun getSuggestions(query: String, limit: Int = 8): Result<List<SearchSuggestion>>

    suspend fun getDetails(providerId: String, titleId: String, forceRefresh: Boolean = false): Result<MediaDetails>

    suspend fun getSeasons(providerId: String, seriesId: String): Result<List<SeasonSummary>>

    suspend fun getEpisodes(providerId: String, seriesId: String, seasonNumber: Int): Result<List<EpisodeItem>>

    suspend fun getPersonFilmography(
        providerId: String,
        personId: String,
        personName: String,
        personImageUrl: String? = null
    ): Result<PersonFilmography> = Result.failure(
        UnsupportedOperationException("Person filmography is not supported for $providerId")
    )

    suspend fun resolvePlayback(
        providerId: String,
        titleId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ): Result<PlaybackResolution>

    suspend fun getSubtitles(
        providerId: String,
        titleId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ): Result<List<SubtitleTrack>>

    fun observeWatchlist(): Flow<List<MovieTitleSummary>>
    fun observeIsInWatchlist(compositeId: String): Flow<Boolean>
    suspend fun toggleWatchlist(item: MovieTitleSummary): Boolean

    fun observeContinueWatching(limit: Int = 12): Flow<List<WatchHistoryItem>>
    suspend fun saveWatchProgress(
        item: MovieTitleSummary,
        positionMs: Long,
        durationMs: Long,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        episodeLabel: String? = null
    )
    suspend fun getSavedPositionMs(
        providerId: String,
        titleId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ): Long

    fun observeRecentSearches(limit: Int = 10): Flow<List<String>>
    suspend fun recordSearchQuery(query: String)
    suspend fun clearSearchHistory()

    suspend fun getProviderLinks(providerId: String, titleId: String): List<ProviderLinkRecord> = emptyList()
    suspend fun unlinkProviderTitle(providerId: String, titleId: String): String? = null
}
