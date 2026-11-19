package com.example.engine

import com.example.model.ColorBreakdownRow
import com.example.model.ColumnMapping
import com.example.model.DeliveryBreakdownRow
import com.example.model.ExtractedMetadata
import com.example.model.OriginalRowSnapshot
import com.example.model.OriginalSourceSnapshot
import com.example.model.ParsedSheet
import com.example.model.PoFileFormat
import com.example.model.PoLineItem
import com.example.model.PoStatus
import com.example.model.PoValidationWarning
import com.example.model.PurchaseOrderRecord
import com.example.model.StyleBreakdownRow
import com.example.model.ValidationSeverity
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * Flexible PO Header Detection, Dynamic Size Recognition, Transfer, Validation & Breakdown Engine.
 * Never silently alters original buyer quantities; flags every discrepancy clearly.
 */
object PoTransferEngine {

    private val alphaSizeRegex = Regex(
        "^(XXS|XS|S|M|L|XL|XXL|2XL|3XL|4XL|5XL|6XL|XXXL|OS|ONE\\s*SIZE|FREE|S/M|M/L|L/XL|XS/S|XL/XXL)$",
        RegexOption.IGNORE_CASE
    )
    private val kidsSizeRegex = Regex(
        "^(\\d{1,2}\\s*(M|MO|MOS|Y|YR|YRS|T)|\\d{1,2}-\\d{1,2}\\s*(M|Y|T)|\\d{2,3}/\\d{2,3}|NB|NEWBORN)$",
        RegexOption.IGNORE_CASE
    )
    private val numericGarmentSizeRegex = Regex(
        "^((2[0-9]|3[0-9]|4[0-9]|5[0-9]|6[0-4])([RLTS]|X\\d{2})?|0|2|4|6|8|10|12|14|16|18|20|22)$",
        RegexOption.IGNORE_CASE
    )
    private val explicitSizeHeaderRegex = Regex(
        "^(SZ|SIZE)[\\s_\\-:#]*([A-Z0-9/\\-]+)$",
        RegexOption.IGNORE_CASE
    )

    private val knownHeaderKeywords = setOf(
        "style", "style no", "style number", "style #", "art", "article", "color", "colour",
        "colorway", "shade", "qty", "quantity", "total", "total qty", "order qty", "size",
        "unit price", "price", "fob", "rate", "description", "item", "garment", "delivery",
        "ship date", "etd", "ex-factory", "destination", "port", "country", "po", "po no",
        "po number", "buyer", "fabric", "factory", "currency"
    )

    fun analyzeSheet(
        sheet: ParsedSheet,
        defaultCurrency: String = "USD"
    ): Pair<ExtractedMetadata, ColumnMapping> {
        val rows = sheet.rows
        if (rows.isEmpty()) return ExtractedMetadata(currency = defaultCurrency) to ColumnMapping()

        val headerRowIdx = detectHeaderRowIndex(rows)
        val headers = rows.getOrElse(headerRowIdx) { emptyList() }
        val metadata = extractTopBlockMetadata(rows, headerRowIdx, defaultCurrency)
        val mapping = autoDetectColumnMapping(headers, headerRowIdx, rows)
        return metadata to mapping
    }

    fun detectHeaderRowIndex(rows: List<List<String>>): Int {
        var bestRow = 0
        var bestScore = -1
        val limit = minOf(rows.size, 25)
        for (r in 0 until limit) {
            val row = rows[r]
            val nonBlank = row.count { it.isNotBlank() }
            if (nonBlank < 2) continue
            var score = 0
            for (cell in row) {
                val norm = normalizeHeader(cell)
                if (norm.isBlank()) continue
                if (knownHeaderKeywords.any { norm == it || norm.contains(it) }) {
                    score += 4
                }
                if (isLikelySizeHeader(cell)) {
                    score += 3
                }
            }
            if (nonBlank >= 4) score += 2
            if (score > bestScore) {
                bestScore = score
                bestRow = r
            }
        }
        return bestRow
    }

    private fun extractTopBlockMetadata(
        rows: List<List<String>>,
        headerRowIdx: Int,
        defaultCurrency: String
    ): ExtractedMetadata {
        var buyer = ""
        var poNumber = ""
        var poDate = ""
        var deliveryDate = ""
        var shipDate = ""
        var destination = ""
        var factory = ""
        var fabric = ""
        var currency = defaultCurrency

        fun assignFromPair(rawKey: String, rawValue: String) {
            val key = normalizeHeader(rawKey)
            val value = rawValue.trim()
            if (key.isBlank() || value.isBlank()) return
            when {
                buyer.isBlank() && (key.contains("buyer") || key.contains("customer") || key.contains("brand") || key.contains("client")) ->
                    buyer = value
                poNumber.isBlank() && (key == "po" || key.contains("po no") || key.contains("po number") || key.contains("purchase order") || key.contains("order no")) ->
                    poNumber = value
                poDate.isBlank() && (key.contains("po date") || key.contains("order date") || key.contains("issue date")) ->
                    poDate = value
                deliveryDate.isBlank() && (key.contains("delivery") || key.contains("handover") || key.contains("ex-factory") || key.contains("ex factory") || key.contains("etd")) ->
                    deliveryDate = value
                shipDate.isBlank() && (key.contains("ship date") || key.contains("shipment") || key.contains("sailing")) ->
                    shipDate = value
                destination.isBlank() && (key.contains("destination") || key.contains("port") || key.contains("country") || key.contains("dc")) ->
                    destination = value
                factory.isBlank() && (key.contains("factory") || key.contains("vendor") || key.contains("supplier") || key.contains("unit")) ->
                    factory = value
                fabric.isBlank() && (key.contains("fabric") || key.contains("composition") || key.contains("material") || key.contains("gsm")) ->
                    fabric = value
                key.contains("currency") || key == "curr" -> {
                    val c = value.uppercase(Locale.US).take(6)
                    if (c.isNotBlank()) currency = c
                }
            }
        }

        for (r in 0 until headerRowIdx) {
            val row = rows[r]
            for (c in row.indices) {
                val cell = row[c].trim()
                if (cell.isBlank()) continue
                if (cell.contains(":")) {
                    val idx = cell.indexOf(':')
                    val k = cell.substring(0, idx)
                    val v = cell.substring(idx + 1).trim()
                    if (v.isNotBlank()) {
                        assignFromPair(k, v)
                    } else if (c + 1 < row.size) {
                        val nextVal = (c + 1 until row.size).firstNotNullOfOrNull { nc ->
                            row[nc].trim().takeIf { it.isNotBlank() }
                        }
                        if (nextVal != null) assignFromPair(k, nextVal)
                    }
                } else if (c + 1 < row.size && row[c + 1].isNotBlank()) {
                    assignFromPair(cell, row[c + 1])
                }
            }
        }

        return ExtractedMetadata(
            buyerName = buyer,
            poNumber = poNumber,
            poDate = poDate,
            deliveryDate = deliveryDate,
            shipDate = shipDate,
            destination = destination,
            factoryInfo = factory,
            fabricDetails = fabric,
            currency = currency
        )
    }

    fun autoDetectColumnMapping(
        headers: List<String>,
        headerRowIndex: Int,
        rows: List<List<String>>
    ): ColumnMapping {
        var buyerCol: Int? = null
        var poNumberCol: Int? = null
        var poDateCol: Int? = null
        var styleNumCol: Int? = null
        var styleDescCol: Int? = null
        var itemDescCol: Int? = null
        var colorCol: Int? = null
        var garmentTypeCol: Int? = null
        var singleSizeCol: Int? = null
        var qtyCol: Int? = null
        var unitPriceCol: Int? = null
        var currencyCol: Int? = null
        var deliveryDateCol: Int? = null
        var shipDateCol: Int? = null
        var destinationCol: Int? = null
        var factoryCol: Int? = null
        var fabricCol: Int? = null
        val sizeMatrixCols = mutableListOf<Int>()

        val sampleDataRows = rows.drop(headerRowIndex + 1).take(15)

        headers.forEachIndexed { index, rawHeader ->
            val h = normalizeHeader(rawHeader)
            if (h.isBlank()) return@forEachIndexed

            when {
                isLikelySizeHeader(rawHeader) && !isTotalOrPriceHeader(h) -> {
                    // Check that sample data in this column looks numeric or empty
                    val looksNumeric = sampleDataRows.isEmpty() || sampleDataRows.all { row ->
                        val v = row.getOrElse(index) { "" }.trim()
                        v.isBlank() || parseLongFlexible(v) != null || v == "-"
                    }
                    if (looksNumeric) {
                        sizeMatrixCols.add(index)
                    }
                }
                buyerCol == null && (h == "buyer" || h.contains("buyer name") || h == "customer" || h == "brand") ->
                    buyerCol = index
                poNumberCol == null && (h == "po" || h == "po no" || h == "po number" || h == "po #" || h.contains("purchase order") || h == "order no") ->
                    poNumberCol = index
                poDateCol == null && (h.contains("po date") || h.contains("order date") || h.contains("issue date")) ->
                    poDateCol = index
                styleNumCol == null && (h == "style" || h == "style no" || h == "style number" || h == "style #" || h == "style code" || h == "art no" || h == "article" || h == "article no" || h == "sku") ->
                    styleNumCol = index
                styleDescCol == null && (h.contains("style desc") || h == "description" || h == "style name" || h == "product name") ->
                    styleDescCol = index
                itemDescCol == null && (h.contains("item desc") || h == "item" || h == "item name" || h == "product desc") ->
                    itemDescCol = index
                colorCol == null && (h == "color" || h == "colour" || h.contains("color name") || h.contains("colour name") || h == "colorway" || h == "shade") ->
                    colorCol = index
                garmentTypeCol == null && (h.contains("garment") || h.contains("category") || h.contains("product type") || h == "dept" || h == "department" || h == "item type") ->
                    garmentTypeCol = index
                singleSizeCol == null && (h == "size" || h == "size name" || h == "size code") ->
                    singleSizeCol = index
                unitPriceCol == null && (h.contains("unit price") || h == "price" || h == "fob" || h.contains("fob price") || h == "rate" || h == "cost") ->
                    unitPriceCol = index
                currencyCol == null && (h == "currency" || h == "curr" || h == "ccy") ->
                    currencyCol = index
                qtyCol == null && (h == "total" || h == "total qty" || h == "qty" || h == "quantity" || h == "order qty" || h == "po qty" || h == "pcs" || h == "total pcs" || h == "units") ->
                    qtyCol = index
                deliveryDateCol == null && (h.contains("delivery") || h.contains("ex-factory") || h.contains("ex factory") || h.contains("handover") || h == "etd") ->
                    deliveryDateCol = index
                shipDateCol == null && (h.contains("ship date") || h.contains("shipment") || h == "sailing date") ->
                    shipDateCol = index
                destinationCol == null && (h.contains("destination") || h == "dest" || h == "country" || h == "port" || h == "dc" || h == "market") ->
                    destinationCol = index
                factoryCol == null && (h.contains("factory") || h == "unit" || h == "vendor" || h == "supplier" || h == "plant") ->
                    factoryCol = index
                fabricCol == null && (h.contains("fabric") || h.contains("composition") || h.contains("material") || h == "gsm" || h == "quality") ->
                    fabricCol = index
            }
        }

        // If no horizontal size matrix was found, keep singleSizeColumn for vertical pivoting
        val effectiveSingleSize = if (sizeMatrixCols.isEmpty()) singleSizeCol else null

        return ColumnMapping(
            headerRowIndex = headerRowIndex,
            buyerColumn = buyerCol,
            poNumberColumn = poNumberCol,
            poDateColumn = poDateCol,
            styleNumberColumn = styleNumCol,
            styleDescriptionColumn = styleDescCol,
            itemDescriptionColumn = itemDescCol,
            colorColumn = colorCol,
            garmentTypeColumn = garmentTypeCol,
            singleSizeColumn = effectiveSingleSize,
            quantityColumn = qtyCol,
            unitPriceColumn = unitPriceCol,
            currencyColumn = currencyCol,
            deliveryDateColumn = deliveryDateCol,
            shipDateColumn = shipDateCol,
            destinationColumn = destinationCol,
            factoryInfoColumn = factoryCol,
            fabricDetailsColumn = fabricCol,
            sizeMatrixColumns = sizeMatrixCols
        )
    }

    fun isLikelySizeHeader(rawHeader: String): Boolean {
        val trimmed = rawHeader.trim()
        if (trimmed.isBlank() || trimmed.length > 16) return false
        return alphaSizeRegex.matches(trimmed) ||
            kidsSizeRegex.matches(trimmed) ||
            numericGarmentSizeRegex.matches(trimmed) ||
            explicitSizeHeaderRegex.matches(trimmed)
    }

    private fun isTotalOrPriceHeader(norm: String): Boolean =
        norm.contains("total") || norm.contains("price") || norm.contains("value") ||
            norm.contains("amount") || norm.contains("qty") || norm.contains("fob") ||
            norm.contains("style") || norm.contains("po")

    /**
     * Converts raw parsed sheet + user-confirmed metadata & column mapping into a standardized LSL PurchaseOrderRecord
     * alongside an immutable OriginalSourceSnapshot.
     */
    fun transferToStandardizedPo(
        userId: String,
        fileName: String,
        fileFormat: PoFileFormat,
        sheet: ParsedSheet,
        metadata: ExtractedMetadata,
        mapping: ColumnMapping,
        existingPoNumbers: Set<String>
    ): Pair<PurchaseOrderRecord, OriginalSourceSnapshot> {
        val headers = sheet.rows.getOrElse(mapping.headerRowIndex) { emptyList() }
        val dataRows = sheet.rows.drop(mapping.headerRowIndex + 1)

        val warnings = mutableListOf<PoValidationWarning>()
        val dynamicSizes = linkedSetOf<String>()
        val rawLineItems = mutableListOf<PoLineItem>()

        var resolvedBuyer = metadata.buyerName.trim()
        var resolvedPoNumber = metadata.poNumber.trim()
        var resolvedPoDate = metadata.poDate.trim()
        var resolvedDelivery = metadata.deliveryDate.trim()
        var resolvedShip = metadata.shipDate.trim()
        var resolvedDest = metadata.destination.trim()
        var resolvedFactory = metadata.factoryInfo.trim()
        var resolvedFabric = metadata.fabricDetails.trim()
        var resolvedCurrency = metadata.currency.trim().ifBlank { "USD" }

        // Track vertical-mode grouping if buyer PO lists one size per row
        val isVerticalMode = mapping.sizeMatrixColumns.isEmpty() && mapping.singleSizeColumn != null
        val verticalGroups = linkedMapOf<String, MutableList<Pair<String, Long>>>()
        val verticalBaseItems = linkedMapOf<String, PoLineItem>()

        // Pre-register horizontal matrix size names in exact original order
        val matrixColToName = linkedMapOf<Int, String>()
        for (colIdx in mapping.sizeMatrixColumns) {
            val rawName = headers.getOrElse(colIdx) { "SZ${colIdx + 1}" }.trim()
            val cleanName = explicitSizeHeaderRegex.matchEntire(rawName)?.groupValues?.getOrNull(2)?.ifBlank { rawName }
                ?: rawName.ifBlank { "SZ${colIdx + 1}" }
            matrixColToName[colIdx] = cleanName
            dynamicSizes.add(cleanName)
        }

        dataRows.forEachIndexed { idx, row ->
            val excelRowNumber = mapping.headerRowIndex + 2 + idx
            if (row.all { it.isBlank() }) return@forEachIndexed
            // Skip footer summary rows like "Grand Total" / "Total" so we don't double-count
            val firstNonBlank = row.firstOrNull { it.isNotBlank() }?.lowercase(Locale.US) ?: ""
            if (firstNonBlank == "total" || firstNonBlank == "grand total" || firstNonBlank == "subtotal" || firstNonBlank.startsWith("grand total")) {
                return@forEachIndexed
            }

            fun colVal(col: Int?): String = if (col != null) row.getOrElse(col) { "" }.trim() else ""

            val rowBuyer = colVal(mapping.buyerColumn)
            val rowPo = colVal(mapping.poNumberColumn)
            val rowPoDate = colVal(mapping.poDateColumn)
            val rowCurr = colVal(mapping.currencyColumn)
            if (resolvedBuyer.isBlank() && rowBuyer.isNotBlank()) resolvedBuyer = rowBuyer
            if (resolvedPoNumber.isBlank() && rowPo.isNotBlank()) resolvedPoNumber = rowPo
            if (resolvedPoDate.isBlank() && rowPoDate.isNotBlank()) resolvedPoDate = rowPoDate
            if (rowCurr.isNotBlank()) resolvedCurrency = rowCurr.uppercase(Locale.US).take(6)

            val styleNumber = colVal(mapping.styleNumberColumn)
            val styleDesc = colVal(mapping.styleDescriptionColumn)
            val itemDesc = colVal(mapping.itemDescriptionColumn)
            val color = colVal(mapping.colorColumn)
            val garmentType = colVal(mapping.garmentTypeColumn)
            val rowDelivery = colVal(mapping.deliveryDateColumn).ifBlank { resolvedDelivery }
            val rowShip = colVal(mapping.shipDateColumn).ifBlank { resolvedShip }
            val rowDest = colVal(mapping.destinationColumn).ifBlank { resolvedDest }
            val rowFactory = colVal(mapping.factoryInfoColumn).ifBlank { resolvedFactory }
            val rowFabric = colVal(mapping.fabricDetailsColumn).ifBlank { resolvedFabric }

            if (resolvedDelivery.isBlank() && rowDelivery.isNotBlank()) resolvedDelivery = rowDelivery
            if (resolvedShip.isBlank() && rowShip.isNotBlank()) resolvedShip = rowShip
            if (resolvedDest.isBlank() && rowDest.isNotBlank()) resolvedDest = rowDest
            if (resolvedFactory.isBlank() && rowFactory.isNotBlank()) resolvedFactory = rowFactory
            if (resolvedFabric.isBlank() && rowFabric.isNotBlank()) resolvedFabric = rowFabric

            val rawPrice = colVal(mapping.unitPriceColumn)
            val parsedPrice = if (rawPrice.isBlank()) 0.0 else {
                parseDoubleFlexible(rawPrice) ?: run {
                    warnings.add(
                        PoValidationWarning(
                            code = "INVALID_UNIT_PRICE",
                            severity = ValidationSeverity.WARNING,
                            field = "Row $excelRowNumber",
                            message = "Row $excelRowNumber has unreadable unit price '$rawPrice'. Left as 0.00 for manual review."
                        )
                    )
                    0.0
                }
            }

            val rawDeclaredQty = colVal(mapping.quantityColumn)
            val parsedDeclaredQty = if (rawDeclaredQty.isBlank()) 0L else {
                parseLongFlexible(rawDeclaredQty) ?: run {
                    warnings.add(
                        PoValidationWarning(
                            code = "INVALID_QUANTITY",
                            severity = ValidationSeverity.ERROR,
                            field = "Row $excelRowNumber",
                            message = "Row $excelRowNumber has unreadable quantity '$rawDeclaredQty'."
                        )
                    )
                    0L
                }
            }

            if (isVerticalMode) {
                val sizeLabel = colVal(mapping.singleSizeColumn).ifBlank { "UNSPECIFIED" }
                if (sizeLabel != "UNSPECIFIED") dynamicSizes.add(sizeLabel)
                val groupKey = "${styleNumber.lowercase(Locale.US)}|${color.lowercase(Locale.US)}|${rowDelivery.lowercase(Locale.US)}|${rowDest.lowercase(Locale.US)}"
                val list = verticalGroups.getOrPut(groupKey) { mutableListOf() }
                list.add(sizeLabel to parsedDeclaredQty)
                if (groupKey !in verticalBaseItems) {
                    verticalBaseItems[groupKey] = PoLineItem(
                        lineId = "ln_${UUID.randomUUID().toString().take(8)}",
                        styleNumber = styleNumber,
                        styleDescription = styleDesc,
                        itemDescription = itemDesc,
                        color = color,
                        garmentType = garmentType,
                        unitPrice = parsedPrice,
                        declaredQty = 0L,
                        sizeQuantities = emptyMap(),
                        deliveryDate = rowDelivery,
                        shipDate = rowShip,
                        destination = rowDest,
                        factoryInfo = rowFactory,
                        fabricDetails = rowFabric
                    )
                }
            } else {
                val sizeMap = linkedMapOf<String, Long>()
                for ((colIdx, sizeName) in matrixColToName) {
                    val rawCell = row.getOrElse(colIdx) { "" }.trim()
                    if (rawCell.isBlank() || rawCell == "-") {
                        sizeMap[sizeName] = 0L
                    } else {
                        val qty = parseLongFlexible(rawCell)
                        if (qty == null || qty < 0L) {
                            warnings.add(
                                PoValidationWarning(
                                    code = "INVALID_SIZE_VALUE",
                                    severity = ValidationSeverity.ERROR,
                                    field = "Row $excelRowNumber / $sizeName",
                                    message = "Row $excelRowNumber size '$sizeName' contains unreadable value '$rawCell'."
                                )
                            )
                            sizeMap[sizeName] = 0L
                        } else {
                            sizeMap[sizeName] = qty
                        }
                    }
                }

                // Skip rows that have neither style, color, nor any quantity
                if (styleNumber.isBlank() && color.isBlank() && sizeMap.values.sum() == 0L && parsedDeclaredQty == 0L) {
                    return@forEachIndexed
                }

                rawLineItems.add(
                    PoLineItem(
                        lineId = "ln_${UUID.randomUUID().toString().take(8)}",
                        styleNumber = styleNumber,
                        styleDescription = styleDesc,
                        itemDescription = itemDesc,
                        color = color,
                        garmentType = garmentType,
                        unitPrice = parsedPrice,
                        declaredQty = parsedDeclaredQty,
                        sizeQuantities = sizeMap,
                        deliveryDate = rowDelivery,
                        shipDate = rowShip,
                        destination = rowDest,
                        factoryInfo = rowFactory,
                        fabricDetails = rowFabric
                    )
                )
            }
        }

        val finalLineItems = if (isVerticalMode) {
            verticalBaseItems.map { (key, base) ->
                val pairs = verticalGroups[key].orEmpty()
                val sizeMap = linkedMapOf<String, Long>()
                for (sz in dynamicSizes) sizeMap[sz] = 0L
                var sum = 0L
                for ((sz, q) in pairs) {
                    sizeMap[sz] = (sizeMap[sz] ?: 0L) + q
                    sum += q
                }
                base.copy(declaredQty = sum, sizeQuantities = sizeMap)
            }
        } else {
            rawLineItems
        }

        val draft = PurchaseOrderRecord(
            userId = userId,
            poNumber = resolvedPoNumber.ifBlank { "PO-UNSPECIFIED" },
            buyerName = resolvedBuyer.ifBlank { "Unspecified Buyer" },
            poDate = resolvedPoDate,
            deliveryDate = resolvedDelivery,
            shipDate = resolvedShip,
            destination = resolvedDest,
            factoryInfo = resolvedFactory,
            fabricDetails = resolvedFabric,
            currency = resolvedCurrency,
            status = PoStatus.PENDING_VERIFICATION,
            originalFileName = fileName,
            originalFileFormat = fileFormat,
            originalSheetName = sheet.name,
            originalHeaders = headers.take(80),
            sizeColumns = dynamicSizes.toList().take(40),
            lineItems = finalLineItems.take(250),
            validationWarnings = warnings
        )

        val validated = recalculateAndValidate(
            po = draft,
            existingPoNumbers = existingPoNumbers,
            extraParserWarnings = warnings
        )

        val preservedRows = sheet.rows.take(500).mapIndexed { rIdx, rCells ->
            OriginalRowSnapshot(rowIndex = (rIdx + 1).toLong(), cells = rCells.take(80))
        }
        val mappingSnapshot = buildMap<String, Any> {
            put("headerRowIndex", mapping.headerRowIndex)
            mapping.buyerColumn?.let { put("buyerColumn", it) }
            mapping.poNumberColumn?.let { put("poNumberColumn", it) }
            mapping.styleNumberColumn?.let { put("styleNumberColumn", it) }
            mapping.colorColumn?.let { put("colorColumn", it) }
            mapping.quantityColumn?.let { put("quantityColumn", it) }
            mapping.unitPriceColumn?.let { put("unitPriceColumn", it) }
            put("sizeMatrixColumns", mapping.sizeMatrixColumns)
        }

        val sourceSnapshot = OriginalSourceSnapshot(
            id = "source_primary",
            userId = userId,
            poId = "",
            fileName = fileName,
            fileFormat = fileFormat,
            sheetName = sheet.name,
            headers = headers.take(80),
            rowCount = sheet.rows.size.toLong(),
            rows = preservedRows,
            columnMapping = mappingSnapshot
        )

        return validated to sourceSnapshot
    }

    /**
     * Recomputes style, color, size, and overall totals and executes all Section 5 validation checks.
     */
    fun recalculateAndValidate(
        po: PurchaseOrderRecord,
        existingPoNumbers: Set<String> = emptySet(),
        extraParserWarnings: List<PoValidationWarning> = emptyList()
    ): PurchaseOrderRecord {
        val warnings = mutableListOf<PoValidationWarning>()
        warnings.addAll(extraParserWarnings)

        if (po.poNumber.isBlank() || po.poNumber == "PO-UNSPECIFIED") {
            warnings.add(
                PoValidationWarning(
                    code = "MISSING_PO_NUMBER",
                    severity = ValidationSeverity.ERROR,
                    field = "PO Number",
                    message = "PO Number was not detected in the buyer file. Enter the official PO Number before verification."
                )
            )
        } else if (existingPoNumbers.any { it.equals(po.poNumber.trim(), ignoreCase = true) }) {
            warnings.add(
                PoValidationWarning(
                    code = "DUPLICATE_PO_NUMBER",
                    severity = ValidationSeverity.WARNING,
                    field = "PO Number",
                    message = "Duplicate PO detected: '${po.poNumber}' already exists in Saved POs."
                )
            )
        }

        if (po.buyerName.isBlank() || po.buyerName == "Unspecified Buyer") {
            warnings.add(
                PoValidationWarning(
                    code = "MISSING_BUYER",
                    severity = ValidationSeverity.WARNING,
                    field = "Buyer Name",
                    message = "Buyer name is missing. Please confirm the buyer before exporting."
                )
            )
        }

        if (po.lineItems.isEmpty()) {
            warnings.add(
                PoValidationWarning(
                    code = "NO_LINE_ITEMS",
                    severity = ValidationSeverity.ERROR,
                    field = "Line Items",
                    message = "No PO line items were extracted. Adjust the header row or column mapping."
                )
            )
        }

        if (po.sizeColumns.isEmpty()) {
            warnings.add(
                PoValidationWarning(
                    code = "MISSING_SIZE_COLUMNS",
                    severity = ValidationSeverity.WARNING,
                    field = "Size Breakdown",
                    message = "No size columns detected. Mark size columns in Column Mapping or add sizes manually."
                )
            )
        }

        var grandTotalQty = 0L
        var grandTotalValue = 0.0
        var sumOfDeclaredQty = 0L
        var sumOfSizeQty = 0L

        po.lineItems.forEachIndexed { index, item ->
            val label = item.styleNumber.ifBlank { "Line ${index + 1}" }
            if (item.styleNumber.isBlank()) {
                warnings.add(
                    PoValidationWarning(
                        code = "MISSING_STYLE_NUMBER",
                        severity = ValidationSeverity.ERROR,
                        field = "Line ${index + 1}",
                        message = "Line ${index + 1} (${item.color.ifBlank { "No color" }}) is missing a Style Number."
                    )
                )
            }

            if (item.color.isBlank()) {
                warnings.add(
                    PoValidationWarning(
                        code = "MISSING_COLOR",
                        severity = ValidationSeverity.WARNING,
                        field = label,
                        message = "Style '$label' (Line ${index + 1}) is missing a Color."
                    )
                )
            }

            val sizeSum = item.sizeTotal
            sumOfSizeQty += sizeSum
            sumOfDeclaredQty += item.declaredQty

            if (po.sizeColumns.isNotEmpty() && sizeSum == 0L && item.declaredQty > 0L) {
                warnings.add(
                    PoValidationWarning(
                        code = "MISSING_SIZE_QUANTITIES",
                        severity = ValidationSeverity.WARNING,
                        field = "$label / ${item.color.ifBlank { "-" }}",
                        message = "Style '$label' has declared PO qty ${item.declaredQty} but size breakdown totals 0."
                    )
                )
            } else if (item.declaredQty > 0L && sizeSum > 0L && item.declaredQty != sizeSum) {
                val diff = sizeSum - item.declaredQty
                warnings.add(
                    PoValidationWarning(
                        code = "SIZE_VS_STYLE_QTY_MISMATCH",
                        severity = ValidationSeverity.ERROR,
                        field = "$label / ${item.color.ifBlank { "-" }}",
                        message = "Quantity mismatch on '$label' (${item.color}): Size sum ($sizeSum) != Buyer PO Qty (${item.declaredQty}) [Diff: ${if (diff > 0) "+$diff" else "$diff"}]."
                    )
                )
            }

            if (item.effectiveQty <= 0L) {
                warnings.add(
                    PoValidationWarning(
                        code = "ZERO_LINE_QTY",
                        severity = ValidationSeverity.WARNING,
                        field = label,
                        message = "Style '$label' (Line ${index + 1}) has zero total quantity."
                    )
                )
            }

            grandTotalQty += item.effectiveQty
            grandTotalValue += item.lineTotalValue
        }

        if (sumOfDeclaredQty > 0L && sumOfSizeQty > 0L && sumOfDeclaredQty != sumOfSizeQty) {
            warnings.add(
                PoValidationWarning(
                    code = "OVERALL_PO_QTY_MISMATCH",
                    severity = ValidationSeverity.ERROR,
                    field = "Overall PO Total",
                    message = "Overall size-wise total ($sumOfSizeQty pcs) does not match buyer declared PO total ($sumOfDeclaredQty pcs). Original buyer values preserved for review."
                )
            )
        }

        // Cross-check color-wise & style-wise totals consistency
        val styleSumCheck = computeStyleBreakdown(po).sumOf { it.poQuantity }
        val colorSumCheck = computeColorBreakdown(po).sumOf { it.quantity }
        if (styleSumCheck != grandTotalQty || colorSumCheck != grandTotalQty) {
            warnings.add(
                PoValidationWarning(
                    code = "AGGREGATE_TOTAL_MISMATCH",
                    severity = ValidationSeverity.ERROR,
                    field = "Totals Check",
                    message = "Style total ($styleSumCheck) or Color total ($colorSumCheck) differs from Overall PO Total ($grandTotalQty)."
                )
            )
        }

        val distinctStyles = po.lineItems.mapNotNull { it.styleNumber.trim().takeIf { s -> s.isNotBlank() } }.distinct().size
        val distinctColors = po.lineItems.mapNotNull { it.color.trim().takeIf { c -> c.isNotBlank() } }.distinct().size

        val dedupedWarnings = warnings.distinctBy { "${it.code}|${it.field}|${it.message}" }.take(200)

        return po.copy(
            validationWarnings = dedupedWarnings,
            totalQuantity = grandTotalQty,
            totalValue = (grandTotalValue * 100.0).toLong() / 100.0,
            styleCount = distinctStyles.toLong(),
            colorCount = distinctColors.toLong(),
            warningCount = dedupedWarnings.size.toLong()
        )
    }

    // -------------------------------------------------------------------------
    // Standardized LSL Breakdown Calculators (Sections 4B, 4C, 4D, 4E)
    // -------------------------------------------------------------------------
    fun computeStyleBreakdown(po: PurchaseOrderRecord): List<StyleBreakdownRow> {
        return po.lineItems
            .groupBy { it.styleNumber.ifBlank { "(Missing Style)" } }
            .map { (style, items) ->
                val qty = items.sumOf { it.effectiveQty }
                val value = items.sumOf { it.lineTotalValue }
                val avgPrice = if (qty > 0L) value / qty else (items.firstOrNull()?.unitPrice ?: 0.0)
                val colors = items.mapNotNull { it.color.takeIf { c -> c.isNotBlank() } }.distinct().joinToString(", ")
                val desc = items.firstOrNull { it.styleDescription.isNotBlank() }?.styleDescription
                    ?: items.firstOrNull { it.itemDescription.isNotBlank() }?.itemDescription
                    ?: "-"
                val garmentType = items.firstOrNull { it.garmentType.isNotBlank() }?.garmentType ?: "-"
                StyleBreakdownRow(
                    styleNumber = style,
                    description = desc,
                    colors = colors.ifBlank { "-" },
                    garmentType = garmentType,
                    poQuantity = qty,
                    avgUnitPrice = avgPrice,
                    totalValue = value
                )
            }
    }

    fun computeColorBreakdown(po: PurchaseOrderRecord): List<ColorBreakdownRow> {
        val grandTotal = po.lineItems.sumOf { it.effectiveQty }.coerceAtLeast(1L)
        return po.lineItems
            .groupBy { (it.styleNumber.ifBlank { "(Missing Style)" }) to (it.color.ifBlank { "(Unspecified Color)" }) }
            .map { (key, items) ->
                val qty = items.sumOf { it.effectiveQty }
                val value = items.sumOf { it.lineTotalValue }
                ColorBreakdownRow(
                    styleNumber = key.first,
                    color = key.second,
                    garmentType = items.firstOrNull { it.garmentType.isNotBlank() }?.garmentType ?: "-",
                    quantity = qty,
                    sharePercent = (qty.toDouble() / grandTotal.toDouble()) * 100.0,
                    totalValue = value
                )
            }
    }

    fun computeDeliveryBreakdown(po: PurchaseOrderRecord): List<DeliveryBreakdownRow> {
        return po.lineItems
            .groupBy { item ->
                Triple(
                    item.deliveryDate.ifBlank { po.deliveryDate.ifBlank { "TBD" } },
                    item.shipDate.ifBlank { po.shipDate.ifBlank { "-" } },
                    item.destination.ifBlank { po.destination.ifBlank { "-" } }
                )
            }
            .map { (key, items) ->
                val styles = items.map { it.styleNumber }.distinct().size
                val factory = items.firstOrNull { it.factoryInfo.isNotBlank() }?.factoryInfo
                    ?: po.factoryInfo.ifBlank { "-" }
                DeliveryBreakdownRow(
                    deliveryDate = key.first,
                    shipDate = key.second,
                    destination = key.third,
                    factoryInfo = factory,
                    styleCount = styles,
                    quantity = items.sumOf { it.effectiveQty },
                    totalValue = items.sumOf { it.lineTotalValue }
                )
            }
            .sortedBy { it.deliveryDate }
    }

    fun computeSizeColumnTotals(po: PurchaseOrderRecord): Map<String, Long> {
        val totals = linkedMapOf<String, Long>()
        for (sz in po.sizeColumns) {
            totals[sz] = po.lineItems.sumOf { it.sizeQuantities[sz] ?: 0L }
        }
        return totals
    }

    // -------------------------------------------------------------------------
    // Parsing Helpers
    // -------------------------------------------------------------------------
    private fun normalizeHeader(raw: String): String =
        raw.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")

    fun parseLongFlexible(raw: String): Long? {
        val cleaned = raw.trim()
            .replace(",", "")
            .replace("pcs", "", ignoreCase = true)
            .replace("units", "", ignoreCase = true)
            .trim()
        if (cleaned.isBlank()) return null
        cleaned.toLongOrNull()?.let { return it }
        val dbl = cleaned.toDoubleOrNull() ?: return null
        return if (abs(dbl - dbl.toLong()) < 1e-6) dbl.toLong() else null
    }

    fun parseDoubleFlexible(raw: String): Double? {
        val cleaned = raw.trim()
            .replace(Regex("[$€£¥৳,]"), "")
            .replace(Regex("^(USD|EUR|GBP|BDT)\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
        return cleaned.toDoubleOrNull()
    }
}
