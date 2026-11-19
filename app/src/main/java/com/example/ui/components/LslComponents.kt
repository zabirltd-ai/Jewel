package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.model.PoStatus
import com.example.model.PoValidationWarning
import com.example.model.ValidationSeverity
import com.example.ui.theme.LslBorderGrey
import com.example.ui.theme.LslErrorBg
import com.example.ui.theme.LslErrorBorder
import com.example.ui.theme.LslErrorText
import com.example.ui.theme.LslForestGreen
import com.example.ui.theme.LslMintBorder
import com.example.ui.theme.LslMintTint
import com.example.ui.theme.LslPureWhite
import com.example.ui.theme.LslSurfaceSubtle
import com.example.ui.theme.LslWarningBg
import com.example.ui.theme.LslWarningBorder
import com.example.ui.theme.LslWarningText
import com.example.ui.theme.MonoNumericStyle
import com.google.firebase.Timestamp
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

fun formatInt(value: Long): String = NumberFormat.getIntegerInstance(Locale.US).format(value)

fun formatCurrency(currency: String, value: Double): String {
    val nf = NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }
    return "${currency.ifBlank { "USD" }} ${nf.format(value)}"
}

fun formatTimestamp(timestamp: Timestamp?): String {
    val ts = timestamp ?: return "Just now"
    return SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US).format(ts.toDate())
}

@Composable
fun LslKpiCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accentColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = MonoNumericStyle.fontFamily,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun PoStatusBadge(
    status: PoStatus,
    modifier: Modifier = Modifier
) {
    val (bg, fg, border) = when (status) {
        PoStatus.VERIFIED -> Triple(LslMintTint, LslForestGreen, LslMintBorder)
        PoStatus.EXPORTED -> Triple(LslForestGreen.copy(alpha = 0.14f), LslForestGreen, LslMintBorder)
        PoStatus.PENDING_VERIFICATION -> Triple(LslWarningBg, LslWarningText, LslWarningBorder)
        PoStatus.DRAFT -> Triple(LslSurfaceSubtle, MaterialTheme.colorScheme.onSurfaceVariant, LslBorderGrey)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = bg,
        border = BorderStroke(1.dp, border)
    ) {
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun ValidationSummaryCard(
    warnings: List<PoValidationWarning>,
    modifier: Modifier = Modifier,
    maxVisible: Int = 8
) {
    if (warnings.isEmpty()) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = LslMintTint,
            border = BorderStroke(1.dp, LslMintBorder)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = "Validation passed",
                    tint = LslForestGreen
                )
                Column {
                    Text(
                        text = "All PO Validation Checks Passed",
                        style = MaterialTheme.typography.titleSmall,
                        color = LslForestGreen
                    )
                    Text(
                        text = "Size totals, color totals, style quantities, and overall PO counts reconcile cleanly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        return
    }

    val errorCount = warnings.count { it.severity == ValidationSeverity.ERROR }
    val warnCount = warnings.size - errorCount
    val isCritical = errorCount > 0

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (isCritical) LslErrorBg else LslWarningBg,
        border = BorderStroke(1.dp, if (isCritical) LslErrorBorder else LslWarningBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isCritical) Icons.Outlined.ErrorOutline else Icons.Outlined.WarningAmber,
                    contentDescription = "Validation alerts",
                    tint = if (isCritical) LslErrorText else LslWarningText
                )
                Text(
                    text = "Validation Alerts: $errorCount Error(s), $warnCount Warning(s)",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isCritical) LslErrorText else LslWarningText
                )
            }
            Text(
                text = "Original buyer figures are preserved intact. Review and resolve any mismatches manually.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            warnings.take(maxVisible).forEach { w ->
                val tint = if (w.severity == ValidationSeverity.ERROR) LslErrorText else LslWarningText
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = tint
                    )
                    Text(
                        text = "[${w.field}] ${w.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            if (warnings.size > maxVisible) {
                Text(
                    text = "+ ${warnings.size - maxVisible} more alert(s) included in Excel Validation Report.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Horizontally scrollable Excel-style corporate grid with LSL Forest Green header,
 * crisp white header labels, alternating light grey rows, and optional Grand Total footer.
 */
@Composable
fun ExcelStyleDataTable(
    headers: List<String>,
    rows: List<List<String>>,
    columnWidths: List<Dp>,
    modifier: Modifier = Modifier,
    rightAlignedColumns: Set<Int> = emptySet(),
    totalRow: List<String>? = null
) {
    val scrollState = rememberScrollState()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.horizontalScroll(scrollState)) {
            // Header Row (LSL Corporate Forest Green)
            Row(
                modifier = Modifier
                    .background(LslForestGreen)
                    .padding(vertical = 10.dp, horizontal = 4.dp)
            ) {
                headers.forEachIndexed { colIdx, label ->
                    val width = columnWidths.getOrElse(colIdx) { 100.dp }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = LslPureWhite,
                        textAlign = if (colIdx in rightAlignedColumns) TextAlign.End else TextAlign.Start,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .width(width)
                            .padding(horizontal = 8.dp)
                    )
                }
            }

            // Body Rows
            if (rows.isEmpty()) {
                Box(
                    modifier = Modifier
                        .padding(20.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = "No rows available in this breakdown.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                rows.forEachIndexed { rowIdx, cells ->
                    val rowBg = if (rowIdx % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    else MaterialTheme.colorScheme.surface
                    Row(
                        modifier = Modifier
                            .background(rowBg)
                            .border(width = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        headers.indices.forEach { colIdx ->
                            val width = columnWidths.getOrElse(colIdx) { 100.dp }
                            val textVal = cells.getOrElse(colIdx) { "" }
                            val isNumeric = colIdx in rightAlignedColumns
                            Text(
                                text = textVal,
                                style = if (isNumeric) MonoNumericStyle else MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = if (isNumeric) TextAlign.End else TextAlign.Start,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .width(width)
                                    .padding(horizontal = 8.dp)
                            )
                        }
                    }
                }
            }

            // Optional Grand Total Footer Row
            if (totalRow != null) {
                Row(
                    modifier = Modifier
                        .background(LslMintTint)
                        .border(width = 1.dp, color = LslMintBorder)
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    headers.indices.forEach { colIdx ->
                        val width = columnWidths.getOrElse(colIdx) { 100.dp }
                        val textVal = totalRow.getOrElse(colIdx) { "" }
                        val isNumeric = colIdx in rightAlignedColumns
                        Text(
                            text = textVal,
                            style = if (isNumeric) {
                                MonoNumericStyle.copy(fontWeight = FontWeight.Bold)
                            } else {
                                MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                            },
                            color = LslForestGreen,
                            textAlign = if (isNumeric) TextAlign.End else TextAlign.Start,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .width(width)
                                .padding(horizontal = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyPoWorkspaceCard(
    title: String,
    subtitle: String,
    primaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(LslMintTint),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.UploadFile,
                    contentDescription = title,
                    tint = LslForestGreen,
                    modifier = Modifier.size(30.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onPrimaryAction,
                    modifier = Modifier.testTag("empty_state_primary_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = LslForestGreen)
                ) {
                    Text(primaryActionLabel)
                }
                if (secondaryActionLabel != null && onSecondaryAction != null) {
                    OutlinedButton(onClick = onSecondaryAction) {
                        Text(secondaryActionLabel)
                    }
                }
            }
        }
    }
}

@Composable
fun DuplicatePoWarningDialog(
    poNumber: String,
    onDismiss: () -> Unit,
    onSaveAnyway: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = "Duplicate PO Warning",
                tint = LslWarningText
            )
        },
        title = { Text("Duplicate PO Number Detected") },
        text = {
            Text(
                "PO '$poNumber' already exists in Saved POs. To prevent accidental duplicate records, " +
                    "please verify whether you want to edit the PO number first or save as a separate revision."
            )
        },
        confirmButton = {
            Button(
                onClick = onSaveAnyway,
                modifier = Modifier.testTag("duplicate_save_anyway_button"),
                colors = ButtonDefaults.buttonColors(containerColor = LslWarningText)
            ) {
                Text("Save Separate Record")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel & Review")
            }
        }
    )
}
