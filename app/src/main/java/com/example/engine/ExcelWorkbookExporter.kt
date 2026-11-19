package com.example.engine

import com.example.model.PurchaseOrderRecord
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pure-Kotlin OOXML (.xlsx) workbook generator producing a professionally formatted,
 * editable 6-worksheet Excel file for Laila Styles Limited:
 *  Sheet 1: PO Summary
 *  Sheet 2: Style Breakdown
 *  Sheet 3: Size Breakdown (with dynamic size columns & SUM formulas)
 *  Sheet 4: Color Breakdown
 *  Sheet 5: Delivery Breakdown
 *  Sheet 6: Validation Report
 */
object ExcelWorkbookExporter {

    private sealed class CellVal {
        data class Str(val value: String, val style: Int = STYLE_BODY) : CellVal()
        data class Num(val value: Double, val style: Int = STYLE_INT) : CellVal()
        data class Formula(val formula: String, val cached: Double, val style: Int = STYLE_INT) : CellVal()
    }

    // Style indices mapped in xl/styles.xml
    private const val STYLE_BODY = 0
    private const val STYLE_TITLE = 1
    private const val STYLE_SUBTITLE = 2
    private const val STYLE_HEADER = 3
    private const val STYLE_ZEBRA = 4
    private const val STYLE_INT = 5
    private const val STYLE_INT_ZEBRA = 6
    private const val STYLE_CURRENCY = 7
    private const val STYLE_CURRENCY_ZEBRA = 8
    private const val STYLE_TOTAL_LABEL = 9
    private const val STYLE_TOTAL_INT = 10
    private const val STYLE_TOTAL_CURRENCY = 11
    private const val STYLE_LABEL_BOLD = 12
    private const val STYLE_WARN = 13
    private const val STYLE_ERROR = 14
    private const val STYLE_PERCENT = 15

    fun exportToStream(
        po: PurchaseOrderRecord,
        companyName: String,
        outputStream: OutputStream
    ) {
        val sheets = listOf(
            "PO Summary" to buildSummarySheet(po, companyName),
            "Style Breakdown" to buildStyleBreakdownSheet(po, companyName),
            "Size Breakdown" to buildSizeBreakdownSheet(po, companyName),
            "Color Breakdown" to buildColorBreakdownSheet(po, companyName),
            "Delivery Breakdown" to buildDeliveryBreakdownSheet(po, companyName),
            "Validation Report" to buildValidationReportSheet(po, companyName)
        )

        ZipOutputStream(outputStream).use { zip ->
            writeEntry(zip, "[Content_Types].xml", buildContentTypesXml(sheets.size))
            writeEntry(zip, "_rels/.rels", buildRootRelsXml())
            writeEntry(zip, "xl/workbook.xml", buildWorkbookXml(sheets.map { it.first }))
            writeEntry(zip, "xl/_rels/workbook.xml.rels", buildWorkbookRelsXml(sheets.size))
            writeEntry(zip, "xl/styles.xml", buildStylesXml())
            sheets.forEachIndexed { idx, (_, xml) ->
                writeEntry(zip, "xl/worksheets/sheet${idx + 1}.xml", xml)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Sheet 1: PO Summary
    // -------------------------------------------------------------------------
    private fun buildSummarySheet(po: PurchaseOrderRecord, companyName: String): String {
        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val rows = mutableListOf<List<CellVal>>()
        rows.add(listOf(CellVal.Str(companyName.uppercase(Locale.US), STYLE_TITLE)))
        rows.add(listOf(CellVal.Str("STANDARDIZED PO SUMMARY REPORT  |  Generated: $nowStr", STYLE_SUBTITLE)))
        rows.add(emptyList())
        rows.add(
            listOf(
                CellVal.Str("Field", STYLE_HEADER),
                CellVal.Str("Standardized LSL Value", STYLE_HEADER),
                CellVal.Str("Reference / Notes", STYLE_HEADER)
            )
        )

        val kvRows = listOf(
            Triple("Buyer Name", po.buyerName, "Extracted from buyer PO"),
            Triple("PO Number", po.poNumber, "Unique PO identifier"),
            Triple("PO Date", po.poDate.ifBlank { "-" }, "Buyer order issue date"),
            Triple("Delivery Date", po.deliveryDate.ifBlank { "-" }, "Primary ex-factory / delivery date"),
            Triple("Ship Date", po.shipDate.ifBlank { "-" }, "Planned shipment / ETD"),
            Triple("Destination", po.destination.ifBlank { "-" }, "Port / destination market"),
            Triple("Factory Information", po.factoryInfo.ifBlank { "-" }, "Production unit"),
            Triple("Fabric & Details", po.fabricDetails.ifBlank { "-" }, "Fabrication & remarks"),
            Triple("Currency", po.currency, "Pricing currency"),
            Triple("Workflow Status", po.status.label, "LSL verification status"),
            Triple("Original Buyer File", po.originalFileName, "Preserved source (${po.originalFileFormat.name})"),
            Triple("Original Worksheet", po.originalSheetName.ifBlank { "-" }, "Source tab name"),
            Triple("Size Range", po.sizeColumns.joinToString(", ").ifBlank { "-" }, "${po.sizeColumns.size} dynamic size columns")
        )

        kvRows.forEachIndexed { idx, (k, v, note) ->
            val st = if (idx % 2 == 1) STYLE_ZEBRA else STYLE_BODY
            rows.add(listOf(CellVal.Str(k, STYLE_LABEL_BOLD), CellVal.Str(v, st), CellVal.Str(note, st)))
        }

        rows.add(emptyList())
        rows.add(
            listOf(
                CellVal.Str("Key Metric", STYLE_HEADER),
                CellVal.Str("Calculated Total", STYLE_HEADER),
                CellVal.Str("Formula Source", STYLE_HEADER)
            )
        )
        rows.add(
            listOf(
                CellVal.Str("Total Styles", STYLE_LABEL_BOLD),
                CellVal.Num(po.styleCount.toDouble(), STYLE_INT),
                CellVal.Str("Distinct style numbers", STYLE_BODY)
            )
        )
        rows.add(
            listOf(
                CellVal.Str("Total Colors", STYLE_LABEL_BOLD),
                CellVal.Num(po.colorCount.toDouble(), STYLE_INT),
                CellVal.Str("Distinct colorways", STYLE_BODY)
            )
        )
        val styleCount = PoTransferEngine.computeStyleBreakdown(po).size
        val styleLastRow = 4 + styleCount
        rows.add(
            listOf(
                CellVal.Str("Total PO Quantity (Pcs)", STYLE_TOTAL_LABEL),
                if (styleCount > 0) CellVal.Formula("SUM('Style Breakdown'!E5:E$styleLastRow)", po.totalQuantity.toDouble(), STYLE_TOTAL_INT)
                else CellVal.Num(po.totalQuantity.toDouble(), STYLE_TOTAL_INT),
                CellVal.Str("Linked to Style Breakdown", STYLE_TOTAL_LABEL)
            )
        )
        rows.add(
            listOf(
                CellVal.Str("Total PO Value (${po.currency})", STYLE_TOTAL_LABEL),
                if (styleCount > 0) CellVal.Formula("SUM('Style Breakdown'!G5:G$styleLastRow)", po.totalValue, STYLE_TOTAL_CURRENCY)
                else CellVal.Num(po.totalValue, STYLE_TOTAL_CURRENCY),
                CellVal.Str("Linked to Style Breakdown", STYLE_TOTAL_LABEL)
            )
        )

        return buildWorksheetXml(
            rows = rows,
            colWidths = listOf(28.0, 38.0, 36.0),
            frozenRows = 4,
            autoFilterRange = "A4:C17",
            mergedRanges = listOf("A1:C1", "A2:C2")
        )
    }

    // -------------------------------------------------------------------------
    // Sheet 2: Style Breakdown
    // -------------------------------------------------------------------------
    private fun buildStyleBreakdownSheet(po: PurchaseOrderRecord, companyName: String): String {
        val breakdown = PoTransferEngine.computeStyleBreakdown(po)
        val rows = mutableListOf<List<CellVal>>()
        rows.add(listOf(CellVal.Str("$companyName — STYLE BREAKDOWN (PO: ${po.poNumber})", STYLE_TITLE)))
        rows.add(listOf(CellVal.Str("Buyer: ${po.buyerName}  |  Currency: ${po.currency}  |  Delivery: ${po.deliveryDate.ifBlank { "-" }}", STYLE_SUBTITLE)))
        rows.add(emptyList())
        rows.add(
            listOf(
                CellVal.Str("Style Number", STYLE_HEADER),
                CellVal.Str("Description", STYLE_HEADER),
                CellVal.Str("Color(s)", STYLE_HEADER),
                CellVal.Str("Garment Type", STYLE_HEADER),
                CellVal.Str("PO Quantity", STYLE_HEADER),
                CellVal.Str("Unit Price (${po.currency})", STYLE_HEADER),
                CellVal.Str("Total Value (${po.currency})", STYLE_HEADER)
            )
        )

        breakdown.forEachIndexed { idx, item ->
            val excelRow = 5 + idx
            val zebra = idx % 2 == 1
            val txtSt = if (zebra) STYLE_ZEBRA else STYLE_BODY
            val intSt = if (zebra) STYLE_INT_ZEBRA else STYLE_INT
            val curSt = if (zebra) STYLE_CURRENCY_ZEBRA else STYLE_CURRENCY
            rows.add(
                listOf(
                    CellVal.Str(item.styleNumber, txtSt),
                    CellVal.Str(item.description, txtSt),
                    CellVal.Str(item.colors, txtSt),
                    CellVal.Str(item.garmentType, txtSt),
                    CellVal.Num(item.poQuantity.toDouble(), intSt),
                    CellVal.Num(item.avgUnitPrice, curSt),
                    CellVal.Formula("E$excelRow*F$excelRow", item.totalValue, curSt)
                )
            )
        }

        val lastDataRow = 4 + breakdown.size
        rows.add(
            listOf(
                CellVal.Str("GRAND TOTAL", STYLE_TOTAL_LABEL),
                CellVal.Str("${breakdown.size} Styles", STYLE_TOTAL_LABEL),
                CellVal.Str("", STYLE_TOTAL_LABEL),
                CellVal.Str("", STYLE_TOTAL_LABEL),
                if (breakdown.isNotEmpty()) CellVal.Formula("SUM(E5:E$lastDataRow)", po.totalQuantity.toDouble(), STYLE_TOTAL_INT)
                else CellVal.Num(0.0, STYLE_TOTAL_INT),
                CellVal.Str("", STYLE_TOTAL_LABEL),
                if (breakdown.isNotEmpty()) CellVal.Formula("SUM(G5:G$lastDataRow)", po.totalValue, STYLE_TOTAL_CURRENCY)
                else CellVal.Num(0.0, STYLE_TOTAL_CURRENCY)
            )
        )

        return buildWorksheetXml(
            rows = rows,
            colWidths = listOf(18.0, 32.0, 24.0, 18.0, 15.0, 16.0, 18.0),
            frozenRows = 4,
            autoFilterRange = if (breakdown.isNotEmpty()) "A4:G$lastDataRow" else "A4:G4",
            mergedRanges = listOf("A1:G1", "A2:G2")
        )
    }

    // -------------------------------------------------------------------------
    // Sheet 3: Size Breakdown (Dynamic Size Matrix + Row/Col SUM Formulas)
    // -------------------------------------------------------------------------
    private fun buildSizeBreakdownSheet(po: PurchaseOrderRecord, companyName: String): String {
        val sizes = po.sizeColumns
        val totalColIdx = 2 + sizes.size // 0=Style, 1=Color, 2..2+sizes-1=Sizes, 2+sizes=Total
        val lastColLetter = colIndexToLetter(totalColIdx)

        val rows = mutableListOf<List<CellVal>>()
        rows.add(listOf(CellVal.Str("$companyName — SIZE BREAKDOWN MATRIX (PO: ${po.poNumber})", STYLE_TITLE)))
        rows.add(listOf(CellVal.Str("Buyer: ${po.buyerName}  |  Dynamic Sizes: ${sizes.joinToString(", ").ifBlank { "None" }}", STYLE_SUBTITLE)))
        rows.add(emptyList())

        val headerRow = mutableListOf<CellVal>(
            CellVal.Str("Style", STYLE_HEADER),
            CellVal.Str("Color", STYLE_HEADER)
        )
        sizes.forEach { sz -> headerRow.add(CellVal.Str(sz, STYLE_HEADER)) }
        headerRow.add(CellVal.Str("Total", STYLE_HEADER))
        rows.add(headerRow)

        val firstSizeLetter = colIndexToLetter(2)
        val lastSizeLetter = if (sizes.isNotEmpty()) colIndexToLetter(1 + sizes.size) else firstSizeLetter

        po.lineItems.forEachIndexed { idx, line ->
            val excelRow = 5 + idx
            val zebra = idx % 2 == 1
            val txtSt = if (zebra) STYLE_ZEBRA else STYLE_BODY
            val intSt = if (zebra) STYLE_INT_ZEBRA else STYLE_INT

            val r = mutableListOf<CellVal>(
                CellVal.Str(line.styleNumber.ifBlank { "(Missing)" }, txtSt),
                CellVal.Str(line.color.ifBlank { "-" }, txtSt)
            )
            sizes.forEach { sz ->
                r.add(CellVal.Num((line.sizeQuantities[sz] ?: 0L).toDouble(), intSt))
            }
            if (sizes.isNotEmpty()) {
                r.add(CellVal.Formula("SUM($firstSizeLetter$excelRow:$lastSizeLetter$excelRow)", line.sizeTotal.toDouble(), intSt))
            } else {
                r.add(CellVal.Num(line.effectiveQty.toDouble(), intSt))
            }
            rows.add(r)
        }

        val lastDataRow = 4 + po.lineItems.size
        val sizeTotals = PoTransferEngine.computeSizeColumnTotals(po)
        val grandRow = mutableListOf<CellVal>(
            CellVal.Str("GRAND TOTAL", STYLE_TOTAL_LABEL),
            CellVal.Str("${po.lineItems.size} Lines", STYLE_TOTAL_LABEL)
        )
        sizes.forEachIndexed { sIdx, sz ->
            val colLetter = colIndexToLetter(2 + sIdx)
            val cached = (sizeTotals[sz] ?: 0L).toDouble()
            if (po.lineItems.isNotEmpty()) {
                grandRow.add(CellVal.Formula("SUM(${colLetter}5:${colLetter}$lastDataRow)", cached, STYLE_TOTAL_INT))
            } else {
                grandRow.add(CellVal.Num(0.0, STYLE_TOTAL_INT))
            }
        }
        val grandCached = if (sizes.isNotEmpty()) sizeTotals.values.sum().toDouble() else po.totalQuantity.toDouble()
        grandRow.add(
            if (po.lineItems.isNotEmpty()) CellVal.Formula("SUM(${lastColLetter}5:${lastColLetter}$lastDataRow)", grandCached, STYLE_TOTAL_INT)
            else CellVal.Num(0.0, STYLE_TOTAL_INT)
        )
        rows.add(grandRow)

        val widths = mutableListOf(18.0, 20.0)
        sizes.forEach { sz -> widths.add(maxOf(10.0, sz.length + 4.0)) }
        widths.add(14.0)

        return buildWorksheetXml(
            rows = rows,
            colWidths = widths,
            frozenRows = 4,
            autoFilterRange = if (po.lineItems.isNotEmpty()) "A4:$lastColLetter$lastDataRow" else "A4:${lastColLetter}4",
            mergedRanges = listOf("A1:${lastColLetter}1", "A2:${lastColLetter}2")
        )
    }

    // -------------------------------------------------------------------------
    // Sheet 4: Color Breakdown
    // -------------------------------------------------------------------------
    private fun buildColorBreakdownSheet(po: PurchaseOrderRecord, companyName: String): String {
        val colorRows = PoTransferEngine.computeColorBreakdown(po)
        val rows = mutableListOf<List<CellVal>>()
        rows.add(listOf(CellVal.Str("$companyName — COLOR BREAKDOWN (PO: ${po.poNumber})", STYLE_TITLE)))
        rows.add(listOf(CellVal.Str("Style-wise and Color-wise Quantity Distribution", STYLE_SUBTITLE)))
        rows.add(emptyList())
        rows.add(
            listOf(
                CellVal.Str("Style Number", STYLE_HEADER),
                CellVal.Str("Color", STYLE_HEADER),
                CellVal.Str("Garment Type", STYLE_HEADER),
                CellVal.Str("Quantity (Pcs)", STYLE_HEADER),
                CellVal.Str("Share %", STYLE_HEADER),
                CellVal.Str("Total Value (${po.currency})", STYLE_HEADER)
            )
        )

        colorRows.forEachIndexed { idx, item ->
            val zebra = idx % 2 == 1
            val txtSt = if (zebra) STYLE_ZEBRA else STYLE_BODY
            val intSt = if (zebra) STYLE_INT_ZEBRA else STYLE_INT
            val curSt = if (zebra) STYLE_CURRENCY_ZEBRA else STYLE_CURRENCY
            rows.add(
                listOf(
                    CellVal.Str(item.styleNumber, txtSt),
                    CellVal.Str(item.color, txtSt),
                    CellVal.Str(item.garmentType, txtSt),
                    CellVal.Num(item.quantity.toDouble(), intSt),
                    CellVal.Num(item.sharePercent / 100.0, STYLE_PERCENT),
                    CellVal.Num(item.totalValue, curSt)
                )
            )
        }

        val lastDataRow = 4 + colorRows.size
        rows.add(
            listOf(
                CellVal.Str("GRAND TOTAL", STYLE_TOTAL_LABEL),
                CellVal.Str("${colorRows.size} Style-Color Combos", STYLE_TOTAL_LABEL),
                CellVal.Str("", STYLE_TOTAL_LABEL),
                if (colorRows.isNotEmpty()) CellVal.Formula("SUM(D5:D$lastDataRow)", po.totalQuantity.toDouble(), STYLE_TOTAL_INT)
                else CellVal.Num(0.0, STYLE_TOTAL_INT),
                CellVal.Num(if (colorRows.isNotEmpty()) 1.0 else 0.0, STYLE_PERCENT),
                if (colorRows.isNotEmpty()) CellVal.Formula("SUM(F5:F$lastDataRow)", po.totalValue, STYLE_TOTAL_CURRENCY)
                else CellVal.Num(0.0, STYLE_TOTAL_CURRENCY)
            )
        )

        return buildWorksheetXml(
            rows = rows,
            colWidths = listOf(18.0, 22.0, 18.0, 16.0, 12.0, 18.0),
            frozenRows = 4,
            autoFilterRange = if (colorRows.isNotEmpty()) "A4:F$lastDataRow" else "A4:F4",
            mergedRanges = listOf("A1:F1", "A2:F2")
        )
    }

    // -------------------------------------------------------------------------
    // Sheet 5: Delivery Breakdown
    // -------------------------------------------------------------------------
    private fun buildDeliveryBreakdownSheet(po: PurchaseOrderRecord, companyName: String): String {
        val deliveries = PoTransferEngine.computeDeliveryBreakdown(po)
        val rows = mutableListOf<List<CellVal>>()
        rows.add(listOf(CellVal.Str("$companyName — DELIVERY & SHIPMENT BREAKDOWN (PO: ${po.poNumber})", STYLE_TITLE)))
        rows.add(listOf(CellVal.Str("Shipment schedule grouped by Delivery Date, Ship Date & Destination", STYLE_SUBTITLE)))
        rows.add(emptyList())
        rows.add(
            listOf(
                CellVal.Str("Delivery Date", STYLE_HEADER),
                CellVal.Str("Ship Date", STYLE_HEADER),
                CellVal.Str("Destination", STYLE_HEADER),
                CellVal.Str("Factory Info", STYLE_HEADER),
                CellVal.Str("Styles", STYLE_HEADER),
                CellVal.Str("PO Quantity", STYLE_HEADER),
                CellVal.Str("Total Value (${po.currency})", STYLE_HEADER)
            )
        )

        deliveries.forEachIndexed { idx, item ->
            val zebra = idx % 2 == 1
            val txtSt = if (zebra) STYLE_ZEBRA else STYLE_BODY
            val intSt = if (zebra) STYLE_INT_ZEBRA else STYLE_INT
            val curSt = if (zebra) STYLE_CURRENCY_ZEBRA else STYLE_CURRENCY
            rows.add(
                listOf(
                    CellVal.Str(item.deliveryDate, txtSt),
                    CellVal.Str(item.shipDate, txtSt),
                    CellVal.Str(item.destination, txtSt),
                    CellVal.Str(item.factoryInfo, txtSt),
                    CellVal.Num(item.styleCount.toDouble(), intSt),
                    CellVal.Num(item.quantity.toDouble(), intSt),
                    CellVal.Num(item.totalValue, curSt)
                )
            )
        }

        val lastDataRow = 4 + deliveries.size
        rows.add(
            listOf(
                CellVal.Str("GRAND TOTAL", STYLE_TOTAL_LABEL),
                CellVal.Str("", STYLE_TOTAL_LABEL),
                CellVal.Str("${deliveries.size} Shipment Groups", STYLE_TOTAL_LABEL),
                CellVal.Str("", STYLE_TOTAL_LABEL),
                CellVal.Num(po.styleCount.toDouble(), STYLE_TOTAL_INT),
                if (deliveries.isNotEmpty()) CellVal.Formula("SUM(F5:F$lastDataRow)", po.totalQuantity.toDouble(), STYLE_TOTAL_INT)
                else CellVal.Num(0.0, STYLE_TOTAL_INT),
                if (deliveries.isNotEmpty()) CellVal.Formula("SUM(G5:G$lastDataRow)", po.totalValue, STYLE_TOTAL_CURRENCY)
                else CellVal.Num(0.0, STYLE_TOTAL_CURRENCY)
            )
        )

        return buildWorksheetXml(
            rows = rows,
            colWidths = listOf(16.0, 16.0, 26.0, 24.0, 11.0, 15.0, 18.0),
            frozenRows = 4,
            autoFilterRange = if (deliveries.isNotEmpty()) "A4:G$lastDataRow" else "A4:G4",
            mergedRanges = listOf("A1:G1", "A2:G2")
        )
    }

    // -------------------------------------------------------------------------
    // Sheet 6: Validation Report
    // -------------------------------------------------------------------------
    private fun buildValidationReportSheet(po: PurchaseOrderRecord, companyName: String): String {
        val rows = mutableListOf<List<CellVal>>()
        rows.add(listOf(CellVal.Str("$companyName — DATA VALIDATION REPORT (PO: ${po.poNumber})", STYLE_TITLE)))
        rows.add(listOf(CellVal.Str("Checks: Size vs Style totals, Color/Style totals, Missing fields, Duplicate POs & Unreadable values", STYLE_SUBTITLE)))
        rows.add(emptyList())
        rows.add(
            listOf(
                CellVal.Str("Severity", STYLE_HEADER),
                CellVal.Str("Check Code", STYLE_HEADER),
                CellVal.Str("Field / Location", STYLE_HEADER),
                CellVal.Str("Validation Message", STYLE_HEADER)
            )
        )

        if (po.validationWarnings.isEmpty()) {
            rows.add(
                listOf(
                    CellVal.Str("PASS", STYLE_TOTAL_LABEL),
                    CellVal.Str("ALL_CHECKS_PASSED", STYLE_TOTAL_LABEL),
                    CellVal.Str("Entire PO", STYLE_TOTAL_LABEL),
                    CellVal.Str("Zero discrepancies detected. All size, color, style, and overall PO totals reconcile.", STYLE_TOTAL_LABEL)
                )
            )
        } else {
            po.validationWarnings.forEach { w ->
                val sevStyle = if (w.severity.name == "ERROR") STYLE_ERROR else STYLE_WARN
                rows.add(
                    listOf(
                        CellVal.Str(w.severity.label.uppercase(Locale.US), sevStyle),
                        CellVal.Str(w.code, STYLE_BODY),
                        CellVal.Str(w.field, STYLE_BODY),
                        CellVal.Str(w.message, STYLE_BODY)
                    )
                )
            }
        }

        return buildWorksheetXml(
            rows = rows,
            colWidths = listOf(14.0, 28.0, 24.0, 66.0),
            frozenRows = 4,
            autoFilterRange = "A4:D${maxOf(4, 4 + po.validationWarnings.size)}",
            mergedRanges = listOf("A1:D1", "A2:D2")
        )
    }

    // -------------------------------------------------------------------------
    // OOXML Builders
    // -------------------------------------------------------------------------
    private fun buildWorksheetXml(
        rows: List<List<CellVal>>,
        colWidths: List<Double>,
        frozenRows: Int,
        autoFilterRange: String?,
        mergedRanges: List<String>
    ): String {
        val sb = StringBuilder(4096)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")

        sb.append("<sheetViews><sheetView workbookViewId=\"0\">")
        if (frozenRows > 0) {
            sb.append("<pane ySplit=\"$frozenRows\" topLeftCell=\"A${frozenRows + 1}\" activePane=\"bottomLeft\" state=\"frozen\"/>")
        }
        sb.append("</sheetView></sheetViews>")

        sb.append("<sheetFormatPr defaultRowHeight=\"20\"/>")

        if (colWidths.isNotEmpty()) {
            sb.append("<cols>")
            colWidths.forEachIndexed { idx, width ->
                val colNum = idx + 1
                sb.append("<col min=\"$colNum\" max=\"$colNum\" width=\"$width\" customWidth=\"1\"/>")
            }
            sb.append("</cols>")
        }

        sb.append("<sheetData>")
        rows.forEachIndexed { rowIdx, cells ->
            val rNum = rowIdx + 1
            val heightAttr = when (rNum) {
                1 -> " ht=\"28\" customHeight=\"1\""
                2 -> " ht=\"20\" customHeight=\"1\""
                4 -> " ht=\"24\" customHeight=\"1\""
                else -> ""
            }
            sb.append("<row r=\"$rNum\"$heightAttr>")
            cells.forEachIndexed { colIdx, cell ->
                val ref = "${colIndexToLetter(colIdx)}$rNum"
                when (cell) {
                    is CellVal.Str -> {
                        sb.append("<c r=\"$ref\" t=\"inlineStr\" s=\"${cell.style}\"><is><t>")
                        sb.append(escapeXml(cell.value))
                        sb.append("</t></is></c>")
                    }
                    is CellVal.Num -> {
                        sb.append("<c r=\"$ref\" s=\"${cell.style}\"><v>${cell.value}</v></c>")
                    }
                    is CellVal.Formula -> {
                        sb.append("<c r=\"$ref\" s=\"${cell.style}\"><f>${escapeXml(cell.formula)}</f><v>${cell.cached}</v></c>")
                    }
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData>")

        if (!autoFilterRange.isNullOrBlank()) {
            sb.append("<autoFilter ref=\"$autoFilterRange\"/>")
        }

        if (mergedRanges.isNotEmpty()) {
            sb.append("<mergeCells count=\"${mergedRanges.size}\">")
            mergedRanges.forEach { range ->
                sb.append("<mergeCell ref=\"$range\"/>")
            }
            sb.append("</mergeCells>")
        }

        sb.append("<pageMargins left=\"0.5\" right=\"0.5\" top=\"0.6\" bottom=\"0.6\" header=\"0.3\" footer=\"0.3\"/>")
        sb.append("<pageSetup orientation=\"landscape\" paperSize=\"9\"/>")
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun buildStylesXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <numFmts count="3">
    <numFmt numFmtId="164" formatCode="#,##0"/>
    <numFmt numFmtId="165" formatCode="#,##0.00"/>
    <numFmt numFmtId="166" formatCode="0.0%"/>
  </numFmts>
  <fonts count="7">
    <font><sz val="10"/><color rgb="FF102017"/><name val="Calibri"/></font>
    <font><b/><sz val="14"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>
    <font><sz val="10"/><color rgb="FF124F2D"/><name val="Calibri"/></font>
    <font><b/><sz val="10"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>
    <font><b/><sz val="10"/><color rgb="FF0B371F"/><name val="Calibri"/></font>
    <font><b/><sz val="10"/><color rgb="FF9A5B00"/><name val="Calibri"/></font>
    <font><b/><sz val="10"/><color rgb="FFB42318"/><name val="Calibri"/></font>
  </fonts>
  <fills count="8">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FF124F2D"/><bgColor indexed="64"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFE6F4EC"/><bgColor indexed="64"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFF3F5F4"/><bgColor indexed="64"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFD9EFE2"/><bgColor indexed="64"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFFFF7E6"/><bgColor indexed="64"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFFDECEC"/><bgColor indexed="64"/></patternFill></fill>
  </fills>
  <borders count="3">
    <border><left/><right/><top/><bottom/><diagonal/></border>
    <border>
      <left style="thin"><color rgb="FFD5DDD8"/></left>
      <right style="thin"><color rgb="FFD5DDD8"/></right>
      <top style="thin"><color rgb="FFD5DDD8"/></top>
      <bottom style="thin"><color rgb="FFD5DDD8"/></bottom>
      <diagonal/>
    </border>
    <border>
      <left style="thin"><color rgb="FF124F2D"/></left>
      <right style="thin"><color rgb="FF124F2D"/></right>
      <top style="medium"><color rgb="FF124F2D"/></top>
      <bottom style="double"><color rgb="FF124F2D"/></bottom>
      <diagonal/>
    </border>
  </borders>
  <cellStyleXfs count="1">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
  </cellStyleXfs>
  <cellXfs count="16">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment horizontal="left" vertical="center"/></xf>
    <xf numFmtId="0" fontId="2" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="left" vertical="center"/></xf>
    <xf numFmtId="0" fontId="3" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="0" fillId="4" borderId="1" xfId="0" applyFill="1" applyBorder="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>
    <xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1" applyAlignment="1"><alignment horizontal="right" vertical="center"/></xf>
    <xf numFmtId="164" fontId="0" fillId="4" borderId="1" xfId="0" applyNumberFormat="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="right" vertical="center"/></xf>
    <xf numFmtId="165" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1" applyAlignment="1"><alignment horizontal="right" vertical="center"/></xf>
    <xf numFmtId="165" fontId="0" fillId="4" borderId="1" xfId="0" applyNumberFormat="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="right" vertical="center"/></xf>
    <xf numFmtId="0" fontId="4" fillId="5" borderId="2" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="left" vertical="center"/></xf>
    <xf numFmtId="164" fontId="4" fillId="5" borderId="2" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="right" vertical="center"/></xf>
    <xf numFmtId="165" fontId="4" fillId="5" borderId="2" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="right" vertical="center"/></xf>
    <xf numFmtId="0" fontId="4" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="left" vertical="center"/></xf>
    <xf numFmtId="0" fontId="5" fillId="6" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
    <xf numFmtId="0" fontId="6" fillId="7" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
    <xf numFmtId="166" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1" applyAlignment="1"><alignment horizontal="right" vertical="center"/></xf>
  </cellXfs>
</styleSheet>"""

    private fun buildContentTypesXml(sheetCount: Int): String {
        val overrides = (1..sheetCount).joinToString("") { i ->
            "<Override PartName=\"/xl/worksheets/sheet$i.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
  $overrides
</Types>"""
    }

    private fun buildRootRelsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private fun buildWorkbookXml(sheetNames: List<String>): String {
        val sheetsTag = sheetNames.mapIndexed { idx, name ->
            "<sheet name=\"${escapeXml(name.take(31))}\" sheetId=\"${idx + 1}\" r:id=\"rId${idx + 1}\"/>"
        }.joinToString("")
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>$sheetsTag</sheets>
</workbook>"""
    }

    private fun buildWorkbookRelsXml(sheetCount: Int): String {
        val sheetRels = (1..sheetCount).joinToString("") { i ->
            "<Relationship Id=\"rId$i\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet$i.xml\"/>"
        }
        val styleId = sheetCount + 1
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  $sheetRels
  <Relationship Id="rId$styleId" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun colIndexToLetter(colIndex: Int): String {
        var n = colIndex + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.append(('A'.code + rem).toChar())
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    private fun escapeXml(input: String): String = input
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
