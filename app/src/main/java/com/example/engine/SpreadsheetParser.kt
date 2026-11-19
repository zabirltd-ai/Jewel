package com.example.engine

import android.util.Xml
import com.example.model.ParsedSheet
import com.example.model.ParsedWorkbook
import com.example.model.PoFileFormat
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * Pure-Kotlin native Android spreadsheet reader supporting:
 * - Excel OOXML (.xlsx) via ZipInputStream + XmlPullParser (multi-sheet, shared strings, dates, formulas)
 * - Legacy Excel (.xls) via OLE2/BIFF8 binary parser with XML SpreadsheetML / HTML / TSV fallbacks
 * - Delimited text (.csv / .tsv) via RFC 4180 parser with auto-delimiter detection
 */
object SpreadsheetParser {

    fun parse(fileName: String, inputStream: InputStream): ParsedWorkbook {
        val bytes = inputStream.readBytes()
        require(bytes.isNotEmpty()) { "Selected file is empty." }

        val declaredFormat = PoFileFormat.detectFromFileName(fileName)
        return when {
            isZipMagic(bytes) -> parseXlsx(fileName, bytes)
            isOle2Magic(bytes) -> parseBiff8Xls(fileName, bytes)
            declaredFormat == PoFileFormat.XLS && looksLikeXmlOrHtml(bytes) -> parseXmlOrHtmlTable(fileName, bytes)
            else -> parseCsv(fileName, bytes, if (declaredFormat == PoFileFormat.XLS) PoFileFormat.XLS else PoFileFormat.CSV)
        }
    }

    private fun isZipMagic(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    private fun isOle2Magic(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() &&
            bytes[2] == 0x11.toByte() && bytes[3] == 0xE0.toByte() &&
            bytes[4] == 0xA1.toByte() && bytes[5] == 0xB1.toByte() &&
            bytes[6] == 0x1A.toByte() && bytes[7] == 0xE1.toByte()

    private fun looksLikeXmlOrHtml(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, min(bytes.size, 512), Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return head.startsWith("<")
    }

    // -------------------------------------------------------------------------
    // 1. XLSX (OOXML) Parser
    // -------------------------------------------------------------------------
    private fun parseXlsx(fileName: String, bytes: ByteArray): ParsedWorkbook {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val normalized = entry.name.trimStart('/')
                    entries[normalized] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val sharedStrings = entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val dateStyleIndices = entries["xl/styles.xml"]?.let { parseDateStyleIndices(it) } ?: emptySet()
        val relMap = entries["xl/_rels/workbook.xml.rels"]?.let { parseWorkbookRels(it) } ?: emptyMap()
        val sheetDefs = entries["xl/workbook.xml"]?.let { parseWorkbookSheets(it) } ?: emptyList()

        val sheets = mutableListOf<ParsedSheet>()
        if (sheetDefs.isNotEmpty()) {
            for ((sheetName, relId) in sheetDefs) {
                val target = relMap[relId] ?: continue
                val resolvedPath = resolveZipPath(target)
                val sheetBytes = entries[resolvedPath] ?: continue
                val rows = parseWorksheetXml(sheetBytes, sharedStrings, dateStyleIndices)
                sheets.add(ParsedSheet(name = sheetName.ifBlank { "Sheet ${sheets.size + 1}" }, rows = rows))
            }
        }

        // Fallback if workbook.xml wasn't standard
        if (sheets.isEmpty()) {
            val fallbackSheets = entries.keys
                .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
                .sorted()
            fallbackSheets.forEachIndexed { idx, path ->
                val rows = parseWorksheetXml(entries.getValue(path), sharedStrings, dateStyleIndices)
                sheets.add(ParsedSheet(name = "Sheet ${idx + 1}", rows = rows))
            }
        }

        require(sheets.isNotEmpty()) { "No readable worksheets found in Excel file." }
        return ParsedWorkbook(fileName = fileName, format = PoFileFormat.XLSX, sheets = sheets)
    }

    private fun resolveZipPath(target: String): String {
        val clean = target.trimStart('/')
        return when {
            clean.startsWith("xl/") -> clean
            clean.startsWith("../") -> clean.removePrefix("../")
            else -> "xl/$clean"
        }
    }

    private fun parseSharedStrings(xmlBytes: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(xmlBytes), "UTF-8")

        var inSi = false
        var inT = false
        val current = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = localTag(parser.name)
                    if (name == "si") {
                        inSi = true
                        current.setLength(0)
                    } else if (inSi && name == "t") {
                        inT = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inSi && inT) current.append(parser.text ?: "")
                }
                XmlPullParser.END_TAG -> {
                    val name = localTag(parser.name)
                    if (name == "t") {
                        inT = false
                    } else if (name == "si") {
                        strings.add(current.toString())
                        inSi = false
                    }
                }
            }
            event = parser.next()
        }
        return strings
    }

    private fun parseWorkbookRels(xmlBytes: ByteArray): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(xmlBytes), "UTF-8")

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && localTag(parser.name) == "Relationship") {
                val id = parser.getAttributeValue(null, "Id")
                val target = parser.getAttributeValue(null, "Target")
                if (!id.isNullOrBlank() && !target.isNullOrBlank()) {
                    map[id] = target
                }
            }
            event = parser.next()
        }
        return map
    }

    private fun parseWorkbookSheets(xmlBytes: ByteArray): List<Pair<String, String>> {
        val sheets = mutableListOf<Pair<String, String>>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(xmlBytes), "UTF-8")

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && localTag(parser.name) == "sheet") {
                val name = parser.getAttributeValue(null, "name") ?: "Sheet"
                val relId = parser.getAttributeValue(null, "r:id")
                    ?: parser.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
                    ?: (0 until parser.attributeCount).firstNotNullOfOrNull { idx ->
                        if (parser.getAttributeName(idx).endsWith(":id") || parser.getAttributeName(idx) == "id") {
                            parser.getAttributeValue(idx)
                        } else null
                    }
                if (!relId.isNullOrBlank()) {
                    sheets.add(name to relId)
                }
            }
            event = parser.next()
        }
        return sheets
    }

    private fun parseDateStyleIndices(xmlBytes: ByteArray): Set<Int> {
        val customDateFmtIds = mutableSetOf<Int>()
        val dateStyleIndices = mutableSetOf<Int>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(xmlBytes), "UTF-8")

        var inCellXfs = false
        var xfIndex = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (localTag(parser.name)) {
                        "numFmt" -> {
                            val id = parser.getAttributeValue(null, "numFmtId")?.toIntOrNull()
                            val code = parser.getAttributeValue(null, "formatCode")?.lowercase(Locale.US) ?: ""
                            if (id != null && looksLikeDateFormat(code)) {
                                customDateFmtIds.add(id)
                            }
                        }
                        "cellXfs" -> {
                            inCellXfs = true
                            xfIndex = 0
                        }
                        "xf" -> if (inCellXfs) {
                            val numFmtId = parser.getAttributeValue(null, "numFmtId")?.toIntOrNull() ?: -1
                            if (numFmtId in 14..22 || numFmtId in 45..47 || numFmtId in customDateFmtIds) {
                                dateStyleIndices.add(xfIndex)
                            }
                            xfIndex++
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (localTag(parser.name) == "cellXfs") inCellXfs = false
                }
            }
            event = parser.next()
        }
        return dateStyleIndices
    }

    private fun looksLikeDateFormat(code: String): Boolean {
        val cleaned = code.replace(Regex("\"[^\"]*\""), "")
        return (cleaned.contains("yy") || cleaned.contains("dd")) &&
            (cleaned.contains("m") || cleaned.contains("d")) &&
            !cleaned.contains("#") && !cleaned.contains("0.00")
    }

    private fun parseWorksheetXml(
        xmlBytes: ByteArray,
        sharedStrings: List<String>,
        dateStyleIndices: Set<Int>
    ): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(xmlBytes), "UTF-8")

        var currentRow = mutableMapOf<Int, String>()
        var maxColInRow = -1
        var autoColIdx = 0

        var cellColIdx = -1
        var cellType: String? = null
        var cellStyleIdx: Int? = null
        var inValue = false
        var inInlineT = false
        val textBuffer = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (localTag(parser.name)) {
                        "row" -> {
                            currentRow = mutableMapOf()
                            maxColInRow = -1
                            autoColIdx = 0
                        }
                        "c" -> {
                            val ref = parser.getAttributeValue(null, "r")
                            cellColIdx = if (!ref.isNullOrBlank()) columnRefToIndex(ref) else autoColIdx
                            autoColIdx = cellColIdx + 1
                            cellType = parser.getAttributeValue(null, "t")
                            cellStyleIdx = parser.getAttributeValue(null, "s")?.toIntOrNull()
                            textBuffer.setLength(0)
                        }
                        "v" -> {
                            inValue = true
                            textBuffer.setLength(0)
                        }
                        "t" -> {
                            if (cellType == "inlineStr") {
                                inInlineT = true
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inValue || inInlineT) {
                        textBuffer.append(parser.text ?: "")
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (localTag(parser.name)) {
                        "v" -> inValue = false
                        "t" -> inInlineT = false
                        "c" -> {
                            val raw = textBuffer.toString()
                            val formatted = decodeCellValue(raw, cellType, cellStyleIdx, sharedStrings, dateStyleIndices)
                            if (cellColIdx >= 0 && formatted.isNotEmpty()) {
                                currentRow[cellColIdx] = formatted
                                if (cellColIdx > maxColInRow) maxColInRow = cellColIdx
                            }
                            cellColIdx = -1
                            cellType = null
                            cellStyleIdx = null
                        }
                        "row" -> {
                            if (maxColInRow >= 0) {
                                val list = MutableList(maxColInRow + 1) { idx -> currentRow[idx] ?: "" }
                                if (list.any { it.isNotBlank() }) {
                                    rows.add(list)
                                }
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }

        val globalMaxCol = rows.maxOfOrNull { it.size } ?: 0
        return rows.map { r ->
            if (r.size < globalMaxCol) r + List(globalMaxCol - r.size) { "" } else r
        }
    }

    private fun decodeCellValue(
        raw: String,
        cellType: String?,
        styleIdx: Int?,
        sharedStrings: List<String>,
        dateStyleIndices: Set<Int>
    ): String {
        if (raw.isEmpty()) return ""
        return when (cellType) {
            "s" -> {
                val idx = raw.trim().toIntOrNull() ?: return raw
                sharedStrings.getOrElse(idx) { "" }.trim()
            }
            "inlineStr", "str" -> raw.trim()
            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
            "e" -> raw.trim()
            else -> {
                val num = raw.trim().toDoubleOrNull()
                if (num != null) {
                    if (styleIdx != null && styleIdx in dateStyleIndices && num > 20000.0 && num < 80000.0) {
                        excelSerialToIsoDate(num)
                    } else {
                        formatCleanNumber(num)
                    }
                } else {
                    raw.trim()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 2. Legacy Binary Excel (.xls OLE2 / BIFF8) Parser
    // -------------------------------------------------------------------------
    private fun parseBiff8Xls(fileName: String, bytes: ByteArray): ParsedWorkbook {
        val workbookStream = extractOle2WorkbookStream(bytes)
            ?: throw IllegalArgumentException("Unable to locate Workbook stream inside .xls file.")

        val sheetNames = mutableListOf<String>()
        val sheetOffsets = mutableListOf<Int>()
        val sharedStrings = mutableListOf<String>()
        val sheetCellMaps = mutableListOf<MutableMap<Pair<Int, Int>, String>>()

        val buf = ByteBuffer.wrap(workbookStream).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        var activeSheetIdx = -1

        while (pos + 4 <= workbookStream.size) {
            val recId = buf.getShort(pos).toInt() and 0xFFFF
            val recLen = buf.getShort(pos + 2).toInt() and 0xFFFF
            val dataStart = pos + 4
            val dataEnd = min(dataStart + recLen, workbookStream.size)
            pos = dataEnd

            when (recId) {
                0x0809 -> { // BOF
                    val bofType = if (dataStart + 4 <= dataEnd) buf.getShort(dataStart + 2).toInt() and 0xFFFF else 0
                    if (bofType == 0x0010) { // Worksheet substream
                        activeSheetIdx++
                        sheetCellMaps.add(mutableMapOf())
                    }
                }
                0x0085 -> { // BOUNDSHEET
                    if (dataStart + 8 <= dataEnd) {
                        val offset = buf.getInt(dataStart)
                        val nameLen = workbookStream[dataStart + 6].toInt() and 0xFF
                        val flags = workbookStream[dataStart + 7].toInt() and 0xFF
                        val isUnicode = (flags and 0x01) != 0
                        val byteLen = if (isUnicode) nameLen * 2 else nameLen
                        val safeLen = min(byteLen, dataEnd - (dataStart + 8))
                        val name = if (safeLen > 0) {
                            if (isUnicode) String(workbookStream, dataStart + 8, safeLen, Charsets.UTF_16LE)
                            else String(workbookStream, dataStart + 8, safeLen, Charset.forName("ISO-8859-1"))
                        } else "Sheet ${sheetNames.size + 1}"
                        sheetNames.add(name)
                        sheetOffsets.add(offset)
                    }
                }
                0x00FC -> { // SST (Shared String Table) + immediate CONTINUE records
                    val chunks = mutableListOf(workbookStream.copyOfRange(dataStart, dataEnd))
                    while (pos + 4 <= workbookStream.size) {
                        val nextId = buf.getShort(pos).toInt() and 0xFFFF
                        if (nextId != 0x003C) break
                        val nextLen = buf.getShort(pos + 2).toInt() and 0xFFFF
                        val cStart = pos + 4
                        val cEnd = min(cStart + nextLen, workbookStream.size)
                        chunks.add(workbookStream.copyOfRange(cStart, cEnd))
                        pos = cEnd
                    }
                    parseBiff8Sst(chunks, sharedStrings)
                }
                0x00FD -> { // LABELSST
                    if (activeSheetIdx >= 0 && dataStart + 10 <= dataEnd) {
                        val row = buf.getShort(dataStart).toInt() and 0xFFFF
                        val col = buf.getShort(dataStart + 2).toInt() and 0xFFFF
                        val sstIdx = buf.getInt(dataStart + 6)
                        val text = sharedStrings.getOrElse(sstIdx) { "" }.trim()
                        if (text.isNotEmpty()) sheetCellMaps[activeSheetIdx][row to col] = text
                    }
                }
                0x0204 -> { // LABEL
                    if (activeSheetIdx >= 0 && dataStart + 8 <= dataEnd) {
                        val row = buf.getShort(dataStart).toInt() and 0xFFFF
                        val col = buf.getShort(dataStart + 2).toInt() and 0xFFFF
                        val len = buf.getShort(dataStart + 6).toInt() and 0xFFFF
                        val textStart = dataStart + 8
                        if (textStart < dataEnd) {
                            val flag = workbookStream[textStart].toInt() and 0xFF
                            val isUtf16 = (flag and 0x01) != 0
                            val strBytes = min(if (isUtf16) len * 2 else len, dataEnd - (textStart + 1))
                            val text = if (strBytes > 0) {
                                if (isUtf16) String(workbookStream, textStart + 1, strBytes, Charsets.UTF_16LE)
                                else String(workbookStream, textStart + 1, strBytes, Charset.forName("ISO-8859-1"))
                            } else ""
                            if (text.isNotBlank()) sheetCellMaps[activeSheetIdx][row to col] = text.trim()
                        }
                    }
                }
                0x0203 -> { // NUMBER
                    if (activeSheetIdx >= 0 && dataStart + 14 <= dataEnd) {
                        val row = buf.getShort(dataStart).toInt() and 0xFFFF
                        val col = buf.getShort(dataStart + 2).toInt() and 0xFFFF
                        val value = buf.getDouble(dataStart + 6)
                        sheetCellMaps[activeSheetIdx][row to col] = formatCleanNumber(value)
                    }
                }
                0x027E -> { // RK
                    if (activeSheetIdx >= 0 && dataStart + 10 <= dataEnd) {
                        val row = buf.getShort(dataStart).toInt() and 0xFFFF
                        val col = buf.getShort(dataStart + 2).toInt() and 0xFFFF
                        val rk = buf.getInt(dataStart + 6)
                        sheetCellMaps[activeSheetIdx][row to col] = formatCleanNumber(decodeRk(rk))
                    }
                }
                0x00BD -> { // MULRK
                    if (activeSheetIdx >= 0 && dataStart + 6 <= dataEnd) {
                        val row = buf.getShort(dataStart).toInt() and 0xFFFF
                        val firstCol = buf.getShort(dataStart + 2).toInt() and 0xFFFF
                        val count = (dataEnd - dataStart - 6) / 6
                        for (i in 0 until count) {
                            val rkPos = dataStart + 4 + i * 6 + 2
                            if (rkPos + 4 <= dataEnd) {
                                val rk = buf.getInt(rkPos)
                                sheetCellMaps[activeSheetIdx][row to (firstCol + i)] = formatCleanNumber(decodeRk(rk))
                            }
                        }
                    }
                }
                0x0006 -> { // FORMULA (cached numeric result)
                    if (activeSheetIdx >= 0 && dataStart + 14 <= dataEnd) {
                        val row = buf.getShort(dataStart).toInt() and 0xFFFF
                        val col = buf.getShort(dataStart + 2).toInt() and 0xFFFF
                        val byte6 = workbookStream[dataStart + 12].toInt() and 0xFF
                        val byte7 = workbookStream[dataStart + 13].toInt() and 0xFF
                        if (!(byte6 == 0xFF && byte7 == 0xFF)) {
                            val value = buf.getDouble(dataStart + 6)
                            if (!value.isNaN() && !value.isInfinite()) {
                                sheetCellMaps[activeSheetIdx][row to col] = formatCleanNumber(value)
                            }
                        }
                    }
                }
            }
        }

        val parsedSheets = sheetCellMaps.mapIndexed { idx, cellMap ->
            val name = sheetNames.getOrElse(idx) { "Sheet ${idx + 1}" }
            val maxRow = cellMap.keys.maxOfOrNull { it.first } ?: -1
            val maxCol = cellMap.keys.maxOfOrNull { it.second } ?: -1
            val rows = if (maxRow >= 0 && maxCol >= 0) {
                (0..maxRow).mapNotNull { r ->
                    val rowList = List(maxCol + 1) { c -> cellMap[r to c] ?: "" }
                    if (rowList.any { it.isNotBlank() }) rowList else null
                }
            } else emptyList()
            ParsedSheet(name = name, rows = rows)
        }.filter { it.rows.isNotEmpty() }

        require(parsedSheets.isNotEmpty()) { "No readable worksheet rows found in .xls file." }
        return ParsedWorkbook(fileName = fileName, format = PoFileFormat.XLS, sheets = parsedSheets)
    }

    private fun extractOle2WorkbookStream(bytes: ByteArray): ByteArray? {
        if (bytes.size < 512) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val sectorShift = buf.getShort(30).toInt() and 0xFFFF
        val sectorSize = 1 shl sectorShift
        val miniSectorShift = buf.getShort(32).toInt() and 0xFFFF
        val miniSectorSize = 1 shl miniSectorShift
        val firstDirSector = buf.getInt(48)
        val miniCutoff = buf.getInt(56)
        val firstMiniFatSector = buf.getInt(60)
        val firstDifatSector = buf.getInt(68)
        val numDifatSectors = buf.getInt(72)

        val difat = mutableListOf<Int>()
        for (i in 0 until 109) {
            val s = buf.getInt(76 + i * 4)
            if (s >= 0) difat.add(s)
        }
        var difatSec = firstDifatSector
        var remainingDifat = numDifatSectors
        while (difatSec >= 0 && remainingDifat > 0) {
            val base = (difatSec + 1) * sectorSize
            if (base + sectorSize > bytes.size) break
            val entriesPerSec = (sectorSize / 4) - 1
            for (i in 0 until entriesPerSec) {
                val s = buf.getInt(base + i * 4)
                if (s >= 0) difat.add(s)
            }
            difatSec = buf.getInt(base + entriesPerSec * 4)
            remainingDifat--
        }

        val fat = mutableListOf<Int>()
        for (sec in difat) {
            val base = (sec + 1) * sectorSize
            if (base + sectorSize > bytes.size) continue
            for (i in 0 until sectorSize / 4) {
                fat.add(buf.getInt(base + i * 4))
            }
        }

        fun readChain(startSector: Int, expectedSize: Int = -1): ByteArray {
            val out = mutableListOf<Byte>()
            var sec = startSector
            val visited = mutableSetOf<Int>()
            while (sec >= 0 && sec < fat.size && visited.add(sec)) {
                val base = (sec + 1) * sectorSize
                if (base >= bytes.size) break
                val end = min(base + sectorSize, bytes.size)
                for (i in base until end) out.add(bytes[i])
                if (expectedSize > 0 && out.size >= expectedSize) break
                sec = fat[sec]
            }
            val arr = out.toByteArray()
            return if (expectedSize > 0 && arr.size > expectedSize) arr.copyOf(expectedSize) else arr
        }

        val dirBytes = readChain(firstDirSector)
        var rootStartSec = -1
        var rootSize = 0
        var wbStartSec = -1
        var wbSize = 0

        var offset = 0
        while (offset + 128 <= dirBytes.size) {
            val dBuf = ByteBuffer.wrap(dirBytes, offset, 128).order(ByteOrder.LITTLE_ENDIAN)
            val nameBytesLen = (dBuf.getShort(64).toInt() and 0xFFFF).coerceIn(0, 64)
            val entryType = dirBytes[offset + 66].toInt() and 0xFF
            val name = if (nameBytesLen >= 2) {
                String(dirBytes, offset, nameBytesLen - 2, Charsets.UTF_16LE)
            } else ""
            val startSec = dBuf.getInt(116)
            val streamSize = dBuf.getInt(120)

            if (entryType == 5) { // Root Entry
                rootStartSec = startSec
                rootSize = streamSize
            } else if (entryType == 2 && (name.equals("Workbook", ignoreCase = true) || name.equals("Book", ignoreCase = true))) {
                wbStartSec = startSec
                wbSize = streamSize
            }
            offset += 128
        }

        if (wbStartSec < 0 || wbSize <= 0) return null
        if (wbSize >= miniCutoff || rootStartSec < 0) {
            return readChain(wbStartSec, wbSize)
        }

        // Read from MiniStream
        val miniStream = readChain(rootStartSec, rootSize)
        val miniFatBytes = readChain(firstMiniFatSector)
        val miniFatBuf = ByteBuffer.wrap(miniFatBytes).order(ByteOrder.LITTLE_ENDIAN)
        val miniFat = IntArray(miniFatBytes.size / 4) { i -> miniFatBuf.getInt(i * 4) }

        val out = ByteArray(wbSize)
        var written = 0
        var sec = wbStartSec
        val visited = mutableSetOf<Int>()
        while (sec >= 0 && sec < miniFat.size && written < wbSize && visited.add(sec)) {
            val srcPos = sec * miniSectorSize
            if (srcPos >= miniStream.size) break
            val copyLen = min(min(miniSectorSize, miniStream.size - srcPos), wbSize - written)
            System.arraycopy(miniStream, srcPos, out, written, copyLen)
            written += copyLen
            sec = miniFat[sec]
        }
        return out
    }

    private fun parseBiff8Sst(chunks: List<ByteArray>, out: MutableList<String>) {
        if (chunks.isEmpty() || chunks[0].size < 8) return
        val headerBuf = ByteBuffer.wrap(chunks[0]).order(ByteOrder.LITTLE_ENDIAN)
        val uniqueCount = headerBuf.getInt(4).coerceIn(0, 200_000)

        var chunkIdx = 0
        var offset = 8

        fun readByte(): Int? {
            while (chunkIdx < chunks.size && offset >= chunks[chunkIdx].size) {
                chunkIdx++
                offset = 0
            }
            if (chunkIdx >= chunks.size) return null
            return chunks[chunkIdx][offset++].toInt() and 0xFF
        }

        fun readShort(): Int? {
            val lo = readByte() ?: return null
            val hi = readByte() ?: return null
            return lo or (hi shl 8)
        }

        fun readInt(): Int? {
            val lo = readShort() ?: return null
            val hi = readShort() ?: return null
            return lo or (hi shl 16)
        }

        fun skipBytes(count: Int) {
            var rem = count
            while (rem > 0 && chunkIdx < chunks.size) {
                val avail = chunks[chunkIdx].size - offset
                if (rem < avail) {
                    offset += rem
                    rem = 0
                } else {
                    rem -= avail
                    chunkIdx++
                    offset = 0
                }
            }
        }

        for (i in 0 until uniqueCount) {
            val charCount = readShort() ?: break
            val flags = readByte() ?: break
            var isUtf16 = (flags and 0x01) != 0
            val hasExt = (flags and 0x04) != 0
            val hasRich = (flags and 0x08) != 0
            val richRuns = if (hasRich) (readShort() ?: 0) else 0
            val extSize = if (hasExt) (readInt() ?: 0) else 0

            val sb = StringBuilder(charCount)
            var charsRead = 0
            while (charsRead < charCount && chunkIdx < chunks.size) {
                if (offset >= chunks[chunkIdx].size) {
                    chunkIdx++
                    offset = 0
                    if (chunkIdx >= chunks.size || chunks[chunkIdx].isEmpty()) break
                    // First byte of CONTINUE record specifies encoding for continued string
                    val contFlag = chunks[chunkIdx][offset++].toInt() and 0xFF
                    isUtf16 = (contFlag and 0x01) != 0
                }
                val bytesAvail = chunks[chunkIdx].size - offset
                val charsAvail = if (isUtf16) bytesAvail / 2 else bytesAvail
                val take = min(charCount - charsRead, charsAvail)
                if (take <= 0) break
                val str = if (isUtf16) {
                    String(chunks[chunkIdx], offset, take * 2, Charsets.UTF_16LE)
                } else {
                    String(chunks[chunkIdx], offset, take, Charset.forName("ISO-8859-1"))
                }
                sb.append(str)
                offset += if (isUtf16) take * 2 else take
                charsRead += take
            }
            if (richRuns > 0) skipBytes(richRuns * 4)
            if (extSize > 0) skipBytes(extSize)
            out.add(sb.toString())
        }
    }

    private fun decodeRk(rk: Int): Double {
        val isDiv100 = (rk and 0x01) != 0
        val isInt = (rk and 0x02) != 0
        val raw = if (isInt) {
            (rk shr 2).toDouble()
        } else {
            val bits = (rk and 0xFFFFFFFC.toInt()).toLong() shl 32
            java.lang.Double.longBitsToDouble(bits)
        }
        return if (isDiv100) raw / 100.0 else raw
    }

    // -------------------------------------------------------------------------
    // 3. SpreadsheetML XML / HTML Table .xls Fallback Parser
    // -------------------------------------------------------------------------
    private fun parseXmlOrHtmlTable(fileName: String, bytes: ByteArray): ParsedWorkbook {
        val content = String(bytes, Charsets.UTF_8)
        val rows = mutableListOf<List<String>>()
        val rowRegex = Regex("<(?:tr|Row)[^>]*>(.*?)</(?:tr|Row)>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val cellRegex = Regex("<(?:td|th|Cell)[^>]*>(.*?)</(?:td|th|Cell)>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val tagStrip = Regex("<[^>]+>")

        for (rowMatch in rowRegex.findAll(content)) {
            val cells = cellRegex.findAll(rowMatch.groupValues[1]).map { cm ->
                tagStrip.replace(cm.groupValues[1], "")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&nbsp;", " ")
                    .trim()
            }.toList()
            if (cells.any { it.isNotBlank() }) rows.add(cells)
        }

        require(rows.isNotEmpty()) { "Could not read tabular rows from XML/HTML Excel file." }
        val maxCol = rows.maxOf { it.size }
        val padded = rows.map { if (it.size < maxCol) it + List(maxCol - it.size) { "" } else it }
        return ParsedWorkbook(
            fileName = fileName,
            format = PoFileFormat.XLS,
            sheets = listOf(ParsedSheet(name = "Imported Sheet", rows = padded))
        )
    }

    // -------------------------------------------------------------------------
    // 4. CSV / TSV Parser (RFC 4180)
    // -------------------------------------------------------------------------
    private fun parseCsv(fileName: String, bytes: ByteArray, format: PoFileFormat): ParsedWorkbook {
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        val delimiter = detectDelimiter(text)
        val rows = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < text.length) {
            val ch = text[i]
            when {
                inQuotes -> {
                    if (ch == '"') {
                        if (i + 1 < text.length && text[i + 1] == '"') {
                            cell.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        cell.append(ch)
                    }
                }
                ch == '"' -> inQuotes = true
                ch == delimiter -> {
                    currentRow.add(cell.toString().trim())
                    cell.setLength(0)
                }
                ch == '\r' || ch == '\n' -> {
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    currentRow.add(cell.toString().trim())
                    cell.setLength(0)
                    if (currentRow.any { it.isNotBlank() }) {
                        rows.add(currentRow)
                    }
                    currentRow = mutableListOf()
                }
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(cell.toString().trim())
            if (currentRow.any { it.isNotBlank() }) rows.add(currentRow)
        }

        require(rows.isNotEmpty()) { "No readable rows found in CSV file." }
        val maxCol = rows.maxOf { it.size }
        val padded = rows.map { if (it.size < maxCol) it + List(maxCol - it.size) { "" } else it }
        return ParsedWorkbook(
            fileName = fileName,
            format = format,
            sheets = listOf(ParsedSheet(name = "CSV Data", rows = padded))
        )
    }

    private fun detectDelimiter(text: String): Char {
        val sampleLines = text.lineSequence().filter { it.isNotBlank() }.take(5).toList()
        if (sampleLines.isEmpty()) return ','
        val candidates = listOf(',', ';', '\t')
        return candidates.maxByOrNull { delim ->
            sampleLines.sumOf { line -> line.count { it == delim } }
        }?.takeIf { delim -> sampleLines.any { it.contains(delim) } } ?: ','
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private fun localTag(rawName: String?): String =
        rawName?.substringAfter(':') ?: ""

    private fun columnRefToIndex(ref: String): Int {
        var col = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') {
                col = col * 26 + (ch - 'A' + 1)
            } else if (ch in 'a'..'z') {
                col = col * 26 + (ch - 'a' + 1)
            } else {
                break
            }
        }
        return (col - 1).coerceAtLeast(0)
    }

    private fun formatCleanNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return ""
        val rounded = floor(value + 0.5)
        return if (abs(value - rounded) < 1e-7 && abs(value) < 1e13) {
            rounded.toLong().toString()
        } else {
            DecimalFormat("0.####").format(value)
        }
    }

    private fun excelSerialToIsoDate(serial: Double): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(1899, Calendar.DECEMBER, 30, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, serial.toInt())
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(cal.time)
    }
}
