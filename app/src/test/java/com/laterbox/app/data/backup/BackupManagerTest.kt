package com.laterbox.app.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.data.local.entity.CollectionEntity
import com.laterbox.app.data.local.entity.IntelligenceSignalEntity
import com.laterbox.app.data.local.entity.IntelligenceSurfaceScope
import com.laterbox.app.data.local.entity.SavedLinkEntity
import com.laterbox.app.data.local.entity.TasteFacetType
import com.laterbox.app.data.local.entity.UserTasteFacetEntity
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.domain.model.ContentType
import com.laterbox.app.movies.data.local.entity.MovieWatchlistEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupManagerTest {

    private lateinit var database: LaterboxDatabase
    private lateinit var userPreferencesRepository: UserPreferencesRepository
    private lateinit var backupManager: BackupManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        userPreferencesRepository = UserPreferencesRepository(context)
        backupManager = BackupManager(database, userPreferencesRepository = userPreferencesRepository)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.runBlocking {
            userPreferencesRepository.setPersonalizationEnabled(true)
            userPreferencesRepository.setAssistantPersonalContextEnabled(false)
        }
        database.close()
    }

    @Test
    fun serializationAndDeserialization_preservesAllFields() {
        val backup = LaterboxBackup(
            version = 1,
            exportedAt = 1700000000000L,
            appName = "Laterbox",
            collections = listOf(
                BackupCollection(name = "Design Inspiration", createdAt = 1699999999000L)
            ),
            links = listOf(
                BackupLink(
                    originalUrl = "https://example.com/article",
                    normalizedUrl = "https://example.com/article",
                    title = "Design Systems in 2026",
                    description = "Comprehensive guide",
                    siteName = "Example Blog",
                    domain = "example.com",
                    faviconUrl = "https://example.com/favicon.ico",
                    previewImageUrl = "https://example.com/preview.png",
                    contentType = ContentType.ARTICLE,
                    personalNote = "Important reference for typography",
                    collectionName = "Design Inspiration",
                    isRead = true,
                    isArchived = false,
                    reminderAt = 1700050000000L,
                    createdAt = 1700000000000L
                )
            )
        )

        val json = backupManager.serializeBackup(backup)
        val deserialized = backupManager.deserializeBackup(json)

        assertEquals(1, deserialized.version)
        assertEquals("Laterbox", deserialized.appName)
        assertEquals(1, deserialized.collections.size)
        assertEquals("Design Inspiration", deserialized.collections[0].name)
        assertEquals(1, deserialized.links.size)

        val link = deserialized.links[0]
        assertEquals("https://example.com/article", link.originalUrl)
        assertEquals("Design Systems in 2026", link.title)
        assertEquals("Important reference for typography", link.personalNote)
        assertEquals("Design Inspiration", link.collectionName)
        assertEquals(ContentType.ARTICLE, link.contentType)
        assertEquals(1700050000000L, link.reminderAt)
        assertTrue(link.isRead)
    }

    @Test
    fun exportAndImport_roundtripAndDeduplication() = runTest {
        val colDao = database.collectionDao()
        val linkDao = database.savedLinkDao()

        // Insert initial data
        val colId = colDao.insertCollection(CollectionEntity(name = "Tech"))
        linkDao.insertLink(
            SavedLinkEntity(
                originalUrl = "https://github.com",
                normalizedUrl = "https://github.com",
                title = "GitHub",
                domain = "github.com",
                collectionId = colId
            )
        )

        // Export to stream
        val out = ByteArrayOutputStream()
        val exportResult = backupManager.exportToJson(out)
        assertTrue(exportResult.isSuccess)
        assertEquals(1, exportResult.getOrNull())

        // Create a second clean in-memory database to simulate restore
        val context = ApplicationProvider.getApplicationContext<Context>()
        val targetDb = Room.inMemoryDatabaseBuilder(context, LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val targetManager = BackupManager(targetDb)

        try {
            // Import into clean database
            val input = ByteArrayInputStream(out.toByteArray())
            val importResult = targetManager.importFromJson(input)
            assertTrue(importResult.isSuccess)

            val stats = importResult.getOrThrow()
            assertEquals(1, stats.restoredLinksCount)
            assertEquals(1, stats.restoredCollectionsCount)
            assertEquals(0, stats.skippedDuplicatesCount)

            val importedInbox = targetDb.savedLinkDao().getInboxLinks().first()
            assertEquals(1, importedInbox.size)
            assertEquals("GitHub", importedInbox[0].link.title)
            assertEquals("Tech", importedInbox[0].collection?.name)

            // Re-importing into the same database should skip duplicate
            val duplicateInput = ByteArrayInputStream(out.toByteArray())
            val secondImport = targetManager.importFromJson(duplicateInput)
            val secondStats = secondImport.getOrThrow()
            assertEquals(0, secondStats.restoredLinksCount)
            assertEquals(1, secondStats.skippedDuplicatesCount)
        } finally {
            targetDb.close()
        }
    }

    @Test
    fun exportAndImport_multiCollectionSupport() = runTest {
        val colDao = database.collectionDao()
        val linkDao = database.savedLinkDao()

        val techId = colDao.insertCollection(CollectionEntity(name = "Tech"))
        val readsId = colDao.insertCollection(CollectionEntity(name = "Reads"))

        val linkId = linkDao.insertLink(
            SavedLinkEntity(
                originalUrl = "https://kotlinlang.org",
                normalizedUrl = "https://kotlinlang.org",
                title = "Kotlin",
                domain = "kotlinlang.org"
            )
        )
        linkDao.insertLinkCollectionCrossRef(
            com.laterbox.app.data.local.entity.LinkCollectionCrossRef(linkId, techId)
        )
        linkDao.insertLinkCollectionCrossRef(
            com.laterbox.app.data.local.entity.LinkCollectionCrossRef(linkId, readsId)
        )

        val out = ByteArrayOutputStream()
        backupManager.exportToJson(out)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val targetDb = Room.inMemoryDatabaseBuilder(context, LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val targetManager = BackupManager(targetDb)
        try {
            val input = ByteArrayInputStream(out.toByteArray())
            val importResult = targetManager.importFromJson(input)
            assertTrue(importResult.isSuccess)

            val imported = targetDb.savedLinkDao().getInboxLinks().first()
            assertEquals(1, imported.size)
            assertEquals(2, imported[0].collections.size)
            val names = imported[0].collections.map { it.name }.toSet()
            assertTrue(names.contains("Tech"))
            assertTrue(names.contains("Reads"))
        } finally {
            targetDb.close()
        }
    }

    @Test
    fun importFromJson_reschedulesAlarmsOnlyForUnreadUnarchivedFutureReminders() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val nowMs = 1_800_000_000_000L
        val futureMs = nowMs + 3_600_000L
        val pastMs = nowMs - 60_000L

        val backup = LaterboxBackup(
            version = 1,
            exportedAt = nowMs,
            links = listOf(
                BackupLink(
                    originalUrl = "https://example.com/future-unread",
                    normalizedUrl = "https://example.com/future-unread",
                    title = "Future Unread",
                    domain = "example.com",
                    isRead = false,
                    isArchived = false,
                    reminderAt = futureMs,
                    createdAt = nowMs - 10_000L
                ),
                BackupLink(
                    originalUrl = "https://example.com/future-read",
                    normalizedUrl = "https://example.com/future-read",
                    title = "Future Read",
                    domain = "example.com",
                    isRead = true,
                    isArchived = false,
                    reminderAt = futureMs,
                    createdAt = nowMs - 10_000L
                ),
                BackupLink(
                    originalUrl = "https://example.com/future-archived",
                    normalizedUrl = "https://example.com/future-archived",
                    title = "Future Archived",
                    domain = "example.com",
                    isRead = false,
                    isArchived = true,
                    reminderAt = futureMs,
                    createdAt = nowMs - 10_000L
                ),
                BackupLink(
                    originalUrl = "https://example.com/past-unread",
                    normalizedUrl = "https://example.com/past-unread",
                    title = "Past Unread",
                    domain = "example.com",
                    isRead = false,
                    isArchived = false,
                    reminderAt = pastMs,
                    createdAt = nowMs - 10_000L
                )
            )
        )

        val scheduled = mutableListOf<Triple<Long, Long, String?>>()
        val realScheduler = com.laterbox.app.reminders.ReminderScheduler(context)
        val managerWithHook = BackupManager(
            database = database,
            reminderScheduler = realScheduler,
            onScheduleReminder = { linkId, triggerAt, title, _ ->
                scheduled.add(Triple(linkId, triggerAt, title))
                realScheduler.scheduleReminder(linkId, triggerAt, title, "https://example.com/future-unread")
            },
            nowMsProvider = { nowMs }
        )

        val json = managerWithHook.serializeBackup(backup)
        val res = managerWithHook.importFromJson(ByteArrayInputStream(json.toByteArray()))
        assertTrue(res.isSuccess)
        assertEquals(4, res.getOrThrow().restoredLinksCount)

        assertEquals(1, scheduled.size)
        assertEquals("Future Unread", scheduled[0].third)
        assertEquals(futureMs, scheduled[0].second)

        val scheduledLinkId = scheduled[0].first
        val pi = android.app.PendingIntent.getBroadcast(
            context,
            com.laterbox.app.reminders.NotificationIds.linkReminderAlarmRequestCode(scheduledLinkId),
            android.content.Intent(context, com.laterbox.app.reminders.ReminderReceiver::class.java).apply {
                action = com.laterbox.app.reminders.ReminderReceiver.ACTION_TRIGGER_REMINDER
            },
            android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        assertNotNull(pi)
        realScheduler.cancelReminder(scheduledLinkId)
    }

    @Test
    fun importFromJson_mergedDuplicateWithMissingLocalReminder_reschedulesFutureReminder() = runTest {
        val nowMs = 1_800_000_000_000L
        val futureMs = nowMs + 7_200_000L
        val linkDao = database.savedLinkDao()

        val unreadId = linkDao.insertLink(
            SavedLinkEntity(
                originalUrl = "https://example.com/dup-unread",
                normalizedUrl = "https://example.com/dup-unread",
                title = "Dup Unread",
                domain = "example.com",
                isRead = false,
                isArchived = false,
                reminderAt = null,
                nudgeDismissed = true
            )
        )
        linkDao.insertLink(
            SavedLinkEntity(
                originalUrl = "https://example.com/dup-read",
                normalizedUrl = "https://example.com/dup-read",
                title = "Dup Read",
                domain = "example.com",
                isRead = true,
                isArchived = false,
                reminderAt = null
            )
        )

        val backup = LaterboxBackup(
            version = 1,
            exportedAt = nowMs,
            links = listOf(
                BackupLink(
                    originalUrl = "https://example.com/dup-unread",
                    normalizedUrl = "https://example.com/dup-unread",
                    title = "Dup Unread",
                    domain = "example.com",
                    isRead = false,
                    isArchived = false,
                    reminderAt = futureMs,
                    createdAt = nowMs
                ),
                BackupLink(
                    originalUrl = "https://example.com/dup-read",
                    normalizedUrl = "https://example.com/dup-read",
                    title = "Dup Read",
                    domain = "example.com",
                    isRead = true,
                    isArchived = false,
                    reminderAt = futureMs,
                    createdAt = nowMs
                )
            )
        )

        val scheduled = mutableListOf<Triple<Long, Long, String?>>()
        val manager = BackupManager(
            database = database,
            onScheduleReminder = { linkId, triggerAt, title, _ ->
                scheduled.add(Triple(linkId, triggerAt, title))
            },
            nowMsProvider = { nowMs }
        )

        val json = manager.serializeBackup(backup)
        val res = manager.importFromJson(ByteArrayInputStream(json.toByteArray()))
        assertTrue(res.isSuccess)
        assertEquals(2, res.getOrThrow().skippedDuplicatesCount)

        assertEquals(1, scheduled.size)
        assertEquals(unreadId, scheduled[0].first)
        assertEquals(futureMs, scheduled[0].second)
        val mergedUnread = linkDao.getEntityById(unreadId)!!
        assertEquals(futureMs, mergedUnread.reminderAt)
        assertEquals(false, mergedUnread.nudgeDismissed)
    }

    @Test
    fun exportAndImportV2_roundTripsFacetsWatchlistAndPrefs_andOmitsEphemeralSignals() = runTest {
        val nowMs = 1_800_000_000_000L
        val futureMs = nowMs + 7_200_000L
        val intelligenceDao = database.intelligenceDao()
        val moviesDao = database.moviesDao()
        val savedLinkDao = database.savedLinkDao()

        userPreferencesRepository.setPersonalizationEnabled(false)
        userPreferencesRepository.setAssistantPersonalContextEnabled(true)

        savedLinkDao.insertLink(
            SavedLinkEntity(
                originalUrl = "https://example.com/v2-link",
                normalizedUrl = "https://example.com/v2-link",
                title = "V2 Reminder Link",
                domain = "example.com",
                reminderAt = futureMs,
                isRead = false,
                isArchived = false,
                createdAt = nowMs
            )
        )

        intelligenceDao.upsertFacet(
            UserTasteFacetEntity(
                facetKey = "topic:design",
                facetType = TasteFacetType.EXPLICIT_LIKE.name,
                surfaceScope = IntelligenceSurfaceScope.LIBRARY.name,
                displayLabel = "Design",
                score = 0.92,
                confidence = 1.0,
                provenance = "Pinned in inspector",
                evidenceSummary = "Pinned Design",
                includeInAssistantContext = true,
                isSuppressedByUser = false,
                firstObservedAtMs = nowMs - 5000L,
                lastReinforcedAtMs = nowMs
            )
        )
        // Ephemeral history signal MUST be omitted from exported JSON
        intelligenceDao.insertSignal(
            IntelligenceSignalEntity(
                signalType = "SEARCH",
                surfaceScope = IntelligenceSurfaceScope.LIBRARY.name,
                targetKey = "secret query",
                topicsCsv = "topic:design",
                createdAtMs = nowMs
            )
        )

        moviesDao.insertWatchlistItem(
            MovieWatchlistEntity(
                compositeId = "moviebox:42",
                providerId = "moviebox",
                titleId = "42",
                kind = "MOVIE",
                title = "Arrival",
                posterUrl = "https://img.example.com/arrival.jpg",
                backdropUrl = null,
                releaseYear = 2016,
                rating = 7.9,
                genresCsv = "Sci-Fi,Drama",
                summary = "Linguist meets visitors",
                sourceBadge = "MOVIEBOX",
                isPlayableHint = true,
                addedAt = nowMs,
                canonicalId = "imdb:tt2543164"
            )
        )

        val outStream = ByteArrayOutputStream()
        assertTrue(backupManager.exportToJson(outStream).isSuccess)
        val exportedJson = outStream.toString(Charsets.UTF_8.name())

        val root = JSONObject(exportedJson)
        assertEquals(LaterboxBackup.CURRENT_BACKUP_VERSION, root.getInt("version"))
        assertFalse(root.has("intelligenceSignals"))
        assertFalse(root.has("intelligence_signals"))
        assertFalse(root.has("movieCacheEntries"))
        assertFalse(root.has("movie_cache_entries"))

        // Reset DB + prefs cleanly and import into fresh in-memory DB
        val context = ApplicationProvider.getApplicationContext<Context>()
        val targetDb = Room.inMemoryDatabaseBuilder(context, LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            userPreferencesRepository.setPersonalizationEnabled(true)
            userPreferencesRepository.setAssistantPersonalContextEnabled(false)

            val scheduled = mutableListOf<Triple<Long, Long, String?>>()
            val targetManager = BackupManager(
                database = targetDb,
                onScheduleReminder = { linkId, triggerAt, title, _ ->
                    scheduled.add(Triple(linkId, triggerAt, title))
                },
                nowMsProvider = { nowMs },
                userPreferencesRepository = userPreferencesRepository
            )

            val restoreResult = targetManager.importFromJson(
                ByteArrayInputStream(exportedJson.toByteArray(Charsets.UTF_8))
            ).getOrThrow()

            assertEquals(1, restoreResult.restoredLinksCount)
            assertEquals(1, restoreResult.restoredFacetsCount)
            assertEquals(1, restoreResult.restoredWatchlistCount)
            assertEquals(1, scheduled.size)
            assertEquals("V2 Reminder Link", scheduled[0].third)

            val restoredFacet = targetDb.intelligenceDao().getFacetByKey("topic:design")
            assertNotNull(restoredFacet)
            assertEquals(TasteFacetType.EXPLICIT_LIKE.name, restoredFacet!!.facetType)
            assertTrue(restoredFacet.includeInAssistantContext)

            val restoredWatchlist = targetDb.moviesDao().getWatchlistSnapshot()
            assertEquals(1, restoredWatchlist.size)
            assertEquals("Arrival", restoredWatchlist[0].title)
            assertEquals("imdb:tt2543164", restoredWatchlist[0].canonicalId)

            val restoredPrefs = userPreferencesRepository.userPreferences.first()
            assertFalse(restoredPrefs.personalizationEnabled)
            assertTrue(restoredPrefs.assistantPersonalContextEnabled)
        } finally {
            targetDb.close()
        }
    }

    @Test
    fun importFromJson_v1BackwardCompat_andMergePrecedenceAndWatchlistDedup() = runTest {
        val nowMs = 1_800_000_000_000L
        val v1Json = """
            {
              "version": 1,
              "appName": "Laterbox",
              "exportedAt": $nowMs,
              "collections": [{"name": "Reading", "createdAt": $nowMs}],
              "links": [{
                "originalUrl": "https://example.com/v1-only",
                "normalizedUrl": "https://example.com/v1-only",
                "title": "Legacy V1 Article",
                "domain": "example.com",
                "collectionName": "Reading",
                "isRead": false,
                "isArchived": false,
                "createdAt": $nowMs
              }]
            }
        """.trimIndent()
        val v1Result = backupManager.importFromJson(ByteArrayInputStream(v1Json.toByteArray())).getOrThrow()
        assertEquals(1, v1Result.restoredLinksCount)
        assertEquals(1, v1Result.restoredCollectionsCount)
        assertEquals(0, v1Result.restoredFacetsCount)
        assertEquals(0, v1Result.restoredWatchlistCount)

        // Seed local facets to test merge precedence + watchlist dedup
        val intelligenceDao = database.intelligenceDao()
        val moviesDao = database.moviesDao()
        intelligenceDao.upsertFacets(
            listOf(
                UserTasteFacetEntity(
                    facetKey = "topic:design",
                    facetType = TasteFacetType.EXPLICIT_LIKE.name,
                    surfaceScope = IntelligenceSurfaceScope.LIBRARY.name,
                    displayLabel = "Design",
                    score = 0.9,
                    confidence = 1.0,
                    provenance = "Local explicit",
                    evidenceSummary = "Local explicit",
                    firstObservedAtMs = 100L,
                    lastReinforcedAtMs = 500L
                ),
                UserTasteFacetEntity(
                    facetKey = "topic:science",
                    facetType = TasteFacetType.ENDURING_INTEREST.name,
                    surfaceScope = IntelligenceSurfaceScope.LIBRARY.name,
                    displayLabel = "Science",
                    score = 0.5,
                    confidence = 0.7,
                    provenance = "Local older inferred",
                    evidenceSummary = "Old",
                    firstObservedAtMs = 100L,
                    lastReinforcedAtMs = 500L
                )
            )
        )
        moviesDao.insertWatchlistItem(
            MovieWatchlistEntity(
                compositeId = "providerA:100",
                providerId = "providerA",
                titleId = "100",
                kind = "MOVIE",
                title = "Existing Movie",
                posterUrl = null,
                backdropUrl = null,
                releaseYear = 2020,
                rating = 8.0,
                genresCsv = "Drama",
                summary = null,
                sourceBadge = "OPEN_ARCHIVE",
                isPlayableHint = true,
                addedAt = 500L,
                canonicalId = "canonical:shared-1"
            )
        )

        val v2MergeBackup = LaterboxBackup(
            version = 2,
            exportedAt = nowMs,
            userTasteFacets = listOf(
                // Newer timestamp but inferred -> MUST NOT overwrite local EXPLICIT_LIKE
                BackupTasteFacet(
                    facetKey = "topic:design",
                    facetType = TasteFacetType.ENDURING_INTEREST.name,
                    surfaceScope = IntelligenceSurfaceScope.LIBRARY.name,
                    displayLabel = "Design",
                    score = 0.4,
                    confidence = 0.6,
                    provenance = "Backup newer inferred",
                    evidenceSummary = "Backup inferred",
                    firstObservedAtMs = 100L,
                    lastReinforcedAtMs = 9999L
                ),
                // Newer inferred -> SHOULD overwrite local older inferred
                BackupTasteFacet(
                    facetKey = "topic:science",
                    facetType = TasteFacetType.ENDURING_INTEREST.name,
                    surfaceScope = IntelligenceSurfaceScope.LIBRARY.name,
                    displayLabel = "Science",
                    score = 0.88,
                    confidence = 0.9,
                    provenance = "Backup newer inferred",
                    evidenceSummary = "Newer",
                    firstObservedAtMs = 100L,
                    lastReinforcedAtMs = 2000L
                )
            ),
            movieWatchlist = listOf(
                // Different compositeId but same canonicalId -> deduplicated (skipped)
                BackupMovieWatchlistItem(
                    compositeId = "providerB:200",
                    providerId = "providerB",
                    titleId = "200",
                    kind = "MOVIE",
                    title = "Existing Movie Duplicate",
                    canonicalId = "canonical:shared-1"
                ),
                // Distinct canonical/composite -> inserted
                BackupMovieWatchlistItem(
                    compositeId = "providerB:201",
                    providerId = "providerB",
                    titleId = "201",
                    kind = "SERIES",
                    title = "New Series",
                    canonicalId = "canonical:unique-2"
                )
            )
        )

        val json = backupManager.serializeBackup(v2MergeBackup)
        val mergeRes = backupManager.importFromJson(ByteArrayInputStream(json.toByteArray())).getOrThrow()
        assertEquals(1, mergeRes.restoredFacetsCount)
        assertEquals(1, mergeRes.restoredWatchlistCount)
        assertEquals("Local explicit", intelligenceDao.getFacetByKey("topic:design")!!.provenance)
        assertEquals("Backup newer inferred", intelligenceDao.getFacetByKey("topic:science")!!.provenance)
        assertEquals(2, moviesDao.getWatchlistSnapshot().size)
    }
}
