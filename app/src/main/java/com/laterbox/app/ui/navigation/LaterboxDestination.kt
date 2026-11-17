package com.laterbox.app.ui.navigation

sealed class LaterboxDestination(val route: String) {
    object Inbox : LaterboxDestination("inbox")
    object Collections : LaterboxDestination("collections")
    object Downloads : LaterboxDestination("downloads")
    object Archive : LaterboxDestination("archive")
    object Settings : LaterboxDestination("settings")
    object SettingsAppearanceProfile : LaterboxDestination("settings/appearance_profile")
    object SettingsRemindersAi : LaterboxDestination("settings/reminders_ai")
    object SettingsCaptureShortcuts : LaterboxDestination("settings/capture_shortcuts")
    object SettingsReadingBehavior : LaterboxDestination("settings/reading_behavior")
    object SettingsMediaDownloads : LaterboxDestination("settings/media_downloads")
    object SettingsDataStorage : LaterboxDestination("settings/data_storage")
    object SettingsAboutPrivacy : LaterboxDestination("settings/about_privacy")
    object NotificationHistory : LaterboxDestination("notification_history")

    object CollectionDetail : LaterboxDestination("collection/{collectionId}/{collectionName}") {
        fun createRoute(collectionId: Long, collectionName: String): String {
            val encodedName = java.net.URLEncoder.encode(collectionName, "UTF-8")
            return "collection/$collectionId/$encodedName"
        }
    }

    object Chat : LaterboxDestination("chat?conversationId={conversationId}&initialPrompt={initialPrompt}") {
        const val BASE_ROUTE = "chat"
        fun createRoute(conversationId: String? = null, initialPrompt: String? = null): String {
            val encodedConv = java.net.URLEncoder.encode(conversationId.orEmpty(), "UTF-8")
            val encodedPrompt = java.net.URLEncoder.encode(initialPrompt.orEmpty(), "UTF-8")
            return "chat?conversationId=$encodedConv&initialPrompt=$encodedPrompt"
        }
    }

    object Movies : LaterboxDestination("movies")

    object MoviesCatalogue : LaterboxDestination("movies/catalogue?kind={kind}&genre={genre}&query={query}&sort={sort}&country={country}") {
        fun createRoute(
            kind: String? = null,
            genre: String? = null,
            query: String? = null,
            sort: String? = null,
            country: String? = null
        ): String {
            val encodedKind = java.net.URLEncoder.encode(kind.orEmpty(), "UTF-8")
            val encodedGenre = java.net.URLEncoder.encode(genre.orEmpty(), "UTF-8")
            val encodedQuery = java.net.URLEncoder.encode(query.orEmpty(), "UTF-8")
            val encodedSort = java.net.URLEncoder.encode(sort.orEmpty(), "UTF-8")
            val encodedCountry = java.net.URLEncoder.encode(country.orEmpty(), "UTF-8")
            return "movies/catalogue?kind=$encodedKind&genre=$encodedGenre&query=$encodedQuery&sort=$encodedSort&country=$encodedCountry"
        }
    }

    object MovieDetail : LaterboxDestination("movies/detail/{providerId}/{titleId}") {
        fun createRoute(providerId: String, titleId: String): String {
            val encodedProvider = java.net.URLEncoder.encode(providerId, "UTF-8")
            val encodedId = java.net.URLEncoder.encode(titleId, "UTF-8")
            return "movies/detail/$encodedProvider/$encodedId"
        }
    }

    object MoviePlayer : LaterboxDestination("movies/player/{providerId}/{titleId}?season={season}&episode={episode}") {
        fun createRoute(
            providerId: String,
            titleId: String,
            seasonNumber: Int? = null,
            episodeNumber: Int? = null
        ): String {
            val encodedProvider = java.net.URLEncoder.encode(providerId, "UTF-8")
            val encodedId = java.net.URLEncoder.encode(titleId, "UTF-8")
            val s = seasonNumber ?: -1
            val e = episodeNumber ?: -1
            return "movies/player/$encodedProvider/$encodedId?season=$s&episode=$e"
        }
    }
}
