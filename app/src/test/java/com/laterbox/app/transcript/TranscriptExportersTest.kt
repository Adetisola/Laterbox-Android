package com.laterbox.app.transcript.export

import com.laterbox.app.transcript.domain.CaptionType
import com.laterbox.app.transcript.domain.TranscriptDocument
import com.laterbox.app.transcript.domain.TranscriptParagraph
import com.laterbox.app.transcript.domain.TranscriptSegment
import com.laterbox.app.transcript.domain.VideoTranscriptEntry
import com.laterbox.app.transcript.domain.VideoTranscriptStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptExportersTest {

    private fun samplePlaylistDocument(): TranscriptDocument {
        return TranscriptDocument(
            isPlaylist = true,
            title = "Product Strategy: Series / 2026",
            sourceUrl = "https://www.youtube.com/playlist?list=PLstrategy2026",
            extractedAtMillis = 1794700800000L, // 2026-11-15 UTC
            videosFound = 3,
            successfulCount = 2,
            noCaptionsCount = 1,
            inaccessibleCount = 0,
            entries = listOf(
                VideoTranscriptEntry(
                    position = 1,
                    videoId = "vid1",
                    title = "Understanding Product Growth",
                    url = "https://www.youtube.com/watch?v=vid1",
                    channel = "Growth Lab",
                    durationSeconds = 1475L,
                    captionLanguage = "English",
                    captionType = CaptionType.AUTOMATIC,
                    status = VideoTranscriptStatus.SUCCESS,
                    segments = listOf(
                        TranscriptSegment(0L, 12000L, "Transcript begins here verbatim."),
                        TranscriptSegment(35000L, 45000L, "The next section begins here.")
                    ),
                    paragraphs = listOf(
                        TranscriptParagraph(0L, "Transcript begins here verbatim."),
                        TranscriptParagraph(35000L, "The next section begins here.")
                    )
                ),
                VideoTranscriptEntry(
                    position = 2,
                    videoId = "vid2",
                    title = "Building Better Products",
                    url = "https://www.youtube.com/watch?v=vid2",
                    status = VideoTranscriptStatus.SUCCESS,
                    paragraphs = listOf(
                        TranscriptParagraph(null, "Transcript content goes here without timestamps.")
                    )
                ),
                VideoTranscriptEntry(
                    position = 3,
                    videoId = "vid3",
                    title = "Product Retention Strategies",
                    url = "https://www.youtube.com/watch?v=vid3",
                    status = VideoTranscriptStatus.NO_CAPTIONS,
                    statusMessage = "No accessible captions were found for this video."
                )
            )
        )
    }

    @Test
    fun formatting_sanitizesFilenamesAndFormatsTimes() {
        val sanitized = TranscriptFormatting.sanitizeFilename("  My: Bad/File*Name? <2026> | Test.. ")
        assertFalse(sanitized.contains(":"))
        assertFalse(sanitized.contains("/"))
        assertFalse(sanitized.contains("*"))
        assertFalse(sanitized.contains("?"))
        assertFalse(sanitized.contains("<"))
        assertFalse(sanitized.contains(">"))
        assertFalse(sanitized.contains("|"))
        assertEquals("YouTube_Transcript", TranscriptFormatting.sanitizeFilename("///..."))
        assertEquals("24:35", TranscriptFormatting.formatDuration(1475L))
        assertEquals("1:01:05", TranscriptFormatting.formatDuration(3665L))
        assertEquals("[00:35]", TranscriptFormatting.formatTimestamp(35000L))
        assertEquals("[1:02:03]", TranscriptFormatting.formatTimestamp(3723000L))
    }

    @Test
    fun markdownExporter_generatesStructuredDocumentWithWorkingAnchors() {
        val doc = samplePlaylistDocument()
        val md = MarkdownTranscriptExporter(TimeZone.getTimeZone("UTC")).exportToString(doc)

        assertTrue(md.startsWith("# Product Strategy: Series / 2026 — Video Transcripts\n"))
        assertTrue(md.contains("Source: https://www.youtube.com/playlist?list=PLstrategy2026"))
        assertTrue(md.contains("Date Extracted: 2026-11-15"))
        assertTrue(md.contains("Videos Found: 3"))
        assertTrue(md.contains("Successful Transcripts: 2"))
        assertTrue(md.contains("No Available Captions: 1"))

        assertTrue(md.contains("## Contents"))
        assertTrue(md.contains("1. [Understanding Product Growth](#01--understanding-product-growth)"))
        assertTrue(md.contains("2. [Building Better Products](#02--building-better-products)"))
        assertTrue(md.contains("3. [Product Retention Strategies](#03--product-retention-strategies)"))

        assertTrue(md.contains("## 01 — Understanding Product Growth"))
        assertTrue(md.contains("Channel: Growth Lab"))
        assertTrue(md.contains("Duration: 24:35"))
        assertTrue(md.contains("Playlist Position: 1"))
        assertTrue(md.contains("Caption Language: English"))
        assertTrue(md.contains("Caption Type: Automatic"))
        assertTrue(md.contains("[00:00] Transcript begins here verbatim."))
        assertTrue(md.contains("[00:35] The next section begins here."))
        assertTrue(md.contains("Extraction Status: Transcript unavailable"))
        assertTrue(md.contains("No accessible captions were found for this video."))
    }

    @Test
    fun plainTextExporter_generatesReadableTextWithZeroMarkdownOrHtmlTokens() {
        val doc = samplePlaylistDocument()
        val txt = PlainTextTranscriptExporter(TimeZone.getTimeZone("UTC")).exportToString(doc)

        assertTrue(txt.contains("Product Strategy: Series / 2026 — Video Transcripts"))
        assertTrue(txt.contains("CONTENTS"))
        assertTrue(txt.contains("1. Understanding Product Growth"))
        assertTrue(txt.contains("01 — Understanding Product Growth"))
        assertTrue(txt.contains("[00:00] Transcript begins here verbatim."))
        assertTrue(txt.contains("========================================================================"))
        assertTrue(txt.contains("No accessible captions were found for this video."))

        // Verify no Markdown headings, bold/italic/horizontal-rule tokens, or HTML tags
        assertFalse(txt.contains("## "))
        assertFalse(txt.contains("### "))
        assertFalse(txt.contains("](#"))
        assertFalse(txt.contains("**"))
        assertFalse(txt.contains("<p>"))
        assertFalse(txt.contains("<br"))
    }

    @Test
    fun pdfExporter_generatesValidMultiPagePdfWithClickableLinks() {
        val context = RuntimeEnvironment.getApplication()
        val longParagraphs = (1..90).map { i ->
            TranscriptParagraph(
                startMs = i * 15_000L,
                text = "Paragraph $i carries exact spoken technical wording from the session covering distributed storage, latency bounds, and recovery verification."
            )
        }
        val doc = TranscriptDocument(
            isPlaylist = true,
            title = "Large Engineering Playlist",
            sourceUrl = "https://www.youtube.com/playlist?list=PLlarge",
            extractedAtMillis = 1794700800000L,
            videosFound = 2,
            successfulCount = 1,
            noCaptionsCount = 1,
            inaccessibleCount = 0,
            entries = listOf(
                VideoTranscriptEntry(
                    position = 1,
                    videoId = "long1",
                    title = "Deep Dive Session",
                    url = "https://www.youtube.com/watch?v=long1",
                    channel = "Systems Eng",
                    durationSeconds = 1800L,
                    captionLanguage = "English",
                    captionType = CaptionType.MANUAL,
                    status = VideoTranscriptStatus.SUCCESS,
                    paragraphs = longParagraphs
                ),
                VideoTranscriptEntry(
                    position = 2,
                    videoId = "missing2",
                    title = "Missing Captions Talk",
                    url = "https://www.youtube.com/watch?v=missing2",
                    status = VideoTranscriptStatus.NO_CAPTIONS,
                    statusMessage = "No accessible captions were found for this video."
                )
            )
        )

        val pdfBytes = PdfTranscriptExporter(context, TimeZone.getTimeZone("UTC")).exportToBytes(doc)
        val ascii = String(pdfBytes, StandardCharsets.ISO_8859_1)

        assertTrue(pdfBytes.size > 1000)
        assertTrue(ascii.startsWith("%PDF-"))
        assertTrue(ascii.trimEnd().endsWith("%%EOF"))
        assertTrue(ascii.contains("/Subtype /Link"))
        assertTrue(ascii.contains("https://www.youtube.com/playlist?list=PLlarge"))
        assertTrue(ascii.contains("https://www.youtube.com/watch?v=long1"))
        assertTrue(ascii.contains("Page 2"))

        // Also test VectorPdfTranscriptWriter directly to verify multi-page count and PdfLinkAnnotator append
        val rawVectorBytes = ByteArrayOutputStream().also {
            VectorPdfTranscriptWriter(TimeZone.getTimeZone("UTC")).write(doc, it)
        }.toByteArray()
        val vectorAscii = String(rawVectorBytes, StandardCharsets.ISO_8859_1)
        assertTrue(vectorAscii.contains("Page 3"))
        assertTrue(vectorAscii.contains("Deep Dive Session"))

        val tempSrc = File.createTempFile("annot_test_", ".pdf")
        try {
            tempSrc.writeBytes(rawVectorBytes)
            val annotatedOut = ByteArrayOutputStream()
            val ok = PdfLinkAnnotator.appendLinkAnnotations(
                sourcePdf = tempSrc,
                targetOut = annotatedOut,
                annotations = listOf(
                    PdfLinkAnnotation(0, 50f, 100f, 300f, 120f, 842f, "https://www.youtube.com/watch?v=appended")
                )
            )
            assertTrue(ok)
            val annotatedStr = String(annotatedOut.toByteArray(), StandardCharsets.ISO_8859_1)
            assertTrue(annotatedStr.contains("/URI (https://www.youtube.com/watch?v=appended)"))
            assertTrue(annotatedStr.contains("/Prev "))
        } finally {
            tempSrc.delete()
        }
    }
}
