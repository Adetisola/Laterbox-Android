package com.laterbox.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.laterbox.app.BuildConfig
import com.laterbox.app.data.preferences.UserPreferences
import com.laterbox.app.diagnostics.LocalLibraryMetrics
import com.laterbox.app.diagnostics.ProductMetricsTracker
import com.laterbox.app.domain.model.Collection
import com.laterbox.app.domain.model.CollectionWithCount
import com.laterbox.app.storage.AppStorageReport
import com.laterbox.app.ui.collections.CollectionsScreen
import com.laterbox.app.ui.collections.CollectionsUiState
import com.laterbox.app.ui.components.CustomReminderPickerDialog
import com.laterbox.app.ui.components.ReminderContext
import com.laterbox.app.ui.settings.AppStorageSettingsScreen
import com.laterbox.app.ui.settings.MoviesSettingsScreen
import com.laterbox.app.ui.settings.RemindersAiSettingsScreen
import com.laterbox.app.ui.settings.SettingsScreen
import com.laterbox.app.ui.theme.LaterboxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsCollectionsAndReminderUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun customReminderPickerDialog_rendersContextAwareCopyForMovieSeriesAndLink() {
        composeTestRule.setContent {
            LaterboxTheme {
                CustomReminderPickerDialog(
                    contextType = ReminderContext.MOVIE,
                    onDismissRequest = {},
                    onConfirm = {}
                )
            }
        }
        composeTestRule
            .onNodeWithText("Choose when you would like Laterbox to remind you to revisit this movie.")
            .assertIsDisplayed()
    }

    @Test
    fun customReminderPickerDialog_rendersSeriesCopy() {
        composeTestRule.setContent {
            LaterboxTheme {
                CustomReminderPickerDialog(
                    contextType = ReminderContext.TV_SERIES,
                    onDismissRequest = {},
                    onConfirm = {}
                )
            }
        }
        composeTestRule
            .onNodeWithText("Choose when you would like Laterbox to remind you to revisit this series.")
            .assertIsDisplayed()
    }

    @Test
    fun customReminderPickerDialog_rendersLinkCopyByDefault() {
        composeTestRule.setContent {
            LaterboxTheme {
                CustomReminderPickerDialog(
                    onDismissRequest = {},
                    onConfirm = {}
                )
            }
        }
        composeTestRule
            .onNodeWithText("Choose when you would like Laterbox to remind you to revisit this link.")
            .assertIsDisplayed()
    }

    @Test
    fun settingsScreen_keepsStickyHeaderFixedRemovesSubtitlesAndCleansAboutAndInsights() {
        val metrics = ProductMetricsTracker.computeMetrics(
            inboxCount = 5,
            inboxUnreadCount = 2,
            archivedCount = 1,
            totalReadCount = 4,
            activeScheduledReminders = 2,
            mediaAssetsCount = 3,
            estimatedStorageBytes = 5L * 1024L * 1024L,
            prefs = UserPreferences(),
            collectionsCount = 3
        )
        assertEquals(3, metrics.collectionsCount)

        composeTestRule.setContent {
            LaterboxTheme {
                SettingsScreen(
                    preferences = UserPreferences(),
                    onThemeModeChange = {},
                    onNavigateToAppearanceProfile = {},
                    onNavigateToRemindersAi = {},
                    onNavigateToCaptureShortcuts = {},
                    onNavigateToReadingBehavior = {},
                    onNavigateToMediaDownloads = {},
                    onNavigateToDataStorage = {},
                    onNavigateToAboutPrivacy = {},
                    onBackClick = {},
                    localMetrics = metrics
                )
            }
        }

        val initialHeaderTop = composeTestRule
            .onNodeWithTag("settings_sticky_header")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
            .top

        // Category subtitles removed
        composeTestRule.onNodeWithText("Appearance & Profile").assertIsDisplayed()
        composeTestRule.onNodeWithText("Theme, reader font, and greeting.").assertDoesNotExist()
        composeTestRule.onNodeWithText("Schedules, notifications, and assistant.").assertDoesNotExist()
        composeTestRule.onNodeWithText("Backups, storage, and cache cleanup.").assertDoesNotExist()

        // Scroll content down and confirm sticky header does not move
        composeTestRule.onNodeWithTag("settings_scroll_content").performTouchInput { swipeUp() }
        val scrolledHeaderTop = composeTestRule
            .onNodeWithTag("settings_sticky_header")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        assertEquals(initialHeaderTop, scrolledHeaderTop, 0.5f)

        // About cleanup & Insights redesign
        composeTestRule.onNodeWithText("v${BuildConfig.VERSION_NAME}").performScrollTo().assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Built-In Product & Reliability Analytics & Third-Party Services")
            .assertDoesNotExist()
        composeTestRule.onNodeWithTag("insights_metrics_row").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Saved Items").assertIsDisplayed()
        composeTestRule.onNodeWithText("Collections").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 Collections").assertIsDisplayed()
        composeTestRule.onNodeWithText("Saved Library").assertDoesNotExist()
        composeTestRule.onNodeWithText("Reading Progress").assertDoesNotExist()
    }

    @Test
    fun remindersAiSettingsScreen_hidesGeminiApiKeyInputWhileKeepingButtons() {
        composeTestRule.setContent {
            LaterboxTheme {
                RemindersAiSettingsScreen(
                    preferences = UserPreferences(),
                    onSmartRemindersEnabledChange = {},
                    onMorningEveningEnabledChange = {},
                    onMorningTimeChange = { _, _ -> },
                    onEveningTimeChange = { _, _ -> },
                    onWeekendRoundupEnabledChange = {},
                    onWeekendRoundupTimeChange = { _, _ -> },
                    onDailyDigestEnabledChange = {},
                    onDailyDigestTimeChange = { _, _ -> },
                    onDailyDigestWeekdaysOnlyChange = {},
                    onCustomScheduleEnabledChange = {},
                    onCustomScheduleDaysChange = {},
                    onCustomScheduleTimeChange = { _, _ -> },
                    onSmartReminderPersistenceChange = {},
                    aiConnectionStatus = null,
                    onGeminiApiKeyChange = {},
                    onTestAiConnection = {},
                    onSendTestSmartDigest = { true },
                    onBackClick = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Enter Google AI Studio access key (optional)").assertDoesNotExist()
        composeTestRule.onNodeWithText("Google AI Studio").assertDoesNotExist()
        composeTestRule.onNodeWithText("Test AI Connection").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Send Test Smart Reminder").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun moviesSettingsScreen_placesActivityBeforePlayerGesturesWithoutSubtitle() {
        composeTestRule.setContent {
            LaterboxTheme {
                MoviesSettingsScreen(
                    preferences = UserPreferences(),
                    onDoubleTapSeekSecondsChange = {},
                    onSwipeToSeekEnabledChange = {},
                    onSwipeSeekSensitivityChange = {},
                    onBrightnessGestureEnabledChange = {},
                    onVolumeGestureEnabledChange = {},
                    onAutoplayNextEpisodeChange = {},
                    onAutoplayCountdownSecondsChange = {},
                    onDefaultQualityHeightChange = {},
                    onSubtitleStyleChange = {},
                    onOpenViewingHistory = {},
                    onBackClick = {}
                )
            }
        }

        val activityTop = composeTestRule
            .onNodeWithText("Activity")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        val playerGesturesTop = composeTestRule
            .onNodeWithText("Player Gestures")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
            .top

        assertTrue(
            "Expected Activity ($activityTop) to appear above Player Gestures ($playerGesturesTop)",
            activityTop < playerGesturesTop
        )
        composeTestRule.onNodeWithText("Viewing History").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Browse and manage watched movies, episodes, and trailers")
            .assertDoesNotExist()
    }

    @Test
    fun appStorageSettingsScreen_removesStorageRowSubLabels() {
        val report = AppStorageReport(
            downloadedMediaBytes = 10L * 1024 * 1024,
            inProgressDownloadsBytes = 0L,
            streamPlaybackCacheBytes = 4L * 1024 * 1024,
            imageCacheBytes = 2L * 1024 * 1024,
            databaseAndMetadataBytes = 1024L * 1024,
            extractorRuntimesBytes = 0L,
            tempAndOtherCacheBytes = 0L,
            safeClearableBytes = 6L * 1024 * 1024,
            totalUsedBytes = 17L * 1024 * 1024,
            deviceFreeBytes = 1000L * 1024 * 1024,
            deviceTotalBytes = 4000L * 1024 * 1024,
            storagePressureHigh = false,
            cachedApiEntryCount = 2
        )

        composeTestRule.setContent {
            LaterboxTheme {
                AppStorageSettingsScreen(
                    report = report,
                    isLoading = false,
                    isClearingSafeCache = false,
                    feedbackMessage = null,
                    onRefresh = {},
                    onClearSafeCache = {},
                    onRunMaintenance = {},
                    onDismissFeedback = {},
                    onBackClick = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("storage_downloaded_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Offline movies, shows, media & subtitles").assertDoesNotExist()
        composeTestRule.onNodeWithTag("storage_cache_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Stream playback buffer, poster images & temporary files").assertDoesNotExist()
        composeTestRule.onNodeWithTag("storage_app_data_row").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Saved library, preferences, and download tools").assertDoesNotExist()
    }

    @Test
    fun collectionsScreen_usesItemPluralizationForZeroOneAndMultipleItems() {
        val uiState = CollectionsUiState(
            isLoading = false,
            collections = listOf(
                CollectionWithCount(Collection(id = 1L, name = "Empty Shelf"), itemCount = 0),
                CollectionWithCount(Collection(id = 2L, name = "Single Shelf"), itemCount = 1),
                CollectionWithCount(Collection(id = 3L, name = "Watchlist"), itemCount = 3)
            )
        )

        composeTestRule.setContent {
            LaterboxTheme {
                CollectionsScreen(
                    uiState = uiState,
                    onCollectionClick = {},
                    onOpenCreateDialog = {},
                    onCloseCreateDialog = {},
                    onCreateCollection = {},
                    onOpenRenameDialog = {},
                    onCloseRenameDialog = {},
                    onRenameCollection = { _, _ -> },
                    onOpenDeleteDialog = {},
                    onCloseDeleteDialog = {},
                    onDeleteCollection = {}
                )
            }
        }

        composeTestRule.onNodeWithText("0 items").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 item").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 items").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 links").assertDoesNotExist()
        composeTestRule.onNodeWithText("1 link").assertDoesNotExist()
    }
}
