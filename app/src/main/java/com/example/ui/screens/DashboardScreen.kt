package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PendingActions
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.WarningAmber
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.AppDestination
import com.example.model.PoStatus
import com.example.model.PurchaseOrderRecord
import com.example.model.UiState
import com.example.ui.components.EmptyPoWorkspaceCard
import com.example.ui.components.LslKpiCard
import com.example.ui.components.PoStatusBadge
import com.example.ui.components.formatCurrency
import com.example.ui.components.formatInt
import com.example.ui.components.formatTimestamp
import com.example.ui.theme.LslEmeraldAccent
import com.example.ui.theme.LslErrorBg
import com.example.ui.theme.LslErrorText
import com.example.ui.theme.LslForestGreen
import com.example.ui.theme.LslForestGreenDark
import com.example.ui.theme.LslPureWhite
import com.example.ui.theme.LslWarningBg
import com.example.ui.theme.LslWarningBorder
import com.example.ui.theme.LslWarningText
import com.example.ui.theme.MonoNumericStyle

@Composable
fun DashboardScreen(
    companyName: String,
    savedPosState: UiState<List<PurchaseOrderRecord>>,
    isWideScreen: Boolean,
    onUploadNewPo: () -> Unit,
    onOpenSavedPos: () -> Unit,
    onOpenPo: (PurchaseOrderRecord, AppDestination) -> Unit
) {
    when (savedPosState) {
        is UiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = LslForestGreen)
                    Text("Loading LSL Merchandising Dashboard…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        is UiState.Error -> {
            Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = LslErrorBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Unable to Sync Saved POs", style = MaterialTheme.typography.titleMedium, color = LslErrorText)
                        Text(savedPosState.message, style = MaterialTheme.typography.bodySmall, color = LslErrorText)
                        Spacer(modifier = Modifier.height(6.dp))
                        Button(
                            onClick = onUploadNewPo,
                            colors = ButtonDefaults.buttonColors(containerColor = LslForestGreen)
                        ) {
                            Text("Upload & Parse Buyer PO Offline")
                        }
                    }
                }
            }
        }
        is UiState.Success -> {
            val allPos = savedPosState.data
            val totalPoRecords = allPos.size.toLong()
            val totalPoQty = allPos.sumOf { it.totalQuantity }
            val totalBuyers = allPos.map { it.buyerName.trim().lowercase() }.filter { it.isNotBlank() }.distinct().size.toLong()
            val totalStyles = allPos.flatMap { po -> po.lineItems.map { it.styleNumber.trim().lowercase() } }
                .filter { it.isNotBlank() }
                .distinct()
                .size
                .toLong()
                .let { distinctAcrossAll -> if (distinctAcrossAll > 0L) distinctAcrossAll else allPos.sumOf { it.styleCount } }

            val pendingVerification = allPos.filter {
                it.status == PoStatus.PENDING_VERIFICATION || it.status == PoStatus.DRAFT || it.warningCount > 0L
            }
            val recentlyUploaded = allPos.sortedByDescending { it.createdAt?.seconds ?: 0L }.take(5)
            val recentlyUpdated = allPos.sortedByDescending { it.updatedAt?.seconds ?: 0L }.take(5)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Corporate LSL Header Banner
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(LslForestGreenDark, LslForestGreen, LslEmeraldAccent)
                                )
                            )
                            .padding(20.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = companyName.uppercase(),
                                style = MaterialTheme.typography.labelLarge,
                                color = LslPureWhite.copy(alpha = 0.82f)
                            )
                            Text(
                                text = "PO Transfer & Breakdown Command Center",
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = LslPureWhite
                            )
                            Text(
                                text = "Upload buyer Excel or CSV purchase orders, verify dynamic size matrices, and export production-ready LSL workbooks.",
                                style = MaterialTheme.typography.bodySmall,
                                color = LslPureWhite.copy(alpha = 0.88f)
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Button(
                                    onClick = onUploadNewPo,
                                    modifier = Modifier.testTag("dashboard_upload_button"),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = LslPureWhite,
                                        contentColor = LslForestGreenDark
                                    ),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.UploadFile,
                                        contentDescription = "Upload New PO",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Upload Buyer PO", fontWeight = FontWeight.SemiBold)
                                }
                                OutlinedButton(
                                    onClick = onOpenSavedPos,
                                    modifier = Modifier.testTag("dashboard_saved_pos_button"),
                                    border = BorderStroke(1.dp, LslPureWhite.copy(alpha = 0.55f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LslPureWhite),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Saved POs (${allPos.size})")
                                }
                            }
                        }
                    }
                }

                // 4 Merchandising KPI Cards
                item {
                    if (isWideScreen) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            LslKpiCard(
                                title = "Total PO Records",
                                value = formatInt(totalPoRecords),
                                subtitle = "${pendingVerification.size} pending check",
                                icon = Icons.Outlined.Description,
                                modifier = Modifier.weight(1f)
                            )
                            LslKpiCard(
                                title = "Total PO Quantity",
                                value = formatInt(totalPoQty),
                                subtitle = "Garment pieces",
                                icon = Icons.Outlined.Inventory2,
                                modifier = Modifier.weight(1f),
                                accentColor = LslEmeraldAccent
                            )
                            LslKpiCard(
                                title = "Total Buyers",
                                value = formatInt(totalBuyers),
                                subtitle = "Active accounts",
                                icon = Icons.Outlined.Business,
                                modifier = Modifier.weight(1f)
                            )
                            LslKpiCard(
                                title = "Total Styles",
                                value = formatInt(totalStyles),
                                subtitle = "Tracked styles",
                                icon = Icons.Outlined.Checkroom,
                                modifier = Modifier.weight(1f),
                                accentColor = LslEmeraldAccent
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                LslKpiCard(
                                    title = "Total PO Records",
                                    value = formatInt(totalPoRecords),
                                    subtitle = "${pendingVerification.size} pending check",
                                    icon = Icons.Outlined.Description,
                                    modifier = Modifier.weight(1f)
                                )
                                LslKpiCard(
                                    title = "Total PO Quantity",
                                    value = formatInt(totalPoQty),
                                    subtitle = "Garment pieces",
                                    icon = Icons.Outlined.Inventory2,
                                    modifier = Modifier.weight(1f),
                                    accentColor = LslEmeraldAccent
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                LslKpiCard(
                                    title = "Total Buyers",
                                    value = formatInt(totalBuyers),
                                    subtitle = "Active accounts",
                                    icon = Icons.Outlined.Business,
                                    modifier = Modifier.weight(1f)
                                )
                                LslKpiCard(
                                    title = "Total Styles",
                                    value = formatInt(totalStyles),
                                    subtitle = "Tracked styles",
                                    icon = Icons.Outlined.Checkroom,
                                    modifier = Modifier.weight(1f),
                                    accentColor = LslEmeraldAccent
                                )
                            }
                        }
                    }
                }

                // Empty State when no POs saved yet
                if (allPos.isEmpty()) {
                    item {
                        EmptyPoWorkspaceCard(
                            title = "No Buyer Purchase Orders Saved Yet",
                            subtitle = "Start by uploading an original buyer Excel (.xlsx, .xls) or CSV Purchase Order. LSL PO Manager will detect headers, map dynamic sizes, and generate standardized LSL breakdowns.",
                            primaryActionLabel = "Upload First Buyer PO",
                            onPrimaryAction = onUploadNewPo
                        )
                    }
                } else {
                    // Pending PO Verification Section
                    if (pendingVerification.isNotEmpty()) {
                        item {
                            SectionHeaderRow(
                                title = "Pending PO Verification (${pendingVerification.size})",
                                subtitle = "Requires merchandiser review or mismatch resolution"
                            )
                        }
                        items(pendingVerification.take(4), key = { "pending_${it.id}" }) { po ->
                            PendingVerificationCard(
                                po = po,
                                onReview = { onOpenPo(po, AppDestination.PO_TRANSFER) }
                            )
                        }
                    }

                    // Recently Uploaded POs
                    item {
                        SectionHeaderRow(
                            title = "Recently Uploaded POs",
                            subtitle = "Latest buyer files transferred into LSL database"
                        )
                    }
                    items(recentlyUploaded, key = { "uploaded_${it.id}" }) { po ->
                        DashboardPoListCard(
                            po = po,
                            timestampLabel = "Uploaded ${formatTimestamp(po.createdAt)}",
                            onClick = { onOpenPo(po, AppDestination.PO_BREAKDOWN) }
                        )
                    }

                    // Recently Updated POs
                    item {
                        SectionHeaderRow(
                            title = "Recently Updated POs",
                            subtitle = "Latest edited, verified, or exported records"
                        )
                    }
                    items(recentlyUpdated, key = { "updated_${it.id}" }) { po ->
                        DashboardPoListCard(
                            po = po,
                            timestampLabel = "Updated ${formatTimestamp(po.updatedAt ?: po.createdAt)}",
                            onClick = { onOpenPo(po, AppDestination.PO_BREAKDOWN) }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun SectionHeaderRow(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PendingVerificationCard(
    po: PurchaseOrderRecord,
    onReview: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onReview),
        shape = RoundedCornerShape(12.dp),
        color = LslWarningBg,
        border = BorderStroke(1.dp, LslWarningBorder)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (po.warningCount > 0L) Icons.Outlined.WarningAmber else Icons.Outlined.PendingActions,
                contentDescription = "Pending verification",
                tint = LslWarningText
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${po.poNumber}  •  ${po.buyerName}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatInt(po.totalQuantity)} pcs  •  ${po.styleCount} styles  •  ${po.warningCount} alert(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = LslWarningText
                )
            }
            OutlinedButton(
                onClick = onReview,
                border = BorderStroke(1.dp, LslWarningText),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = LslWarningText),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Verify", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun DashboardPoListCard(
    po: PurchaseOrderRecord,
    timestampLabel: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = po.poNumber,
                        style = MonoNumericStyle.copy(fontWeight = FontWeight.Bold, fontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp)),
                        color = LslForestGreen
                    )
                    Text(
                        text = po.buyerName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                PoStatusBadge(status = po.status)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${formatInt(po.totalQuantity)} pcs  •  ${po.styleCount} styles  •  ${formatCurrency(po.currency, po.totalValue)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = po.originalFileName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = timestampLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
