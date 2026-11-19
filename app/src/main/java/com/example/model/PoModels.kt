package com.example.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import java.util.UUID

enum class AppDestination(val title: String, val shortTitle: String) {
    DASHBOARD("Dashboard", "Dashboard"),
    UPLOAD_PO("Upload New PO", "Upload"),
    PO_TRANSFER("PO Transfer", "Transfer"),
    PO_BREAKDOWN("PO Breakdown", "Breakdown"),
    SAVED_POS("Saved POs", "Saved"),
    EXCEL_EXPORT("Excel Export", "Excel"),
    PDF_PRINT("PDF & Print", "PDF/Print"),
    SETTINGS("Settings", "Settings")
}

enum class PoStatus(val label: String) {
    DRAFT("Draft"),
    PENDING_VERIFICATION("Pending Verification"),
    VERIFIED("Verified"),
    EXPORTED("Exported");

    companion object {
        fun fromRaw(raw: String?): PoStatus =
            entries.firstOrNull { it.name == raw } ?: PENDING_VERIFICATION
    }
}

enum class PoFileFormat(val extension: String) {
    XLSX("xlsx"),
    XLS("xls"),
    CSV("csv");

    companion object {
        fun fromRaw(raw: String?): PoFileFormat =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: XLSX

        fun detectFromFileName(fileName: String): PoFileFormat {
            val lower = fileName.lowercase()
            return when {
                lower.endsWith(".csv") || lower.endsWith(".tsv") || lower.endsWith(".txt") -> CSV
                lower.endsWith(".xls") -> XLS
                else -> XLSX
            }
        }
    }
}

enum class AuditAction(val label: String) {
    UPLOADED("Uploaded"),
    TRANSFERRED("Transferred"),
    EDITED("Edited"),
    VERIFIED("Verified"),
    EXPORTED_EXCEL("Exported Excel"),
    EXPORTED_PDF("Exported PDF"),
    PRINTED("Printed");

    companion object {
        fun fromRaw(raw: String?): AuditAction =
            entries.firstOrNull { it.name == raw } ?: EDITED
    }
}

enum class ValidationSeverity(val label: String) {
    ERROR("Error"),
    WARNING("Warning");

    companion object {
        fun fromRaw(raw: String?): ValidationSeverity =
            entries.firstOrNull { it.name == raw } ?: WARNING
    }
}

@Keep
data class PoValidationWarning(
    val code: String = "",
    val severity: ValidationSeverity = ValidationSeverity.WARNING,
    val field: String = "",
    val message: String = ""
) {
    fun toFirestoreMap(): Map<String, Any> = mapOf(
        "code" to code.take(64),
        "severity" to severity.name,
        "field" to field.take(100),
        "message" to message.take(400)
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): PoValidationWarning = PoValidationWarning(
            code = map["code"] as? String ?: "",
            severity = ValidationSeverity.fromRaw(map["severity"] as? String),
            field = map["field"] as? String ?: "",
            message = map["message"] as? String ?: ""
        )
    }
}

@Keep
data class PoLineItem(
    val lineId: String = UUID.randomUUID().toString().take(12),
    val styleNumber: String = "",
    val styleDescription: String = "",
    val itemDescription: String = "",
    val color: String = "",
    val garmentType: String = "",
    val unitPrice: Double = 0.0,
    val declaredQty: Long = 0L,
    val sizeQuantities: Map<String, Long> = emptyMap(),
    val deliveryDate: String = "",
    val shipDate: String = "",
    val destination: String = "",
    val factoryInfo: String = "",
    val fabricDetails: String = ""
) {
    val sizeTotal: Long
        get() = sizeQuantities.values.sum()

    val effectiveQty: Long
        get() = if (sizeQuantities.isNotEmpty() && sizeTotal > 0L) sizeTotal else declaredQty

    val lineTotalValue: Double
        get() = effectiveQty * unitPrice

    fun toFirestoreMap(): Map<String, Any> = mapOf(
        "lineId" to lineId.take(40),
        "styleNumber" to styleNumber.take(100),
        "styleDescription" to styleDescription.take(250),
        "itemDescription" to itemDescription.take(250),
        "color" to color.take(100),
        "garmentType" to garmentType.take(100),
        "unitPrice" to unitPrice.coerceAtLeast(0.0),
        "declaredQty" to declaredQty.coerceAtLeast(0L),
        "sizeQuantities" to sizeQuantities.mapValues { (_, v) -> v.coerceAtLeast(0L) },
        "deliveryDate" to deliveryDate.take(40),
        "shipDate" to shipDate.take(40),
        "destination" to destination.take(150),
        "factoryInfo" to factoryInfo.take(200),
        "fabricDetails" to fabricDetails.take(300)
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): PoLineItem {
            val rawSizes = map["sizeQuantities"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val parsedSizes = linkedMapOf<String, Long>()
            for ((k, v) in rawSizes) {
                val key = k as? String ?: continue
                val qty = (v as? Number)?.toLong() ?: 0L
                parsedSizes[key] = qty
            }
            return PoLineItem(
                lineId = (map["lineId"] as? String)?.ifBlank { UUID.randomUUID().toString().take(12) }
                    ?: UUID.randomUUID().toString().take(12),
                styleNumber = map["styleNumber"] as? String ?: "",
                styleDescription = map["styleDescription"] as? String ?: "",
                itemDescription = map["itemDescription"] as? String ?: "",
                color = map["color"] as? String ?: "",
                garmentType = map["garmentType"] as? String ?: "",
                unitPrice = (map["unitPrice"] as? Number)?.toDouble() ?: 0.0,
                declaredQty = (map["declaredQty"] as? Number)?.toLong() ?: 0L,
                sizeQuantities = parsedSizes,
                deliveryDate = map["deliveryDate"] as? String ?: "",
                shipDate = map["shipDate"] as? String ?: "",
                destination = map["destination"] as? String ?: "",
                factoryInfo = map["factoryInfo"] as? String ?: "",
                fabricDetails = map["fabricDetails"] as? String ?: ""
            )
        }
    }
}

@Keep
data class PurchaseOrderRecord(
    val id: String = "",
    val userId: String = "",
    val poNumber: String = "",
    val buyerName: String = "",
    val poDate: String = "",
    val deliveryDate: String = "",
    val shipDate: String = "",
    val destination: String = "",
    val factoryInfo: String = "",
    val fabricDetails: String = "",
    val currency: String = "USD",
    val status: PoStatus = PoStatus.PENDING_VERIFICATION,
    val originalFileName: String = "",
    val originalFileFormat: PoFileFormat = PoFileFormat.XLSX,
    val originalSheetName: String = "",
    val originalHeaders: List<String> = emptyList(),
    val sizeColumns: List<String> = emptyList(),
    val lineItems: List<PoLineItem> = emptyList(),
    val validationWarnings: List<PoValidationWarning> = emptyList(),
    val totalQuantity: Long = 0L,
    val totalValue: Double = 0.0,
    val styleCount: Long = 0L,
    val colorCount: Long = 0L,
    val warningCount: Long = 0L,
    val notes: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

@Keep
data class OriginalRowSnapshot(
    val rowIndex: Long = 0L,
    val cells: List<String> = emptyList()
)

@Keep
data class OriginalSourceSnapshot(
    val id: String = "",
    val userId: String = "",
    val poId: String = "",
    val fileName: String = "",
    val fileFormat: PoFileFormat = PoFileFormat.XLSX,
    val sheetName: String = "",
    val headers: List<String> = emptyList(),
    val rowCount: Long = 0L,
    val rows: List<OriginalRowSnapshot> = emptyList(),
    val columnMapping: Map<String, Any> = emptyMap(),
    val createdAt: Timestamp? = null
)

@Keep
data class AuditLogEntry(
    val id: String = "",
    val userId: String = "",
    val poId: String = "",
    val action: AuditAction = AuditAction.EDITED,
    val summary: String = "",
    val actorName: String = "",
    val createdAt: Timestamp? = null
)

@Keep
data class MerchandiserSettings(
    val id: String = "default",
    val userId: String = "",
    val companyName: String = "LAILA STYLES LIMITED",
    val defaultCurrency: String = "USD",
    val defaultFactory: String = "",
    val strictValidation: Boolean = true,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

// Parsing & Flexible Column Mapping Models (in-memory staging before saving)
data class ParsedSheet(
    val name: String,
    val rows: List<List<String>>
)

data class ParsedWorkbook(
    val fileName: String,
    val format: PoFileFormat,
    val sheets: List<ParsedSheet>
)

data class ExtractedMetadata(
    val buyerName: String = "",
    val poNumber: String = "",
    val poDate: String = "",
    val deliveryDate: String = "",
    val shipDate: String = "",
    val destination: String = "",
    val factoryInfo: String = "",
    val fabricDetails: String = "",
    val currency: String = "USD"
)

data class ColumnMapping(
    val headerRowIndex: Int = 0,
    val buyerColumn: Int? = null,
    val poNumberColumn: Int? = null,
    val poDateColumn: Int? = null,
    val styleNumberColumn: Int? = null,
    val styleDescriptionColumn: Int? = null,
    val itemDescriptionColumn: Int? = null,
    val colorColumn: Int? = null,
    val garmentTypeColumn: Int? = null,
    val singleSizeColumn: Int? = null,
    val quantityColumn: Int? = null,
    val unitPriceColumn: Int? = null,
    val currencyColumn: Int? = null,
    val deliveryDateColumn: Int? = null,
    val shipDateColumn: Int? = null,
    val destinationColumn: Int? = null,
    val factoryInfoColumn: Int? = null,
    val fabricDetailsColumn: Int? = null,
    val sizeMatrixColumns: List<Int> = emptyList()
)

// Aggregated Breakdown Models
data class StyleBreakdownRow(
    val styleNumber: String,
    val description: String,
    val colors: String,
    val garmentType: String,
    val poQuantity: Long,
    val avgUnitPrice: Double,
    val totalValue: Double
)

data class ColorBreakdownRow(
    val styleNumber: String,
    val color: String,
    val garmentType: String,
    val quantity: Long,
    val sharePercent: Double,
    val totalValue: Double
)

data class DeliveryBreakdownRow(
    val deliveryDate: String,
    val shipDate: String,
    val destination: String,
    val factoryInfo: String,
    val styleCount: Int,
    val quantity: Long,
    val totalValue: Double
)

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
