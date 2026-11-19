package com.example.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.PoRepository
import com.example.engine.PoTransferEngine
import com.example.engine.SpreadsheetParser
import com.example.model.AppDestination
import com.example.model.AuditAction
import com.example.model.AuditLogEntry
import com.example.model.ColumnMapping
import com.example.model.ExtractedMetadata
import com.example.model.MerchandiserSettings
import com.example.model.OriginalSourceSnapshot
import com.example.model.ParsedWorkbook
import com.example.model.PoLineItem
import com.example.model.PoStatus
import com.example.model.PurchaseOrderRecord
import com.example.model.UiState
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.UUID

data class UploadStagingState(
    val isParsing: Boolean = false,
    val parseError: String? = null,
    val workbook: ParsedWorkbook? = null,
    val selectedSheetIndex: Int = 0,
    val metadata: ExtractedMetadata = ExtractedMetadata(),
    val mapping: ColumnMapping = ColumnMapping()
)

data class ActivePoState(
    val po: PurchaseOrderRecord? = null,
    val originalSource: OriginalSourceSnapshot? = null,
    val hasUnsavedChanges: Boolean = false,
    val isSaving: Boolean = false,
    val duplicatePoWarningNumber: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class LslPoViewModel(
    private val repository: PoRepository,
    private val currentUserId: String,
    private val userDisplayName: String
) : ViewModel() {

    // Navigation state with backstack support for BackHandler
    private val _navStack = MutableStateFlow(listOf(AppDestination.DASHBOARD))
    val currentDestination: StateFlow<AppDestination> = _navStack
        .map { it.lastOrNull() ?: AppDestination.DASHBOARD }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), AppDestination.DASHBOARD)

    val canNavigateBack: StateFlow<Boolean> = _navStack
        .map { it.size > 1 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), false)

    // Real-time Firestore PO Records stream with two-tier Flow error unwrapping
    val savedPosState: StateFlow<UiState<List<PurchaseOrderRecord>>> = repository
        .observePurchaseOrders(currentUserId)
        .map<List<PurchaseOrderRecord>, UiState<List<PurchaseOrderRecord>>> { UiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing purchase orders", rootError)
            emit(UiState.Error(rootError.message ?: "Failed to load saved Purchase Orders."))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = UiState.Loading
        )

    // Real-time Merchandiser Settings stream
    val settingsState: StateFlow<MerchandiserSettings> = repository
        .observeSettings(currentUserId)
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            Log.w(TAG, "Error observing settings", firestoreError ?: error)
            emit(MerchandiserSettings(userId = currentUserId))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = MerchandiserSettings(userId = currentUserId)
        )

    // Upload & Flexible Column Mapping Staging State
    private val _uploadState = MutableStateFlow(UploadStagingState())
    val uploadState: StateFlow<UploadStagingState> = _uploadState.asStateFlow()

    // Active PO in Transfer / Breakdown / Export / Print workspace
    private val _activePoState = MutableStateFlow(ActivePoState())
    val activePoState: StateFlow<ActivePoState> = _activePoState.asStateFlow()

    // Observed Audit Logs for the currently open saved PO
    val activeAuditLogs: StateFlow<List<AuditLogEntry>> = _activePoState
        .map { it.po?.id.orEmpty() }
        .flatMapLatest { poId ->
            if (poId.isBlank()) flowOf(emptyList())
            else repository.observeAuditLogs(currentUserId, poId)
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    // Observed Immutable Original Source Snapshot for currently open saved PO
    val savedOriginalSource: StateFlow<OriginalSourceSnapshot?> = _activePoState
        .map { it.po?.id.orEmpty() }
        .flatMapLatest { poId ->
            if (poId.isBlank()) flowOf(null)
            else repository.observeOriginalSource(currentUserId, poId)
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    emit(null)
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    // Snackbar / Status banner message
    private val _bannerMessage = MutableStateFlow<String?>(null)
    val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

    // -------------------------------------------------------------------------
    // Navigation Actions
    // -------------------------------------------------------------------------
    fun navigateTo(destination: AppDestination) {
        _navStack.update { stack ->
            if (stack.lastOrNull() == destination) stack
            else if (destination == AppDestination.DASHBOARD) listOf(AppDestination.DASHBOARD)
            else stack + destination
        }
    }

    fun navigateBack() {
        _navStack.update { stack ->
            if (stack.size > 1) stack.dropLast(1) else stack
        }
    }

    fun clearBanner() {
        _bannerMessage.value = null
    }

    fun showBanner(msg: String) {
        _bannerMessage.value = msg
    }

    // -------------------------------------------------------------------------
    // Phase 1: Upload Original Buyer PO, Auto-Detect Headers & Preview
    // -------------------------------------------------------------------------
    fun parseUploadedFile(fileName: String, inputStreamProvider: () -> InputStream?) {
        viewModelScope.launch {
            _uploadState.update { it.copy(isParsing = true, parseError = null) }
            try {
                val defaultCurrency = settingsState.value.defaultCurrency
                val parsed = withContext(Dispatchers.IO) {
                    val stream = inputStreamProvider()
                        ?: throw IllegalStateException("Could not open selected file.")
                    stream.use { SpreadsheetParser.parse(fileName, it) }
                }
                val firstSheet = parsed.sheets.first()
                val (metadata, mapping) = PoTransferEngine.analyzeSheet(firstSheet, defaultCurrency)
                _uploadState.value = UploadStagingState(
                    isParsing = false,
                    parseError = null,
                    workbook = parsed,
                    selectedSheetIndex = 0,
                    metadata = metadata,
                    mapping = mapping
                )
                showBanner("Loaded '$fileName' (${parsed.sheets.size} sheet${if (parsed.sheets.size > 1) "s" else ""}). Review detected columns below.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse buyer PO file", e)
                _uploadState.update {
                    it.copy(
                        isParsing = false,
                        parseError = e.localizedMessage ?: "Failed to read file. Verify it is a valid .xlsx, .xls, or .csv PO."
                    )
                }
            }
        }
    }

    fun selectUploadSheet(sheetIndex: Int) {
        val wb = _uploadState.value.workbook ?: return
        val safeIdx = sheetIndex.coerceIn(0, wb.sheets.lastIndex)
        val sheet = wb.sheets[safeIdx]
        val (metadata, mapping) = PoTransferEngine.analyzeSheet(sheet, settingsState.value.defaultCurrency)
        _uploadState.update {
            it.copy(selectedSheetIndex = safeIdx, metadata = metadata, mapping = mapping)
        }
    }

    fun updateHeaderRowIndex(newHeaderRow: Int) {
        val wb = _uploadState.value.workbook ?: return
        val sheet = wb.sheets.getOrNull(_uploadState.value.selectedSheetIndex) ?: return
        val clamped = newHeaderRow.coerceIn(0, (sheet.rows.size - 1).coerceAtLeast(0))
        val headers = sheet.rows.getOrElse(clamped) { emptyList() }
        val newMapping = PoTransferEngine.autoDetectColumnMapping(headers, clamped, sheet.rows)
        _uploadState.update { it.copy(mapping = newMapping) }
    }

    fun updateUploadMetadata(transform: (ExtractedMetadata) -> ExtractedMetadata) {
        _uploadState.update { it.copy(metadata = transform(it.metadata)) }
    }

    fun updateColumnMapping(transform: (ColumnMapping) -> ColumnMapping) {
        _uploadState.update { it.copy(mapping = transform(it.mapping)) }
    }

    fun toggleSizeMatrixColumn(colIndex: Int) {
        _uploadState.update { state ->
            val current = state.mapping.sizeMatrixColumns.toMutableList()
            if (colIndex in current) current.remove(colIndex) else current.add(colIndex)
            current.sort()
            state.copy(
                mapping = state.mapping.copy(
                    sizeMatrixColumns = current,
                    singleSizeColumn = if (current.isNotEmpty()) null else state.mapping.singleSizeColumn
                )
            )
        }
    }

    fun clearUploadStaging() {
        _uploadState.value = UploadStagingState()
    }

    // -------------------------------------------------------------------------
    // Phase 2: Automatic PO Transfer to Standardized LSL Format
    // -------------------------------------------------------------------------
    fun transferUploadedPo() {
        val state = _uploadState.value
        val wb = state.workbook ?: return
        val sheet = wb.sheets.getOrNull(state.selectedSheetIndex) ?: return
        val existingPoNums = currentSavedPoList().map { it.poNumber }.toSet()

        val (transferredPo, sourceSnapshot) = PoTransferEngine.transferToStandardizedPo(
            userId = currentUserId,
            fileName = wb.fileName,
            fileFormat = wb.format,
            sheet = sheet,
            metadata = state.metadata,
            mapping = state.mapping,
            existingPoNumbers = existingPoNums
        )

        _activePoState.value = ActivePoState(
            po = transferredPo,
            originalSource = sourceSnapshot,
            hasUnsavedChanges = true,
            isSaving = false,
            duplicatePoWarningNumber = null
        )
        navigateTo(AppDestination.PO_TRANSFER)
        val warnCount = transferredPo.validationWarnings.size
        showBanner(
            if (warnCount > 0) "Transferred PO ${transferredPo.poNumber} with $warnCount validation notice(s) to review."
            else "Transferred PO ${transferredPo.poNumber} cleanly — all totals verified."
        )
    }

    // -------------------------------------------------------------------------
    // Open Saved PO & Interactive Editing (Auto-Recalculates Totals & Validation)
    // -------------------------------------------------------------------------
    fun openSavedPo(po: PurchaseOrderRecord, targetDestination: AppDestination = AppDestination.PO_BREAKDOWN) {
        _activePoState.value = ActivePoState(
            po = po,
            originalSource = null,
            hasUnsavedChanges = false,
            isSaving = false,
            duplicatePoWarningNumber = null
        )
        navigateTo(targetDestination)
    }

    fun updateActivePoMetadata(transform: (PurchaseOrderRecord) -> PurchaseOrderRecord) {
        mutateActivePo(transform)
    }

    fun updateLineItem(lineId: String, transform: (PoLineItem) -> PoLineItem) {
        mutateActivePo { po ->
            po.copy(lineItems = po.lineItems.map { if (it.lineId == lineId) transform(it) else it })
        }
    }

    fun updateLineSizeQuantity(lineId: String, sizeName: String, newQty: Long) {
        val safeQty = newQty.coerceAtLeast(0L)
        mutateActivePo { po ->
            val updatedItems = po.lineItems.map { item ->
                if (item.lineId != lineId) item
                else {
                    val newSizes = item.sizeQuantities.toMutableMap()
                    newSizes[sizeName] = safeQty
                    val newSizeSum = newSizes.values.sum()
                    item.copy(
                        sizeQuantities = newSizes,
                        declaredQty = if (item.declaredQty == item.sizeTotal || item.declaredQty == 0L) newSizeSum else item.declaredQty
                    )
                }
            }
            po.copy(lineItems = updatedItems)
        }
    }

    fun addLineItem(
        styleNumber: String,
        styleDescription: String,
        color: String,
        garmentType: String,
        unitPrice: Double,
        deliveryDate: String,
        destination: String
    ) {
        mutateActivePo { po ->
            val zeroSizes = po.sizeColumns.associateWith { 0L }
            val newItem = PoLineItem(
                lineId = "ln_${UUID.randomUUID().toString().take(8)}",
                styleNumber = styleNumber.trim(),
                styleDescription = styleDescription.trim(),
                color = color.trim(),
                garmentType = garmentType.trim(),
                unitPrice = unitPrice.coerceAtLeast(0.0),
                declaredQty = 0L,
                sizeQuantities = zeroSizes,
                deliveryDate = deliveryDate.trim().ifBlank { po.deliveryDate },
                shipDate = po.shipDate,
                destination = destination.trim().ifBlank { po.destination },
                factoryInfo = po.factoryInfo,
                fabricDetails = po.fabricDetails
            )
            po.copy(lineItems = po.lineItems + newItem)
        }
    }

    fun removeLineItem(lineId: String) {
        mutateActivePo { po ->
            po.copy(lineItems = po.lineItems.filterNot { it.lineId == lineId })
        }
    }

    fun addSizeColumn(sizeLabel: String) {
        val trimmed = sizeLabel.trim()
        if (trimmed.isBlank()) return
        mutateActivePo { po ->
            if (po.sizeColumns.any { it.equals(trimmed, ignoreCase = true) }) return@mutateActivePo po
            val newSizes = po.sizeColumns + trimmed
            val updatedItems = po.lineItems.map { item ->
                val map = item.sizeQuantities.toMutableMap()
                map.putIfAbsent(trimmed, 0L)
                item.copy(sizeQuantities = map)
            }
            po.copy(sizeColumns = newSizes, lineItems = updatedItems)
        }
    }

    fun removeSizeColumn(sizeLabel: String) {
        mutateActivePo { po ->
            val newSizes = po.sizeColumns.filterNot { it == sizeLabel }
            val updatedItems = po.lineItems.map { item ->
                val map = item.sizeQuantities.toMutableMap()
                map.remove(sizeLabel)
                item.copy(sizeQuantities = map)
            }
            po.copy(sizeColumns = newSizes, lineItems = updatedItems)
        }
    }

    fun alignDeclaredQtyToSizeSum(lineId: String) {
        mutateActivePo { po ->
            po.copy(
                lineItems = po.lineItems.map { item ->
                    if (item.lineId == lineId) item.copy(declaredQty = item.sizeTotal) else item
                }
            )
        }
    }

    private fun mutateActivePo(transform: (PurchaseOrderRecord) -> PurchaseOrderRecord) {
        val current = _activePoState.value.po ?: return
        val existingPoNums = currentSavedPoList()
            .filter { it.id != current.id }
            .map { it.poNumber }
            .toSet()
        val updated = PoTransferEngine.recalculateAndValidate(
            po = transform(current),
            existingPoNumbers = existingPoNums
        )
        _activePoState.update { it.copy(po = updated, hasUnsavedChanges = true) }
    }

    // -------------------------------------------------------------------------
    // Permanent Cloud Save, Verification & Duplicate PO Guard
    // -------------------------------------------------------------------------
    fun requestSaveActivePo(markVerified: Boolean = false, forceDuplicateSave: Boolean = false) {
        val state = _activePoState.value
        val po = state.po ?: return
        if (state.isSaving) return

        // Duplicate PO guard for brand-new unsaved POs
        val isNew = po.id.isBlank()
        if (isNew && !forceDuplicateSave) {
            val dup = currentSavedPoList().firstOrNull {
                it.poNumber.equals(po.poNumber.trim(), ignoreCase = true)
            }
            if (dup != null) {
                _activePoState.update { it.copy(duplicatePoWarningNumber = dup.poNumber) }
                return
            }
        }

        val targetStatus = when {
            markVerified -> PoStatus.VERIFIED
            po.status == PoStatus.DRAFT -> PoStatus.PENDING_VERIFICATION
            else -> po.status
        }
        val toPersist = po.copy(status = targetStatus)

        viewModelScope.launch {
            _activePoState.update { it.copy(isSaving = true, duplicatePoWarningNumber = null) }
            if (isNew) {
                val result = repository.saveNewPurchaseOrder(
                    userId = currentUserId,
                    po = toPersist,
                    originalSource = state.originalSource,
                    actorName = userDisplayName
                )
                result.fold(
                    onSuccess = { newId ->
                        _activePoState.update {
                            it.copy(
                                po = toPersist.copy(id = newId),
                                hasUnsavedChanges = false,
                                isSaving = false
                            )
                        }
                        showBanner("Saved PO ${toPersist.poNumber} permanently to LSL Cloud Database.")
                    },
                    onFailure = { err ->
                        _activePoState.update { it.copy(isSaving = false) }
                        showBanner("Save failed: ${err.localizedMessage ?: "Permission or network error."}")
                    }
                )
            } else {
                val action = if (markVerified) AuditAction.VERIFIED else AuditAction.EDITED
                val summary = if (markVerified) {
                    "Verified PO ${toPersist.poNumber} (${toPersist.totalQuantity} pcs, ${toPersist.styleCount} styles)."
                } else {
                    "Updated PO ${toPersist.poNumber} — ${toPersist.totalQuantity} pcs across ${toPersist.styleCount} styles."
                }
                val result = repository.updatePurchaseOrder(
                    userId = currentUserId,
                    po = toPersist,
                    auditAction = action,
                    auditSummary = summary,
                    actorName = userDisplayName
                )
                result.fold(
                    onSuccess = {
                        _activePoState.update {
                            it.copy(po = toPersist, hasUnsavedChanges = false, isSaving = false)
                        }
                        showBanner(if (markVerified) "PO ${toPersist.poNumber} marked Verified." else "Changes saved to PO ${toPersist.poNumber}.")
                    },
                    onFailure = { err ->
                        _activePoState.update { it.copy(isSaving = false) }
                        showBanner("Update failed: ${err.localizedMessage ?: "Permission or network error."}")
                    }
                )
            }
        }
    }

    fun dismissDuplicateWarning() {
        _activePoState.update { it.copy(duplicatePoWarningNumber = null) }
    }

    fun deleteSavedPo(po: PurchaseOrderRecord) {
        if (po.id.isBlank()) return
        viewModelScope.launch {
            val result = repository.deletePurchaseOrder(currentUserId, po.id)
            result.fold(
                onSuccess = {
                    if (_activePoState.value.po?.id == po.id) {
                        _activePoState.value = ActivePoState()
                    }
                    showBanner("Deleted PO ${po.poNumber}.")
                },
                onFailure = { err ->
                    showBanner("Delete failed: ${err.localizedMessage ?: "Unable to remove PO."}")
                }
            )
        }
    }

    fun recordExportAudit(action: AuditAction, summary: String) {
        val po = _activePoState.value.po ?: return
        if (po.id.isBlank()) return
        viewModelScope.launch {
            val markExported = action == AuditAction.EXPORTED_EXCEL || action == AuditAction.EXPORTED_PDF
            repository.recordExportOrPrintEvent(
                userId = currentUserId,
                poId = po.id,
                action = action,
                summary = summary,
                actorName = userDisplayName,
                markExported = markExported
            )
            if (markExported) {
                _activePoState.update { s ->
                    s.copy(po = s.po?.copy(status = PoStatus.EXPORTED))
                }
            }
        }
    }

    fun saveMerchandiserSettings(settings: MerchandiserSettings) {
        viewModelScope.launch {
            val res = repository.saveSettings(currentUserId, settings)
            res.fold(
                onSuccess = { showBanner("LSL Merchandiser settings saved.") },
                onFailure = { err -> showBanner("Failed to save settings: ${err.localizedMessage}") }
            )
        }
    }

    private fun currentSavedPoList(): List<PurchaseOrderRecord> =
        (savedPosState.value as? UiState.Success)?.data.orEmpty()

    companion object {
        private const val TAG = "LslPoVM"
    }
}
