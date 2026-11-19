package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TableView
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.AppDestination
import com.example.model.PoStatus
import com.example.model.PurchaseOrderRecord
import com.example.model.UiState
import com.example.ui.components.EmptyPoWorkspaceCard
import com.example.ui.components.PoStatusBadge
import com.example.ui.components.formatCurrency
import com.example.ui.components.formatInt
import com.example.ui.components.formatTimestamp
import com.example.ui.theme.LslErrorText
import com.example.ui.theme.LslForestGreen
import com.example.ui.theme.LslMintTint
import com.example.ui.theme.LslWarningBg
import com.example.ui.theme.LslWarningText
import com.example.ui.theme.MonoNumericStyle

private enum class DateRangeFilter(val label: String, val days: Long?) {
    ALL("All Dates", null),
    LAST_7("Last 7 Days", 7L),
    LAST_30("Last 30 Days", 30L)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SavedPosScreen(
    savedPosState: UiState<List<PurchaseOrderRecord>>,
    onUploadNewPo: () -> Unit,
    onOpenPo: (PurchaseOrderRecord, AppDestination) -> Unit,
    onDeletePo: (PurchaseOrderRecord) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf<PoStatus?>(null) }
    var dateFilter by remember { mutableStateOf(DateRangeFilter.ALL) }
    var poPendingDelete by remember { mutableStateOf<PurchaseOrderRecord?>(null) }

    poPendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { poPendingDelete = null },
            title = { Text("Delete Saved PO?") },
            text = {
                Text("Permanently remove PO '${target.poNumber}' (${target.buyerName}) and its archived history from LSL Cloud Database?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeletePo(target)
                        poPendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LslErrorText)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { poPendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    when (savedPosState) {
        is UiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = LslForestGreen)
            }
        }
        is UiState.Error -> {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(savedPosState.message, color = LslErrorText)
            }
        }
        is UiState.Success -> {
            val allPos = savedPosState.data
            val poNumberCounts = remember(allPos) {
                allPos.groupingBy { it.poNumber.trim().lowercase() }.eachCount()
            }
            val nowMs = System.currentTimeMillis()
            val filtered = remember(allPos, searchQuery, statusFilter, dateFilter) {
                val q = searchQuery.trim().lowercase()
                allPos.filter { po ->
                    val matchesQuery = q.isBlank() ||
                        po.poNumber.lowercase().contains(q) ||
                        po.buyerName.lowercase().contains(q) ||
                        po.originalFileName.lowercase().contains(q) ||
                        po.lineItems.any { it.styleNumber.lowercase().contains(q) || it.color.lowercase().contains(q) }
                    val matchesStatus = statusFilter == null || po.status == statusFilter
                    val matchesDate = dateFilter.days == null || run {
                        val tsMs = (po.createdAt ?: po.updatedAt)?.toDate()?.time ?: nowMs
                        (nowMs - tsMs) <= dateFilter.days!! * 24L * 60L * 60L * 1000L
                    }
                    matchesQuery && matchesStatus && matchesDate
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Search & Filters Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search by PO Number, Buyer, or Style Number…") },
                                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = "Search POs") },
                                trailingIcon = {
                                    if (searchQuery.isNotBlank()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Outlined.Clear, contentDescription = "Clear search")
                                        }
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("saved_pos_search_input")
                            )

                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = statusFilter == null,
                                    onClick = { statusFilter = null },
                                    label = { Text("All (${allPos.size})") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = LslMintTint,
                                        selectedLabelColor = LslForestGreen
                                    )
                                )
                                PoStatus.entries.forEach { st ->
                                    FilterChip(
                                        selected = statusFilter == st,
                                        onClick = { statusFilter = if (statusFilter == st) null else st },
                                        label = { Text(st.label) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = LslMintTint,
                                            selectedLabelColor = LslForestGreen
                                        )
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                DateRangeFilter.entries.forEach { df ->
                                    FilterChip(
                                        selected = dateFilter == df,
                                        onClick = { dateFilter = df },
                                        label = { Text(df.label) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = LslMintTint,
                                            selectedLabelColor = LslForestGreen
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                if (allPos.isEmpty()) {
                    item {
                        EmptyPoWorkspaceCard(
                            title = "Permanent PO Database Is Empty",
                            subtitle = "Uploaded and saved Purchase Orders will be stored permanently here with full search, editing, and re-export support.",
                            primaryActionLabel = "Upload Buyer PO",
                            onPrimaryAction = onUploadNewPo
                        )
                    }
                } else if (filtered.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Text(
                                text = "No saved POs match your current search or filters.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(20.dp)
                            )
                        }
                    }
                } else {
                    items(filtered, key = { it.id }) { po ->
                        val isDuplicatePo = (poNumberCounts[po.poNumber.trim().lowercase()] ?: 0) > 1
                        SavedPoRecordCard(
                            po = po,
                            isDuplicatePo = isDuplicatePo,
                            onOpenBreakdown = { onOpenPo(po, AppDestination.PO_BREAKDOWN) },
                            onEdit = { onOpenPo(po, AppDestination.PO_TRANSFER) },
                            onExportExcel = { onOpenPo(po, AppDestination.EXCEL_EXPORT) },
                            onExportPdfPrint = { onOpenPo(po, AppDestination.PDF_PRINT) },
                            onDelete = { poPendingDelete = po }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedPoRecordCard(
    po: PurchaseOrderRecord,
    isDuplicatePo: Boolean,
    onOpenBreakdown: () -> Unit,
    onEdit: () -> Unit,
    onExportExcel: () -> Unit,
    onExportPdfPrint: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenBreakdown)
            .testTag("saved_po_card_${po.poNumber}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = po.poNumber,
                        style = MonoNumericStyle.copy(fontWeight = FontWeight.Bold),
                        color = LslForestGreen
                    )
                    Text(
                        text = po.buyerName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                PoStatusBadge(status = po.status)
            }

            if (isDuplicatePo) {
                Surface(shape = RoundedCornerShape(8.dp), color = LslWarningBg) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Outlined.WarningAmber, contentDescription = "Duplicate PO", tint = LslWarningText, modifier = Modifier.size(14.dp))
                        Text("Duplicate PO Number exists in database", style = MaterialTheme.typography.labelSmall, color = LslWarningText)
                    }
                }
            }

            Text(
                text = "Qty: ${formatInt(po.totalQuantity)} pcs  •  Styles: ${po.styleCount}  •  Value: ${formatCurrency(po.currency, po.totalValue)}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoNumericStyle.fontFamily),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "File: ${po.originalFileName} (${po.originalFileFormat.name})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Uploaded: ${formatTimestamp(po.createdAt)}  •  Updated: ${formatTimestamp(po.updatedAt ?: po.createdAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = onOpenBreakdown,
                    colors = ButtonDefaults.buttonColors(containerColor = LslForestGreen),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) { Text("Open") }
                OutlinedButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Edit PO", modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Edit")
                }
                OutlinedButton(onClick = onExportExcel, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                    Icon(Icons.Outlined.TableView, contentDescription = "Re-export Excel", modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Excel")
                }
                OutlinedButton(onClick = onExportPdfPrint, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                    Icon(Icons.Outlined.Print, contentDescription = "PDF and Print", modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("PDF/Print")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete PO", tint = LslErrorText)
                }
            }
        }
    }
}
