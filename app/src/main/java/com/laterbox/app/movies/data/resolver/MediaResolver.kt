package com.laterbox.app.movies.data.resolver

import com.laterbox.app.movies.data.local.dao.MoviesDao
import com.laterbox.app.movies.data.local.entity.CanonicalMediaEntity
import com.laterbox.app.movies.data.local.entity.MediaProviderLinkEntity
import com.laterbox.app.movies.data.provider.InternetArchiveMoviesProvider
import com.laterbox.app.movies.data.provider.MovieBoxProvider
import com.laterbox.app.movies.data.provider.TvMazeSeriesProvider
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.ProviderLinkRecord
import java.util.UUID

/**
 * Resolves and persists canonical media identities across MovieBox, Internet Archive, and TVMaze,
 * enforcing T1/T2 matching rules and rejecting title-only merges (T3).
 */
class MediaResolver(
    private val moviesDao: MoviesDao,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun resolveOrRegister(
        summary: MovieTitleSummary,
        seasonCount: Int? = null
    ): MovieTitleSummary {
        if (summary.id.isBlank() || summary.providerId.isBlank()) return summary
        val candidate = MatchCandidate.fromSummary(summary, seasonCount)
        val canonicalId = resolveOrRegisterCandidate(candidate)
        return summary.copy(canonicalId = canonicalId)
    }

    suspend fun resolveOrRegisterCandidate(candidate: MatchCandidate): String {
        val currentMs = nowMs()
        val existingLink = moviesDao.getProviderLink(candidate.providerId, candidate.titleId)
        if (existingLink != null) {
            val canonical = moviesDao.getCanonicalById(existingLink.canonicalId)
            if (canonical != null) {
                val mergedImdb = canonical.imdbId ?: MediaMatcher.normalizeImdbId(candidate.imdbId)
                val mergedTvMaze = canonical.tvmazeId ?: MediaMatcher.normalizeTvMazeId(candidate.tvmazeId)
                val mergedYear = canonical.releaseYear ?: candidate.releaseYear
                if (mergedImdb != canonical.imdbId ||
                    mergedTvMaze != canonical.tvmazeId ||
                    mergedYear != canonical.releaseYear
                ) {
                    moviesDao.upsertCanonicalMedia(
                        canonical.copy(
                            imdbId = mergedImdb,
                            tvmazeId = mergedTvMaze,
                            releaseYear = mergedYear,
                            updatedAt = currentMs
                        )
                    )
                }
                return canonical.canonicalId
            }
        }

        val normImdb = MediaMatcher.normalizeImdbId(candidate.imdbId)
        val normTvMaze = MediaMatcher.normalizeTvMazeId(candidate.tvmazeId)
        val normTitle = MediaMatcher.normalizeTitle(candidate.title)

        // Check T1 by external IDs first, then T2 by kind + normTitle + year + corroboration
        val pool = linkedSetOf<CanonicalMediaEntity>()
        if (normImdb != null) {
            moviesDao.findCanonicalByImdbId(normImdb)?.let { pool.add(it) }
        }
        if (normTvMaze != null) {
            moviesDao.findCanonicalByTvMazeId(normTvMaze)?.let { pool.add(it) }
        }
        if (normTitle.isNotBlank()) {
            pool.addAll(moviesDao.findCanonicalByKindAndNormTitle(candidate.kind.wireValue, normTitle))
        }

        var matchedCanonical: CanonicalMediaEntity? = null
        var matchedConfidence: MatchConfidence = MatchConfidence.T3

        for (existing in pool) {
            val existingCandidate = MatchCandidate(
                canonicalId = existing.canonicalId,
                providerId = existing.canonicalId,
                titleId = existing.canonicalId,
                kind = MediaKind.fromWire(existing.kind),
                title = existing.title,
                releaseYear = existing.releaseYear,
                imdbId = existing.imdbId,
                tvmazeId = existing.tvmazeId
            )
            val evaluation = MediaMatcher.evaluate(candidate, existingCandidate)
            if (evaluation.isMatch) {
                matchedCanonical = existing
                matchedConfidence = evaluation.confidence
                break
            }
        }

        if (matchedCanonical != null) {
            moviesDao.upsertCanonicalMedia(
                matchedCanonical.copy(
                    imdbId = matchedCanonical.imdbId ?: normImdb,
                    tvmazeId = matchedCanonical.tvmazeId ?: normTvMaze,
                    releaseYear = matchedCanonical.releaseYear ?: candidate.releaseYear,
                    updatedAt = currentMs
                )
            )
            moviesDao.upsertProviderLink(
                MediaProviderLinkEntity(
                    canonicalId = matchedCanonical.canonicalId,
                    providerId = candidate.providerId,
                    providerTitleId = candidate.titleId,
                    confidence = matchedConfidence.wireValue,
                    updatedAt = currentMs
                )
            )
            return matchedCanonical.canonicalId
        }

        val newCanonicalId = candidate.canonicalId?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        moviesDao.upsertCanonicalMedia(
            CanonicalMediaEntity(
                canonicalId = newCanonicalId,
                kind = candidate.kind.wireValue,
                title = candidate.title,
                normTitle = normTitle.ifBlank { candidate.title.lowercase() },
                releaseYear = candidate.releaseYear,
                imdbId = normImdb,
                tvmazeId = normTvMaze,
                updatedAt = currentMs
            )
        )
        moviesDao.upsertProviderLink(
            MediaProviderLinkEntity(
                canonicalId = newCanonicalId,
                providerId = candidate.providerId,
                providerTitleId = candidate.titleId,
                confidence = MatchConfidence.T1.wireValue,
                updatedAt = currentMs
            )
        )
        return newCanonicalId
    }

    suspend fun resolveCanonicalIdForProvider(providerId: String, titleId: String): String? {
        if (providerId == CANONICAL_PROVIDER_ID) {
            return moviesDao.getCanonicalById(titleId)?.canonicalId ?: titleId
        }
        return moviesDao.getProviderLink(providerId, titleId)?.canonicalId
    }

    /**
     * Resolves a target `(providerId, titleId)` given either a concrete provider pair or
     * `providerId == "canonical"` with `titleId == canonicalId`.
     */
    suspend fun resolveConcreteTarget(
        providerId: String,
        titleId: String,
        preferPlayable: Boolean = false
    ): Pair<String, String> {
        if (providerId != CANONICAL_PROVIDER_ID) {
            if (preferPlayable) {
                val canonicalId = moviesDao.getProviderLink(providerId, titleId)?.canonicalId
                if (canonicalId != null && providerId == TvMazeSeriesProvider.PROVIDER_ID) {
                    val links = moviesDao.getProviderLinksForCanonical(canonicalId)
                    val preferred = pickPreferredLink(links, preferPlayable = true)
                    if (preferred != null) {
                        return preferred.providerId to preferred.providerTitleId
                    }
                }
            }
            return providerId to titleId
        }
        val links = moviesDao.getProviderLinksForCanonical(titleId)
        val preferred = pickPreferredLink(links, preferPlayable = preferPlayable)
        return if (preferred != null) {
            preferred.providerId to preferred.providerTitleId
        } else {
            providerId to titleId
        }
    }

    suspend fun getLinksForTitle(providerId: String, titleId: String): List<ProviderLinkRecord> {
        val canonicalId = resolveCanonicalIdForProvider(providerId, titleId) ?: return emptyList()
        return moviesDao.getProviderLinksForCanonical(canonicalId).map {
            ProviderLinkRecord(
                canonicalId = it.canonicalId,
                providerId = it.providerId,
                providerTitleId = it.providerTitleId,
                confidence = it.confidence,
                updatedAt = it.updatedAt
            )
        }
    }

    /**
     * Unlinks a specific provider title from its shared canonical identity and assigns it
     * an isolated canonical entry so future lookups keep it separate.
     */
    suspend fun unlinkProviderTitle(providerId: String, titleId: String): String? {
        val currentMs = nowMs()
        val existingLink = moviesDao.getProviderLink(providerId, titleId) ?: return null
        val oldCanonical = moviesDao.getCanonicalById(existingLink.canonicalId)
        moviesDao.deleteProviderLink(providerId, titleId)

        if (moviesDao.countProviderLinksForCanonical(existingLink.canonicalId) == 0) {
            moviesDao.deleteCanonicalMedia(existingLink.canonicalId)
        }

        val isolatedId = UUID.randomUUID().toString()
        moviesDao.upsertCanonicalMedia(
            CanonicalMediaEntity(
                canonicalId = isolatedId,
                kind = oldCanonical?.kind ?: MediaKind.MOVIE.wireValue,
                title = oldCanonical?.title ?: "$providerId:$titleId",
                normTitle = oldCanonical?.normTitle ?: titleId.lowercase(),
                releaseYear = oldCanonical?.releaseYear,
                imdbId = null,
                tvmazeId = if (providerId == TvMazeSeriesProvider.PROVIDER_ID) titleId else null,
                updatedAt = currentMs
            )
        )
        moviesDao.upsertProviderLink(
            MediaProviderLinkEntity(
                canonicalId = isolatedId,
                providerId = providerId,
                providerTitleId = titleId,
                confidence = MatchConfidence.T3.wireValue,
                updatedAt = currentMs
            )
        )
        moviesDao.updateWatchlistCanonicalId("$providerId:$titleId", isolatedId)
        return isolatedId
    }

    suspend fun backfillWatchlistAndHistoryIfNeeded() {
        val pendingWatchlist = moviesDao.getWatchlistItemsWithoutCanonicalId()
        for (item in pendingWatchlist) {
            val cid = resolveOrRegisterCandidate(
                MatchCandidate(
                    providerId = item.providerId,
                    titleId = item.titleId,
                    kind = MediaKind.fromWire(item.kind),
                    title = item.title,
                    releaseYear = item.releaseYear,
                    tvmazeId = if (item.providerId == TvMazeSeriesProvider.PROVIDER_ID) item.titleId else null
                )
            )
            moviesDao.updateWatchlistCanonicalId(item.compositeId, cid)
        }

        val pendingHistory = moviesDao.getWatchHistoryWithoutCanonicalId()
        for (entry in pendingHistory) {
            val cid = resolveOrRegisterCandidate(
                MatchCandidate(
                    providerId = entry.providerId,
                    titleId = entry.titleId,
                    kind = MediaKind.fromWire(entry.kind),
                    title = entry.title,
                    tvmazeId = if (entry.providerId == TvMazeSeriesProvider.PROVIDER_ID) entry.titleId else null
                )
            )
            moviesDao.updateWatchHistoryCanonicalId(entry.historyKey, cid)
        }
    }

    private fun pickPreferredLink(
        links: List<MediaProviderLinkEntity>,
        preferPlayable: Boolean
    ): MediaProviderLinkEntity? {
        if (links.isEmpty()) return null
        val priority = if (preferPlayable) {
            listOf(
                MovieBoxProvider.PROVIDER_ID,
                InternetArchiveMoviesProvider.PROVIDER_ID,
                TvMazeSeriesProvider.PROVIDER_ID
            )
        } else {
            listOf(
                MovieBoxProvider.PROVIDER_ID,
                TvMazeSeriesProvider.PROVIDER_ID,
                InternetArchiveMoviesProvider.PROVIDER_ID
            )
        }
        return links.minByOrNull { link ->
            val idx = priority.indexOf(link.providerId)
            if (idx >= 0) idx else priority.size
        }
    }

    companion object {
        const val CANONICAL_PROVIDER_ID = "canonical"
    }
}
