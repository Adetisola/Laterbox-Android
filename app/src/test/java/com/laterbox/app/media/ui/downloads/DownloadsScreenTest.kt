package com.laterbox.app.media.ui.downloads

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.laterbox.app.media.domain.model.DownloadState
import com.laterbox.app.media.domain.model.DownloadTask
import com.laterbox.app.media.domain.model.DownloadsFilterCategory
import com.laterbox.app.media.domain.model.MediaAsset
import com.laterbox.app.media.domain.model.MediaType
import com.laterbox.app.ui.theme.LaterboxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun unifiedDownloadsScreen_displaysActiveQueueWithSpeedEta_categoryChips_moreMenu_andStorageSheet() {
        var selectedCategory: DownloadsFilterCategory? = null
        var exportedAssetId: String? = null
        var deletedAssetId: String? = null

        val activeTask = DownloadTask(
            id = "task_1",
            savedLinkId = null,
            sourceUrl = "https://cdn.example/movie.mp4",
            sourcePlatform = "MovieBox",
            candidateId = "c1",
            formatSelector = null,
            mediaType = MediaType.VIDEO,
            qualityLabel = "720p · MP4",
            title = "His Girl Friday (720p)",
            assetId = null,
            status = DownloadState.DOWNLOADING,
            progress = 42,
            downloadedBytes = 10L * 1024L * 1024L,
            totalBytes = 25L * 1024L * 1024L,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L,
            contentCategory = "MOVIE",
            speedBps = 2L * 1024L * 1024L,
            etaSeconds = 8L
        )
        val libraryAsset = MediaAsset(
            id = "asset_1",
            savedLinkId = null,
            sourcePlatform = "MovieBox",
            originalSourceUrl = "https://cdn.example/s1e1.mp4",
            title = "Severance · S1E1 (720p)",
            mimeType = "video/mp4",
            mediaType = MediaType.VIDEO,
            localPath = "video/s1e1.mp4",
            exportedUri = null,
            fileSize = 420L * 1024L * 1024L,
            durationMs = 2400_000L,
            width = 1280,
            height = 720,
            qualityLabel = "720p · MP4",
            checksum = "chk1",
            createdAt = 1_700_000_000_000L,
            contentCategory = "EPISODE"
        )

        composeTestRule.setContent {
            LaterboxTheme {
                DownloadsScreenContent(
                    uiState = DownloadsUiState(
                        selectedTab = DownloadsTab.LIBRARY,
                        selectedCategory = DownloadsFilterCategory.ALL,
                        assets = listOf(libraryAsset),
                        queueTasks = listOf(activeTask),
                        storageBreakdown = mapOf(MediaType.VIDEO to 420L * 1024L * 1024L),
                        totalStorageBytes = 420L * 1024L * 1024L
                    ),
                    onSettingsClick = {},
                    onOpenLinkDetail = {},
                    onMenuClick = null,
                    onSearchQueryChange = {},
                    onCategorySelected = { selectedCategory = it },
                    onTabSelected = {},
                    onToggleAssetSelection = {},
                    onSelectAllAssets = {},
                    onClearSelection = {},
                    onDeleteSelectedAssets = {},
                    onExportSelectedAssets = {},
                    onOpenAsset = {},
                    onExportAsset = { exportedAssetId = it.id },
                    onDeleteAsset = { deletedAssetId = it.id },
                    onToggleTaskSelection = {},
                    onSelectAllTasks = {},
                    onClearTaskSelection = {},
                    onCancelSelectedTasks = {},
                    onRetrySelectedTasks = {},
                    onDismissSelectedTasks = {},
                    onOverrideCellular = {},
                    onPauseTask = {},
                    onResumeTask = {},
                    onCancelTask = {},
                    onRetryTask = {},
                    onDismissTask = {}
                )
            }
        }

        // Active Queue rendered on top with speed + ETA telemetry
        composeTestRule.onNodeWithTag("downloads_queue_header").assertIsDisplayed()
        composeTestRule.onNodeWithText("His Girl Friday (720p)").assertIsDisplayed()
        composeTestRule.onNodeWithText("2.0 MB/s · 8s left", substring = true).assertIsDisplayed()

        // Category filter chips include Movies & Documents
        composeTestRule.onNodeWithTag("downloads_filter_movies").performClick()
        assertEquals(DownloadsFilterCategory.MOVIES, selectedCategory)

        // Library card actions live inside More menu
        composeTestRule.onNodeWithTag("downloads_unified_list").performScrollToNode(hasTestTag("asset_more_button_asset_1"))
        composeTestRule.onNodeWithTag("asset_more_button_asset_1").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Save to device").assertIsDisplayed().performClick()
        assertEquals("asset_1", exportedAssetId)

        composeTestRule.onNodeWithTag("asset_more_button_asset_1").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Delete").assertIsDisplayed().performClick()
        assertEquals("asset_1", deletedAssetId)

        // Header Storage button opens Storage modal sheet
        composeTestRule.onNodeWithTag("downloads_storage_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("downloads_storage_sheet").assertIsDisplayed()
        assertTrue(true)
    }

    @Test
    fun pausedDownloadTask_displaysProgressBarPercentageAndDownloadedTotalBytesWithoutStaleSpeed() {
        val pausedTask = DownloadTask(
            id = "task_paused",
            savedLinkId = null,
            sourceUrl = "https://cdn.example/movie.mp4",
            sourcePlatform = "MovieBox",
            candidateId = "c1",
            formatSelector = null,
            mediaType = MediaType.VIDEO,
            qualityLabel = "720p · MP4",
            title = "Severance · S1 · E1 (720p)",
            assetId = null,
            status = DownloadState.PAUSED,
            progress = 40,
            downloadedBytes = 10L * 1024L * 1024L,
            totalBytes = 25L * 1024L * 1024L,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L,
            contentCategory = "EPISODE",
            speedBps = 2L * 1024L * 1024L,
            etaSeconds = 8L
        )

        composeTestRule.setContent {
            LaterboxTheme {
                DownloadsScreenContent(
                    uiState = DownloadsUiState(
                        selectedTab = DownloadsTab.QUEUE,
                        selectedCategory = DownloadsFilterCategory.ALL,
                        assets = emptyList(),
                        queueTasks = listOf(pausedTask),
                        storageBreakdown = emptyMap(),
                        totalStorageBytes = 0L
                    ),
                    onSettingsClick = {},
                    onOpenLinkDetail = {},
                    onMenuClick = null,
                    onSearchQueryChange = {},
                    onCategorySelected = {},
                    onTabSelected = {},
                    onToggleAssetSelection = {},
                    onSelectAllAssets = {},
                    onClearSelection = {},
                    onDeleteSelectedAssets = {},
                    onExportSelectedAssets = {},
                    onOpenAsset = {},
                    onExportAsset = {},
                    onDeleteAsset = {},
                    onToggleTaskSelection = {},
                    onSelectAllTasks = {},
                    onClearTaskSelection = {},
                    onCancelSelectedTasks = {},
                    onRetrySelectedTasks = {},
                    onDismissSelectedTasks = {},
                    onOverrideCellular = {},
                    onPauseTask = {},
                    onResumeTask = {},
                    onCancelTask = {},
                    onRetryTask = {},
                    onDismissTask = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("queue_task_progress_task_paused").assertIsDisplayed()
        composeTestRule.onNodeWithText("Paused · 40% · 10.0 MB / 25.0 MB").assertIsDisplayed()
        composeTestRule.onNodeWithText("40%", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("10.0 MB / 25.0 MB", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("2.0 MB/s", substring = true).assertDoesNotExist()
    }
}
