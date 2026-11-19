package com.example.ui.screens

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
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.MerchandiserSettings
import com.example.ui.theme.LslErrorText
import com.example.ui.theme.LslForestGreen
import com.example.ui.theme.LslPureWhite

@Composable
fun SettingsScreen(
    settings: MerchandiserSettings,
    userEmail: String,
    userDisplayName: String,
    onSaveSettings: (MerchandiserSettings) -> Unit,
    onSignOut: () -> Unit
) {
    var companyName by remember { mutableStateOf(settings.companyName) }
    var defaultCurrency by remember { mutableStateOf(settings.defaultCurrency) }
    var defaultFactory by remember { mutableStateOf(settings.defaultFactory) }
    var strictValidation by remember { mutableStateOf(settings.strictValidation) }

    LaunchedEffect(settings) {
        companyName = settings.companyName
        defaultCurrency = settings.defaultCurrency
        defaultFactory = settings.defaultFactory
        strictValidation = settings.strictValidation
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Corporate Configuration Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("LSL Merchandising Settings", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "Configure default corporate branding and parsing defaults applied to Excel workbooks and PDF reports.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = companyName,
                        onValueChange = { companyName = it.take(120) },
                        label = { Text("Company Report Heading") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = defaultCurrency,
                            onValueChange = { defaultCurrency = it.uppercase().take(6) },
                            label = { Text("Default Currency") },
                            singleLine = true,
                            modifier = Modifier.weight(0.4f)
                        )
                        OutlinedTextField(
                            value = defaultFactory,
                            onValueChange = { defaultFactory = it.take(150) },
                            label = { Text("Default Factory Unit") },
                            singleLine = true,
                            modifier = Modifier.weight(0.6f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Strict PO Quantity Validation", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "Highlight size vs style quantity discrepancies and duplicate PO warnings prominently before verification.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = strictValidation,
                            onCheckedChange = { strictValidation = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = LslPureWhite,
                                checkedTrackColor = LslForestGreen
                            )
                        )
                    }

                    Button(
                        onClick = {
                            onSaveSettings(
                                settings.copy(
                                    companyName = companyName.ifBlank { "LAILA STYLES LIMITED" },
                                    defaultCurrency = defaultCurrency.ifBlank { "USD" },
                                    defaultFactory = defaultFactory,
                                    strictValidation = strictValidation
                                )
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("save_settings_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = LslForestGreen)
                    ) {
                        Icon(Icons.Outlined.Save, contentDescription = "Save Settings", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save Settings")
                    }
                }
            }
        }

        // Account & Security Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AccountCircle,
                            contentDescription = "Account",
                            tint = LslForestGreen,
                            modifier = Modifier.size(40.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = userDisplayName.ifBlank { "LSL Merchandiser" },
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = userEmail.ifBlank { "Signed in via Google" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        text = "Your saved PO records, immutable buyer file snapshots, and audit histories are securely isolated to your authenticated Google account in Cloud Firestore.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedButton(
                        onClick = onSignOut,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("sign_out_button"),
                        border = BorderStroke(1.dp, LslErrorText),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = LslErrorText)
                    ) {
                        Icon(Icons.Outlined.Logout, contentDescription = "Sign Out", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sign Out")
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}
