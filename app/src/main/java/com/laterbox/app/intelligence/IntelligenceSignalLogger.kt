package com.laterbox.app.intelligence

import com.laterbox.app.data.local.dao.IntelligenceDao
import com.laterbox.app.data.local.entity.IntelligenceSignalEntity
import com.laterbox.app.data.local.entity.IntelligenceSurfaceScope
import com.laterbox.app.data.local.entity.TasteFacetType
import com.laterbox.app.data.local.entity.TitleReaction
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.domain.model.ContentType
import com.laterbox.app.domain.model.SavedLink
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.reminders.NotificationCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Always-on, privacy-safe on-device signal logger for the Experience Intelligence Layer.
 *
 * Enforces strict media-only Inbox -> Movies cross-signal isolation, sensitive-topic filtering,
 * sanitized provenance/target keys (zero plaintext search queries or collection names),
 * and distinct behavioral signal semantics.
 */
class IntelligenceSignalLogger(
    private val intelligenceDao: IntelligenceDao,
    private val userUnderstandingRepository: UserUnderstandingRepository,
    @Suppress("UNUSED_PARAMETER") private val userPreferencesRepository: UserPreferencesRepository,
    private val isIntelligenceEnabled: () -> Boolean = { true },
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    private fun isActive(): Boolean = isIntelligenceEnabled()

    suspend fun logTitleFeedback(item: MovieTitleSummary, reaction: TitleReaction?) {
        if (!isActive()) return
        runCatching {
            withContext(Dispatchers.IO) {
                val ts = nowMs()
                userUnderstandingRepository.setTitleReaction(item, reaction, ts)
                if (reaction == null) return@withContext

                val facets = TopicExtractor.extractMediaFacets(item)
                val signalType = if (reaction == TitleReaction.LIKE) SIGNAL_TITLE_LIKE else SIGNAL_TITLE_DISLIKE
                val strength = if (reaction == TitleReaction.LIKE) 1.0 else -1.0
                intelligenceDao.insertSignal(
                    IntelligenceSignalEntity(
                        signalType = signalType,
                        surfaceScope = IntelligenceSurfaceScope.MOVIES.name,
                        targetKey = item.stableMediaKey,
                        topicsCsv = facets.joinToString(",") { it.facetKey },
                        isMediaCue = true,
                        strength = strength,
                        createdAtMs = ts
                    )
                )
                if (reaction == TitleReaction.LIKE) {
                    for (facet in facets) {
                        val delta = if (facet.facetKey.startsWith("genre:")) 0.26 else 0.10
                        userUnderstandingRepository.reinforceFacet(
                            candidate = facet,
                            delta = delta,
                            provenance = "Liked a ${facet.displayLabel} title",
                            evidenceSummary = "Explicit title like (${facet.displayLabel})",
                            facetType = TasteFacetType.ENDURING_INTEREST,
                            timestampMs = ts,
                            signalType = SIGNAL_TITLE_LIKE,
                            signalQuality = 0.42,
                            isBehavioralEvidence = true
                        )
                    }
                } else {
                    // Cautious negative implication: suppress the specific title via title_feedback,
                    // apply only a gentle attribute nudge so one dislike never bans an entire genre.
                    for (facet in facets.filter { it.facetKey.startsWith("genre:") }) {
                        userUnderstandingRepository.penalizeFacet(facet.facetKey, 0.04, ts)
                    }
                }
            }
        }
    }

    suspend fun logDetailOpened(item: MovieTitleSummary) {
        if (!isActive()) return
        runCatching {
            withContext(Dispatchers.IO) {
                val ts = nowMs()
                val facets = TopicExtractor.extractMediaFacets(item)
                intelligenceDao.insertSignal(
                    IntelligenceSignalEntity(
                        signalType = SIGNAL_DETAIL_OPEN,
                        surfaceScope = IntelligenceSurfaceScope.MOVIES.name,
                        targetKey = item.stableMediaKey,
                        topicsCsv = facets.joinToString(",") { it.facetKey },
                        isMediaCue = true,
                        strength = 0.20,
                        createdAtMs = ts
                    )
                )
                for (facet in facets) {
                    val delta = if (facet.facetKey.startsWith("genre:")) 0.03 else 0.015
                    userUnderstandingRepository.reinforceFacet(
                        candidate = facet,
                        delta = delta,
                        provenance = "Explored ${facet.displayLabel} details",
                        evidenceSummary = "Exploratory detail view (${facet.displayLabel})",
                        facetType = TasteFacetType.SHORT_TERM_INTENT,
                        timestampMs = ts,
                        signalType = SIGNAL_DETAIL_OPEN,
                        signalQuality = 0.05,
                        isBehavioralEvidence = true
                    )
                }
            }
        }
    }

    suspend fun logPlaybackStarted(item: MovieTitleSummary) {
        if (!isActive()) return
        runCatching {
            withContext(Dispatchers.IO) {
                val ts = nowMs()
                val facets = TopicExtractor.extractMediaFacets(item)
                intelligenceDao.insertSignal(
                    IntelligenceSignalEntity(
                        signalType = SIGNAL_PLAYBACK_START,
                        surfaceScope = IntelligenceSurfaceScope.MOVIES.name,
                        targetKey = item.stableMediaKey,
                        topicsCsv = facets.joinToString(",") { it.facetKey },
                        isMediaCue = true,
                        strength = 0.35,
                        createdAtMs = ts
                    )
                )
                for (facet in facets) {
                    val delta = if (facet.facetKey.startsWith("genre:")) 0.05 else 0.02
                    userUnderstandingRepository.reinforceFacet(
                        candidate = facet,
                        delta = delta,
                        provenance = "Started watching ${facet.displayLabel}",
                        evidenceSummary = "Playback started (${facet.displayLabel})",
                        facetType = TasteFacetType.SHORT_TERM_INTENT,
                        timestampMs = ts,
                        signalType = SIGNAL_PLAYBACK_START,
                        signalQuality = 0.08,
                        isBehavioralEvidence = true
                    )
                }
            }
        }
    }

    suspend fun logEarlyPlaybackExit(item: MovieTitleSummary) {
        if (!isActive()) return
        runCatching {
            withContext(Dispatchers.IO) {
                val ts = nowMs()
                val facets = TopicExtractor.extractMediaFacets(item)
                intelligenceDao.insertSignal(
                    IntelligenceSignalEntity(
                        signalType = SIGNAL_EARLY_PLAYBACK_EXIT,
                        surfaceScope = IntelligenceSurfaceScope.MOVIES.name,
                        targetKey = item.stableMediaKey,
                        topicsCsv = facets.joinToString(",") { it.facetKey },
                        isMediaCue = true,
                        strength = -0.10,
                        createdAtMs = ts
                    )
                )
                // Contextual signal only; never interpret early playback exit as a broad genre dislike.
            }
        }
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
                        strength = if (added) 0.70 else 0.0,
                        createdAtMs = ts
                    )
                )
                if (added) {
                    for (facet in facets) {
                        val delta = if (facet.facetKey.startsWith("genre:")) 0.15 else 0.07
                        userUnderstandingRepository.reinforceFacet(
                            candidate = facet,
                            delta = delta,
                            provenance = "Added to watchlist",
                            evidenceSummary = "Future viewing intent (${facet.displayLabel})",
                            facetType = TasteFacetType.ENDURING_INTEREST,
                            timestampMs = ts,
                            signalType = SIGNAL_WATCHLIST_ADD,
                            signalQuality = 0.24,
                            isBehavioralEvidence = true
                        )
                    }
                }
                // Watchlist removal is NOT a dislike; never penalize genres.
            }
        }
    }

    suspend fun logReminderChange(item: MovieTitleSummary, added: Boolean) {
        if (!isActive()) return
        runCatching {
            withContext(Dispatchers.IO) {
                val ts = nowMs()
                val facets = TopicExtractor.extractMediaFacets(item)
                intelligenceDao.insertSignal(
                    IntelligenceSignalEntity(
                        signalType = if (added) SIGNAL_REMINDER_SET else SIGNAL_REMINDER_REMOVE,
                        surfaceScope = IntelligenceSurfaceScope.MOVIES.name,
                        targetKey = item.stableMediaKey,
                        topicsCsv = facets.joinToString(",") { it.facetKey },
                        isMediaCue = true,
                        strength = if (added) 0.72 else 0.0,
                        createdAtMs = ts
                    )
                )
                if (added) {
                    for (facet in facets) {
                        val delta = if (facet.facetKey.startsWith("genre:")) 0.14 else 0.06
                        userUnderstandingRepository.reinforceFacet(
                            candidate = facet,
                            delta = delta,
                            provenance = "Set viewing reminder",
                            evidenceSummary = "Explicit future intent (${facet.displayLabel})",
                            facetType = TasteFacetType.ENDURING_INTEREST,
                            timestampMs = ts,
                            signalType = SIGNAL_REMINDER_SET,
                            signalQuality = 0.22,
                            isBehavioralEvidence = true
                        )
                    }
                }
            }
        }
    }

    suspend fun logLinkReminderChange(link: SavedLink, added: Boolean) {
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
                intelligenceDao.insertSignal(
                    IntelligenceSignalEntity(
                        signalType = if (added) SIGNAL_REMINDER_SET else SIGNAL_REMINDER_REMOVE,
                        surfaceScope = IntelligenceSurfaceScope.LIBRARY.name,
                        targetKey = "link:${link.id}",
                        topicsCsv = topics.joinToString(",") { it.facetKey },
                        isMediaCue = false,
                        strength = if (added) 0.65 else 0.0,
                        createdAtMs = ts
                    )
                )
                if (added) {
                    for (topic in topics) {
                        userUnderstandingRepository.reinforceFacet(
                            candidate = topic,
                            delta = 0.12,
                            provenance = "Set link reminder",
                            evidenceSummary = "Explicit future reading intent (${topic.displayLabel})",
                            facetType = TasteFacetType.ENDURING_INTEREST,
                            timestampMs = ts,
                            signalType = SIGNAL_REMINDER_SET,
                            signalQuality = 0.20,
                            isBehavioralEvidence = true
                        )
                    }
                }
            }
        }
    }

    suspend fun logMovieDownload(item: MovieTitleSummary, removed: Boolean = false) {
        if (!isActive()) return
        runCatching {
            withContext(Dispatchers.IO) {
                val ts = nowMs()
                val facets = TopicExtractor.extractMediaFacets(item)
                intelligenceDao.insertSignal(
                    IntelligenceSignalEntity(
                        signalType = if (removed) SIGNAL_MOVIE_DOWNLOAD_REMOVE else SIGNAL_MOVIE_DOWNLOAD,
                        surfaceScope = IntelligenceSurfaceScope.MOVIES.name,
                        targetKey = item.stableMediaKey,
                        topicsCsv = facets.joinToString(",") { it.facetKey },
                        isMediaCue = true,
                        strength = if (removed) 0.0 else 0.62,
                        createdAtMs = ts
                    )
                )
                if (!removed) {
                    for (facet in facets) {
                        val delta = if (facet.facetKey.startsWith("genre:")) 0.11 else 0.05
                        userUnderstandingRepository.reinforceFacet(
                            candidate = facet,
                            delta = delta,
                            provenance = "Downloaded for offline viewing",
                            evidenceSummary = "Download interest (${facet.displayLabel})",
                            facetType = TasteFacetType.ENDURING_INTEREST,
                            timestampMs = ts,
                            signalType = SIGNAL_MOVIE_DOWNLOAD,
                            signalQuality = 0.16,
                            isBehavioralEvidence = true
                        )
                    }
                }
            }
        }
    }

    suspend fun logTitleShared(item: MovieTitleSummary) {
        if (!isActive()) return
        runCatching {
            withContext(Dispatchers.IO) {
                val ts = nowMs()
                val facets = TopicExtractor.extractMediaFacets(item)
                intelligenceDao.insertSignal(
                    IntelligenceSignalEntity(
                        signalType = SIGNAL_TITLE_SHARE,
                        surfaceScope = IntelligenceSurfaceScope.MOVIES.name,
                        targetKey = item.stableMediaKey,
                        topicsCsv = facets.joinToString(",") { it.facetKey },
                        isMediaCue = true,
                        strength = 0.45,
                        createdAtMs = ts
                    )
                )
                for (facet in facets) {
                    val delta = if (facet.facetKey.startsWith("genre:")) 0.07 else 0.03
                    userUnderstandingRepository.reinforceFacet(
                        candidate = facet,
                        delta = delta,
                        provenance = "Shared ${facet.displayLabel} title",
                        evidenceSummary = "Shared title (${facet.displayLabel})",
                        facetType = TasteFacetType.SHORT_TERM_INTENT,
                        timestampMs = ts,
                        signalType = SIGNAL_TITLE_SHARE,
                        signalQuality = 0.10,
                        isBehavioralEvidence = true
                    )
                }
            }
        }
    }

    suspend fun logWatchProgress(
        item: MovieTitleSummary,
        positionMs: Long,
        durationMs: Long,
        isCompleted: Boolean,
        isRewatch: Boolean = false,
        isManual: Boolean = false
    ) {
        if (!isActive()) return
        val fraction = if (durationMs > 0L) positionMs.toDouble() / durationMs.toDouble() else 0.0
        if (!isCompleted && !isRewatch && !isManual && fraction < 0.15) return
        runCatching {
            withContext(Dispatchers.IO) {
                val ts = nowMs()
                val facets = TopicExtractor.extractMediaFacets(item)
                val topicsCsv = facets.joinToString(",") { it.facetKey }
                val signalType = when {
                    isRewatch -> SIGNAL_REWATCH
                    isManual -> SIGNAL_MANUAL_WATCHED
                    isCompleted -> SIGNAL_WATCH_COMPLETED
                    else -> SIGNAL_WATCH_PROGRESS
                }
                val strength = when (signalType) {
                    SIGNAL_REWATCH -> 1.0
                    SIGNAL_WATCH_COMPLETED -> 0.95
                    SIGNAL_MANUAL_WATCHED -> 0.80
                    else -> 0.50
                }
                val quality = when (signalType) {
                    SIGNAL_REWATCH -> 0.42
                    SIGNAL_WATCH_COMPLETED -> 0.36
                    SIGNAL_MANUAL_WATCHED -> 0.28
                    else -> 0.14
                }
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
                        signalType == SIGNAL_REWATCH && facet.facetKey.startsWith("genre:") -> 0.26
                        signalType == SIGNAL_REWATCH -> 0.11
                        signalType == SIGNAL_WATCH_COMPLETED && facet.facetKey.startsWith("genre:") -> 0.22
                        signalType == SIGNAL_WATCH_COMPLETED -> 0.09
                        signalType == SIGNAL_MANUAL_WATCHED && facet.facetKey.startsWith("genre:") -> 0.16
                        signalType == SIGNAL_MANUAL_WATCHED -> 0.07
                        facet.facetKey.startsWith("genre:") -> 0.09
                        else -> 0.04
                    }
                    val provenanceLabel = when (signalType) {
                        SIGNAL_REWATCH -> "Rewatched ${facet.displayLabel}"
                        SIGNAL_MANUAL_WATCHED -> "Marked ${facet.displayLabel} watched"
                        SIGNAL_WATCH_COMPLETED -> "Completed watching ${facet.displayLabel}"
                        else -> "Watching ${facet.displayLabel}"
                    }
                    userUnderstandingRepository.reinforceFacet(
                        candidate = facet,
                        delta = delta,
                        provenance = provenanceLabel,
                        evidenceSummary = "Viewing history in ${facet.displayLabel}",
                        facetType = TasteFacetType.ENDURING_INTEREST,
                        timestampMs = ts,
                        signalType = signalType,
                        signalQuality = quality,
                        isBehavioralEvidence = true
                    )
                }
            }
        }
    }

    suspend fun logContinueWatchingRemoved(item: MovieTitleSummary) {
        if (!isActive()) return
        runCatching {
            withContext(Dispatchers.IO) {
                val ts = nowMs()
                val facets = TopicExtractor.extractMediaFacets(item)
                intelligenceDao.insertSignal(
                    IntelligenceSignalEntity(
                        signalType = SIGNAL_CONTINUE_WATCHING_REMOVE,
                        surfaceScope = IntelligenceSurfaceScope.MOVIES.name,
                        targetKey = item.stableMediaKey,
                        topicsCsv = facets.joinToString(",") { it.facetKey },
                        isMediaCue = true,
                        strength = 0.0,
                        createdAtMs = ts
                    )
                )
                // Removing from Continue Watching is NOT a genre dislike.
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
                        strength = -0.35,
                        createdAtMs = ts
                    )
                )
                // Hiding one title suppresses that title; do NOT penalize every associated genre.
            }
        }
    }

    suspend fun logSeriesMuted(item: MovieTitleSummary, muted: Boolean) {
        if (!isActive()) return
        runCatching {
            withContext(Dispatchers.IO) {
                val ts = nowMs()
                val facets = TopicExtractor.extractMediaFacets(item)
                intelligenceDao.insertSignal(
                    IntelligenceSignalEntity(
                        signalType = if (muted) SIGNAL_SERIES_MUTE else SIGNAL_SERIES_UNMUTE,
                        surfaceScope = IntelligenceSurfaceScope.MOVIES.name,
                        targetKey = item.stableMediaKey,
                        topicsCsv = facets.joinToString(",") { it.facetKey },
                        isMediaCue = true,
                        strength = if (muted) -0.15 else 0.10,
                        createdAtMs = ts
                    )
                )
                // Muting series alerts respects title-level mute semantics; never treat as broad genre dislike.
            }
        }
    }

    suspend fun logMediaAddedToCollection(item: MovieTitleSummary, @Suppress("UNUSED_PARAMETER") collectionName: String? = null) {
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
                        strength = 0.80,
                        createdAtMs = ts
                    )
                )
                for (facet in facets) {
                    val delta = if (facet.facetKey.startsWith("genre:")) 0.17 else 0.07
                    userUnderstandingRepository.reinforceFacet(
                        candidate = facet,
                        delta = delta,
                        provenance = "Saved to collection",
                        evidenceSummary = "Collection interest (${facet.displayLabel})",
                        facetType = TasteFacetType.ENDURING_INTEREST,
                        timestampMs = ts,
                        signalType = SIGNAL_COLLECTION_ADD,
                        signalQuality = 0.25,
                        isBehavioralEvidence = true
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
                            "Explicit media link cue"
                        } else {
                            "Saved ${facet.displayLabel} link"
                        },
                        evidenceSummary = if (isMovieCrossSignal) {
                            "Explicit media link cue for ${facet.displayLabel}"
                        } else {
                            "Saved reading interest in ${facet.displayLabel}"
                        },
                        facetType = TasteFacetType.ENDURING_INTEREST,
                        timestampMs = ts,
                        signalType = SIGNAL_LINK_SAVE,
                        signalQuality = 0.20,
                        isBehavioralEvidence = true
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
                        provenance = "Read ${topic.displayLabel} link",
                        evidenceSummary = "Read saved ${topic.displayLabel} item",
                        facetType = TasteFacetType.ENDURING_INTEREST,
                        timestampMs = ts,
                        signalType = SIGNAL_LINK_READ,
                        signalQuality = 0.25,
                        isBehavioralEvidence = true
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
                        timestampMs = ts,
                        signalType = SIGNAL_COLLECTION_ADD,
                        signalQuality = 0.22,
                        isBehavioralEvidence = true
                    )
                }
            }
        }
    }

    /**
     * Records an explicitly submitted search. Must NEVER be called on typing/debounced keystrokes.
     * Stores only sanitized derived facets and a hashed target key (never plaintext query text).
     * Repeated searches alone never promote SHORT_TERM_INTENT to ENDURING_INTEREST.
     */
    suspend fun logSearch(surfaceScope: IntelligenceSurfaceScope, query: String) {
        if (!isActive()) return
        val clean = query.trim()
        if (clean.length < 2) return
        if (TopicExtractor.containsSensitiveText(clean)) return
        runCatching {
            withContext(Dispatchers.IO) {
                val ts = nowMs()
                val combined = when (surfaceScope) {
                    IntelligenceSurfaceScope.MOVIES ->
                        TopicExtractor.extractMoviesSearchFacets(clean)
                    IntelligenceSurfaceScope.LIBRARY, IntelligenceSurfaceScope.ALL ->
                        TopicExtractor.extractLibraryTopics(query = clean)
                            .map { it.copy(surfaceScope = IntelligenceSurfaceScope.LIBRARY, isMediaCue = false) }
                }.distinctBy { it.facetKey }

                val safeTarget = TopicExtractor.safeSearchTargetKey(surfaceScope, clean, combined)
                val isMediaSearch = surfaceScope == IntelligenceSurfaceScope.MOVIES && combined.isNotEmpty()
                intelligenceDao.insertSignal(
                    IntelligenceSignalEntity(
                        signalType = SIGNAL_SEARCH,
                        surfaceScope = surfaceScope.name,
                        targetKey = safeTarget,
                        topicsCsv = combined.joinToString(",") { it.facetKey },
                        isMediaCue = isMediaSearch,
                        strength = 0.40,
                        createdAtMs = ts
                    )
                )
                for (facet in combined) {
                    userUnderstandingRepository.reinforceFacet(
                        candidate = facet,
                        delta = 0.11,
                        provenance = "Recent search",
                        evidenceSummary = "Submitted search for ${facet.displayLabel}",
                        facetType = TasteFacetType.SHORT_TERM_INTENT,
                        timestampMs = ts,
                        signalType = SIGNAL_SEARCH,
                        signalQuality = 0.08,
                        isBehavioralEvidence = false
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
                        strength = -0.15,
                        createdAtMs = nowMs()
                    )
                )
                // Notification dismissal must NEVER become a taste dislike.
            }
        }
    }

    suspend fun logResurfaceInteraction(link: SavedLink, opened: Boolean) {
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
                intelligenceDao.insertSignal(
                    IntelligenceSignalEntity(
                        signalType = if (opened) SIGNAL_RESURFACE_OPENED else SIGNAL_RESURFACE_DISMISSED,
                        surfaceScope = IntelligenceSurfaceScope.LIBRARY.name,
                        targetKey = "link:${link.id}",
                        topicsCsv = topics.joinToString(",") { it.facetKey },
                        isMediaCue = false,
                        strength = if (opened) 0.65 else -0.15,
                        createdAtMs = ts
                    )
                )
                if (opened) {
                    for (topic in topics) {
                        userUnderstandingRepository.reinforceFacet(
                            candidate = topic,
                            delta = 0.10,
                            provenance = "Re-engaged with ${topic.displayLabel} link",
                            evidenceSummary = "Re-engaged with resurfaced ${topic.displayLabel} link",
                            facetType = TasteFacetType.ENDURING_INTEREST,
                            timestampMs = ts,
                            signalType = SIGNAL_RESURFACE_OPENED,
                            signalQuality = 0.14,
                            isBehavioralEvidence = true
                        )
                    }
                }
                // Dismissing a Pick Back Up suggestion must NOT become a topic dislike.
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

    companion object {
        const val SIGNAL_TITLE_LIKE = "TITLE_LIKE"
        const val SIGNAL_TITLE_DISLIKE = "TITLE_DISLIKE"
        const val SIGNAL_DETAIL_OPEN = "DETAIL_OPEN"
        const val SIGNAL_PLAYBACK_START = "PLAYBACK_START"
        const val SIGNAL_EARLY_PLAYBACK_EXIT = "EARLY_PLAYBACK_EXIT"
        const val SIGNAL_WATCH_PROGRESS = "WATCH_PROGRESS"
        const val SIGNAL_WATCH_COMPLETED = "WATCH_COMPLETED"
        const val SIGNAL_REWATCH = "REWATCH"
        const val SIGNAL_MANUAL_WATCHED = "MANUAL_WATCHED"
        const val SIGNAL_WATCHLIST_ADD = "WATCHLIST_ADD"
        const val SIGNAL_WATCHLIST_REMOVE = "WATCHLIST_REMOVE"
        const val SIGNAL_REMINDER_SET = "REMINDER_SET"
        const val SIGNAL_REMINDER_REMOVE = "REMINDER_REMOVE"
        const val SIGNAL_MOVIE_DOWNLOAD = "MOVIE_DOWNLOAD"
        const val SIGNAL_MOVIE_DOWNLOAD_REMOVE = "MOVIE_DOWNLOAD_REMOVE"
        const val SIGNAL_TITLE_SHARE = "TITLE_SHARE"
        const val SIGNAL_CONTINUE_WATCHING_REMOVE = "CONTINUE_WATCHING_REMOVE"
        const val SIGNAL_MOVIE_HIDE = "MOVIE_HIDE"
        const val SIGNAL_SERIES_MUTE = "SERIES_MUTE"
        const val SIGNAL_SERIES_UNMUTE = "SERIES_UNMUTE"
        const val SIGNAL_LINK_SAVE = "LINK_SAVE"
        const val SIGNAL_LINK_READ = "LINK_READ"
        const val SIGNAL_COLLECTION_ADD = "COLLECTION_ADD"
        const val SIGNAL_SEARCH = "SEARCH_QUERY"
        const val SIGNAL_NOTIFICATION_OPENED = "NOTIFICATION_OPENED"
        const val SIGNAL_NOTIFICATION_DISMISSED = "NOTIFICATION_DISMISSED"
        const val SIGNAL_RESURFACE_OPENED = "RESURFACE_OPENED"
        const val SIGNAL_RESURFACE_DISMISSED = "RESURFACE_DISMISSED"
    }
}
