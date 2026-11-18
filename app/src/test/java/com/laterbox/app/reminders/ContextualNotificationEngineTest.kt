package com.laterbox.app.reminders

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.data.repository.ActivityEventRepository
import com.laterbox.app.diagnostics.AppHealthChecker
import com.laterbox.app.media.data.adapters.ytdlp.YtDlpRuntimeManager
import com.laterbox.app.movies.data.local.entity.MovieSeriesPreferencesEntity
import com.laterbox.app.movies.data.local.entity.MovieWatchHistoryEntity
import com.laterbox.app.movies.data.local.entity.MovieWatchlistEntity
import com.laterbox.app.movies.domain.model.BrowseSection
import com.laterbox.app.movies.domain.model.CataloguePage
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.EpisodeItem
import com.laterbox.app.movies.domain.model.MediaDetails
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesHomeFeed
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.SearchSuggestion
import com.laterbox.app.movies.domain.model.SeasonSummary
import com.laterbox.app.movies.domain.model.SubtitleTrack
import com.laterbox.app.movies.domain.repository.MoviesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContextualNotificationEngineTest {

    private lateinit var context: Context
    private lateinit var db: LaterboxDatabase
    private lateinit var prefsRepo: UserPreferencesRepository
    private lateinit var activityRepo: ActivityEventRepository
    private lateinit var healthChecker: AppHealthChecker
    private lateinit var engine: ContextualNotificationEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        db = Room.inMemoryDatabaseBuilder(context, LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        prefsRepo = UserPreferencesRepository(context)
        activityRepo = ActivityEventRepository(db.activityEventDao())
        healthChecker = AppHealthChecker(context, db, YtDlpRuntimeManager(context), prefsRepo)
        engine = ContextualNotificationEngine(context, db, prefsRepo, activityRepo, healthChecker)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun isWithinQuietHours_handlesOvernightDaytimeAndDisabledWindows() {
        // Overnight 22:00 -> 08:00
        assertTrue(NotificationHelper.isWithinQuietHours(23, 22, 8))
        assertTrue(NotificationHelper.isWithinQuietHours(2, 22, 8))
        assertFalse(NotificationHelper.isWithinQuietHours(8, 22, 8))
        assertFalse(NotificationHelper.isWithinQuietHours(15, 22, 8))

        // Daytime 09:00 -> 17:00
        assertTrue(NotificationHelper.isWithinQuietHours(9, 9, 17))
        assertTrue(NotificationHelper.isWithinQuietHours(16, 9, 17))
        assertFalse(NotificationHelper.isWithinQuietHours(17, 9, 17))
        assertFalse(NotificationHelper.isWithinQuietHours(20, 9, 17))

        // Disabled when start == end
        assertFalse(NotificationHelper.isWithinQuietHours(12, 10, 10))
    }

    @Test
    fun claimDedupKey_returnsTrueOnceAndFalseOnDuplicate() {
        val unique = "test_dedup_${System.nanoTime()}"
        assertTrue(NotificationHelper.claimDedupKey(context, unique))
        assertFalse(NotificationHelper.claimDedupKey(context, unique))
    }

    @Test
    fun notifyFailures_respectsQuietHoursAndPreferenceToggles() = runTest {
        prefsRepo.setSmartNotificationsExtractionFailure(true)
        prefsRepo.setSmartNotificationsDownloadFailure(true)
        prefsRepo.setQuietHoursEnabled(true)
        prefsRepo.setQuietHoursWindow(22, 8)

        // Suppressed during quiet hour (23:00)
        assertFalse(
            engine.notifyExtractionFailure(
                savedLinkId = 1L,
                url = "https://example.com/v",
                linkTitle = "Video",
                errorMessage = "Timed out",
                dedupKey = "ext_quiet_${System.nanoTime()}",
                currentHour = 23
            )
        )
        assertFalse(
            engine.notifyDownloadFailure(
                taskId = "task-1",
                savedLinkId = 1L,
                title = "Video",
                errorMessage = "HTTP 403",
                dedupKey = "dl_quiet_${System.nanoTime()}",
                currentHour = 2
            )
        )

        // Suppressed when toggles are off even outside quiet hours
        prefsRepo.setSmartNotificationsExtractionFailure(false)
        prefsRepo.setSmartNotificationsDownloadFailure(false)
        assertFalse(
            engine.notifyExtractionFailure(
                savedLinkId = 1L,
                url = "https://example.com/v",
                linkTitle = "Video",
                errorMessage = "Timed out",
                dedupKey = "ext_off_${System.nanoTime()}",
                currentHour = 14
            )
        )
        assertFalse(
            engine.notifyDownloadFailure(
                taskId = "task-1",
                savedLinkId = 1L,
                title = "Video",
                errorMessage = "HTTP 403",
                dedupKey = "dl_off_${System.nanoTime()}",
                currentHour = 14
            )
        )
    }

    @Test
    fun selectNextUnshownTip_isNonRepeatingAcrossCatalogAndStopsWhenExhausted() = runTest {
        prefsRepo.resetShownTips()
        val seen = mutableSetOf<String>()

        repeat(ContextualNotificationEngine.TIP_CATALOG.size) {
            val next = ContextualNotificationEngine.selectNextUnshownTip(seen)
            assertNotNull(next)
            assertTrue("Tip ${next!!.id} should not repeat", seen.add(next.id))
            prefsRepo.recordShownTip(next.id, timestamp = 1000L + it)
        }

        assertNull(ContextualNotificationEngine.selectNextUnshownTip(seen))
        assertEquals(ContextualNotificationEngine.TIP_CATALOG.size, seen.size)
    }

    @Test
    fun maybeNotifyMoviesAndTv_respectsTogglesQuietHoursMuteAndPostsContinueWatchingNudge() = runTest {
        prefsRepo.recordMoviesNotificationPosted(0L)
        val now = 2_000_000_000_000L
        val dao = db.moviesDao()
        dao.upsertWatchHistory(
            MovieWatchHistoryEntity(
                historyKey = "moviebox:cw_1:0:0",
                compositeId = "moviebox:cw_1",
                providerId = "moviebox",
                titleId = "cw_1",
                kind = "movie",
                title = "The Thin Man",
                episodeLabel = null,
                seasonNumber = null,
                episodeNumber = null,
                posterUrl = null,
                positionMs = 300_000L,
                durationMs = 5_400_000L,
                lastWatchedAt = now - ContextualNotificationEngine.CONTINUE_WATCHING_STALE_MS - 60_000L,
                isTrailer = false,
                isCompleted = false
            )
        )

        prefsRepo.setSmartRemindersEnabled(true)
        prefsRepo.setSmartNotificationsMoviesTv(true)
        prefsRepo.setQuietHoursEnabled(true)
        prefsRepo.setQuietHoursWindow(22, 8)

        // Suppressed during quiet hours
        assertFalse(engine.maybeNotifyMoviesAndTv(nowMs = now, currentHour = 23))

        // Suppressed when toggle disabled
        prefsRepo.setSmartNotificationsMoviesTv(false)
        assertFalse(engine.maybeNotifyMoviesAndTv(nowMs = now, currentHour = 14))
        prefsRepo.setSmartNotificationsMoviesTv(true)

        // Suppressed when series/title is muted
        dao.upsertSeriesPreferences(
            MovieSeriesPreferencesEntity(
                mediaKey = "moviebox:cw_1",
                remindersMuted = true,
                updatedAt = now
            )
        )
        assertFalse(engine.maybeNotifyMoviesAndTv(nowMs = now, currentHour = 14))

        // Unmuted outside quiet hours posts Continue Watching notification and enforces cooldown
        dao.upsertSeriesPreferences(
            MovieSeriesPreferencesEntity(
                mediaKey = "moviebox:cw_1",
                remindersMuted = false,
                updatedAt = now
            )
        )
        assertTrue(engine.maybeNotifyMoviesAndTv(nowMs = now, currentHour = 14))
        val notifMgr = Shadows.shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        assertTrue(notifMgr.allNotifications.any { it.extras.getString("android.title")?.contains("The Thin Man") == true })

        // Cooldown suppresses immediate second notification
        assertFalse(engine.maybeNotifyMoviesAndTv(nowMs = now + 60_000L, currentHour = 14))
    }

    @Test
    fun maybeNotifyMoviesAndTv_postsVerifiedTvMazeNewSeasonOnlyWhenHigherSeasonVerified() = runTest {
        prefsRepo.recordMoviesNotificationPosted(0L)
        val now = 3_000_000_000_000L
        val dao = db.moviesDao()
        dao.upsertWatchHistory(
            MovieWatchHistoryEntity(
                historyKey = "tvmaze:66:1:10",
                compositeId = "tvmaze:66",
                providerId = "tvmaze",
                titleId = "66",
                kind = "tv_series",
                title = "Severance",
                episodeLabel = "S1E10",
                seasonNumber = 1,
                episodeNumber = 10,
                posterUrl = null,
                positionMs = 2_400_000L,
                durationMs = 2_400_000L,
                lastWatchedAt = now - ContextualNotificationEngine.CONTINUE_WATCHING_STALE_MS - 60_000L,
                isTrailer = false,
                isCompleted = true
            )
        )

        var seasonsResponse = listOf(SeasonSummary(seasonNumber = 1, title = "Season 1", episodeCount = 9))
        val fakeRepo = object : MoviesRepository {
            override suspend fun getHomeFeed(forceRefresh: Boolean) = Result.success(MoviesHomeFeed())
            override suspend fun getCatalogue(kind: MediaKind?, page: Int, pageSize: Int, genre: String?, sort: CatalogueSort, country: String?, year: String?) = Result.success(CataloguePage(items = emptyList(), page = page, hasMore = false))
            override suspend fun getGenres() = Result.success(emptyList<String>())
            override suspend fun search(query: String, kind: MediaKind?, page: Int, pageSize: Int) = Result.success(CataloguePage(items = emptyList(), page = page, hasMore = false))
            override suspend fun getSuggestions(query: String, limit: Int) = Result.success(emptyList<SearchSuggestion>())
            override suspend fun getDetails(providerId: String, titleId: String, forceRefresh: Boolean) = Result.failure<MediaDetails>(UnsupportedOperationException())
            override suspend fun getSeasons(providerId: String, seriesId: String) = Result.success(seasonsResponse)
            override suspend fun getEpisodes(providerId: String, seriesId: String, seasonNumber: Int) = Result.success(emptyList<EpisodeItem>())
            override suspend fun resolvePlayback(providerId: String, titleId: String, seasonNumber: Int?, episodeNumber: Int?) = Result.failure<PlaybackResolution>(UnsupportedOperationException())
            override suspend fun getSubtitles(providerId: String, titleId: String, seasonNumber: Int?, episodeNumber: Int?) = Result.success(emptyList<SubtitleTrack>())
            override fun observeWatchlist(): Flow<List<MovieTitleSummary>> = flowOf(emptyList())
            override fun observeIsInWatchlist(compositeId: String): Flow<Boolean> = flowOf(false)
            override suspend fun toggleWatchlist(item: MovieTitleSummary) = false
            override fun observeContinueWatching(limit: Int) = flowOf(emptyList<com.laterbox.app.movies.domain.model.WatchHistoryItem>())
            override suspend fun saveWatchProgress(item: MovieTitleSummary, positionMs: Long, durationMs: Long, seasonNumber: Int?, episodeNumber: Int?, episodeLabel: String?) {}
            override suspend fun getSavedPositionMs(providerId: String, titleId: String, seasonNumber: Int?, episodeNumber: Int?) = 0L
            override fun observeRecentSearches(limit: Int): Flow<List<String>> = flowOf(emptyList())
            override suspend fun recordSearchQuery(query: String) {}
            override suspend fun clearSearchHistory() {}
        }

        prefsRepo.setSmartRemindersEnabled(true)
        prefsRepo.setSmartNotificationsMoviesTv(true)
        prefsRepo.setQuietHoursEnabled(false)

        // Only season 1 verified -> does not alert
        assertFalse(engine.maybeNotifyMoviesAndTv(moviesRepository = fakeRepo, nowMs = now, currentHour = 14))

        // Season 2 verified by TVMaze -> alerts honestly
        seasonsResponse = listOf(
            SeasonSummary(seasonNumber = 1, title = "Season 1", episodeCount = 9),
            SeasonSummary(seasonNumber = 2, title = "Season 2", episodeCount = 10)
        )
        assertTrue(engine.maybeNotifyMoviesAndTv(moviesRepository = fakeRepo, nowMs = now, currentHour = 14))
        val events = activityRepo.getRecentEvents(10)
        assertTrue(events.any { it.title.contains("New season of Severance") })
    }

    @Test
    fun maybeNotifyMoviesAndTv_postsWatchlistNudgeForUnwatchedOlderItem() = runTest {
        prefsRepo.recordMoviesNotificationPosted(0L)
        val now = 4_000_000_000_000L
        db.moviesDao().insertWatchlistItem(
            MovieWatchlistEntity(
                compositeId = "ia:his_girl_friday",
                providerId = "ia",
                titleId = "his_girl_friday",
                kind = "movie",
                title = "His Girl Friday",
                posterUrl = null,
                backdropUrl = null,
                releaseYear = 1940,
                rating = 7.9,
                genresCsv = "Comedy",
                summary = null,
                sourceBadge = "OPEN_ARCHIVE",
                isPlayableHint = true,
                addedAt = now - ContextualNotificationEngine.WATCHLIST_NUDGE_AGE_MS - 60_000L
            )
        )

        prefsRepo.setSmartRemindersEnabled(true)
        prefsRepo.setSmartNotificationsMoviesTv(true)
        prefsRepo.setQuietHoursEnabled(false)

        assertTrue(engine.maybeNotifyMoviesAndTv(nowMs = now, currentHour = 14))
        val events = activityRepo.getRecentEvents(10)
        assertTrue(events.any { it.title.contains("His Girl Friday") })
    }
}
