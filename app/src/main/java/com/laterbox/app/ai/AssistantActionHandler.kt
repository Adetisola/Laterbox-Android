package com.laterbox.app.ai

import org.json.JSONArray
import org.json.JSONObject

enum class ActionCategory { READ_ONLY, STATE_CHANGING }

enum class AssistantPermissionCategory(
    val key: String,
    val displayName: String,
    val description: String,
    val requiresApprovalByDefault: Boolean
) {
    NAVIGATION_DISCOVERY(
        key = "NAVIGATION_DISCOVERY",
        displayName = "Navigation & Discovery",
        description = "Open sections, search links, or search Movies & TV catalogue.",
        requiresApprovalByDefault = false
    ),
    PLAYBACK_CONTROL(
        key = "PLAYBACK_CONTROL",
        displayName = "Playback Control",
        description = "Launch playback for movies, episodes, or downloaded videos.",
        requiresApprovalByDefault = false
    ),
    LIBRARY_MANAGEMENT(
        key = "LIBRARY_MANAGEMENT",
        displayName = "Library Management",
        description = "Manage watchlist, collections, watched history, retries, and downloads.",
        requiresApprovalByDefault = true
    ),
    REMINDER_MANAGEMENT(
        key = "REMINDER_MANAGEMENT",
        displayName = "Reminders & Alerts",
        description = "Schedule viewing reminders or mute series alerts.",
        requiresApprovalByDefault = true
    ),
    CONVERSATION_MANAGEMENT(
        key = "CONVERSATION_MANAGEMENT",
        displayName = "Conversation Management",
        description = "Rename or delete assistant conversations.",
        requiresApprovalByDefault = true
    );

    companion object {
        val configurableCategories: List<AssistantPermissionCategory> = listOf(
            LIBRARY_MANAGEMENT,
            REMINDER_MANAGEMENT,
            CONVERSATION_MANAGEMENT
        )

        fun fromKey(key: String?): AssistantPermissionCategory? =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }
    }
}

sealed interface ChatAction {
    val label: String

    val permissionCategory: AssistantPermissionCategory
        get() = when (this) {
            is OpenMoviePlayer -> AssistantPermissionCategory.PLAYBACK_CONTROL
            is RetryInspection,
            is RetryDownload,
            is RefreshMetadata,
            is ToggleMovieWatchlist,
            is AddMovieToCollection,
            is MarkMovieWatched,
            is DeleteDownload -> AssistantPermissionCategory.LIBRARY_MANAGEMENT
            is ScheduleMovieReminder,
            is MuteSeriesReminders -> AssistantPermissionCategory.REMINDER_MANAGEMENT
            is RenameConversation,
            is DeleteConversation -> AssistantPermissionCategory.CONVERSATION_MANAGEMENT
            else -> AssistantPermissionCategory.NAVIGATION_DISCOVERY
        }

    /** Irreversible destructive actions ALWAYS require explicit confirmation even when category is Always Allowed. */
    val isIrreversibleDestructive: Boolean
        get() = this is DeleteConversation || this is DeleteDownload

    /** Read-only actions run after allowlist + target validation; state-changing ones need approval. */
    val category: ActionCategory
        get() = when (this) {
            is RetryInspection,
            is RetryDownload,
            is RefreshMetadata,
            is ToggleMovieWatchlist,
            is AddMovieToCollection,
            is MarkMovieWatched,
            is ScheduleMovieReminder,
            is MuteSeriesReminders,
            is RenameConversation,
            is DeleteConversation,
            is DeleteDownload -> ActionCategory.STATE_CHANGING
            else -> ActionCategory.READ_ONLY
        }

    /** Stable per-action-type key used for "Always allow in this conversation" grants. */
    val permissionKey: String?
        get() = when (this) {
            is RetryInspection -> "RETRY_INSPECTION"
            is RetryDownload -> "RETRY_DOWNLOAD"
            is RefreshMetadata -> "REFRESH_METADATA"
            is ToggleMovieWatchlist -> "TOGGLE_MOVIE_WATCHLIST"
            is AddMovieToCollection -> "ADD_MOVIE_TO_COLLECTION"
            is MarkMovieWatched -> "MARK_MOVIE_WATCHED"
            is ScheduleMovieReminder -> "SCHEDULE_MOVIE_REMINDER"
            is MuteSeriesReminders -> "MUTE_SERIES_REMINDERS"
            is RenameConversation -> "RENAME_CONVERSATION"
            else -> null
        }

    val actionName: String
        get() = when (this) {
            is RetryInspection -> "Retry media inspection"
            is RetryDownload -> "Retry download"
            is RefreshMetadata -> "Refresh link details"
            is ToggleMovieWatchlist -> if (add) "Add to watchlist" else "Remove from watchlist"
            is AddMovieToCollection -> "Add title to collection"
            is MarkMovieWatched -> if (watched) "Mark as watched" else "Mark as unwatched"
            is ScheduleMovieReminder -> "Schedule viewing reminder"
            is MuteSeriesReminders -> if (muted) "Mute series reminders" else "Unmute series reminders"
            is RenameConversation -> "Rename conversation"
            is DeleteConversation -> "Delete conversation"
            is DeleteDownload -> "Delete downloaded file"
            else -> label
        }

    val explanation: String
        get() = when (this) {
            is RetryInspection -> "Check this saved link again for downloadable media."
            is RetryDownload -> "Restart this failed or stopped download."
            is RefreshMetadata -> "Fetch this link's title and preview again."
            is ToggleMovieWatchlist -> if (add) {
                "Save \"${title}\" to your Movies & TV watchlist."
            } else {
                "Remove \"${title}\" from your Movies & TV watchlist."
            }
            is AddMovieToCollection -> {
                val targetCol = collectionName?.takeIf { it.isNotBlank() } ?: "your selected collection"
                "Add \"${title}\" to collection \"$targetCol\"."
            }
            is MarkMovieWatched -> if (watched) {
                "Record \"${title}\" as watched in your viewing history."
            } else {
                "Clear completed watched status for \"${title}\"."
            }
            is ScheduleMovieReminder -> "Schedule a Movies & TV viewing reminder for \"${title}\"."
            is MuteSeriesReminders -> if (muted) {
                "Stop proactive Smart Reminders for \"${title}\"."
            } else {
                "Re-enable proactive Smart Reminders for \"${title}\"."
            }
            is RenameConversation -> "Rename this conversation to \"$title\" and lock the title."
            is DeleteConversation -> "Permanently remove \"${title}\" and its chat history from this device."
            is DeleteDownload -> "Permanently remove downloaded file \"${title}\" from device storage."
            else -> label
        }

    val targetLabel: String?
        get() = when (this) {
            is RetryInspection -> title
            is RetryDownload -> title
            is RefreshMetadata -> title
            is ToggleMovieWatchlist -> title
            is AddMovieToCollection -> title
            is MarkMovieWatched -> title
            is ScheduleMovieReminder -> title
            is MuteSeriesReminders -> title
            is RenameConversation -> title
            is DeleteConversation -> title
            is DeleteDownload -> title
            else -> null
        }

    val consequenceSummary: String
        get() = when (this) {
            is RetryInspection -> "Uses the network. Nothing is deleted."
            is RetryDownload -> "Uses the network and device storage. Nothing is deleted."
            is RefreshMetadata -> "Uses the network. Saved notes and collections are kept."
            is ToggleMovieWatchlist -> "Updates your local watchlist only. Nothing is deleted permanently."
            is AddMovieToCollection -> "Links this title to a local Laterbox collection."
            is MarkMovieWatched -> "Updates your local watch history & completion state."
            is ScheduleMovieReminder -> "Schedules a local reminder notification."
            is MuteSeriesReminders -> "Updates reminder mute state for this series locally."
            is RenameConversation -> "Updates the conversation title locally and prevents auto-renaming."
            is DeleteConversation -> "Irreversible: permanently deletes messages in this conversation."
            is DeleteDownload -> "Irreversible: permanently deletes this file from offline storage."
            else -> "Opens a screen. Nothing changes."
        }

    data class SearchLinks(val query: String) : ChatAction {
        override val label: String get() = "Search \"$query\""
    }

    data class OpenLinkDetail(val linkId: Long, val title: String) : ChatAction {
        override val label: String get() = "Open ${title.take(28)}"
    }

    data class OpenDownloads(val initialTab: Int = 0) : ChatAction {
        override val label: String get() = if (initialTab == 1) "Open download queue" else "Open downloads"
    }

    data class OpenYouTubeTranscripts(
        val url: String? = null,
        val labelTitle: String? = null
    ) : ChatAction {
        override val label: String
            get() = when {
                !labelTitle.isNullOrBlank() -> labelTitle.take(36)
                !url.isNullOrBlank() -> "Extract YouTube transcript"
                else -> "Open YouTube Transcripts"
            }
    }

    data object OpenCollections : ChatAction {
        override val label: String get() = "Open collections"
    }

    data class OpenSettings(val route: String = "settings") : ChatAction {
        override val label: String get() = when (route) {
            "settings/reminders_ai" -> "Reminders & AI settings"
            "settings/media_downloads" -> "Media & Downloads settings"
            "settings/movies_tv" -> "Movies & TV settings"
            "settings/capture_shortcuts" -> "Capture & Shortcuts settings"
            "settings/about_privacy" -> "About Laterbox in Settings"
            "settings/data_storage" -> "Data & Storage settings"
            else -> "Open settings"
        }
    }

    data object RunHealthCheck : ChatAction {
        override val label: String get() = "Run app health check"
    }

    data class RetryInspection(
        val linkId: Long,
        val title: String,
        val requiresConfirmation: Boolean = true
    ) : ChatAction {
        override val label: String get() = "Retry inspection: ${title.take(24)}"
    }

    data class RetryDownload(
        val taskId: String,
        val title: String,
        val requiresConfirmation: Boolean = true
    ) : ChatAction {
        override val label: String get() = "Retry download: ${title.take(24)}"
    }

    data class RefreshMetadata(
        val linkId: Long,
        val title: String,
        val requiresConfirmation: Boolean = true
    ) : ChatAction {
        override val label: String get() = "Refresh details: ${title.take(24)}"
    }

    data object OpenMoviesHome : ChatAction {
        override val label: String get() = "Open Movies & TV"
    }

    data class SearchMovies(
        val query: String,
        val kind: String? = null,
        val genre: String? = null
    ) : ChatAction {
        override val label: String get() = when {
            query.isNotBlank() -> "Search Movies: \"${query.take(24)}\""
            !genre.isNullOrBlank() -> "Browse ${genre.take(24)}"
            else -> "Browse Movies & TV"
        }
    }

    data class OpenMovieDetail(
        val providerId: String,
        val titleId: String,
        val title: String
    ) : ChatAction {
        override val label: String get() = "Open ${title.take(28)}"
    }

    data class OpenMoviePlayer(
        val providerId: String,
        val titleId: String,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        val title: String
    ) : ChatAction {
        override val label: String get() = if (seasonNumber != null && episodeNumber != null) {
            "Play ${title.take(18)} S${seasonNumber}E$episodeNumber"
        } else {
            "Play ${title.take(26)}"
        }
    }

    data class ToggleMovieWatchlist(
        val providerId: String,
        val titleId: String,
        val add: Boolean,
        val title: String,
        val requiresConfirmation: Boolean = true
    ) : ChatAction {
        override val label: String get() = if (add) {
            "Add to watchlist: ${title.take(22)}"
        } else {
            "Remove watchlist: ${title.take(22)}"
        }
    }

    data class AddMovieToCollection(
        val providerId: String,
        val titleId: String,
        val collectionId: Long? = null,
        val collectionName: String? = null,
        val title: String,
        val requiresConfirmation: Boolean = true
    ) : ChatAction {
        override val label: String get() = "Add ${title.take(18)} to ${collectionName?.take(14) ?: "collection"}"
    }

    data class MarkMovieWatched(
        val providerId: String,
        val titleId: String,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        val watched: Boolean = true,
        val title: String,
        val requiresConfirmation: Boolean = true
    ) : ChatAction {
        override val label: String get() = if (watched) {
            "Mark watched: ${title.take(22)}"
        } else {
            "Mark unwatched: ${title.take(22)}"
        }
    }

    data class ScheduleMovieReminder(
        val providerId: String,
        val titleId: String,
        val triggerAtMillis: Long,
        val note: String? = null,
        val title: String,
        val requiresConfirmation: Boolean = true
    ) : ChatAction {
        override val label: String get() = "Remind: ${title.take(26)}"
    }

    data class MuteSeriesReminders(
        val providerId: String,
        val titleId: String,
        val muted: Boolean = true,
        val title: String,
        val requiresConfirmation: Boolean = true
    ) : ChatAction {
        override val label: String get() = if (muted) {
            "Mute reminders: ${title.take(20)}"
        } else {
            "Unmute reminders: ${title.take(20)}"
        }
    }

    data class RenameConversation(
        val conversationId: String? = null,
        val title: String,
        val requiresConfirmation: Boolean = true
    ) : ChatAction {
        override val label: String get() = "Rename: ${title.take(24)}"
    }

    data class DeleteConversation(
        val conversationId: String? = null,
        val title: String,
        val requiresConfirmation: Boolean = true
    ) : ChatAction {
        override val label: String get() = "Delete conversation"
    }

    data class DeleteDownload(
        val taskId: String? = null,
        val assetId: String? = null,
        val title: String,
        val requiresConfirmation: Boolean = true
    ) : ChatAction {
        override val label: String get() = "Delete download: ${title.take(20)}"
    }
}

data class ParsedAssistantResponse(
    val displayText: String,
    val actions: List<ChatAction>
)

object AssistantActionHandler {

    private val ALLOWED_SETTINGS_ROUTES = setOf(
        "settings",
        "settings/appearance_profile",
        "settings/reminders_ai",
        "settings/capture_shortcuts",
        "settings/reading_behavior",
        "settings/media_downloads",
        "settings/movies_tv",
        "settings/data_storage",
        "settings/about_privacy"
    )

    private val ALLOWED_MEDIA_KINDS = setOf("movie", "tv_series")

    private val ACTION_BLOCK_REGEX = Regex(
        """\[ACTIONS\]\s*(.*?)\s*\[/ACTIONS\]""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    fun parseModelResponse(rawText: String): ParsedAssistantResponse {
        val match = ACTION_BLOCK_REGEX.find(rawText)
        if (match == null) {
            return ParsedAssistantResponse(
                displayText = rawText.trim(),
                actions = emptyList()
            )
        }

        val jsonPart = match.groupValues[1].trim()
        val displayText = rawText.removeRange(match.range).trim()
        val actions = fromJsonList(jsonPart)
        return ParsedAssistantResponse(
            displayText = displayText,
            actions = actions
        )
    }

    fun fromJsonList(json: String?): List<ChatAction> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val cleaned = json.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val array = JSONArray(cleaned)
            val result = mutableListOf<ChatAction>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                parseAllowlistedAction(obj)?.let { result += it }
            }
            result.distinctBy { it.toString() }.take(4)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun toJsonList(actions: List<ChatAction>): String? {
        if (actions.isEmpty()) return null
        val array = JSONArray()
        for (action in actions) {
            val obj = JSONObject()
            when (action) {
                is ChatAction.SearchLinks -> {
                    obj.put("type", "SEARCH_LINKS")
                    obj.put("query", action.query)
                }
                is ChatAction.OpenLinkDetail -> {
                    obj.put("type", "OPEN_LINK_DETAIL")
                    obj.put("linkId", action.linkId)
                    obj.put("title", action.title)
                }
                is ChatAction.OpenDownloads -> {
                    obj.put("type", "OPEN_DOWNLOADS")
                    obj.put("initialTab", action.initialTab)
                }
                is ChatAction.OpenYouTubeTranscripts -> {
                    obj.put("type", "OPEN_YOUTUBE_TRANSCRIPTS")
                    if (!action.url.isNullOrBlank()) obj.put("url", action.url)
                    if (!action.labelTitle.isNullOrBlank()) obj.put("title", action.labelTitle)
                }
                is ChatAction.OpenCollections -> {
                    obj.put("type", "OPEN_COLLECTIONS")
                }
                is ChatAction.OpenSettings -> {
                    obj.put("type", "OPEN_SETTINGS")
                    obj.put("route", action.route)
                }
                is ChatAction.RunHealthCheck -> {
                    obj.put("type", "RUN_HEALTH_CHECK")
                }
                is ChatAction.RetryInspection -> {
                    obj.put("type", "RETRY_INSPECTION")
                    obj.put("linkId", action.linkId)
                    obj.put("title", action.title)
                    obj.put("requiresConfirmation", true)
                }
                is ChatAction.RetryDownload -> {
                    obj.put("type", "RETRY_DOWNLOAD")
                    obj.put("taskId", action.taskId)
                    obj.put("title", action.title)
                    obj.put("requiresConfirmation", true)
                }
                is ChatAction.RefreshMetadata -> {
                    obj.put("type", "REFRESH_METADATA")
                    obj.put("linkId", action.linkId)
                    obj.put("title", action.title)
                    obj.put("requiresConfirmation", true)
                }
                is ChatAction.OpenMoviesHome -> {
                    obj.put("type", "OPEN_MOVIES_HOME")
                }
                is ChatAction.SearchMovies -> {
                    obj.put("type", "SEARCH_MOVIES")
                    obj.put("query", action.query)
                    if (!action.kind.isNullOrBlank()) obj.put("kind", action.kind)
                    if (!action.genre.isNullOrBlank()) obj.put("genre", action.genre)
                }
                is ChatAction.OpenMovieDetail -> {
                    obj.put("type", "OPEN_MOVIE_DETAIL")
                    obj.put("providerId", action.providerId)
                    obj.put("titleId", action.titleId)
                    obj.put("title", action.title)
                }
                is ChatAction.OpenMoviePlayer -> {
                    obj.put("type", "OPEN_MOVIE_PLAYER")
                    obj.put("providerId", action.providerId)
                    obj.put("titleId", action.titleId)
                    action.seasonNumber?.let { obj.put("seasonNumber", it) }
                    action.episodeNumber?.let { obj.put("episodeNumber", it) }
                    obj.put("title", action.title)
                }
                is ChatAction.ToggleMovieWatchlist -> {
                    obj.put("type", "TOGGLE_MOVIE_WATCHLIST")
                    obj.put("providerId", action.providerId)
                    obj.put("titleId", action.titleId)
                    obj.put("add", action.add)
                    obj.put("title", action.title)
                    obj.put("requiresConfirmation", true)
                }
                is ChatAction.AddMovieToCollection -> {
                    obj.put("type", "ADD_MOVIE_TO_COLLECTION")
                    obj.put("providerId", action.providerId)
                    obj.put("titleId", action.titleId)
                    action.collectionId?.let { obj.put("collectionId", it) }
                    if (!action.collectionName.isNullOrBlank()) obj.put("collectionName", action.collectionName)
                    obj.put("title", action.title)
                    obj.put("requiresConfirmation", true)
                }
                is ChatAction.MarkMovieWatched -> {
                    obj.put("type", "MARK_MOVIE_WATCHED")
                    obj.put("providerId", action.providerId)
                    obj.put("titleId", action.titleId)
                    action.seasonNumber?.let { obj.put("seasonNumber", it) }
                    action.episodeNumber?.let { obj.put("episodeNumber", it) }
                    obj.put("watched", action.watched)
                    obj.put("title", action.title)
                    obj.put("requiresConfirmation", true)
                }
                is ChatAction.ScheduleMovieReminder -> {
                    obj.put("type", "SCHEDULE_MOVIE_REMINDER")
                    obj.put("providerId", action.providerId)
                    obj.put("titleId", action.titleId)
                    obj.put("triggerAtMillis", action.triggerAtMillis)
                    if (!action.note.isNullOrBlank()) obj.put("note", action.note)
                    obj.put("title", action.title)
                    obj.put("requiresConfirmation", true)
                }
                is ChatAction.MuteSeriesReminders -> {
                    obj.put("type", "MUTE_SERIES_REMINDERS")
                    obj.put("providerId", action.providerId)
                    obj.put("titleId", action.titleId)
                    obj.put("muted", action.muted)
                    obj.put("title", action.title)
                    obj.put("requiresConfirmation", true)
                }
                is ChatAction.RenameConversation -> {
                    obj.put("type", "RENAME_CONVERSATION")
                    action.conversationId?.let { obj.put("conversationId", it) }
                    obj.put("title", action.title)
                    obj.put("requiresConfirmation", true)
                }
                is ChatAction.DeleteConversation -> {
                    obj.put("type", "DELETE_CONVERSATION")
                    action.conversationId?.let { obj.put("conversationId", it) }
                    obj.put("title", action.title)
                    obj.put("requiresConfirmation", true)
                }
                is ChatAction.DeleteDownload -> {
                    obj.put("type", "DELETE_DOWNLOAD")
                    action.taskId?.let { obj.put("taskId", it) }
                    action.assetId?.let { obj.put("assetId", it) }
                    obj.put("title", action.title)
                    obj.put("requiresConfirmation", true)
                }
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun parseAllowlistedAction(obj: JSONObject): ChatAction? {
        return when (obj.optString("type").trim().uppercase()) {
            "SEARCH_LINKS" -> {
                val q = obj.optString("query").trim().take(80)
                if (q.isNotBlank()) ChatAction.SearchLinks(q) else null
            }
            "OPEN_LINK_DETAIL" -> {
                val linkId = obj.optLong("linkId", -1L)
                val title = obj.optString("title").trim().ifBlank { "Saved link" }.take(60)
                if (linkId > 0L) ChatAction.OpenLinkDetail(linkId, title) else null
            }
            "OPEN_DOWNLOADS" -> {
                val tab = obj.optInt("initialTab", 0).coerceIn(0, 1)
                ChatAction.OpenDownloads(tab)
            }
            "OPEN_YOUTUBE_TRANSCRIPTS" -> {
                val rawUrl = obj.optString("url").trim().takeIf { it.isNotBlank() }?.take(300)
                val validUrl = if (rawUrl != null && com.laterbox.app.transcript.domain.YouTubeUrlParser.isYouTubeUrl(rawUrl)) {
                    rawUrl
                } else {
                    null
                }
                val title = obj.optString("title").trim().takeIf { it.isNotBlank() }?.take(60)
                ChatAction.OpenYouTubeTranscripts(url = validUrl, labelTitle = title)
            }
            "OPEN_COLLECTIONS" -> ChatAction.OpenCollections
            "OPEN_SETTINGS" -> {
                val route = obj.optString("route").trim().ifBlank { "settings" }
                if (route in ALLOWED_SETTINGS_ROUTES) ChatAction.OpenSettings(route) else ChatAction.OpenSettings("settings")
            }
            "RUN_HEALTH_CHECK" -> ChatAction.RunHealthCheck
            "RETRY_INSPECTION" -> {
                val linkId = obj.optLong("linkId", -1L)
                val title = obj.optString("title").trim().ifBlank { "Saved link" }.take(60)
                // Mutating action ALWAYS enforces confirmation gating regardless of model JSON
                if (linkId > 0L) ChatAction.RetryInspection(linkId, title, requiresConfirmation = true) else null
            }
            "RETRY_DOWNLOAD" -> {
                val taskId = obj.optString("taskId").trim().take(80)
                val title = obj.optString("title").trim().ifBlank { "Download task" }.take(60)
                // Mutating action ALWAYS enforces confirmation gating regardless of model JSON
                if (taskId.isNotBlank()) ChatAction.RetryDownload(taskId, title, requiresConfirmation = true) else null
            }
            "REFRESH_METADATA" -> {
                val linkId = obj.optLong("linkId", -1L)
                val title = obj.optString("title").trim().ifBlank { "Saved link" }.take(60)
                if (linkId > 0L) ChatAction.RefreshMetadata(linkId, title, requiresConfirmation = true) else null
            }
            "OPEN_MOVIES_HOME" -> ChatAction.OpenMoviesHome
            "SEARCH_MOVIES" -> {
                val q = obj.optString("query").trim().take(80)
                val rawKind = obj.optString("kind").trim().lowercase().takeIf { it in ALLOWED_MEDIA_KINDS }
                val genre = obj.optString("genre").trim().takeIf { it.isNotBlank() }?.take(40)
                if (q.isNotBlank() || genre != null || rawKind != null) {
                    ChatAction.SearchMovies(query = q, kind = rawKind, genre = genre)
                } else null
            }
            "OPEN_MOVIE_DETAIL" -> {
                val providerId = obj.optString("providerId").trim().take(40)
                val titleId = obj.optString("titleId").trim().take(80)
                val title = obj.optString("title").trim().ifBlank { "Title" }.take(60)
                if (providerId.isNotBlank() && titleId.isNotBlank()) {
                    ChatAction.OpenMovieDetail(providerId, titleId, title)
                } else null
            }
            "OPEN_MOVIE_PLAYER" -> {
                val providerId = obj.optString("providerId").trim().take(40)
                val titleId = obj.optString("titleId").trim().take(80)
                val season = obj.optInt("seasonNumber", -1).takeIf { it > 0 }
                val episode = obj.optInt("episodeNumber", -1).takeIf { it > 0 }
                val title = obj.optString("title").trim().ifBlank { "Title" }.take(60)
                if (providerId.isNotBlank() && titleId.isNotBlank()) {
                    ChatAction.OpenMoviePlayer(providerId, titleId, season, episode, title)
                } else null
            }
            "TOGGLE_MOVIE_WATCHLIST" -> {
                val providerId = obj.optString("providerId").trim().take(40)
                val titleId = obj.optString("titleId").trim().take(80)
                val add = obj.optBoolean("add", true)
                val title = obj.optString("title").trim().ifBlank { "Title" }.take(60)
                if (providerId.isNotBlank() && titleId.isNotBlank()) {
                    ChatAction.ToggleMovieWatchlist(providerId, titleId, add, title, requiresConfirmation = true)
                } else null
            }
            "ADD_MOVIE_TO_COLLECTION" -> {
                val providerId = obj.optString("providerId").trim().take(40)
                val titleId = obj.optString("titleId").trim().take(80)
                val collectionId = obj.optLong("collectionId", -1L).takeIf { it > 0L }
                val collectionName = obj.optString("collectionName").trim().takeIf { it.isNotBlank() }?.take(50)
                val title = obj.optString("title").trim().ifBlank { "Title" }.take(60)
                if (providerId.isNotBlank() && titleId.isNotBlank() && (collectionId != null || collectionName != null)) {
                    ChatAction.AddMovieToCollection(
                        providerId = providerId,
                        titleId = titleId,
                        collectionId = collectionId,
                        collectionName = collectionName,
                        title = title,
                        requiresConfirmation = true
                    )
                } else null
            }
            "MARK_MOVIE_WATCHED" -> {
                val providerId = obj.optString("providerId").trim().take(40)
                val titleId = obj.optString("titleId").trim().take(80)
                val season = obj.optInt("seasonNumber", -1).takeIf { it > 0 }
                val episode = obj.optInt("episodeNumber", -1).takeIf { it > 0 }
                val watched = obj.optBoolean("watched", true)
                val title = obj.optString("title").trim().ifBlank { "Title" }.take(60)
                if (providerId.isNotBlank() && titleId.isNotBlank()) {
                    ChatAction.MarkMovieWatched(
                        providerId = providerId,
                        titleId = titleId,
                        seasonNumber = season,
                        episodeNumber = episode,
                        watched = watched,
                        title = title,
                        requiresConfirmation = true
                    )
                } else null
            }
            "SCHEDULE_MOVIE_REMINDER" -> {
                val providerId = obj.optString("providerId").trim().take(40)
                val titleId = obj.optString("titleId").trim().take(80)
                val triggerAtMillis = obj.optLong("triggerAtMillis", -1L)
                val note = obj.optString("note").trim().takeIf { it.isNotBlank() }?.take(120)
                val title = obj.optString("title").trim().ifBlank { "Title" }.take(60)
                if (providerId.isNotBlank() && titleId.isNotBlank() && triggerAtMillis > 0L) {
                    ChatAction.ScheduleMovieReminder(
                        providerId = providerId,
                        titleId = titleId,
                        triggerAtMillis = triggerAtMillis,
                        note = note,
                        title = title,
                        requiresConfirmation = true
                    )
                } else null
            }
            "MUTE_SERIES_REMINDERS" -> {
                val providerId = obj.optString("providerId").trim().take(40)
                val titleId = obj.optString("titleId").trim().take(80)
                val muted = obj.optBoolean("muted", true)
                val title = obj.optString("title").trim().ifBlank { "Series" }.take(60)
                if (providerId.isNotBlank() && titleId.isNotBlank()) {
                    ChatAction.MuteSeriesReminders(
                        providerId = providerId,
                        titleId = titleId,
                        muted = muted,
                        title = title,
                        requiresConfirmation = true
                    )
                } else null
            }
            "RENAME_CONVERSATION" -> {
                val convId = obj.optString("conversationId").trim().takeIf { it.isNotBlank() }?.take(80)
                val title = obj.optString("title").trim().take(60)
                if (title.isNotBlank()) {
                    ChatAction.RenameConversation(
                        conversationId = convId,
                        title = title,
                        requiresConfirmation = true
                    )
                } else null
            }
            "DELETE_CONVERSATION" -> {
                val convId = obj.optString("conversationId").trim().takeIf { it.isNotBlank() }?.take(80)
                val title = obj.optString("title").trim().ifBlank { "this conversation" }.take(60)
                ChatAction.DeleteConversation(
                    conversationId = convId,
                    title = title,
                    requiresConfirmation = true
                )
            }
            "DELETE_DOWNLOAD" -> {
                val taskId = obj.optString("taskId").trim().takeIf { it.isNotBlank() }?.take(80)
                val assetId = obj.optString("assetId").trim().takeIf { it.isNotBlank() }?.take(80)
                val title = obj.optString("title").trim().ifBlank { "Downloaded file" }.take(60)
                if (taskId != null || assetId != null) {
                    ChatAction.DeleteDownload(
                        taskId = taskId,
                        assetId = assetId,
                        title = title,
                        requiresConfirmation = true
                    )
                } else null
            }
            // Unknown types are never accepted.
            else -> null
        }
    }
}
