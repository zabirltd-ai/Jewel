package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.model.PoLineItem
import com.example.model.PurchaseOrderRecord
import com.example.ui.components.DuplicatePoWarningDialog
import com.example.ui.components.EmptyPoWorkspaceCard
import com.example.ui.components.PoStatusBadge
import com.example.ui.components.ValidationSummaryCard
import com.example.ui.components.formatCurrency
import com.example.ui.components.formatInt
import com.example.ui.theme.LslErrorText
import com.example.ui.theme.LslForestGreen
import com.example.ui.theme.LslMintTint
import com.example.ui.theme.LslPureWhite
import com.example.ui.theme.LslWarningBg
import com.example.ui.theme.LslWarningBorder
import com.example.ui.theme.LslWarningText
import com.example.ui.theme.MonoNumericStyle
import com.example.viewmodel.ActivePoState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PoTransferScreen(
    activeState: ActivePoState,
    onGoToUpload: () -> Unit,
    onGoToSavedPos: () -> Unit,
    onGoToBreakdown: () -> Unit,
    onGoToExcelExport: () -> Unit,
    onGoToPdfPrint: () -> Unit,
    onUpdateMetadata: ((PurchaseOrderRecord) -> PurchaseOrderRecord) -> Unit,
    onUpdateLineItem: (String, (PoLineItem) -> PoLineItem) -> Unit,
    onUpdateLineSizeQty: (String, String, Long) -> Unit,
    onAlignDeclaredQty: (String) -> Unit,
    onAddLineItem: (String, String, String, String, Double, String, String) -> Unit,
    onRemoveLineItem: (String) -> Unit,
    onAddSizeColumn: (String) -> Unit,
    onRemoveSizeColumn: (String) -> Unit,
    onSavePo: (markVerified: Boolean, forceDuplicate: Boolean) -> Unit,
    onDismissDuplicateDialog: () -> Unit
) {
    val po = activeState.po
    if (po == null) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            EmptyPoWorkspaceCard(
                title = "No Active Purchase Order Loaded",
                subtitle = "Upload a buyer Excel/CSV PO file or open an existing saved PO to review, edit, validate, and standardize breakdowns.",
                primaryActionLabel = "Upload New PO",
                onPrimaryAction = onGoToUpload,
                secondaryActionLabel = "Open Saved POs",
                onSecondaryAction = onGoToSavedPos
            )
        }
        return
    }

    var showAddSizeDialog by remember { mutableStateOf(false) }
    var showAddLineDialog by remember { mutableStateOf(false) }
    var editingLine by remember { mutableStateOf<PoLineItem?>(null) }

    if (activeState.duplicatePoWarningNumber != null) {
        DuplicatePoWarningDialog(
            poNumber = activeState.duplicatePoWarningNumber,
            onDismiss = onDismissDuplicateDialog,
            onSaveAnyway = { onSavePo(false, true) }
        )
    }

    if (showAddSizeDialog) {
        AddSizeColumnDialog(
            onDismiss = { showAddSizeDialog = false },
            onAdd = { sizeLabel ->
                onAddSizeColumn(sizeLabel)
                showAddSizeDialog = false
            }
        )
    }

    if (showAddLineDialog) {
        AddOrEditLineDialog(
            initial = null,
            defaultDelivery = po.deliveryDate,
            defaultDestination = po.destination,
            onDismiss = { showAddLineDialog = false },
            onConfirm = { style, desc, color, garment, price, deliv, dest ->
                onAddLineItem(style, desc, color, garment, price, deliv, dest)
                showAddLineDialog = false
            }
        )
    }

    editingLine?.let { target ->
        AddOrEditLineDialog(
            initial = target,
            defaultDelivery = po.deliveryDate,
            defaultDestination = po.destination,
            onDismiss = { editingLine = null },
            onConfirm = { style, desc, color, garment, price, deliv, dest ->
                onUpdateLineItem(target.lineId) {
                    it.copy(
                        styleNumber = style,
                        styleDescription = desc,
                        color = color,
                        garmentType = garment,
                        unitPrice = price,
                        deliveryDate = deliv,
                        destination = dest
                    )
                }
                editingLine = null
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Sticky Summary & Action Controls Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "PO ${po.poNumber}",
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontFamily = MonoNumericStyle.fontFamily,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = LslForestGreen
                            )
                            Text(
                                text = "${po.buyerName}  •  Source: ${po.originalFileName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        PoStatusBadge(status = po.status)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MetricPill("Total Qty", "${formatInt(po.totalQuantity)} pcs", Modifier.weight(1f))
                        MetricPill("Total Value", formatCurrency(po.currency, po.totalValue), Modifier.weight(1f))
                        MetricPill("Styles / Colors", "${po.styleCount} / ${po.colorCount}", Modifier.weight(1f))
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onSavePo(false, false) },
                            enabled = !activeState.isSaving,
                            modifier = Modifier.testTag("transfer_save_po_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = LslForestGreen)
                        ) {
                            if (activeState.isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = LslPureWhite, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.Save, contentDescription = "Save PO", modifier = Modifier.size(17.dp))
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (po.id.isBlank()) "Save PO" else "Save Changes")
                        }

                        OutlinedButton(
                            onClick = { onSavePo(true, false) },
                            enabled = !activeState.isSaving,
                            modifier = Modifier.testTag("transfer_verify_po_button")
                        ) {
                            Icon(Icons.Outlined.Verified, contentDescription = "Verify PO", modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Verify & Save")
                        }

                        OutlinedButton(onClick = onGoToBreakdown) {
                            Icon(Icons.Outlined.TableChart, contentDescription = "Breakdowns", modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Breakdowns")
                        }

                        OutlinedButton(onClick = onGoToExcelExport) {
                            Text("Excel")
                        }

                        OutlinedButton(onClick = onGoToPdfPrint) {
                            Icon(Icons.Outlined.Print, contentDescription = "PDF and Print", modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("PDF & Print")
                        }
                    }

                    if (activeState.hasUnsavedChanges) {
                        Text(
                            text = "Unsaved edits in workspace — tap Save PO to persist permanently.",
                            style = MaterialTheme.typography.labelMedium,
                            color = LslWarningText
                        )
                    }
                }
            }
        }

        // Validation Report Banner
        item {
            ValidationSummaryCard(warnings = po.validationWarnings)
        }

        // Editable Standardized PO Header Information
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Standardized LSL PO Header", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = po.buyerName,
                            onValueChange = { v -> onUpdateMetadata { it.copy(buyerName = v) } },
                            label = { Text("Buyer Name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = po.poNumber,
                            onValueChange = { v -> onUpdateMetadata { it.copy(poNumber = v) } },
                            label = { Text("PO Number") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = po.poDate,
                            onValueChange = { v -> onUpdateMetadata { it.copy(poDate = v) } },
                            label = { Text("PO Date") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = po.deliveryDate,
                            onValueChange = { v -> onUpdateMetadata { it.copy(deliveryDate = v) } },
                            label = { Text("Delivery Date") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = po.shipDate,
                            onValueChange = { v -> onUpdateMetadata { it.copy(shipDate = v) } },
                            label = { Text("Ship Date") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = po.currency,
                            onValueChange = { v -> onUpdateMetadata { it.copy(currency = v.uppercase().take(6)) } },
                            label = { Text("Currency") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = po.destination,
                            onValueChange = { v -> onUpdateMetadata { it.copy(destination = v) } },
                            label = { Text("Destination") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = po.factoryInfo,
                            onValueChange = { v -> onUpdateMetadata { it.copy(factoryInfo = v) } },
                            label = { Text("Factory Info") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = po.fabricDetails,
                        onValueChange = { v -> onUpdateMetadata { it.copy(fabricDetails = v) } },
                        label = { Text("Fabric & Trim Details") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = po.notes,
                        onValueChange = { v -> onUpdateMetadata { it.copy(notes = v) } },
                        label = { Text("Merchandiser Remarks") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Dynamic Size Columns Manager
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Dynamic Size Columns (${po.sizeColumns.size})", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Add or remove alpha, numeric, kids, or custom buyer sizes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = { showAddSizeDialog = true },
                            modifier = Modifier.testTag("add_size_column_button")
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = "Add size", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add Size")
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        po.sizeColumns.forEach { sz ->
                            InputChip(
                                selected = true,
                                onClick = { onRemoveSizeColumn(sz) },
                                label = { Text(sz, fontFamily = MonoNumericStyle.fontFamily) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = "Remove size $sz",
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }

        // Style / Color / Size Line Items Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Style & Size Line Items (${po.lineItems.size})", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Edit descriptions, colors, prices, or size quantities — totals recalculate instantly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = { showAddLineDialog = true },
                    modifier = Modifier.testTag("add_line_item_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = LslForestGreen)
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "Add style/color line", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Color/Style")
                }
            }
        }

        // Editable Line Item Cards
        items(po.lineItems, key = { it.lineId }) { item ->
            EditablePoLineCard(
                item = item,
                sizeColumns = po.sizeColumns,
                currency = po.currency,
                onEditDetails = { editingLine = item },
                onDelete = { onRemoveLineItem(item.lineId) },
                onSizeQtyChange = { sz, q -> onUpdateLineSizeQty(item.lineId, sz, q) },
                onAlignDeclaredQty = { onAlignDeclaredQty(item.lineId) }
            )
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun MetricPill(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = LslMintTint
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = value,
                style = MonoNumericStyle.copy(fontWeight = FontWeight.Bold),
                color = LslForestGreen
            )
        }
    }
}

@Composable
private fun EditablePoLineCard(
    item: PoLineItem,
    sizeColumns: List<String>,
    currency: String,
    onEditDetails: () -> Unit,
    onDelete: () -> Unit,
    onSizeQtyChange: (String, Long) -> Unit,
    onAlignDeclaredQty: () -> Unit
) {
    val hasMismatch = item.declaredQty > 0L && item.sizeTotal > 0L && item.declaredQty != item.sizeTotal

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (hasMismatch) LslWarningBorder else MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${item.styleNumber.ifBlank { "(Missing Style)" }}  •  ${item.color.ifBlank { "(No Color)" }}",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = LslForestGreen
                    )
                    Text(
                        text = listOf(item.styleDescription, item.garmentType, formatCurrency(currency, item.unitPrice) + "/pc")
                            .filter { it.isNotBlank() }
                            .joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row {
                    IconButton(onClick = onEditDetails) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Edit style line", tint = LslForestGreen)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "Remove style line", tint = LslErrorText)
                    }
                }
            }

            if (hasMismatch) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = LslWarningBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Mismatch: Size sum (${item.sizeTotal}) != Buyer declared qty (${item.declaredQty})",
                            style = MaterialTheme.typography.bodySmall,
                            color = LslWarningText,
                            modifier = Modifier.weight(1f)
                        )
                        AssistChip(
                            onClick = onAlignDeclaredQty,
                            label = { Text("Accept Size Sum") }
                        )
                    }
                }
            }

            if (sizeColumns.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    sizeColumns.forEach { sz ->
                        val currentVal = item.sizeQuantities[sz] ?: 0L
                        var textState by remember(item.lineId, sz, currentVal) {
                            mutableStateOf(if (currentVal == 0L) "0" else currentVal.toString())
                        }
                        OutlinedTextField(
                            value = textState,
                            onValueChange = { raw ->
                                val filtered = raw.filter { it.isDigit() }.take(7)
                                textState = filtered
                                onSizeQtyChange(sz, filtered.toLongOrNull() ?: 0L)
                            },
                            label = { Text(sz, style = MaterialTheme.typography.labelSmall) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = MonoNumericStyle,
                            modifier = Modifier.width(82.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Line Qty: ${formatInt(item.effectiveQty)} pcs",
                    style = MonoNumericStyle.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Line Value: ${formatCurrency(currency, item.lineTotalValue)}",
                    style = MonoNumericStyle.copy(fontWeight = FontWeight.Bold),
                    color = LslForestGreen
                )
            }
        }
    }
}

@Composable
private fun AddSizeColumnDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit
) {
    var sizeText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Dynamic Size Column") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Enter any alpha (XXS, 3XL), numeric (28, 34), kids (2Y, 6-9M), or custom buyer size label.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = sizeText,
                    onValueChange = { sizeText = it.take(16) },
                    label = { Text("Size Label") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(sizeText) },
                enabled = sizeText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = LslForestGreen)
            ) { Text("Add Column") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun AddOrEditLineDialog(
    initial: PoLineItem?,
    defaultDelivery: String,
    defaultDestination: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String, Double, String, String) -> Unit
) {
    var style by remember { mutableStateOf(initial?.styleNumber ?: "") }
    var desc by remember { mutableStateOf(initial?.styleDescription ?: "") }
    var color by remember { mutableStateOf(initial?.color ?: "") }
    var garment by remember { mutableStateOf(initial?.garmentType ?: "") }
    var priceText by remember { mutableStateOf(if (initial != null && initial.unitPrice > 0.0) initial.unitPrice.toString() else "") }
    var delivery by remember { mutableStateOf(initial?.deliveryDate?.ifBlank { defaultDelivery } ?: defaultDelivery) }
    var destination by remember { mutableStateOf(initial?.destination?.ifBlank { defaultDestination } ?: defaultDestination) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add Style / Color Line" else "Edit Style / Color Line") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = style, onValueChange = { style = it }, label = { Text("Style Number *") }, singleLine = true)
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Style Description") }, singleLine = true)
                OutlinedTextField(value = color, onValueChange = { color = it }, label = { Text("Color *") }, singleLine = true)
                OutlinedTextField(value = garment, onValueChange = { garment = it }, label = { Text("Garment Type") }, singleLine = true)
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = { Text("Unit Price") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(value = delivery, onValueChange = { delivery = it }, label = { Text("Delivery Date") }, singleLine = true)
                OutlinedTextField(value = destination, onValueChange = { destination = it }, label = { Text("Destination") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(style, desc, color, garment, priceText.toDoubleOrNull() ?: 0.0, delivery, destination)
                },
                enabled = style.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = LslForestGreen)
            ) { Text(if (initial == null) "Add Line" else "Update") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
