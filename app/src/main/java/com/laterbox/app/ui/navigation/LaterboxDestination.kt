package com.laterbox.app.ui.navigation

import android.net.Uri

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
    object SettingsMoviesTv : LaterboxDestination("settings/movies_tv")
    object MoviesViewingHistory : LaterboxDestination("movies/viewing_history")
    object SettingsDataStorage : LaterboxDestination("settings/data_storage")
    object SettingsAppStorage : LaterboxDestination("settings/app_storage")
    object SettingsAboutPrivacy : LaterboxDestination("settings/about_privacy")
    object NotificationHistory : LaterboxDestination("notification_history")

    object CollectionDetail : LaterboxDestination("collection/{collectionId}/{collectionName}") {
        fun createRoute(collectionId: Long, collectionName: String): String {
            val encodedName = Uri.encode(collectionName)
            return "collection/$collectionId/$encodedName"
        }
    }

    object Chat : LaterboxDestination("chat?conversationId={conversationId}&initialPrompt={initialPrompt}") {
        const val BASE_ROUTE = "chat"
        fun createRoute(conversationId: String? = null, initialPrompt: String? = null): String {
            val encodedConv = Uri.encode(conversationId.orEmpty())
            val encodedPrompt = Uri.encode(initialPrompt.orEmpty())
            return "chat?conversationId=$encodedConv&initialPrompt=$encodedPrompt"
        }
    }

    object Movies : LaterboxDestination("movies")

    object MoviesWatchlist : LaterboxDestination("movies/watchlist")

    object MoviesCatalogue : LaterboxDestination("movies/catalogue?kind={kind}&genre={genre}&query={query}&sort={sort}&country={country}&focusSearch={focusSearch}") {
        fun createRoute(
            kind: String? = null,
            genre: String? = null,
            query: String? = null,
            sort: String? = null,
            country: String? = null,
            focusSearch: Boolean = false
        ): String {
            val encodedKind = Uri.encode(kind.orEmpty())
            val encodedGenre = Uri.encode(genre.orEmpty())
            val encodedQuery = Uri.encode(query.orEmpty())
            val encodedSort = Uri.encode(sort.orEmpty())
            val encodedCountry = Uri.encode(country.orEmpty())
            return "movies/catalogue?kind=$encodedKind&genre=$encodedGenre&query=$encodedQuery&sort=$encodedSort&country=$encodedCountry&focusSearch=$focusSearch"
        }
    }

    object MovieDetail : LaterboxDestination("movies/detail/{providerId}/{titleId}") {
        fun createRoute(providerId: String, titleId: String): String {
            val encodedProvider = Uri.encode(providerId)
            val encodedId = Uri.encode(titleId)
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
            val encodedProvider = Uri.encode(providerId)
            val encodedId = Uri.encode(titleId)
            val s = seasonNumber ?: -1
            val e = episodeNumber ?: -1
            return "movies/player/$encodedProvider/$encodedId?season=$s&episode=$e"
        }
    }
}
