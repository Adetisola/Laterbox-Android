package com.laterbox.app.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.data.local.entity.SavedLinkEntity
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.data.repository.ActivityEventRepository
import com.laterbox.app.data.repository.ActivityEventType
import com.laterbox.app.domain.model.ContentType
import com.laterbox.app.media.data.adapters.ytdlp.YtDlpRuntimeManager
import com.laterbox.app.media.data.db.entity.DownloadedAssetEntity
import com.laterbox.app.media.domain.model.MediaType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaterboxContextEngineTest {

    private lateinit var context: Context
    private lateinit var db: LaterboxDatabase
    private lateinit var prefsRepo: UserPreferencesRepository
    private lateinit var activityRepo: ActivityEventRepository
    private lateinit var engine: LaterboxContextEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        prefsRepo = UserPreferencesRepository(context)
        activityRepo = ActivityEventRepository(db.activityEventDao())
        engine = LaterboxContextEngine(
            database = db,
            activityEventRepository = activityRepo,
            userPreferencesRepository = prefsRepo,
            ytDlpRuntimeManager = YtDlpRuntimeManager(context),
            appContext = context
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun buildContextForPrompt_ranksRelevantLinksWithinBoundsAndRedactsSecrets() = runTest {
        prefsRepo.setGeminiApiKey("AIzaSySuperSecretKey1234567890123456")
        val linkDao = db.savedLinkDao()
        for (i in 1..15) {
            linkDao.insertLink(
                SavedLinkEntity(
                    originalUrl = "https://example.com/item/$i",
                    normalizedUrl = "https://example.com/item/$i",
                    title = if (i == 14) "Kotlin Coroutines Deep Dive" else "General Article $i",
                    domain = "example.com",
                    contentType = ContentType.ARTICLE
                )
            )
        }

        activityRepo.recordEvent(
            type = ActivityEventType.INSPECTION_FAILED,
            title = "Failed in /data/user/0/com.laterbox.app/files/secret",
            detail = "x-goog-api-key=AIzaSyAnotherSecretKey9999999999999"
        )

        val built = engine.buildContextForPrompt("Find my saved kotlin coroutines link")
        assertTrue(built.contextBlock.contains("Kotlin Coroutines Deep Dive"))
        val linkMatches = Regex("\\[linkId=\\d+\\]").findAll(built.contextBlock).count()
        assertTrue("Expected at most 10 links in context, got $linkMatches", linkMatches <= 10)
        assertFalse(built.contextBlock.contains("AIzaSy"))
        assertFalse(built.contextBlock.contains("/data/user/0/"))
    }

    @Test
    fun buildContextForPrompt_reportsLiveTranscriptAndDocumentLibraryCapabilitiesWithoutOutdatedClaims() = runTest {
        prefsRepo.setCustomExportDirectory("content://com.android.externalstorage.documents/tree/primary%3ATranscripts", "Transcripts")
        repeat(2) { idx ->
            db.downloadedAssetDao().insertAsset(
                DownloadedAssetEntity(
                    id = "doc_$idx",
                    savedLinkId = null,
                    sourcePlatform = "YouTube Transcript",
                    originalSourceUrl = "https://www.youtube.com/watch?v=vid$idx",
                    title = "Transcript_$idx.md",
                    mimeType = "text/markdown",
                    mediaType = MediaType.DOCUMENT.name,
                    localPath = "document/Transcript_$idx.md",
                    fileSize = 256L,
                    checksum = "hash_$idx",
                    createdAt = 1700000000000L + idx
                )
            )
        }

        val built = engine.buildContextForPrompt("Can you extract a YouTube video transcript and export PDF?")
        assertTrue(built.contextBlock.contains("Live App Capabilities & Runtime Status:"))
        assertTrue(built.contextBlock.contains("YouTube Transcript Extraction"))
        assertTrue(built.contextBlock.contains("Markdown (.md), Plain Text (.txt), PDF (.pdf)"))
        assertTrue(built.contextBlock.contains("Transcript clipboard copy"))
        assertTrue(built.contextBlock.contains("Downloads Library (Documents): Supported (2 saved documents)"))
        assertTrue(built.contextBlock.contains("Transcripts"))
        assertTrue(built.contextBlock.contains("CONTENT GROUNDING NOTE"))
        assertTrue(built.systemInstruction.contains("OPEN_YOUTUBE_TRANSCRIPTS"))
        assertFalse(built.systemInstruction.contains("does NOT transcribe videos"))
        assertFalse(built.contextBlock.contains("video transcript is not stored locally"))
    }

    @Test
    fun buildContextForPrompt_includesMoviesWatchlistHistoryAndAllAppSections() = runTest {
        db.moviesDao().insertWatchlistItem(
            com.laterbox.app.movies.data.local.entity.MovieWatchlistEntity(
                compositeId = "tvmaze:66",
                providerId = "tvmaze",
                titleId = "66",
                kind = "tv_series",
                title = "Severance",
                posterUrl = null,
                backdropUrl = null,
                releaseYear = 2022,
                rating = 8.7,
                genresCsv = "Drama,Sci-Fi",
                summary = "Macrodata refinement",
                sourceBadge = "LIVE_TVMAZE",
                isPlayableHint = false,
                addedAt = 1700000000000L
            )
        )
        db.moviesDao().upsertWatchHistory(
            com.laterbox.app.movies.data.local.entity.MovieWatchHistoryEntity(
                historyKey = "tvmaze:66:s1:e2",
                compositeId = "tvmaze:66",
                providerId = "tvmaze",
                titleId = "66",
                kind = "tv_series",
                title = "Severance",
                episodeLabel = "S1E2",
                seasonNumber = 1,
                episodeNumber = 2,
                posterUrl = null,
                backdropUrl = null,
                positionMs = 120_000L,
                durationMs = 240_000L,
                lastWatchedAt = 1700000500000L,
                isTrailer = false
            )
        )

        val built = engine.buildContextForPrompt("What episode of Severance was I watching?")
        assertTrue(built.contextBlock.contains("Movies & TV Watchlist"))
        assertTrue(built.contextBlock.contains("Movies & TV Watch History"))
        assertTrue(built.contextBlock.contains("Severance"))
        assertTrue(built.contextBlock.contains("S1E2"))
        assertTrue(built.systemInstruction.contains("OPEN_MOVIE_DETAIL"))
        assertTrue(built.systemInstruction.contains("Movies & TV"))
    }

    @Test
    fun buildContextForPrompt_includesRecentlyViewedViewingRemindersRewatchesAndCollectionMedia() = runTest {
        val collectionId = db.collectionDao().insertCollection(
            com.laterbox.app.data.local.entity.CollectionEntity(name = "Sci-Fi Favorites")
        )
        db.moviesDao().upsertWatchHistory(
            com.laterbox.app.movies.data.local.entity.MovieWatchHistoryEntity(
                historyKey = "archive:dune",
                compositeId = "archive:dune",
                providerId = "archive",
                titleId = "dune",
                kind = "movie",
                title = "Dune Classic",
                episodeLabel = null,
                seasonNumber = null,
                episodeNumber = null,
                posterUrl = null,
                backdropUrl = null,
                positionMs = 180_000L,
                durationMs = 200_000L,
                lastWatchedAt = 1700001000000L,
                isTrailer = false,
                isCompleted = true,
                rewatchCount = 2
            )
        )
        db.moviesDao().upsertRecentlyViewed(
            com.laterbox.app.movies.data.local.entity.MovieRecentlyViewedEntity(
                mediaKey = "tvmaze:101",
                compositeId = "tvmaze:101",
                providerId = "tvmaze",
                titleId = "101",
                kind = "tv_series",
                title = "Andor",
                posterUrl = null,
                backdropUrl = null,
                releaseYear = 2022,
                rating = 8.4,
                genresCsv = "Sci-Fi,Drama",
                sourceBadge = "LIVE_TVMAZE",
                isPlayableHint = false,
                viewedAt = 1700002000000L
            )
        )
        db.moviesDao().upsertViewingReminder(
            com.laterbox.app.movies.data.local.entity.MovieViewingReminderEntity(
                mediaKey = "tvmaze:101",
                compositeId = "tvmaze:101",
                providerId = "tvmaze",
                titleId = "101",
                kind = "tv_series",
                title = "Andor",
                note = "Watch Episode 3 tonight",
                triggerAtMillis = 1800000000000L,
                isDismissed = false,
                createdAt = 1700002000000L,
                updatedAt = 1700002000000L
            )
        )
        db.moviesDao().upsertMediaCollectionCrossRef(
            com.laterbox.app.movies.data.local.entity.MediaCollectionCrossRef(
                mediaKey = "archive:dune",
                collectionId = collectionId,
                compositeId = "archive:dune",
                providerId = "archive",
                titleId = "dune",
                kind = "movie",
                title = "Dune Classic",
                sourceBadge = "LIVE_INTERNET_ARCHIVE",
                addedAt = 1700002500000L
            )
        )
        db.moviesDao().upsertSeriesPreferences(
            com.laterbox.app.movies.data.local.entity.MovieSeriesPreferencesEntity(
                mediaKey = "tvmaze:66",
                remindersMuted = true,
                updatedAt = 1700002600000L
            )
        )

        val built = engine.buildContextForPrompt("What did I recently view or set a reminder for?")
        assertTrue(built.contextBlock.contains("1 collections (1 Movies & TV items)"))
        assertTrue(built.contextBlock.contains("1 Movies & TV reminders"))
        assertTrue(built.contextBlock.contains("1 muted series"))
        assertTrue(built.contextBlock.contains("1 reminders; 1 custom collections"))
        assertTrue(built.contextBlock.contains("rewatches=2"))
        assertTrue(built.contextBlock.contains("Movies & TV Recently Viewed"))
        assertTrue(built.contextBlock.contains("[providerId=tvmaze titleId=101] title=\"Andor\""))
        assertTrue(built.contextBlock.contains("Movies & TV Viewing Reminders"))
        assertTrue(built.contextBlock.contains("Watch Episode 3 tonight"))
    }
}
