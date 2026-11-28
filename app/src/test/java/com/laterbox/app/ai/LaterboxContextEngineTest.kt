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

    @Test
    fun buildContextForPrompt_propagatesRemoteConfigFlagsAndMaintenanceState() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.enqueue(
            okhttp3.mockwebserver.MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "schemaVersion": 1,
                      "revision": 9,
                      "publishedAt": "2026-12-01T00:00:00Z",
                      "expiresAt": "2099-01-01T00:00:00Z",
                      "general": { "minRefreshIntervalSeconds": 60, "staleCacheMaxAgeSeconds": 86400 },
                      "versionPolicy": { "minimumSupportedVersionCode": 1, "recommendedVersionCode": 84 },
                      "maintenance": {
                        "enabled": true,
                        "mode": "partial",
                        "title": "Catalogue Maintenance",
                        "message": "Online streaming is paused for upgrades.",
                        "allowLocalOfflineAccess": true
                      },
                      "announcements": [],
                      "features": {
                        "movies_tv_enabled": true,
                        "provider_moviebox_enabled": false,
                        "provider_internet_archive_enabled": true,
                        "provider_tvmaze_enabled": false,
                        "movies_recommendations_enabled": false,
                        "ai_assistant_enabled": true,
                        "smart_reminders_enabled": false,
                        "player_intro_skip_enabled": false
                      },
                      "ui": { "components": [] }
                    }
                    """.trimIndent()
                )
        )
        server.start()
        try {
            val baseUrl = server.url("/").toString().trimEnd('/')
            val dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                produceFile = { java.io.File(context.cacheDir, "rc_ctx_test_${System.nanoTime()}.preferences_pb") }
            )
            val cache = com.laterbox.app.remoteconfig.RemoteConfigCache(dataStore) { baseUrl }
            val client = com.laterbox.app.remoteconfig.RemoteConfigClient(
                okHttpClient = okhttp3.OkHttpClient(),
                baseUrlProvider = { baseUrl },
                appCheckTokenProvider = { null },
                clientVersionCode = 84,
                clientVersionName = "1.0.84"
            )
            val repo = com.laterbox.app.remoteconfig.RemoteConfigRepository(
                cache = cache,
                client = client,
                clientVersionCode = 84
            )
            val rcManager = com.laterbox.app.remoteconfig.RemoteConfigManager(repository = repo)
            rcManager.refreshNow()

            val wiredEngine = LaterboxContextEngine(
                database = db,
                activityEventRepository = activityRepo,
                userPreferencesRepository = prefsRepo,
                ytDlpRuntimeManager = YtDlpRuntimeManager(context),
                appContext = context,
                remoteConfigManager = rcManager
            )

            val built = wiredEngine.buildContextForPrompt("Why can't I browse MovieBox right now?")
            val snap = built.capabilitySnapshot
            assertTrue(snap.onlineServicesPaused)
            assertFalse(snap.providerMovieboxEnabled)
            assertTrue(snap.providerInternetArchiveEnabled)
            assertFalse(snap.providerTvmazeEnabled)
            assertFalse(snap.moviesRecommendationsEnabled)
            assertFalse(snap.playerIntroSkipEnabled)
            assertFalse(snap.smartRemindersEnabled)

            assertTrue(built.contextBlock.contains("Maintenance notice: Online streaming is paused for upgrades."))
            assertTrue(built.contextBlock.contains("Online services: Paused (maintenance)"))
            assertTrue(built.contextBlock.contains("MovieBox=off, Internet Archive=on, TVMaze=off"))
            assertTrue(built.contextBlock.contains("Evaluated Capability States (Manifest v3):"))
            assertTrue(built.contextBlock.contains("movies.discovery_and_library): DISABLED"))
            assertTrue(built.contextBlock.contains("downloads.media_extraction): CONDITIONALLY_AVAILABLE"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun buildContextForPrompt_doesNotLeakUnrelatedCollectionNames_andIncludesMatchedOrExplicitCollections() = runTest {
        val hospitalColId = db.collectionDao().insertCollection(
            com.laterbox.app.data.local.entity.CollectionEntity(name = "Hospital Bills")
        )
        val breakupColId = db.collectionDao().insertCollection(
            com.laterbox.app.data.local.entity.CollectionEntity(name = "Breakup Notes")
        )
        val linkId1 = db.savedLinkDao().insertLink(
            SavedLinkEntity(
                originalUrl = "https://example.com/receipt",
                normalizedUrl = "https://example.com/receipt",
                title = "Clinic Invoice 2026",
                domain = "example.com",
                contentType = ContentType.ARTICLE,
                collectionId = hospitalColId
            )
        )
        db.savedLinkDao().insertLinkCollectionCrossRef(
            com.laterbox.app.data.local.entity.LinkCollectionCrossRef(linkId = linkId1, collectionId = hospitalColId)
        )
        val linkId2 = db.savedLinkDao().insertLink(
            SavedLinkEntity(
                originalUrl = "https://example.com/journal",
                normalizedUrl = "https://example.com/journal",
                title = "Personal Reflection",
                domain = "example.com",
                contentType = ContentType.ARTICLE,
                collectionId = breakupColId
            )
        )
        db.savedLinkDao().insertLinkCollectionCrossRef(
            com.laterbox.app.data.local.entity.LinkCollectionCrossRef(linkId = linkId2, collectionId = breakupColId)
        )

        // 1. Unrelated prompt: numeric count stays in Library summary, ZERO raw collection names anywhere
        val unrelated = engine.buildContextForPrompt("What movies are trending on my watchlist?")
        assertTrue(unrelated.contextBlock.contains("2 collections"))
        assertFalse(unrelated.contextBlock.contains("Hospital Bills"))
        assertFalse(unrelated.contextBlock.contains("Breakup Notes"))
        assertFalse(unrelated.contextBlock.contains("Collections (max"))
        assertFalse(unrelated.contextBlock.contains("collections=["))

        // 2. Keyword-matched prompt: includes ONLY matching collection
        val matched = engine.buildContextForPrompt("Show me my hospital receipts")
        assertTrue(matched.contextBlock.contains("Hospital Bills"))
        assertFalse(matched.contextBlock.contains("Breakup Notes"))

        // 3. Explicit collections query: includes requested collections
        val explicit = engine.buildContextForPrompt("List my collections")
        assertTrue(explicit.contextBlock.contains("Hospital Bills"))
        assertTrue(explicit.contextBlock.contains("Breakup Notes"))
    }

    @Test
    fun buildContextForPrompt_rejectsSubstringAndFeatureHelpCollectionLeaks() = runTest {
        val sensitiveCollections = listOf(
            "Hospital Bills",
            "Breakup Notes",
            "Private Art",
            "Tax Returns",
            "Shower Thoughts",
            "Othello"
        )
        sensitiveCollections.forEach { name ->
            db.collectionDao().insertCollection(
                com.laterbox.app.data.local.entity.CollectionEntity(name = name)
            )
        }

        val benignPrompts = listOf(
            "I am not sure what to watch",
            "I feel ill today",
            "Explain hospitality trends",
            "Recommend an article to read",
            "Fix syntax error in SQL",
            "What TV show should I watch?",
            "Hello",
            "How do collections work in Laterbox?",
            "How do my collections work?",
            "Show collections settings",
            "Where are my collections settings?"
        )
        for (prompt in benignPrompts) {
            val built = engine.buildContextForPrompt(prompt)
            for (secret in sensitiveCollections) {
                assertFalse(
                    "Prompt '$prompt' leaked collection '$secret'",
                    built.contextBlock.contains(secret)
                )
            }
            assertFalse(
                "Prompt '$prompt' unexpectedly rendered Collections section",
                built.contextBlock.contains("Collections (max")
            )
        }

        val directMatch = engine.buildContextForPrompt("Show my Hospital Bills")
        assertTrue(directMatch.contextBlock.contains("Hospital Bills"))
        assertFalse(directMatch.contextBlock.contains("Breakup Notes"))

        val explicitList = engine.buildContextForPrompt("List my collections")
        sensitiveCollections.forEach { secret ->
            assertTrue(
                "Explicit list query missing '$secret'",
                explicitList.contextBlock.contains(secret)
            )
        }
    }

    @Test
    fun buildContextForPrompt_wiresAppHealthCheckerSummary() = runTest {
        val runtimeManager = YtDlpRuntimeManager(context)
        val checker = com.laterbox.app.diagnostics.AppHealthChecker(
            appContext = context,
            database = db,
            runtimeManager = runtimeManager,
            userPreferencesRepository = prefsRepo
        )
        // Insert failed download task so health checker surfaces a real issue
        db.downloadTaskDao().insertTask(
            com.laterbox.app.media.data.db.entity.DownloadTaskEntity(
                id = "t-health-1",
                savedLinkId = null,
                sourceUrl = "https://example.com/v.mp4",
                sourcePlatform = "Direct",
                candidateId = null,
                formatSelector = null,
                mediaType = MediaType.VIDEO.name,
                qualityLabel = null,
                title = "Broken Video",
                status = com.laterbox.app.media.domain.model.DownloadState.FAILED.name,
                errorMessage = "Network error",
                createdAt = 1700000000000L,
                updatedAt = 1700000000000L
            )
        )
        val healthEngine = LaterboxContextEngine(
            database = db,
            activityEventRepository = activityRepo,
            userPreferencesRepository = prefsRepo,
            appHealthChecker = checker,
            ytDlpRuntimeManager = runtimeManager,
            appContext = context
        )

        val built = healthEngine.buildContextForPrompt("Check my app health")
        assertTrue(built.contextBlock.contains("App Health Summary:"))
        assertTrue(built.contextBlock.contains("failedDownloads=1"))
        assertTrue(built.contextBlock.contains("media download failed"))
    }

    @Test
    fun buildContextForPrompt_scopesCapabilitiesAndGatesTasteProfileOnOptInAndKillSwitch() = runTest {
        db.collectionDao().insertCollection(
            com.laterbox.app.data.local.entity.CollectionEntity(
                id = 55L,
                name = "Private Secret Watchlist"
            )
        )
        val userUnderstandingRepo = com.laterbox.app.intelligence.UserUnderstandingRepository(
            intelligenceDao = db.intelligenceDao(),
            userPreferencesRepository = prefsRepo,
            moviesDao = db.moviesDao(),
            savedLinkDao = db.savedLinkDao()
        )
        userUnderstandingRepo.setExplicitPreference(
            facetKey = "genre:sci_fi",
            displayLabel = "Sci-Fi",
            surfaceScope = com.laterbox.app.data.local.entity.IntelligenceSurfaceScope.MOVIES,
            liked = true,
            includeInAssistant = true
        )

        val rcServer = okhttp3.mockwebserver.MockWebServer()
        rcServer.enqueue(
            okhttp3.mockwebserver.MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "schemaVersion": 1,
                      "revision": 2,
                      "publishedAt": "2026-11-27T10:00:00Z",
                      "maintenance": { "enabled": false, "mode": "none" },
                      "features": { "experience_intelligence_enabled": true }
                    }
                    """.trimIndent()
                )
        )
        rcServer.start()
        try {
            val baseUrl = rcServer.url("/").toString().trimEnd('/')
            val dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                produceFile = { java.io.File(context.cacheDir, "rc_v2_ctx_${System.nanoTime()}.preferences_pb") }
            )
            val rcManager = com.laterbox.app.remoteconfig.RemoteConfigManager(
                repository = com.laterbox.app.remoteconfig.RemoteConfigRepository(
                    cache = com.laterbox.app.remoteconfig.RemoteConfigCache(dataStore) { baseUrl },
                    client = com.laterbox.app.remoteconfig.RemoteConfigClient(
                        okHttpClient = okhttp3.OkHttpClient(),
                        baseUrlProvider = { baseUrl },
                        appCheckTokenProvider = { null },
                        clientVersionCode = 84,
                        clientVersionName = "1.0.84"
                    ),
                    clientVersionCode = 84
                )
            )
            rcManager.refreshNow()

            val v2Engine = LaterboxContextEngine(
                database = db,
                activityEventRepository = activityRepo,
                userPreferencesRepository = prefsRepo,
                appContext = context,
                remoteConfigManager = rcManager,
                userUnderstandingRepository = userUnderstandingRepo
            )

            // 1. Default opt-out: assistantPersonalContextEnabled=false -> taste profile NOT injected, raw collection hidden
            prefsRepo.setAssistantPersonalContextEnabled(false)
            val optOutCtx = v2Engine.buildContextForPrompt("Suggest something to watch tonight")
            assertFalse(optOutCtx.contextBlock.contains("On-Device Taste Profile:"))
            assertFalse(optOutCtx.contextBlock.contains("Private Secret Watchlist"))
            // Narrow prompt omits unrelated SUPPORTED capabilities
            assertTrue(optOutCtx.contextBlock.contains("additional capabilities are SUPPORTED and available"))

            // 2. Opt-in enabled -> bounded safe facet appears, raw collection title still excluded
            prefsRepo.setAssistantPersonalContextEnabled(true)
            val optInCtx = v2Engine.buildContextForPrompt("What can you help me watch?")
            assertTrue(optInCtx.contextBlock.contains("On-Device Taste Profile:"))
            assertTrue(optInCtx.contextBlock.contains("Sci-Fi"))
            assertFalse(optInCtx.contextBlock.contains("Private Secret Watchlist"))
            assertFalse(optInCtx.contextBlock.contains("additional capabilities are SUPPORTED"))
        } finally {
            rcServer.shutdown()
        }
    }
}
