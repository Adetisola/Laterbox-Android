package com.laterbox.app.transcript.export

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.laterbox.app.media.domain.repository.MediaRepository
import com.laterbox.app.transcript.domain.ExportFormat
import com.laterbox.app.transcript.domain.MultiFormatExportResult
import com.laterbox.app.transcript.domain.SingleExportOutcome
import com.laterbox.app.transcript.domain.TranscriptDocument
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

sealed interface ExportDestination {
    data object LaterboxLibrary : ExportDestination
    data class SafTree(val treeUri: Uri, val displayName: String? = null) : ExportDestination
    data object DownloadsFolder : ExportDestination
}

interface TranscriptTargetWriter {
    fun openOutputStream(
        filename: String,
        mimeType: String,
        destination: ExportDestination
    ): OpenedExportTarget

    fun cleanupFailedTarget(uri: Uri?, destination: ExportDestination)
}

data class OpenedExportTarget(
    val uri: Uri,
    val actualFilename: String,
    val displayLocation: String,
    val outputStream: OutputStream
)

class AndroidTranscriptTargetWriter(
    private val context: Context
) : TranscriptTargetWriter {

    private val contentResolver: ContentResolver get() = context.contentResolver

    override fun openOutputStream(
        filename: String,
        mimeType: String,
        destination: ExportDestination
    ): OpenedExportTarget {
        return when (destination) {
            ExportDestination.LaterboxLibrary ->
                throw UnsupportedOperationException("LaterboxLibrary destination is handled internally by MediaRepository.")
            is ExportDestination.SafTree -> openSafTreeTarget(destination.treeUri, destination.displayName, filename, mimeType)
            ExportDestination.DownloadsFolder -> openMediaStoreDownloadsTarget(filename, mimeType)
        }
    }

    override fun cleanupFailedTarget(uri: Uri?, destination: ExportDestination) {
        if (uri == null) return
        try {
            when (destination) {
                ExportDestination.LaterboxLibrary -> Unit
                is ExportDestination.SafTree -> DocumentFile.fromSingleUri(context, uri)?.delete()
                ExportDestination.DownloadsFolder -> contentResolver.delete(uri, null, null)
            }
        } catch (_: Throwable) {}
    }

    private fun openSafTreeTarget(
        treeUri: Uri,
        treeDisplayName: String?,
        filename: String,
        mimeType: String
    ): OpenedExportTarget {
        val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("Selected folder is no longer accessible.")
        if (!treeDoc.exists() || !treeDoc.canWrite()) {
            throw IOException("Selected folder does not have write permission.")
        }

        val resolvedName = resolveUniqueSafFilename(treeDoc, filename)
        val created = treeDoc.createFile(mimeType, resolvedName)
            ?: throw IOException("Could not create \"$resolvedName\" in selected folder.")
        val out = contentResolver.openOutputStream(created.uri)
            ?: run {
                created.delete()
                throw IOException("Could not open output stream for \"$resolvedName\".")
            }

        val folderLabel = treeDisplayName ?: treeDoc.name ?: "Selected folder"
        return OpenedExportTarget(
            uri = created.uri,
            actualFilename = created.name ?: resolvedName,
            displayLocation = "$folderLabel/${created.name ?: resolvedName}",
            outputStream = out
        )
    }

    private fun resolveUniqueSafFilename(treeDoc: DocumentFile, desiredName: String): String {
        val existing = treeDoc.findFile(desiredName) ?: return desiredName
        if (!existing.exists()) return desiredName
        val base = desiredName.substringBeforeLast('.')
        val ext = desiredName.substringAfterLast('.', "")
        val suffix = if (ext.isNotEmpty()) ".$ext" else ""
        for (idx in 1..20) {
            val candidate = "${base}_($idx)$suffix"
            if (treeDoc.findFile(candidate) == null) {
                return candidate
            }
        }
        return "${base}_${System.currentTimeMillis()}$suffix"
    }

    private fun openMediaStoreDownloadsTarget(
        filename: String,
        mimeType: String
    ): OpenedExportTarget {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IOException("Choosing a folder via Storage Access Framework is required on this Android version.")
        }
        return insertMediaStoreDownloadApi29(filename, mimeType)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insertMediaStoreDownloadApi29(filename: String, mimeType: String): OpenedExportTarget {
        val relativeDir = "${Environment.DIRECTORY_DOWNLOADS}/Laterbox/Transcripts"
        val collectionUri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = contentResolver.insert(collectionUri, values)
            ?: throw IOException("Failed to create entry in Downloads/Laterbox/Transcripts.")

        val rawOut = try {
            contentResolver.openOutputStream(uri)
                ?: throw IOException("Failed to open output stream for $filename.")
        } catch (e: Exception) {
            try { contentResolver.delete(uri, null, null) } catch (_: Throwable) {}
            throw e
        }

        val finalizingStream = object : OutputStream() {
            private var closed = false
            override fun write(b: Int) = rawOut.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = rawOut.write(b, off, len)
            override fun flush() = rawOut.flush()
            override fun close() {
                if (closed) return
                closed = true
                try {
                    rawOut.flush()
                } finally {
                    rawOut.close()
                }
                val doneValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                contentResolver.update(uri, doneValues, null, null)
            }
        }

        return OpenedExportTarget(
            uri = uri,
            actualFilename = filename,
            displayLocation = "$relativeDir/$filename",
            outputStream = finalizingStream
        )
    }
}

class TranscriptExportManager(
    private val context: Context? = null,
    private val markdownExporter: MarkdownTranscriptExporter = MarkdownTranscriptExporter(),
    private val plainTextExporter: PlainTextTranscriptExporter = PlainTextTranscriptExporter(),
    private val pdfExporter: PdfTranscriptExporter = PdfTranscriptExporter(context = context),
    private val targetWriter: TranscriptTargetWriter? = context?.let { AndroidTranscriptTargetWriter(it) },
    private val mediaRepository: MediaRepository? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    fun buildBaseFilename(document: TranscriptDocument): String {
        val prefix = TranscriptFormatting.sanitizeFilename(document.title)
        return if (document.isPlaylist && !prefix.contains("transcript", ignoreCase = true)) {
            TranscriptFormatting.sanitizeFilename("$prefix - Transcripts")
        } else if (!document.isPlaylist && !prefix.contains("transcript", ignoreCase = true)) {
            TranscriptFormatting.sanitizeFilename("$prefix - Transcript")
        } else {
            prefix
        }
    }

    fun isTreeUriWritable(context: Context, uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        return try {
            val uri = Uri.parse(uriString)
            val tree = DocumentFile.fromTreeUri(context, uri)
            tree != null && tree.exists() && tree.canWrite()
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun exportSelectedFormats(
        document: TranscriptDocument,
        formats: Set<ExportFormat>,
        destination: ExportDestination
    ): MultiFormatExportResult = withContext(ioDispatcher) {
        if (formats.isEmpty()) {
            return@withContext MultiFormatExportResult(emptyList())
        }

        val baseName = buildBaseFilename(document)
        val orderedFormats = ExportFormat.entries.filter { it in formats }

        if (destination is ExportDestination.LaterboxLibrary) {
            return@withContext saveOnlyToLibrary(document, orderedFormats, baseName)
        }

        val writer = targetWriter
            ?: return@withContext MultiFormatExportResult(
                orderedFormats.map { fmt ->
                    SingleExportOutcome(
                        format = fmt,
                        filename = "$baseName.${fmt.extension}",
                        success = false,
                        errorMessage = "Export writer is not configured."
                    )
                }
            )

        val outcomes = mutableListOf<SingleExportOutcome>()

        for (format in orderedFormats) {
            val desiredFilename = "$baseName.${format.extension}"
            val bytes = try {
                generateBytes(document, format)
            } catch (e: Exception) {
                outcomes.add(
                    SingleExportOutcome(
                        format = format,
                        filename = desiredFilename,
                        success = false,
                        errorMessage = e.message?.takeIf { it.isNotBlank() } ?: "Failed to generate ${format.displayName}"
                    )
                )
                continue
            }

            // Always persist internal Document asset in Laterbox Downloads Library first when MediaRepository is wired.
            // If external folder export subsequently fails, this Library document is intentionally preserved.
            val libraryAsset = saveToLibrarySafely(
                document = document,
                format = format,
                desiredFilename = desiredFilename,
                bytes = bytes
            )

            var openedTarget: OpenedExportTarget? = null
            try {
                openedTarget = writer.openOutputStream(desiredFilename, format.mimeType, destination)
                openedTarget.outputStream.use { out ->
                    out.write(bytes)
                    out.flush()
                }
                if (libraryAsset != null) {
                    try {
                        mediaRepository?.markAssetExported(libraryAsset.id, openedTarget.uri.toString())
                    } catch (_: Throwable) {}
                }
                outcomes.add(
                    SingleExportOutcome(
                        format = format,
                        filename = openedTarget.actualFilename,
                        success = true,
                        uri = openedTarget.uri.toString(),
                        displayLocation = openedTarget.displayLocation,
                        savedToLibrary = libraryAsset != null,
                        libraryAssetId = libraryAsset?.id,
                        externalExportSucceeded = true
                    )
                )
            } catch (e: Exception) {
                writer.cleanupFailedTarget(openedTarget?.uri, destination)
                val rawErr = e.message?.takeIf { it.isNotBlank() } ?: "Failed to export ${format.displayName}"
                val combinedMsg = if (libraryAsset != null) {
                    "Saved to Downloads Library (Documents); external export failed: $rawErr"
                } else {
                    rawErr
                }
                outcomes.add(
                    SingleExportOutcome(
                        format = format,
                        filename = desiredFilename,
                        success = false,
                        uri = libraryAsset?.let { resolveInternalFileProviderUri(it.localPath) },
                        displayLocation = libraryAsset?.let { "Downloads Library (Documents)/${File(it.localPath).name}" },
                        errorMessage = combinedMsg,
                        savedToLibrary = libraryAsset != null,
                        libraryAssetId = libraryAsset?.id,
                        externalExportSucceeded = false
                    )
                )
            }
        }

        MultiFormatExportResult(outcomes)
    }

    private suspend fun saveOnlyToLibrary(
        document: TranscriptDocument,
        orderedFormats: List<ExportFormat>,
        baseName: String
    ): MultiFormatExportResult {
        if (mediaRepository == null) {
            return MultiFormatExportResult(
                orderedFormats.map { fmt ->
                    SingleExportOutcome(
                        format = fmt,
                        filename = "$baseName.${fmt.extension}",
                        success = false,
                        errorMessage = "Downloads Library repository is unavailable."
                    )
                }
            )
        }

        val outcomes = mutableListOf<SingleExportOutcome>()
        for (format in orderedFormats) {
            val desiredFilename = "$baseName.${format.extension}"
            try {
                val bytes = generateBytes(document, format)
                val asset = mediaRepository.saveGeneratedDocument(
                    title = "$baseName.${format.extension}",
                    filename = desiredFilename,
                    mimeType = format.mimeType,
                    bytes = bytes,
                    sourcePlatform = "YouTube Transcript",
                    originalSourceUrl = document.sourceUrl,
                    savedLinkId = null,
                    qualityLabel = format.extension.uppercase()
                )
                val storedName = File(asset.localPath).name
                outcomes.add(
                    SingleExportOutcome(
                        format = format,
                        filename = storedName,
                        success = true,
                        uri = resolveInternalFileProviderUri(asset.localPath),
                        displayLocation = "Downloads Library · Documents/$storedName",
                        savedToLibrary = true,
                        libraryAssetId = asset.id,
                        externalExportSucceeded = false
                    )
                )
            } catch (e: Exception) {
                outcomes.add(
                    SingleExportOutcome(
                        format = format,
                        filename = desiredFilename,
                        success = false,
                        errorMessage = e.message?.takeIf { it.isNotBlank() } ?: "Failed to save ${format.displayName} to Library"
                    )
                )
            }
        }
        return MultiFormatExportResult(outcomes)
    }

    private suspend fun saveToLibrarySafely(
        document: TranscriptDocument,
        format: ExportFormat,
        desiredFilename: String,
        bytes: ByteArray
    ): com.laterbox.app.media.domain.model.MediaAsset? {
        val repo = mediaRepository ?: return null
        return try {
            repo.saveGeneratedDocument(
                title = desiredFilename,
                filename = desiredFilename,
                mimeType = format.mimeType,
                bytes = bytes,
                sourcePlatform = "YouTube Transcript",
                originalSourceUrl = document.sourceUrl,
                savedLinkId = null,
                qualityLabel = format.extension.uppercase()
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveInternalFileProviderUri(relativePath: String): String? {
        val ctx = context ?: return null
        return try {
            val file = File(ctx.filesDir, "media/$relativePath")
            if (!file.exists()) return null
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file).toString()
        } catch (_: Throwable) {
            null
        }
    }

    fun writeFormatToStream(
        document: TranscriptDocument,
        format: ExportFormat,
        outputStream: OutputStream
    ) {
        when (format) {
            ExportFormat.MARKDOWN -> markdownExporter.exportToStream(document, outputStream)
            ExportFormat.PLAIN_TEXT -> plainTextExporter.exportToStream(document, outputStream)
            ExportFormat.PDF -> pdfExporter.exportToStream(document, outputStream)
        }
    }

    fun generateBytes(document: TranscriptDocument, format: ExportFormat): ByteArray {
        val baos = ByteArrayOutputStream()
        writeFormatToStream(document, format, baos)
        return baos.toByteArray()
    }
}
