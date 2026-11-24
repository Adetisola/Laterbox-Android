package com.laterbox.app.media.data.download

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.media.data.db.entity.DownloadTaskEntity
import com.laterbox.app.media.data.repository.MediaRepositoryImpl
import com.laterbox.app.media.data.storage.MediaStorageManager
import com.laterbox.app.media.domain.model.DownloadState
import com.laterbox.app.media.domain.model.FailureCode
import com.laterbox.app.media.domain.model.MediaType
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import org.robolectric.annotation.Config
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowStatFs
import java.time.Duration
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadRetrySchedulingTest {

    private lateinit var context: Context
    private lateinit var database: LaterboxDatabase
    private lateinit var storageManager: MediaStorageManager
    private lateinit var server: MockWebServer
    private var fakeNowMs: Long = 1_760_000_000_000L

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.mkdirs()
        ShadowStatFs.registerStats(context.filesDir.path, 1_000_000, 500_000, 500_000)
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        WorkManager.getInstance(context).cancelAllWork().result.get()
        WorkManager.getInstance(context).pruneWork().result.get()

        database = LaterboxDatabase.getInstance(context)
        storageManager = MediaStorageManager(context)
        server = MockWebServer()
        server.start()
        fakeNowMs = 1_760_000_000_000L
        DownloadWorker.clockMsProvider = { fakeNowMs }
    }

    @After
    fun teardown() {
        DownloadWorker.clockMsProvider = { System.currentTimeMillis() }
        ShadowStatFs.reset()
        runCatching { WorkManager.getInstance(context).cancelAllWork().result.get() }
        runBlocking {
            withContext(Dispatchers.IO) {
                val ids = database.downloadTaskDao().getActiveAndPausedTaskIds()
                for (id in ids) {
                    storageManager.deletePartFile(id)
                }
                database.clearAllTables()
            }
        }
        server.shutdown()
    }

    private fun seedTask(
        id: String,
        url: String,
        downloadedBytes: Long = 0L,
        totalBytes: Long? = null,
        etag: String? = null,
        retryCount: Int = 0,
        nextRetryAtMs: Long? = null,
        status: DownloadState = DownloadState.QUEUED,
        allowCellularOverride: Boolean = true,
        partialText: String? = null
    ): DownloadTaskEntity {
        val partFile = storageManager.getPartFile(id)
        if (partialText != null) {
            partFile.parentFile?.mkdirs()
            partFile.writeText(partialText)
        } else {
            partFile.delete()
        }
        val entity = DownloadTaskEntity(
            id = id,
            savedLinkId = null,
            sourceUrl = url,
            sourcePlatform = "MovieBox",
            candidateId = "c_$id",
            formatSelector = null,
            mediaType = MediaType.VIDEO.name,
            qualityLabel = "720p · MP4",
            title = "Sample Video",
            status = status.name,
            progress = if (totalBytes != null && totalBytes > 0L) ((downloadedBytes * 100L) / totalBytes).toInt() else 0,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            allowCellularOverride = allowCellularOverride,
            etag = etag,
            retryCount = retryCount,
            nextRetryAtMs = nextRetryAtMs,
            createdAt = fakeNowMs - 10_000L,
            updatedAt = fakeNowMs - 10_000L
        )
        runBlocking {
            withContext(Dispatchers.IO) {
                database.downloadTaskDao().insertTask(entity)
            }
        }
        return entity
    }

    @Test
    fun http429DeltaSeconds_schedulesExactDelayedWorkViaWorkManagerWithoutSelfCancelling() = runBlocking {
        val url = server.url("/video_429_delta.mp4").toString()
        val taskId = "task_wm_429_delta"
        seedTask(
            id = taskId,
            url = url,
            downloadedBytes = 10L,
            totalBytes = 100L,
            etag = "\"etag-resume\"",
            retryCount = 0,
            partialText = "0123456789"
        )

        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "45")
        )

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_TASK_ID to taskId))
            .build()
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniqueWork(DownloadWorker.uniqueWorkName(taskId), ExistingWorkPolicy.REPLACE, request).result.get()

        // Pump the Robolectric main looper so WorkManager's MainThreadExecutor settles the worker result.
        var finishedFirstWorker = false
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            val infos = wm.getWorkInfosForUniqueWork(DownloadWorker.uniqueWorkName(taskId)).get()
            val firstInfo = infos.firstOrNull { it.id == request.id }
            if (firstInfo != null && firstInfo.state == WorkInfo.State.SUCCEEDED) {
                finishedFirstWorker = true
                break
            }
            Thread.sleep(20L)
        }
        assertTrue("First DownloadWorker should succeed cleanly without self-cancellation", finishedFirstWorker)

        val infos = wm.getWorkInfosForUniqueWork(DownloadWorker.uniqueWorkName(taskId)).get()
        val pendingInfos = infos.filter { !it.state.isFinished }
        assertEquals("Expected single pending delayed retry work item", 1, pendingInfos.size)
        val pendingInfo = pendingInfos.single()
        assertEquals(WorkInfo.State.ENQUEUED, pendingInfo.state)

        val spec = WorkManagerImpl.getInstance(context).workDatabase.workSpecDao()
            .getWorkSpec(pendingInfo.id.toString())
        assertNotNull(spec)
        assertEquals(45_000L, spec!!.initialDelay)

        val persisted = database.downloadTaskDao().getEntityById(taskId)!!
        assertEquals(DownloadState.QUEUED.name, persisted.status)
        assertEquals(FailureCode.RATE_LIMITED.name, persisted.errorCode)
        assertEquals(1, persisted.retryCount)
        assertEquals(fakeNowMs + 45_000L, persisted.nextRetryAtMs)
        assertEquals("\"etag-resume\"", persisted.etag)
        assertEquals(10L, persisted.downloadedBytes)
        assertEquals("0123456789", storageManager.getPartFile(taskId).readText())
    }

    @Test
    fun http429HttpDateAndExcessiveRetryAfter_respectsClockOffsetAndMaxCap() = runBlocking {
        val url = server.url("/video_429_date.mp4").toString()
        val taskId = "task_wm_429_date"
        seedTask(id = taskId, url = url)

        // Server Date is Tue, 24 Nov 2026 12:00:00 GMT and Retry-After is +120s
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Date", "Tue, 24 Nov 2026 12:00:00 GMT")
                .addHeader("Retry-After", "Tue, 24 Nov 2026 12:02:00 GMT")
        )

        val worker = TestListenableWorkerBuilder<DownloadWorker>(context)
            .setInputData(workDataOf(DownloadWorker.KEY_TASK_ID to taskId))
            .build()
        val result = worker.doWork()
        assertTrue(result is androidx.work.ListenableWorker.Result.Success)

        val wm = WorkManager.getInstance(context)
        val pending = wm.getWorkInfosForUniqueWork(DownloadWorker.uniqueWorkName(taskId)).get()
            .filter { !it.state.isFinished }
        assertEquals(1, pending.size)
        val spec = WorkManagerImpl.getInstance(context).workDatabase.workSpecDao()
            .getWorkSpec(pending.single().id.toString())!!
        assertEquals(120_000L, spec.initialDelay)
        assertEquals(fakeNowMs + 120_000L, database.downloadTaskDao().getEntityById(taskId)!!.nextRetryAtMs)

        // Clear work & test excessive Retry-After (> 300s background cap)
        wm.cancelUniqueWork(DownloadWorker.uniqueWorkName(taskId)).result.get()
        wm.pruneWork().result.get()
        val cappedTaskId = "task_wm_429_cap"
        seedTask(id = cappedTaskId, url = server.url("/video_429_cap.mp4").toString())
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "900")
        )
        val cappedWorker = TestListenableWorkerBuilder<DownloadWorker>(context)
            .setInputData(workDataOf(DownloadWorker.KEY_TASK_ID to cappedTaskId))
            .build()
        val cappedRes = cappedWorker.doWork()
        assertTrue(cappedRes is androidx.work.ListenableWorker.Result.Success)

        val cappedPending = wm.getWorkInfosForUniqueWork(DownloadWorker.uniqueWorkName(cappedTaskId)).get()
            .filter { !it.state.isFinished }
        assertEquals(1, cappedPending.size)
        val cappedSpec = WorkManagerImpl.getInstance(context).workDatabase.workSpecDao()
            .getWorkSpec(cappedPending.single().id.toString())!!
        assertEquals(300_000L, cappedSpec.initialDelay)
    }

    @Test
    fun earlyWakeupBeforeNextRetryAtMs_reschedulesWithoutHittingServerOrIncrementingRetryCount() = runBlocking {
        val url = server.url("/video_early_guard.mp4").toString()
        val taskId = "task_early_wakeup"
        seedTask(
            id = taskId,
            url = url,
            downloadedBytes = 12L,
            totalBytes = 100L,
            etag = "\"keep-etag\"",
            retryCount = 1,
            nextRetryAtMs = fakeNowMs + 60_000L,
            partialText = "PartialBytes"
        )

        val worker = TestListenableWorkerBuilder<DownloadWorker>(context)
            .setInputData(workDataOf(DownloadWorker.KEY_TASK_ID to taskId))
            .build()
        val result = worker.doWork()
        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals(0, server.requestCount)

        val persisted = database.downloadTaskDao().getEntityById(taskId)!!
        assertEquals(DownloadState.QUEUED.name, persisted.status)
        assertEquals(1, persisted.retryCount)
        assertEquals(fakeNowMs + 60_000L, persisted.nextRetryAtMs)
        assertEquals("\"keep-etag\"", persisted.etag)
        assertEquals("PartialBytes", storageManager.getPartFile(taskId).readText())

        val pending = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(DownloadWorker.uniqueWorkName(taskId)).get()
            .filter { !it.state.isFinished }
        assertEquals(1, pending.size)
        val spec = WorkManagerImpl.getInstance(context).workDatabase.workSpecDao()
            .getWorkSpec(pending.single().id.toString())!!
        assertEquals(60_000L, spec.initialDelay)
    }

    @Test
    fun recoverInterruptedTasks_preservesRemainingRetryDelayAcrossRestartAndAvoidsDuplicates() = runBlocking {
        val taskId = "task_restart_preserve"
        val futureRetryAt = fakeNowMs + 90_000L
        seedTask(
            id = taskId,
            url = server.url("/restart_video.mp4").toString(),
            retryCount = 1,
            nextRetryAtMs = futureRetryAt
        )

        val repo = MediaRepositoryImpl(
            context = context,
            assetDao = database.downloadedAssetDao(),
            taskDao = database.downloadTaskDao(),
            storageManager = storageManager,
            clockMs = { fakeNowMs }
        )

        // Advance simulated clock by 15s -> 75s remaining; no WorkManager work exists yet (simulating lost queue).
        fakeNowMs += 15_000L
        repo.recoverInterruptedTasks()

        val wm = WorkManager.getInstance(context)
        val firstPending = wm.getWorkInfosForUniqueWork(DownloadWorker.uniqueWorkName(taskId)).get()
            .filter { !it.state.isFinished }
        assertEquals(1, firstPending.size)
        val spec1 = WorkManagerImpl.getInstance(context).workDatabase.workSpecDao()
            .getWorkSpec(firstPending.single().id.toString())!!
        assertEquals(75_000L, spec1.initialDelay)

        // Calling recoverInterruptedTasks again must keep existing scheduled work (no duplicate or reset).
        fakeNowMs += 10_000L
        repo.recoverInterruptedTasks()
        val secondPending = wm.getWorkInfosForUniqueWork(DownloadWorker.uniqueWorkName(taskId)).get()
            .filter { !it.state.isFinished }
        assertEquals(1, secondPending.size)
        assertEquals(firstPending.single().id, secondPending.single().id)
        val spec2 = WorkManagerImpl.getInstance(context).workDatabase.workSpecDao()
            .getWorkSpec(secondPending.single().id.toString())!!
        assertEquals(75_000L, spec2.initialDelay)
    }

    @Test
    fun pauseCancelDismissAndManualRetry_manageScheduledRetryCleanlyAndPreserveResumeMetadata() = runBlocking {
        val taskId = "task_controls_1"
        val url = server.url("/controls.mp4").toString()
        seedTask(
            id = taskId,
            url = url,
            downloadedBytes = 8L,
            totalBytes = 100L,
            etag = "\"etag-ctrl\"",
            retryCount = 1,
            nextRetryAtMs = fakeNowMs + 50_000L,
            partialText = "12345678"
        )

        val wm = WorkManager.getInstance(context)
        val delayedReq = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInitialDelay(50_000L, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(DownloadWorker.KEY_TASK_ID to taskId))
            .build()
        wm.enqueueUniqueWork(DownloadWorker.uniqueWorkName(taskId), ExistingWorkPolicy.REPLACE, delayedReq).result.get()

        val repo = MediaRepositoryImpl(
            context = context,
            assetDao = database.downloadedAssetDao(),
            taskDao = database.downloadTaskDao(),
            storageManager = storageManager,
            clockMs = { fakeNowMs }
        )

        // Pause cancels pending WorkManager retry, clears nextRetryAtMs, and keeps partial file & etag.
        repo.pauseDownload(taskId)
        val paused = database.downloadTaskDao().getEntityById(taskId)!!
        assertEquals(DownloadState.PAUSED.name, paused.status)
        assertNull(paused.nextRetryAtMs)
        assertEquals("\"etag-ctrl\"", paused.etag)
        assertEquals(1, paused.retryCount)
        assertTrue(storageManager.getPartFile(taskId).exists())
        val afterPauseInfos = wm.getWorkInfosForUniqueWork(DownloadWorker.uniqueWorkName(taskId)).get()
        assertTrue(afterPauseInfos.all { it.state == WorkInfo.State.CANCELLED || it.state.isFinished })

        // Resume clears nextRetryAtMs and enqueues immediate (initialDelay == 0) WorkManager work.
        repo.resumeDownload(taskId)
        val resumed = database.downloadTaskDao().getEntityById(taskId)!!
        assertEquals(DownloadState.QUEUED.name, resumed.status)
        assertNull(resumed.nextRetryAtMs)
        val afterResumePending = wm.getWorkInfosForUniqueWork(DownloadWorker.uniqueWorkName(taskId)).get()
            .filter { !it.state.isFinished }
        assertEquals(1, afterResumePending.size)
        val resumeSpec = WorkManagerImpl.getInstance(context).workDatabase.workSpecDao()
            .getWorkSpec(afterResumePending.single().id.toString())!!
        assertEquals(0L, resumeSpec.initialDelay)

        // Cancel cancels WorkManager work and deletes partial file.
        repo.cancelDownload(taskId)
        val cancelled = database.downloadTaskDao().getEntityById(taskId)!!
        assertEquals(DownloadState.CANCELLED.name, cancelled.status)
        assertFalse(storageManager.getPartFile(taskId).exists())
        val afterCancelInfos = wm.getWorkInfosForUniqueWork(DownloadWorker.uniqueWorkName(taskId)).get()
        assertTrue(afterCancelInfos.all { it.state == WorkInfo.State.CANCELLED || it.state.isFinished })
    }
}
