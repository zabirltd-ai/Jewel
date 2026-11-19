package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import com.example.model.PurchaseOrderRecord
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Generates professional A4 Landscape (842 x 595 pt) multi-page LSL PO PDF reports
 * with repeating table headers, proper margins, page numbers, zero cut-off columns,
 * native Android PrintManager integration, and PdfRenderer bitmap preview.
 */
object PdfAndPrintEngine {

    private const val PAGE_WIDTH = 842 // A4 Landscape points
    private const val PAGE_HEIGHT = 595
    private const val MARGIN_X = 32f
    private const val MARGIN_TOP = 28f
    private const val MARGIN_BOTTOM = 34f
    private const val CONTENT_WIDTH = PAGE_WIDTH - (MARGIN_X * 2)

    private val colorForest = Color.rgb(18, 79, 45)
    private val colorMint = Color.rgb(230, 244, 236)
    private val colorZebra = Color.rgb(245, 247, 246)
    private val colorTotalBg = Color.rgb(217, 239, 226)
    private val colorBorder = Color.rgb(205, 215, 210)
    private val colorInk = Color.rgb(16, 32, 23)
    private val colorMuted = Color.rgb(90, 108, 98)

    fun writePdfToStream(
        po: PurchaseOrderRecord,
        companyName: String,
        outputStream: OutputStream
    ) {
        val doc = buildPdfDocument(po, companyName)
        try {
            doc.writeTo(outputStream)
        } finally {
            doc.close()
        }
    }

    fun generatePreviewPdfFile(
        context: Context,
        po: PurchaseOrderRecord,
        companyName: String
    ): File {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safePo = po.poNumber.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "PO" }
        val file = File(exportDir, "LSL_PO_${safePo}_preview.pdf")
        FileOutputStream(file).use { fos ->
            writePdfToStream(po, companyName, fos)
        }
        return file
    }

    fun renderPdfPreviewBitmaps(pdfFile: File, scale: Float = 1.6f): List<Bitmap> {
        val bitmaps = mutableListOf<Bitmap>()
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { page ->
                        val width = (page.width * scale).toInt().coerceAtLeast(300)
                        val height = (page.height * scale).toInt().coerceAtLeast(200)
                        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmaps.add(bmp)
                    }
                }
            }
        }
        return bitmaps
    }

    fun printPurchaseOrder(
        context: Context,
        po: PurchaseOrderRecord,
        companyName: String
    ) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val jobName = "LSL_PO_${po.poNumber.ifBlank { "Report" }}"
        val pdfFile = generatePreviewPdfFile(context, po, companyName)

        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback,
                extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder("$jobName.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                    .build()
                callback.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out PageRange>,
                destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback
            ) {
                try {
                    FileInputStream(pdfFile).use { input ->
                        FileOutputStream(destination.fileDescriptor).use { output ->
                            input.copyTo(output)
                        }
                    }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.localizedMessage)
                }
            }
        }

        val attrs = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape())
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()

        printManager.print(jobName, adapter, attrs)
    }

    // -------------------------------------------------------------------------
    // Multi-page A4 Landscape Vector Layout Engine
    // -------------------------------------------------------------------------
    private fun buildPdfDocument(po: PurchaseOrderRecord, companyName: String): PdfDocument {
        val document = PdfDocument()
        val numFmt = NumberFormat.getIntegerInstance(Locale.US)
        val curFmt = NumberFormat.getNumberInstance(Locale.US).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        val dateStamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

        val paintHeaderFill = Paint().apply { color = colorForest; style = Paint.Style.FILL }
        val paintMintFill = Paint().apply { color = colorMint; style = Paint.Style.FILL }
        val paintZebraFill = Paint().apply { color = colorZebra; style = Paint.Style.FILL }
        val paintTotalFill = Paint().apply { color = colorTotalBg; style = Paint.Style.FILL }
        val paintBorder = Paint().apply { color = colorBorder; style = Paint.Style.STROKE; strokeWidth = 0.7f }
        val paintGreenRule = Paint().apply { color = colorForest; style = Paint.Style.STROKE; strokeWidth = 1.2f }

        val paintBrand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 15f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val paintBrandSub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(212, 240, 224)
            textSize = 9f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val paintSectionTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorForest
            textSize = 11f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val paintTableHeader = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 8.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val paintCell = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorInk
            textSize = 8.2f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val paintCellBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorInk
            textSize = 8.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val paintFooter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorMuted
            textSize = 8f
        }

        var pageNumber = 0
        var currentPage: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f

        fun drawPageFooter(c: Canvas, pNum: Int) {
            val footerY = PAGE_HEIGHT - 16f
            c.drawLine(MARGIN_X, footerY - 12f, PAGE_WIDTH - MARGIN_X, footerY - 12f, paintBorder)
            c.drawText(
                "${companyName.uppercase(Locale.US)}  •  PO #${po.poNumber}  •  Generated $dateStamp",
                MARGIN_X,
                footerY,
                paintFooter
            )
            val pageLabel = "Page $pNum"
            val labelW = paintFooter.measureText(pageLabel)
            c.drawText(pageLabel, PAGE_WIDTH - MARGIN_X - labelW, footerY, paintFooter)
        }

        fun startNewPage() {
            currentPage?.let { p ->
                canvas?.let { drawPageFooter(it, pageNumber) }
                document.finishPage(p)
            }
            pageNumber++
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            currentPage = document.startPage(info)
            canvas = currentPage!!.canvas
            val c = canvas!!

            // Corporate LSL Green Banner on every page
            c.drawRect(MARGIN_X, MARGIN_TOP, PAGE_WIDTH - MARGIN_X, MARGIN_TOP + 38f, paintHeaderFill)
            c.drawText(companyName.uppercase(Locale.US), MARGIN_X + 12f, MARGIN_TOP + 17f, paintBrand)
            c.drawText("PO TRANSFER & BREAKDOWN REPORT", MARGIN_X + 12f, MARGIN_TOP + 30f, paintBrandSub)

            val rightMeta = "PO: ${po.poNumber}  |  Buyer: ${po.buyerName}"
            val rw = paintBrandSub.measureText(rightMeta)
            c.drawText(rightMeta, PAGE_WIDTH - MARGIN_X - 12f - rw, MARGIN_TOP + 17f, paintBrandSub)
            val rightMeta2 = "PO Date: ${po.poDate.ifBlank { "-" }}  |  Delivery: ${po.deliveryDate.ifBlank { "-" }}  |  Status: ${po.status.label}"
            val rw2 = paintBrandSub.measureText(rightMeta2)
            c.drawText(rightMeta2, PAGE_WIDTH - MARGIN_X - 12f - rw2, MARGIN_TOP + 30f, paintBrandSub)

            y = MARGIN_TOP + 50f
        }

        fun ensureSpace(needed: Float, onNewPageHeader: (() -> Unit)? = null) {
            if (y + needed > PAGE_HEIGHT - MARGIN_BOTTOM) {
                startNewPage()
                onNewPageHeader?.invoke()
            }
        }

        fun drawTableRow(
            c: Canvas,
            colWidths: FloatArray,
            values: List<String>,
            rightAlignedCols: Set<Int>,
            rowHeight: Float,
            fillPaint: Paint?,
            textPaint: Paint
        ) {
            if (fillPaint != null) {
                c.drawRect(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y + rowHeight, fillPaint)
            }
            var x = MARGIN_X
            values.forEachIndexed { idx, raw ->
                val w = colWidths[idx]
                c.drawRect(x, y, x + w, y + rowHeight, paintBorder)
                val maxTextW = (w - 8f).coerceAtLeast(10f)
                val clipped = ellipsize(raw, textPaint, maxTextW)
                val textY = y + (rowHeight / 2f) + 3f
                if (idx in rightAlignedCols) {
                    val tw = textPaint.measureText(clipped)
                    c.drawText(clipped, x + w - 4f - tw, textY, textPaint)
                } else {
                    c.drawText(clipped, x + 4f, textY, textPaint)
                }
                x += w
            }
            y += rowHeight
        }

        fun drawSectionTable(
            title: String,
            headers: List<String>,
            colWeights: FloatArray,
            rows: List<List<String>>,
            totalRow: List<String>?,
            rightAlignedCols: Set<Int>
        ) {
            val weightSum = colWeights.sum().coerceAtLeast(1f)
            val widths = FloatArray(colWeights.size) { i -> (colWeights[i] / weightSum) * CONTENT_WIDTH }
            val headerHeight = 20f
            val rowHeight = 18f

            fun drawTitleAndHeader() {
                canvas!!.drawText(title, MARGIN_X, y + 10f, paintSectionTitle)
                y += 15f
                drawTableRow(canvas!!, widths, headers, rightAlignedCols, headerHeight, paintHeaderFill, paintTableHeader)
            }

            ensureSpace(58f)
            drawTitleAndHeader()

            rows.forEachIndexed { idx, rowVals ->
                ensureSpace(rowHeight + 4f) {
                    drawTitleAndHeader() // Repeat table header automatically on every new page!
                }
                val bg = if (idx % 2 == 1) paintZebraFill else null
                drawTableRow(canvas!!, widths, rowVals, rightAlignedCols, rowHeight, bg, paintCell)
            }

            if (totalRow != null) {
                ensureSpace(rowHeight + 6f) { drawTitleAndHeader() }
                drawTableRow(canvas!!, widths, totalRow, rightAlignedCols, rowHeight + 2f, paintTotalFill, paintCellBold)
                canvas!!.drawLine(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y, paintGreenRule)
            }
            y += 14f
        }

        // ---- Page 1 Start ----
        startNewPage()

        // PO Summary Strip
        val c = canvas!!
        val kpiCards = listOf(
            "BUYER" to po.buyerName,
            "PO NUMBER" to po.poNumber,
            "TOTAL STYLES" to po.styleCount.toString(),
            "TOTAL QUANTITY" to "${numFmt.format(po.totalQuantity)} pcs",
            "TOTAL VALUE" to "${po.currency} ${curFmt.format(po.totalValue)}",
            "DELIVERY DATE" to po.deliveryDate.ifBlank { "-" }
        )
        val cardGap = 6f
        val cardW = (CONTENT_WIDTH - cardGap * (kpiCards.size - 1)) / kpiCards.size
        kpiCards.forEachIndexed { idx, (label, value) ->
            val left = MARGIN_X + idx * (cardW + cardGap)
            val rect = RectF(left, y, left + cardW, y + 36f)
            c.drawRoundRect(rect, 4f, 4f, paintMintFill)
            c.drawRoundRect(rect, 4f, 4f, paintBorder)
            c.drawText(label, left + 6f, y + 12f, paintFooter)
            c.drawText(ellipsize(value, paintCellBold, cardW - 12f), left + 6f, y + 27f, paintCellBold)
        }
        y += 48f

        // 1. Style Breakdown
        val styleRows = PoTransferEngine.computeStyleBreakdown(po)
        drawSectionTable(
            title = "1. STYLE-WISE BREAKDOWN",
            headers = listOf("Style Number", "Description", "Color(s)", "Garment Type", "PO Qty", "Unit Price", "Total Value"),
            colWeights = floatArrayOf(1.3f, 2.2f, 1.6f, 1.2f, 1.0f, 1.0f, 1.2f),
            rows = styleRows.map {
                listOf(
                    it.styleNumber,
                    it.description,
                    it.colors,
                    it.garmentType,
                    numFmt.format(it.poQuantity),
                    "${po.currency} ${curFmt.format(it.avgUnitPrice)}",
                    "${po.currency} ${curFmt.format(it.totalValue)}"
                )
            },
            totalRow = listOf(
                "GRAND TOTAL",
                "${styleRows.size} Styles",
                "",
                "",
                numFmt.format(po.totalQuantity),
                "",
                "${po.currency} ${curFmt.format(po.totalValue)}"
            ),
            rightAlignedCols = setOf(4, 5, 6)
        )

        // 2. Dynamic Size Breakdown Matrix (scales gracefully across A4 Landscape with zero cut-off!)
        val sizes = po.sizeColumns
        val sizeHeaders = buildList {
            add("Style")
            add("Color")
            addAll(sizes)
            add("Total")
        }
        val sizeWeights = FloatArray(sizeHeaders.size) { idx ->
            when (idx) {
                0 -> 1.5f
                1 -> 1.5f
                sizeHeaders.lastIndex -> 1.1f
                else -> 0.85f
            }
        }
        val sizeTotals = PoTransferEngine.computeSizeColumnTotals(po)
        val sizeRightCols = (2..sizeHeaders.lastIndex).toSet()
        drawSectionTable(
            title = "2. SIZE-WISE QUANTITY BREAKDOWN",
            headers = sizeHeaders,
            colWeights = sizeWeights,
            rows = po.lineItems.map { line ->
                buildList {
                    add(line.styleNumber.ifBlank { "(Missing)" })
                    add(line.color.ifBlank { "-" })
                    sizes.forEach { sz -> add(numFmt.format(line.sizeQuantities[sz] ?: 0L)) }
                    add(numFmt.format(line.effectiveQty))
                }
            },
            totalRow = buildList {
                add("GRAND TOTAL")
                add("${po.lineItems.size} Lines")
                sizes.forEach { sz -> add(numFmt.format(sizeTotals[sz] ?: 0L)) }
                add(numFmt.format(po.totalQuantity))
            },
            rightAlignedCols = sizeRightCols
        )

        // 3. Color-Wise Breakdown
        val colorRows = PoTransferEngine.computeColorBreakdown(po)
        drawSectionTable(
            title = "3. COLOR-WISE BREAKDOWN",
            headers = listOf("Style Number", "Color", "Garment Type", "Quantity", "Share %", "Total Value"),
            colWeights = floatArrayOf(1.5f, 1.8f, 1.4f, 1.1f, 0.9f, 1.3f),
            rows = colorRows.map {
                listOf(
                    it.styleNumber,
                    it.color,
                    it.garmentType,
                    numFmt.format(it.quantity),
                    String.format(Locale.US, "%.1f%%", it.sharePercent),
                    "${po.currency} ${curFmt.format(it.totalValue)}"
                )
            },
            totalRow = listOf(
                "GRAND TOTAL",
                "${colorRows.size} Colorways",
                "",
                numFmt.format(po.totalQuantity),
                "100.0%",
                "${po.currency} ${curFmt.format(po.totalValue)}"
            ),
            rightAlignedCols = setOf(3, 4, 5)
        )

        // 4. Delivery Breakdown (when available)
        val deliveryRows = PoTransferEngine.computeDeliveryBreakdown(po)
        if (deliveryRows.isNotEmpty()) {
            drawSectionTable(
                title = "4. DELIVERY & SHIPMENT BREAKDOWN",
                headers = listOf("Delivery Date", "Ship Date", "Destination", "Factory Info", "Styles", "Quantity", "Total Value"),
                colWeights = floatArrayOf(1.2f, 1.2f, 1.8f, 1.6f, 0.8f, 1.0f, 1.3f),
                rows = deliveryRows.map {
                    listOf(
                        it.deliveryDate,
                        it.shipDate,
                        it.destination,
                        it.factoryInfo,
                        it.styleCount.toString(),
                        numFmt.format(it.quantity),
                        "${po.currency} ${curFmt.format(it.totalValue)}"
                    )
                },
                totalRow = listOf(
                    "GRAND TOTAL",
                    "",
                    "${deliveryRows.size} Shipments",
                    "",
                    po.styleCount.toString(),
                    numFmt.format(po.totalQuantity),
                    "${po.currency} ${curFmt.format(po.totalValue)}"
                ),
                rightAlignedCols = setOf(4, 5, 6)
            )
        }

        // Finish last page
        currentPage?.let { p ->
            canvas?.let { drawPageFooter(it, pageNumber) }
            document.finishPage(p)
        }

        return document
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return if (end <= 1) text.take(1) else text.substring(0, end) + "…"
    }
}
