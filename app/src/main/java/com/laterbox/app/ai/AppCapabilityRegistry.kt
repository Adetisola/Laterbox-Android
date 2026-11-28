package com.laterbox.app.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

enum class CapabilityAvailability {
    SUPPORTED,
    CONDITIONALLY_AVAILABLE,
    DISABLED,
    UNAVAILABLE,
    UNKNOWN
}

data class FailureRecoveryPath(
    val condition: String,
    val explanation: String,
    val recoveryDestination: String
)

data class ProductCapabilityItem(
    val id: String,
    val section: String,
    val title: String,
    val description: String,
    val defaultStatus: CapabilityAvailability = CapabilityAvailability.SUPPORTED,
    val minVersionCode: Int = 1,
    val remoteFeatureKey: String? = null,
    val conditionNote: String? = null,
    val availableActions: List<String> = emptyList(),
    val preconditions: List<String> = emptyList(),
    val requiresBackend: Boolean = false,
    val aiCopyRequiresBackend: Boolean = false,
    val reversible: Boolean = true,
    val requiresConfirmation: Boolean = false,
    val sideEffects: List<String> = emptyList(),
    val failureRecoveryPaths: List<FailureRecoveryPath> = emptyList()
)

data class ResolvedCapabilityItem(
    val id: String,
    val section: String,
    val title: String,
    val description: String,
    val status: CapabilityAvailability,
    val reason: String? = null
)

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
 * Live runtime capability snapshot injected into [LaterboxContextEngine] and backend AI sync
 * so the assistant reflects real current app features, installed engines, Movies & TV library state, and user storage state.
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
    val moviesActiveRemindersCount: Int = 0,
    val clientVersionCode: Int? = null,
    val clientVersionName: String? = null,
    val manifestVersion: Int = 3,
    val manifestRevision: String = "2026.11.27-v3",
    val onlineServicesPaused: Boolean = false,
    val moviesTvEnabled: Boolean = true,
    val providerMovieboxEnabled: Boolean = true,
    val providerInternetArchiveEnabled: Boolean = true,
    val providerTvmazeEnabled: Boolean = true,
    val moviesRecommendationsEnabled: Boolean = true,
    val playerIntroSkipEnabled: Boolean = true
)

/**
 * Single source of truth for Laterbox's current features across all sections,
 * versioned & hashed Product Capability Manifest, and honest limitations.
 */
object AppCapabilityRegistry {

    private const val MANIFEST_ASSET_NAME = "capability_manifest_v3.json"

    internal data class LoadedCapabilityManifest(
        val manifestVersion: Int,
        val manifestRevision: String,
        val manifestHash: String,
        val items: List<ProductCapabilityItem>
    )

    @Volatile
    private var initializedContext: Context? = null

    fun initialize(context: Context) {
        initializedContext = context.applicationContext ?: context
    }

    private val loadedManifest: LoadedCapabilityManifest by lazy {
        loadCanonicalManifest()
    }

    val MANIFEST_VERSION: Int
        get() = loadedManifest.manifestVersion

    val MANIFEST_REVISION: String
        get() = loadedManifest.manifestRevision

    val MANIFEST_HASH: String
        get() = loadedManifest.manifestHash

    val productManifestItems: List<ProductCapabilityItem>
        get() = loadedManifest.items

    val ONLINE_BLOCKED_IDS: Set<String> = setOf(
        "movies.discovery_and_library",
        "movies.recommendations",
        "movies.pip_and_mini_player",
        "assistant.chat_and_actions",
        "downloads.youtube_transcripts"
    )

    private fun loadCanonicalManifest(): LoadedCapabilityManifest {
        val rawJson = readManifestJsonText()
        return parseAndVerifyManifestJson(rawJson)
    }

    private fun readManifestJsonText(): String {
        initializedContext?.let { ctx ->
            runCatching {
                ctx.assets.open(MANIFEST_ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrNull()?.let { return it }
        }
        AppCapabilityRegistry::class.java.classLoader
            ?.getResourceAsStream(MANIFEST_ASSET_NAME)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { return it.readText() }
        val fileCandidates = listOf(
            File("src/main/assets/$MANIFEST_ASSET_NAME"),
            File("app/src/main/assets/$MANIFEST_ASSET_NAME")
        )
        for (candidate in fileCandidates) {
            if (candidate.isFile) {
                return candidate.readText(Charsets.UTF_8)
            }
        }
        throw IllegalStateException("Missing canonical capability manifest asset: $MANIFEST_ASSET_NAME")
    }

    internal fun parseAndVerifyManifestJson(rawJson: String): LoadedCapabilityManifest {
        val root = JSONObject(rawJson)
        val manifestVersion = root.getInt("manifestVersion")
        val manifestRevision = root.getString("manifestRevision").trim()
        val manifestHash = root.getString("manifestHash").trim()
        if (manifestVersion <= 0 || manifestRevision.isEmpty() || manifestHash.isEmpty()) {
            throw IllegalStateException("Invalid capability manifest header in $MANIFEST_ASSET_NAME")
        }
        val itemsArray = root.getJSONArray("items")
        val items = buildList(itemsArray.length()) {
            for (i in 0 until itemsArray.length()) {
                val obj = itemsArray.getJSONObject(i)
                add(
                    ProductCapabilityItem(
                        id = obj.getString("id"),
                        section = obj.getString("section"),
                        title = obj.getString("title"),
                        description = obj.getString("description"),
                        defaultStatus = CapabilityAvailability.valueOf(obj.optString("defaultStatus", "SUPPORTED")),
                        minVersionCode = obj.optInt("minVersionCode", 1),
                        remoteFeatureKey = obj.optNullableString("remoteFeatureKey"),
                        conditionNote = obj.optNullableString("conditionNote"),
                        availableActions = obj.optJSONArray("availableActions").toStringList(),
                        preconditions = obj.optJSONArray("preconditions").toStringList(),
                        requiresBackend = obj.optBoolean("requiresBackend", false),
                        aiCopyRequiresBackend = obj.optBoolean("aiCopyRequiresBackend", false),
                        reversible = obj.optBoolean("reversible", true),
                        requiresConfirmation = obj.optBoolean("requiresConfirmation", false),
                        sideEffects = obj.optJSONArray("sideEffects").toStringList(),
                        failureRecoveryPaths = obj.optJSONArray("failureRecoveryPaths").toFailureRecoveryPaths()
                    )
                )
            }
        }
        if (items.isEmpty()) {
            throw IllegalStateException("Capability manifest $MANIFEST_ASSET_NAME contains no items")
        }
        val computedHash = computeManifestHash(items, manifestVersion)
        if (computedHash != manifestHash) {
            throw IllegalStateException(
                "Capability manifest hash mismatch for $MANIFEST_ASSET_NAME: expected=$manifestHash actual=$computedHash"
            )
        }
        return LoadedCapabilityManifest(
            manifestVersion = manifestVersion,
            manifestRevision = manifestRevision,
            manifestHash = manifestHash,
            items = items
        )
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotEmpty() }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (i in 0 until length()) {
                add(getString(i))
            }
        }
    }

    private fun JSONArray?.toFailureRecoveryPaths(): List<FailureRecoveryPath> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (i in 0 until length()) {
                val obj = getJSONObject(i)
                add(
                    FailureRecoveryPath(
                        condition = obj.getString("condition"),
                        explanation = obj.getString("explanation"),
                        recoveryDestination = obj.getString("recoveryDestination")
                    )
                )
            }
        }
    }

    fun computeManifestHash(
        items: List<ProductCapabilityItem> = productManifestItems,
        manifestVersion: Int = MANIFEST_VERSION
    ): String {
        val sortedItems = items.sortedBy { it.id }
        val canonical = buildString {
            append("{\"items\":[")
            sortedItems.forEachIndexed { idx, item ->
                if (idx > 0) append(',')
                append('{')
                append("\"aiCopyRequiresBackend\":"); append(item.aiCopyRequiresBackend); append(',')
                append("\"availableActions\":"); appendJsonStringList(item.availableActions); append(',')
                append("\"conditionNote\":"); appendJsonNullableString(item.conditionNote); append(',')
                append("\"defaultStatus\":"); appendJsonString(item.defaultStatus.name); append(',')
                append("\"description\":"); appendJsonString(item.description); append(',')
                append("\"failureRecoveryPaths\":"); appendFailureRecoveryPaths(item.failureRecoveryPaths); append(',')
                append("\"id\":"); appendJsonString(item.id); append(',')
                append("\"minVersionCode\":"); append(item.minVersionCode); append(',')
                append("\"preconditions\":"); appendJsonStringList(item.preconditions); append(',')
                append("\"remoteFeatureKey\":"); appendJsonNullableString(item.remoteFeatureKey); append(',')
                append("\"requiresBackend\":"); append(item.requiresBackend); append(',')
                append("\"requiresConfirmation\":"); append(item.requiresConfirmation); append(',')
                append("\"reversible\":"); append(item.reversible); append(',')
                append("\"section\":"); appendJsonString(item.section); append(',')
                append("\"sideEffects\":"); appendJsonStringList(item.sideEffects); append(',')
                append("\"title\":"); appendJsonString(item.title)
                append('}')
            }
            append("],\"manifestVersion\":")
            append(manifestVersion)
            append('}')
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun StringBuilder.appendJsonStringList(values: List<String>) {
        append('[')
        values.forEachIndexed { idx, value ->
            if (idx > 0) append(',')
            appendJsonString(value)
        }
        append(']')
    }

    private fun StringBuilder.appendFailureRecoveryPaths(paths: List<FailureRecoveryPath>) {
        append('[')
        paths.forEachIndexed { idx, path ->
            if (idx > 0) append(',')
            append('{')
            append("\"condition\":"); appendJsonString(path.condition); append(',')
            append("\"explanation\":"); appendJsonString(path.explanation); append(',')
            append("\"recoveryDestination\":"); appendJsonString(path.recoveryDestination)
            append('}')
        }
        append(']')
    }

    private fun StringBuilder.appendJsonNullableString(value: String?) {
        if (value == null) append("null") else appendJsonString(value)
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }

    fun evaluateCapabilities(snapshot: CapabilitySnapshot): List<ResolvedCapabilityItem> {
        val clientVer = snapshot.clientVersionCode
        val providerStates = listOf(
            "MovieBox" to snapshot.providerMovieboxEnabled,
            "Internet Archive" to snapshot.providerInternetArchiveEnabled,
            "TVMaze" to snapshot.providerTvmazeEnabled
        )
        val enabledProviders = providerStates.filter { it.second }.map { it.first }
        val disabledProviders = providerStates.filterNot { it.second }.map { it.first }

        return productManifestItems.map { item ->
            var status = item.defaultStatus
            var reason: String? = item.conditionNote

            if (clientVer != null && clientVer < item.minVersionCode) {
                status = CapabilityAvailability.UNAVAILABLE
                reason = "Requires a newer app version (build ${item.minVersionCode}+)."
            } else if (snapshot.onlineServicesPaused && item.id in ONLINE_BLOCKED_IDS) {
                status = CapabilityAvailability.DISABLED
                reason = "Temporarily unavailable while Laterbox online services are paused."
            } else if (snapshot.onlineServicesPaused && item.id == "downloads.media_extraction") {
                status = CapabilityAvailability.CONDITIONALLY_AVAILABLE
                reason = "On-device extraction works, while online metadata and social link services are paused."
            } else {
                when (item.id) {
                    "assistant.chat_and_actions" -> if (!snapshot.assistantEnabled) {
                        status = CapabilityAvailability.DISABLED
                        reason = "Disabled in your Laterbox Assistant settings."
                    }
                    "reminders.smart_and_scheduled" -> if (!snapshot.smartRemindersEnabled) {
                        status = CapabilityAvailability.DISABLED
                        reason = "Smart Reminders are turned off in Settings."
                    }
                    "movies.discovery_and_library" -> if (!snapshot.moviesTvEnabled) {
                        status = CapabilityAvailability.DISABLED
                        reason = "Movies & TV section is currently disabled."
                    } else if (enabledProviders.isEmpty()) {
                        status = CapabilityAvailability.CONDITIONALLY_AVAILABLE
                        reason = "All online catalogue providers are currently disabled; local Watchlist, history, and offline downloads remain available."
                    } else if (disabledProviders.isNotEmpty()) {
                        status = CapabilityAvailability.CONDITIONALLY_AVAILABLE
                        reason = "Active catalogue providers: ${enabledProviders.joinToString(", ")}. Disabled: ${disabledProviders.joinToString(", ")}."
                    }
                    "movies.pip_and_mini_player" -> if (!snapshot.moviesTvEnabled) {
                        status = CapabilityAvailability.DISABLED
                        reason = "Movies & TV section is currently disabled."
                    }
                    "movies.recommendations" -> if (!snapshot.moviesRecommendationsEnabled) {
                        status = CapabilityAvailability.DISABLED
                        reason = "Personalized recommendations are turned off."
                    } else if (!snapshot.moviesTvEnabled) {
                        status = CapabilityAvailability.DISABLED
                        reason = "Movies & TV section is currently disabled."
                    }
                    "movies.intro_skip" -> if (!snapshot.playerIntroSkipEnabled) {
                        status = CapabilityAvailability.DISABLED
                        reason = "Skip intro controls are turned off."
                    } else if (!snapshot.moviesTvEnabled) {
                        status = CapabilityAvailability.DISABLED
                        reason = "Movies & TV section is currently disabled."
                    }
                    "downloads.youtube_transcripts" -> if (!snapshot.youtubeTranscriptsReady) {
                        status = CapabilityAvailability.UNAVAILABLE
                        reason = "YouTube transcript extraction is unavailable right now."
                    } else if (snapshot.ytDlpState == "FAILED") {
                        status = CapabilityAvailability.CONDITIONALLY_AVAILABLE
                        reason = "On-device extractor initialization failed; watch-page captions fallback may still work when captions exist."
                    }
                    "downloads.saf_export" -> if (snapshot.configuredExportFolderWritable && !snapshot.configuredExportFolderName.isNullOrBlank()) {
                        status = CapabilityAvailability.SUPPORTED
                        reason = "Export folder \"${snapshot.configuredExportFolderName}\" is ready."
                    } else if (!snapshot.configuredExportFolderName.isNullOrBlank()) {
                        status = CapabilityAvailability.CONDITIONALLY_AVAILABLE
                        reason = "Export folder \"${snapshot.configuredExportFolderName}\" needs permission re-selection."
                    }
                }
            }

            ResolvedCapabilityItem(
                id = item.id,
                section = item.section,
                title = item.title,
                description = item.description,
                status = status,
                reason = reason
            )
        }
    }

    fun toRuntimeSnapshotJson(snapshot: CapabilitySnapshot): JSONObject = JSONObject().apply {
        snapshot.clientVersionCode?.let { put("clientVersionCode", it) }
        snapshot.clientVersionName?.let { put("clientVersionName", it) }
        put("manifestVersion", snapshot.manifestVersion)
        put("manifestRevision", snapshot.manifestRevision)
        put("manifestHash", MANIFEST_HASH)
        put("ytDlpState", snapshot.ytDlpState)
        put("ytDlpVersion", snapshot.ytDlpVersion ?: JSONObject.NULL)
        put("quickJsReady", snapshot.quickJsReady)
        put("quickJsVersion", snapshot.quickJsVersion ?: JSONObject.NULL)
        put("ffmpegState", snapshot.ffmpegState)
        put("youtubeTranscriptsReady", snapshot.youtubeTranscriptsReady)
        put("transcriptClipboardCopySupported", snapshot.transcriptClipboardCopySupported)
        put("downloadsLibraryDocumentsSupported", snapshot.downloadsLibraryDocumentsSupported)
        put("storedDocumentCount", snapshot.storedDocumentCount)
        put("configuredExportFolderName", snapshot.configuredExportFolderName ?: JSONObject.NULL)
        put("configuredExportFolderWritable", snapshot.configuredExportFolderWritable)
        put("assistantEnabled", snapshot.assistantEnabled)
        put("smartRemindersEnabled", snapshot.smartRemindersEnabled)
        put("quietHoursSummary", snapshot.quietHoursSummary)
        put("onlineServicesPaused", snapshot.onlineServicesPaused)
        put("moviesTvEnabled", snapshot.moviesTvEnabled)
        put("providerMovieboxEnabled", snapshot.providerMovieboxEnabled)
        put("providerInternetArchiveEnabled", snapshot.providerInternetArchiveEnabled)
        put("providerTvmazeEnabled", snapshot.providerTvmazeEnabled)
        put("moviesRecommendationsEnabled", snapshot.moviesRecommendationsEnabled)
        put("playerIntroSkipEnabled", snapshot.playerIntroSkipEnabled)
        put("moviesProvidersSummary", snapshot.moviesProvidersSummary)
        put("moviesWatchlistCount", snapshot.moviesWatchlistCount)
        put("moviesInProgressCount", snapshot.moviesInProgressCount)
        put("moviesCompletedCount", snapshot.moviesCompletedCount)
        put("moviesOfflineCount", snapshot.moviesOfflineCount)
        put("collectionsCount", snapshot.collectionsCount)
        put("moviesActiveRemindersCount", snapshot.moviesActiveRemindersCount)
    }

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
            summary = "On-device media downloader, video/audio processing, YouTube transcript documents, and offline Movies/TV library.",
            capabilities = listOf(
                "Inspect and download supported video and audio formats directly on-device.",
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
                "Browse curated rails, Picked For You, Surprise Me picks, content-aware Because You Watched & More Like This recommendations, Like/Dislike title feedback powered by always-on local Experience Intelligence, and expandable filters (Kind, Genre, Language, Country, Year, Runtime, Sort).",
                "Unified title identities across Laterbox's catalogue and guides so watchlist and history do not duplicate.",
                "Watch History split separately for Movies, Episodes, and Trailers; Continue Watching management (Resume, Restart from beginning, Remove from Continue Watching, Hide title); Rewatch tracking; Episode watched/unwatched toggles & next unwatched episode; Recently Viewed; Custom Collections.",
                "Full-screen ExoPlayer with vertical brightness/volume swipe gestures, horizontal swipe-to-seek with adjustable sensitivity, double-tap seek, up to 200% LoudnessEnhancer volume boost, subtitle styling, per-series audio/subtitle preferences, next-episode autoplay, and verified intro/recap skip buttons when timestamps are provided by metadata.",
                "Offline playback directly from downloaded Movie/Episode files in Downloads."
            ),
            constraints = listOf(
                "Skip Intro / Skip Recap only appear when verified intro/recap timestamps exist in source metadata; never guess timestamps.",
                "Playback and episode availability depend on available catalogue sources or already-downloaded local files."
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
                "Configure Gemini API key, Smart Reminders schedules, Movies & TV player and subtitles, SAF export directory, and JSON backup/restore."
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
            snapshot.onlineServicesPaused -> "Paused (online services paused)"
            snapshot.ytDlpState == "INITIALIZED" -> "Ready (yt-dlp initialized)"
            snapshot.ytDlpState == "FAILED" -> "Degraded (yt-dlp init failed; watch-page fallback may still work)"
            snapshot.youtubeTranscriptsReady -> "Ready (yt-dlp initializes on first extraction)"
            else -> "Unavailable"
        }
        appendLine("- Online services: ${if (snapshot.onlineServicesPaused) "Paused (maintenance)" else "Active"}")
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
            "- Active catalogue providers: " +
                "MovieBox=${if (snapshot.providerMovieboxEnabled) "on" else "off"}, " +
                "Internet Archive=${if (snapshot.providerInternetArchiveEnabled) "on" else "off"}, " +
                "TVMaze=${if (snapshot.providerTvmazeEnabled) "on" else "off"}"
        )
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

    fun formatEvaluatedCapabilitiesBlock(resolved: List<ResolvedCapabilityItem>): String = buildString {
        appendLine("Evaluated Capability States (Manifest v$MANIFEST_VERSION):")
        for (item in resolved) {
            val reasonPart = item.reason?.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
            appendLine("- [${item.section}] ${item.title} (${item.id}): ${item.status.name}$reasonPart")
        }
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
    const val FEATURE_EXPERIENCE_INTELLIGENCE = "feature:experience_intelligence_enabled"
    const val FEATURE_CONTEXTUAL_NOTIFICATION_GOVERNOR = "feature:contextual_notification_governor_enabled"

    const val KEY_MOVIES_TV = "movies_tv_enabled"
    const val KEY_PROVIDER_MOVIEBOX = "provider_moviebox_enabled"
    const val KEY_PROVIDER_INTERNET_ARCHIVE = "provider_internet_archive_enabled"
    const val KEY_PROVIDER_TVMAZE = "provider_tvmaze_enabled"
    const val KEY_MOVIES_RECOMMENDATIONS = "movies_recommendations_enabled"
    const val KEY_AI_ASSISTANT = "ai_assistant_enabled"
    const val KEY_SMART_REMINDERS = "smart_reminders_enabled"
    const val KEY_PLAYER_INTRO_SKIP = "player_intro_skip_enabled"
    const val KEY_YTDLP_AUTO_UPDATE = "ytdlp_auto_update_enabled"
    const val KEY_EXPERIENCE_INTELLIGENCE = "experience_intelligence_enabled"
    const val KEY_CONTEXTUAL_NOTIFICATION_GOVERNOR = "contextual_notification_governor_enabled"

    const val CAP_MULTI_SURFACE = "multi_surface"
    const val SURFACE_INBOX = "surface:inbox"
    const val SURFACE_COLLECTIONS = "surface:collections"
    const val SURFACE_MOVIES = "surface:movies"
    const val SURFACE_DOWNLOADS = "surface:downloads"
    const val SURFACE_ARCHIVE = "surface:archive"
    const val SURFACE_ASSISTANT = "surface:assistant"

    const val UI_ANNOUNCEMENT_BANNER = "ui:announcement_banner"
    const val UI_FLOATING_NOTICE = "ui:floating_notice"
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
        KEY_EXPERIENCE_INTELLIGENCE,
        KEY_CONTEXTUAL_NOTIFICATION_GOVERNOR,
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
        "recently_added",
        "top_rated",
        "discovery_sections",
    )

    val SUPPORTED_DESTINATIONS: Set<String> = setOf(
        "movies",
        "downloads",
        "collections",
        "inbox",
        "archive",
        "assistant",
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
        FEATURE_EXPERIENCE_INTELLIGENCE,
        FEATURE_CONTEXTUAL_NOTIFICATION_GOVERNOR,
        CAP_MULTI_SURFACE,
        SURFACE_INBOX,
        SURFACE_COLLECTIONS,
        SURFACE_MOVIES,
        SURFACE_DOWNLOADS,
        SURFACE_ARCHIVE,
        SURFACE_ASSISTANT,
        UI_ANNOUNCEMENT_BANNER,
        UI_FLOATING_NOTICE,
        UI_HERO_CAROUSEL,
        UI_CONTENT_RAIL,
        UI_FEATURED_CALLOUT_CARD,
    )

    fun declaredCapabilitiesCsv(): String = declaredCapabilities.sorted().joinToString(",")
}


