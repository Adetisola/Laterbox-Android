package com.laterbox.app.transcript.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.laterbox.app.transcript.domain.CaptionType
import com.laterbox.app.transcript.domain.TranscriptDocument
import com.laterbox.app.transcript.domain.VideoTranscriptEntry
import com.laterbox.app.transcript.domain.VideoTranscriptStatus
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.TimeZone

class PdfTranscriptExporter(
    private val context: Context? = null,
    private val timeZone: TimeZone = TimeZone.getDefault()
) {

    companion object {
        // A4 in 72 dpi points
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val MARGIN_HORIZONTAL = 46f
        const val MARGIN_TOP = 48f
        const val FOOTER_Y = 812f
        const val MAX_CONTENT_Y = 782f
        val CONTENT_WIDTH = PAGE_WIDTH - (MARGIN_HORIZONTAL * 2f)
    }

    fun exportToStream(document: TranscriptDocument, outputStream: OutputStream) {
        val usedNative = tryNativeAndroidPdf(document, outputStream)
        if (!usedNative) {
            VectorPdfTranscriptWriter(timeZone).write(document, outputStream)
        }
    }

    fun exportToBytes(document: TranscriptDocument): ByteArray {
        val baos = ByteArrayOutputStream()
        exportToStream(document, baos)
        return baos.toByteArray()
    }

    private fun tryNativeAndroidPdf(
        document: TranscriptDocument,
        outputStream: OutputStream
    ): Boolean {
        val tempFile = try {
            if (context != null) {
                File.createTempFile("laterbox_transcript_", ".pdf", context.cacheDir)
            } else {
                File.createTempFile("laterbox_transcript_", ".pdf")
            }
        } catch (_: Throwable) {
            return false
        }

        return try {
            val linkAnnotations = mutableListOf<PdfLinkAnnotation>()
            val pdfDoc = PdfDocument()
            try {
                val renderer = NativeCanvasPdfRenderer(pdfDoc, linkAnnotations, timeZone)
                renderer.render(document)
                FileOutputStream(tempFile).use { fos ->
                    pdfDoc.writeTo(fos)
                    fos.flush()
                }
            } finally {
                pdfDoc.close()
            }

            if (tempFile.length() < 64L || !startsWithPdfHeader(tempFile)) {
                return false
            }

            val annotated = PdfLinkAnnotator.appendLinkAnnotations(
                sourcePdf = tempFile,
                targetOut = outputStream,
                annotations = linkAnnotations
            )
            if (!annotated) {
                tempFile.inputStream().use { it.copyTo(outputStream) }
                outputStream.flush()
            }
            true
        } catch (_: Throwable) {
            false
        } finally {
            try {
                tempFile.delete()
            } catch (_: Throwable) {}
        }
    }

    private fun startsWithPdfHeader(file: File): Boolean {
        return try {
            val head = ByteArray(5)
            file.inputStream().use { it.read(head) }
            String(head, StandardCharsets.ISO_8859_1) == "%PDF-"
        } catch (_: Throwable) {
            false
        }
    }
}

private class NativeCanvasPdfRenderer(
    private val pdfDoc: PdfDocument,
    private val linkAnnotations: MutableList<PdfLinkAnnotation>,
    private val timeZone: TimeZone
) {
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#18181B")
        textSize = 18f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val sectionHeadingPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#18181B")
        textSize = 13.5f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val subHeadingPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3F3F46")
        textSize = 11f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val metaLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#52525B")
        textSize = 9.5f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val linkPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B45309")
        textSize = 9.5f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        isUnderlineText = true
    }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#27272A")
        textSize = 10.5f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val statusUnavailablePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#991B1B")
        textSize = 10f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
    }
    private val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#71717A")
        textSize = 8.5f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E4E4E7")
        strokeWidth = 0.75f
    }
    private val accentRulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D97706")
        strokeWidth = 2f
    }
    private val metaCardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F8FAFC")
        style = Paint.Style.FILL
    }
    private val metaCardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")
        style = Paint.Style.STROKE
        strokeWidth = 0.75f
    }

    private var currentPage: PdfDocument.Page? = null
    private var currentCanvas: Canvas? = null
    private var currentPageIndex = -1
    private var cursorY = PdfTranscriptExporter.MARGIN_TOP

    fun render(doc: TranscriptDocument) {
        startNewPage()

        // 1. Document Header
        drawFlowingText(
            text = TranscriptFormatting.documentHeadingTitle(doc),
            paint = titlePaint,
            width = PdfTranscriptExporter.CONTENT_WIDTH,
            lineSpacingMult = 1.2f
        )
        cursorY += 6f
        val canvas = currentCanvas!!
        canvas.drawLine(
            PdfTranscriptExporter.MARGIN_HORIZONTAL,
            cursorY,
            PdfTranscriptExporter.PAGE_WIDTH - PdfTranscriptExporter.MARGIN_HORIZONTAL,
            cursorY,
            accentRulePaint
        )
        cursorY += 12f

        // 2. Summary Metadata Lines
        val metaLines = buildList {
            add("Date Extracted: ${TranscriptFormatting.formatDateIso(doc.extractedAtMillis, timeZone)}")
            if (doc.isPlaylist) {
                add("Videos Found: ${doc.videosFound}")
                add("Successful Transcripts: ${doc.successfulCount}")
                if (doc.noCaptionsCount > 0) add("No Available Captions: ${doc.noCaptionsCount}")
                if (doc.inaccessibleCount > 0) add("Could Not Be Accessed: ${doc.inaccessibleCount}")
            } else {
                val st = doc.entries.firstOrNull()?.status?.displayLabel ?: VideoTranscriptStatus.SUCCESS.displayLabel
                add("Extraction Status: $st")
            }
        }
        drawMetadataCard(sourceUrl = doc.sourceUrl, lines = metaLines)
        cursorY += 14f

        // 3. Contents Section
        ensureVerticalSpace(40f)
        drawFlowingText("Contents", subHeadingPaint, PdfTranscriptExporter.CONTENT_WIDTH, 1.15f)
        cursorY += 4f
        for ((index, entry) in doc.entries.withIndex()) {
            val statusTag = if (entry.hasTranscript) "" else " (${entry.status.displayLabel})"
            drawFlowingText(
                text = "${index + 1}. ${entry.title}$statusTag",
                paint = metaLabelPaint,
                width = PdfTranscriptExporter.CONTENT_WIDTH,
                lineSpacingMult = 1.25f
            )
            cursorY += 2f
        }
        cursorY += 10f

        // 4. Individual Video Sections
        for ((idx, entry) in doc.entries.withIndex()) {
            // Avoid orphaned video heading at the bottom of a page
            ensureVerticalSpace(88f)
            if (idx > 0 && cursorY > PdfTranscriptExporter.MARGIN_TOP + 4f) {
                currentCanvas?.drawLine(
                    PdfTranscriptExporter.MARGIN_HORIZONTAL,
                    cursorY,
                    PdfTranscriptExporter.PAGE_WIDTH - PdfTranscriptExporter.MARGIN_HORIZONTAL,
                    cursorY,
                    dividerPaint
                )
                cursorY += 14f
            } else if (idx == 0) {
                currentCanvas?.drawLine(
                    PdfTranscriptExporter.MARGIN_HORIZONTAL,
                    cursorY,
                    PdfTranscriptExporter.PAGE_WIDTH - PdfTranscriptExporter.MARGIN_HORIZONTAL,
                    cursorY,
                    dividerPaint
                )
                cursorY += 14f
            }

            val heading = TranscriptFormatting.videoSectionHeading(entry, doc)
            drawFlowingText(heading, sectionHeadingPaint, PdfTranscriptExporter.CONTENT_WIDTH, 1.2f)
            cursorY += 4f

            if (entry.url.isNotBlank()) {
                drawLabeledLink("Video URL: ", entry.url)
            }
            val entryMeta = buildList {
                if (!entry.channel.isNullOrBlank()) add("Channel: ${entry.channel}")
                TranscriptFormatting.formatDuration(entry.durationSeconds)?.let { add("Duration: $it") }
                if (doc.isPlaylist) add("Playlist Position: ${entry.position}")
                if (!entry.captionLanguage.isNullOrBlank()) add("Caption Language: ${entry.captionLanguage}")
                if (entry.captionType != CaptionType.UNKNOWN) add("Caption Type: ${entry.captionType.displayLabel}")
                add("Extraction Status: ${entry.status.displayLabel}")
            }
            for (line in entryMeta) {
                drawFlowingText(line, metaLabelPaint, PdfTranscriptExporter.CONTENT_WIDTH, 1.2f)
            }

            cursorY += 8f
            if (entry.hasTranscript) {
                ensureVerticalSpace(36f)
                drawFlowingText("Transcript", subHeadingPaint, PdfTranscriptExporter.CONTENT_WIDTH, 1.15f)
                cursorY += 4f
                for (paragraph in entry.paragraphs) {
                    val ts = TranscriptFormatting.formatTimestamp(paragraph.startMs)
                    val fullParagraph = if (ts != null) "$ts ${paragraph.text}" else paragraph.text
                    drawFlowingText(fullParagraph, bodyPaint, PdfTranscriptExporter.CONTENT_WIDTH, 1.32f)
                    cursorY += 6f
                }
            } else {
                val msg = entry.statusMessage?.takeIf { it.isNotBlank() }
                    ?: "No accessible captions were found for this video."
                drawFlowingText(msg, statusUnavailablePaint, PdfTranscriptExporter.CONTENT_WIDTH, 1.25f)
                cursorY += 8f
            }
        }

        finishCurrentPage()
    }

    private fun drawMetadataCard(sourceUrl: String, lines: List<String>) {
        val urlLayout = buildStaticLayout("Source: $sourceUrl", linkPaint, PdfTranscriptExporter.CONTENT_WIDTH - 20f, 1.2f)
        val lineLayouts = lines.map { buildStaticLayout(it, metaLabelPaint, PdfTranscriptExporter.CONTENT_WIDTH - 20f, 1.25f) }
        val totalHeight = 16f + urlLayout.height + lineLayouts.sumOf { it.height + 2 }
        ensureVerticalSpace(totalHeight + 6f)

        val left = PdfTranscriptExporter.MARGIN_HORIZONTAL
        val top = cursorY
        val right = PdfTranscriptExporter.PAGE_WIDTH - PdfTranscriptExporter.MARGIN_HORIZONTAL
        val bottom = top + totalHeight
        val rect = RectF(left, top, right, bottom)
        currentCanvas?.drawRoundRect(rect, 6f, 6f, metaCardBgPaint)
        currentCanvas?.drawRoundRect(rect, 6f, 6f, metaCardBorderPaint)

        cursorY += 8f
        drawLabeledLink("Source: ", sourceUrl, indent = 10f, maxWidth = PdfTranscriptExporter.CONTENT_WIDTH - 20f)
        cursorY += 2f
        for (line in lines) {
            drawFlowingText(line, metaLabelPaint, PdfTranscriptExporter.CONTENT_WIDTH - 20f, 1.25f, indent = 10f)
            cursorY += 2f
        }
        cursorY = bottom
    }

    private fun drawLabeledLink(
        prefix: String,
        url: String,
        indent: Float = 0f,
        maxWidth: Float = PdfTranscriptExporter.CONTENT_WIDTH - indent
    ) {
        val x = PdfTranscriptExporter.MARGIN_HORIZONTAL + indent
        val prefixWidth = metaLabelPaint.measureText(prefix)
        val urlAvailWidth = (maxWidth - prefixWidth).coerceAtLeast(120f)
        val urlLayout = buildStaticLayout(url, linkPaint, urlAvailWidth, 1.15f)
        val neededHeight = maxOf(urlLayout.height.toFloat(), metaLabelPaint.textSize + 4f)
        ensureVerticalSpace(neededHeight + 2f)

        val canvas = currentCanvas ?: return
        val topY = cursorY
        canvas.drawText(prefix, x, topY - metaLabelPaint.ascent(), metaLabelPaint)

        val linkX = x + prefixWidth
        canvas.save()
        canvas.translate(linkX, topY)
        urlLayout.draw(canvas)
        canvas.restore()

        val actualLinkRight = minOf(
            linkX + (0 until urlLayout.lineCount).maxOfOrNull { urlLayout.getLineWidth(it) }.let { it ?: urlAvailWidth },
            PdfTranscriptExporter.PAGE_WIDTH - PdfTranscriptExporter.MARGIN_HORIZONTAL
        )
        linkAnnotations.add(
            PdfLinkAnnotation(
                pageIndexZeroBased = currentPageIndex,
                left = linkX,
                top = topY,
                right = actualLinkRight,
                bottom = topY + urlLayout.height,
                pageHeight = PdfTranscriptExporter.PAGE_HEIGHT.toFloat(),
                uri = url
            )
        )
        cursorY += neededHeight + 2f
    }

    private fun drawFlowingText(
        text: String,
        paint: TextPaint,
        width: Float,
        lineSpacingMult: Float,
        indent: Float = 0f
    ) {
        if (text.isBlank()) return
        val layout = buildStaticLayout(text, paint, width, lineSpacingMult)
        val x = PdfTranscriptExporter.MARGIN_HORIZONTAL + indent
        for (lineIdx in 0 until layout.lineCount) {
            val lineTop = layout.getLineTop(lineIdx)
            val lineBottom = layout.getLineBottom(lineIdx)
            val lineHeight = (lineBottom - lineTop).toFloat().coerceAtLeast(paint.textSize * 1.15f)
            ensureVerticalSpace(lineHeight)

            val canvas = currentCanvas ?: return
            canvas.save()
            canvas.translate(x, cursorY - lineTop)
            canvas.clipRect(0f, lineTop.toFloat() - 2f, width, lineBottom.toFloat() + 2f)
            layout.draw(canvas)
            canvas.restore()
            cursorY += lineHeight
        }
    }

    private fun buildStaticLayout(
        text: String,
        paint: TextPaint,
        width: Float,
        lineSpacingMult: Float
    ): StaticLayout {
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(100))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, lineSpacingMult)
            .setIncludePad(false)
            .build()
    }

    private fun ensureVerticalSpace(neededHeight: Float) {
        if (cursorY + neededHeight > PdfTranscriptExporter.MAX_CONTENT_Y) {
            startNewPage()
        }
    }

    private fun startNewPage() {
        finishCurrentPage()
        currentPageIndex++
        val pageInfo = PdfDocument.PageInfo.Builder(
            PdfTranscriptExporter.PAGE_WIDTH,
            PdfTranscriptExporter.PAGE_HEIGHT,
            currentPageIndex + 1
        ).create()
        val page = pdfDoc.startPage(pageInfo)
        currentPage = page
        currentCanvas = page.canvas
        cursorY = PdfTranscriptExporter.MARGIN_TOP
    }

    private fun finishCurrentPage() {
        val page = currentPage ?: return
        val canvas = currentCanvas ?: return
        // Draw page footer before closing the page
        val footerRuleY = PdfTranscriptExporter.FOOTER_Y - 14f
        canvas.drawLine(
            PdfTranscriptExporter.MARGIN_HORIZONTAL,
            footerRuleY,
            PdfTranscriptExporter.PAGE_WIDTH - PdfTranscriptExporter.MARGIN_HORIZONTAL,
            footerRuleY,
            dividerPaint
        )
        canvas.drawText(
            "Laterbox Transcript Export",
            PdfTranscriptExporter.MARGIN_HORIZONTAL,
            PdfTranscriptExporter.FOOTER_Y,
            footerPaint
        )
        val pageLabel = "Page ${currentPageIndex + 1}"
        val labelWidth = footerPaint.measureText(pageLabel)
        canvas.drawText(
            pageLabel,
            PdfTranscriptExporter.PAGE_WIDTH - PdfTranscriptExporter.MARGIN_HORIZONTAL - labelWidth,
            PdfTranscriptExporter.FOOTER_Y,
            footerPaint
        )
        pdfDoc.finishPage(page)
        currentPage = null
        currentCanvas = null
    }
}

/**
 * Pure-Kotlin standards-compliant PDF 1.4 streaming writer used as fallback in non-native
 * environments (e.g., Robolectric JVM tests) or if Android PdfDocument cannot initialize.
 * Streams each finished page directly to the output stream to keep memory usage bounded.
 */
internal class VectorPdfTranscriptWriter(
    private val timeZone: TimeZone = TimeZone.getDefault()
) {
    private val pageWidth = PdfTranscriptExporter.PAGE_WIDTH.toFloat()
    private val pageHeight = PdfTranscriptExporter.PAGE_HEIGHT.toFloat()
    private val marginX = PdfTranscriptExporter.MARGIN_HORIZONTAL
    private val contentWidth = PdfTranscriptExporter.CONTENT_WIDTH
    private val topStartY = pageHeight - PdfTranscriptExporter.MARGIN_TOP
    private val minContentY = pageHeight - PdfTranscriptExporter.MAX_CONTENT_Y
    private val footerY = pageHeight - PdfTranscriptExporter.FOOTER_Y

    private val objectOffsets = mutableListOf<Long>()
    private var bytesWritten = 0L

    private val pageObjIds = mutableListOf<Int>()
    private var currentStream = StringBuilder()
    private var currentAnnots = mutableListOf<Int>()
    private var cursorY = topStartY
    private var pageCount = 0

    // Reserve 1=Catalog, 2=Pages, 3=F1(Helvetica), 4=F2(Helvetica-Bold), 5=F3(Helvetica-Oblique)
    private var nextObjId = 6
    private lateinit var out: OutputStream

    fun write(doc: TranscriptDocument, outputStream: OutputStream) {
        out = outputStream
        writeRaw("%PDF-1.4\n%\u00E2\u00E3\u00CF\u00D3\n")
        // Reserve indices 1..5 in objectOffsets (written after pages)
        repeat(5) { objectOffsets.add(0L) }

        beginPage()
        drawWrappedText(TranscriptFormatting.documentHeadingTitle(doc), font = "F2", size = 17f, leading = 22f, colorRgb = "0.09 0.09 0.11")
        cursorY -= 4f
        drawRule(y = cursorY, r = 0.85f, g = 0.47f, b = 0.02f, width = 1.6f)
        cursorY -= 14f

        drawUrlLine("Source: ", doc.sourceUrl)
        drawWrappedText("Date Extracted: ${TranscriptFormatting.formatDateIso(doc.extractedAtMillis, timeZone)}", "F1", 9.5f, 13f, "0.32 0.32 0.36")
        if (doc.isPlaylist) {
            drawWrappedText("Videos Found: ${doc.videosFound}", "F1", 9.5f, 13f, "0.32 0.32 0.36")
            drawWrappedText("Successful Transcripts: ${doc.successfulCount}", "F1", 9.5f, 13f, "0.32 0.32 0.36")
            if (doc.noCaptionsCount > 0) {
                drawWrappedText("No Available Captions: ${doc.noCaptionsCount}", "F1", 9.5f, 13f, "0.32 0.32 0.36")
            }
            if (doc.inaccessibleCount > 0) {
                drawWrappedText("Could Not Be Accessed: ${doc.inaccessibleCount}", "F1", 9.5f, 13f, "0.32 0.32 0.36")
            }
        } else {
            val st = doc.entries.firstOrNull()?.status?.displayLabel ?: VideoTranscriptStatus.SUCCESS.displayLabel
            drawWrappedText("Extraction Status: $st", "F1", 9.5f, 13f, "0.32 0.32 0.36")
        }

        cursorY -= 10f
        ensureSpace(32f)
        drawWrappedText("Contents", "F2", 11f, 15f, "0.25 0.25 0.28")
        cursorY -= 2f
        for ((idx, entry) in doc.entries.withIndex()) {
            val suffix = if (entry.hasTranscript) "" else " (${entry.status.displayLabel})"
            drawWrappedText("${idx + 1}. ${entry.title}$suffix", "F1", 9.5f, 13f, "0.32 0.32 0.36")
        }

        for (entry in doc.entries) {
            cursorY -= 8f
            ensureSpace(80f)
            drawRule(cursorY, 0.89f, 0.89f, 0.91f, 0.75f)
            cursorY -= 14f
            drawEntry(entry, doc)
        }

        endPage()

        // Write fixed objects 1..5 cleanly and emit xref + trailer
        objectOffsets[0] = bytesWritten
        writeRaw("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

        objectOffsets[1] = bytesWritten
        val kidsStr = pageObjIds.joinToString(" ") { "$it 0 R" }
        writeRaw("2 0 obj\n<< /Type /Pages /Kids [$kidsStr] /Count ${pageObjIds.size} >>\nendobj\n")

        objectOffsets[2] = bytesWritten
        writeRaw("3 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>\nendobj\n")

        objectOffsets[3] = bytesWritten
        writeRaw("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>\nendobj\n")

        objectOffsets[4] = bytesWritten
        writeRaw("5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Oblique /Encoding /WinAnsiEncoding >>\nendobj\n")

        val xrefOffset = bytesWritten
        val totalObjects = objectOffsets.size + 1
        writeRaw("xref\n0 $totalObjects\n0000000000 65535 f \n")
        for (off in objectOffsets) {
            writeRaw(String.format(Locale.US, "%010d 00000 n \n", off))
        }
        writeRaw("trailer\n<< /Size $totalObjects /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")
        out.flush()
    }

    private fun drawEntry(entry: VideoTranscriptEntry, doc: TranscriptDocument) {
        val heading = TranscriptFormatting.videoSectionHeading(entry, doc)
        drawWrappedText(heading, "F2", 13f, 17f, "0.09 0.09 0.11")
        cursorY -= 2f

        if (entry.url.isNotBlank()) {
            drawUrlLine("Video URL: ", entry.url)
        }
        if (!entry.channel.isNullOrBlank()) {
            drawWrappedText("Channel: ${entry.channel}", "F1", 9.5f, 13f, "0.32 0.32 0.36")
        }
        TranscriptFormatting.formatDuration(entry.durationSeconds)?.let {
            drawWrappedText("Duration: $it", "F1", 9.5f, 13f, "0.32 0.32 0.36")
        }
        if (doc.isPlaylist) {
            drawWrappedText("Playlist Position: ${entry.position}", "F1", 9.5f, 13f, "0.32 0.32 0.36")
        }
        if (!entry.captionLanguage.isNullOrBlank()) {
            drawWrappedText("Caption Language: ${entry.captionLanguage}", "F1", 9.5f, 13f, "0.32 0.32 0.36")
        }
        if (entry.captionType != CaptionType.UNKNOWN) {
            drawWrappedText("Caption Type: ${entry.captionType.displayLabel}", "F1", 9.5f, 13f, "0.32 0.32 0.36")
        }
        drawWrappedText("Extraction Status: ${entry.status.displayLabel}", "F1", 9.5f, 13f, "0.32 0.32 0.36")

        cursorY -= 6f
        if (entry.hasTranscript) {
            ensureSpace(30f)
            drawWrappedText("Transcript", "F2", 11f, 15f, "0.25 0.25 0.28")
            cursorY -= 2f
            for (paragraph in entry.paragraphs) {
                val ts = TranscriptFormatting.formatTimestamp(paragraph.startMs)
                val full = if (ts != null) "$ts ${paragraph.text}" else paragraph.text
                drawWrappedText(full, "F1", 10.5f, 14.5f, "0.15 0.15 0.17")
                cursorY -= 5f
            }
        } else {
            val msg = entry.statusMessage?.takeIf { it.isNotBlank() }
                ?: "No accessible captions were found for this video."
            drawWrappedText(msg, "F3", 10f, 14f, "0.60 0.11 0.11")
            cursorY -= 4f
        }
    }

    private fun drawUrlLine(prefix: String, url: String) {
        val size = 9.5f
        val leading = 13f
        val prefixWidth = estimateTextWidth(prefix, size, bold = false)
        val availUrlWidth = (contentWidth - prefixWidth).coerceAtLeast(140f)
        val wrapped = wrapText(url, availUrlWidth, size, bold = false)
        for ((idx, line) in wrapped.withIndex()) {
            ensureSpace(leading)
            if (idx == 0) {
                appendTextOp(prefix, marginX, cursorY, "F1", size, "0.32 0.32 0.36")
            }
            val linkX = marginX + prefixWidth
            appendTextOp(line, linkX, cursorY, "F1", size, "0.71 0.33 0.04")
            val lineW = estimateTextWidth(line, size, bold = false)
            val annotId = emitObject(
                "<< /Type /Annot /Subtype /Link /Border [0 0 0] " +
                    "/Rect [${fmt(linkX)} ${fmt(cursorY - 2f)} ${fmt(linkX + lineW)} ${fmt(cursorY + size + 1f)}] " +
                    "/A << /Type /Action /S /URI /URI (${PdfLinkAnnotator.escapePdfString(url)}) >> >>"
            )
            currentAnnots.add(annotId)
            cursorY -= leading
        }
    }

    private fun drawWrappedText(
        text: String,
        font: String,
        size: Float,
        leading: Float,
        colorRgb: String
    ) {
        val lines = wrapText(text, contentWidth, size, bold = font == "F2")
        for (line in lines) {
            ensureSpace(leading)
            appendTextOp(line, marginX, cursorY, font, size, colorRgb)
            cursorY -= leading
        }
    }

    private fun appendTextOp(text: String, x: Float, y: Float, font: String, size: Float, colorRgb: String) {
        val escaped = escapeWinAnsiPdfText(text)
        currentStream.append("$colorRgb rg BT /$font ${fmt(size)} Tf 1 0 0 1 ${fmt(x)} ${fmt(y)} Tm ($escaped) Tj ET\n")
    }

    private fun drawRule(y: Float, r: Float, g: Float, b: Float, width: Float) {
        currentStream.append("$r $g $b RG ${fmt(width)} w ${fmt(marginX)} ${fmt(y)} m ${fmt(pageWidth - marginX)} ${fmt(y)} l S\n")
    }

    private fun ensureSpace(neededHeight: Float) {
        if (cursorY - neededHeight < minContentY) {
            endPage()
            beginPage()
        }
    }

    private fun beginPage() {
        pageCount++
        currentStream = StringBuilder()
        currentAnnots = mutableListOf()
        cursorY = topStartY
    }

    private fun endPage() {
        if (pageCount == 0) return
        val footerRuleY = footerY + 12f
        drawRule(footerRuleY, 0.89f, 0.89f, 0.91f, 0.6f)
        appendTextOp("Laterbox Transcript Export", marginX, footerY, "F1", 8.5f, "0.44 0.44 0.48")
        val pageLabel = "Page $pageCount"
        val labelWidth = estimateTextWidth(pageLabel, 8.5f, bold = false)
        appendTextOp(pageLabel, pageWidth - marginX - labelWidth, footerY, "F1", 8.5f, "0.44 0.44 0.48")

        val streamBytes = currentStream.toString().toByteArray(StandardCharsets.ISO_8859_1)
        val contentObjId = emitObject("<< /Length ${streamBytes.size} >>\nstream\n${currentStream}endstream")
        val annotsEntry = if (currentAnnots.isNotEmpty()) {
            " /Annots [${currentAnnots.joinToString(" ") { "$it 0 R" }}]"
        } else ""
        val pageId = emitObject(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${PdfTranscriptExporter.PAGE_WIDTH} ${PdfTranscriptExporter.PAGE_HEIGHT}] " +
                "/Resources << /Font << /F1 3 0 R /F2 4 0 R /F3 5 0 R >> >> " +
                "/Contents $contentObjId 0 R$annotsEntry >>"
        )
        pageObjIds.add(pageId)
    }

    private fun emitObject(body: String): Int {
        val objId = nextObjId++
        while (objectOffsets.size < objId) {
            objectOffsets.add(0L)
        }
        objectOffsets[objId - 1] = bytesWritten
        writeRaw("$objId 0 obj\n$body\nendobj\n")
        return objId
    }

    private fun writeRaw(str: String) {
        val bytes = str.toByteArray(StandardCharsets.ISO_8859_1)
        out.write(bytes)
        bytesWritten += bytes.size
    }

    private fun wrapText(text: String, maxWidth: Float, size: Float, bold: Boolean): List<String> {
        val clean = text.replace("\r\n", "\n").replace("\n", " ").trim()
        if (clean.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        val words = clean.split(Regex("\\s+"))
        val current = StringBuilder()

        for (word in words) {
            val candidate = if (current.isEmpty()) word else "${current} $word"
            if (estimateTextWidth(candidate, size, bold) <= maxWidth) {
                if (current.isNotEmpty()) current.append(' ')
                current.append(word)
            } else {
                if (current.isNotEmpty()) {
                    lines.add(current.toString())
                    current.clear()
                }
                if (estimateTextWidth(word, size, bold) <= maxWidth) {
                    current.append(word)
                } else {
                    // Break extra-long tokens (such as URLs) safely without clipping
                    var chunk = StringBuilder()
                    for (ch in word) {
                        if (estimateTextWidth(chunk.toString() + ch, size, bold) > maxWidth && chunk.isNotEmpty()) {
                            lines.add(chunk.toString())
                            chunk = StringBuilder()
                        }
                        chunk.append(ch)
                    }
                    if (chunk.isNotEmpty()) current.append(chunk)
                }
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }

    private fun estimateTextWidth(text: String, size: Float, bold: Boolean): Float {
        var units = 0f
        val scale = if (bold) 1.07f else 1.0f
        for (ch in text) {
            units += when (ch) {
                'i', 'l', 'I', '|', '.', ',', ':', ';', '!', '\'', '`' -> 0.28f
                'f', 'j', 'r', 't', '-', '(', ')', '[', ']', ' ' -> 0.36f
                'm', 'w', 'M', 'W' -> 0.80f
                in 'A'..'Z' -> 0.67f
                in '0'..'9' -> 0.56f
                else -> 0.54f
            }
        }
        return units * size * scale
    }

    private fun escapeWinAnsiPdfText(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (ch in text) {
            val normalized = when (ch) {
                '\u2014' -> '-' // em dash safe ASCII/WinAnsi
                '\u2013' -> '-'
                '\u2018', '\u2019' -> '\''
                '\u201C', '\u201D' -> '"'
                '\u2026' -> '.'
                else -> ch
            }
            when (normalized) {
                '\\' -> sb.append("\\\\")
                '(' -> sb.append("\\(")
                ')' -> sb.append("\\)")
                '\r', '\n' -> sb.append(' ')
                else -> if (normalized.code in 32..255) sb.append(normalized) else sb.append('?')
            }
        }
        return sb.toString()
    }

    private fun fmt(v: Float): String = String.format(Locale.US, "%.2f", v)
}
