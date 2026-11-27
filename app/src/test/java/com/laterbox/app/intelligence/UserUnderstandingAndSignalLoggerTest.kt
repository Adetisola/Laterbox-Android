package com.laterbox.app.intelligence

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.data.local.entity.CollectionEntity
import com.laterbox.app.data.local.entity.IntelligenceSignalEntity
import com.laterbox.app.data.local.entity.IntelligenceSurfaceScope
import com.laterbox.app.data.local.entity.TasteFacetType
import com.laterbox.app.data.local.entity.UserTasteFacetEntity
import com.laterbox.app.data.local.entity.SavedLinkEntity
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.domain.model.ContentType
import com.laterbox.app.movies.data.local.entity.MovieWatchlistEntity
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.reminders.NotificationCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserUnderstandingAndSignalLoggerTest {

    private lateinit var context: Context
    private lateinit var db: LaterboxDatabase
    private lateinit var prefsRepo: UserPreferencesRepository
    private lateinit var repo: UserUnderstandingRepository
    private lateinit var logger: IntelligenceSignalLogger
    private var fakeNowMs: Long = 1_750_000_000_000L
    private var remoteIntelligenceEnabled: Boolean = true

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(context.cacheDir, "intel_test_${System.nanoTime()}.preferences_pb") }
        )
        prefsRepo = UserPreferencesRepository(context, dataStore)
        repo = UserUnderstandingRepository(
            intelligenceDao = db.intelligenceDao(),
            userPreferencesRepository = prefsRepo,
            moviesDao = db.moviesDao(),
            savedLinkDao = db.savedLinkDao(),
            nowMs = { fakeNowMs }
        )
        logger = IntelligenceSignalLogger(
            intelligenceDao = db.intelligenceDao(),
            userUnderstandingRepository = repo,
            userPreferencesRepository = prefsRepo,
            isIntelligenceEnabled = { remoteIntelligenceEnabled },
            nowMs = { fakeNowMs }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun silentBootstrap_derivesSafeFacetsFromExistingLibraryWithoutSensitiveLeaks() = runTest {
        db.moviesDao().insertWatchlistItem(
            MovieWatchlistEntity(
                compositeId = "moviebox:10",
                providerId = "moviebox",
                titleId = "10",
                kind = "movie",
                title = "Dune: Part Two",
                posterUrl = null,
                backdropUrl = null,
                releaseYear = 2024,
                rating = 8.5,
                genresCsv = "Sci-Fi,Adventure",
                summary = "Desert epic",
                sourceBadge = "LIVE_MOVIEBOX",
                isPlayableHint = true,
                addedAt = fakeNowMs
            )
        )
        db.savedLinkDao().insertLink(
            SavedLinkEntity(
                originalUrl = "https://kotlinlang.org/docs/flow.html",
                normalizedUrl = "https://kotlinlang.org/docs/flow.html",
                title = "Kotlin Coroutines Flow Guide",
                domain = "kotlinlang.org",
                contentType = ContentType.ARTICLE,
                isRead = true
            )
        )
        db.savedLinkDao().insertLink(
            SavedLinkEntity(
                originalUrl = "https://clinic.example.com/invoice",
                normalizedUrl = "https://clinic.example.com/invoice",
                title = "Medical Prescription & Hospital Tax Invoice",
                domain = "clinic.example.com",
                contentType = ContentType.ARTICLE,
                isRead = false
            )
        )

        repo.bootstrapFromExistingLibraryIfNeeded()

        val facets = db.intelligenceDao().getAllFacetsSnapshot()
        assertTrue(facets.any { it.facetKey == "genre:sci_fi" && it.surfaceScope == IntelligenceSurfaceScope.MOVIES.name })
        assertTrue(facets.any { it.facetKey == "topic:technology" && it.surfaceScope == IntelligenceSurfaceScope.LIBRARY.name })
        assertFalse(facets.any { it.displayLabel.contains("Medical", ignoreCase = true) || it.displayLabel.contains("Tax", ignoreCase = true) })
        assertTrue(prefsRepo.userPreferencesFlow.first().personalizationBootstrapped)
    }

    @Test
    fun signalLogger_logsSignalsAndHonorsLocalPauseAndRemoteKillSwitch() = runTest {
        val sciFiMovie = MovieTitleSummary(
            id = "42",
            providerId = "moviebox",
            kind = MediaKind.MOVIE,
            title = "Arrival",
            genres = listOf("Sci-Fi", "Drama")
        )
        logger.logWatchlistChange(sciFiMovie, added = true)
        logger.logWatchProgress(sciFiMovie, positionMs = 96_000L, durationMs = 100_000L, isCompleted = true)
        logger.logSearch(IntelligenceSurfaceScope.MOVIES, "sci-fi thriller")
        logger.logNotificationOpened(NotificationCategory.MOVIES_WATCHLIST, "moviebox:42")

        assertEquals(4, db.intelligenceDao().countSignals())
        val facets = db.intelligenceDao().getAllFacetsSnapshot()
        assertTrue(facets.any { it.facetKey == "genre:sci_fi" })

        // General article link MUST NOT cross-signal Movies
        logger.logLinkSaved(
            linkId = 99L,
            url = "https://developer.android.com/jetpack/compose",
            domain = "developer.android.com",
            title = "Jetpack Compose Engineering Guide"
        )
        val signalsAfterArticle = db.intelligenceDao().getRecentSignalsSnapshot(10)
        val articleSignal = signalsAfterArticle.first { it.targetKey == "link:99" }
        assertFalse(articleSignal.isMediaCue)
        assertEquals(IntelligenceSurfaceScope.LIBRARY.name, articleSignal.surfaceScope)

        // Pause locally -> zero new signals
        prefsRepo.setPersonalizationEnabled(false)
        logger.logWatchlistChange(sciFiMovie, added = false)
        assertEquals(5, db.intelligenceDao().countSignals())

        // Re-enable locally, disable remotely -> zero new signals
        prefsRepo.setPersonalizationEnabled(true)
        remoteIntelligenceEnabled = false
        logger.logNotificationDismissed(NotificationCategory.SMART_DIGEST)
        assertEquals(5, db.intelligenceDao().countSignals())
    }

    @Test
    fun dailyMaintenance_prunesSignalsTo500RowsAnd30Days_andDecaysNonExplicitFacets() = runTest {
        val dao = db.intelligenceDao()
        // Insert 520 fresh signals + 1 stale 35-day-old signal
        val staleMs = fakeNowMs - (35L * UserUnderstandingRepository.DAY_MS)
        dao.insertSignal(
            IntelligenceSignalEntity(
                signalType = IntelligenceSignalLogger.SIGNAL_SEARCH,
                surfaceScope = IntelligenceSurfaceScope.LIBRARY.name,
                targetKey = "stale",
                topicsCsv = "",
                isMediaCue = false,
                strength = 0.5,
                createdAtMs = staleMs
            )
        )
        repeat(520) { idx ->
            dao.insertSignal(
                IntelligenceSignalEntity(
                    signalType = IntelligenceSignalLogger.SIGNAL_LINK_READ,
                    surfaceScope = IntelligenceSurfaceScope.LIBRARY.name,
                    targetKey = "link:$idx",
                    topicsCsv = "topic:technology",
                    isMediaCue = false,
                    strength = 0.8,
                    createdAtMs = fakeNowMs - (idx * 1000L)
                )
            )
        }

        // Insert an expired short-term facet, a very old weak enduring facet, and an explicit like
        dao.upsertFacets(
            listOf(
                UserTasteFacetEntity(
                    facetKey = "genre:horror",
                    facetType = TasteFacetType.SHORT_TERM_INTENT.name,
                    surfaceScope = IntelligenceSurfaceScope.MOVIES.name,
                    displayLabel = "Horror",
                    score = 0.4,
                    confidence = 0.6,
                    provenance = "Recent search",
                    evidenceSummary = "Search",
                    firstObservedAtMs = fakeNowMs - 20L * UserUnderstandingRepository.DAY_MS,
                    lastReinforcedAtMs = fakeNowMs - 20L * UserUnderstandingRepository.DAY_MS,
                    expiresAtMs = fakeNowMs - 1_000L
                ),
                UserTasteFacetEntity(
                    facetKey = "topic:gaming",
                    facetType = TasteFacetType.ENDURING_INTEREST.name,
                    surfaceScope = IntelligenceSurfaceScope.LIBRARY.name,
                    displayLabel = "Gaming",
                    score = 0.08,
                    confidence = 0.5,
                    provenance = "Old save",
                    evidenceSummary = "Old",
                    firstObservedAtMs = fakeNowMs - 90L * UserUnderstandingRepository.DAY_MS,
                    lastReinforcedAtMs = fakeNowMs - 90L * UserUnderstandingRepository.DAY_MS,
                    expiresAtMs = null
                ),
                UserTasteFacetEntity(
                    facetKey = "genre:sci_fi",
                    facetType = TasteFacetType.EXPLICIT_LIKE.name,
                    surfaceScope = IntelligenceSurfaceScope.MOVIES.name,
                    displayLabel = "Sci-Fi",
                    score = 0.95,
                    confidence = 1.0,
                    provenance = "Liked by you",
                    evidenceSummary = "Pinned",
                    includeInAssistantContext = true,
                    isSuppressedByUser = false,
                    firstObservedAtMs = fakeNowMs - 120L * UserUnderstandingRepository.DAY_MS,
                    lastReinforcedAtMs = fakeNowMs - 120L * UserUnderstandingRepository.DAY_MS,
                    expiresAtMs = null
                )
            )
        )

        val stats = repo.runDailyMaintenance(fakeNowMs)
        assertTrue(stats.expiredFacetsRemoved >= 1)
        assertTrue(stats.decayedFacetsRemoved >= 1)
        assertEquals(500, dao.countSignals())
        val remainingSignals = dao.getRecentSignalsSnapshot(500)
        assertFalse(remainingSignals.any { it.targetKey == "stale" })

        val remainingFacets = dao.getAllFacetsSnapshot()
        assertEquals(listOf("genre:sci_fi"), remainingFacets.map { it.facetKey })
    }

    @Test
    fun resetModel_wipesOnlyIntelligenceTables_andLeavesLibraryIntact() = runTest {
        val colId = db.collectionDao().insertCollection(CollectionEntity(name = "Private Vault"))
        db.savedLinkDao().insertLink(
            SavedLinkEntity(
                originalUrl = "https://example.com/keep",
                normalizedUrl = "https://example.com/keep",
                title = "Keep Me",
                domain = "example.com",
                collectionId = colId
            )
        )
        repo.setExplicitPreference("genre:drama", "Drama", IntelligenceSurfaceScope.MOVIES, liked = true)
        logger.logSearch(IntelligenceSurfaceScope.LIBRARY, "kotlin")
        assertTrue(db.intelligenceDao().countFacets() > 0)
        assertTrue(db.intelligenceDao().countSignals() > 0)

        repo.resetModel()

        assertEquals(0, db.intelligenceDao().countFacets())
        assertEquals(0, db.intelligenceDao().countSignals())
        assertEquals(1, db.savedLinkDao().getInboxCount())
        assertEquals(1, db.collectionDao().getCollections().first().size)
    }

    @Test
    fun assistantSummary_isGatedByAssistantPersonalContextToggleAndRespectsSuppression() = runTest {
        repo.setExplicitPreference("genre:sci_fi", "Sci-Fi", IntelligenceSurfaceScope.MOVIES, liked = true, includeInAssistant = true)
        repo.setExplicitPreference("genre:horror", "Horror", IntelligenceSurfaceScope.MOVIES, liked = false, includeInAssistant = true)
        repo.reinforceFacet(
            candidate = ExtractedFacetCandidate("topic:technology", "Technology", IntelligenceSurfaceScope.LIBRARY),
            delta = 0.6,
            provenance = "Saved tech links",
            evidenceSummary = "Tech",
            timestampMs = fakeNowMs
        )

        // Default assistantPersonalContextEnabled == false -> empty summary
        assertEquals("", repo.buildPrivacySafeAssistantSummary(fakeNowMs))

        // Enable assistantPersonalContextEnabled -> includes only includeInAssistantContext=true non-suppressed facets
        prefsRepo.setAssistantPersonalContextEnabled(true)
        val summary = repo.buildPrivacySafeAssistantSummary(fakeNowMs)
        assertTrue(summary.contains("Sci-Fi"))
        assertFalse(summary.contains("Technology")) // includeInAssistantContext defaults to false!

        // Suppress Sci-Fi -> excluded
        repo.toggleSuppressFacet("genre:sci_fi", true)
        assertEquals("", repo.buildPrivacySafeAssistantSummary(fakeNowMs))
    }
}
