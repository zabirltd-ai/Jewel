package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.example.model.ColumnMapping
import com.example.model.ExtractedMetadata
import com.example.ui.components.ExcelStyleDataTable
import com.example.ui.theme.LslEmeraldAccent
import com.example.ui.theme.LslErrorBg
import com.example.ui.theme.LslErrorText
import com.example.ui.theme.LslForestGreen
import com.example.ui.theme.LslMintBorder
import com.example.ui.theme.LslMintTint
import com.example.ui.theme.LslPureWhite
import com.example.viewmodel.UploadStagingState
import java.io.InputStream

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun UploadPoScreen(
    uploadState: UploadStagingState,
    isLaptopScreen: Boolean = false,
    onParseFile: (String, () -> InputStream?) -> Unit,
    onSelectSheet: (Int) -> Unit,
    onUpdateHeaderRow: (Int) -> Unit,
    onUpdateMetadata: ((ExtractedMetadata) -> ExtractedMetadata) -> Unit,
    onUpdateMapping: ((ColumnMapping) -> ColumnMapping) -> Unit,
    onToggleSizeColumn: (Int) -> Unit,
    onClearStaging: () -> Unit,
    onTransferToLslFormat: () -> Unit
) {
    val context = LocalContext.current
    var showColumnMapper by remember { mutableStateOf(true) }
    var isDragHovering by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = resolveDisplayName(context, uri)
            onParseFile(fileName) {
                context.contentResolver.openInputStream(uri)
            }
        }
    }

    val openBuyerFilePicker = {
        filePickerLauncher.launch(
            arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel",
                "text/csv",
                "text/comma-separated-values",
                "text/plain",
                "*/*"
            )
        )
    }

    // Native Laptop / ChromeOS / DeX Drag-and-Drop receiver for .xlsx, .xls, .csv files
    val dragAndDropCallback = remember(context, onParseFile) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                isDragHovering = true
            }

            override fun onExited(event: DragAndDropEvent) {
                isDragHovering = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                isDragHovering = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                isDragHovering = false
                val dragEvent = event.toAndroidDragEvent()
                val clipData = dragEvent.clipData ?: return false
                if (clipData.itemCount <= 0) return false
                val uri = clipData.getItemAt(0)?.uri ?: return false
                val activity = context.findHostActivity()
                val dropPermission = activity?.let {
                    ActivityCompat.requestDragAndDropPermissions(it, dragEvent)
                }
                val fileName = resolveDisplayName(context, uri)
                onParseFile(fileName) {
                    try {
                        context.contentResolver.openInputStream(uri)
                    } finally {
                        dropPermission?.release()
                    }
                }
                return true
            }
        }
    }

    val dropzoneCard: @Composable () -> Unit = {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Upload Original Buyer PO",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Drag & drop or browse Excel (.xlsx), Legacy Excel (.xls), and CSV (.csv)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (uploadState.workbook != null) {
                        IconButton(onClick = onClearStaging) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Reset uploaded file")
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isDragHovering) LslEmeraldAccent.copy(alpha = 0.18f)
                            else LslMintTint.copy(alpha = 0.65f)
                        )
                        .border(
                            width = if (isDragHovering) 2.5.dp else 1.5.dp,
                            color = if (isDragHovering) LslForestGreen else LslMintBorder,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .dragAndDropTarget(
                            shouldStartDragAndDrop = { true },
                            target = dragAndDropCallback
                        )
                        .clickable(enabled = !uploadState.isParsing, onClick = openBuyerFilePicker)
                        .testTag("browse_po_file_box")
                        .padding(vertical = 24.dp, horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (uploadState.isParsing) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(color = LslForestGreen)
                            Text(
                                text = "Reading buyer workbook & detecting columns…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = LslForestGreen
                            )
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CloudUpload,
                                contentDescription = "Select or drop buyer PO file",
                                tint = LslForestGreen,
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                text = if (isDragHovering) "Drop Buyer PO File Here"
                                else uploadState.workbook?.fileName ?: "Drop Buyer PO File Here or Browse (.xlsx, .xls, .csv)",
                                style = MaterialTheme.typography.titleMedium,
                                color = LslForestGreen,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = if (uploadState.workbook != null) {
                                    "${uploadState.workbook.format.name} • ${uploadState.workbook.sheets.size} worksheet(s) loaded • Click or drop to replace"
                                } else {
                                    "Original buyer file is preserved read-only. Preview & adjust column mapping before transfer."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Button(
                                onClick = openBuyerFilePicker,
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .testTag("browse_po_file_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = LslForestGreen)
                            ) {
                                Text(if (uploadState.workbook == null) "Browse Files" else "Choose Another File")
                            }
                        }
                    }
                }

                if (uploadState.parseError != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = LslErrorBg,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = uploadState.parseError,
                            style = MaterialTheme.typography.bodySmall,
                            color = LslErrorText,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }

    val workbook = uploadState.workbook
    val activeSheet = workbook?.sheets?.getOrNull(uploadState.selectedSheetIndex)

    if (isLaptopScreen && workbook != null && activeSheet != null) {
        val headers = activeSheet.rows.getOrElse(uploadState.mapping.headerRowIndex) { emptyList() }
        val previewRows = activeSheet.rows.drop(uploadState.mapping.headerRowIndex + 1).take(40)

        // Two-Pane Laptop Upload Workbench: Left = Upload + Mapping, Right = Live Preview + Transfer
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(0.46f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item { dropzoneCard() }
                item {
                    SheetAndHeaderCard(
                        workbook = workbook,
                        activeSheetRowCount = activeSheet.rows.size,
                        selectedSheetIndex = uploadState.selectedSheetIndex,
                        headerRowIndex = uploadState.mapping.headerRowIndex,
                        onSelectSheet = onSelectSheet,
                        onUpdateHeaderRow = onUpdateHeaderRow
                    )
                }
                item {
                    MetadataEditorCard(
                        meta = uploadState.metadata,
                        onUpdateMetadata = onUpdateMetadata
                    )
                }
                item {
                    FlexibleColumnMapperCard(
                        mapping = uploadState.mapping,
                        headers = headers,
                        expanded = showColumnMapper,
                        onToggleExpanded = { showColumnMapper = !showColumnMapper },
                        onUpdateMapping = onUpdateMapping,
                        onToggleSizeColumn = onToggleSizeColumn
                    )
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(0.54f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Outlined.TableChart, contentDescription = "Original Buyer Preview", tint = LslForestGreen)
                                    Column {
                                        Text("Original Buyer PO Preview (Read-Only)", style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            text = "Showing ${previewRows.size} of ${maxOf(0, activeSheet.rows.size - uploadState.mapping.headerRowIndex - 1)} data row(s)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Button(
                                    onClick = onTransferToLslFormat,
                                    modifier = Modifier
                                        .height(44.dp)
                                        .testTag("transfer_po_button"),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = LslForestGreen)
                                ) {
                                    Text("Transfer to LSL Format", fontWeight = FontWeight.SemiBold)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(Icons.Outlined.ArrowForward, contentDescription = "Transfer PO", modifier = Modifier.size(18.dp))
                                }
                            }

                            val cleanHeaders = headers.mapIndexed { i, h -> h.ifBlank { "Col ${i + 1}" } }
                            ExcelStyleDataTable(
                                headers = cleanHeaders,
                                rows = previewRows,
                                columnWidths = cleanHeaders.map { h -> maxOf(90, minOf(165, h.length * 9 + 26)).dp },
                                rightAlignedColumns = uploadState.mapping.sizeMatrixColumns.toSet() +
                                    setOfNotNull(uploadState.mapping.quantityColumn, uploadState.mapping.unitPriceColumn)
                            )
                        }
                    }
                }
            }
        }
        return
    }

    // Standard single-column flow (Mobile / Tablet or before file loaded)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { dropzoneCard() }

        if (workbook != null && activeSheet != null) {
            val headers = activeSheet.rows.getOrElse(uploadState.mapping.headerRowIndex) { emptyList() }
            val previewRows = activeSheet.rows.drop(uploadState.mapping.headerRowIndex + 1).take(25)

            item {
                SheetAndHeaderCard(
                    workbook = workbook,
                    activeSheetRowCount = activeSheet.rows.size,
                    selectedSheetIndex = uploadState.selectedSheetIndex,
                    headerRowIndex = uploadState.mapping.headerRowIndex,
                    onSelectSheet = onSelectSheet,
                    onUpdateHeaderRow = onUpdateHeaderRow
                )
            }
            item {
                MetadataEditorCard(
                    meta = uploadState.metadata,
                    onUpdateMetadata = onUpdateMetadata
                )
            }
            item {
                FlexibleColumnMapperCard(
                    mapping = uploadState.mapping,
                    headers = headers,
                    expanded = showColumnMapper,
                    onToggleExpanded = { showColumnMapper = !showColumnMapper },
                    onUpdateMapping = onUpdateMapping,
                    onToggleSizeColumn = onToggleSizeColumn
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Outlined.TableChart, contentDescription = "Original Buyer Preview", tint = LslForestGreen)
                        Column {
                            Text("Original Buyer PO Preview (Read-Only)", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Showing first ${previewRows.size} of ${maxOf(0, activeSheet.rows.size - uploadState.mapping.headerRowIndex - 1)} data row(s) before transfer.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    val cleanHeaders = headers.mapIndexed { i, h -> h.ifBlank { "Col ${i + 1}" } }
                    ExcelStyleDataTable(
                        headers = cleanHeaders,
                        rows = previewRows,
                        columnWidths = cleanHeaders.map { h -> maxOf(86, minOf(160, h.length * 9 + 24)).dp },
                        rightAlignedColumns = uploadState.mapping.sizeMatrixColumns.toSet() +
                            setOfNotNull(uploadState.mapping.quantityColumn, uploadState.mapping.unitPriceColumn)
                    )
                }
            }
            item {
                Button(
                    onClick = onTransferToLslFormat,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("transfer_po_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LslForestGreen)
                ) {
                    Text(
                        text = "Transfer & Validate in Standardized LSL Format",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(Icons.Outlined.ArrowForward, contentDescription = "Transfer PO")
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SheetAndHeaderCard(
    workbook: com.example.model.ParsedWorkbook,
    activeSheetRowCount: Int,
    selectedSheetIndex: Int,
    headerRowIndex: Int,
    onSelectSheet: (Int) -> Unit,
    onUpdateHeaderRow: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (workbook.sheets.size > 1) {
                Text("Select Worksheet", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    workbook.sheets.forEachIndexed { idx, sheet ->
                        FilterChip(
                            selected = idx == selectedSheetIndex,
                            onClick = { onSelectSheet(idx) },
                            label = { Text("${sheet.name} (${sheet.rows.size} rows)") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = LslMintTint,
                                selectedLabelColor = LslForestGreen
                            )
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Detected Header Row", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Row #${headerRowIndex + 1} of $activeSheetRowCount total rows",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onUpdateHeaderRow(headerRowIndex - 1) },
                        enabled = headerRowIndex > 0,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) { Text("-1") }
                    OutlinedButton(
                        onClick = { onUpdateHeaderRow(headerRowIndex + 1) },
                        enabled = headerRowIndex + 1 < activeSheetRowCount,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) { Text("+1") }
                }
            }
        }
    }
}

@Composable
private fun MetadataEditorCard(
    meta: ExtractedMetadata,
    onUpdateMetadata: ((ExtractedMetadata) -> ExtractedMetadata) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Detected Buyer & PO Header Details", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = meta.buyerName,
                    onValueChange = { v -> onUpdateMetadata { it.copy(buyerName = v) } },
                    label = { Text("Buyer Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = meta.poNumber,
                    onValueChange = { v -> onUpdateMetadata { it.copy(poNumber = v) } },
                    label = { Text("PO Number") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = meta.poDate,
                    onValueChange = { v -> onUpdateMetadata { it.copy(poDate = v) } },
                    label = { Text("PO Date") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = meta.deliveryDate,
                    onValueChange = { v -> onUpdateMetadata { it.copy(deliveryDate = v) } },
                    label = { Text("Delivery Date") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = meta.shipDate,
                    onValueChange = { v -> onUpdateMetadata { it.copy(shipDate = v) } },
                    label = { Text("Ship Date") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = meta.currency,
                    onValueChange = { v -> onUpdateMetadata { it.copy(currency = v.uppercase().take(6)) } },
                    label = { Text("Currency") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = meta.destination,
                    onValueChange = { v -> onUpdateMetadata { it.copy(destination = v) } },
                    label = { Text("Destination") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = meta.factoryInfo,
                    onValueChange = { v -> onUpdateMetadata { it.copy(factoryInfo = v) } },
                    label = { Text("Factory Info") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = meta.fabricDetails,
                onValueChange = { v -> onUpdateMetadata { it.copy(fabricDetails = v) } },
                label = { Text("Fabric & Other Details") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlexibleColumnMapperCard(
    mapping: ColumnMapping,
    headers: List<String>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onUpdateMapping: ((ColumnMapping) -> ColumnMapping) -> Unit,
    onToggleSizeColumn: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Tune, contentDescription = "Column Mapping", tint = LslForestGreen)
                    Column {
                        Text("Flexible Column Mapping", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${mapping.sizeMatrixColumns.size} dynamic size column(s) selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = "Toggle column mapping"
                )
            }

            if (expanded) {
                ColumnMappingDropdownRow("Style Number", mapping.styleNumberColumn, headers) { idx ->
                    onUpdateMapping { it.copy(styleNumberColumn = idx) }
                }
                ColumnMappingDropdownRow("Style Description", mapping.styleDescriptionColumn, headers) { idx ->
                    onUpdateMapping { it.copy(styleDescriptionColumn = idx) }
                }
                ColumnMappingDropdownRow("Color / Shade", mapping.colorColumn, headers) { idx ->
                    onUpdateMapping { it.copy(colorColumn = idx) }
                }
                ColumnMappingDropdownRow("Garment Type", mapping.garmentTypeColumn, headers) { idx ->
                    onUpdateMapping { it.copy(garmentTypeColumn = idx) }
                }
                ColumnMappingDropdownRow("Unit Price / FOB", mapping.unitPriceColumn, headers) { idx ->
                    onUpdateMapping { it.copy(unitPriceColumn = idx) }
                }
                ColumnMappingDropdownRow("Total / Declared Qty", mapping.quantityColumn, headers) { idx ->
                    onUpdateMapping { it.copy(quantityColumn = idx) }
                }
                ColumnMappingDropdownRow("Single Size Col (Vertical PO)", mapping.singleSizeColumn, headers) { idx ->
                    onUpdateMapping { it.copy(singleSizeColumn = idx) }
                }
                ColumnMappingDropdownRow("Delivery Date Column", mapping.deliveryDateColumn, headers) { idx ->
                    onUpdateMapping { it.copy(deliveryDateColumn = idx) }
                }
                ColumnMappingDropdownRow("Destination Column", mapping.destinationColumn, headers) { idx ->
                    onUpdateMapping { it.copy(destinationColumn = idx) }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Dynamic Size Matrix Columns (Click to toggle alpha, numeric, kids, or custom sizes):",
                    style = MaterialTheme.typography.labelLarge,
                    color = LslForestGreen
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    headers.forEachIndexed { colIdx, headerTitle ->
                        val label = headerTitle.ifBlank { "Col ${colIdx + 1}" }
                        val selected = colIdx in mapping.sizeMatrixColumns
                        FilterChip(
                            selected = selected,
                            onClick = { onToggleSizeColumn(colIdx) },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = LslForestGreen,
                                selectedLabelColor = LslPureWhite
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnMappingDropdownRow(
    fieldLabel: String,
    selectedCol: Int?,
    headers: List<String>,
    onSelect: (Int?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedText = if (selectedCol != null) {
        val h = headers.getOrElse(selectedCol) { "" }.ifBlank { "Col ${selectedCol + 1}" }
        "Col ${selectedCol + 1}: $h"
    } else {
        "Not Mapped"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = fieldLabel,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.45f)
        )
        Box(modifier = Modifier.weight(0.55f)) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true },
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedText,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Outlined.ExpandMore,
                        contentDescription = "Choose column for $fieldLabel",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("Not Mapped") },
                    onClick = {
                        onSelect(null)
                        expanded = false
                    }
                )
                headers.forEachIndexed { idx, h ->
                    val label = "Col ${idx + 1}: ${h.ifBlank { "(Blank)" }}"
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onSelect(idx)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

private fun resolveDisplayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) {
                val name = cursor.getString(idx)
                if (!name.isNullOrBlank()) return name
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "buyer_po.xlsx"
}
