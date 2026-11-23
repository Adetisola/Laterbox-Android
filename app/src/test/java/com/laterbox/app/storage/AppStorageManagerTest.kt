package com.laterbox.app.storage

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.media.data.db.entity.DownloadTaskEntity
import com.laterbox.app.media.data.storage.MediaStorageManager
import com.laterbox.app.movies.data.local.entity.MovieCacheEntryEntity
import com.laterbox.app.ui.settings.AppStorageSettingsScreen
import com.laterbox.app.ui.theme.LaterboxTheme
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppStorageManagerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: LaterboxDatabase
    private var fakeClockMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cleanAppStorageDirs()
    }

    @After
    fun tearDown() {
        db.close()
        cleanAppStorageDirs()
    }

    private fun cleanAppStorageDirs() {
        File(context.filesDir, "media").deleteRecursively()
        File(context.filesDir, "download_parts").deleteRecursively()
        File(context.cacheDir, "media_parts").deleteRecursively()
        File(context.cacheDir, "image_cache").deleteRecursively()
        File(context.cacheDir, "movies_media_cache").deleteRecursively()
        File(context.cacheDir, "stale_temp.tmp").delete()
    }

    @Test
    fun inspectAndClearSafeCaches_preservesDownloadsAndPartialFilesWhileClearingDisposableCaches() = runTest {
        val manager = AppStorageManager(
            context = context,
            moviesDao = db.moviesDao(),
            downloadTaskDao = db.downloadTaskDao(),
            storageManager = MediaStorageManager(context),
            nowMs = { fakeClockMs }
        )

        val downloadedFile = File(File(context.filesDir, "media/video").apply { mkdirs() }, "movie.mp4").apply {
            writeBytes(ByteArray(1200))
        }
        val activePartFile = File(File(context.filesDir, "download_parts").apply { mkdirs() }, "active_1.part").apply {
            writeBytes(ByteArray(800))
        }
        val imageCacheFile = File(File(context.cacheDir, "image_cache").apply { mkdirs() }, "poster.img").apply {
            writeBytes(ByteArray(500))
        }
        val streamCacheFile = File(File(context.cacheDir, "movies_media_cache").apply { mkdirs() }, "seg.exo").apply {
            writeBytes(ByteArray(700))
        }
        val staleTemp = File(context.cacheDir, "stale_temp.tmp").apply {
            writeBytes(ByteArray(300))
        }

        db.moviesDao().upsertCacheEntry(
            MovieCacheEntryEntity("expired_key", "moviebox", "{}", fakeClockMs - 10_000L, fakeClockMs - 1_000L)
        )
        db.moviesDao().upsertCacheEntry(
            MovieCacheEntryEntity("fresh_key", "moviebox", "{}", fakeClockMs - 1_000L, fakeClockMs + 60_000L)
        )

        val before = manager.inspectStorage()
        assertEquals(1200L, before.downloadedMediaBytes)
        assertEquals(800L, before.inProgressDownloadsBytes)
        assertEquals(500L, before.imageCacheBytes)
        assertEquals(700L, before.streamPlaybackCacheBytes)
        assertTrue(before.tempAndOtherCacheBytes >= 300L)
        assertEquals(2, before.cachedApiEntryCount)

        val cleanup = manager.clearSafeCaches()
        assertTrue(cleanup.bytesFreed >= 1500L)
        assertEquals(1, cleanup.expiredEntriesPruned)

        // Disposable caches removed
        assertFalse(imageCacheFile.exists())
        assertFalse(streamCacheFile.exists())
        assertFalse(staleTemp.exists())

        // User media & active partial downloads untouched
        assertTrue(downloadedFile.exists())
        assertTrue(activePartFile.exists())
        assertEquals(1200L, cleanup.report.downloadedMediaBytes)
        assertEquals(800L, cleanup.report.inProgressDownloadsBytes)
        assertEquals(0L, cleanup.report.imageCacheBytes)
        assertEquals(0L, cleanup.report.streamPlaybackCacheBytes)
    }

    @Test
    fun runMaintenanceCleanup_migratesLegacyPartsAndSweepsExpiredAndOrphanTemp() = runTest {
        db.downloadTaskDao().insertTask(
            DownloadTaskEntity(
                id = "paused_1",
                savedLinkId = null,
                sourceUrl = "https://cdn.example/video.mp4",
                sourcePlatform = "MovieBox",
                candidateId = "cand_1",
                formatSelector = "720p",
                mediaType = "VIDEO",
                qualityLabel = "720p",
                title = "Paused Movie",
                status = "PAUSED",
                createdAt = fakeClockMs,
                updatedAt = fakeClockMs
            )
        )
        val legacyDir = File(context.cacheDir, "media_parts").apply { mkdirs() }
        File(legacyDir, "paused_1.part").writeBytes(ByteArray(400))
        File(legacyDir, "dead_orphan.part").writeBytes(ByteArray(250))

        val oldTemp = File(context.cacheDir, "stale_temp.tmp").apply {
            writeBytes(ByteArray(300))
            setLastModified(fakeClockMs - (48L * 60L * 60L * 1000L))
        }
        db.moviesDao().upsertCacheEntry(
            MovieCacheEntryEntity("old_entry", "moviebox", "{}", fakeClockMs - 10_000L, fakeClockMs - 5_000L)
        )

        val manager = AppStorageManager(
            context = context,
            moviesDao = db.moviesDao(),
            downloadTaskDao = db.downloadTaskDao(),
            storageManager = MediaStorageManager(context),
            nowMs = { fakeClockMs }
        )

        val result = manager.runMaintenanceCleanup()
        assertEquals(1, result.expiredEntriesPruned)
        assertFalse(oldTemp.exists())
        assertTrue(File(File(context.filesDir, "download_parts"), "paused_1.part").exists())
        assertFalse(File(File(context.filesDir, "download_parts"), "dead_orphan.part").exists())
    }

    @Test
    fun appStorageSettingsScreen_displaysBreakdownAndInvokesClearSafeCache() {
        var cleared = false
        val report = AppStorageReport(
            downloadedMediaBytes = 50L * 1024 * 1024,
            inProgressDownloadsBytes = 5L * 1024 * 1024,
            streamPlaybackCacheBytes = 12L * 1024 * 1024,
            imageCacheBytes = 8L * 1024 * 1024,
            databaseAndMetadataBytes = 2L * 1024 * 1024,
            extractorRuntimesBytes = 0L,
            tempAndOtherCacheBytes = 1L * 1024 * 1024,
            safeClearableBytes = 21L * 1024 * 1024,
            totalUsedBytes = 78L * 1024 * 1024,
            deviceFreeBytes = 2_000L * 1024 * 1024,
            deviceTotalBytes = 10_000L * 1024 * 1024,
            storagePressureHigh = false,
            cachedApiEntryCount = 14
        )

        composeTestRule.setContent {
            LaterboxTheme {
                AppStorageSettingsScreen(
                    report = report,
                    isLoading = false,
                    isClearingSafeCache = false,
                    feedbackMessage = null,
                    onRefresh = {},
                    onClearSafeCache = { cleared = true },
                    onRunMaintenance = {},
                    onDismissFeedback = {},
                    onBackClick = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("app_storage_settings_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("storage_stacked_bar").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("storage_downloaded_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("storage_cache_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("storage_app_data_row").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("clear_safe_cache_button")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertTrue(cleared)
    }

    @Test
    fun threeCategoryReport_reconcilesTotalWithPrefsAndNoDoubleCounting() = runTest {
        val manager = AppStorageManager(
            context = context,
            moviesDao = db.moviesDao(),
            downloadTaskDao = db.downloadTaskDao(),
            storageManager = MediaStorageManager(context),
            nowMs = { fakeClockMs }
        )

        File(File(context.filesDir, "media/video").apply { mkdirs() }, "done.mp4").writeBytes(ByteArray(1_000))
        File(File(context.filesDir, "subtitles").apply { mkdirs() }, "en.srt").writeBytes(ByteArray(250))
        File(File(context.filesDir, "download_parts").apply { mkdirs() }, "partial.part").writeBytes(ByteArray(500))
        File(File(context.cacheDir, "image_cache").apply { mkdirs() }, "img.bin").writeBytes(ByteArray(400))
        File(File(context.cacheDir, "movies_media_cache").apply { mkdirs() }, "seg.exo").writeBytes(ByteArray(600))
        File(File(context.filesDir, "datastore").apply { mkdirs() }, "prefs.preferences_pb").writeBytes(ByteArray(300))

        try {
            val report = manager.inspectStorage()
            assertEquals(1_250L, report.downloadedMediaBytes)
            assertEquals(500L, report.inProgressDownloadsBytes)
            assertEquals(1_750L, report.downloadedBytes)
            assertEquals(1_000L, report.streamPlaybackCacheBytes + report.imageCacheBytes)
            assertEquals(report.cacheBytes, report.safeClearableBytes)
            assertTrue(report.settingsAndPrefsBytes >= 300L)
            assertEquals(
                report.totalUsedBytes,
                report.downloadedBytes + report.cacheBytes + report.appDataBytes
            )
        } finally {
            File(context.filesDir, "subtitles").deleteRecursively()
            File(context.filesDir, "datastore").deleteRecursively()
        }
    }

    @Test
    fun storageBarFractions_handlesZeroAndSmallNonZeroSegmentsProportionally() {
        val zero = com.laterbox.app.ui.settings.computeStorageBarFractions(0L, 0L, 0L)
        assertEquals(0f, zero.downloaded, 0.0001f)
        assertEquals(0f, zero.cache, 0.0001f)
        assertEquals(0f, zero.appData, 0.0001f)

        val skewed = com.laterbox.app.ui.settings.computeStorageBarFractions(
            downloadedBytes = 10_000_000L,
            cacheBytes = 1_000L,
            appDataBytes = 500_000L,
            minVisibleFraction = 0.03f
        )
        assertTrue(skewed.cache >= 0.029f)
        assertTrue(skewed.downloaded > skewed.appData)
        assertTrue(skewed.appData > skewed.cache)
        assertEquals(1f, skewed.downloaded + skewed.cache + skewed.appData, 0.001f)
    }

    @Test
    fun moviesMediaCache_protectsActiveStreamUrlsFromClearCache() {
        val activeUrl = "https://cdn.example/active_stream.mp4"
        val idleUrl = "https://cdn.example/old_stream.mp4"
        com.laterbox.app.movies.data.cache.MoviesMediaCache.markStreamActive(activeUrl)
        try {
            assertTrue(com.laterbox.app.movies.data.cache.MoviesMediaCache.isStreamActive(activeUrl))
            assertFalse(com.laterbox.app.movies.data.cache.MoviesMediaCache.isStreamActive(idleUrl))
        } finally {
            com.laterbox.app.movies.data.cache.MoviesMediaCache.markStreamInactive(activeUrl)
        }
        assertFalse(com.laterbox.app.movies.data.cache.MoviesMediaCache.isStreamActive(activeUrl))
    }

    @Test
    fun dataStorageSettingsScreen_autoTriggersRefreshAndRendersLoadedOverview() {
        var refreshCalls = 0
        val report = AppStorageReport(
            downloadedMediaBytes = 25L * 1024 * 1024,
            inProgressDownloadsBytes = 0L,
            streamPlaybackCacheBytes = 5L * 1024 * 1024,
            imageCacheBytes = 3L * 1024 * 1024,
            databaseAndMetadataBytes = 1L * 1024 * 1024,
            extractorRuntimesBytes = 0L,
            tempAndOtherCacheBytes = 0L,
            safeClearableBytes = 8L * 1024 * 1024,
            totalUsedBytes = 34L * 1024 * 1024,
            deviceFreeBytes = 1_500L * 1024 * 1024,
            deviceTotalBytes = 8_000L * 1024 * 1024,
            storagePressureHigh = false,
            cachedApiEntryCount = 4
        )

        composeTestRule.setContent {
            LaterboxTheme {
                com.laterbox.app.ui.settings.DataStorageSettingsScreen(
                    onExportBackup = { Result.success(0) },
                    onImportBackup = {
                        Result.success(com.laterbox.app.data.backup.BackupRestoreResult(0, 0, 0))
                    },
                    onBackClick = {},
                    storageReport = report,
                    isStorageLoading = false,
                    onRefreshStorage = { refreshCalls++ }
                )
            }
        }

        composeTestRule.waitForIdle()
        assertEquals(1, refreshCalls)
        composeTestRule.onNodeWithText("Export JSON").assertIsDisplayed()
        composeTestRule.onNodeWithText("Import JSON").assertIsDisplayed()
        composeTestRule.onNodeWithText("Device Cloud Auto-Backup").assertDoesNotExist()
        composeTestRule.onNodeWithText("Storage Overview").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("storage_overview_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("storage_downloaded_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("storage_cache_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("storage_app_data_row").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun storageOverview_rendersLoadingFallbackErrorAndZeroStatesCleanly() {
        var retried = 0
        var isLoadingState = true
        var errorState: String? = null
        var reportState: AppStorageReport? = null

        composeTestRule.setContent {
            LaterboxTheme {
                com.laterbox.app.ui.settings.AppStorageSectionContent(
                    report = reportState,
                    isLoading = isLoadingState,
                    isClearingSafeCache = false,
                    feedbackMessage = null,
                    errorMessage = errorState,
                    onRefresh = { retried++ },
                    onClearSafeCache = {},
                    onRunMaintenance = {},
                    onDismissFeedback = {}
                )
            }
        }

        // 1. Loading before first report completes
        composeTestRule.onNodeWithTag("storage_loading_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("Calculating storage usage…").assertIsDisplayed()

        // Verify error, empty-fallback, and zero-bytes branches separately
        composeTestRule.setContent {
            LaterboxTheme {
                com.laterbox.app.ui.settings.AppStorageSectionContent(
                    report = null,
                    isLoading = false,
                    isClearingSafeCache = false,
                    feedbackMessage = null,
                    errorMessage = "Disk read timed out",
                    onRefresh = { retried++ },
                    onClearSafeCache = {},
                    onRunMaintenance = {},
                    onDismissFeedback = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("storage_error_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("Disk read timed out").assertIsDisplayed()
        composeTestRule.onNodeWithTag("storage_retry_button").performClick()
        assertEquals(1, retried)

        composeTestRule.setContent {
            LaterboxTheme {
                com.laterbox.app.ui.settings.AppStorageSectionContent(
                    report = AppStorageReport(
                        downloadedMediaBytes = 0L,
                        inProgressDownloadsBytes = 0L,
                        streamPlaybackCacheBytes = 0L,
                        imageCacheBytes = 0L,
                        databaseAndMetadataBytes = 0L,
                        extractorRuntimesBytes = 0L,
                        tempAndOtherCacheBytes = 0L,
                        safeClearableBytes = 0L,
                        totalUsedBytes = 0L,
                        deviceFreeBytes = 0L,
                        deviceTotalBytes = 0L,
                        storagePressureHigh = false,
                        cachedApiEntryCount = 0
                    ),
                    isLoading = false,
                    isClearingSafeCache = false,
                    feedbackMessage = null,
                    onRefresh = {},
                    onClearSafeCache = {},
                    onRunMaintenance = {},
                    onDismissFeedback = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("storage_overview_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("storage_stacked_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("clear_safe_cache_button").assertIsDisplayed()
    }
}

