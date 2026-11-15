package com.laterbox.app.ai

/**
 * Live runtime capability snapshot injected into [LaterboxContextEngine] so the assistant
 * reflects real current app features, installed engines, and user storage state.
 */
data class CapabilitySnapshot(
    val ytDlpState: String = "NOT_INITIALIZED",
    val ytDlpVersion: String? = null,
    val quickJsReady: Boolean = false,
    val quickJsVersion: String? = null,
    val ffmpegState: String = "NOT_INITIALIZED",
    val youtubeTranscriptsReady: Boolean = true,
    val supportedTranscriptFormats: List<String> = listOf(
        "Markdown (.md)",
        "Plain Text (.txt)",
        "PDF (.pdf)"
    ),
    val transcriptClipboardCopySupported: Boolean = true,
    val downloadsLibraryDocumentsSupported: Boolean = true,
    val storedDocumentCount: Int = 0,
    val configuredExportFolderName: String? = null,
    val configuredExportFolderWritable: Boolean = false
)

/**
 * Single source of truth for Laterbox's current features, transcript capabilities,
 * and honest limitations.
 */
object AppCapabilityRegistry {

    val staticFeatureGuide: String = """
        Laterbox Real Capabilities & Grounding Rules:
        1. Saving Links:
           - Users can save links via the in-app + Save Link sheet, the Android Share sheet ("Share to Laterbox"), the Android text-selection Save pill (when Accessibility capture is enabled in Settings → Capture & Shortcuts), or Clipboard detection prompts.
           - Saving works immediately in offline-tolerant Room storage; metadata/media enrichment catches up when online.
        2. Inbox, Organization & Reading:
           - Inbox supports search across title, domain, URL, and personal notes, filters (All, Unread, Videos, Articles, etc.), view modes (Hero, Compact, Editorial), Collections for grouping links, and Archive for finished items.
        3. Background Media Inspection & Downloads:
           - When a link is saved (and Auto-inspect on save is on), Laterbox inspects media formats in the background using an embedded yt-dlp + Python + QuickJS (libqjs.so) + FFmpeg runtime on-device.
           - Multi-resolution video + audio streams and audio-only formats can be queued in the Downloads screen (Library / Queue tabs) and optionally exported to a custom folder.
        4. YouTube Transcript Extraction, Clipboard Copy & Multi-Format Export:
           - Laterbox supports extracting verbatim YouTube captions/transcripts for BOTH single YouTube videos and full YouTube playlists (preserving playlist order, pagination, independent per-video status tracking, and partial exports if some videos fail or are cancelled).
           - Extraction fetches captions and metadata only using embedded yt-dlp + QuickJS (--skip-download); it does NOT download video or audio files. It prefers English captions by default and supports manually uploaded and auto-generated captions with timestamps.
           - Extracted transcripts are never summarized, rewritten, or fabricated by the extractor.
           - Users can copy an individual video's full transcript (Copy button) or copy all extracted playlist transcripts (Copy All) directly to the clipboard.
           - Users can export transcripts in any combination of Markdown (.md), Plain Text (.txt), and PDF (.pdf).
           - Exported transcript files are automatically saved into Laterbox's Downloads Library → Documents section as Documents, and can also be exported to the user's configured custom folder or Downloads/Laterbox/Transcripts.
           - You can open the YouTube Transcript extractor sheet directly for the user via the read-only OPEN_YOUTUBE_TRANSCRIPTS action (optionally prefilling a YouTube video or playlist URL).
        5. Reminders & Contextual Notifications:
           - Manual per-link reminders, Smart Digest reminder schedules, terminal extraction/download recovery alerts, App Health alerts, Quiet Hours (Settings → Reminders & AI), and in-app Notification History.
        6. Honest Limitations (MUST respect):
           - Laterbox does NOT perform local speech-to-text / Whisper / ASR audio transcription when a video has no accessible captions on YouTube; transcript extraction relies on available YouTube caption tracks.
           - Laterbox does NOT download/store full paywalled article body text unless a user saved snippets in their description or personal notes.
           - Never invent, paraphrase, or fabricate spoken transcript content if captions have not been extracted or are unavailable. When a user asks for a YouTube video or playlist transcript, explain that Laterbox can extract accessible captions and offer OPEN_YOUTUBE_TRANSCRIPTS if helpful.
           - AI Assistant runs remotely via Google AI Studio / Gemini; no local on-device LLM is present. Never claim private files or API keys are accessible.
    """.trimIndent()

    fun formatLiveCapabilitiesBlock(snapshot: CapabilitySnapshot): String = buildString {
        appendLine("Live App Capabilities & Runtime Status:")
        val transcriptEngineNote = when {
            snapshot.ytDlpState == "INITIALIZED" -> "Ready (yt-dlp initialized)"
            snapshot.ytDlpState == "FAILED" -> "Degraded (yt-dlp init failed; watch-page fallback may still work)"
            snapshot.youtubeTranscriptsReady -> "Ready (yt-dlp initializes on first extraction)"
            else -> "Unavailable"
        }
        appendLine("- YouTube Transcript Extraction (single video & playlist captions): $transcriptEngineNote")
        appendLine("- yt-dlp engine: ${snapshot.ytDlpState}${snapshot.ytDlpVersion?.let { " (v$it)" } ?: ""}")
        val qjsVer = snapshot.quickJsVersion?.takeIf { it.isNotBlank() && it != "unknown" }?.let { " ($it)" }.orEmpty()
        appendLine("- QuickJS runtime: ${if (snapshot.quickJsReady) "Available$qjsVer" else "Unavailable"}")
        appendLine("- FFmpeg merger: ${snapshot.ffmpegState}")
        appendLine("- Supported transcript export formats: ${snapshot.supportedTranscriptFormats.joinToString(", ")}")
        appendLine("- Transcript clipboard copy (single video & playlist Copy All): ${if (snapshot.transcriptClipboardCopySupported) "Supported" else "Unavailable"}")
        appendLine("- Downloads Library (Documents): ${if (snapshot.downloadsLibraryDocumentsSupported) "Supported (${snapshot.storedDocumentCount} saved documents)" else "Unavailable"}")
        val folderSummary = when {
            snapshot.configuredExportFolderName.isNullOrBlank() -> "Not set (uses Downloads Library Documents or folder picker)"
            snapshot.configuredExportFolderWritable -> "\"${snapshot.configuredExportFolderName}\" (writable)"
            else -> "\"${snapshot.configuredExportFolderName}\" (permission re-selection needed)"
        }
        appendLine("- Configured export folder: $folderSummary")
    }.trim()
}
