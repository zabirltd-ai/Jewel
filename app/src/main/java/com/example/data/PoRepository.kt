package com.example.data

import android.content.Context
import com.example.R
import com.example.model.AuditAction
import com.example.model.AuditLogEntry
import com.example.model.MerchandiserSettings
import com.example.model.OriginalRowSnapshot
import com.example.model.OriginalSourceSnapshot
import com.example.model.PoFileFormat
import com.example.model.PoLineItem
import com.example.model.PoStatus
import com.example.model.PoValidationWarning
import com.example.model.PurchaseOrderRecord
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Firestore Repository for LSL PO Transfer & Breakdown Manager.
 * Always connects to the named enterprise database via R.string.firestore_database_id.
 */
class PoRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireVerifiedUid(expectedUserId: String): String {
        val uid = auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
        check(uid == expectedUserId) { "Signed-in user changed; refusing cross-user Firestore operation." }
        return uid
    }

    private fun userPoCollection(userId: String) =
        db.collection("users").document(userId).collection("purchase_orders")

    private fun userSettingsDoc(userId: String) =
        db.collection("users").document(userId).collection("settings").document("default")

    // -------------------------------------------------------------------------
    // Real-time Observation Flows
    // -------------------------------------------------------------------------
    fun observePurchaseOrders(userId: String): Flow<List<PurchaseOrderRecord>> = flow {
        val path = "users/$userId/purchase_orders"
        emitAll(
            userPoCollection(userId)
                .snapshots()
                .map { snapshot ->
                    snapshot.documents
                        .mapNotNull { parsePoDocument(it) }
                        .sortedByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeAuditLogs(userId: String, poId: String): Flow<List<AuditLogEntry>> = flow {
        val path = "users/$userId/purchase_orders/$poId/audit_logs"
        emitAll(
            userPoCollection(userId).document(poId).collection("audit_logs")
                .snapshots()
                .map { snapshot ->
                    snapshot.documents
                        .mapNotNull { parseAuditDocument(it) }
                        .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeOriginalSource(userId: String, poId: String): Flow<OriginalSourceSnapshot?> = flow {
        val path = "users/$userId/purchase_orders/$poId/original_source"
        emitAll(
            userPoCollection(userId).document(poId).collection("original_source")
                .snapshots()
                .map { snapshot ->
                    snapshot.documents.firstOrNull()?.let { parseOriginalSourceDocument(it) }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeSettings(userId: String): Flow<MerchandiserSettings> = flow {
        val path = "users/$userId/settings/default"
        emitAll(
            userSettingsDoc(userId)
                .snapshots()
                .map { doc ->
                    if (doc.exists()) {
                        MerchandiserSettings(
                            id = doc.id,
                            userId = doc.getString("userId") ?: userId,
                            companyName = doc.getString("companyName")?.ifBlank { "LAILA STYLES LIMITED" }
                                ?: "LAILA STYLES LIMITED",
                            defaultCurrency = doc.getString("defaultCurrency")?.ifBlank { "USD" } ?: "USD",
                            defaultFactory = doc.getString("defaultFactory") ?: "",
                            strictValidation = doc.getBoolean("strictValidation") ?: true,
                            createdAt = doc.getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE),
                            updatedAt = doc.getTimestamp("updatedAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        )
                    } else {
                        MerchandiserSettings(userId = userId)
                    }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(toLog, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    // -------------------------------------------------------------------------
    // Atomic Write & Partial Update Mutations (Result<T> Crash-Safe)
    // -------------------------------------------------------------------------
    suspend fun saveNewPurchaseOrder(
        userId: String,
        po: PurchaseOrderRecord,
        originalSource: OriginalSourceSnapshot?,
        actorName: String
    ): Result<String> {
        val poId = "po_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val poPath = "users/$userId/purchase_orders/$poId"
        return try {
            val uid = requireVerifiedUid(userId)
            val safePoNumber = po.poNumber.trim().ifBlank { "PO-UNSPECIFIED" }.take(100)
            val safeBuyer = po.buyerName.trim().ifBlank { "Unspecified Buyer" }.take(150)
            val safeFileName = po.originalFileName.trim().ifBlank { "buyer_po.xlsx" }.take(255)
            val safeCurrency = po.currency.trim().ifBlank { "USD" }.take(12)

            val poRef = userPoCollection(uid).document(poId)
            val batch = db.batch()

            val poPayload = buildMap<String, Any> {
                put("userId", uid)
                put("poNumber", safePoNumber)
                put("buyerName", safeBuyer)
                put("poDate", po.poDate.take(40))
                put("deliveryDate", po.deliveryDate.take(40))
                put("shipDate", po.shipDate.take(40))
                put("destination", po.destination.take(150))
                put("factoryInfo", po.factoryInfo.take(200))
                put("fabricDetails", po.fabricDetails.take(500))
                put("currency", safeCurrency)
                put("status", po.status.name)
                put("originalFileName", safeFileName)
                put("originalFileFormat", po.originalFileFormat.name)
                put("originalSheetName", po.originalSheetName.take(120))
                put("originalHeaders", po.originalHeaders.take(80).map { it.take(100) })
                put("sizeColumns", po.sizeColumns.take(40).map { it.take(30) })
                put("lineItems", po.lineItems.take(250).map { it.toFirestoreMap() })
                put("validationWarnings", po.validationWarnings.take(200).map { it.toFirestoreMap() })
                put("totalQuantity", po.totalQuantity.coerceIn(0L, 100_000_000L))
                put("totalValue", po.totalValue.coerceAtLeast(0.0))
                put("styleCount", po.styleCount.coerceIn(0L, 10_000L))
                put("colorCount", po.colorCount.coerceIn(0L, 10_000L))
                put("warningCount", po.warningCount.coerceIn(0L, 10_000L))
                put("notes", po.notes.take(1000))
                put("createdAt", FieldValue.serverTimestamp())
                put("updatedAt", FieldValue.serverTimestamp())
            }
            batch.set(poRef, poPayload)

            if (originalSource != null) {
                val sourceRef = poRef.collection("original_source").document("source_primary")
                val rowMaps = originalSource.rows.take(500).map { row ->
                    mapOf(
                        "rowIndex" to row.rowIndex,
                        "cells" to row.cells.take(80).map { it.take(200) }
                    )
                }
                val sourcePayload = mapOf(
                    "userId" to uid,
                    "poId" to poId,
                    "fileName" to originalSource.fileName.ifBlank { safeFileName }.take(255),
                    "fileFormat" to originalSource.fileFormat.name,
                    "sheetName" to originalSource.sheetName.take(120),
                    "headers" to originalSource.headers.take(80).map { it.take(100) },
                    "rowCount" to originalSource.rowCount.coerceIn(0L, 50_000L),
                    "rows" to rowMaps,
                    "columnMapping" to originalSource.columnMapping,
                    "createdAt" to FieldValue.serverTimestamp()
                )
                batch.set(sourceRef, sourcePayload)
            }

            val logId = "log_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            val logRef = poRef.collection("audit_logs").document(logId)
            val logPayload = mapOf(
                "userId" to uid,
                "poId" to poId,
                "action" to AuditAction.UPLOADED.name,
                "summary" to "Saved PO $safePoNumber ($safeBuyer) from $safeFileName — ${po.totalQuantity} pcs across ${po.styleCount} styles.".take(500),
                "actorName" to actorName.take(150),
                "createdAt" to FieldValue.serverTimestamp()
            )
            batch.set(logRef, logPayload)

            batch.commit().await()
            Result.success(poId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, poPath)
            Result.failure(e)
        }
    }

    suspend fun updatePurchaseOrder(
        userId: String,
        po: PurchaseOrderRecord,
        auditAction: AuditAction,
        auditSummary: String,
        actorName: String
    ): Result<Unit> {
        val poPath = "users/$userId/purchase_orders/${po.id}"
        return try {
            val uid = requireVerifiedUid(userId)
            require(po.id.isNotBlank()) { "PO document ID must not be blank for update." }
            val poRef = userPoCollection(uid).document(po.id)
            val batch = db.batch()

            // Partial update preserving immutable userId, createdAt, originalFileName, originalFileFormat
            val updatePayload = mapOf(
                "poNumber" to po.poNumber.trim().ifBlank { "PO-UNSPECIFIED" }.take(100),
                "buyerName" to po.buyerName.trim().ifBlank { "Unspecified Buyer" }.take(150),
                "poDate" to po.poDate.take(40),
                "deliveryDate" to po.deliveryDate.take(40),
                "shipDate" to po.shipDate.take(40),
                "destination" to po.destination.take(150),
                "factoryInfo" to po.factoryInfo.take(200),
                "fabricDetails" to po.fabricDetails.take(500),
                "currency" to po.currency.trim().ifBlank { "USD" }.take(12),
                "status" to po.status.name,
                "sizeColumns" to po.sizeColumns.take(40).map { it.take(30) },
                "lineItems" to po.lineItems.take(250).map { it.toFirestoreMap() },
                "validationWarnings" to po.validationWarnings.take(200).map { it.toFirestoreMap() },
                "totalQuantity" to po.totalQuantity.coerceIn(0L, 100_000_000L),
                "totalValue" to po.totalValue.coerceAtLeast(0.0),
                "styleCount" to po.styleCount.coerceIn(0L, 10_000L),
                "colorCount" to po.colorCount.coerceIn(0L, 10_000L),
                "warningCount" to po.warningCount.coerceIn(0L, 10_000L),
                "notes" to po.notes.take(1000),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            batch.update(poRef, updatePayload)

            val logId = "log_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            val logRef = poRef.collection("audit_logs").document(logId)
            batch.set(
                logRef,
                mapOf(
                    "userId" to uid,
                    "poId" to po.id,
                    "action" to auditAction.name,
                    "summary" to auditSummary.ifBlank { "Updated PO breakdown." }.take(500),
                    "actorName" to actorName.take(150),
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, poPath)
            Result.failure(e)
        }
    }

    suspend fun recordExportOrPrintEvent(
        userId: String,
        poId: String,
        action: AuditAction,
        summary: String,
        actorName: String,
        markExported: Boolean
    ): Result<Unit> {
        val poPath = "users/$userId/purchase_orders/$poId"
        return try {
            val uid = requireVerifiedUid(userId)
            require(poId.isNotBlank()) { "PO ID must not be blank." }
            val poRef = userPoCollection(uid).document(poId)
            val batch = db.batch()
            if (markExported) {
                batch.update(
                    poRef,
                    mapOf(
                        "status" to PoStatus.EXPORTED.name,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            val logId = "log_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            batch.set(
                poRef.collection("audit_logs").document(logId),
                mapOf(
                    "userId" to uid,
                    "poId" to poId,
                    "action" to action.name,
                    "summary" to summary.take(500),
                    "actorName" to actorName.take(150),
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, poPath)
            Result.failure(e)
        }
    }

    suspend fun deletePurchaseOrder(userId: String, poId: String): Result<Unit> {
        val poPath = "users/$userId/purchase_orders/$poId"
        return try {
            val uid = requireVerifiedUid(userId)
            require(poId.isNotBlank()) { "PO ID must not be blank." }
            val poRef = userPoCollection(uid).document(poId)
            // Clean up subcollections before deleting parent
            val sources = poRef.collection("original_source").get().await()
            val logs = poRef.collection("audit_logs").get().await()
            val batch = db.batch()
            sources.documents.forEach { batch.delete(it.reference) }
            logs.documents.forEach { batch.delete(it.reference) }
            batch.delete(poRef)
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, poPath)
            Result.failure(e)
        }
    }

    suspend fun saveSettings(userId: String, settings: MerchandiserSettings): Result<Unit> {
        val path = "users/$userId/settings/default"
        return try {
            val uid = requireVerifiedUid(userId)
            val docRef = userSettingsDoc(uid)
            val existing = docRef.get().await()
            if (existing.exists()) {
                docRef.update(
                    mapOf(
                        "companyName" to settings.companyName.trim().ifBlank { "LAILA STYLES LIMITED" }.take(120),
                        "defaultCurrency" to settings.defaultCurrency.trim().ifBlank { "USD" }.take(12),
                        "defaultFactory" to settings.defaultFactory.trim().take(150),
                        "strictValidation" to settings.strictValidation,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "companyName" to settings.companyName.trim().ifBlank { "LAILA STYLES LIMITED" }.take(120),
                        "defaultCurrency" to settings.defaultCurrency.trim().ifBlank { "USD" }.take(12),
                        "defaultFactory" to settings.defaultFactory.trim().take(150),
                        "strictValidation" to settings.strictValidation,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    // -------------------------------------------------------------------------
    // Safe Snapshot Deserialization Helpers (Number Coercion & ESTIMATE)
    // -------------------------------------------------------------------------
    @Suppress("UNCHECKED_CAST")
    private fun parsePoDocument(doc: DocumentSnapshot): PurchaseOrderRecord? {
        val data = doc.data ?: return null
        val rawLineItems = (data["lineItems"] as? List<*>)
            ?.mapNotNull { it as? Map<String, Any?> }
            ?.map { PoLineItem.fromMap(it) }
            ?: emptyList()
        val rawWarnings = (data["validationWarnings"] as? List<*>)
            ?.mapNotNull { it as? Map<String, Any?> }
            ?.map { PoValidationWarning.fromMap(it) }
            ?: emptyList()
        val rawSizes = (data["sizeColumns"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        val rawHeaders = (data["originalHeaders"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

        return PurchaseOrderRecord(
            id = doc.id,
            userId = data["userId"] as? String ?: "",
            poNumber = data["poNumber"] as? String ?: "",
            buyerName = data["buyerName"] as? String ?: "",
            poDate = data["poDate"] as? String ?: "",
            deliveryDate = data["deliveryDate"] as? String ?: "",
            shipDate = data["shipDate"] as? String ?: "",
            destination = data["destination"] as? String ?: "",
            factoryInfo = data["factoryInfo"] as? String ?: "",
            fabricDetails = data["fabricDetails"] as? String ?: "",
            currency = data["currency"] as? String ?: "USD",
            status = PoStatus.fromRaw(data["status"] as? String),
            originalFileName = data["originalFileName"] as? String ?: "",
            originalFileFormat = PoFileFormat.fromRaw(data["originalFileFormat"] as? String),
            originalSheetName = data["originalSheetName"] as? String ?: "",
            originalHeaders = rawHeaders,
            sizeColumns = rawSizes,
            lineItems = rawLineItems,
            validationWarnings = rawWarnings,
            totalQuantity = (data["totalQuantity"] as? Number)?.toLong() ?: 0L,
            totalValue = (data["totalValue"] as? Number)?.toDouble() ?: 0.0,
            styleCount = (data["styleCount"] as? Number)?.toLong() ?: 0L,
            colorCount = (data["colorCount"] as? Number)?.toLong() ?: 0L,
            warningCount = (data["warningCount"] as? Number)?.toLong() ?: 0L,
            notes = data["notes"] as? String ?: "",
            createdAt = doc.getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE),
            updatedAt = doc.getTimestamp("updatedAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        )
    }

    private fun parseAuditDocument(doc: DocumentSnapshot): AuditLogEntry? {
        val data = doc.data ?: return null
        return AuditLogEntry(
            id = doc.id,
            userId = data["userId"] as? String ?: "",
            poId = data["poId"] as? String ?: "",
            action = AuditAction.fromRaw(data["action"] as? String),
            summary = data["summary"] as? String ?: "",
            actorName = data["actorName"] as? String ?: "",
            createdAt = doc.getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseOriginalSourceDocument(doc: DocumentSnapshot): OriginalSourceSnapshot? {
        val data = doc.data ?: return null
        val headers = (data["headers"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        val rawRows = (data["rows"] as? List<*>)?.mapNotNull { entry ->
            val m = entry as? Map<String, Any?> ?: return@mapNotNull null
            val idx = (m["rowIndex"] as? Number)?.toLong() ?: 0L
            val cells = (m["cells"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            OriginalRowSnapshot(rowIndex = idx, cells = cells)
        } ?: emptyList()
        val mapping = (data["columnMapping"] as? Map<String, Any>) ?: emptyMap()

        return OriginalSourceSnapshot(
            id = doc.id,
            userId = data["userId"] as? String ?: "",
            poId = data["poId"] as? String ?: "",
            fileName = data["fileName"] as? String ?: "",
            fileFormat = PoFileFormat.fromRaw(data["fileFormat"] as? String),
            sheetName = data["sheetName"] as? String ?: "",
            headers = headers,
            rowCount = (data["rowCount"] as? Number)?.toLong() ?: rawRows.size.toLong(),
            rows = rawRows,
            columnMapping = mapping,
            createdAt = doc.getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        )
    }
}
