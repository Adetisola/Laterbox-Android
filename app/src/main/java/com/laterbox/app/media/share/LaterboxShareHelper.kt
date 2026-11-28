package com.laterbox.app.media.share

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.laterbox.app.media.data.db.entity.DownloadPlaybackProgressKey
import com.laterbox.app.media.domain.model.MediaAsset
import com.laterbox.app.media.ui.downloads.DownloadLibraryEntry
import com.laterbox.app.movies.data.download.MoviesDownloadBridge
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.ui.navigation.LaterboxDeepLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

sealed interface LaterboxShareTarget {
    data class TitleDeepLink(
        val title: String,
        val shareUrl: String,
        val kind: MediaKind
    ) : LaterboxShareTarget

    data class LocalFile(
        val title: String,
        val file: File,
        val mimeType: String
    ) : LaterboxShareTarget

    data object BlockedEpisodeShare : LaterboxShareTarget

    data object Unavailable : LaterboxShareTarget
}

object LaterboxShareHelper {

    private val shareSignalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isBlockedEpisodeAsset(asset: MediaAsset): Boolean {
        if (asset.contentCategory.equals(MoviesDownloadBridge.CATEGORY_EPISODE, ignoreCase = true)) {
            return true
        }
        val parts = DownloadPlaybackProgressKey.parseMediaParts(asset.sourceRef) ?: return false
        return parts.seasonNumber != null || parts.episodeNumber != null
    }

    fun resolveTitleShareUrl(item: MovieTitleSummary): String? =
        LaterboxDeepLinks.buildTitleUrl(
            providerId = item.providerId,
            titleId = item.id,
            kind = item.kind
        )

    fun resolveSeriesGroupShareUrl(group: DownloadLibraryEntry.SeriesGroup): String? =
        LaterboxDeepLinks.buildSeriesUrl(
            providerId = group.providerId,
            titleId = group.titleId
        )

    fun classifyAssetShareTarget(context: Context, asset: MediaAsset): LaterboxShareTarget {
        if (isBlockedEpisodeAsset(asset)) {
            return LaterboxShareTarget.BlockedEpisodeShare
        }

        val mediaParts = DownloadPlaybackProgressKey.parseMediaParts(asset.sourceRef)
        val isDownloadedMovie =
            asset.contentCategory.equals(MoviesDownloadBridge.CATEGORY_MOVIE, ignoreCase = true) ||
                mediaParts != null
        if (isDownloadedMovie) {
            if (mediaParts == null) return LaterboxShareTarget.Unavailable
            val shareUrl = LaterboxDeepLinks.buildMovieUrl(
                providerId = mediaParts.providerId,
                titleId = mediaParts.titleId
            ) ?: return LaterboxShareTarget.Unavailable
            val cleanTitle = cleanMovieDisplayTitle(asset.title)
            return LaterboxShareTarget.TitleDeepLink(
                title = cleanTitle,
                shareUrl = shareUrl,
                kind = MediaKind.MOVIE
            )
        }

        val shareableFile = resolveShareableFile(context, asset) ?: return LaterboxShareTarget.Unavailable
        val displayTitle = asset.title?.takeIf { it.isNotBlank() } ?: shareableFile.name
        val mime = asset.mimeType.takeIf { it.isNotBlank() } ?: "*/*"
        return LaterboxShareTarget.LocalFile(
            title = displayTitle,
            file = shareableFile,
            mimeType = mime
        )
    }

    fun resolveShareableFile(context: Context, asset: MediaAsset): File? {
        val relative = asset.localPath.trim().takeIf { it.isNotEmpty() } ?: return null
        val mediaRoot = File(context.filesDir, "media").canonicalFile
        val updatesRoot = File(context.cacheDir, "updates").canonicalFile
        val candidate = runCatching { File(mediaRoot, relative).canonicalFile }.getOrNull()
            ?: return null

        val inAllowedRoot = isPathWithinRoot(mediaRoot.path, candidate.path) ||
            isPathWithinRoot(updatesRoot.path, candidate.path)
        if (!inAllowedRoot) return null
        if (!candidate.exists() || !candidate.isFile) return null
        return candidate
    }

    internal fun isPathWithinRoot(rootCanonicalPath: String, candidateCanonicalPath: String): Boolean {
        val normalizedRoot = rootCanonicalPath.trimEnd(File.separatorChar)
        if (normalizedRoot.isEmpty()) return false
        return candidateCanonicalPath.startsWith(normalizedRoot + File.separatorChar)
    }

    internal fun cleanMovieDisplayTitle(rawTitle: String?): String {
        val cleaned = rawTitle?.trim().orEmpty()
        if (cleaned.isEmpty()) return "Movie on Laterbox"
        return cleaned.replace(Regex("""\s*\([^)]*\)\s*$"""), "").trim().ifEmpty { cleaned }
    }

    fun shareTitle(context: Context, item: MovieTitleSummary): Boolean {
        val url = resolveTitleShareUrl(item)
        if (url == null) {
            com.laterbox.app.ui.util.SaveFeedbackHelper.showInAppOrSystem(
                context,
                "Unable to create share link for this title.",
                com.laterbox.app.ui.components.LaterboxToastVariant.ERROR
            )
            return false
        }
        val launched = launchTextShare(context, title = item.title, url = url)
        if (launched) {
            recordShareIntelligenceSignal(context, item)
        }
        return launched
    }

    fun shareMovieDetails(
        context: Context,
        providerId: String,
        titleId: String,
        title: String,
        kind: MediaKind
    ): Boolean {
        val url = LaterboxDeepLinks.buildTitleUrl(providerId, titleId, kind)
        if (url == null) {
            com.laterbox.app.ui.util.SaveFeedbackHelper.showInAppOrSystem(
                context,
                "Unable to create share link for this title.",
                com.laterbox.app.ui.components.LaterboxToastVariant.ERROR
            )
            return false
        }
        val launched = launchTextShare(context, title = title, url = url)
        if (launched) {
            recordShareIntelligenceSignal(
                context,
                MovieTitleSummary(
                    id = titleId,
                    providerId = providerId,
                    kind = kind,
                    title = title
                )
            )
        }
        return launched
    }

    private fun recordShareIntelligenceSignal(context: Context, item: MovieTitleSummary) {
        val app = context.applicationContext as? com.laterbox.app.LaterboxApplication ?: return
        app.container.let { container ->
            shareSignalScope.launch {
                val enriched = runCatching {
                    container.moviesRepository.getDetails(item.providerId, item.id, forceRefresh = false)
                        .getOrNull()
                        ?.summary
                }.getOrNull() ?: item
                container.intelligenceSignalLogger.logTitleShared(enriched)
            }
        }
    }

    fun shareSeriesGroup(context: Context, group: DownloadLibraryEntry.SeriesGroup): Boolean {
        val url = resolveSeriesGroupShareUrl(group)
        if (url == null) {
            com.laterbox.app.ui.util.SaveFeedbackHelper.showInAppOrSystem(
                context,
                "Unable to create share link for this series.",
                com.laterbox.app.ui.components.LaterboxToastVariant.ERROR
            )
            return false
        }
        return launchTextShare(context, title = group.seriesTitle, url = url)
    }

    fun shareAsset(context: Context, asset: MediaAsset): Boolean {
        return when (val target = classifyAssetShareTarget(context, asset)) {
            is LaterboxShareTarget.TitleDeepLink ->
                launchTextShare(context, title = target.title, url = target.shareUrl)

            is LaterboxShareTarget.LocalFile ->
                launchFileShare(context, target)

            LaterboxShareTarget.BlockedEpisodeShare -> {
                com.laterbox.app.ui.util.SaveFeedbackHelper.showInAppOrSystem(
                    context,
                    "Share the series page instead of individual downloaded episodes.",
                    com.laterbox.app.ui.components.LaterboxToastVariant.WARNING
                )
                false
            }

            LaterboxShareTarget.Unavailable -> {
                com.laterbox.app.ui.util.SaveFeedbackHelper.showInAppOrSystem(
                    context,
                    "This downloaded file is unavailable to share.",
                    com.laterbox.app.ui.components.LaterboxToastVariant.WARNING
                )
                false
            }
        }
    }

    private fun launchTextShare(context: Context, title: String, url: String): Boolean {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, "Watch \"$title\" on Laterbox\n$url")
        }
        return launchChooser(context, sendIntent, "Share $title")
    }

    private fun launchFileShare(context: Context, target: LaterboxShareTarget.LocalFile): Boolean {
        val contentUri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target.file
            )
        }.getOrNull()
        if (contentUri == null || contentUri.scheme != "content") {
            com.laterbox.app.ui.util.SaveFeedbackHelper.showInAppOrSystem(
                context,
                "Unable to prepare file for sharing.",
                com.laterbox.app.ui.components.LaterboxToastVariant.ERROR
            )
            return false
        }

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = target.mimeType
            putExtra(Intent.EXTRA_SUBJECT, target.title)
            putExtra(Intent.EXTRA_STREAM, contentUri)
            clipData = ClipData.newRawUri(target.title, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return launchChooser(context, sendIntent, "Share ${target.title}")
    }

    private fun launchChooser(context: Context, sendIntent: Intent, chooserTitle: String): Boolean {
        val chooser = Intent.createChooser(sendIntent, chooserTitle).apply {
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return try {
            context.startActivity(chooser)
            (context.applicationContext as? com.laterbox.app.LaterboxApplication)
                ?.container
                ?.analyticsTracker
                ?.trackFeatureUsed("media_share")
            true
        } catch (_: ActivityNotFoundException) {
            com.laterbox.app.ui.util.SaveFeedbackHelper.showInAppOrSystem(
                context,
                "No app available to share with.",
                com.laterbox.app.ui.components.LaterboxToastVariant.WARNING
            )
            false
        }
    }
}
