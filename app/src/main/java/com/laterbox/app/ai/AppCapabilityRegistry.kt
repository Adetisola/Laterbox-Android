package com.laterbox.app.ai

/**
 * High-level product sections registered in [AppCapabilityRegistry].
 */
enum class LaterboxSection(val displayName: String) {
    INBOX("Inbox"),
    COLLECTIONS("Collections"),
    DOWNLOADS("Downloads"),
    ARCHIVE("Archive"),
    MOVIES_TV("Movies & TV"),
    REMINDERS("Reminders"),
    SETTINGS("Settings")
}

/**
 * Structured capability descriptor for a Laterbox section.
 */
data class SectionCapabilitySpec(
    val section: LaterboxSection,
    val summary: String,
    val capabilities: List<String>,
    val constraints: List<String> = emptyList()
)

/**
 * Live runtime capability snapshot injected into [LaterboxContextEngine] so the assistant
 * reflects real current app features, installed engines, Movies & TV library state, and user storage state.
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
    val configuredExportFolderWritable: Boolean = false,
    val assistantEnabled: Boolean = true,
    val smartRemindersEnabled: Boolean = true,
    val quietHoursSummary: String = "Enabled (22:00–08:00)",
    val moviesProvidersSummary: String = "Live Streaming Catalogue, Internet Archive, TV Series Guide",
    val moviesWatchlistCount: Int = 0,
    val moviesInProgressCount: Int = 0,
    val moviesCompletedCount: Int = 0,
    val moviesOfflineCount: Int = 0,
    val collectionsCount: Int = 0,
    val moviesActiveRemindersCount: Int = 0
)

/**
 * Single source of truth for Laterbox's current features across all sections,
 * transcript & Movies/TV capabilities, and honest limitations.
 */
object AppCapabilityRegistry {

    val sectionSpecs: List<SectionCapabilitySpec> = listOf(
        SectionCapabilitySpec(
            section = LaterboxSection.INBOX,
            summary = "Save, organize, search, and triage web links offline-first.",
            capabilities = listOf(
                "Save links via in-app + Save Link sheet, Android Share sheet (\"Share to Laterbox\"), text-selection Save pill, and Clipboard detection.",
                "Instant offline Room persistence with background metadata and media enrichment.",
                "Search across title, domain, URL, and personal notes; switch filters (All, Unread, Videos, Articles) and layouts (Hero, Compact, Editorial)."
            )
        ),
        SectionCapabilitySpec(
            section = LaterboxSection.COLLECTIONS,
            summary = "Unified custom collections for both saved links and Movies & TV titles.",
            capabilities = listOf(
                "Create, rename, and delete custom collections.",
                "Add or remove both saved links and Movies & TV titles in the same collections."
            )
        ),
        SectionCapabilitySpec(
            section = LaterboxSection.DOWNLOADS,
            summary = "On-device media downloader, stream muxer, YouTube transcript documents, and offline Movies/TV library.",
            capabilities = listOf(
                "Inspect and download video/audio formats using embedded yt-dlp + Python + QuickJS (libqjs.so) + FFmpeg.",
                "Queue & Library tabs across Images, Videos, Movies, Audio, and Documents, with optional SAF custom folder export.",
                "Extract verbatim YouTube captions/transcripts for single videos and playlists (--skip-download, no audio/video download), copy individual or all playlist transcripts to clipboard, and export to Markdown (.md), Plain Text (.txt), and PDF (.pdf) saved in Downloads Library -> Documents."
            ),
            constraints = listOf(
                "Laterbox does NOT perform local speech-to-text / Whisper / ASR audio transcription when a YouTube video has no accessible captions; extraction relies on available YouTube caption tracks.",
                "Laterbox does NOT download/store full paywalled article body text unless saved in description or personal notes."
            )
        ),
        SectionCapabilitySpec(
            section = LaterboxSection.ARCHIVE,
            summary = "Finished links preserved out of the active Inbox with search, restore, and delete.",
            capabilities = listOf(
                "Archive read links and restore them back to Inbox or delete permanently."
            )
        ),
        SectionCapabilitySpec(
            section = LaterboxSection.MOVIES_TV,
            summary = "Discovery, Personal Library, Offline Playback, and unified full-screen Media Player.",
            capabilities = listOf(
                "Browse curated rails, Surprise Me picks, Because You Watched & More Like This recommendations, Runtime-aware picks, and expandable filters (Kind, Genre, Language, Country, Year, Runtime, Sort).",
                "Canonical cross-provider identities across live streaming catalogue, Internet Archive, and TV series guide so watchlist & history do not duplicate.",
                "Watch History split separately for Movies, Episodes, and Trailers; Continue Watching management (Resume, Restart from beginning, Remove from Continue Watching, Hide title); Rewatch tracking; Episode watched/unwatched toggles & next unwatched episode; Recently Viewed; Custom Collections.",
                "Full-screen ExoPlayer with vertical brightness/volume swipe gestures, horizontal swipe-to-seek with adjustable sensitivity, double-tap seek, up to 200% LoudnessEnhancer volume boost, subtitle styling, per-series audio/subtitle preferences, next-episode autoplay, and verified intro/recap skip buttons when timestamps are provided by metadata.",
                "Offline playback directly from downloaded Movie/Episode files in Downloads."
            ),
            constraints = listOf(
                "Skip Intro / Skip Recap only appear when verified intro/recap timestamps exist in source metadata; never guess timestamps.",
                "Playback & episode availability depend on connected providers or already-downloaded local files."
            )
        ),
        SectionCapabilitySpec(
            section = LaterboxSection.REMINDERS,
            summary = "Manual reminders, Smart Digest schedules, Movies & TV viewing nudges, Quiet Hours, and Notification History.",
            capabilities = listOf(
                "Manual per-link reminders and scheduled Movies & TV viewing reminders.",
                "Smart Digest & Contextual Notifications covering unread links, Continue Watching / next unwatched episode nudges, Watchlist nudges, verified New Season alerts, extraction/download failure recovery, and App Health alerts.",
                "Quiet Hours, per-series reminder mute, persistence caps, and in-app Notification History."
            )
        ),
        SectionCapabilitySpec(
            section = LaterboxSection.SETTINGS,
            summary = "Appearance, Reminders & AI, Capture & Shortcuts, Reading & Behaviour, Media & Downloads, Movies & TV player config, Data & Storage backup/restore, and About & Privacy.",
            capabilities = listOf(
                "Configure Gemini API key, Smart Reminders schedules, Movies & TV player/subtitles/endpoints, SAF export directory, and JSON backup/restore."
            ),
            constraints = listOf(
                "AI Assistant runs remotely via Google AI Studio / Gemini; no local on-device LLM is present. Never claim private files or API keys are accessible."
            )
        )
    )

    val staticFeatureGuide: String = buildString {
        appendLine("Laterbox Real Capabilities & Grounding Rules:")
        sectionSpecs.forEachIndexed { index, spec ->
            appendLine("${index + 1}. ${spec.section.displayName}: ${spec.summary}")
            spec.capabilities.forEach { cap ->
                appendLine("   - $cap")
            }
        }
        appendLine("${sectionSpecs.size + 1}. Honest Limitations (MUST respect):")
        sectionSpecs.flatMap { it.constraints }.forEach { constraint ->
            appendLine("   - $constraint")
        }
        appendLine("   - Never invent, paraphrase, or fabricate spoken transcript content, release dates, new seasons, intro/recap timestamps, IDs, or links not present in the context snapshot. Offer OPEN_YOUTUBE_TRANSCRIPTS when users want YouTube captions extracted.")
    }.trim()

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
        appendLine("- AI Assistant: ${if (snapshot.assistantEnabled) "Enabled" else "Disabled"}")
        appendLine("- Smart Reminders: ${if (snapshot.smartRemindersEnabled) "Enabled" else "Disabled"} (Quiet Hours: ${snapshot.quietHoursSummary})")
        appendLine(
            "- Movies & TV Personal Library (${snapshot.moviesProvidersSummary}): " +
                "${snapshot.moviesWatchlistCount} watchlist, " +
                "${snapshot.moviesInProgressCount} in-progress, " +
                "${snapshot.moviesCompletedCount} completed, " +
                "${snapshot.moviesOfflineCount} offline, " +
                "${snapshot.moviesActiveRemindersCount} reminders; " +
                "${snapshot.collectionsCount} custom collections"
        )
    }.trim()
}

object RemoteCapabilities {
    const val FEATURE_MOVIES_TV = "feature:movies_tv_enabled"
    const val FEATURE_PROVIDER_MOVIEBOX = "feature:provider_moviebox_enabled"
    const val FEATURE_PROVIDER_INTERNET_ARCHIVE = "feature:provider_internet_archive_enabled"
    const val FEATURE_PROVIDER_TVMAZE = "feature:provider_tvmaze_enabled"
    const val FEATURE_MOVIES_RECOMMENDATIONS = "feature:movies_recommendations_enabled"
    const val FEATURE_AI_ASSISTANT = "feature:ai_assistant_enabled"
    const val FEATURE_SMART_REMINDERS = "feature:smart_reminders_enabled"
    const val FEATURE_PLAYER_INTRO_SKIP = "feature:player_intro_skip_enabled"
    const val FEATURE_YTDLP_AUTO_UPDATE = "feature:ytdlp_auto_update_enabled"

    const val KEY_MOVIES_TV = "movies_tv_enabled"
    const val KEY_PROVIDER_MOVIEBOX = "provider_moviebox_enabled"
    const val KEY_PROVIDER_INTERNET_ARCHIVE = "provider_internet_archive_enabled"
    const val KEY_PROVIDER_TVMAZE = "provider_tvmaze_enabled"
    const val KEY_MOVIES_RECOMMENDATIONS = "movies_recommendations_enabled"
    const val KEY_AI_ASSISTANT = "ai_assistant_enabled"
    const val KEY_SMART_REMINDERS = "smart_reminders_enabled"
    const val KEY_PLAYER_INTRO_SKIP = "player_intro_skip_enabled"
    const val KEY_YTDLP_AUTO_UPDATE = "ytdlp_auto_update_enabled"

    const val UI_ANNOUNCEMENT_BANNER = "ui:announcement_banner"
    const val UI_HERO_CAROUSEL = "ui:hero_carousel"
    const val UI_CONTENT_RAIL = "ui:content_rail"
    const val UI_FEATURED_CALLOUT_CARD = "ui:featured_callout_card"

    const val TYPE_ANNOUNCEMENT_BANNER = "announcement_banner"
    const val TYPE_HERO_CAROUSEL = "hero_carousel"
    const val TYPE_CONTENT_RAIL = "content_rail"
    const val TYPE_FEATURED_CALLOUT_CARD = "featured_callout_card"

    val SUPPORTED_FEATURE_KEYS: Set<String> = setOf(
        KEY_MOVIES_TV,
        KEY_PROVIDER_MOVIEBOX,
        KEY_PROVIDER_INTERNET_ARCHIVE,
        KEY_PROVIDER_TVMAZE,
        KEY_MOVIES_RECOMMENDATIONS,
        KEY_AI_ASSISTANT,
        KEY_SMART_REMINDERS,
        KEY_PLAYER_INTRO_SKIP,
        KEY_YTDLP_AUTO_UPDATE,
    )

    val DEFAULT_FEATURES: Map<String, Boolean> = SUPPORTED_FEATURE_KEYS.associateWith { true }

    val SUPPORTED_UI_COMPONENT_TYPES: Set<String> = setOf(
        TYPE_ANNOUNCEMENT_BANNER,
        TYPE_HERO_CAROUSEL,
        TYPE_CONTENT_RAIL,
        TYPE_FEATURED_CALLOUT_CARD,
    )

    val SUPPORTED_RAIL_IDS: Set<String> = setOf(
        "continue_watching",
        "watchlist",
        "recently_viewed",
        "because_you_watched",
        "quick_picks",
        "recently_added",
        "top_rated",
        "newest",
        "discovery_sections",
    )

    val SUPPORTED_DESTINATIONS: Set<String> = setOf(
        "movies",
        "downloads",
        "collections",
        "inbox",
        "archive",
        "settings",
    )

    val declaredCapabilities: Set<String> = setOf(
        FEATURE_MOVIES_TV,
        FEATURE_PROVIDER_MOVIEBOX,
        FEATURE_PROVIDER_INTERNET_ARCHIVE,
        FEATURE_PROVIDER_TVMAZE,
        FEATURE_MOVIES_RECOMMENDATIONS,
        FEATURE_AI_ASSISTANT,
        FEATURE_SMART_REMINDERS,
        FEATURE_PLAYER_INTRO_SKIP,
        FEATURE_YTDLP_AUTO_UPDATE,
        UI_ANNOUNCEMENT_BANNER,
        UI_HERO_CAROUSEL,
        UI_CONTENT_RAIL,
        UI_FEATURED_CALLOUT_CARD,
    )

    fun declaredCapabilitiesCsv(): String = declaredCapabilities.sorted().joinToString(",")
}


