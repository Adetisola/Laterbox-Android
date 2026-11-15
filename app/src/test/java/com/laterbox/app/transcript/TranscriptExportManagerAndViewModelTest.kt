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
import kotlinx.coroutines.flow.first
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

    @Test
    fun exportManager_persistsDocumentsIntoDownloadsLibrary_andPreservesInternalCopyOnExternalFailure() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.laterbox.app.data.local.LaterboxDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val mediaRepo = com.laterbox.app.media.data.repository.MediaRepositoryImpl(
                context = context,
                assetDao = db.downloadedAssetDao(),
                taskDao = db.downloadTaskDao()
            )
            val doc = sampleDocument()

            // 1. Export directly to Laterbox Downloads Library (Documents)
            val libraryOnlyManager = TranscriptExportManager(
                context = context,
                mediaRepository = mediaRepo,
                ioDispatcher = mainDispatcherRule.testDispatcher
            )
            val libRes = libraryOnlyManager.exportSelectedFormats(
                document = doc,
                formats = setOf(ExportFormat.MARKDOWN, ExportFormat.PLAIN_TEXT),
                destination = ExportDestination.LaterboxLibrary
            )
            assertTrue(libRes.allSucceeded)
            assertTrue(libRes.anySavedToLibrary)

            val storedDocs = db.downloadedAssetDao()
                .filterAssets(com.laterbox.app.media.domain.model.MediaType.DOCUMENT.name, "")
                .first()
            assertEquals(2, storedDocs.size)
            assertTrue(storedDocs.all { it.savedLinkId == null })
            assertTrue(storedDocs.all { it.localPath.startsWith("document/") })
            assertTrue(storedDocs.all { java.io.File(context.filesDir, "media/${it.localPath}").exists() })

            // 2. External SAF export partially fails on PDF -> internal Library PDF document must still be preserved!
            val partialFailingWriter = InMemoryTargetWriter(failFormats = setOf(ExportFormat.PDF))
            val hybridManager = TranscriptExportManager(
                context = context,
                mediaRepository = mediaRepo,
                targetWriter = partialFailingWriter,
                ioDispatcher = mainDispatcherRule.testDispatcher
            )
            val hybridRes = hybridManager.exportSelectedFormats(
                document = doc,
                formats = setOf(ExportFormat.MARKDOWN, ExportFormat.PDF),
                destination = ExportDestination.SafTree(Uri.parse("content://test.saf/tree/dir"), "Exports")
            )
            assertFalse(hybridRes.allSucceeded)
            assertTrue(hybridRes.anySavedToLibrary)
            val mdOutcome = hybridRes.outcomes.first { it.format == ExportFormat.MARKDOWN }
            val pdfOutcome = hybridRes.outcomes.first { it.format == ExportFormat.PDF }
            assertTrue(mdOutcome.success && mdOutcome.savedToLibrary)
            assertFalse(pdfOutcome.success)
            assertTrue("Internal Library document must be saved even when external SAF export fails", pdfOutcome.savedToLibrary)

            val updatedDocs = db.downloadedAssetDao()
                .filterAssets(com.laterbox.app.media.domain.model.MediaType.DOCUMENT.name, "")
                .first()
            assertEquals(4, updatedDocs.size)
            val savedMdAsset = updatedDocs.firstOrNull { it.id == mdOutcome.libraryAssetId }
            assertNotNull(savedMdAsset?.exportedUri)
        } finally {
            db.close()
        }
    }

    @Test
    fun viewModel_timeoutDoesNotShowCancelled_andOpenFromAssistantDoesNotAutoExtract() = runTest {
        val context = RuntimeEnvironment.getApplication()
        var videoFetchCalls = 0
        val timeoutDs = object : YouTubeTranscriptDataSource {
            override suspend fun fetchVideoInfo(videoId: String, videoUrl: String, processId: String): VideoInfoFetchResult {
                videoFetchCalls++
                return VideoInfoFetchResult.Failed(
                    status = VideoTranscriptStatus.TIMEOUT,
                    message = "Timed out while retrieving video metadata from YouTube."
                )
            }

            override suspend fun fetchPlaylistEntries(
                playlistId: String,
                playlistUrl: String,
                processId: String,
                onPageLoaded: suspend (Int, Int?) -> Unit
            ): PlaylistFetchResult = PlaylistFetchResult.Failed(VideoTranscriptStatus.TIMEOUT, "timeout")

            override suspend fun fetchCaptionPayload(url: String): Result<String> = Result.failure(IOException("unused"))
            override fun cancelProcess(processId: String) {}
        }

        val vm = YouTubeTranscriptViewModel(
            extractor = YouTubeTranscriptExtractor(timeoutDs, ioDispatcher = mainDispatcherRule.testDispatcher),
            exportManager = TranscriptExportManager(context, ioDispatcher = mainDispatcherRule.testDispatcher),
            userPreferencesRepository = UserPreferencesRepository(context)
        )

        // Assistant opens sheet read-only with prefilled URL -> must NOT auto-extract
        vm.openFromAssistant("https://www.youtube.com/watch?v=vid_to")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isSheetVisible)
        assertTrue(vm.uiState.value.allowManualUrlEntry)
        assertFalse(vm.uiState.value.isExtracting)
        assertEquals(0, videoFetchCalls)

        // User submits extraction -> timeout produces document entry with TIMEOUT status and does NOT mark wasCancelled
        vm.submitManualUrlExtraction()
        advanceUntilIdle()
        assertEquals(1, videoFetchCalls)
        assertFalse(vm.uiState.value.wasCancelled)
        val entry = vm.uiState.value.document?.entries?.singleOrNull()
        assertNotNull(entry)
        assertEquals(VideoTranscriptStatus.TIMEOUT, entry!!.status)
        assertTrue(entry.statusMessage?.contains("Timed out") == true)
        assertFalse(vm.uiState.value.errorMessage.orEmpty().contains("was cancelled"))
    }

    @Test
    fun clipboardFormatter_copiesFullUntruncatedSingleAndPlaylistTranscripts() {
        val longParagraphs = (1..120).map { idx ->
            TranscriptParagraph(startMs = idx * 1000L, text = "Spoken sentence number $idx.")
        }
        val successEntry1 = VideoTranscriptEntry(
            position = 1,
            videoId = "v1",
            title = "Long Video",
            url = "https://www.youtube.com/watch?v=v1",
            status = VideoTranscriptStatus.SUCCESS,
            paragraphs = longParagraphs
        )
        val singleClip = com.laterbox.app.transcript.export.TranscriptClipboardFormatter.formatSingleVideo(successEntry1)
        assertTrue(singleClip.contains("Long Video"))
        assertTrue(singleClip.contains("Spoken sentence number 1."))
        assertTrue("Clipboard copy must include all paragraphs beyond 80 preview cap", singleClip.contains("Spoken sentence number 120."))

        val playlistDoc = TranscriptDocument(
            isPlaylist = true,
            title = "Series Title",
            sourceUrl = "https://www.youtube.com/playlist?list=PLseries",
            extractedAtMillis = 1794700800000L,
            videosFound = 3,
            successfulCount = 2,
            noCaptionsCount = 1,
            inaccessibleCount = 0,
            entries = listOf(
                successEntry1,
                VideoTranscriptEntry(
                    position = 2,
                    videoId = "v2",
                    title = "No Captions Clip",
                    url = "https://www.youtube.com/watch?v=v2",
                    status = VideoTranscriptStatus.NO_CAPTIONS
                ),
                VideoTranscriptEntry(
                    position = 3,
                    videoId = "v3",
                    title = "Third Video",
                    url = "https://www.youtube.com/watch?v=v3",
                    status = VideoTranscriptStatus.SUCCESS,
                    paragraphs = listOf(TranscriptParagraph(5000L, "Third video spoken text."))
                )
            )
        )
        val playlistClip = com.laterbox.app.transcript.export.TranscriptClipboardFormatter.formatPlaylist(playlistDoc)
        assertTrue(playlistClip.contains("Series Title"))
        assertTrue(playlistClip.contains("1. Long Video"))
        assertTrue(playlistClip.contains("Spoken sentence number 120."))
        assertTrue(playlistClip.contains("3. Third Video"))
        assertTrue(playlistClip.contains("Third video spoken text."))
        assertFalse(playlistClip.contains("No Captions Clip"))
    }

    @Test
    fun viewModel_retryFailedVideos_preserves20SucceededAndRetriesOnly7Failed() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val fetchedIds = mutableListOf<String>()
        var failFirstSeven = true

        val ds = object : YouTubeTranscriptDataSource {
            override suspend fun fetchVideoInfo(videoId: String, videoUrl: String, processId: String): VideoInfoFetchResult {
                fetchedIds.add(videoId)
                val idx = videoId.removePrefix("v").toInt()
                return if (failFirstSeven && idx in 1..7) {
                    VideoInfoFetchResult.Failed(
                        status = VideoTranscriptStatus.TIMEOUT,
                        message = "Timed out retrieving metadata",
                        retryable = true
                    )
                } else {
                    VideoInfoFetchResult.Success(
                        FetchedVideoInfo(
                            videoId = videoId,
                            title = "Episode $idx",
                            url = videoUrl,
                            captionTracks = listOf(
                                CaptionTrackCandidate("en", "English", false, mapOf("vtt" to "https://cap.test/$idx.vtt"))
                            )
                        )
                    )
                }
            }

            override suspend fun fetchPlaylistEntries(
                playlistId: String,
                playlistUrl: String,
                processId: String,
                onPageLoaded: suspend (Int, Int?) -> Unit
            ): PlaylistFetchResult = PlaylistFetchResult.Success(
                com.laterbox.app.transcript.domain.PlaylistResolution(
                    playlistId = playlistId,
                    title = "27-Part Course",
                    sourceUrl = playlistUrl,
                    totalCountHint = 27,
                    entries = (1..27).map { idx ->
                        com.laterbox.app.transcript.domain.PlaylistEntrySummary(
                            position = idx,
                            videoId = "v$idx",
                            title = "Episode $idx",
                            url = "https://www.youtube.com/watch?v=v$idx"
                        )
                    }
                )
            )

            override suspend fun fetchCaptionPayload(url: String): Result<String> {
                val idx = url.substringAfterLast("/").substringBefore(".vtt")
                return Result.success("WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nTranscript line for $idx.")
            }

            override fun cancelProcess(processId: String) {}
        }

        val extractor = YouTubeTranscriptExtractor(
            dataSource = ds,
            ioDispatcher = mainDispatcherRule.testDispatcher,
            resilienceConfig = com.laterbox.app.transcript.data.PlaylistResilienceConfig(
                baseInterVideoDelayMs = 1L,
                maxMetadataRetries = 0
            )
        )
        val vm = YouTubeTranscriptViewModel(
            extractor = extractor,
            exportManager = TranscriptExportManager(context, ioDispatcher = mainDispatcherRule.testDispatcher),
            userPreferencesRepository = UserPreferencesRepository(context)
        )

        vm.openForUrl("https://www.youtube.com/playlist?list=PL27vm")
        advanceUntilIdle()
        val initialDoc = vm.uiState.value.document!!
        assertEquals(20, initialDoc.successfulCount)
        assertEquals(7, initialDoc.retryableFailedCount)

        fetchedIds.clear()
        failFirstSeven = false
        vm.retryFailedVideos()
        advanceUntilIdle()

        assertEquals((1..7).map { "v$it" }, fetchedIds)
        val updatedDoc = vm.uiState.value.document!!
        assertEquals(27, updatedDoc.successfulCount)
        assertEquals(0, updatedDoc.retryableFailedCount)
        assertTrue(vm.uiState.value.canExport)
    }
}

