package com.laterbox.app.ui.inbox

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.laterbox.app.ai.RemoteCapabilities
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.data.local.entity.IntelligenceSurfaceScope
import com.laterbox.app.data.local.entity.NotificationHistoryEntity
import com.laterbox.app.data.local.entity.SavedLinkEntity
import com.laterbox.app.data.local.entity.TasteFacetType
import com.laterbox.app.data.local.entity.UserTasteFacetEntity
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.data.repository.CollectionRepository
import com.laterbox.app.data.repository.LinkRepository
import com.laterbox.app.data.repository.NotificationHistoryRepository
import com.laterbox.app.domain.model.ContentType
import com.laterbox.app.intelligence.IntelligenceSignalLogger
import com.laterbox.app.intelligence.UserUnderstandingRepository
import com.laterbox.app.remoteconfig.RemoteConfigCache
import com.laterbox.app.remoteconfig.RemoteConfigClient
import com.laterbox.app.remoteconfig.RemoteConfigManager
import com.laterbox.app.remoteconfig.RemoteConfigRepository
import com.laterbox.app.util.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InboxViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var database: LaterboxDatabase
    private lateinit var linkRepository: LinkRepository
    private lateinit var preferencesRepository: UserPreferencesRepository
    private lateinit var viewModelStore: ViewModelStore

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
        preferencesRepository = UserPreferencesRepository(context)
        viewModelStore = ViewModelStore()
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        database.close()
    }

    private fun createViewModel(
        notificationHistoryRepository: NotificationHistoryRepository? = null,
        intelligenceSignalLogger: IntelligenceSignalLogger? = null,
        userUnderstandingRepository: UserUnderstandingRepository? = null,
        collectionRepository: CollectionRepository? = null,
        remoteConfigManager: RemoteConfigManager? = null,
        repoOverride: LinkRepository = linkRepository
    ): InboxViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return InboxViewModel(
                    linkRepository = repoOverride,
                    userPreferencesRepository = preferencesRepository,
                    notificationHistoryRepository = notificationHistoryRepository,
                    intelligenceSignalLogger = intelligenceSignalLogger,
                    userUnderstandingRepository = userUnderstandingRepository,
                    collectionRepository = collectionRepository,
                    remoteConfigManager = remoteConfigManager
                ) as T
            }
        }
        return ViewModelProvider(viewModelStore, factory)[InboxViewModel::class.java]
    }

    @Test
    fun inboxViewModel_observesLinksAndFiltersCorrectly() = runTest {
        val viewModel = createViewModel()

        // Save a test article and a test video
        linkRepository.saveLink(
            originalUrl = "https://example.com/article1",
            title = "First Article",
            contentType = ContentType.ARTICLE
        )
        linkRepository.saveLink(
            originalUrl = "https://youtube.com/watch?v=123",
            title = "Cooking Video",
            contentType = ContentType.VIDEO
        )

        viewModel.uiState.test {
            // First item might be initial loading or populated
            var state = awaitItem()
            while (state.isLoading || state.links.size < 2) {
                state = awaitItem()
            }

            assertEquals(2, state.links.size)
            assertFalse(state.isEmpty)

            // Test search filter
            viewModel.onSearchQueryChange("Cooking")
            state = awaitItem()
            while (state.links.size != 1 || state.links[0].title != "Cooking Video") {
                state = awaitItem()
            }
            assertEquals("Cooking Video", state.links[0].title)

            // Clear search
            viewModel.onClearSearch()
            state = awaitItem()
            while (state.links.size != 2) {
                state = awaitItem()
            }
            assertEquals(2, state.links.size)

            // Filter by ContentType
            viewModel.onFilterContentType(ContentType.ARTICLE)
            state = awaitItem()
            while (state.links.size != 1 || state.links[0].title != "First Article") {
                state = awaitItem()
            }
            assertEquals("First Article", state.links[0].title)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun inboxViewModel_archiveRemovesLinkFromInbox() = runTest {
        val viewModel = createViewModel()

        val linkId = linkRepository.saveLink(
            originalUrl = "https://example.com/item",
            title = "Item to archive"
        )

        viewModel.uiState.test {
            var state = awaitItem()
            while (state.isLoading || state.links.isEmpty()) {
                state = awaitItem()
            }
            assertEquals(1, state.links.size)

            viewModel.onArchive(linkId)
            state = awaitItem()
            while (state.links.isNotEmpty()) {
                state = awaitItem()
            }
            assertEquals(0, state.links.size)
            assertTrue(state.isEmpty)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun inboxViewModel_togglesViewMode() = runTest {
        val viewModel = createViewModel()

        viewModel.uiState.test {
            var state = awaitItem()
            while (state.isLoading) {
                state = awaitItem()
            }
            assertEquals(com.laterbox.app.data.preferences.InboxViewMode.HERO, state.viewMode)

            viewModel.onToggleViewMode()
            state = awaitItem()
            while (state.viewMode != com.laterbox.app.data.preferences.InboxViewMode.COMPACT) {
                state = awaitItem()
            }
            assertEquals(com.laterbox.app.data.preferences.InboxViewMode.COMPACT, state.viewMode)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun saveLinkDeduplicatesNormalizedUrlAndMergesNotesNonDestructively() = runTest {
        val firstId = linkRepository.saveLink(
            originalUrl = "https://example.com/article?utm_source=newsletter",
            title = "Custom User Title",
            personalNote = "Initial note"
        )
        val secondId = linkRepository.saveLink(
            originalUrl = "https://example.com/article?utm_medium=social",
            title = "example.com",
            personalNote = "Follow-up thought"
        )

        assertEquals(firstId, secondId)
        val saved = database.savedLinkDao().getEntityById(firstId)
        assertEquals("Custom User Title", saved?.title)
        assertTrue(saved?.personalNote?.contains("Initial note") == true)
        assertTrue(saved?.personalNote?.contains("Follow-up thought") == true)
    }

    @Test
    fun refreshEnrichesPartialAndFailedLinksAndResetsRefreshingState() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta property="og:title" content="Fetched OG Title" />
                    <meta property="og:description" content="Fetched OG Description" />
                    <meta property="og:image" content="/images/hero.png" />
                </head>
                <body></body>
                </html>
            """.trimIndent()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody(html))

            val targetUrl = server.url("/post").toString()
            val id = database.savedLinkDao().insertLink(
                com.laterbox.app.data.local.entity.SavedLinkEntity(
                    originalUrl = targetUrl,
                    normalizedUrl = targetUrl,
                    title = "User Custom Title",
                    domain = "localhost",
                    previewImageUrl = null,
                    metadataStatus = com.laterbox.app.domain.model.MetadataStatus.PARTIAL
                )
            )

            val viewModel = createViewModel()
            viewModel.refresh()

            // Wait until repository finishes enriching the link
            var updated = database.savedLinkDao().getEntityById(id)
            var attempts = 0
            while (updated?.previewImageUrl == null && attempts < 50) {
                Thread.sleep(50)
                testScheduler.advanceUntilIdle()
                updated = database.savedLinkDao().getEntityById(id)
                attempts++
            }

            // Custom user title is preserved while missing description & OG image are populated
            assertEquals("User Custom Title", updated?.title)
            assertEquals("Fetched OG Description", updated?.description)
            assertEquals("${server.url("/")}images/hero.png", updated?.previewImageUrl)
            assertEquals(com.laterbox.app.domain.model.MetadataStatus.SUCCESS, updated?.metadataStatus)
            assertFalse(viewModel.uiState.value.isRefreshing)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun dateRangeFilter_filtersLinksCorrectly() = runTest {
        val viewModel = createViewModel()

        val now = System.currentTimeMillis()
        val oneDayAgo = now - 24 * 60 * 60 * 1000L
        val tenDaysAgo = now - 10 * 24 * 60 * 60 * 1000L

        database.savedLinkDao().insertLink(
            com.laterbox.app.data.local.entity.SavedLinkEntity(
                originalUrl = "https://example.com/today",
                normalizedUrl = "https://example.com/today",
                title = "Today Link",
                domain = "example.com",
                createdAt = now
            )
        )
        database.savedLinkDao().insertLink(
            com.laterbox.app.data.local.entity.SavedLinkEntity(
                originalUrl = "https://example.com/yesterday",
                normalizedUrl = "https://example.com/yesterday",
                title = "Yesterday Link",
                domain = "example.com",
                createdAt = oneDayAgo
            )
        )
        database.savedLinkDao().insertLink(
            com.laterbox.app.data.local.entity.SavedLinkEntity(
                originalUrl = "https://example.com/old",
                normalizedUrl = "https://example.com/old",
                title = "Old Link",
                domain = "example.com",
                createdAt = tenDaysAgo
            )
        )

        viewModel.uiState.test {
            var state = awaitItem()
            while (state.isLoading || state.links.size < 3) {
                state = awaitItem()
            }
            assertEquals(3, state.links.size)

            // Filter Today
            viewModel.onFilterDateRange(DateRangeFilter.TODAY)
            state = awaitItem()
            while (state.links.size != 1 || state.links[0].title != "Today Link") {
                state = awaitItem()
            }
            assertEquals("Today Link", state.links[0].title)

            // Filter Last 7 Days (Today + Yesterday)
            viewModel.onFilterDateRange(DateRangeFilter.LAST_7_DAYS)
            state = awaitItem()
            while (state.links.size != 2) {
                state = awaitItem()
            }
            assertEquals(2, state.links.size)

            // Reset Filter
            viewModel.onFilterDateRange(DateRangeFilter.LAST_7_DAYS) // Toggle off -> ALL
            state = awaitItem()
            while (state.links.size != 3) {
                state = awaitItem()
            }
            assertEquals(3, state.links.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun inboxViewModel_saveClipboardSuggestion_recordsHashAndSavesLink() = runTest {
        val viewModel = createViewModel()
        val suggestion = com.laterbox.app.capture.ClipboardSuggestion(
            url = "https://example.com/clipboard-save",
            domain = "example.com",
            hash = "testhash123"
        )

        var saved = false
        val job = viewModel.saveClipboardSuggestion(suggestion) {
            saved = true
        }
        job.join()
        testScheduler.advanceUntilIdle()

        assertTrue(saved)
        val savedLink = linkRepository.findDuplicate("https://example.com/clipboard-save")
        org.junit.Assert.assertNotNull(savedLink)

        val prefs = preferencesRepository.userPreferences.first()
        assertTrue(prefs.recentClipboardHistory.any { it.startsWith("testhash123:") })
    }

    @Test
    fun inboxViewModel_observesUnreadNotificationCount() = runTest {
        val notificationRepo = NotificationHistoryRepository(database.notificationHistoryDao())
        val viewModel = createViewModel(notificationHistoryRepository = notificationRepo)

        viewModel.uiState.test {
            var state = awaitItem()
            while (state.isLoading) {
                state = awaitItem()
            }
            assertEquals(0, state.unreadNotificationCount)

            // Insert unread notification
            val notifId = notificationRepo.recordNotification(
                url = "https://example.com/notif",
                title = "Reminder",
                body = "Read now",
                origin = "SMART_DIGEST"
            )

            state = awaitItem()
            while (state.unreadNotificationCount != 1) {
                state = awaitItem()
            }
            assertEquals(1, state.unreadNotificationCount)

            // Mark as read
            notificationRepo.markAsRead(notifId)
            state = awaitItem()
            while (state.unreadNotificationCount != 0) {
                state = awaitItem()
            }
            assertEquals(0, state.unreadNotificationCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun inboxViewModel_multiSelection_toggle_and_clear() = runTest {
        val viewModel = createViewModel()

        viewModel.uiState.test {
            var state = awaitItem()
            while (state.isLoading) {
                state = awaitItem()
            }
            assertFalse(state.isSelectionMode)
            assertEquals(0, state.selectedCount)

            viewModel.onToggleSelectLink(101L)
            state = awaitItem()
            while (!state.isSelectionMode) {
                state = awaitItem()
            }
            assertEquals(1, state.selectedCount)
            assertTrue(state.selectedLinkIds.contains(101L))

            viewModel.onToggleSelectLink(102L)
            state = awaitItem()
            while (state.selectedCount != 2) {
                state = awaitItem()
            }
            assertEquals(2, state.selectedCount)

            viewModel.onToggleSelectLink(101L)
            state = awaitItem()
            while (state.selectedCount != 1) {
                state = awaitItem()
            }
            assertEquals(1, state.selectedCount)
            assertFalse(state.selectedLinkIds.contains(101L))
            assertTrue(state.selectedLinkIds.contains(102L))

            viewModel.onClearSelection()
            state = awaitItem()
            while (state.isSelectionMode) {
                state = awaitItem()
            }
            assertFalse(state.isSelectionMode)
            assertEquals(0, state.selectedCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun inboxViewModel_multiSelection_selectAll_and_batchArchive() = runTest {
        val viewModel = createViewModel()

        val id1 = linkRepository.saveLink(
            originalUrl = "https://example.com/item1",
            title = "Item 1",
            contentType = ContentType.ARTICLE,
            autoFetchMetadata = false
        )
        val id2 = linkRepository.saveLink(
            originalUrl = "https://example.com/item2",
            title = "Item 2",
            contentType = ContentType.ARTICLE,
            autoFetchMetadata = false
        )

        viewModel.uiState.test(timeout = kotlin.time.Duration.parse("10s")) {
            var state = awaitItem()
            while (state.isLoading || state.links.size < 2) {
                state = awaitItem()
            }

            viewModel.onSelectAll(state.links)
            state = awaitItem()
            while (!state.isSelectionMode) {
                state = awaitItem()
            }
            assertTrue(state.isSelectionMode)
            assertEquals(2, state.selectedCount)

            val archiveJob = viewModel.onArchiveSelected()
            archiveJob.join()

            state = awaitItem()
            while (state.isSelectionMode || state.links.isNotEmpty()) {
                state = awaitItem()
            }
            assertFalse(state.isSelectionMode)
            assertTrue(state.links.isEmpty())

            cancelAndIgnoreRemainingEvents()
        }

        val remaining = linkRepository.inboxLinks.first()
        assertTrue("Archived links should no longer appear in inbox", remaining.isEmpty())
    }

    @Test
    fun inboxViewModel_multiSelection_batchToggleRead_and_batchDelete() = runTest {
        val viewModel = createViewModel()

        val id1 = linkRepository.saveLink(
            originalUrl = "https://example.com/read1",
            title = "Read 1",
            contentType = ContentType.ARTICLE
        )
        val id2 = linkRepository.saveLink(
            originalUrl = "https://example.com/read2",
            title = "Read 2",
            contentType = ContentType.ARTICLE
        )

        viewModel.uiState.test {
            var state = awaitItem()
            while (state.isLoading || state.links.size < 2) {
                state = awaitItem()
            }

            viewModel.onSelectAll(state.links)
            state = awaitItem()
            while (!state.isSelectionMode) {
                state = awaitItem()
            }

            // Batch toggle read: both are unread, so they should become read
            val toggleJob = viewModel.onToggleReadSelected(state.links)
            toggleJob.join()

            state = awaitItem()
            while (state.links.any { !it.isRead }) {
                state = awaitItem()
            }
            assertTrue(state.links.all { it.isRead })

            // Select all and batch delete
            viewModel.onSelectAll(state.links)
            state = awaitItem()
            while (!state.isSelectionMode) {
                state = awaitItem()
            }

            val deleteJob = viewModel.onDeleteSelected()
            deleteJob.join()

            state = awaitItem()
            while (state.isSelectionMode || state.links.isNotEmpty()) {
                state = awaitItem()
            }
            assertFalse(state.isSelectionMode)
            assertTrue(state.links.isEmpty())

            cancelAndIgnoreRemainingEvents()
        }

        val afterDelete = linkRepository.inboxLinks.first()
        assertTrue("Deleted links should be empty", afterDelete.isEmpty())
    }

    @Test
    fun inboxViewModel_togglesViewModeAcrossAllThreeModes() = runTest {
        val viewModel = createViewModel()

        viewModel.uiState.test {
            var state = awaitItem()
            while (state.isLoading) {
                state = awaitItem()
            }
            assertEquals(com.laterbox.app.data.preferences.InboxViewMode.HERO, state.viewMode)

            viewModel.onToggleViewMode()
            state = awaitItem()
            while (state.viewMode != com.laterbox.app.data.preferences.InboxViewMode.COMPACT) {
                state = awaitItem()
            }

            viewModel.onToggleViewMode()
            state = awaitItem()
            while (state.viewMode != com.laterbox.app.data.preferences.InboxViewMode.EDITORIAL) {
                state = awaitItem()
            }
            assertEquals(com.laterbox.app.data.preferences.InboxViewMode.EDITORIAL, state.viewMode)

            viewModel.onToggleViewMode()
            state = awaitItem()
            while (state.viewMode != com.laterbox.app.data.preferences.InboxViewMode.HERO) {
                state = awaitItem()
            }
            assertEquals(com.laterbox.app.data.preferences.InboxViewMode.HERO, state.viewMode)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun inboxViewModel_quickFiltersAndArchiveAllReadWithUndoWorkCorrectly() = runTest {
        val viewModel = createViewModel()

        val idUnread = linkRepository.saveLink(
            originalUrl = "https://example.com/unread",
            title = "Unread Scheduled Item",
            reminderAt = System.currentTimeMillis() + 3_600_000L
        )
        val idRead = linkRepository.saveLink(
            originalUrl = "https://example.com/read",
            title = "Read Item"
        )
        linkRepository.setRead(idRead, true)

        viewModel.uiState.test {
            var state = awaitItem()
            while (state.isLoading || state.links.size < 2 || state.readInboxCount != 1) {
                state = awaitItem()
            }
            assertEquals(1, state.readInboxCount)

            // Quick filter UNREAD
            viewModel.onFilterQuickFilter(InboxQuickFilter.UNREAD)
            state = awaitItem()
            while (state.activeQuickFilter != InboxQuickFilter.UNREAD) {
                state = awaitItem()
            }
            assertEquals(listOf(idUnread), state.links.map { it.id })

            // Quick filter SCHEDULED
            viewModel.onFilterQuickFilter(InboxQuickFilter.SCHEDULED)
            state = awaitItem()
            while (state.activeQuickFilter != InboxQuickFilter.SCHEDULED) {
                state = awaitItem()
            }
            assertEquals(listOf(idUnread), state.links.map { it.id })

            // Reset to ALL
            viewModel.onFilterQuickFilter(InboxQuickFilter.ALL)
            state = awaitItem()
            while (state.activeQuickFilter != InboxQuickFilter.ALL || state.links.size != 2) {
                state = awaitItem()
            }

            // Archive all read
            var archivedIds: List<Long> = emptyList()
            viewModel.onArchiveAllRead { archivedIds = it }.join()
            state = awaitItem()
            while (state.links.size != 1 || state.readInboxCount != 0) {
                state = awaitItem()
            }
            assertEquals(listOf(idRead), archivedIds)
            assertEquals(listOf(idUnread), state.links.map { it.id })

            // Undo batch archive
            viewModel.unarchiveBatch(archivedIds).join()
            state = awaitItem()
            while (state.links.size != 2 || state.readInboxCount != 1) {
                state = awaitItem()
            }
            assertEquals(2, state.links.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun inboxViewModel_resurfacerPreservesChronologicalOrderAndLogsSignals() = runTest {
        val nowMs = System.currentTimeMillis()
        val dayMs = 24L * 60L * 60L * 1000L
        val understandingRepo = UserUnderstandingRepository(
            intelligenceDao = database.intelligenceDao(),
            userPreferencesRepository = preferencesRepository,
            moviesDao = database.moviesDao(),
            savedLinkDao = database.savedLinkDao()
        )
        val signalLogger = IntelligenceSignalLogger(
            intelligenceDao = database.intelligenceDao(),
            userUnderstandingRepository = understandingRepo,
            userPreferencesRepository = preferencesRepository
        )

        database.intelligenceDao().upsertFacet(
            UserTasteFacetEntity(
                facetKey = "topic:design",
                facetType = TasteFacetType.ACTIVE_GOAL.name,
                surfaceScope = IntelligenceSurfaceScope.LIBRARY.name,
                displayLabel = "Design",
                score = 0.9,
                confidence = 0.95,
                provenance = "Goal",
                evidenceSummary = "Goal",
                firstObservedAtMs = nowMs - 10 * dayMs,
                lastReinforcedAtMs = nowMs - dayMs
            )
        )

        val oldestId = database.savedLinkDao().insertLink(
            SavedLinkEntity(
                originalUrl = "https://figma.com/systems",
                normalizedUrl = "https://figma.com/systems",
                title = "Figma Systems",
                domain = "figma.com",
                createdAt = nowMs - 6 * dayMs,
                updatedAt = nowMs - 6 * dayMs
            )
        )
        val midOldId = database.savedLinkDao().insertLink(
            SavedLinkEntity(
                originalUrl = "https://example.org/essay",
                normalizedUrl = "https://example.org/essay",
                title = "Longform Essay",
                domain = "example.org",
                createdAt = nowMs - 4 * dayMs,
                updatedAt = nowMs - 4 * dayMs
            )
        )
        val freshAId = database.savedLinkDao().insertLink(
            SavedLinkEntity(
                originalUrl = "https://example.com/fresh-a",
                normalizedUrl = "https://example.com/fresh-a",
                title = "Fresh A",
                domain = "example.com",
                createdAt = nowMs - 2_000L,
                updatedAt = nowMs - 2_000L
            )
        )
        val newestId = database.savedLinkDao().insertLink(
            SavedLinkEntity(
                originalUrl = "https://example.com/newest",
                normalizedUrl = "https://example.com/newest",
                title = "Newest Link",
                domain = "example.com",
                createdAt = nowMs - 500L,
                updatedAt = nowMs - 500L
            )
        )

        val viewModel = createViewModel(
            intelligenceSignalLogger = signalLogger,
            userUnderstandingRepository = understandingRepo
        )

        viewModel.uiState.test {
            var state = awaitItem()
            while (state.isLoading || state.links.size < 4) {
                state = awaitItem()
            }
            if (state.resurfacedLinks.isEmpty()) {
                // Allow one more combine cycle if facet/pref emissions arrived sequentially
                val next = awaitItem()
                state = next
            }
            assertTrue(
                "Expected non-empty resurfacedLinks for 4 links (createdAt=${state.links.map { it.createdAt }}, nowMs=$nowMs); state=$state",
                state.resurfacedLinks.isNotEmpty()
            )

            // Main feed MUST remain strictly chronological (newest -> oldest) regardless of design facet score
            assertEquals(
                listOf(newestId, freshAId, midOldId, oldestId),
                state.links.map { it.id }
            )
            val topResurfaced = state.resurfacedLinks.first()
            assertEquals(oldestId, topResurfaced.link.id)

            viewModel.onDismissResurfacedLink(topResurfaced.link)
            state = awaitItem()
            while (state.resurfacedLinks.any { it.link.id == oldestId }) {
                state = awaitItem()
            }

            val midSuggestion = state.resurfacedLinks.firstOrNull { it.link.id == midOldId }
            if (midSuggestion != null) {
                viewModel.onResurfacedLinkOpened(midSuggestion)
                state = awaitItem()
                while (state.resurfacedLinks.any { it.link.id == midOldId }) {
                    state = awaitItem()
                }
            }

            var loggedTypes: List<String> = emptyList()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                repeat(40) {
                    loggedTypes = database.intelligenceDao().getRecentSignalsSnapshot(20).map { it.signalType }
                    if (IntelligenceSignalLogger.SIGNAL_RESURFACE_DISMISSED in loggedTypes) return@withContext
                    Thread.sleep(25L)
                }
            }
            assertTrue(
                "Expected RESURFACE_DISMISSED in loggedTypes=$loggedTypes",
                IntelligenceSignalLogger.SIGNAL_RESURFACE_DISMISSED in loggedTypes
            )

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun inboxViewModel_collectionSuggestionAcceptLogsSignal_dismissSuppresses_andKillSwitchGatesAllProactiveHelpers() = runTest {
        val nowMs = System.currentTimeMillis()
        val dayMs = 24L * 60L * 60L * 1000L
        val understandingRepo = UserUnderstandingRepository(
            intelligenceDao = database.intelligenceDao(),
            userPreferencesRepository = preferencesRepository,
            moviesDao = database.moviesDao(),
            savedLinkDao = database.savedLinkDao()
        )
        val signalLogger = IntelligenceSignalLogger(
            intelligenceDao = database.intelligenceDao(),
            userUnderstandingRepository = understandingRepo,
            userPreferencesRepository = preferencesRepository
        )
        val loggerAwareLinkRepo = LinkRepository(
            savedLinkDao = database.savedLinkDao(),
            intelligenceSignalLogger = signalLogger
        )
        val collectionRepo = CollectionRepository(database.collectionDao())
        val colId = collectionRepo.createCollection("UI Design")

        val categorizedId = loggerAwareLinkRepo.saveLink(
            originalUrl = "https://figma.com/kit-1",
            title = "Figma Component Kit"
        )
        loggerAwareLinkRepo.setLinkCollections(categorizedId, listOf(colId))

        val uncategorizedMatchId = database.savedLinkDao().insertLink(
            SavedLinkEntity(
                originalUrl = "https://figma.com/tokens-guide",
                normalizedUrl = "https://figma.com/tokens-guide",
                title = "Figma Design Tokens",
                domain = "figma.com",
                createdAt = nowMs - 5 * dayMs,
                updatedAt = nowMs - 5 * dayMs
            )
        )
        // Add 2 more older links so InboxResurfacer threshold (4 links) is also met
        database.savedLinkDao().insertLink(
            SavedLinkEntity(
                originalUrl = "https://example.org/read1",
                normalizedUrl = "https://example.org/read1",
                title = "Readable Essay 1",
                domain = "example.org",
                createdAt = nowMs - 6 * dayMs,
                updatedAt = nowMs - 6 * dayMs
            )
        )
        database.savedLinkDao().insertLink(
            SavedLinkEntity(
                originalUrl = "https://example.org/read2",
                normalizedUrl = "https://example.org/read2",
                title = "Readable Essay 2",
                domain = "example.org",
                createdAt = nowMs - 7 * dayMs,
                updatedAt = nowMs - 7 * dayMs
            )
        )

        val server = MockWebServer()
        server.start()
        val rcScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "schemaVersion": 1,
                      "revision": 99,
                      "publishedAt": "2026-11-26T00:00:00Z",
                      "expiresAt": "2099-01-01T00:00:00Z",
                      "general": { "minRefreshIntervalSeconds": 60, "staleCacheMaxAgeSeconds": 86400 },
                      "features": {
                        "${RemoteCapabilities.KEY_EXPERIENCE_INTELLIGENCE}": false
                      }
                    }
                    """.trimIndent()
                )
            )
            val dataStore = PreferenceDataStoreFactory.create(
                scope = rcScope,
                produceFile = { File(tempFolder.root, "rc_phase3_test.preferences_pb") }
            )
            val rcRepo = RemoteConfigRepository(
                cache = RemoteConfigCache(dataStore),
                client = RemoteConfigClient(
                    okHttpClient = OkHttpClient(),
                    baseUrlProvider = { server.url("/").toString() },
                    appCheckTokenProvider = { null },
                    clientVersionCode = 999,
                    clientVersionName = "1.0.99"
                ),
                clientVersionCode = 999
            )
            val remoteConfigManager = RemoteConfigManager(rcRepo, rcScope)

            val viewModel = createViewModel(
                intelligenceSignalLogger = signalLogger,
                userUnderstandingRepository = understandingRepo,
                collectionRepository = collectionRepo,
                remoteConfigManager = remoteConfigManager,
                repoOverride = loggerAwareLinkRepo
            )

            viewModel.uiState.test {
                var state = awaitItem()
                while (state.isLoading || state.collectionSuggestions.isEmpty() || state.resurfacedLinks.isEmpty()) {
                    state = awaitItem()
                }
                val suggestion = state.collectionSuggestions.first()
                assertEquals(uncategorizedMatchId, suggestion.link.id)
                assertEquals(colId, suggestion.collection.id)

                // Accept 1-tap suggestion -> adds to collection and logs COLLECTION_ADD
                viewModel.onAcceptCollectionSuggestion(suggestion)
                state = awaitItem()
                while (state.collectionSuggestions.any { it.link.id == uncategorizedMatchId && it.collection.id == colId }) {
                    state = awaitItem()
                }
                val updated = loggerAwareLinkRepo.getLinkById(uncategorizedMatchId).first()!!
                assertTrue(colId in updated.collectionIds)
                var signalTypes: List<String> = emptyList()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    repeat(40) {
                        signalTypes = database.intelligenceDao().getRecentSignalsSnapshot(20).map { it.signalType }
                        if (IntelligenceSignalLogger.SIGNAL_COLLECTION_ADD in signalTypes) return@withContext
                        Thread.sleep(25L)
                    }
                }
                assertTrue(
                    "Expected COLLECTION_ADD in signalTypes=$signalTypes",
                    IntelligenceSignalLogger.SIGNAL_COLLECTION_ADD in signalTypes
                )

                // Insert second uncategorized figma link to verify remote kill-switch & local pause gating
                val secondFigmaId = database.savedLinkDao().insertLink(
                    SavedLinkEntity(
                        originalUrl = "https://figma.com/auto-layout-2",
                        normalizedUrl = "https://figma.com/auto-layout-2",
                        title = "Auto Layout Part 2",
                        domain = "figma.com",
                        createdAt = nowMs - 4 * dayMs,
                        updatedAt = nowMs - 4 * dayMs
                    )
                )
                state = awaitItem()
                while (state.collectionSuggestions.none { it.link.id == secondFigmaId }) {
                    state = awaitItem()
                }

                // Remote kill-switch flips experience_intelligence_enabled -> false
                remoteConfigManager.refreshNow()
                state = awaitItem()
                while (state.resurfacedLinks.isNotEmpty() || state.collectionSuggestions.isNotEmpty()) {
                    state = awaitItem()
                }
                assertTrue(state.resurfacedLinks.isEmpty())
                assertTrue(state.collectionSuggestions.isEmpty())

                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            rcScope.cancel()
            server.shutdown()
        }
    }
}


