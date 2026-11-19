package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.TableView
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.engine.PoTransferEngine
import com.example.model.AuditLogEntry
import com.example.model.OriginalSourceSnapshot
import com.example.ui.components.EmptyPoWorkspaceCard
import com.example.ui.components.ExcelStyleDataTable
import com.example.ui.components.PoStatusBadge
import com.example.ui.components.ValidationSummaryCard
import com.example.ui.components.formatCurrency
import com.example.ui.components.formatInt
import com.example.ui.components.formatTimestamp
import com.example.ui.theme.LslForestGreen
import com.example.ui.theme.LslMintTint
import com.example.ui.theme.MonoNumericStyle
import com.example.viewmodel.ActivePoState
import java.util.Locale

private val breakdownTabs = listOf(
    "A. PO Summary",
    "B. Style Breakdown",
    "C. Size Breakdown",
    "D. Color Breakdown",
    "E. Delivery Breakdown",
    "Original Buyer Data",
    "Audit History"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PoBreakdownScreen(
    activeState: ActivePoState,
    savedOriginalSource: OriginalSourceSnapshot?,
    auditLogs: List<AuditLogEntry>,
    onGoToUpload: () -> Unit,
    onGoToSavedPos: () -> Unit,
    onEditInTransfer: () -> Unit,
    onSavePo: () -> Unit,
    onGoToExcelExport: () -> Unit,
    onGoToPdfPrint: () -> Unit
) {
    val po = activeState.po
    if (po == null) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            EmptyPoWorkspaceCard(
                title = "Select or Upload a PO to View Breakdowns",
                subtitle = "Inspect standardized PO Summary, Style Breakdown, Dynamic Size Matrix, Color Breakdown, Delivery Schedule, and Audit History.",
                primaryActionLabel = "Open Saved POs",
                onPrimaryAction = onGoToSavedPos,
                secondaryActionLabel = "Upload New PO",
                onSecondaryAction = onGoToUpload
            )
        }
        return
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    val effectiveSource = activeState.originalSource ?: savedOriginalSource

    Column(modifier = Modifier.fillMaxSize()) {
        // PO Banner & Quick Actions
        Surface(
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${po.poNumber}  •  ${po.buyerName}",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = LslForestGreen
                        )
                        Text(
                            text = "${formatInt(po.totalQuantity)} pcs  •  ${po.styleCount} styles  •  ${formatCurrency(po.currency, po.totalValue)}",
                            style = MonoNumericStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    PoStatusBadge(status = po.status)
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onEditInTransfer, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Edit PO", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Edit PO")
                    }
                    if (activeState.hasUnsavedChanges || po.id.isBlank()) {
                        Button(
                            onClick = onSavePo,
                            colors = ButtonDefaults.buttonColors(containerColor = LslForestGreen),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Outlined.Save, contentDescription = "Save PO", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save PO")
                        }
                    }
                    OutlinedButton(
                        onClick = onGoToExcelExport,
                        modifier = Modifier.testTag("breakdown_excel_button"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Outlined.TableView, contentDescription = "Export Excel", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Export Excel")
                    }
                    OutlinedButton(
                        onClick = onGoToPdfPrint,
                        modifier = Modifier.testTag("breakdown_pdf_button"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Outlined.Print, contentDescription = "PDF and Print", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("PDF & Print")
                    }
                }
            }
        }

        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = LslForestGreen,
            edgePadding = 12.dp
        ) {
            breakdownTabs.forEachIndexed { idx, label ->
                Tab(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx },
                    text = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selectedTab == idx) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (selectedTab) {
                0 -> { // A. PO Summary
                    item {
                        ValidationSummaryCard(warnings = po.validationWarnings)
                    }
                    item {
                        val rows = listOf(
                            listOf("Buyer", po.buyerName),
                            listOf("PO Number", po.poNumber),
                            listOf("PO Date", po.poDate.ifBlank { "-" }),
                            listOf("Total Styles", po.styleCount.toString()),
                            listOf("Total Colors", po.colorCount.toString()),
                            listOf("Total Quantity", "${formatInt(po.totalQuantity)} pcs"),
                            listOf("Total Value", formatCurrency(po.currency, po.totalValue)),
                            listOf("Delivery Date", po.deliveryDate.ifBlank { "-" }),
                            listOf("Ship Date", po.shipDate.ifBlank { "-" }),
                            listOf("Destination", po.destination.ifBlank { "-" }),
                            listOf("Factory Info", po.factoryInfo.ifBlank { "-" }),
                            listOf("Fabric & Details", po.fabricDetails.ifBlank { "-" }),
                            listOf("Dynamic Size Range", po.sizeColumns.joinToString(", ").ifBlank { "-" }),
                            listOf("Original Buyer File", "${po.originalFileName} (${po.originalFileFormat.name})"),
                            listOf("Merchandiser Notes", po.notes.ifBlank { "-" })
                        )
                        ExcelStyleDataTable(
                            headers = listOf("PO Summary Field", "Standardized LSL Value"),
                            rows = rows,
                            columnWidths = listOf(170.dp, 260.dp)
                        )
                    }
                }

                1 -> { // B. Style Breakdown
                    val styles = PoTransferEngine.computeStyleBreakdown(po)
                    item {
                        ExcelStyleDataTable(
                            headers = listOf("Style Number", "Description", "Color(s)", "Garment Type", "PO Quantity", "Unit Price", "Total Value"),
                            rows = styles.map {
                                listOf(
                                    it.styleNumber,
                                    it.description,
                                    it.colors,
                                    it.garmentType,
                                    formatInt(it.poQuantity),
                                    formatCurrency(po.currency, it.avgUnitPrice),
                                    formatCurrency(po.currency, it.totalValue)
                                )
                            },
                            columnWidths = listOf(116.dp, 170.dp, 130.dp, 110.dp, 96.dp, 108.dp, 120.dp),
                            rightAlignedColumns = setOf(4, 5, 6),
                            totalRow = listOf(
                                "GRAND TOTAL",
                                "${styles.size} Styles",
                                "",
                                "",
                                formatInt(po.totalQuantity),
                                "",
                                formatCurrency(po.currency, po.totalValue)
                            )
                        )
                    }
                }

                2 -> { // C. Dynamic Size Breakdown
                    val sizes = po.sizeColumns
                    val sizeTotals = PoTransferEngine.computeSizeColumnTotals(po)
                    val headers = buildList {
                        add("Style")
                        add("Color")
                        addAll(sizes)
                        add("Total")
                    }
                    val widths = buildList {
                        add(116.dp)
                        add(120.dp)
                        sizes.forEach { sz -> add(maxOf(64, sz.length * 10 + 26).dp) }
                        add(92.dp)
                    }
                    val rightCols = (2..headers.lastIndex).toSet()
                    item {
                        ExcelStyleDataTable(
                            headers = headers,
                            rows = po.lineItems.map { line ->
                                buildList {
                                    add(line.styleNumber.ifBlank { "(Missing)" })
                                    add(line.color.ifBlank { "-" })
                                    sizes.forEach { sz -> add(formatInt(line.sizeQuantities[sz] ?: 0L)) }
                                    add(formatInt(line.effectiveQty))
                                }
                            },
                            columnWidths = widths,
                            rightAlignedColumns = rightCols,
                            totalRow = buildList {
                                add("GRAND TOTAL")
                                add("${po.lineItems.size} Lines")
                                sizes.forEach { sz -> add(formatInt(sizeTotals[sz] ?: 0L)) }
                                add(formatInt(po.totalQuantity))
                            }
                        )
                    }
                }

                3 -> { // D. Color Breakdown
                    val colors = PoTransferEngine.computeColorBreakdown(po)
                    item {
                        ExcelStyleDataTable(
                            headers = listOf("Style Number", "Color", "Garment Type", "Quantity", "Share %", "Total Value"),
                            rows = colors.map {
                                listOf(
                                    it.styleNumber,
                                    it.color,
                                    it.garmentType,
                                    formatInt(it.quantity),
                                    String.format(Locale.US, "%.1f%%", it.sharePercent),
                                    formatCurrency(po.currency, it.totalValue)
                                )
                            },
                            columnWidths = listOf(120.dp, 130.dp, 110.dp, 96.dp, 82.dp, 120.dp),
                            rightAlignedColumns = setOf(3, 4, 5),
                            totalRow = listOf(
                                "GRAND TOTAL",
                                "${colors.size} Combos",
                                "",
                                formatInt(po.totalQuantity),
                                "100.0%",
                                formatCurrency(po.currency, po.totalValue)
                            )
                        )
                    }
                }

                4 -> { // E. Delivery Breakdown
                    val deliveries = PoTransferEngine.computeDeliveryBreakdown(po)
                    item {
                        ExcelStyleDataTable(
                            headers = listOf("Delivery Date", "Ship Date", "Destination", "Factory Info", "Styles", "PO Quantity", "Total Value"),
                            rows = deliveries.map {
                                listOf(
                                    it.deliveryDate,
                                    it.shipDate,
                                    it.destination,
                                    it.factoryInfo,
                                    it.styleCount.toString(),
                                    formatInt(it.quantity),
                                    formatCurrency(po.currency, it.totalValue)
                                )
                            },
                            columnWidths = listOf(112.dp, 112.dp, 140.dp, 140.dp, 72.dp, 100.dp, 120.dp),
                            rightAlignedColumns = setOf(4, 5, 6),
                            totalRow = listOf(
                                "GRAND TOTAL",
                                "",
                                "${deliveries.size} Shipments",
                                "",
                                po.styleCount.toString(),
                                formatInt(po.totalQuantity),
                                formatCurrency(po.currency, po.totalValue)
                            )
                        )
                    }
                }

                5 -> { // Immutable Original Buyer File Snapshot
                    item {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = LslMintTint),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Immutable Original Buyer Source", style = MaterialTheme.typography.titleSmall, color = LslForestGreen)
                                Text(
                                    text = "File: ${effectiveSource?.fileName ?: po.originalFileName}  •  Format: ${(effectiveSource?.fileFormat ?: po.originalFileFormat).name}  •  Sheet: ${effectiveSource?.sheetName ?: po.originalSheetName.ifBlank { "-" }}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "Preserved read-only and strictly separated from converted LSL data.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (effectiveSource != null && effectiveSource.rows.isNotEmpty()) {
                        item {
                            val maxCols = maxOf(effectiveSource.headers.size, effectiveSource.rows.maxOfOrNull { it.cells.size } ?: 0)
                            val colHeaders = (0 until maxCols).map { c ->
                                effectiveSource.headers.getOrElse(c) { "" }.ifBlank { "Col ${c + 1}" }
                            }
                            ExcelStyleDataTable(
                                headers = listOf("Row") + colHeaders,
                                rows = effectiveSource.rows.take(100).map { r ->
                                    listOf("#${r.rowIndex}") + (0 until maxCols).map { c -> r.cells.getOrElse(c) { "" } }
                                },
                                columnWidths = listOf(56.dp) + colHeaders.map { h -> maxOf(84, minOf(150, h.length * 9 + 24)).dp }
                            )
                        }
                    } else {
                        item {
                            Text(
                                text = "Original buyer rows snapshot will appear here once saved or loaded.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                6 -> { // Audit History
                    if (auditLogs.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.History, contentDescription = "Audit history", tint = LslForestGreen)
                                    Text(
                                        text = if (po.id.isBlank()) "Save this PO to start recording immutable audit timestamps."
                                        else "No audit entries recorded yet.",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    } else {
                        items(auditLogs, key = { it.id }) { log ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = log.action.label,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = LslForestGreen
                                        )
                                        Text(
                                            text = formatTimestamp(log.createdAt),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(text = log.summary, style = MaterialTheme.typography.bodySmall)
                                    if (log.actorName.isNotBlank()) {
                                        Text(
                                            text = "By: ${log.actorName}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}
