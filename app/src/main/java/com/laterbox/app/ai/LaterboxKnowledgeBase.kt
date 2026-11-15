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
        - {"type":"OPEN_SETTINGS","route":"settings"} (valid routes: settings, settings/reminders_ai, settings/media_downloads, settings/capture_shortcuts, settings/about_privacy, settings/data_storage)
        - {"type":"RUN_HEALTH_CHECK"}
        - {"type":"RETRY_INSPECTION","linkId":123,"title":"<short label>"} (only when inspection failed for an exact linkId in context)
        - {"type":"RETRY_DOWNLOAD","taskId":"<exact taskId>","title":"<short label>"} (only when download failed for an exact taskId in context)
        - {"type":"REFRESH_METADATA","linkId":123,"title":"<short label>"} (to refresh title/preview for an exact linkId in context)
        Never invent linkId or taskId values not present in the context.
    """.trimIndent()

    val FEATURE_GUIDE: String
        get() = AppCapabilityRegistry.staticFeatureGuide
}
