package com.laterbox.app.movies.data.download

import android.content.Context
import com.laterbox.app.movies.domain.model.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

enum class OfflineSubtitleStatus {
    COMPLETED,
    FAILED
}

data class OfflineSubtitleEntry(
    val trackId: String,
    val language: String,
    val languageCode: String,
    val label: String,
    val format: String,
    val sourceUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val fileName: String,
    val status: OfflineSubtitleStatus,
    val fileSizeBytes: Long = 0L,
    val errorMessage: String? = null
)

data class OfflineSubtitleManifest(
    val assetId: String,
    val sourceRef: String? = null,
    val providerId: String? = null,
    val titleId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val entries: List<OfflineSubtitleEntry> = emptyList(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

data class AssetSubtitleSummary(
    val assetId: String,
    val totalCount: Int,
    val completedCount: Int,
    val failedCount: Int
) {
    val hasSubtitles: Boolean get() = totalCount > 0
    val hasFailures: Boolean get() = failedCount > 0
    val isPartialSuccess: Boolean get() = completedCount > 0 && failedCount > 0

    fun formatBadgeText(): String = when {
        totalCount <= 0 -> ""
        failedCount == 0 -> "$completedCount ${if (completedCount == 1) "subtitle" else "subtitles"} offline"
        completedCount > 0 -> "$completedCount/$totalCount subtitles ($failedCount failed)"
        else -> "Subtitles failed ($failedCount)"
    }
}

class SubtitleDownloadManager(
    private val subtitlesRootDir: File,
    private val okHttpClient: OkHttpClient = OkHttpClient()
) {
    constructor(context: Context, okHttpClient: OkHttpClient = OkHttpClient()) : this(
        subtitlesRootDir = File(context.applicationContext.filesDir, "media/subtitles"),
        okHttpClient = okHttpClient
    )

    private val fileLock = Any()
    private val _summaries = MutableStateFlow<Map<String, AssetSubtitleSummary>>(emptyMap())
    val summaries: StateFlow<Map<String, AssetSubtitleSummary>> = _summaries.asStateFlow()

    init {
        refreshSummaries()
    }

    fun refreshSummaries() {
        val map = loadAllSummariesInternal()
        _summaries.value = map
    }

    suspend fun downloadSubtitlesForAsset(
        assetId: String,
        sourceRef: String?,
        providerId: String?,
        titleId: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        subtitles: List<SubtitleTrack>,
        fallbackHeaders: Map<String, String> = emptyMap()
    ): AssetSubtitleSummary = withContext(Dispatchers.IO) {
        val cleanAssetId = assetId.trim()
        val dedupedTracks = deduplicateTracks(subtitles)
        if (cleanAssetId.isEmpty() || dedupedTracks.isEmpty()) {
            val emptySummary = AssetSubtitleSummary(cleanAssetId, 0, 0, 0)
            return@withContext emptySummary
        }

        val assetDir = assetDirectory(cleanAssetId).apply { if (!exists()) mkdirs() }
        val existingEntriesByUrl = readManifest(cleanAssetId)?.entries?.associateBy { it.sourceUrl }.orEmpty()
        val semaphore = Semaphore(MAX_CONCURRENT_SUBTITLES)

        val entries = coroutineScope {
            dedupedTracks.mapIndexed { index, track ->
                async {
                    val format = normalizeFormat(track.format, track.url)
                    val safeName = buildSidecarFileName(index, track.languageCode, track.id, format)
                    val existing = existingEntriesByUrl[track.url]
                    if (existing != null && existing.status == OfflineSubtitleStatus.COMPLETED) {
                        val existingFile = File(assetDir, existing.fileName)
                        if (existingFile.exists() && existingFile.length() > 0L) {
                            return@async existing
                        }
                    }

                    val mergedHeaders = LinkedHashMap<String, String>().apply {
                        putAll(fallbackHeaders)
                        putAll(track.headers)
                    }
                    semaphore.withPermit {
                        downloadSingleSubtitleTrack(
                            assetDir = assetDir,
                            fileName = safeName,
                            track = track,
                            format = format,
                            headers = mergedHeaders
                        )
                    }
                }
            }.awaitAll()
        }

        val manifest = OfflineSubtitleManifest(
            assetId = cleanAssetId,
            sourceRef = sourceRef?.takeIf { it.isNotBlank() },
            providerId = providerId?.takeIf { it.isNotBlank() },
            titleId = titleId?.takeIf { it.isNotBlank() },
            seasonNumber = seasonNumber?.takeIf { it > 0 },
            episodeNumber = episodeNumber?.takeIf { it > 0 },
            entries = entries,
            updatedAtMs = System.currentTimeMillis()
        )
        writeManifest(manifest)
        refreshSummaries()
        manifest.toSummary()
    }

    suspend fun retryFailedSubtitles(assetId: String): AssetSubtitleSummary? = withContext(Dispatchers.IO) {
        val cleanAssetId = assetId.trim()
        if (cleanAssetId.isEmpty()) return@withContext null
        val manifest = readManifest(cleanAssetId) ?: return@withContext null
        val assetDir = assetDirectory(cleanAssetId).apply { if (!exists()) mkdirs() }
        val semaphore = Semaphore(MAX_CONCURRENT_SUBTITLES)

        val updatedEntries = coroutineScope {
            manifest.entries.map { entry ->
                val targetFile = File(assetDir, entry.fileName)
                val alreadyComplete = entry.status == OfflineSubtitleStatus.COMPLETED &&
                    targetFile.exists() &&
                    targetFile.length() > 0L
                if (alreadyComplete) {
                    async { entry }
                } else {
                    async {
                        semaphore.withPermit {
                            downloadSingleSubtitleTrack(
                                assetDir = assetDir,
                                fileName = entry.fileName,
                                track = SubtitleTrack(
                                    id = entry.trackId,
                                    language = entry.language,
                                    languageCode = entry.languageCode,
                                    label = entry.label,
                                    url = entry.sourceUrl,
                                    format = entry.format,
                                    headers = entry.headers
                                ),
                                format = normalizeFormat(entry.format, entry.sourceUrl),
                                headers = entry.headers
                            )
                        }
                    }
                }
            }.awaitAll()
        }

        val updatedManifest = manifest.copy(
            entries = updatedEntries,
            updatedAtMs = System.currentTimeMillis()
        )
        writeManifest(updatedManifest)
        refreshSummaries()
        updatedManifest.toSummary()
    }

    fun getOfflineSubtitleTracks(assetId: String): List<SubtitleTrack> {
        val cleanAssetId = assetId.trim()
        if (cleanAssetId.isEmpty()) return emptyList()
        val manifest = readManifest(cleanAssetId) ?: return emptyList()
        val assetDir = assetDirectory(cleanAssetId)
        return manifest.toCompletedSubtitleTracks(assetDir)
    }

    fun getOfflineSubtitleTracksForTitle(
        providerId: String,
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?,
        assetId: String? = null
    ): List<SubtitleTrack> {
        if (!assetId.isNullOrBlank()) {
            val direct = getOfflineSubtitleTracks(assetId)
            if (direct.isNotEmpty()) return direct
        }
        val targetSeason = seasonNumber?.takeIf { it > 0 }
        val targetEpisode = episodeNumber?.takeIf { it > 0 }
        val root = subtitlesRootDir
        if (!root.exists() || !root.isDirectory) return emptyList()

        val manifests = synchronized(fileLock) {
            root.listFiles()
                ?.filter { it.isDirectory }
                ?.mapNotNull { dir -> readManifestFile(File(dir, MANIFEST_FILE_NAME)) }
                .orEmpty()
        }

        val matched = manifests
            .filter { m ->
                matchesTitleEpisode(m, providerId, titleId, targetSeason, targetEpisode)
            }
            .sortedByDescending { it.updatedAtMs }

        for (manifest in manifestsSortedOrFirst(matched)) {
            val tracks = manifest.toCompletedSubtitleTracks(assetDirectory(manifest.assetId))
            if (tracks.isNotEmpty()) return tracks
        }
        return emptyList()
    }

    fun getSubtitleSummary(assetId: String): AssetSubtitleSummary? {
        val clean = assetId.trim().takeIf { it.isNotEmpty() } ?: return null
        return readManifest(clean)?.toSummary()
    }

    fun deleteSubtitlesForAsset(assetId: String): Boolean {
        val clean = assetId.trim().takeIf { it.isNotEmpty() } ?: return false
        val deleted = synchronized(fileLock) {
            val dir = assetDirectory(clean)
            if (dir.exists()) dir.deleteRecursively() else true
        }
        refreshSummaries()
        return deleted
    }

    private fun manifestsSortedOrFirst(list: List<OfflineSubtitleManifest>): List<OfflineSubtitleManifest> = list

    private fun matchesTitleEpisode(
        manifest: OfflineSubtitleManifest,
        providerId: String,
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): Boolean {
        val manifestProvider = manifest.providerId ?: manifest.sourceRef?.split("|")?.getOrNull(0)?.trim()
        val manifestTitle = manifest.titleId ?: manifest.sourceRef?.split("|")?.getOrNull(1)?.trim()
        if (!manifestProvider.equals(providerId, ignoreCase = true) ||
            !manifestTitle.equals(titleId, ignoreCase = true)
        ) {
            return false
        }
        val manifestSeason = manifest.seasonNumber
            ?: manifest.sourceRef?.split("|")?.getOrNull(2)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        val manifestEpisode = manifest.episodeNumber
            ?: manifest.sourceRef?.split("|")?.getOrNull(3)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        return manifestSeason == seasonNumber && manifestEpisode == episodeNumber
    }

    private suspend fun downloadSingleSubtitleTrack(
        assetDir: File,
        fileName: String,
        track: SubtitleTrack,
        format: String,
        headers: Map<String, String>
    ): OfflineSubtitleEntry {
        val targetFile = File(assetDir, fileName)
        val tmpFile = File(assetDir, "$fileName.tmp")
        var lastError: String? = null

        for (attempt in 1..2) {
            try {
                if (tmpFile.exists()) tmpFile.delete()
                val reqBuilder = Request.Builder().url(track.url).get()
                headers.forEach { (k, v) ->
                    if (k.isNotBlank() && v.isNotBlank()) {
                        reqBuilder.header(k, v)
                    }
                }
                if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    reqBuilder.header("User-Agent", DEFAULT_USER_AGENT)
                }

                okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = "HTTP ${response.code}"
                    } else {
                        val bodyBytes = response.body?.bytes()
                        if (bodyBytes == null || bodyBytes.isEmpty()) {
                            lastError = "Empty subtitle payload"
                        } else {
                            tmpFile.writeBytes(bodyBytes)
                            if (targetFile.exists()) targetFile.delete()
                            val moved = tmpFile.renameTo(targetFile) || run {
                                tmpFile.copyTo(targetFile, overwrite = true)
                                tmpFile.delete()
                                true
                            }
                            if (moved && targetFile.exists() && targetFile.length() > 0L) {
                                return OfflineSubtitleEntry(
                                    trackId = track.id,
                                    language = track.language,
                                    languageCode = track.languageCode,
                                    label = track.label,
                                    format = format,
                                    sourceUrl = track.url,
                                    headers = headers,
                                    fileName = fileName,
                                    status = OfflineSubtitleStatus.COMPLETED,
                                    fileSizeBytes = targetFile.length(),
                                    errorMessage = null
                                )
                            } else {
                                lastError = "Failed to save subtitle file"
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
            } finally {
                if (tmpFile.exists()) tmpFile.delete()
            }

            if (attempt < 2) {
                delay(120L)
            }
        }

        if (targetFile.exists()) targetFile.delete()
        return OfflineSubtitleEntry(
            trackId = track.id,
            language = track.language,
            languageCode = track.languageCode,
            label = track.label,
            format = format,
            sourceUrl = track.url,
            headers = headers,
            fileName = fileName,
            status = OfflineSubtitleStatus.FAILED,
            fileSizeBytes = 0L,
            errorMessage = lastError ?: "Subtitle download failed"
        )
    }

    private fun OfflineSubtitleManifest.toCompletedSubtitleTracks(assetDir: File): List<SubtitleTrack> =
        entries.mapNotNull { entry ->
            if (entry.status != OfflineSubtitleStatus.COMPLETED) return@mapNotNull null
            val file = File(assetDir, entry.fileName)
            if (!file.exists() || file.length() <= 0L) return@mapNotNull null
            SubtitleTrack(
                id = entry.trackId,
                language = entry.language,
                languageCode = entry.languageCode,
                label = entry.label,
                url = file.toURI().toString(),
                format = normalizeFormat(entry.format, file.name),
                headers = emptyMap(),
                isOfflineLocal = true
            )
        }

    private fun OfflineSubtitleManifest.toSummary(): AssetSubtitleSummary {
        val completed = entries.count {
            it.status == OfflineSubtitleStatus.COMPLETED &&
                File(assetDirectory(assetId), it.fileName).let { f -> f.exists() && f.length() > 0L }
        }
        val failed = (entries.size - completed).coerceAtLeast(0)
        return AssetSubtitleSummary(
            assetId = assetId,
            totalCount = entries.size,
            completedCount = completed,
            failedCount = failed
        )
    }

    private fun loadAllSummariesInternal(): Map<String, AssetSubtitleSummary> {
        val root = subtitlesRootDir
        if (!root.exists() || !root.isDirectory) return emptyMap()
        return synchronized(fileLock) {
            val out = linkedMapOf<String, AssetSubtitleSummary>()
            root.listFiles()?.forEach { dir ->
                if (dir.isDirectory) {
                    val manifest = readManifestFile(File(dir, MANIFEST_FILE_NAME))
                    if (manifest != null) {
                        out[manifest.assetId] = manifest.toSummary()
                    }
                }
            }
            out
        }
    }

    private fun assetDirectory(assetId: String): File =
        File(subtitlesRootDir, sanitizeToken(assetId))

    private fun readManifest(assetId: String): OfflineSubtitleManifest? = synchronized(fileLock) {
        readManifestFile(File(assetDirectory(assetId), MANIFEST_FILE_NAME))
    }

    private fun readManifestFile(file: File): OfflineSubtitleManifest? {
        if (!file.exists() || file.length() == 0L) return null
        return runCatching {
            val root = JSONObject(file.readText())
            val entriesArr = root.optJSONArray("entries") ?: JSONArray()
            val entries = buildList {
                for (i in 0 until entriesArr.length()) {
                    val o = entriesArr.optJSONObject(i) ?: continue
                    val headersObj = o.optJSONObject("headers")
                    val headersMap = linkedMapOf<String, String>()
                    if (headersObj != null) {
                        val keys = headersObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val v = headersObj.optString(k, "")
                            if (v.isNotBlank()) headersMap[k] = v
                        }
                    }
                    val statusStr = o.optString("status", OfflineSubtitleStatus.FAILED.name)
                    val status = runCatching { OfflineSubtitleStatus.valueOf(statusStr) }
                        .getOrDefault(OfflineSubtitleStatus.FAILED)
                    add(
                        OfflineSubtitleEntry(
                            trackId = o.optString("trackId"),
                            language = o.optString("language", "English"),
                            languageCode = o.optString("languageCode", "en"),
                            label = o.optString("label", "English"),
                            format = o.optString("format", "srt"),
                            sourceUrl = o.optString("sourceUrl"),
                            headers = headersMap,
                            fileName = o.optString("fileName"),
                            status = status,
                            fileSizeBytes = o.optLong("fileSizeBytes", 0L),
                            errorMessage = o.optString("errorMessage").takeIf { it.isNotBlank() && it != "null" }
                        )
                    )
                }
            }
            OfflineSubtitleManifest(
                assetId = root.optString("assetId"),
                sourceRef = root.optString("sourceRef").takeIf { it.isNotBlank() && it != "null" },
                providerId = root.optString("providerId").takeIf { it.isNotBlank() && it != "null" },
                titleId = root.optString("titleId").takeIf { it.isNotBlank() && it != "null" },
                seasonNumber = if (root.has("seasonNumber") && !root.isNull("seasonNumber")) {
                    root.optInt("seasonNumber").takeIf { it > 0 }
                } else null,
                episodeNumber = if (root.has("episodeNumber") && !root.isNull("episodeNumber")) {
                    root.optInt("episodeNumber").takeIf { it > 0 }
                } else null,
                entries = entries,
                updatedAtMs = root.optLong("updatedAtMs", System.currentTimeMillis())
            )
        }.getOrNull()
    }

    private fun writeManifest(manifest: OfflineSubtitleManifest) {
        synchronized(fileLock) {
            val dir = assetDirectory(manifest.assetId).apply { if (!exists()) mkdirs() }
            val target = File(dir, MANIFEST_FILE_NAME)
            val tmp = File(dir, "$MANIFEST_FILE_NAME.tmp")
            val entriesArr = JSONArray()
            manifest.entries.forEach { e ->
                val headersObj = JSONObject()
                e.headers.forEach { (k, v) -> headersObj.put(k, v) }
                entriesArr.put(
                    JSONObject().apply {
                        put("trackId", e.trackId)
                        put("language", e.language)
                        put("languageCode", e.languageCode)
                        put("label", e.label)
                        put("format", e.format)
                        put("sourceUrl", e.sourceUrl)
                        put("headers", headersObj)
                        put("fileName", e.fileName)
                        put("status", e.status.name)
                        put("fileSizeBytes", e.fileSizeBytes)
                        put("errorMessage", e.errorMessage ?: JSONObject.NULL)
                    }
                )
            }
            val root = JSONObject().apply {
                put("assetId", manifest.assetId)
                put("sourceRef", manifest.sourceRef ?: JSONObject.NULL)
                put("providerId", manifest.providerId ?: JSONObject.NULL)
                put("titleId", manifest.titleId ?: JSONObject.NULL)
                put("seasonNumber", manifest.seasonNumber ?: JSONObject.NULL)
                put("episodeNumber", manifest.episodeNumber ?: JSONObject.NULL)
                put("updatedAtMs", manifest.updatedAtMs)
                put("entries", entriesArr)
            }
            tmp.writeText(root.toString())
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun deduplicateTracks(tracks: List<SubtitleTrack>): List<SubtitleTrack> =
        tracks.filter { it.url.isNotBlank() && !it.isOfflineLocal }
            .distinctBy { it.url.trim() }

    private fun normalizeFormat(rawFormat: String, url: String): String {
        val clean = rawFormat.trim().lowercase(Locale.US)
        return when {
            clean == "vtt" || clean == "webvtt" || url.substringBefore("?").endsWith(".vtt", ignoreCase = true) -> "vtt"
            else -> "srt"
        }
    }

    private fun buildSidecarFileName(
        index: Int,
        languageCode: String,
        trackId: String,
        format: String
    ): String {
        val lang = sanitizeToken(languageCode.ifBlank { "sub" }).take(12)
        val tid = sanitizeToken(trackId.ifBlank { index.toString() }).take(20)
        return "${index}_${lang}_${tid}.$format"
    }

    private fun sanitizeToken(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "item" }

    companion object {
        private const val MAX_CONCURRENT_SUBTITLES = 3
        private const val MANIFEST_FILE_NAME = "manifest.json"
        private const val DEFAULT_USER_AGENT =
            "LaterboxAndroid/1.0 (+https://github.com/Adetisola/Laterbox)"
    }
}
