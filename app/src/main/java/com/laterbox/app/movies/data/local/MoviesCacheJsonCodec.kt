package com.laterbox.app.movies.data.local

import com.laterbox.app.movies.domain.model.AudioDubInfo
import com.laterbox.app.movies.domain.model.BrowseSection
import com.laterbox.app.movies.domain.model.CastMember
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.EpisodeItem
import com.laterbox.app.movies.domain.model.MediaDetails
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesHomeFeed
import com.laterbox.app.movies.domain.model.SeasonSummary
import com.laterbox.app.movies.domain.model.SectionStyle
import com.laterbox.app.movies.domain.model.SubtitleTrack
import org.json.JSONArray
import org.json.JSONObject

object MoviesCacheJsonCodec {

    fun encodeHomeFeed(feed: MoviesHomeFeed): String = JSONObject().apply {
        put("featuredBanners", JSONArray().apply { feed.featuredBanners.forEach { put(encodeSummary(it)) } })
        put("sections", JSONArray().apply { feed.sections.forEach { put(encodeSection(it)) } })
        put("genres", JSONArray(feed.genres))
        put("activeProviderNotice", feed.activeProviderNotice ?: JSONObject.NULL)
        put("containsSampleFixtures", feed.containsSampleFixtures)
    }.toString()

    fun decodeHomeFeed(rawJson: String, isOfflineCached: Boolean = true): MoviesHomeFeed? = runCatching {
        val obj = JSONObject(rawJson)
        val banners = obj.optJSONArray("featuredBanners").toSummaryList()
        val sectionsArr = obj.optJSONArray("sections")
        val sections = buildList {
            if (sectionsArr != null) {
                for (i in 0 until sectionsArr.length()) {
                    val sec = sectionsArr.optJSONObject(i) ?: continue
                    add(
                        BrowseSection(
                            id = sec.optString("id"),
                            title = sec.optString("title"),
                            subtitle = sec.optNullableString("subtitle"),
                            style = SectionStyle.entries.firstOrNull { it.name == sec.optString("style") } ?: SectionStyle.RAIL,
                            items = sec.optJSONArray("items").toSummaryList()
                        )
                    )
                }
            }
        }
        MoviesHomeFeed(
            featuredBanners = banners,
            sections = sections,
            genres = obj.optJSONArray("genres").toStringList(),
            activeProviderNotice = obj.optNullableString("activeProviderNotice"),
            isOfflineCached = isOfflineCached,
            containsSampleFixtures = obj.optBoolean("containsSampleFixtures", false)
        )
    }.getOrNull()

    fun encodeDetails(details: MediaDetails): String = JSONObject().apply {
        put("summary", encodeSummary(details.summary))
        put("fullDescription", details.fullDescription)
        put("directorOrCreator", details.directorOrCreator ?: JSONObject.NULL)
        put("networkOrStudio", details.networkOrStudio ?: JSONObject.NULL)
        put("externalInfoUrl", details.externalInfoUrl ?: JSONObject.NULL)
        put("cast", JSONArray().apply {
            details.cast.forEach { c ->
                put(JSONObject().apply {
                    put("name", c.name)
                    put("role", c.role ?: JSONObject.NULL)
                    put("imageUrl", c.imageUrl ?: JSONObject.NULL)
                    put("personId", c.personId ?: JSONObject.NULL)
                    put("providerId", c.providerId ?: JSONObject.NULL)
                })
            }
        })
        put("seasons", JSONArray().apply {
            details.seasons.forEach { s ->
                put(JSONObject().apply {
                    put("seasonNumber", s.seasonNumber)
                    put("title", s.title)
                    put("episodeCount", s.episodeCount)
                    put("imageUrl", s.imageUrl ?: JSONObject.NULL)
                    put("availableEpisodes", JSONArray(s.availableEpisodes))
                    put("availableResolutions", JSONArray(s.availableResolutions))
                })
            }
        })
        put("audioDubs", JSONArray().apply {
            details.audioDubs.forEach { d ->
                put(JSONObject().apply {
                    put("language", d.language)
                    put("label", d.label)
                    put("referenceId", d.referenceId ?: JSONObject.NULL)
                })
            }
        })
        put("availableSubtitles", JSONArray().apply {
            details.availableSubtitles.forEach { sub ->
                put(JSONObject().apply {
                    put("id", sub.id)
                    put("language", sub.language)
                    put("languageCode", sub.languageCode)
                    put("label", sub.label)
                    put("url", sub.url)
                    put("format", sub.format)
                })
            }
        })
        put("relatedTitles", JSONArray().apply {
            details.relatedTitles.forEach { put(encodeSummary(it)) }
        })
    }.toString()

    fun decodeDetails(rawJson: String): MediaDetails? = runCatching {
        val obj = JSONObject(rawJson)
        val summary = decodeSummary(obj.getJSONObject("summary"))
        val cast = buildList {
            val arr = obj.optJSONArray("cast")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        CastMember(
                            name = o.optString("name"),
                            role = o.optNullableString("role"),
                            imageUrl = o.optNullableString("imageUrl"),
                            personId = o.optNullableString("personId"),
                            providerId = o.optNullableString("providerId")
                        )
                    )
                }
            }
        }
        val seasons = buildList {
            val arr = obj.optJSONArray("seasons")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val epArr = o.optJSONArray("availableEpisodes")
                    val availableEps = buildList {
                        if (epArr != null) {
                            for (j in 0 until epArr.length()) {
                                val epNum = epArr.optInt(j, 0)
                                if (epNum > 0) add(epNum)
                            }
                        }
                    }
                    val resArr = o.optJSONArray("availableResolutions")
                    val availableRes = buildList {
                        if (resArr != null) {
                            for (j in 0 until resArr.length()) {
                                val resVal = resArr.optInt(j, 0)
                                if (resVal > 0) add(resVal)
                            }
                        }
                    }
                    add(
                        SeasonSummary(
                            seasonNumber = o.optInt("seasonNumber", 1),
                            title = o.optString("title"),
                            episodeCount = o.optInt("episodeCount", 0),
                            imageUrl = o.optNullableString("imageUrl"),
                            availableEpisodes = availableEps,
                            availableResolutions = availableRes
                        )
                    )
                }
            }
        }
        val dubs = buildList {
            val arr = obj.optJSONArray("audioDubs")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(AudioDubInfo(o.optString("language"), o.optString("label"), o.optNullableString("referenceId")))
                }
            }
        }
        val subs = buildList {
            val arr = obj.optJSONArray("availableSubtitles")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(SubtitleTrack(o.optString("id"), o.optString("language"), o.optString("languageCode"), o.optString("label"), o.optString("url"), o.optString("format", "srt")))
                }
            }
        }
        MediaDetails(
            summary = summary,
            fullDescription = obj.optString("fullDescription"),
            directorOrCreator = obj.optNullableString("directorOrCreator"),
            networkOrStudio = obj.optNullableString("networkOrStudio"),
            cast = cast,
            seasons = seasons,
            audioDubs = dubs,
            availableSubtitles = subs,
            relatedTitles = obj.optJSONArray("relatedTitles").toSummaryList(),
            externalInfoUrl = obj.optNullableString("externalInfoUrl")
        )
    }.getOrNull()

    fun encodeEpisodes(episodes: List<EpisodeItem>): String = JSONArray().apply {
        episodes.forEach { ep ->
            put(JSONObject().apply {
                put("id", ep.id)
                put("seasonNumber", ep.seasonNumber)
                put("episodeNumber", ep.episodeNumber)
                put("title", ep.title)
                put("overview", ep.overview ?: JSONObject.NULL)
                put("runtimeMinutes", ep.runtimeMinutes ?: JSONObject.NULL)
                put("airDate", ep.airDate ?: JSONObject.NULL)
                put("stillUrl", ep.stillUrl ?: JSONObject.NULL)
                if (ep.sizesMbByQuality.isNotEmpty()) {
                    put("sizesMbByQuality", JSONObject().apply {
                        ep.sizesMbByQuality.forEach { (q, size) ->
                            put(q.toString(), size)
                        }
                    })
                }
            })
        }
    }.toString()

    fun decodeEpisodes(rawJson: String): List<EpisodeItem>? = runCatching {
        val arr = JSONArray(rawJson)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sizesObj = o.optJSONObject("sizesMbByQuality")
                val sizesMap = buildMap<Int, Double> {
                    if (sizesObj != null) {
                        val keys = sizesObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val q = k.toIntOrNull() ?: continue
                            val v = sizesObj.optDouble(k, 0.0)
                            if (q > 0 && v > 0.0) put(q, v)
                        }
                    }
                }
                add(
                    EpisodeItem(
                        id = o.optString("id"),
                        seasonNumber = o.optInt("seasonNumber", 1),
                        episodeNumber = o.optInt("episodeNumber", i + 1),
                        title = o.optString("title"),
                        overview = o.optNullableString("overview"),
                        runtimeMinutes = if (o.isNull("runtimeMinutes")) null else o.optInt("runtimeMinutes"),
                        airDate = o.optNullableString("airDate"),
                        stillUrl = o.optNullableString("stillUrl"),
                        sizesMbByQuality = sizesMap
                    )
                )
            }
        }
    }.getOrNull()

    private fun encodeSection(sec: BrowseSection): JSONObject = JSONObject().apply {
        put("id", sec.id)
        put("title", sec.title)
        put("subtitle", sec.subtitle ?: JSONObject.NULL)
        put("style", sec.style.name)
        put("items", JSONArray().apply { sec.items.forEach { put(encodeSummary(it)) } })
    }

    private fun encodeSummary(item: MovieTitleSummary): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("providerId", item.providerId)
        put("kind", item.kind.wireValue)
        put("title", item.title)
        put("releaseYear", item.releaseYear ?: JSONObject.NULL)
        put("rating", item.rating ?: JSONObject.NULL)
        put("durationMinutes", item.durationMinutes ?: JSONObject.NULL)
        put("posterUrl", item.posterUrl ?: JSONObject.NULL)
        put("backdropUrl", item.backdropUrl ?: JSONObject.NULL)
        put("genres", JSONArray(item.genres))
        put("summary", item.summary ?: JSONObject.NULL)
        put("language", item.language ?: JSONObject.NULL)
        put("country", item.country ?: JSONObject.NULL)
        put("detailPath", item.detailPath ?: JSONObject.NULL)
        put("trailerUrl", item.trailerUrl ?: JSONObject.NULL)
        if (item.trailerHeaders.isNotEmpty()) {
            put("trailerHeaders", JSONObject().apply {
                item.trailerHeaders.forEach { (k, v) -> put(k, v) }
            })
        }
        put("sourceBadge", item.sourceBadge.name)
        put("isPlayableHint", item.isPlayableHint)
        put("imdbId", item.imdbId ?: JSONObject.NULL)
        put("tvmazeId", item.tvmazeId ?: JSONObject.NULL)
        put("canonicalId", item.canonicalId ?: JSONObject.NULL)
    }

    private fun decodeSummary(o: JSONObject): MovieTitleSummary {
        val trailerHeadersObj = o.optJSONObject("trailerHeaders")
        val trailerHeaders = buildMap<String, String> {
            if (trailerHeadersObj != null) {
                val keys = trailerHeadersObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    trailerHeadersObj.optNullableString(key)?.let { put(key, it) }
                }
            }
        }
        return MovieTitleSummary(
            id = o.optString("id"),
            providerId = o.optString("providerId"),
            kind = MediaKind.fromWire(o.optString("kind")),
            title = o.optString("title"),
            releaseYear = if (o.isNull("releaseYear")) null else o.optInt("releaseYear"),
            rating = if (o.isNull("rating")) null else o.optDouble("rating"),
            durationMinutes = if (o.isNull("durationMinutes")) null else o.optInt("durationMinutes"),
            posterUrl = o.optNullableString("posterUrl"),
            backdropUrl = o.optNullableString("backdropUrl"),
            genres = o.optJSONArray("genres").toStringList(),
            summary = o.optNullableString("summary"),
            language = o.optNullableString("language"),
            country = o.optNullableString("country"),
            detailPath = o.optNullableString("detailPath"),
            trailerUrl = o.optNullableString("trailerUrl"),
            trailerHeaders = trailerHeaders,
            sourceBadge = DataSourceBadge.fromName(o.optString("sourceBadge")),
            isPlayableHint = o.optBoolean("isPlayableHint", false),
            imdbId = o.optNullableString("imdbId"),
            tvmazeId = o.optNullableString("tvmazeId"),
            canonicalId = o.optNullableString("canonicalId")
        )
    }

    private fun JSONArray?.toSummaryList(): List<MovieTitleSummary> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                optJSONObject(i)?.let { add(decodeSummary(it)) }
            }
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
