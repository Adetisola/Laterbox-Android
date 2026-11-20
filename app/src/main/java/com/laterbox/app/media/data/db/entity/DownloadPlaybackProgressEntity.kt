package com.laterbox.app.media.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.laterbox.app.media.domain.model.MediaAsset

@Entity(
    tableName = "download_playback_progress",
    indices = [
        Index(value = ["asset_id"]),
        Index(value = ["updated_at"])
    ]
)
data class DownloadPlaybackProgressEntity(
    @PrimaryKey
    @ColumnInfo(name = "progress_key")
    val progressKey: String,
    @ColumnInfo(name = "asset_id")
    val assetId: String,
    @ColumnInfo(name = "source_ref")
    val sourceRef: String? = null,
    @ColumnInfo(name = "position_ms")
    val positionMs: Long,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)

object DownloadPlaybackProgressKey {
    /**
     * Builds a canonical progress key:
     * - For MovieBox/Movies & TV assets (`providerId|titleId|season|episode|quality`), strips quality
     *   so different quality downloads of the same movie/episode share one resume position.
     * - For generic downloaded videos without a Movie/TV ref, scopes progress per asset.
     */
    fun forAsset(asset: MediaAsset): String =
        fromSourceRef(asset.sourceRef) ?: "asset:${asset.id}"

    fun fromSourceRef(sourceRef: String?): String? {
        val clean = sourceRef?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val parts = clean.split("|")
        if (parts.size < 4) return null
        val providerId = parts[0].trim()
        val titleId = parts[1].trim()
        val season = parts[2].trim().toIntOrNull() ?: 0
        val episode = parts[3].trim().toIntOrNull() ?: 0
        if (providerId.isBlank() || titleId.isBlank()) return null
        return "media:$providerId|$titleId|$season|$episode"
    }

    fun forMediaItem(
        providerId: String,
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): String {
        val se = seasonNumber ?: 0
        val ep = episodeNumber ?: 0
        return "media:${providerId.trim()}|${titleId.trim()}|$se|$ep"
    }

    fun parseMediaParts(sourceRef: String?): MovieRefParts? {
        val clean = sourceRef?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val parts = clean.split("|")
        if (parts.size < 4) return null
        val providerId = parts[0].trim()
        val titleId = parts[1].trim()
        val season = parts[2].trim().toIntOrNull()?.takeIf { it > 0 }
        val episode = parts[3].trim().toIntOrNull()?.takeIf { it > 0 }
        if (providerId.isBlank() || titleId.isBlank()) return null
        return MovieRefParts(providerId, titleId, season, episode)
    }

    data class MovieRefParts(
        val providerId: String,
        val titleId: String,
        val seasonNumber: Int?,
        val episodeNumber: Int?
    )
}
