package com.example.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TableView
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.engine.ExcelWorkbookExporter
import com.example.engine.PdfAndPrintEngine
import com.example.model.AuditAction
import com.example.ui.components.EmptyPoWorkspaceCard
import com.example.ui.components.PoStatusBadge
import com.example.ui.theme.LslForestGreen
import com.example.ui.theme.MonoNumericStyle
import com.example.viewmodel.ActivePoState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PdfPrintScreen(
    activeState: ActivePoState,
    companyName: String,
    isLaptopScreen: Boolean = false,
    onGoToUpload: () -> Unit,
    onGoToSavedPos: () -> Unit,
    onSavePo: () -> Unit,
    onAuditEvent: (AuditAction, String) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val po = activeState.po
    if (po == null) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            EmptyPoWorkspaceCard(
                title = "No PO Selected for PDF & Print",
                subtitle = "Open a saved PO or upload a buyer PO to inspect the A4 Landscape print preview, export PDF, or print directly.",
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
    var previewPages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var previewPdfFile by remember { mutableStateOf<File?>(null) }
    var isRenderingPreview by remember { mutableStateOf(true) }

    val safePoNum = remember(po.poNumber) {
        po.poNumber.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "PO" }
    }

    LaunchedEffect(po, companyName) {
        isRenderingPreview = true
        try {
            val (file, bitmaps) = withContext(Dispatchers.IO) {
                val f = PdfAndPrintEngine.generatePreviewPdfFile(context, po, companyName)
                val pages = PdfAndPrintEngine.renderPdfPreviewBitmaps(f, scale = 1.6f)
                f to pages
            }
            previewPdfFile = file
            previewPages = bitmaps
        } catch (e: Exception) {
            onShowMessage("Failed to render PDF preview: ${e.localizedMessage}")
        } finally {
            isRenderingPreview = false
        }
    }

    val createPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            PdfAndPrintEngine.writePdfToStream(po, companyName, out)
                        } ?: throw IllegalStateException("Cannot open PDF destination stream.")
                    }
                    onAuditEvent(AuditAction.EXPORTED_PDF, "Exported A4 Landscape PDF report for PO ${po.poNumber}.")
                    onShowMessage("Exported LSL_PO_${safePoNum}_Report.pdf successfully.")
                } catch (e: Exception) {
                    onShowMessage("PDF export failed: ${e.localizedMessage}")
                }
            }
        }
    }

    val createExcelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            ExcelWorkbookExporter.exportToStream(po, companyName, out)
                        } ?: throw IllegalStateException("Cannot open Excel destination stream.")
                    }
                    onAuditEvent(AuditAction.EXPORTED_EXCEL, "Exported 6-sheet Excel workbook from PDF/Print view.")
                    onShowMessage("Exported LSL_PO_${safePoNum}_Breakdown.xlsx successfully.")
                } catch (e: Exception) {
                    onShowMessage("Excel export failed: ${e.localizedMessage}")
                }
            }
        }
    }

    val controlsCard: @Composable () -> Unit = {
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
                        Text("A4 Landscape PDF & Print", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "PO ${po.poNumber}  •  ${po.buyerName}  •  ${previewPages.size} page(s)",
                            style = MonoNumericStyle.copy(fontWeight = FontWeight.Bold),
                            color = LslForestGreen
                        )
                    }
                    PoStatusBadge(status = po.status)
                }

                Text(
                    text = "Formatted for A4 Landscape with LAILA STYLES LIMITED masthead, repeating table headers on every page, margins, page numbers, and zero cut-off columns.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { createExcelLauncher.launch("LSL_PO_${safePoNum}_Breakdown.xlsx") },
                        modifier = Modifier.testTag("pdf_screen_export_excel_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = LslForestGreen)
                    ) {
                        Icon(Icons.Outlined.TableView, contentDescription = "Export Excel", modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export Excel")
                    }

                    Button(
                        onClick = { createPdfLauncher.launch("LSL_PO_${safePoNum}_Report.pdf") },
                        modifier = Modifier.testTag("pdf_screen_export_pdf_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = LslForestGreen)
                    ) {
                        Icon(Icons.Outlined.PictureAsPdf, contentDescription = "Export PDF", modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export PDF")
                    }

                    Button(
                        onClick = {
                            PdfAndPrintEngine.printPurchaseOrder(context, po, companyName)
                            onAuditEvent(AuditAction.PRINTED, "Sent PO ${po.poNumber} to A4 Landscape Print Spooler.")
                        },
                        modifier = Modifier.testTag("pdf_screen_print_po_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = LslForestGreen)
                    ) {
                        Icon(Icons.Outlined.Print, contentDescription = "Print PO", modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Print PO")
                    }

                    OutlinedButton(
                        onClick = onSavePo,
                        enabled = !activeState.isSaving,
                        modifier = Modifier.testTag("pdf_screen_save_po_button")
                    ) {
                        Icon(Icons.Outlined.Save, contentDescription = "Save PO", modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save PO")
                    }

                    OutlinedButton(
                        onClick = {
                            val file = previewPdfFile ?: return@OutlinedButton
                            try {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/pdf"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_SUBJECT, "LSL PO ${po.poNumber} PDF Report")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share LSL PO PDF"))
                            } catch (e: Exception) {
                                onShowMessage("Share PDF failed: ${e.localizedMessage}")
                            }
                        },
                        enabled = previewPdfFile != null
                    ) {
                        Icon(Icons.Outlined.Share, contentDescription = "Share PDF", modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share PDF")
                    }
                }
            }
        }
    }

    if (isLaptopScreen) {
        // Two-Pane Laptop Print Studio
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(0.36f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                controlsCard()
            }
            LazyColumn(
                modifier = Modifier
                    .weight(0.64f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Text("Print Preview (A4 Landscape)", style = MaterialTheme.typography.titleMedium) }
                if (isRenderingPreview) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = LslForestGreen)
                        }
                    }
                } else {
                    itemsIndexed(previewPages) { index, bitmap ->
                        PreviewPageCard(index = index, total = previewPages.size, bitmap = bitmap)
                    }
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { controlsCard() }
            item { Text("Print Preview (A4 Landscape)", style = MaterialTheme.typography.titleMedium) }
            if (isRenderingPreview) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(color = LslForestGreen)
                            Text("Rendering A4 Landscape print preview…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                itemsIndexed(previewPages) { index, bitmap ->
                    PreviewPageCard(index = index, total = previewPages.size, bitmap = bitmap)
                }
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun PreviewPageCard(index: Int, total: Int, bitmap: Bitmap) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Page ${index + 1} of $total  (A4 Landscape • 297 × 210 mm)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "PDF Preview Page ${index + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(842f / 595f)
            )
        }
    }
}
