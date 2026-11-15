package com.laterbox.app.transcript

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.laterbox.app.domain.model.ContentType
import com.laterbox.app.domain.model.SavedLink
import com.laterbox.app.transcript.domain.CaptionType
import com.laterbox.app.transcript.domain.ExportFormat
import com.laterbox.app.transcript.domain.MultiFormatExportResult
import com.laterbox.app.transcript.domain.SingleExportOutcome
import com.laterbox.app.transcript.domain.TranscriptDocument
import com.laterbox.app.transcript.domain.TranscriptExtractionProgress
import com.laterbox.app.transcript.domain.TranscriptExtractionStage
import com.laterbox.app.transcript.domain.TranscriptParagraph
import com.laterbox.app.transcript.domain.VideoTranscriptEntry
import com.laterbox.app.transcript.domain.VideoTranscriptStatus
import com.laterbox.app.transcript.ui.YouTubeTranscriptSheetContent
import com.laterbox.app.transcript.ui.YouTubeTranscriptUiState
import com.laterbox.app.ui.sheets.SavedItemDetailSheet
import com.laterbox.app.ui.sheets.SavedItemDetailUiState
import com.laterbox.app.ui.sheets.YouTubeTranscriptDetailCard
import com.laterbox.app.ui.theme.LaterboxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YouTubeTranscriptUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun savedItemDetailSheet_rendersExtractTranscriptForYouTubeLinksOnly() {
        var activeLink by mutableStateOf(
            SavedLink(
                id = 1L,
                originalUrl = "https://www.youtube.com/watch?v=abc123",
                normalizedUrl = "https://www.youtube.com/watch?v=abc123",
                title = "YouTube Video",
                domain = "youtube.com",
                contentType = ContentType.VIDEO
            )
        )

        composeTestRule.setContent {
            val density = LocalDensity.current
            LaterboxTheme {
                SavedItemDetailSheet(
                    uiState = SavedItemDetailUiState(link = activeLink, editedTitle = activeLink.title),
                    sheetState = SheetState(
                        skipPartiallyExpanded = true,
                        density = density,
                        initialValue = SheetValue.Expanded
                    ),
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
                    onDismissRequest = {},
                    onExtractTranscript = {}
                )
            }
        }

        composeTestRule.onNodeWithText("YouTube Transcripts").assertExists()
        composeTestRule.onNodeWithText("Extract Transcript").assertExists()

        activeLink = activeLink.copy(
            originalUrl = "https://example.com/article",
            normalizedUrl = "https://example.com/article",
            domain = "example.com",
            contentType = ContentType.WEBSITE
        )
        composeTestRule.onNodeWithText("Extract Transcript").assertDoesNotExist()
    }

    @Test
    fun youTubeTranscriptDetailCard_triggersExtractCallback() {
        var clicks = 0
        composeTestRule.setContent {
            LaterboxTheme {
                YouTubeTranscriptDetailCard(
                    url = "https://www.youtube.com/playlist?list=PLtest",
                    onExtractTranscript = { clicks++ }
                )
            }
        }

        composeTestRule.onNodeWithText("Extract captions for all playlist videos and export as MD, TXT, or PDF")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Extract Transcript").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun youTubeTranscriptSheet_displaysPlaylistSummaryMultiFormatCheckboxesAndExportResult() {
        val doc = TranscriptDocument(
            isPlaylist = true,
            title = "Architecture Playlist",
            sourceUrl = "https://www.youtube.com/playlist?list=PLarch",
            extractedAtMillis = 1794700800000L,
            videosFound = 3,
            successfulCount = 2,
            noCaptionsCount = 1,
            inaccessibleCount = 0,
            entries = listOf(
                VideoTranscriptEntry(
                    position = 1,
                    videoId = "v1",
                    title = "Intro Talk",
                    url = "https://www.youtube.com/watch?v=v1",
                    captionLanguage = "English",
                    captionType = CaptionType.MANUAL,
                    status = VideoTranscriptStatus.SUCCESS,
                    paragraphs = listOf(TranscriptParagraph(0L, "Exact spoken transcript paragraph."))
                ),
                VideoTranscriptEntry(
                    position = 2,
                    videoId = "v2",
                    title = "Silent Clip",
                    url = "https://www.youtube.com/watch?v=v2",
                    status = VideoTranscriptStatus.NO_CAPTIONS,
                    statusMessage = "No accessible captions were found for this video."
                )
            )
        )

        var state by mutableStateOf(
            YouTubeTranscriptUiState(
                isSheetVisible = true,
                sourceUrl = doc.sourceUrl,
                document = doc,
                selectedFormats = setOf(ExportFormat.MARKDOWN),
                exportResult = MultiFormatExportResult(
                    outcomes = listOf(
                        SingleExportOutcome(
                            format = ExportFormat.MARKDOWN,
                            filename = "Architecture_Playlist.md",
                            success = true,
                            uri = "content://test/1",
                            displayLocation = "Exports/Architecture_Playlist.md"
                        )
                    )
                )
            )
        )

        composeTestRule.setContent {
            LaterboxTheme {
                YouTubeTranscriptSheetContent(
                    uiState = state,
                    onChooseTarget = {},
                    onCancelExtraction = {},
                    onRetryExtraction = {},
                    onToggleFormat = { fmt ->
                        val next = state.selectedFormats.toMutableSet()
                        if (!next.add(fmt)) next.remove(fmt)
                        state = state.copy(selectedFormats = next)
                    },
                    onSetSelectAllFormats = { all ->
                        state = state.copy(selectedFormats = if (all) ExportFormat.entries.toSet() else emptySet())
                    },
                    hasWritableSafFolder = { false },
                    onLaunchFolderPicker = {},
                    onExportToSafTree = { _, _, _ -> },
                    onExportToDownloads = {},
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithText("YouTube Transcripts").assertIsDisplayed()
        composeTestRule.onNodeWithText("• 3 videos found.").assertIsDisplayed()
        composeTestRule.onNodeWithText("• 2 transcripts extracted successfully.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Markdown (.md)").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Plain text (.txt)").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("PDF (.pdf)").performScrollTo().assertIsDisplayed()

        // Toggle Plain text & PDF on -> all 3 formats selected simultaneously
        composeTestRule.onNodeWithText("Plain text (.txt)").performScrollTo().performClick()
        composeTestRule.onNodeWithText("PDF (.pdf)").performScrollTo().performClick()
        assertEquals(3, state.selectedFormats.size)
        composeTestRule.onNodeWithText("Export Selected Formats (3)").performScrollTo().assertIsDisplayed()

        // Unselect all -> zero format guard shown
        composeTestRule.onNodeWithText("Clear All").performScrollTo().performClick()
        assertTrue(state.selectedFormats.isEmpty())
        composeTestRule.onNodeWithText("Select at least one export format above to enable export.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun youTubeTranscriptSheet_showsProgressAndCancelDuringExtraction() {
        var cancelClicks = 0
        composeTestRule.setContent {
            LaterboxTheme {
                YouTubeTranscriptSheetContent(
                    uiState = YouTubeTranscriptUiState(
                        isSheetVisible = true,
                        sourceUrl = "https://www.youtube.com/playlist?list=PLprogress",
                        isExtracting = true,
                        progress = TranscriptExtractionProgress(
                            stage = TranscriptExtractionStage.EXTRACTING_VIDEO,
                            currentVideoIndex = 4,
                            totalVideos = 18,
                            currentVideoTitle = "Scaling Storage Engines",
                            successfulCount = 3,
                            message = "Extracting video 4 of 18"
                        )
                    ),
                    onChooseTarget = {},
                    onCancelExtraction = { cancelClicks++ },
                    onRetryExtraction = {},
                    onToggleFormat = {},
                    onSetSelectAllFormats = {},
                    hasWritableSafFolder = { false },
                    onLaunchFolderPicker = {},
                    onExportToSafTree = { _, _, _ -> },
                    onExportToDownloads = {},
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Extracting video 4 of 18").assertIsDisplayed()
        composeTestRule.onNodeWithText("Scaling Storage Engines").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancelClicks)
    }
}
