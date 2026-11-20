package com.laterbox.app.movies

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.movies.data.local.entity.CanonicalMediaEntity
import com.laterbox.app.movies.data.local.entity.MediaProviderLinkEntity
import com.laterbox.app.movies.domain.engine.MoviesDiscoveryEngine
import com.laterbox.app.movies.domain.model.CataloguePage
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.EpisodeItem
import com.laterbox.app.movies.domain.model.MediaDetails
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesHomeFeed
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.SearchSuggestion
import com.laterbox.app.movies.domain.model.SeasonSummary
import com.laterbox.app.movies.domain.model.SubtitleTrack
import com.laterbox.app.movies.domain.model.WatchHistoryItem
import com.laterbox.app.movies.domain.repository.MoviesRepository
import com.laterbox.app.movies.ui.MovieDetailViewModel
import com.laterbox.app.movies.ui.PlayerClockState
import com.laterbox.app.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MoviesPerformanceBenchmarksTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tempDbFiles = mutableListOf<String>()

    @After
    fun tearDown() {
        tempDbFiles.forEach { context.deleteDatabase(it) }
    }

    private fun openTempDb(name: String): LaterboxDatabase {
        tempDbFiles.add(name)
        return Room.databaseBuilder(context, LaterboxDatabase::class.java, name)
            .addMigrations(LaterboxDatabase.MIGRATION_16_17)
            .allowMainThreadQueries()
            .build()
    }

    @Test
    fun roomBatching_300CanonicalAndLinkWrites_batchedTransactionBeatsIndividualWrites() = runTest {
        val dbIndividual = openTempDb("bench_individual_${System.nanoTime()}.db")
        val dbBatched = openTempDb("bench_batched_${System.nanoTime()}.db")
        val daoInd = dbIndividual.moviesDao()
        val daoBatch = dbBatched.moviesDao()

        val canonicals = (1..300).map { idx ->
            CanonicalMediaEntity(
                canonicalId = "canon_$idx",
                kind = "movie",
                title = "Title $idx",
                normTitle = "title $idx",
                releaseYear = 2000 + (idx % 25),
                updatedAt = 1_000L + idx
            )
        }
        val links = (1..300).map { idx ->
            MediaProviderLinkEntity(
                canonicalId = "canon_$idx",
                providerId = "ia",
                providerTitleId = "item_$idx",
                confidence = "high",
                updatedAt = 1_000L + idx
            )
        }

        // Warm both SQLite handles
        val warmC = CanonicalMediaEntity("warm_0", "movie", "Warm", "warm", 2020, updatedAt = 1L)
        val warmL = MediaProviderLinkEntity("warm_0", "ia", "warm_0", "high", updatedAt = 1L)
        daoInd.upsertCanonicalMedia(warmC)
        daoInd.upsertProviderLink(warmL)
        daoBatch.upsertCanonicalAndLinksBatch(listOf(warmC), listOf(warmL))

        val indStart = System.nanoTime()
        for (i in canonicals.indices) {
            daoInd.upsertCanonicalMedia(canonicals[i])
            daoInd.upsertProviderLink(links[i])
        }
        val individualMs = (System.nanoTime() - indStart) / 1_000_000.0

        val batchStart = System.nanoTime()
        daoBatch.upsertCanonicalAndLinksBatch(canonicals, links)
        val batchedMs = (System.nanoTime() - batchStart) / 1_000_000.0

        val speedup = if (batchedMs > 0.0) individualMs / batchedMs else 1.0
        println(
            String.format(
                Locale.US,
                "BENCHMARK_DB_BATCHING: individualMs=%.2f batchedMs=%.2f speedup=%.2fx",
                individualMs,
                batchedMs,
                speedup
            )
        )

        assertEquals(301, daoInd.getAllCanonicalMedia().size)
        assertEquals(301, daoBatch.getAllCanonicalMedia().size)
        assertTrue(
            "Expected single @Transaction batch (${batchedMs}ms) to beat individual writes (${individualMs}ms)",
            batchedMs < individualMs
        )

        dbIndividual.close()
        dbBatched.close()
    }

    @Test
    fun discoveryDateKeyParsing_1000Calls_completesQuickly() {
        val sampleDates = (0 until 1000).map { idx ->
            val year = 1990 + (idx % 36)
            val month = 1 + (idx % 12)
            val day = 1 + (idx % 28)
            String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
        }
        // Warm regex & YearMonth paths
        MoviesDiscoveryEngine.parseSortableDateKey("2024-06-15")

        val startNs = System.nanoTime()
        var checksum = 0L
        for (date in sampleDates) {
            checksum += MoviesDiscoveryEngine.parseSortableDateKey(date)
        }
        val totalMs = (System.nanoTime() - startNs) / 1_000_000.0

        println(String.format(Locale.US, "BENCHMARK_DATE_PARSE_1000: totalMs=%.2f checksum=%d", totalMs, checksum))
        assertTrue(checksum > 0L)
        assertTrue("Expected 1000 parses < 500ms, got ${totalMs}ms", totalMs < 500.0)
    }

    @Test
    fun detailPrefill_cachedSummary_populatesUiStateBeforeNetworkCompletes() = runTest {
        val cached = MovieTitleSummary(
            id = "bench_1",
            providerId = "ia",
            kind = MediaKind.MOVIE,
            title = "Cached Classic",
            releaseYear = 1941,
            sourceBadge = DataSourceBadge.LIVE_INTERNET_ARCHIVE,
            isPlayableHint = true
        )
        val networkGate = CompletableDeferred<Result<MediaDetails>>()
        val repo = object : MoviesRepository {
            override suspend fun getHomeFeed(forceRefresh: Boolean) = Result.success(MoviesHomeFeed())
            override suspend fun getCatalogue(kind: MediaKind?, page: Int, pageSize: Int, genre: String?, sort: CatalogueSort, country: String?, year: String?) =
                Result.success(CataloguePage(emptyList(), page, pageSize, 0, false))
            override suspend fun getGenres() = Result.success(emptyList<String>())
            override suspend fun search(query: String, kind: MediaKind?, page: Int, pageSize: Int) =
                Result.success(CataloguePage(emptyList(), page, pageSize, 0, false))
            override suspend fun getSuggestions(query: String, limit: Int) = Result.success(emptyList<SearchSuggestion>())
            override fun getCachedSummary(providerId: String, titleId: String): MovieTitleSummary? = cached
            override suspend fun getDetails(providerId: String, titleId: String, forceRefresh: Boolean) = networkGate.await()
            override suspend fun getSeasons(providerId: String, seriesId: String) = Result.success(emptyList<SeasonSummary>())
            override suspend fun getEpisodes(providerId: String, seriesId: String, seasonNumber: Int) = Result.success(emptyList<EpisodeItem>())
            override suspend fun resolvePlayback(providerId: String, titleId: String, seasonNumber: Int?, episodeNumber: Int?) =
                Result.success(PlaybackResolution.Unavailable("n/a", "n/a", providerId))
            override suspend fun getSubtitles(providerId: String, titleId: String, seasonNumber: Int?, episodeNumber: Int?) =
                Result.success(emptyList<SubtitleTrack>())
            override fun observeWatchlist(): Flow<List<MovieTitleSummary>> = flowOf(emptyList())
            override fun observeIsInWatchlist(compositeId: String): Flow<Boolean> = flowOf(false)
            override suspend fun toggleWatchlist(item: MovieTitleSummary): Boolean = false
            override fun observeContinueWatching(limit: Int): Flow<List<WatchHistoryItem>> = flowOf(emptyList())
            override suspend fun saveWatchProgress(
                item: MovieTitleSummary,
                positionMs: Long,
                durationMs: Long,
                seasonNumber: Int?,
                episodeNumber: Int?,
                episodeLabel: String?
            ) = Unit
            override suspend fun getSavedPositionMs(providerId: String, titleId: String, seasonNumber: Int?, episodeNumber: Int?): Long = 0L
            override fun observeRecentSearches(limit: Int): Flow<List<String>> = flowOf(emptyList())
            override suspend fun recordSearchQuery(query: String) = Unit
            override suspend fun clearSearchHistory() = Unit
        }

        val vm = MovieDetailViewModel(repo)
        val startNs = System.nanoTime()
        vm.load("ia", "bench_1")
        val prefillMs = (System.nanoTime() - startNs) / 1_000_000.0

        println(String.format(Locale.US, "BENCHMARK_DETAIL_PREFILL: prefillMs=%.2f", prefillMs))
        assertFalse(vm.uiState.value.isLoading)
        assertEquals("Cached Classic", vm.uiState.value.details?.summary?.title)
        assertTrue("Expected Priority 1 cache prefill < 200ms, got ${prefillMs}ms", prefillMs < 200.0)
        networkGate.complete(Result.success(MediaDetails(summary = cached, fullDescription = "Full")))
    }

    @Test
    fun playerClockState_isolatedClockChild_avoidsParentRecompositionAcross10Ticks() {
        var parentRecompositions = 0
        var clockRecompositions = 0
        lateinit var clockState: PlayerClockState

        composeTestRule.setContent {
            clockState = remember { PlayerClockState(0L) }
            SideEffect { parentRecompositions++ }
            IsolatedClockText(clockState) { clockRecompositions++ }
        }

        composeTestRule.waitForIdle()
        assertEquals(1, parentRecompositions)
        assertEquals(1, clockRecompositions)

        repeat(10) {
            composeTestRule.runOnIdle {
                clockState.positionMs += 400L
            }
            composeTestRule.waitForIdle()
        }

        println("BENCHMARK_PLAYER_CLOCK_RECOMP: parentRecompositions=$parentRecompositions clockRecompositions=$clockRecompositions")
        assertEquals(1, parentRecompositions)
        assertEquals(11, clockRecompositions)
    }

    @Composable
    private fun IsolatedClockText(clockState: PlayerClockState, onRecompose: () -> Unit) {
        SideEffect { onRecompose() }
        Text(text = "${clockState.positionMs}")
    }
}
