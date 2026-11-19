package com.example.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.engine.ExcelWorkbookExporter
import com.example.engine.PoTransferEngine
import com.example.model.AuditAction
import com.example.ui.components.EmptyPoWorkspaceCard
import com.example.ui.components.PoStatusBadge
import com.example.ui.components.formatCurrency
import com.example.ui.components.formatInt
import com.example.ui.theme.LslForestGreen
import com.example.ui.theme.LslMintTint
import com.example.ui.theme.LslPureWhite
import com.example.ui.theme.MonoNumericStyle
import com.example.viewmodel.ActivePoState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Composable
fun ExcelExportScreen(
    activeState: ActivePoState,
    companyName: String,
    onGoToUpload: () -> Unit,
    onGoToSavedPos: () -> Unit,
    onGoToPdfPrint: () -> Unit,
    onExportCompleted: (AuditAction, String) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val po = activeState.po
    if (po == null) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            EmptyPoWorkspaceCard(
                title = "No PO Selected for Excel Export",
                subtitle = "Open a saved PO or upload and transfer a buyer PO to generate a formatted 6-sheet LSL Excel (.xlsx) workbook.",
                primaryActionLabel = "Open Saved POs",
                onPrimaryAction = onGoToSavedPos,
                secondaryActionLabel = "Upload New PO",
                onSecondaryAction = onGoToUpload
            )
        }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isExporting by remember { mutableStateOf(false) }

    val safePoNum = remember(po.poNumber) {
        po.poNumber.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "PO" }
    }
    val suggestedFileName = "LSL_PO_${safePoNum}_Breakdown.xlsx"

    val createExcelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isExporting = true
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            ExcelWorkbookExporter.exportToStream(po, companyName, out)
                        } ?: throw IllegalStateException("Could not open destination file stream.")
                    }
                    onExportCompleted(
                        AuditAction.EXPORTED_EXCEL,
                        "Exported formatted 6-sheet Excel workbook ($suggestedFileName)."
                    )
                    onShowMessage("Exported $suggestedFileName successfully.")
                } catch (e: Exception) {
                    onShowMessage("Excel export failed: ${e.localizedMessage ?: "Unknown error"}")
                } finally {
                    isExporting = false
                }
            }
        }
    }

    val styleCount = remember(po) { PoTransferEngine.computeStyleBreakdown(po).size }
    val colorCount = remember(po) { PoTransferEngine.computeColorBreakdown(po).size }
    val deliveryCount = remember(po) { PoTransferEngine.computeDeliveryBreakdown(po).size }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Excel Workbook Export (.xlsx)", style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = "PO ${po.poNumber}  •  ${po.buyerName}",
                                style = MonoNumericStyle.copy(fontWeight = FontWeight.Bold),
                                color = LslForestGreen
                            )
                        }
                        PoStatusBadge(status = po.status)
                    }

                    Text(
                        text = "${formatInt(po.totalQuantity)} pcs  •  ${po.styleCount} styles  •  ${po.sizeColumns.size} dynamic sizes  •  ${formatCurrency(po.currency, po.totalValue)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { createExcelLauncher.launch(suggestedFileName) },
                            enabled = !isExporting,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("export_excel_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = LslForestGreen)
                        ) {
                            if (isExporting) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = LslPureWhite, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.Download, contentDescription = "Save Excel", modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Export Excel")
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isExporting = true
                                    try {
                                        val file = withContext(Dispatchers.IO) {
                                            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                                            val f = File(dir, suggestedFileName)
                                            FileOutputStream(f).use { fos ->
                                                ExcelWorkbookExporter.exportToStream(po, companyName, fos)
                                            }
                                            f
                                        }
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file
                                        )
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            putExtra(Intent.EXTRA_SUBJECT, "LSL PO ${po.poNumber} Breakdown")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "Share LSL Excel Workbook"))
                                        onExportCompleted(
                                            AuditAction.EXPORTED_EXCEL,
                                            "Shared formatted 6-sheet Excel workbook ($suggestedFileName)."
                                        )
                                    } catch (e: Exception) {
                                        onShowMessage("Share failed: ${e.localizedMessage}")
                                    } finally {
                                        isExporting = false
                                    }
                                }
                            },
                            enabled = !isExporting,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("share_excel_button")
                        ) {
                            Icon(Icons.Outlined.Share, contentDescription = "Share Excel", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Share")
                        }
                    }

                    OutlinedButton(
                        onClick = onGoToPdfPrint,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Print, contentDescription = "Open PDF & Print", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Switch to PDF Export & A4 Print Preview")
                    }
                }
            }
        }

        // 6-Worksheet Structure Preview Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.TableChart, contentDescription = "Workbook Structure", tint = LslForestGreen)
                        Text("6-Worksheet LSL Workbook Structure", style = MaterialTheme.typography.titleMedium)
                    }
                    WorksheetSpecRow("Sheet 1", "PO Summary", "Buyer header, PO metadata & linked SUM KPIs")
                    WorksheetSpecRow("Sheet 2", "Style Breakdown", "$styleCount style row(s) with =E*F value & SUM formulas")
                    WorksheetSpecRow("Sheet 3", "Size Breakdown", "${po.lineItems.size} line(s) × ${po.sizeColumns.size} dynamic size columns + row/col SUMs")
                    WorksheetSpecRow("Sheet 4", "Color Breakdown", "$colorCount style-colorway rows with share % & totals")
                    WorksheetSpecRow("Sheet 5", "Delivery Breakdown", "$deliveryCount shipment group(s) by delivery, ship date & destination")
                    WorksheetSpecRow("Sheet 6", "Validation Report", "${po.validationWarnings.size} alert(s) or full PASS certificate")
                }
            }
        }

        // LSL Corporate Formatting Checklist
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = LslMintTint
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Included LSL Excel Formatting Standards", style = MaterialTheme.typography.titleSmall, color = LslForestGreen)
                    listOf(
                        "Corporate LSL Forest Green (#124F2D) header fills with bold white text",
                        "Auto-calculated column widths, thin grid borders, and number/currency alignment",
                        "Live Excel SUM() and unit-value multiplication formulas (100% editable offline)",
                        "Frozen header panes and AutoFilter dropdowns enabled on every table",
                        "Green-tinted Grand Total summary rows at the bottom of every breakdown"
                    ).forEach { spec ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = LslForestGreen, modifier = Modifier.size(16.dp))
                            Text(spec, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun WorksheetSpecRow(sheetNum: String, name: String, detail: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(shape = RoundedCornerShape(6.dp), color = LslForestGreen) {
                Text(
                    text = sheetNum,
                    style = MaterialTheme.typography.labelSmall,
                    color = LslPureWhite,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
