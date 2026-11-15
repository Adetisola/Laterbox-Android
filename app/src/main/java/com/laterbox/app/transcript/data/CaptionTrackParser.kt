package com.laterbox.app.transcript.data

import com.laterbox.app.transcript.domain.TranscriptParagraph
import com.laterbox.app.transcript.domain.TranscriptSegment
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import kotlin.math.roundToLong

data class ParsedCaptionResult(
    val segments: List<TranscriptSegment>,
    val paragraphs: List<TranscriptParagraph>
)

object CaptionTrackParser {

    private val INLINE_TAG_REGEX = Regex("<[^>]+>")
    private val VTT_TIMESTAMP_REGEX = Regex(
        """^\s*(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{3})\s+-->\s+(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{3})(?:\s+.*)?$"""
    )
    private val MULTI_SPACE_REGEX = Regex("[ \\t]+")

    fun parse(payload: String, formatHint: String? = null): ParsedCaptionResult {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return ParsedCaptionResult(emptyList(), emptyList())

        val fmt = formatHint?.lowercase()?.trim()
        val rawParsed = when {
            fmt == "json3" || (fmt == null && trimmed.startsWith("{")) -> parseJson3(trimmed)
            fmt == "vtt" || (fmt == null && trimmed.startsWith("WEBVTT")) -> parseVtt(trimmed)
            fmt in setOf("srv1", "srv3", "ttml", "xml") || (fmt == null && trimmed.startsWith("<")) -> parseXml(trimmed)
            else -> {
                when {
                    trimmed.startsWith("{") -> parseJson3(trimmed)
                    trimmed.startsWith("WEBVTT") || VTT_TIMESTAMP_REGEX.containsMatchIn(trimmed) -> parseVtt(trimmed)
                    trimmed.startsWith("<") -> parseXml(trimmed)
                    else -> parsePlainLines(trimmed)
                }
            }
        }

        return rawParsed
    }

    internal fun parseJson3(raw: String): ParsedCaptionResult {
        val segments = mutableListOf<TranscriptSegment>()
        val breakIndices = mutableSetOf<Int>()

        val root = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return ParsedCaptionResult(emptyList(), emptyList())
        }

        val events = root.optJSONArray("events") ?: return ParsedCaptionResult(emptyList(), emptyList())
        var pendingParagraphBreak = false

        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val segs = event.optJSONArray("segs")
            if (segs == null || segs.length() == 0) continue

            val sb = StringBuilder()
            for (j in 0 until segs.length()) {
                val seg = segs.optJSONObject(j) ?: continue
                val utf8 = seg.optString("utf8", "")
                if (utf8.isNotEmpty()) {
                    sb.append(utf8)
                }
            }

            val rawText = sb.toString().replace("\r\n", "\n")
            if (rawText.isBlank()) {
                if (rawText.contains("\n") && segments.isNotEmpty()) {
                    pendingParagraphBreak = true
                }
                continue
            }

            val hasExplicitParagraphBreak = rawText.contains("\n\n")
            val cleaned = cleanCaptionText(rawText)
            if (cleaned.isEmpty()) continue

            val startMs = if (event.has("tStartMs")) event.optLong("tStartMs", -1L).takeIf { it >= 0L } else null
            val durationMs = if (event.has("dDurationMs")) event.optLong("dDurationMs", -1L).takeIf { it > 0L } else null
            val endMs = if (startMs != null && durationMs != null) startMs + durationMs else null

            if (pendingParagraphBreak && segments.isNotEmpty()) {
                breakIndices.add(segments.size)
                pendingParagraphBreak = false
            }

            segments.add(
                TranscriptSegment(
                    startMs = startMs,
                    endMs = endMs,
                    text = cleaned
                )
            )

            if (hasExplicitParagraphBreak) {
                pendingParagraphBreak = true
            }
        }

        val paragraphs = buildParagraphs(segments, explicitBreaks = breakIndices)
        return ParsedCaptionResult(segments = segments, paragraphs = paragraphs)
    }

    internal fun parseVtt(raw: String): ParsedCaptionResult {
        val lines = raw.replace("\r\n", "\n").split("\n")
        val rawCues = mutableListOf<Triple<Long?, Long?, List<String>>>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val match = VTT_TIMESTAMP_REGEX.matchEntire(line)
            if (match != null) {
                val startMs = parseRegexTimestamp(match, startGroup = 1)
                val endMs = parseRegexTimestamp(match, startGroup = 5)
                i++
                val cueLines = mutableListOf<String>()
                while (i < lines.size && lines[i].isNotBlank()) {
                    val cleanedLine = cleanCaptionText(lines[i].replace(INLINE_TAG_REGEX, ""))
                    if (cleanedLine.isNotEmpty()) {
                        cueLines.add(cleanedLine)
                    }
                    i++
                }
                if (cueLines.isNotEmpty()) {
                    rawCues.add(Triple(startMs, endMs, cueLines))
                }
            } else {
                i++
            }
        }

        // Deduplicate rolling-caption overlap commonly emitted by YouTube VTT auto-captions
        val segments = mutableListOf<TranscriptSegment>()
        var previousLines = emptyList<String>()

        for ((startMs, endMs, cueLines) in rawCues) {
            val overlap = findLineOverlap(previousLines, cueLines)
            val newLines = if (overlap > 0) cueLines.drop(overlap) else cueLines
            previousLines = cueLines

            if (newLines.isEmpty()) continue
            val text = newLines.joinToString(" ").trim()
            if (text.isEmpty()) continue

            // Also avoid exact duplicate consecutive segment text at the same or near-identical cue
            val lastSeg = segments.lastOrNull()
            if (lastSeg != null && lastSeg.text == text) continue

            segments.add(
                TranscriptSegment(
                    startMs = startMs,
                    endMs = endMs,
                    text = text
                )
            )
        }

        val paragraphs = buildParagraphs(segments)
        return ParsedCaptionResult(segments = segments, paragraphs = paragraphs)
    }

    internal fun parseXml(raw: String): ParsedCaptionResult {
        val doc = try {
            Jsoup.parse(raw, "", Parser.xmlParser())
        } catch (_: Exception) {
            return ParsedCaptionResult(emptyList(), emptyList())
        }

        val segments = mutableListOf<TranscriptSegment>()
        val textElements = doc.select("text")
        if (textElements.isNotEmpty()) {
            for (el in textElements) {
                val startSec = el.attr("start").toDoubleOrNull()
                val durSec = el.attr("dur").toDoubleOrNull()
                val startMs = startSec?.let { (it * 1000.0).roundToLong().coerceAtLeast(0L) }
                val endMs = if (startMs != null && durSec != null) {
                    startMs + (durSec * 1000.0).roundToLong().coerceAtLeast(0L)
                } else null
                val text = cleanCaptionText(el.wholeText().ifBlank { el.text() })
                if (text.isNotEmpty()) {
                    segments.add(TranscriptSegment(startMs = startMs, endMs = endMs, text = text))
                }
            }
        } else {
            val pElements = doc.select("p")
            for (el in pElements) {
                val startMs = when {
                    el.hasAttr("t") -> el.attr("t").toLongOrNull()
                    el.hasAttr("begin") -> parseTtmlTime(el.attr("begin"))
                    else -> null
                }
                val endMs = when {
                    startMs != null && el.hasAttr("d") -> {
                        val dur = el.attr("d").toLongOrNull()
                        if (dur != null) startMs + dur else null
                    }
                    el.hasAttr("end") -> parseTtmlTime(el.attr("end"))
                    else -> null
                }
                val text = cleanCaptionText(el.wholeText().ifBlank { el.text() })
                if (text.isNotEmpty()) {
                    segments.add(TranscriptSegment(startMs = startMs, endMs = endMs, text = text))
                }
            }
        }

        val paragraphs = buildParagraphs(segments)
        return ParsedCaptionResult(segments = segments, paragraphs = paragraphs)
    }

    private fun parsePlainLines(raw: String): ParsedCaptionResult {
        val chunks = raw.replace("\r\n", "\n")
            .split(Regex("\\n{2,}"))
            .map { cleanCaptionText(it) }
            .filter { it.isNotEmpty() }

        val segments = chunks.map { TranscriptSegment(startMs = null, endMs = null, text = it) }
        val paragraphs = chunks.map { TranscriptParagraph(startMs = null, text = it) }
        return ParsedCaptionResult(segments = segments, paragraphs = paragraphs)
    }

    internal fun buildParagraphs(
        segments: List<TranscriptSegment>,
        explicitBreaks: Set<Int> = emptySet()
    ): List<TranscriptParagraph> {
        if (segments.isEmpty()) return emptyList()

        val paragraphs = mutableListOf<TranscriptParagraph>()
        var currentStartMs: Long? = segments.first().startMs
        val currentParts = mutableListOf<String>()
        var currentCharCount = 0
        var lastSeg: TranscriptSegment? = null

        for ((index, seg) in segments.withIndex()) {
            val shouldBreak = if (index == 0) {
                false
            } else if (explicitBreaks.contains(index)) {
                true
            } else {
                shouldStartNewParagraph(
                    prev = lastSeg!!,
                    curr = seg,
                    currentParagraphStartMs = currentStartMs,
                    currentCharCount = currentCharCount,
                    segmentCountInParagraph = currentParts.size
                )
            }

            if (shouldBreak && currentParts.isNotEmpty()) {
                paragraphs.add(
                    TranscriptParagraph(
                        startMs = currentStartMs,
                        text = currentParts.joinToString(" ").replace(MULTI_SPACE_REGEX, " ").trim()
                    )
                )
                currentParts.clear()
                currentCharCount = 0
                currentStartMs = seg.startMs
            }

            currentParts.add(seg.text)
            currentCharCount += seg.text.length + 1
            lastSeg = seg
        }

        if (currentParts.isNotEmpty()) {
            paragraphs.add(
                TranscriptParagraph(
                    startMs = currentStartMs,
                    text = currentParts.joinToString(" ").replace(MULTI_SPACE_REGEX, " ").trim()
                )
            )
        }

        return paragraphs
    }

    private fun shouldStartNewParagraph(
        prev: TranscriptSegment,
        curr: TranscriptSegment,
        currentParagraphStartMs: Long?,
        currentCharCount: Int,
        segmentCountInParagraph: Int
    ): Boolean {
        // Explicit silence gap (> 3.0s between cue end and next cue start)
        if (prev.endMs != null && curr.startMs != null && (curr.startMs - prev.endMs) >= 3_000L) {
            return true
        }

        val endsWithSentence = prev.text.lastOrNull() in setOf('.', '?', '!')
        if (endsWithSentence && (currentCharCount >= 240 || segmentCountInParagraph >= 4)) {
            return true
        }

        // For long unpunctuated auto-captions, break naturally around ~35s / ~480 chars
        val elapsedInParagraph = if (currentParagraphStartMs != null && curr.startMs != null) {
            curr.startMs - currentParagraphStartMs
        } else {
            0L
        }
        if (elapsedInParagraph >= 35_000L && currentCharCount >= 280) {
            return true
        }
        if (currentCharCount >= 520) {
            return true
        }

        return false
    }

    private fun findLineOverlap(prevLines: List<String>, currLines: List<String>): Int {
        if (prevLines.isEmpty() || currLines.isEmpty()) return 0
        val maxOverlap = minOf(prevLines.size, currLines.size)
        for (len in maxOverlap downTo 1) {
            val prevSuffix = prevLines.subList(prevLines.size - len, prevLines.size)
            val currPrefix = currLines.subList(0, len)
            if (prevSuffix == currPrefix) {
                return len
            }
        }
        return 0
    }

    private fun parseRegexTimestamp(match: MatchResult, startGroup: Int): Long {
        val hours = match.groupValues.getOrNull(startGroup)?.toLongOrNull() ?: 0L
        val minutes = match.groupValues.getOrNull(startGroup + 1)?.toLongOrNull() ?: 0L
        val seconds = match.groupValues.getOrNull(startGroup + 2)?.toLongOrNull() ?: 0L
        val millis = match.groupValues.getOrNull(startGroup + 3)?.toLongOrNull() ?: 0L
        return ((hours * 3600L + minutes * 60L + seconds) * 1000L) + millis
    }

    private fun parseTtmlTime(value: String): Long? {
        val trimmed = value.trim()
        if (trimmed.endsWith("s")) {
            val sec = trimmed.removeSuffix("s").toDoubleOrNull() ?: return null
            return (sec * 1000.0).roundToLong()
        }
        val parts = trimmed.split(":")
        return when (parts.size) {
            3 -> {
                val h = parts[0].toLongOrNull() ?: return null
                val m = parts[1].toLongOrNull() ?: return null
                val s = parts[2].toDoubleOrNull() ?: return null
                ((h * 3600L + m * 60L) * 1000L) + (s * 1000.0).roundToLong()
            }
            2 -> {
                val m = parts[0].toLongOrNull() ?: return null
                val s = parts[1].toDoubleOrNull() ?: return null
                (m * 60L * 1000L) + (s * 1000.0).roundToLong()
            }
            else -> null
        }
    }

    internal fun cleanCaptionText(raw: String): String {
        val unescaped = try {
            Parser.unescapeEntities(raw, false)
        } catch (_: Exception) {
            raw
        }
        return unescaped
            .replace("\u200B", "")
            .replace("\u00A0", " ")
            .replace("\r\n", " ")
            .replace("\n", " ")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
    }
}
