package com.laterbox.app.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.data.local.entity.CollectionEntity
import com.laterbox.app.data.local.entity.SavedLinkEntity
import com.laterbox.app.domain.model.ContentType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
    private lateinit var backupManager: BackupManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        backupManager = BackupManager(database)
    }

    @After
    fun tearDown() {
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
}
