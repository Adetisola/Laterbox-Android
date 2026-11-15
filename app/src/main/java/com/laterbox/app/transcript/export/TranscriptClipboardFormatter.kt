package com.laterbox.app.transcript.export

import com.laterbox.app.transcript.domain.TranscriptDocument
import com.laterbox.app.transcript.domain.VideoTranscriptEntry
import com.laterbox.app.transcript.domain.VideoTranscriptStatus

/**
 * Formats extracted transcripts for clipboard copying with complete verbatim wording
 * (never applying the UI preview truncation cap).
 */
object TranscriptClipboardFormatter {

    fun formatSingleVideo(entry: VideoTranscriptEntry): String {
        if (entry.status != VideoTranscriptStatus.SUCCESS || entry.paragraphs.isEmpty()) {
            return ""
        }
        return buildString {
            appendLine(entry.title)
            if (entry.url.isNotBlank()) {
                appendLine(entry.url)
            }
            appendLine()
            entry.paragraphs.forEachIndexed { idx, paragraph ->
                val ts = TranscriptFormatting.formatTimestamp(paragraph.startMs)
                if (ts != null) {
                    append(ts).append(' ')
                }
                append(paragraph.text)
                if (idx < entry.paragraphs.lastIndex) {
                    append("\n\n")
                }
            }
        }.trim()
    }

    fun formatPlaylist(document: TranscriptDocument): String {
        val successful = document.entries.filter {
            it.status == VideoTranscriptStatus.SUCCESS && it.paragraphs.isNotEmpty()
        }
        if (successful.isEmpty()) return ""

        return buildString {
            if (document.isPlaylist) {
                appendLine(document.title)
                if (document.sourceUrl.isNotBlank()) {
                    appendLine(document.sourceUrl)
                }
                appendLine()
            }
            successful.forEachIndexed { idx, entry ->
                if (document.isPlaylist) {
                    appendLine("${entry.position}. ${entry.title}")
                } else {
                    appendLine(entry.title)
                }
                if (entry.url.isNotBlank()) {
                    appendLine(entry.url)
                }
                appendLine()
                entry.paragraphs.forEachIndexed { pIdx, paragraph ->
                    val ts = TranscriptFormatting.formatTimestamp(paragraph.startMs)
                    if (ts != null) {
                        append(ts).append(' ')
                    }
                    append(paragraph.text)
                    if (pIdx < entry.paragraphs.lastIndex) {
                        append("\n\n")
                    }
                }
                if (idx < successful.lastIndex) {
                    append("\n\n---\n\n")
                }
            }
        }.trim()
    }
}
