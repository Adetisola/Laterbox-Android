package com.laterbox.app.movies.data.resolver

import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

enum class MatchConfidence(val wireValue: String) {
    T1("T1"),
    T2("T2"),
    T3("T3");

    companion object {
        fun fromWire(value: String?): MatchConfidence =
            entries.firstOrNull { it.wireValue.equals(value?.trim(), ignoreCase = true) } ?: T3
    }
}

data class MatchCandidate(
    val canonicalId: String? = null,
    val providerId: String,
    val titleId: String,
    val kind: MediaKind,
    val title: String,
    val releaseYear: Int? = null,
    val durationMinutes: Int? = null,
    val seasonCount: Int? = null,
    val imdbId: String? = null,
    val tvmazeId: String? = null
) {
    companion object {
        fun fromSummary(
            summary: MovieTitleSummary,
            seasonCount: Int? = null
        ): MatchCandidate = MatchCandidate(
            canonicalId = summary.canonicalId,
            providerId = summary.providerId,
            titleId = summary.id,
            kind = summary.kind,
            title = summary.title,
            releaseYear = summary.releaseYear,
            durationMinutes = summary.durationMinutes,
            seasonCount = seasonCount,
            imdbId = summary.imdbId,
            tvmazeId = summary.tvmazeId ?: if (summary.providerId == "tvmaze") summary.id else null
        )
    }
}

data class MatchEvaluation(
    val isMatch: Boolean,
    val confidence: MatchConfidence,
    val reason: String
)

object MediaMatcher {

    fun normalizeTitle(rawTitle: String): String {
        val decomposed = Normalizer.normalize(rawTitle.trim(), Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.US)
        return decomposed
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    fun normalizeImdbId(raw: String?): String? {
        val clean = raw?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() && it != "null" }
            ?: return null
        return clean.takeIf { Regex("^tt\\d{5,}$").matches(it) }
    }

    fun normalizeTvMazeId(raw: String?): String? {
        val clean = raw?.trim()?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        return clean.takeIf { it.all(Char::isDigit) && it.toLongOrNull()?.let { v -> v > 0L } == true }
    }

    /**
     * Evaluates whether two [MatchCandidate] entries represent the same canonical title.
     * - T1: Exact shared external ID (IMDb ID or TVMaze ID).
     * - T2: Same [MediaKind], identical normalized title, release year within ±1,
     *       AND at least one corroborating field (runtime within ±10 min or equal season count > 0).
     * - T3: No merge (keep separate). Title-only or title+year-only matches are rejected.
     */
    fun evaluate(a: MatchCandidate, b: MatchCandidate): MatchEvaluation {
        if (a.providerId == b.providerId && a.titleId == b.titleId) {
            return MatchEvaluation(true, MatchConfidence.T1, "identical_provider_key")
        }

        val aImdb = normalizeImdbId(a.imdbId)
        val bImdb = normalizeImdbId(b.imdbId)
        if (aImdb != null && bImdb != null && aImdb == bImdb) {
            return MatchEvaluation(true, MatchConfidence.T1, "shared_imdb_id")
        }

        val aTvMaze = normalizeTvMazeId(a.tvmazeId)
        val bTvMaze = normalizeTvMazeId(b.tvmazeId)
        if (aTvMaze != null && bTvMaze != null && aTvMaze == bTvMaze) {
            return MatchEvaluation(true, MatchConfidence.T1, "shared_tvmaze_id")
        }

        if (a.kind != b.kind) {
            return MatchEvaluation(false, MatchConfidence.T3, "kind_mismatch")
        }

        val normA = normalizeTitle(a.title)
        val normB = normalizeTitle(b.title)
        if (normA.isBlank() || normA != normB) {
            return MatchEvaluation(false, MatchConfidence.T3, "title_mismatch")
        }

        val yearA = a.releaseYear
        val yearB = b.releaseYear
        if (yearA == null || yearB == null || abs(yearA - yearB) > 1) {
            return MatchEvaluation(false, MatchConfidence.T3, "year_missing_or_out_of_tolerance")
        }

        val runtimeCorroborated = a.durationMinutes != null &&
            b.durationMinutes != null &&
            a.durationMinutes > 0 &&
            b.durationMinutes > 0 &&
            abs(a.durationMinutes - b.durationMinutes) <= 10

        val seasonsCorroborated = a.seasonCount != null &&
            b.seasonCount != null &&
            a.seasonCount > 0 &&
            a.seasonCount == b.seasonCount

        if (runtimeCorroborated || seasonsCorroborated) {
            val field = if (runtimeCorroborated) "runtime" else "season_count"
            return MatchEvaluation(true, MatchConfidence.T2, "corroborated_$field")
        }

        return MatchEvaluation(false, MatchConfidence.T3, "missing_corroborating_field")
    }
}
