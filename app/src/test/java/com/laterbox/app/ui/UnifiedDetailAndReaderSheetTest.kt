package com.laterbox.app.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import com.laterbox.app.data.preferences.CardTapAction
import com.laterbox.app.data.reader.ArticleBlock
import com.laterbox.app.data.reader.ExtractedArticle
import com.laterbox.app.domain.model.ContentType
import com.laterbox.app.domain.model.SavedLink
import com.laterbox.app.media.domain.model.AssetFileStatus
import com.laterbox.app.media.domain.model.MediaAsset
import com.laterbox.app.media.domain.model.MediaType
import com.laterbox.app.ui.reader.ArticleReaderUiState
import com.laterbox.app.ui.sheets.DetailSheetTab
import com.laterbox.app.ui.sheets.SavedItemDetailSheet
import com.laterbox.app.ui.sheets.SavedItemDetailUiState
import com.laterbox.app.ui.sheets.isReaderEligible
import com.laterbox.app.ui.theme.LaterboxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UnifiedDetailAndReaderSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun sampleArticleLink(id: Long = 1L, type: ContentType = ContentType.ARTICLE) = SavedLink(
        id = id,
        originalUrl = "https://example.com/posts/$id",
        normalizedUrl = "https://example.com/posts/$id",
        title = "Deep Dive Into Calm Reading UI",
        description = "An exploration of local-first typography and offline reading architecture.",
        siteName = "Example Engineering",
        domain = "example.com",
        contentType = type,
        personalNote = "Re-read section on layout stability."
    )

    private fun sampleExtractedArticle(title: String = "Deep Dive Into Calm Reading UI"): ExtractedArticle {
        val paragraphs = (1..20).map { idx ->
            ArticleBlock.Paragraph("Paragraph $idx: Careful typography and calm chrome help readers stay focused across long essays.")
        }
        return ExtractedArticle(
            url = "https://example.com/posts/1",
            title = title,
            byline = "Ada Lovelace",
            siteName = "Example Engineering",
            leadImageUrl = null,
            blocks = listOf(ArticleBlock.Heading(2, "Introduction")) + paragraphs,
            wordCount = 340,
            estimatedReadMinutes = 2,
            fetchedAt = 1_700_000_000_000L,
            fromCache = true
        )
    }

    @Test
    fun readerEligibility_matchesContentTypeAndExcludesYouTube() {
        assertTrue(sampleArticleLink(type = ContentType.ARTICLE).isReaderEligible())
        assertTrue(sampleArticleLink(type = ContentType.WEBSITE).isReaderEligible())
        assertTrue(sampleArticleLink(type = ContentType.DISCUSSION).isReaderEligible())
        assertFalse(sampleArticleLink(type = ContentType.VIDEO).isReaderEligible())
        assertFalse(sampleArticleLink(type = ContentType.PRODUCT).isReaderEligible())
        assertFalse(
            sampleArticleLink(type = ContentType.WEBSITE)
                .copy(originalUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                .isReaderEligible()
        )
    }

    @Test
    fun unifiedSheet_articleShowsTabsAndSwitchesViaTabsButtonAndSwipe_whilePreservingDraftAndMetrics() {
        val link = sampleArticleLink()
        var uiState by mutableStateOf(
            SavedItemDetailUiState(
                link = link,
                editedTitle = link.title,
                editedNote = link.personalNote.orEmpty()
            )
        )
        val readerUiState by mutableStateOf<ArticleReaderUiState>(ArticleReaderUiState.Loaded(sampleExtractedArticle()))
        var activeTab by mutableStateOf(DetailSheetTab.DETAILS)
        var readerOpensRecorded = 0
        var readerMetricRecordedInSession by mutableStateOf(false)
        var closeCalls = 0
        var refreshCalls = 0

        composeTestRule.setContent {
            val density = LocalDensity.current
            val sheetState = remember(density) {
                SheetState(
                    skipPartiallyExpanded = true,
                    density = density,
                    initialValue = SheetValue.Expanded
                )
            }
            LaterboxTheme {
                LaunchedEffect(activeTab) {
                    if (activeTab == DetailSheetTab.READER && !readerMetricRecordedInSession) {
                        readerMetricRecordedInSession = true
                        readerOpensRecorded++
                    }
                }
                SavedItemDetailSheet(
                    uiState = uiState,
                    sheetState = sheetState,
                    initialTab = DetailSheetTab.DETAILS,
                    sessionKey = 1,
                    readerUiState = readerUiState,
                    fontScale = 1.0f,
                    onRefreshReader = { refreshCalls++ },
                    onTabChanged = { activeTab = it },
                    onTitleChange = { uiState = uiState.copy(editedTitle = it) },
                    onNoteChange = { uiState = uiState.copy(editedNote = it) },
                    onToggleCollection = {},
                    onCreateCollection = {},
                    onSaveEdits = {},
                    onToggleRead = {},
                    onToggleArchive = {},
                    onDelete = {},
                    onQuickOpen = {},
                    onRetryMetadata = {},
                    onDismissRequest = { closeCalls++ }
                )
            }
        }

        composeTestRule.waitForIdle()
        // Unified sheet renders segmented switcher + pager on Details tab
        composeTestRule.onNodeWithTag("saved_item_detail_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_reader_tab_switcher").assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_tab_details").assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_tab_reader").assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_hub_scroll").assertIsDisplayed()
        composeTestRule.onNodeWithTag("reader_refresh_button").assertDoesNotExist()
        assertEquals(0, readerOpensRecorded)

        // Open editor and make draft edits before switching
        composeTestRule.onNodeWithTag("detail_edit_toggle").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Title").performTextReplacement("Deep Dive Into Calm Reading UI v2")
        composeTestRule.waitForIdle()
        assertEquals("Deep Dive Into Calm Reading UI v2", uiState.editedTitle)

        // Switch via Reader tab -> displays Reader, shows Refresh button, counts readerOpens once
        composeTestRule.onNodeWithTag("detail_tab_reader").performClick()
        composeTestRule.waitForIdle()
        assertEquals(DetailSheetTab.READER, activeTab)
        assertEquals(1, readerOpensRecorded)
        composeTestRule.onNodeWithTag("article_reader_content").assertIsDisplayed()
        composeTestRule.onNodeWithTag("reader_refresh_button").assertIsDisplayed().performClick()
        assertEquals(1, refreshCalls)

        // Horizontal swipe right back to Details preserves pending Title edit
        composeTestRule.onNodeWithTag("detail_reader_pager").performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()
        assertEquals(DetailSheetTab.DETAILS, activeTab)
        assertEquals("Deep Dive Into Calm Reading UI v2", uiState.editedTitle)
        composeTestRule.onNodeWithText("Save Changes").assertExists()

        // Swipe left back to Reader -> metric does NOT increment a second time in the same session
        composeTestRule.onNodeWithTag("detail_reader_pager").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        assertEquals(DetailSheetTab.READER, activeTab)
        assertEquals(1, readerOpensRecorded)

        // Switch to Details and use "Read in Laterbox" button to jump to Reader cleanly
        composeTestRule.onNodeWithTag("detail_tab_details").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Read in Laterbox").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(DetailSheetTab.READER, activeTab)
        assertEquals(1, readerOpensRecorded)

        // More menu & Close remain accessible on Reader tab
        composeTestRule.onNodeWithTag("detail_sheet_more_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Copy link").assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_sheet_close_button").performClick()
        assertEquals(1, closeCalls)
    }

    @Test
    fun unifiedSheet_hidesReaderTabForVideoAndYouTube_andPreservesMediaAndTranscriptActions() {
        val youtubeLink = SavedLink(
            id = 42L,
            originalUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            normalizedUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            title = "Offline Architecture Talk",
            domain = "youtube.com",
            contentType = ContentType.VIDEO
        )
        val asset = MediaAsset(
            id = "asset_1",
            savedLinkId = 42L,
            sourcePlatform = "YouTube",
            originalSourceUrl = youtubeLink.originalUrl,
            title = "Offline Architecture Talk.mp4",
            mimeType = "video/mp4",
            mediaType = MediaType.VIDEO,
            localPath = "/tmp/fake.mp4",
            exportedUri = null,
            fileStatus = AssetFileStatus.AVAILABLE,
            fileSize = 4_000_000L,
            durationMs = 95_000L,
            width = 1920,
            height = 1080,
            qualityLabel = "1080p",
            checksum = "abc",
            createdAt = 1_700_000_000_000L
        )
        var extractClicked = 0
        var openOfflineClicked = 0

        composeTestRule.setContent {
            val density = LocalDensity.current
            val sheetState = remember(density) {
                SheetState(
                    skipPartiallyExpanded = true,
                    density = density,
                    initialValue = SheetValue.Expanded
                )
            }
            LaterboxTheme(darkTheme = true) {
                SavedItemDetailSheet(
                    uiState = SavedItemDetailUiState(
                        link = youtubeLink,
                        editedTitle = youtubeLink.title,
                        assets = listOf(asset)
                    ),
                    sheetState = sheetState,
                    // Even when preference is IN_APP_READER, non-eligible video must open on Details with no switcher
                    initialTab = if (CardTapAction.IN_APP_READER == CardTapAction.IN_APP_READER && youtubeLink.isReaderEligible()) {
                        DetailSheetTab.READER
                    } else {
                        DetailSheetTab.DETAILS
                    },
                    onTitleChange = {},
                    onNoteChange = {},
                    onToggleCollection = {},
                    onCreateCollection = {},
                    onSaveEdits = {},
                    onToggleRead = {},
                    onToggleArchive = {},
                    onDelete = {},
                    onQuickOpen = {},
                    onRetryMetadata = {},
                    onExtractTranscript = { extractClicked++ },
                    onOpenOfflineMedia = { openOfflineClicked++ },
                    onDismissRequest = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("detail_reader_tab_switcher").assertDoesNotExist()
        composeTestRule.onNodeWithTag("detail_tab_reader").assertDoesNotExist()
        composeTestRule.onNodeWithText("▶ Play Offline").assertIsDisplayed().performClick()
        assertEquals(1, openOfflineClicked)

        composeTestRule.onNodeWithText("Extract Transcript").performScrollTo().performClick()
        assertEquals(1, extractClicked)
        composeTestRule.onNodeWithText("Media Library").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun unifiedSheet_opensDirectlyInReaderForInAppReaderPreference_andHandlesErrorLoadingAndScrollIsolation() {
        val link = sampleArticleLink(id = 7L, type = ContentType.WEBSITE)
        var readerUiState by mutableStateOf<ArticleReaderUiState>(ArticleReaderUiState.Loading)
        var refreshCalls = 0
        var openOriginalCalls = 0
        var readArchiveCalls = 0

        composeTestRule.setContent {
            val density = LocalDensity.current
            val sheetState = remember(density) {
                SheetState(
                    skipPartiallyExpanded = true,
                    density = density,
                    initialValue = SheetValue.Expanded
                )
            }
            LaterboxTheme(darkTheme = false) {
                SavedItemDetailSheet(
                    uiState = SavedItemDetailUiState(
                        link = link,
                        editedTitle = link.title,
                        editedNote = link.personalNote.orEmpty()
                    ),
                    sheetState = sheetState,
                    initialTab = DetailSheetTab.READER,
                    sessionKey = 7,
                    readerUiState = readerUiState,
                    fontScale = 1.25f,
                    onRefreshReader = { refreshCalls++ },
                    onMarkReadAndArchive = { readArchiveCalls++ },
                    onTitleChange = {},
                    onNoteChange = {},
                    onToggleCollection = {},
                    onCreateCollection = {},
                    onSaveEdits = {},
                    onToggleRead = {},
                    onToggleArchive = {},
                    onDelete = {},
                    onQuickOpen = { openOriginalCalls++ },
                    onRetryMetadata = {},
                    onDismissRequest = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("reader_loading_state").assertIsDisplayed()

        // Error state offers fallback actions cleanly
        readerUiState = ArticleReaderUiState.Error("Offline and not cached")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("reader_error_state").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open Original in Browser ↗").performClick()
        assertEquals(1, openOriginalCalls)
        composeTestRule.onNodeWithText("Retry Reader Extraction").performClick()
        assertEquals(1, refreshCalls)

        // Loaded state renders cleanly with independent scroll and Read & Archive action
        readerUiState = ArticleReaderUiState.Loaded(sampleExtractedArticle())
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("reader_loaded_scroll").assertIsDisplayed()
        composeTestRule.onNodeWithTag("reader_loaded_scroll").performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()

        // Switch to Details and verify Details starts unscrolled
        composeTestRule.onNodeWithTag("detail_tab_details").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithText("Deep Dive Into Calm Reading UI").onFirst().assertIsDisplayed()
        composeTestRule.onNodeWithText("Read in Laterbox").assertIsDisplayed()

        // Switch back to Reader and trigger Read & Archive
        composeTestRule.onNodeWithTag("detail_tab_reader").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("reader_read_archive_button").performClick()
        assertEquals(1, readArchiveCalls)
    }
}
