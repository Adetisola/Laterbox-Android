package com.laterbox.app.transcript.export

import com.laterbox.app.transcript.domain.CaptionType
import com.laterbox.app.transcript.domain.TranscriptDocument
import com.laterbox.app.transcript.domain.VideoTranscriptStatus
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.TimeZone

class MarkdownTranscriptExporter(
    private val timeZone: TimeZone = TimeZone.getDefault()
) {

    fun exportToStream(document: TranscriptDocument, outputStream: OutputStream) {
        val writer = BufferedWriter(OutputStreamWriter(outputStream, StandardCharsets.UTF_8))
        writeTo(document, writer)
        writer.flush()
    }

    fun exportToString(document: TranscriptDocument): String {
        val out = java.io.ByteArrayOutputStream()
        exportToStream(document, out)
        return out.toString(StandardCharsets.UTF_8.name())
    }

    private fun writeTo(doc: TranscriptDocument, out: Appendable) {
        out.append("# ").append(TranscriptFormatting.documentHeadingTitle(doc)).append("\n\n")
        out.append("Source: ").append(doc.sourceUrl).append("\n")
        out.append("Date Extracted: ").append(TranscriptFormatting.formatDateIso(doc.extractedAtMillis, timeZone)).append("\n")
        if (doc.isPlaylist) {
            out.append("Videos Found: ").append(doc.videosFound.toString()).append("\n")
            out.append("Successful Transcripts: ").append(doc.successfulCount.toString()).append("\n")
            if (doc.noCaptionsCount > 0) {
                out.append("No Available Captions: ").append(doc.noCaptionsCount.toString()).append("\n")
            }
            if (doc.inaccessibleCount > 0) {
                out.append("Could Not Be Accessed: ").append(doc.inaccessibleCount.toString()).append("\n")
            }
        } else {
            val statusLabel = doc.entries.firstOrNull()?.status?.displayLabel
                ?: if (doc.successfulCount > 0) VideoTranscriptStatus.SUCCESS.displayLabel else VideoTranscriptStatus.NO_CAPTIONS.displayLabel
            out.append("Extraction Status: ").append(statusLabel).append("\n")
        }

        val usedSlugs = mutableMapOf<String, Int>()
        val anchors = doc.entries.map { entry ->
            val heading = TranscriptFormatting.videoSectionHeading(entry, doc)
            val slug = TranscriptFormatting.toMarkdownAnchor(heading, usedSlugs)
            Triple(entry, heading, slug)
        }

        out.append("\n---\n\n")
        out.append("## Contents\n\n")
        for ((index, triple) in anchors.withIndex()) {
            val (entry, _, slug) = triple
            val safeTitle = entry.title.replace("[", "\\[").replace("]", "\\]")
            out.append("${index + 1}. [$safeTitle](#$slug)\n")
        }

        for ((entry, heading, _) in anchors) {
            out.append("\n---\n\n")
            out.append("## ").append(heading).append("\n\n")
            if (entry.url.isNotBlank()) {
                out.append("Video URL: ").append(entry.url).append("\n")
            }
            if (!entry.channel.isNullOrBlank()) {
                out.append("Channel: ").append(entry.channel).append("\n")
            }
            val formattedDuration = TranscriptFormatting.formatDuration(entry.durationSeconds)
            if (formattedDuration != null) {
                out.append("Duration: ").append(formattedDuration).append("\n")
            }
            if (doc.isPlaylist) {
                out.append("Playlist Position: ").append(entry.position.toString()).append("\n")
            }
            if (!entry.captionLanguage.isNullOrBlank()) {
                out.append("Caption Language: ").append(entry.captionLanguage).append("\n")
            }
            if (entry.captionType != CaptionType.UNKNOWN) {
                out.append("Caption Type: ").append(entry.captionType.displayLabel).append("\n")
            }
            out.append("Extraction Status: ").append(entry.status.displayLabel).append("\n")

            if (entry.hasTranscript) {
                out.append("\n### Transcript\n\n")
                for ((pIdx, paragraph) in entry.paragraphs.withIndex()) {
                    val ts = TranscriptFormatting.formatTimestamp(paragraph.startMs)
                    if (ts != null) {
                        out.append(ts).append(" ")
                    }
                    out.append(paragraph.text)
                    out.append("\n")
                    if (pIdx < entry.paragraphs.lastIndex) {
                        out.append("\n")
                    }
                }
            } else {
                out.append("\n")
                out.append(
                    entry.statusMessage?.takeIf { it.isNotBlank() }
                        ?: "No accessible captions were found for this video."
                )
                out.append("\n")
            }
        }
    }
}
