package com.laterbox.app.transcript

import android.net.Uri
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.transcript.data.PlaylistFetchResult
import com.laterbox.app.transcript.data.VideoInfoFetchResult
import com.laterbox.app.transcript.data.YouTubeTranscriptDataSource
import com.laterbox.app.transcript.data.YouTubeTranscriptExtractor
import com.laterbox.app.transcript.domain.CaptionTrackCandidate
import com.laterbox.app.transcript.domain.CaptionType
import com.laterbox.app.transcript.domain.ExportFormat
import com.laterbox.app.transcript.domain.FetchedVideoInfo
import com.laterbox.app.transcript.domain.TranscriptDocument
import com.laterbox.app.transcript.domain.TranscriptParagraph
import com.laterbox.app.transcript.domain.TranscriptTargetPreference
import com.laterbox.app.transcript.domain.VideoTranscriptEntry
import com.laterbox.app.transcript.domain.VideoTranscriptStatus
import com.laterbox.app.transcript.domain.YouTubeUrlTarget
import com.laterbox.app.transcript.export.ExportDestination
import com.laterbox.app.transcript.export.OpenedExportTarget
import com.laterbox.app.transcript.export.TranscriptExportManager
import com.laterbox.app.transcript.export.TranscriptTargetWriter
import com.laterbox.app.transcript.ui.YouTubeTranscriptViewModel
import com.laterbox.app.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptExportManagerAndViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class InMemoryTargetWriter(
        private val failFormats: Set<ExportFormat> = emptySet()
    ) : TranscriptTargetWriter {

        val writtenFiles = linkedMapOf<String, ByteArrayOutputStream>()
        val cleanedUris = mutableListOf<Uri>()

        override fun openOutputStream(
            filename: String,
            mimeType: String,
            destination: ExportDestination
        ): OpenedExportTarget {
            val matchedFormat = ExportFormat.entries.firstOrNull { filename.endsWith(".${it.extension}") }
            if (matchedFormat != null && matchedFormat in failFormats) {
                throw IOException("Simulated write failure for $filename")
            }
            val baos = ByteArrayOutputStream()
            writtenFiles[filename] = baos
            return OpenedExportTarget(
                uri = Uri.parse("content://test.saf/tree/transcripts/$filename"),
                actualFilename = filename,
                displayLocation = "Transcripts/$filename",
                outputStream = baos
            )
        }

        override fun cleanupFailedTarget(uri: Uri?, destination: ExportDestination) {
            if (uri != null) cleanedUris.add(uri)
        }
    }

    private fun sampleDocument(): TranscriptDocument {
        return TranscriptDocument(
            isPlaylist = false,
            title = "Composable Architecture: Deep Dive?",
            sourceUrl = "https://www.youtube.com/watch?v=comp123",
            extractedAtMillis = 1794700800000L,
            videosFound = 1,
            successfulCount = 1,
            noCaptionsCount = 0,
            inaccessibleCount = 0,
            entries = listOf(
                VideoTranscriptEntry(
                    position = 1,
                    videoId = "comp123",
                    title = "Composable Architecture: Deep Dive?",
                    url = "https://www.youtube.com/watch?v=comp123",
                    channel = "Android Makers",
                    durationSeconds = 605L,
                    captionLanguage = "English",
                    captionType = CaptionType.MANUAL,
                    status = VideoTranscriptStatus.SUCCESS,
                    paragraphs = listOf(
                        TranscriptParagraph(0L, "Hello everyone and welcome to this session."),
                        TranscriptParagraph(18_000L, "Let's inspect the exact transcript text.")
                    )
                )
            )
        )
    }

    @Test
    fun exportManager_supportsAllSevenNonEmptyFormatCombinations() = runTest {
        val doc = sampleDocument()
        val allCombinations = listOf(
            setOf(ExportFormat.MARKDOWN),
            setOf(ExportFormat.PLAIN_TEXT),
            setOf(ExportFormat.PDF),
            setOf(ExportFormat.MARKDOWN, ExportFormat.PLAIN_TEXT),
            setOf(ExportFormat.MARKDOWN, ExportFormat.PDF),
            setOf(ExportFormat.PLAIN_TEXT, ExportFormat.PDF),
            setOf(ExportFormat.MARKDOWN, ExportFormat.PLAIN_TEXT, ExportFormat.PDF)
        )

        val dest = ExportDestination.SafTree(Uri.parse("content://test.saf/tree/dir"), "Transcripts")
        for (combo in allCombinations) {
            val writer = InMemoryTargetWriter()
            val manager = TranscriptExportManager(
                context = RuntimeEnvironment.getApplication(),
                targetWriter = writer
            )
            val result = manager.exportSelectedFormats(doc, combo, dest)

            assertTrue("Expected allSucceeded for $combo", result.allSucceeded)
            assertFalse(result.anyFailed)
            assertEquals(combo.size, result.outcomes.size)
            assertEquals(combo.size, writer.writtenFiles.size)

            for (fmt in combo) {
                val matched = writer.writtenFiles.entries.firstOrNull { it.key.endsWith(".${fmt.extension}") }
                assertNotNull("Expected file ending with .${fmt.extension} for $combo", matched)
                val bytes = matched!!.value.toByteArray()
                when (fmt) {
                    ExportFormat.MARKDOWN -> {
                        val text = String(bytes, StandardCharsets.UTF_8)
                        assertTrue(text.contains("# Composable Architecture: Deep Dive? — Video Transcript"))
                        assertTrue(text.contains("Hello everyone and welcome to this session."))
                    }
                    ExportFormat.PLAIN_TEXT -> {
                        val text = String(bytes, StandardCharsets.UTF_8)
                        assertTrue(text.contains("Composable Architecture: Deep Dive? — Video Transcript"))
                        assertTrue(text.contains("Hello everyone and welcome to this session."))
                        assertFalse(text.contains("## "))
                    }
                    ExportFormat.PDF -> {
                        val pdfStr = String(bytes, StandardCharsets.ISO_8859_1)
                        assertTrue(pdfStr.startsWith("%PDF-"))
                        assertTrue(pdfStr.trimEnd().endsWith("%%EOF"))
                    }
                }
            }
        }
    }

    @Test
    fun exportManager_partialWriteFailure_reportsAccuratePerFileOutcomes() = runTest {
        val doc = sampleDocument()
        val writer = InMemoryTargetWriter(failFormats = setOf(ExportFormat.PDF))
        val manager = TranscriptExportManager(
            context = RuntimeEnvironment.getApplication(),
            targetWriter = writer
        )

        val result = manager.exportSelectedFormats(
            document = doc,
            formats = setOf(ExportFormat.MARKDOWN, ExportFormat.PLAIN_TEXT, ExportFormat.PDF),
            destination = ExportDestination.SafTree(Uri.parse("content://test.saf/tree/dir"), "Transcripts")
        )

        assertFalse(result.allSucceeded)
        assertTrue(result.anySucceeded)
        assertTrue(result.anyFailed)
        assertEquals(3, result.outcomes.size)
        assertTrue(result.outcomes.first { it.format == ExportFormat.MARKDOWN }.success)
        assertTrue(result.outcomes.first { it.format == ExportFormat.PLAIN_TEXT }.success)
        val pdfOutcome = result.outcomes.first { it.format == ExportFormat.PDF }
        assertFalse(pdfOutcome.success)
        assertTrue(pdfOutcome.errorMessage?.contains("Simulated write failure") == true)
    }

    @Test
    fun viewModel_handlesAmbiguousPlaylistChoiceZeroFormatGuardAndCancelledFolderPickerPreservingDocument() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val captionUrl = "https://captions.test/vm.json3"
        val ds = object : YouTubeTranscriptDataSource {
            override suspend fun fetchVideoInfo(videoId: String, videoUrl: String, processId: String): VideoInfoFetchResult {
                return VideoInfoFetchResult.Success(
                    FetchedVideoInfo(
                        videoId = videoId,
                        title = "Mixed URL Video",
                        url = videoUrl,
                        captionTracks = listOf(CaptionTrackCandidate("en", "English", false, mapOf("json3" to captionUrl)))
                    )
                )
            }

            override suspend fun fetchPlaylistEntries(
                playlistId: String,
                playlistUrl: String,
                processId: String,
                onPageLoaded: suspend (Int, Int?) -> Unit
            ): PlaylistFetchResult = PlaylistFetchResult.Failed(VideoTranscriptStatus.UNAVAILABLE, "unused")

            override suspend fun fetchCaptionPayload(url: String): Result<String> {
                return Result.success("""{"events":[{"tStartMs":0,"dDurationMs":2000,"segs":[{"utf8":"Preserved verbatim text."}]}]}""")
            }

            override fun cancelProcess(processId: String) {}
        }

        val writer = InMemoryTargetWriter()
        val exportManager = TranscriptExportManager(
            context = context,
            targetWriter = writer,
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
        val viewModel = YouTubeTranscriptViewModel(
            extractor = YouTubeTranscriptExtractor(ds, ioDispatcher = mainDispatcherRule.testDispatcher),
            exportManager = exportManager,
            userPreferencesRepository = UserPreferencesRepository(context)
        )

        // Opening watch?v=...&list=PL... prompts target choice
        viewModel.openForUrl("https://www.youtube.com/watch?v=vidA&list=PLcustom123")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isSheetVisible)
        assertTrue(viewModel.uiState.value.needsTargetChoice)
        assertTrue(viewModel.uiState.value.parsedTarget is YouTubeUrlTarget.VideoInPlaylist)

        // Choose single video and extract
        viewModel.chooseTargetAndExtract(TranscriptTargetPreference.VIDEO)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.needsTargetChoice)
        assertNotNull(viewModel.uiState.value.document)
        assertTrue(viewModel.uiState.value.canExport)

        // Zero format guard: unselecting all formats disables canExport
        viewModel.toggleFormat(ExportFormat.MARKDOWN)
        assertTrue(viewModel.uiState.value.selectedFormats.isEmpty())
        assertFalse(viewModel.uiState.value.canExport)

        // Select MD + TXT + PDF together
        viewModel.setSelectAllFormats(true)
        assertEquals(3, viewModel.uiState.value.selectedFormats.size)
        assertTrue(viewModel.uiState.value.canExport)

        // Cancelling folder picker must preserve extracted document
        viewModel.notifyDestinationPickerCancelled()
        assertNotNull(viewModel.uiState.value.document)
        assertTrue(viewModel.uiState.value.exportStatusNotice?.contains("still ready") == true)

        // Exporting to SAF tree writes all 3 formats
        viewModel.exportToSafTree(Uri.parse("content://test.saf/tree/dir"), "Exports", persistAsDefault = false)
        advanceUntilIdle()
        val res = viewModel.uiState.value.exportResult
        assertNotNull(res)
        assertTrue(res!!.allSucceeded)
        assertEquals(3, res.outcomes.size)
        assertEquals(3, writer.writtenFiles.size)
    }
}
