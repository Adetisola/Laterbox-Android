package com.laterbox.app.transcript.export

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

data class PdfLinkAnnotation(
    val pageIndexZeroBased: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val pageHeight: Float,
    val uri: String
)

/**
 * Appends clickable URI /Link annotations to an existing PDF (such as one emitted by
 * android.graphics.pdf.PdfDocument) via a standards-compliant incremental PDF update.
 */
object PdfLinkAnnotator {

    private val OBJ_HEADER_REGEX = Regex("""(\d+)\s+(\d+)\s+obj\b""")
    private val PAGE_TYPE_REGEX = Regex("""/Type\s*/Page(?![a-zA-Z0-9])""")
    private val KIDS_ARRAY_REGEX = Regex("""/Kids\s*\[([^\]]*)]""")
    private val OBJ_REF_REGEX = Regex("""(\d+)\s+0\s+R""")
    private val SIZE_REGEX = Regex("""/Size\s+(\d+)""")
    private val ROOT_REGEX = Regex("""/Root\s+(\d+\s+\d+\s+R)""")

    fun appendLinkAnnotations(
        sourcePdf: File,
        targetOut: OutputStream,
        annotations: List<PdfLinkAnnotation>
    ): Boolean {
        if (!sourcePdf.exists() || sourcePdf.length() < 64L) return false
        if (annotations.isEmpty()) {
            sourcePdf.inputStream().use { it.copyTo(targetOut) }
            return true
        }

        return try {
            val fileLength = sourcePdf.length()
            val tailSize = minOf(4096L, fileLength).toInt()
            val tailText = RandomAccessFile(sourcePdf, "r").use { raf ->
                raf.seek(fileLength - tailSize)
                val buf = ByteArray(tailSize)
                raf.readFully(buf)
                String(buf, StandardCharsets.ISO_8859_1)
            }

            val prevXrefOffset = parseStartXref(tailText) ?: return false
            val prevSize = SIZE_REGEX.findAll(tailText).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
                ?: return false
            val rootRef = ROOT_REGEX.findAll(tailText).lastOrNull()?.groupValues?.get(1)
                ?: return false

            val scannedObjects = scanObjects(sourcePdf)
            val orderedPageIds = resolveOrderedPageIds(scannedObjects)
            if (orderedPageIds.isEmpty()) return false

            val groupedByPage = annotations.groupBy { it.pageIndexZeroBased }
                .filterKeys { it in orderedPageIds.indices }
            if (groupedByPage.isEmpty()) {
                sourcePdf.inputStream().use { it.copyTo(targetOut) }
                return true
            }

            // Copy original bytes verbatim first, tracking byte offset
            var currentOffset = 0L
            sourcePdf.inputStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    targetOut.write(buffer, 0, read)
                    currentOffset += read
                }
            }

            fun writeAscii(text: String) {
                val bytes = text.toByteArray(StandardCharsets.ISO_8859_1)
                targetOut.write(bytes)
                currentOffset += bytes.size
            }

            if (currentOffset > 0L) {
                writeAscii("\n")
            }

            var nextObjId = prevSize
            val updatedEntries = sortedMapOf<Int, Long>()

            for ((pageIdx, pageAnnots) in groupedByPage) {
                val pageObjId = orderedPageIds[pageIdx]
                val pageDict = scannedObjects[pageObjId] ?: continue

                val annotIds = mutableListOf<Int>()
                for (annot in pageAnnots) {
                    val annotId = nextObjId++
                    annotIds.add(annotId)
                    updatedEntries[annotId] = currentOffset

                    // Flip Android top-down Y to PDF bottom-up Y
                    val llx = formatFloat(annot.left)
                    val lly = formatFloat((annot.pageHeight - annot.bottom).coerceAtLeast(0f))
                    val urx = formatFloat(annot.right)
                    val ury = formatFloat((annot.pageHeight - annot.top).coerceAtLeast(0f))
                    val escapedUri = escapePdfString(annot.uri)

                    val objStr = "$annotId 0 obj\n<< /Type /Annot /Subtype /Link /Border [0 0 0] " +
                        "/Rect [$llx $lly $urx $ury] " +
                        "/A << /Type /Action /S /URI /URI ($escapedUri) >> >>\nendobj\n"
                    writeAscii(objStr)
                }

                val newDict = injectAnnotsIntoPageDict(pageDict, annotIds)
                updatedEntries[pageObjId] = currentOffset
                writeAscii("$pageObjId 0 obj\n$newDict\nendobj\n")
            }

            val xrefOffset = currentOffset
            writeAscii("xref\n")
            // Group consecutive object numbers into PDF xref subsections
            val sortedIds = updatedEntries.keys.toList()
            var i = 0
            while (i < sortedIds.size) {
                val startId = sortedIds[i]
                var endId = startId
                while (i + 1 < sortedIds.size && sortedIds[i + 1] == endId + 1) {
                    i++
                    endId = sortedIds[i]
                }
                val count = endId - startId + 1
                writeAscii("$startId $count\n")
                for (objId in startId..endId) {
                    val off = updatedEntries[objId] ?: 0L
                    writeAscii(String.format(java.util.Locale.US, "%010d 00000 n \n", off))
                }
                i++
            }

            writeAscii(
                "trailer\n<< /Size $nextObjId /Root $rootRef /Prev $prevXrefOffset >>\n" +
                    "startxref\n$xrefOffset\n%%EOF\n"
            )
            targetOut.flush()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun parseStartXref(tailText: String): Long? {
        val idx = tailText.lastIndexOf("startxref")
        if (idx < 0) return null
        val after = tailText.substring(idx + "startxref".length).trim()
        val firstToken = after.lineSequence().firstOrNull()?.trim() ?: return null
        return firstToken.toLongOrNull()
    }

    /**
     * Scans object headers and lightweight dictionary text without retaining large binary stream payloads.
     */
    private fun scanObjects(file: File): Map<Int, String> {
        val bytes = file.readBytes()
        val text = String(bytes, StandardCharsets.ISO_8859_1)
        val result = linkedMapOf<Int, String>()

        val matches = OBJ_HEADER_REGEX.findAll(text).toList()
        for (match in matches) {
            val objId = match.groupValues[1].toIntOrNull() ?: continue
            val contentStart = match.range.last + 1
            val dictStart = text.indexOf("<<", contentStart)
            val streamIdx = text.indexOf("stream", contentStart)
            val endObjIdx = text.indexOf("endobj", contentStart)
            if (dictStart < 0 || (endObjIdx >= 0 && dictStart > endObjIdx)) continue
            if (streamIdx >= 0 && dictStart > streamIdx) continue

            val dictEnd = findMatchingDictEnd(text, dictStart) ?: continue
            val dictText = text.substring(dictStart, dictEnd + 2)
            // Retain only page tree and page objects (small metadata dicts)
            if (PAGE_TYPE_REGEX.containsMatchIn(dictText) || dictText.contains("/Pages") || dictText.contains("/Kids")) {
                result[objId] = dictText
            }
        }
        return result
    }

    private fun resolveOrderedPageIds(objects: Map<Int, String>): List<Int> {
        val pagesNodes = objects.filterValues { it.contains("/Kids") && !PAGE_TYPE_REGEX.containsMatchIn(it) }
        val ordered = mutableListOf<Int>()
        val visited = mutableSetOf<Int>()

        fun visitNode(nodeId: Int) {
            if (!visited.add(nodeId)) return
            val dict = objects[nodeId] ?: return
            if (PAGE_TYPE_REGEX.containsMatchIn(dict)) {
                ordered.add(nodeId)
                return
            }
            val kidsMatch = KIDS_ARRAY_REGEX.find(dict) ?: return
            for (refMatch in OBJ_REF_REGEX.findAll(kidsMatch.groupValues[1])) {
                val childId = refMatch.groupValues[1].toIntOrNull() ?: continue
                visitNode(childId)
            }
        }

        // Start from the root /Pages node (one not referenced as a kid by another /Pages node)
        val allKids = pagesNodes.values.flatMap { dict ->
            val m = KIDS_ARRAY_REGEX.find(dict)?.groupValues?.get(1).orEmpty()
            OBJ_REF_REGEX.findAll(m).mapNotNull { it.groupValues[1].toIntOrNull() }
        }.toSet()
        val rootPagesId = pagesNodes.keys.firstOrNull { it !in allKids } ?: pagesNodes.keys.firstOrNull()
        if (rootPagesId != null) {
            visitNode(rootPagesId)
        }

        if (ordered.isEmpty()) {
            objects.entries
                .filter { PAGE_TYPE_REGEX.containsMatchIn(it.value) }
                .sortedBy { it.key }
                .mapTo(ordered) { it.key }
        }
        return ordered
    }

    private fun findMatchingDictEnd(text: String, dictStart: Int): Int? {
        var depth = 0
        var i = dictStart
        while (i < text.length - 1) {
            val c1 = text[i]
            val c2 = text[i + 1]
            if (c1 == '<' && c2 == '<') {
                depth++
                i += 2
                continue
            }
            if (c1 == '>' && c2 == '>') {
                depth--
                if (depth == 0) return i
                i += 2
                continue
            }
            if (c1 == '(') {
                i++
                var escaped = false
                while (i < text.length) {
                    val sc = text[i]
                    if (escaped) {
                        escaped = false
                    } else if (sc == '\\') {
                        escaped = true
                    } else if (sc == ')') {
                        break
                    }
                    i++
                }
            }
            i++
        }
        return null
    }

    private fun injectAnnotsIntoPageDict(dict: String, newAnnotIds: List<Int>): String {
        val refs = newAnnotIds.joinToString(" ") { "$it 0 R" }
        val existingAnnotsRegex = Regex("""/Annots\s*\[([^\]]*)]""")
        val existing = existingAnnotsRegex.find(dict)
        if (existing != null) {
            val combined = (existing.groupValues[1].trim() + " " + refs).trim()
            return existingAnnotsRegex.replaceFirst(dict, "/Annots [$combined]")
        }
        val insertPos = dict.lastIndexOf(">>")
        if (insertPos < 0) return dict
        return dict.substring(0, insertPos).trimEnd() + " /Annots [$refs] >>"
    }

    internal fun escapePdfString(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '(' -> sb.append("\\(")
                ')' -> sb.append("\\)")
                '\r', '\n' -> {}
                else -> if (ch.code in 32..126) sb.append(ch) else sb.append('?')
            }
        }
        return sb.toString()
    }

    private fun formatFloat(value: Float): String {
        return String.format(java.util.Locale.US, "%.2f", value)
    }
}
