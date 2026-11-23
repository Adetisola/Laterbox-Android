package com.laterbox.app.ai

/**
 * Concise, factual guide to Laterbox's real features and honest limitations.
 * Used to ground the Gemini assistant so it never hallucinates non-existent capabilities.
 */
object LaterboxKnowledgeBase {

    val ALLOWED_ACTION_SPEC: String = """
        If and only if one or more follow-up in-app actions directly help the user, you may append ONE block at the very end of your reply in the exact format:
        [ACTIONS]
        [{"type":"..."}]
        [/ACTIONS]
        Allowed action objects ONLY:
        - {"type":"SEARCH_LINKS","query":"<search terms>"}
        - {"type":"OPEN_LINK_DETAIL","linkId":123,"title":"<short label>"}
        - {"type":"OPEN_DOWNLOADS","initialTab":0}  (0 = Library, 1 = Queue)
        - {"type":"OPEN_YOUTUBE_TRANSCRIPTS","url":"<optional youtube video or playlist url>","title":"<short label>"} (opens YouTube Transcript extractor sheet read-only; omit url to open manual input)
        - {"type":"OPEN_COLLECTIONS"}
        - {"type":"OPEN_SETTINGS","route":"settings"} (valid routes: settings, settings/reminders_ai, settings/media_downloads, settings/movies_tv, settings/capture_shortcuts, settings/data_storage)
        - {"type":"RETRY_INSPECTION","linkId":123,"title":"<short label>"} (only when inspection failed for an exact linkId in context)
        - {"type":"RETRY_DOWNLOAD","taskId":"<exact taskId>","title":"<short label>"} (only when download failed for an exact taskId in context)
        - {"type":"REFRESH_METADATA","linkId":123,"title":"<short label>"} (to refresh title/preview for an exact linkId in context)
        - {"type":"OPEN_MOVIES_HOME"} (read-only: opens Movies & TV home)
        - {"type":"SEARCH_MOVIES","query":"<search terms>","kind":"movie","genre":"<optional genre>"} (read-only: opens Movies & TV catalogue/search; kind/genre optional)
        - {"type":"OPEN_MOVIE_DETAIL","providerId":"<providerId>","titleId":"<titleId>","title":"<short label>"} (read-only: exact providerId & titleId from context)
        - {"type":"OPEN_MOVIE_PLAYER","providerId":"<providerId>","titleId":"<titleId>","seasonNumber":1,"episodeNumber":1,"title":"<short label>"} (read-only: seasonNumber & episodeNumber optional for movies)
        - {"type":"TOGGLE_MOVIE_WATCHLIST","providerId":"<providerId>","titleId":"<titleId>","add":true,"title":"<short label>"} (confirmation-gated state change)
        - {"type":"ADD_MOVIE_TO_COLLECTION","providerId":"<providerId>","titleId":"<titleId>","collectionId":1,"collectionName":"<name>","title":"<short label>"} (confirmation-gated state change; provide collectionId or collectionName)
        - {"type":"MARK_MOVIE_WATCHED","providerId":"<providerId>","titleId":"<titleId>","seasonNumber":1,"episodeNumber":1,"watched":true,"title":"<short label>"} (confirmation-gated state change; season/episode optional)
        - {"type":"SCHEDULE_MOVIE_REMINDER","providerId":"<providerId>","titleId":"<titleId>","triggerAtMillis":1730000000000,"note":"<optional note>","title":"<short label>"} (confirmation-gated state change)
        - {"type":"MUTE_SERIES_REMINDERS","providerId":"<providerId>","titleId":"<titleId>","muted":true,"title":"<short label>"} (confirmation-gated state change)
        - {"type":"RENAME_CONVERSATION","title":"<short new title>"} (confirmation-gated state change)
        - {"type":"DELETE_CONVERSATION","title":"<current conversation title>"} (irreversible destructive action; always asks confirmation)
        - {"type":"DELETE_DOWNLOAD","taskId":"<optional taskId>","assetId":"<optional assetId>","title":"<file title>"} (irreversible destructive action; always asks confirmation)
        Recommendation & Data Guardrails:
        - Never invent linkId, taskId, assetId, providerId, or titleId values not present in the context.
        - Never emit OPEN_MOVIE_DETAIL, OPEN_MOVIE_PLAYER, TOGGLE_MOVIE_WATCHLIST, or MARK_MOVIE_WATCHED for general knowledge titles without exact providerId and titleId from context.
        - Clearly distinguish titles saved in Laterbox locally, available in active catalogue search results, or recommended from general knowledge.
    """.trimIndent()

    val FEATURE_GUIDE: String
        get() = AppCapabilityRegistry.staticFeatureGuide
}
