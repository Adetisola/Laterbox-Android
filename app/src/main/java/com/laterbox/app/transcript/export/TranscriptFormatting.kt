package com.laterbox.app.transcript.export

import com.laterbox.app.transcript.domain.TranscriptDocument
import com.laterbox.app.transcript.domain.VideoTranscriptEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object TranscriptFormatting {

    private val ILLEGAL_FILENAME_CHARS = Regex("""[\\/:*?"<>|\u0000-\u001F]""")
    private val MULTI_SPACE = Regex("\\s+")

    fun sanitizeFilename(title: String?, fallback: String = "YouTube_Transcript"): String {
        val raw = title.orEmpty()
            .replace(ILLEGAL_FILENAME_CHARS, "_")
            .replace(MULTI_SPACE, " ")
            .trim()
            .trim('.')
            .trim()
        if (raw.isEmpty() || raw.trim('_', ' ', '.').isEmpty()) return fallback
        return raw.take(80).trim().trim('.').ifEmpty { fallback }
    }

    fun formatDateIso(
        epochMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = timeZone
        return sdf.format(Date(epochMillis))
    }

    fun formatDuration(seconds: Long?): String? {
        if (seconds == null || seconds <= 0L) return null
        val hrs = seconds / 3600L
        val mins = (seconds % 3600L) / 60L
        val secs = seconds % 60L
        return if (hrs > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }

    fun formatTimestamp(ms: Long?): String? {
        if (ms == null || ms < 0L) return null
        val totalSeconds = ms / 1000L
        val hrs = totalSeconds / 3600L
        val mins = (totalSeconds % 3600L) / 60L
        val secs = totalSeconds % 60L
        return if (hrs > 0L) {
            String.format(Locale.US, "[%d:%02d:%02d]", hrs, mins, secs)
        } else {
            String.format(Locale.US, "[%02d:%02d]", mins, secs)
        }
    }

    fun formatPositionPrefix(position: Int, totalCount: Int): String {
        val width = maxOf(2, totalCount.toString().length)
        return position.toString().padStart(width, '0')
    }

    fun documentHeadingTitle(doc: TranscriptDocument): String {
        return if (doc.isPlaylist) {
            "${doc.title} — Video Transcripts"
        } else {
            "${doc.title} — Video Transcript"
        }
    }

    fun videoSectionHeading(entry: VideoTranscriptEntry, doc: TranscriptDocument): String {
        return if (doc.isPlaylist) {
            val pos = formatPositionPrefix(entry.position, doc.videosFound.coerceAtLeast(doc.entries.size))
            "$pos — ${entry.title}"
        } else {
            entry.title
        }
    }

    /**
     * Generates GitHub-compatible Markdown heading anchor IDs (e.g.
     * "01 — Understanding Product Growth" -> "01--understanding-product-growth").
     */
    fun toMarkdownAnchor(heading: String, usedSlugs: MutableMap<String, Int>): String {
        val sb = StringBuilder()
        for (ch in heading.lowercase(Locale.ROOT)) {
            when {
                ch.isLetterOrDigit() || ch == '-' || ch == '_' -> sb.append(ch)
                ch == ' ' -> sb.append('-')
            }
        }
        val base = sb.toString().ifEmpty { "section" }
        val count = usedSlugs[base] ?: 0
        usedSlugs[base] = count + 1
        return if (count == 0) base else "$base-$count"
    }
}
