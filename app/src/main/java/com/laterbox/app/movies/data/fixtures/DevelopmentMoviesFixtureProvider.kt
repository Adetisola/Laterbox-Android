package com.laterbox.app.movies.data.fixtures

import com.laterbox.app.movies.domain.model.AudioDubInfo
import com.laterbox.app.movies.domain.model.BrowseSection
import com.laterbox.app.movies.domain.model.CastMember
import com.laterbox.app.movies.domain.model.CataloguePage
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.EpisodeItem
import com.laterbox.app.movies.domain.model.MediaDetails
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesHomeFeed
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.PlaybackSource
import com.laterbox.app.movies.domain.model.SearchSuggestion
import com.laterbox.app.movies.domain.model.SeasonSummary
import com.laterbox.app.movies.domain.model.SectionStyle
import com.laterbox.app.movies.domain.model.SubtitleTrack
import com.laterbox.app.movies.domain.provider.MovieDetailsProvider
import com.laterbox.app.movies.domain.provider.MoviesCatalogueProvider
import com.laterbox.app.movies.domain.provider.MoviesSearchProvider
import com.laterbox.app.movies.domain.provider.PlaybackSourceProvider
import com.laterbox.app.movies.domain.provider.SeriesEpisodeProvider
import com.laterbox.app.movies.domain.provider.SubtitleProvider

/**
 * Isolated development and offline demo fixtures using open-license Blender Foundation shorts
 * and sample TV guide entries. Explicitly marked [DataSourceBadge.SAMPLE_FIXTURE] so fixture
 * data is never presented as genuine live catalogue responses.
 */
class DevelopmentMoviesFixtureProvider :
    MoviesCatalogueProvider,
    MoviesSearchProvider,
    MovieDetailsProvider,
    SeriesEpisodeProvider,
    PlaybackSourceProvider,
    SubtitleProvider {

    override val providerId: String = PROVIDER_ID
    override val displayName: String = "Open Cinema Sample Fixtures"
    override val sourceBadge: DataSourceBadge = DataSourceBadge.SAMPLE_FIXTURE

    private val movies = listOf(
        MovieTitleSummary(
            id = "big_buck_bunny",
            providerId = PROVIDER_ID,
            kind = MediaKind.MOVIE,
            title = "Big Buck Bunny",
            releaseYear = 2008,
            rating = 8.4,
            durationMinutes = 10,
            posterUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/c/c5/Big_buck_bunny_poster_big.jpg/640px-Big_buck_bunny_poster_big.jpg",
            backdropUrl = "https://peach.blender.org/wp-content/uploads/title_anouncement.jpg",
            genres = listOf("Animation", "Comedy", "Family"),
            summary = "A gentle giant rabbit's peaceful forest morning is disrupted by three mischievous rodents.",
            language = "English",
            country = "Netherlands",
            sourceBadge = DataSourceBadge.SAMPLE_FIXTURE,
            isPlayableHint = true
        ),
        MovieTitleSummary(
            id = "sintel",
            providerId = PROVIDER_ID,
            kind = MediaKind.MOVIE,
            title = "Sintel",
            releaseYear = 2010,
            rating = 8.7,
            durationMinutes = 15,
            posterUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/8/8f/Sintel_poster.jpg/640px-Sintel_poster.jpg",
            backdropUrl = "https://durian.blender.org/wp-content/uploads/2010/06/sintel_trailer_1080p.jpg",
            genres = listOf("Animation", "Fantasy", "Adventure"),
            summary = "A lone wanderer embarks on a perilous quest across frozen peaks to reunite with her companion dragon.",
            language = "English",
            country = "Netherlands",
            sourceBadge = DataSourceBadge.SAMPLE_FIXTURE,
            isPlayableHint = true
        ),
        MovieTitleSummary(
            id = "tears_of_steel",
            providerId = PROVIDER_ID,
            kind = MediaKind.MOVIE,
            title = "Tears of Steel",
            releaseYear = 2012,
            rating = 8.1,
            durationMinutes = 12,
            posterUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e5/Tears_of_Steel_poster.jpg/640px-Tears_of_Steel_poster.jpg",
            backdropUrl = "https://mango.blender.org/wp-content/uploads/2012/09/01_thom_celia_bridge.jpg",
            genres = listOf("Science-Fiction", "Action", "Drama"),
            summary = "In a neon-lit futuristic Amsterdam, a team of warriors and scientists attempts to stop rogue robots.",
            language = "English",
            country = "Netherlands",
            sourceBadge = DataSourceBadge.SAMPLE_FIXTURE,
            isPlayableHint = true
        ),
        MovieTitleSummary(
            id = "elephants_dream",
            providerId = PROVIDER_ID,
            kind = MediaKind.MOVIE,
            title = "Elephants Dream",
            releaseYear = 2006,
            rating = 7.6,
            durationMinutes = 11,
            posterUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e0/Elephants_Dream_s1_proog.jpg/640px-Elephants_Dream_s1_proog.jpg",
            backdropUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e0/Elephants_Dream_s1_proog.jpg/640px-Elephants_Dream_s1_proog.jpg",
            genres = listOf("Animation", "Science-Fiction", "Mystery"),
            summary = "Proog and Emo explore a surreal mechanical world governed by their own perceptions.",
            language = "English",
            country = "Netherlands",
            sourceBadge = DataSourceBadge.SAMPLE_FIXTURE,
            isPlayableHint = true
        )
    )

    private val series = listOf(
        MovieTitleSummary(
            id = "cosmos_series_fixture",
            providerId = PROVIDER_ID,
            kind = MediaKind.TV_SERIES,
            title = "Cosmos Open Anthology (Sample Series)",
            releaseYear = 2015,
            rating = 8.5,
            durationMinutes = 14,
            posterUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/4/45/Cosmos_Laundromat_-_First_Cycle_poster.jpg/640px-Cosmos_Laundromat_-_First_Cycle_poster.jpg",
            backdropUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/4/45/Cosmos_Laundromat_-_First_Cycle_poster.jpg/640px-Cosmos_Laundromat_-_First_Cycle_poster.jpg",
            genres = listOf("Science-Fiction", "Drama", "Anthology"),
            summary = "Sample multi-season development series fixture demonstrating season/episode navigation and explicit unavailability when an episode lacks a stream.",
            language = "English",
            country = "Global",
            sourceBadge = DataSourceBadge.SAMPLE_FIXTURE,
            isPlayableHint = true
        )
    )

    private val allTitles: List<MovieTitleSummary> = movies + series

    override suspend fun getHomeFeed(): Result<MoviesHomeFeed> = Result.success(
        MoviesHomeFeed(
            featuredBanners = listOf(movies[0], movies[1], series[0]),
            sections = listOf(
                BrowseSection(
                    id = "fixture_trending",
                    title = "Open Cinema Fixtures",
                    subtitle = "Creative Commons sample films (offline development fixture)",
                    style = SectionStyle.RAIL,
                    items = movies
                ),
                BrowseSection(
                    id = "fixture_series",
                    title = "Sample Series Guide",
                    subtitle = "Multi-season test fixture",
                    style = SectionStyle.COLLECTION,
                    items = series
                )
            ),
            genres = listOf("All", "Animation", "Science-Fiction", "Comedy", "Adventure", "Fantasy", "Drama"),
            activeProviderNotice = "Development Sample Mode: displaying offline Creative Commons fixtures",
            isOfflineCached = false,
            containsSampleFixtures = true
        )
    )

    override suspend fun getCatalogue(
        kind: MediaKind,
        page: Int,
        pageSize: Int,
        genre: String?,
        sort: CatalogueSort,
        country: String?,
        year: String?
    ): Result<CataloguePage> {
        val base = when (kind) {
            MediaKind.MOVIE -> movies
            MediaKind.TV_SERIES -> series
            MediaKind.SHORT_DRAMA -> emptyList()
        }.filter { item ->
            (genre.isNullOrBlank() || genre.equals("All", ignoreCase = true) ||
                item.genres.any { it.equals(genre, ignoreCase = true) }) &&
                (country.isNullOrBlank() || country.equals("All", ignoreCase = true) ||
                    item.country.equals(country, ignoreCase = true)) &&
                (year.isNullOrBlank() || year.equals("All", ignoreCase = true) ||
                    item.releaseYear?.toString() == year.trim())
        }
        val sorted = when (sort) {
            CatalogueSort.POPULAR -> base
            CatalogueSort.TOP_RATED -> base.sortedByDescending { it.rating ?: 0.0 }
            CatalogueSort.NEWEST -> base.sortedByDescending { it.releaseYear ?: 0 }
        }
        return Result.success(
            CataloguePage(
                items = if (page <= 1) sorted.take(pageSize) else emptyList(),
                page = page,
                pageSize = pageSize,
                totalCount = sorted.size,
                hasMore = false
            )
        )
    }

    override suspend fun getGenres(): Result<List<String>> =
        Result.success(listOf("All", "Animation", "Science-Fiction", "Comedy", "Adventure", "Fantasy", "Drama"))

    override suspend fun search(query: String, kind: MediaKind?, page: Int, pageSize: Int): Result<CataloguePage> {
        val q = query.trim().lowercase()
        val matches = allTitles.filter {
            (kind == null || it.kind == kind) &&
                (it.title.lowercase().contains(q) || it.genres.any { g -> g.lowercase().contains(q) })
        }
        return Result.success(
            CataloguePage(
                items = if (page <= 1) matches.take(pageSize) else emptyList(),
                page = page,
                pageSize = pageSize,
                totalCount = matches.size,
                hasMore = false
            )
        )
    }

    override suspend fun getSuggestions(query: String, limit: Int): Result<List<SearchSuggestion>> {
        val q = query.trim().lowercase()
        if (q.length < 2) return Result.success(emptyList())
        return Result.success(
            allTitles.filter { it.title.lowercase().contains(q) }
                .take(limit)
                .map { SearchSuggestion(it.id, providerId, it.title, it.kind, it.releaseYear, it.posterUrl) }
        )
    }

    override suspend fun getDetails(titleId: String): Result<MediaDetails> {
        val item = allTitles.firstOrNull { it.id == titleId }
            ?: return Result.failure(NoSuchElementException("Sample fixture '$titleId' not found"))
        val isSeries = item.kind == MediaKind.TV_SERIES
        return Result.success(
            MediaDetails(
                summary = item,
                fullDescription = item.summary.orEmpty(),
                directorOrCreator = "Blender Open Movie Studio",
                networkOrStudio = "Blender Foundation (CC-BY)",
                cast = listOf(
                    CastMember("Franck", "Voice / Lead", null),
                    CastMember("Sintel", "Wanderer", null)
                ),
                seasons = if (isSeries) {
                    listOf(
                        SeasonSummary(1, "Season 1", 2),
                        SeasonSummary(2, "Season 2", 1)
                    )
                } else emptyList(),
                audioDubs = listOf(
                    AudioDubInfo("English", "English (Original)"),
                    AudioDubInfo("Spanish", "Español (Sample Metadata)")
                ),
                availableSubtitles = getSubtitles(titleId).getOrDefault(emptyList()),
                relatedTitles = allTitles.filterNot { it.id == titleId },
                externalInfoUrl = "https://studio.blender.org/films/"
            )
        )
    }

    override suspend fun getSeasons(seriesId: String): Result<List<SeasonSummary>> =
        if (seriesId == "cosmos_series_fixture") {
            Result.success(listOf(SeasonSummary(1, "Season 1", 2), SeasonSummary(2, "Season 2", 1)))
        } else Result.success(emptyList())

    override suspend fun getEpisodes(seriesId: String, seasonNumber: Int): Result<List<EpisodeItem>> =
        if (seriesId != "cosmos_series_fixture") {
            Result.success(emptyList())
        } else if (seasonNumber == 1) {
            Result.success(
                listOf(
                    EpisodeItem("s1e1", 1, 1, "First Cycle (Sample Playable)", "Demonstrates authorised Creative Commons sample playback.", 12, "2015-08-10", null),
                    EpisodeItem("s1e2", 1, 2, "Second Cycle (Unavailable Source Demo)", "Demonstrates honest unavailable-stream state handling.", 14, "2015-08-17", null)
                )
            )
        } else {
            Result.success(
                listOf(
                    EpisodeItem("s2e1", 2, 1, "Finale Preview (Unavailable Source Demo)", "Episode without an authorised stream.", 16, "2016-01-12", null)
                )
            )
        }

    override suspend fun resolvePlaybackSources(
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): Result<PlaybackResolution> {
        val item = allTitles.firstOrNull { it.id == titleId }
            ?: return Result.failure(NoSuchElementException("Sample fixture '$titleId' not found"))

        if (titleId == "cosmos_series_fixture" && !((seasonNumber ?: 1) == 1 && (episodeNumber ?: 1) == 1)) {
            return Result.success(
                PlaybackResolution.Unavailable(
                    title = "${item.title} S${seasonNumber ?: 1}E${episodeNumber ?: 1}",
                    reason = "Sample fixture demonstration: this episode has no authorised stream attached.",
                    providerName = displayName,
                    externalReferenceUrl = "https://studio.blender.org/films/",
                    sourceBadge = sourceBadge
                )
            )
        }

        val streamUrl = when (titleId) {
            "big_buck_bunny" -> "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
            "sintel" -> "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4"
            "tears_of_steel" -> "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4"
            "elephants_dream" -> "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4"
            else -> "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"
        }

        return Result.success(
            PlaybackResolution.Available(
                title = item.title,
                subtitleHeading = if (item.kind == MediaKind.TV_SERIES) "Sample Series · S${seasonNumber ?: 1}E${episodeNumber ?: 1}" else "Open License Sample Fixture",
                sources = listOf(
                    PlaybackSource(
                        id = "$titleId:720p",
                        url = streamUrl,
                        format = "MP4",
                        qualityLabel = "720p (Sample)",
                        qualityHeight = 720,
                        codec = "H.264",
                        isAuthorised = true,
                        providerName = displayName
                    )
                ),
                subtitles = emptyList(),
                providerName = displayName,
                sourceBadge = sourceBadge
            )
        )
    }

    override suspend fun getSubtitles(
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): Result<List<SubtitleTrack>> = Result.success(emptyList())

    companion object {
        const val PROVIDER_ID = "fixture"
    }
}
