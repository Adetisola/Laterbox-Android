package com.laterbox.app.ui.sheets

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.data.repository.CollectionRepository
import com.laterbox.app.data.repository.LinkRepository
import com.laterbox.app.domain.model.ContentType
import com.laterbox.app.domain.model.MetadataStatus
import com.laterbox.app.media.domain.adapter.AdapterRegistry
import com.laterbox.app.media.domain.adapter.PlatformResolver
import com.laterbox.app.media.domain.model.DownloadTask
import com.laterbox.app.media.domain.model.InspectionResult
import com.laterbox.app.media.domain.model.MediaAsset
import com.laterbox.app.media.domain.model.MediaCandidate
import com.laterbox.app.media.domain.model.MediaType
import com.laterbox.app.media.domain.repository.MediaRepository
import com.laterbox.app.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedItemDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: LaterboxDatabase
    private lateinit var linkRepository: LinkRepository
    private lateinit var collectionRepository: CollectionRepository
    private lateinit var mockServer: MockWebServer

    private class FakeMediaRepository : MediaRepository {
        override fun getAssetsForLink(linkId: Long): Flow<List<MediaAsset>> = flowOf(emptyList())
        override fun getAllAssets(): Flow<List<MediaAsset>> = flowOf(emptyList())
        override fun filterAssets(mediaType: MediaType?, query: String): Flow<List<MediaAsset>> = flowOf(emptyList())
        override fun getAssetById(id: String): Flow<MediaAsset?> = flowOf(null)
        override fun getTasksForLink(linkId: Long): Flow<List<DownloadTask>> = flowOf(emptyList())
        override fun getQueueTasks(): Flow<List<DownloadTask>> = flowOf(emptyList())
        override fun getTaskById(taskId: String): Flow<DownloadTask?> = flowOf(null)
        override fun getStorageBreakdown(): Flow<Map<MediaType, Long>> = flowOf(emptyMap())
        override fun getAssetCount(): Flow<Int> = flowOf(0)
        override suspend fun enqueueDownload(
            linkId: Long,
            candidate: MediaCandidate,
            sourcePlatform: String,
            title: String?,
            artist: String?,
            album: String?,
            artworkUrl: String?,
            spotifyTrackId: String?,
            versionTag: String?
        ): String = "t1"
        override suspend fun overrideCellularRequirement(taskId: String) {}
        override suspend fun pauseDownload(taskId: String) {}
        override suspend fun resumeDownload(taskId: String) {}
        override suspend fun cancelDownload(taskId: String) {}
        override suspend fun dismissTask(taskId: String) {}
        override suspend fun clearTerminalTasks() {}
        override suspend fun retryDownload(taskId: String) {}
        override suspend fun deleteAsset(assetId: String) {}
        override suspend fun exportAsset(assetId: String): Result<String> = Result.success("")
        override suspend fun deleteAssetsForLink(linkId: Long, deleteFiles: Boolean) {}
        override suspend fun runOrphanSweep() {}
        override suspend fun recoverInterruptedTasks() {}
        override fun getInspectionForLink(linkId: Long): Flow<InspectionResult?> = flowOf(null)
        override fun isInspectingLink(linkId: Long): Flow<Boolean> = flowOf(false)
        override fun startInspection(linkId: Long, url: String, previewImageUrl: String?, forceRefresh: Boolean) {}
        override suspend fun cancelInspection(linkId: Long) {}
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directExecutor = Executor { runnable -> runnable.run() }
        database = Room.inMemoryDatabaseBuilder(context, LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
        linkRepository = LinkRepository(database.savedLinkDao())
        collectionRepository = CollectionRepository(database.collectionDao())
        mockServer = MockWebServer()
        mockServer.start()
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
        database.close()
    }

    @Test
    fun onRetryMetadata_readsSingleSnapshotWithoutLeakingRoomCollector_andRespectsCurrentLink() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><head><title>Fetched Title One</title></head><body>Hello</body></html>")
        )
        val url1 = mockServer.url("/one").toString()
        val url2 = mockServer.url("/two").toString()

        val id1 = linkRepository.saveLink(
            originalUrl = url1,
            title = "Initial One",
            contentType = ContentType.ARTICLE,
            autoFetchMetadata = false
        )
        val id2 = linkRepository.saveLink(
            originalUrl = url2,
            title = "Initial Two",
            contentType = ContentType.ARTICLE,
            autoFetchMetadata = false
        )

        val link1 = linkRepository.getLinkById(id1).first()!!
        val link2 = linkRepository.getLinkById(id2).first()!!

        val viewModel = SavedItemDetailViewModel(
            linkRepository = linkRepository,
            collectionRepository = collectionRepository,
            mediaRepository = FakeMediaRepository(),
            platformResolver = PlatformResolver(AdapterRegistry(emptyList()))
        )

        val collectorJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }

        viewModel.setLink(link1)
        viewModel.onRetryMetadata()
        val deadline = System.currentTimeMillis() + 3_000L
        while (viewModel.uiState.value.link?.metadataStatus == MetadataStatus.FETCHING && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }

        val currentAfterRetry = viewModel.uiState.value.link
        assertNotNull(currentAfterRetry)
        assertEquals(id1, currentAfterRetry?.id)

        // Switching to link2 and then externally updating link1 in DB must NOT overwrite link2 in the ViewModel
        viewModel.setLink(link2)
        assertEquals(id2, viewModel.uiState.value.link?.id)

        linkRepository.updateLink(link1.copy(title = "External Update One"))
        assertEquals(id2, viewModel.uiState.value.link?.id)
        assertEquals("Initial Two", viewModel.uiState.value.editedTitle)

        collectorJob.cancel()
    }
}
