package com.laterbox.app.intelligence

import com.laterbox.app.data.local.dao.IntelligenceDao
import com.laterbox.app.data.local.entity.IntelligenceSignalEntity
import com.laterbox.app.data.local.entity.IntelligenceSurfaceScope
import com.laterbox.app.data.local.entity.TasteFacetType
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.domain.model.ContentType
import com.laterbox.app.domain.model.SavedLink
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.reminders.NotificationCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Bounded, privacy-safe on-device signal logger for the Experience Intelligence Layer.
 *
 * Respects both the local `personalizationEnabled` toggle and remote kill-switch.
 * Enforces strict media-only Inbox -> Movies cross-signal isolation and sensitive-topic filtering.
 */
class IntelligenceSignalLogger(
    private val intelligenceDao: IntelligenceDao,
    private val userUnderstandingRepository: UserUnderstandingRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val isIntelligenceEnabled: () -> Boolean = { true },
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    private suspend fun isActive(): Boolean {
        if (!isIntelligenceEnabled()) return false
        return runCatching {
            userPreferencesRepository.userPreferencesFlow.first().personalizationEnabled
        }.getOrDefault(false)
    }

    suspend fun logWatchlistChange(item: MovieTitleSummary, added: Boolean) {
        if (!isActive()) return
        runCatching {
            withContext(Dispatchers.IO) {
                val ts = nowMs()
                val facets = TopicExtractor.extractMediaFacets(item)
                val topicsCsv = facets.joinToString(",") { it.facetKey }
                intelligenceDao.insertSignal(
                    IntelligenceSignalEntity(
                        signalType = if (added) SIGNAL_WATCHLIST_ADD else SIGNAL_WATCHLIST_REMOVE,
                        surfaceScope = IntelligenceSurfaceScope.MOVIES.name,
                        targetKey = item.stableMediaKey,
                        topicsCsv = topicsCsv,
                        isMediaCue = true,
                        strength = if (added) 0.75 else -0.25,
                        createdAtMs = ts
                    )
                )
                for (facet in facets) {
                    if (added) {
                        val delta = if (facet.facetKey.startsWith("genre:")) 0.18 else 0.08
                        userUnderstandingRepository.reinforceFacet(
                            candidate = facet,
                            delta = delta,
                            provenance = "Added \"${item.title.take(40)}\" to watchlist",
                            evidenceSummary = "Watchlist interest in ${facet.displayLabel}",
                            facetType = TasteFacetType.ENDURING_INTEREST,
                            timestampMs = ts
                        )
                    } else {
                        userUnderstandingRepository.penalizeFacet(facet.facetKey, 0.06, ts)
                    }
                }
            }
        }
    }

    suspend fun logWatchProgress(
        item: MovieTitleSummary,
        positionMs: Long,
        durationMs: Long,
        isCompleted: Boolean
    ) {
        if (!isActive()) return
        val fraction = if (durationMs > 0L) positionMs.toDouble() / durationMs.toDouble() else 0.0
        if (!isCompleted && fraction < 0.15) return
        runCatching {
            withContext(Dispatchers.IO) {
                val ts = nowMs()
                val facets = TopicExtractor.extractMediaFacets(item)
                val topicsCsv = facets.joinToString(",") { it.facetKey }
                val signalType = if (isCompleted) SIGNAL_WATCH_COMPLETED else SIGNAL_WATCH_PROGRESS
                val strength = if (isCompleted) 1.0 else 0.45
                intelligenceDao.insertSignal(
                    IntelligenceSignalEntity(
                        signalType = signalType,
                        surfaceScope = IntelligenceSurfaceScope.MOVIES.name,
                        targetKey = item.stableMediaKey,
                        topicsCsv = topicsCsv,
                        isMediaCue = true,
                        strength = strength,
                        createdAtMs = ts
                    )
                )
                for (facet in facets) {
                    val delta = when {
                        isCompleted && facet.facetKey.startsWith("genre:") -> 0.24
                        isCompleted -> 0.10
                        facet.facetKey.startsWith("genre:") -> 0.10
                        else -> 0.04
                    }
                    userUnderstandingRepository.reinforceFacet(
                        candidate = facet,
                        delta = delta,
                        provenance = if (isCompleted) {
                            "Watched \"${item.title.take(40)}\""
                        } else {
                            "Watching \"${item.title.take(40)}\""
                        },
                        evidenceSummary = "Viewing history in ${facet.displayLabel}",
                        facetType = TasteFacetType.ENDURING_INTEREST,
                        timestampMs = ts
                    )
                }
            }
        }
    }

    suspend fun logMovieHidden(item: MovieTitleSummary) {
        if (!isActive()) return
        runCatching {
            withContext(Dispatchers.IO) {
                val ts = nowMs()
                val facets = TopicExtractor.extractMediaFacets(item)
                intelligenceDao.insertSignal(
                    IntelligenceSignalEntity(
                        signalType = SIGNAL_MOVIE_HIDE,
                        surfaceScope = IntelligenceSurfaceScope.MOVIES.name,
                        targetKey = item.stableMediaKey,
                        topicsCsv = facets.joinToString(",") { it.facetKey },
                        isMediaCue = true,
                        strength = -0.5,
                        createdAtMs = ts
                    )
                )
                for (facet in facets.filter { it.facetKey.startsWith("genre:") }) {
                    userUnderstandingRepository.penalizeFacet(facet.facetKey, 0.15, ts)
                }
            }
        }
    }

    suspend fun logMediaAddedToCollection(item: MovieTitleSummary, collectionName: String? = null) {
        if (!isActive()) return
        runCatching {
            withContext(Dispatchers.IO) {
                val ts = nowMs()
                val facets = TopicExtractor.extractMediaFacets(item)
                intelligenceDao.insertSignal(
                    IntelligenceSignalEntity(
                        signalType = SIGNAL_COLLECTION_ADD,
                        surfaceScope = IntelligenceSurfaceScope.MOVIES.name,
                        targetKey = item.stableMediaKey,
                        topicsCsv = facets.joinToString(",") { it.facetKey },
                        isMediaCue = true,
                        strength = 0.85,
                        createdAtMs = ts
                    )
                )
                val reasonSuffix = collectionName?.takeIf {
                    it.isNotBlank() && TopicExtractor.isSafeFacetLabel(it)
                }?.let { " in \"${it.take(30)}\"" }.orEmpty()
                for (facet in facets) {
                    val delta = if (facet.facetKey.startsWith("genre:")) 0.20 else 0.08
                    userUnderstandingRepository.reinforceFacet(
                        candidate = facet,
                        delta = delta,
                        provenance = "Collected \"${item.title.take(36)}\"$reasonSuffix",
                        evidenceSummary = "Added to collection (${facet.displayLabel})",
                        facetType = TasteFacetType.ENDURING_INTEREST,
                        timestampMs = ts
                    )
                }
            }
        }
    }

    suspend fun logLinkSaved(
        linkId: Long,
        url: String,
        domain: String,
        title: String,
        description: String? = null,
        contentType: ContentType = ContentType.WEBSITE,
        collectionNames: List<String> = emptyList()
    ) {
        if (!isActive()) return
        runCatching {
            withContext(Dispatchers.IO) {
                val ts = nowMs()
                val libraryTopics = TopicExtractor.extractLibraryTopics(
                    url = url,
                    domain = domain,
                    title = title,
                    description = description
                )
                val mediaCue = TopicExtractor.extractMediaCueFromLink(
                    url = url,
                    domain = domain,
                    title = title,
                    description = description,
                    contentType = contentType,
                    collectionNames = collectionNames
                )

                val signalFacets = mutableListOf<ExtractedFacetCandidate>()
                signalFacets.addAll(libraryTopics)
                if (mediaCue.isExplicitMediaCue) {
                    mediaCue.genres.forEach { g ->
                        signalFacets.add(
                            ExtractedFacetCandidate(
                                facetKey = TopicExtractor.genreFacetKey(g),
                                displayLabel = g,
                                surfaceScope = IntelligenceSurfaceScope.MOVIES,
                                isMediaCue = true
                            )
                        )
                    }
                    mediaCue.preferredKind?.let { kind ->
                        signalFacets.add(
                            ExtractedFacetCandidate(
                                facetKey = TopicExtractor.kindFacetKey(kind),
                                displayLabel = kind.label,
                                surfaceScope = IntelligenceSurfaceScope.MOVIES,
                                isMediaCue = true
                            )
                        )
                    }
                }

                if (signalFacets.isEmpty() && !mediaCue.isExplicitMediaCue) return@withContext

                val distinctFacets = signalFacets.distinctBy { it.facetKey }
                intelligenceDao.insertSignal(
                    IntelligenceSignalEntity(
                        signalType = SIGNAL_LINK_SAVE,
                        surfaceScope = if (mediaCue.isExplicitMediaCue) {
                            IntelligenceSurfaceScope.ALL.name
                        } else {
                            IntelligenceSurfaceScope.LIBRARY.name
                        },
                        targetKey = "link:$linkId",
                        topicsCsv = distinctFacets.joinToString(",") { it.facetKey },
                        isMediaCue = mediaCue.isExplicitMediaCue,
                        strength = if (mediaCue.isExplicitMediaCue) 0.70 else 0.50,
                        createdAtMs = ts
                    )
                )

                for (facet in distinctFacets) {
                    val isMovieCrossSignal = facet.surfaceScope == IntelligenceSurfaceScope.MOVIES
                    userUnderstandingRepository.reinforceFacet(
                        candidate = facet,
                        delta = if (isMovieCrossSignal) 0.16 else 0.14,
                        provenance = if (isMovieCrossSignal) {
                            mediaCue.cueReason ?: "From saved media link"
                        } else {
                            "Saved ${domain.take(36)} link"
                        },
                        evidenceSummary = if (isMovieCrossSignal) {
                            "Explicit media link cue for ${facet.displayLabel}"
                        } else {
                            "Saved reading interest in ${facet.displayLabel}"
                        },
                        facetType = TasteFacetType.ENDURING_INTEREST,
                        timestampMs = ts
                    )
                }
            }
        }
    }

    suspend fun logLinkRead(link: SavedLink) {
        if (!isActive()) return
        runCatching {
            withContext(Dispatchers.IO) {
                val ts = nowMs()
                val topics = TopicExtractor.extractLibraryTopics(
                    url = link.originalUrl,
                    domain = link.domain,
                    title = link.title,
                    description = link.description
                )
                val mediaCue = TopicExtractor.extractMediaCueFromLink(
                    url = link.originalUrl,
                    domain = link.domain,
                    title = link.title,
                    description = link.description,
                    contentType = link.contentType,
                    collectionNames = link.collectionNames
                )
                if (topics.isEmpty() && !mediaCue.isExplicitMediaCue) return@withContext

                intelligenceDao.insertSignal(
                    IntelligenceSignalEntity(
                        signalType = SIGNAL_LINK_READ,
                        surfaceScope = IntelligenceSurfaceScope.LIBRARY.name,
                        targetKey = "link:${link.id}",
                        topicsCsv = topics.joinToString(",") { it.facetKey },
                        isMediaCue = mediaCue.isExplicitMediaCue,
                        strength = 0.80,
                        createdAtMs = ts
                    )
                )
                for (topic in topics) {
                    userUnderstandingRepository.reinforceFacet(
                        candidate = topic,
                        delta = 0.18,
                        provenance = "Read \"${link.title.take(36)}\"",
                        evidenceSummary = "Read saved ${topic.displayLabel} item",
                        facetType = TasteFacetType.ENDURING_INTEREST,
                        timestampMs = ts
                    )
                }
            }
        }
    }

    suspend fun logLinkCollectionAdded(link: SavedLink, collectionName: String? = null) {
        if (!isActive()) return
        runCatching {
            withContext(Dispatchers.IO) {
                val ts = nowMs()
                val allCollections = (link.collectionNames + listOfNotNull(collectionName)).distinct()
                val topics = TopicExtractor.extractLibraryTopics(
                    url = link.originalUrl,
                    domain = link.domain,
                    title = link.title,
                    description = link.description
                )
                val mediaCue = TopicExtractor.extractMediaCueFromLink(
                    url = link.originalUrl,
                    domain = link.domain,
                    title = link.title,
                    description = link.description,
                    contentType = link.contentType,
                    collectionNames = allCollections
                )
                val allFacets = buildList {
                    addAll(topics)
                    if (mediaCue.isExplicitMediaCue) {
                        mediaCue.genres.forEach { g ->
                            add(
                                ExtractedFacetCandidate(
                                    facetKey = TopicExtractor.genreFacetKey(g),
                                    displayLabel = g,
                                    surfaceScope = IntelligenceSurfaceScope.MOVIES,
                                    isMediaCue = true
                                )
                            )
                        }
                    }
                }.distinctBy { it.facetKey }

                if (allFacets.isEmpty() && !mediaCue.isExplicitMediaCue) return@withContext

                intelligenceDao.insertSignal(
                    IntelligenceSignalEntity(
                        signalType = SIGNAL_COLLECTION_ADD,
                        surfaceScope = if (mediaCue.isExplicitMediaCue) {
                            IntelligenceSurfaceScope.ALL.name
                        } else {
                            IntelligenceSurfaceScope.LIBRARY.name
                        },
                        targetKey = "link:${link.id}",
                        topicsCsv = allFacets.joinToString(",") { it.facetKey },
                        isMediaCue = mediaCue.isExplicitMediaCue,
                        strength = 0.70,
                        createdAtMs = ts
                    )
                )
                for (facet in allFacets) {
                    userUnderstandingRepository.reinforceFacet(
                        candidate = facet,
                        delta = 0.14,
                        provenance = "Organized into collection",
                        evidenceSummary = "Collection organization (${facet.displayLabel})",
                        facetType = TasteFacetType.ENDURING_INTEREST,
                        timestampMs = ts
                    )
                }
            }
        }
    }

    suspend fun logSearch(surfaceScope: IntelligenceSurfaceScope, query: String) {
        if (!isActive()) return
        val clean = query.trim()
        if (clean.length < 2) return
        if (TopicExtractor.containsSensitiveText(clean)) return
        runCatching {
            withContext(Dispatchers.IO) {
                val ts = nowMs()
                val topics = TopicExtractor.extractLibraryTopics(query = clean)
                val genreFacet = if (surfaceScope == IntelligenceSurfaceScope.MOVIES) {
                    val lower = clean.lowercase(Locale.USCompat.US)
                    MEDIA_SEARCH_GENRES.firstOrNull { lower.contains(it.lowercase()) }?.let { g ->
                        ExtractedFacetCandidate(
                            facetKey = TopicExtractor.genreFacetKey(g),
                            displayLabel = g,
                            surfaceScope = IntelligenceSurfaceScope.MOVIES,
                            isMediaCue = true
                        )
                    }
                } else null

                val combined = (topics + listOfNotNull(genreFacet)).distinctBy { it.facetKey }
                intelligenceDao.insertSignal(
                    IntelligenceSignalEntity(
                        signalType = SIGNAL_SEARCH,
                        surfaceScope = surfaceScope.name,
                        targetKey = "search:${clean.lowercase().take(40)}",
                        topicsCsv = combined.joinToString(",") { it.facetKey },
                        isMediaCue = surfaceScope == IntelligenceSurfaceScope.MOVIES,
                        strength = 0.45,
                        createdAtMs = ts
                    )
                )
                for (facet in combined) {
                    userUnderstandingRepository.reinforceFacet(
                        candidate = facet,
                        delta = 0.12,
                        provenance = "Recent search",
                        evidenceSummary = "Searched for ${facet.displayLabel}",
                        facetType = TasteFacetType.SHORT_TERM_INTENT,
                        timestampMs = ts
                    )
                }
            }
        }
    }

    suspend fun logNotificationOpened(category: NotificationCategory, targetKey: String? = null) {
        if (!isActive()) return
        runCatching {
            withContext(Dispatchers.IO) {
                intelligenceDao.insertSignal(
                    IntelligenceSignalEntity(
                        signalType = SIGNAL_NOTIFICATION_OPENED,
                        surfaceScope = categorySurface(category).name,
                        targetKey = targetKey ?: "notif:${category.name}",
                        topicsCsv = "",
                        isMediaCue = category.name.startsWith("MOVIES_"),
                        strength = 0.60,
                        createdAtMs = nowMs()
                    )
                )
            }
        }
    }

    suspend fun logNotificationDismissed(category: NotificationCategory, targetKey: String? = null) {
        if (!isActive()) return
        runCatching {
            withContext(Dispatchers.IO) {
                intelligenceDao.insertSignal(
                    IntelligenceSignalEntity(
                        signalType = SIGNAL_NOTIFICATION_DISMISSED,
                        surfaceScope = categorySurface(category).name,
                        targetKey = targetKey ?: "notif:${category.name}",
                        topicsCsv = "",
                        isMediaCue = category.name.startsWith("MOVIES_"),
                        strength = -0.40,
                        createdAtMs = nowMs()
                    )
                )
            }
        }
    }

    private fun categorySurface(category: NotificationCategory): IntelligenceSurfaceScope = when (category) {
        NotificationCategory.MOVIE_VIEWING_REMINDER,
        NotificationCategory.MOVIES_CONTINUE_WATCHING,
        NotificationCategory.MOVIES_NEW_SEASON,
        NotificationCategory.MOVIES_WATCHLIST -> IntelligenceSurfaceScope.MOVIES
        else -> IntelligenceSurfaceScope.LIBRARY
    }

    private object Locale {
        object USCompat {
            val US: java.util.Locale = java.util.Locale.US
        }
    }

    companion object {
        const val SIGNAL_WATCHLIST_ADD = "WATCHLIST_ADD"
        const val SIGNAL_WATCHLIST_REMOVE = "WATCHLIST_REMOVE"
        const val SIGNAL_WATCH_PROGRESS = "WATCH_PROGRESS"
        const val SIGNAL_WATCH_COMPLETED = "WATCH_COMPLETED"
        const val SIGNAL_MOVIE_HIDE = "MOVIE_HIDE"
        const val SIGNAL_LINK_SAVE = "LINK_SAVE"
        const val SIGNAL_LINK_READ = "LINK_READ"
        const val SIGNAL_COLLECTION_ADD = "COLLECTION_ADD"
        const val SIGNAL_SEARCH = "SEARCH_QUERY"
        const val SIGNAL_NOTIFICATION_OPENED = "NOTIFICATION_OPENED"
        const val SIGNAL_NOTIFICATION_DISMISSED = "NOTIFICATION_DISMISSED"

        private val MEDIA_SEARCH_GENRES = listOf(
            "Sci-Fi", "Action", "Adventure", "Animation", "Anime", "Comedy",
            "Crime", "Documentary", "Drama", "Fantasy", "Horror", "Mystery",
            "Romance", "Thriller"
        )
    }
}
