package com.example

import com.example.engine.ExcelWorkbookExporter
import com.example.engine.PoTransferEngine
import com.example.engine.SpreadsheetParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PoEngineRobolectricTest {

    @Test
    fun `parses buyer CSV with metadata block, detects dynamic sizes, flags mismatch without mutating, and round-trips 6-sheet XLSX`() {
        val sampleCsv = """
            Buyer:,Nordic Apparel Group,,,,,,
            PO Number:,PO-2026-9912,,,,,,
            PO Date:,2026-11-18,,,,,,
            Delivery Date:,2027-03-01,,,,,,
            Destination:,Rotterdam NL,,,,,,
            Style No,Description,Color,2Y,4Y,6Y,Total Qty,Unit Price
            LSL-101,Kids Fleece Hoodie,Forest Green,100,200,150,450,6.50
            LSL-102,Kids Jogger Pant,Charcoal,50,100,50,250,5.00
        """.trimIndent()

        val workbook = SpreadsheetParser.parse(
            "Nordic_PO_9912.csv",
            ByteArrayInputStream(sampleCsv.toByteArray(Charsets.UTF_8))
        )
        assertEquals(1, workbook.sheets.size)

        val sheet = workbook.sheets.first()
        val (meta, mapping) = PoTransferEngine.analyzeSheet(sheet)
        assertEquals("Nordic Apparel Group", meta.buyerName)
        assertEquals("PO-2026-9912", meta.poNumber)
        assertEquals(5, mapping.headerRowIndex)
        assertEquals(listOf(3, 4, 5), mapping.sizeMatrixColumns)

        val (po, originalSource) = PoTransferEngine.transferToStandardizedPo(
            userId = "user_test",
            fileName = workbook.fileName,
            fileFormat = workbook.format,
            sheet = sheet,
            metadata = meta,
            mapping = mapping,
            existingPoNumbers = emptySet()
        )

        // Dynamic kids size columns preserved
        assertEquals(listOf("2Y", "4Y", "6Y"), po.sizeColumns)
        assertEquals(2, po.lineItems.size)
        // Second line has size sum 200 vs declared buyer qty 250 -> must emit mismatch warning!
        assertTrue(po.validationWarnings.any { it.code == "SIZE_VS_STYLE_QTY_MISMATCH" })
        // Original buyer data preserved separately
        assertEquals(8, originalSource.rows.size)

        // Export genuine 6-sheet .xlsx workbook and re-parse it via SpreadsheetParser!
        val out = ByteArrayOutputStream()
        ExcelWorkbookExporter.exportToStream(po, "LAILA STYLES LIMITED", out)
        val xlsxBytes = out.toByteArray()
        assertTrue(xlsxBytes.size > 1000)

        val reRead = SpreadsheetParser.parse(
            "LSL_PO_9912.xlsx",
            ByteArrayInputStream(xlsxBytes)
        )
        assertEquals(6, reRead.sheets.size)
        assertEquals(
            listOf(
                "PO Summary",
                "Style Breakdown",
                "Size Breakdown",
                "Color Breakdown",
                "Delivery Breakdown",
                "Validation Report"
            ),
            reRead.sheets.map { it.name }
        )
    }
}
