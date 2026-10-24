package com.localadb.manager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localadb.manager.R
import com.localadb.manager.adb.AdbConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Global header component displaying app title, build version, live ADB port status,
 * and quick-action dialogs for pairing, manual port configuration, and USB setup.
 */
@Composable
fun AdbHeaderCard(
    adbManager: AdbConnectionManager,
    effectiveConnectPort: Int?,
    pairingPort: Int?,
    buildTag: String,
    onRestartMdns: () -> Unit,
    onManualConnectPortChanged: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onLaunchPairingNotification: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var showPairingMethodDialog by remember { mutableStateOf(false) }
    var showSplitScreenPairingDialog by remember { mutableStateOf(false) }
    var showManualConnectDialog by remember { mutableStateOf(false) }
    var showUsbSetupDialog by remember { mutableStateOf(false) }

    var dialogPairingResultText by remember { mutableStateOf<String?>(null) }
    var isPairingLoading by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Row 1: App Title and Build Version Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = buildTag,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Row 2: Live Connection Port and Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val portText = if (effectiveConnectPort != null) {
                    stringResource(R.string.mdns_service_connect_found, effectiveConnectPort)
                } else {
                    stringResource(R.string.mdns_service_not_found)
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showManualConnectDialog = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = portText,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (effectiveConnectPort != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = onRestartMdns,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(text = stringResource(R.string.btn_sync_scan), fontSize = 11.sp)
                    }

                    Button(
                        onClick = { showPairingMethodDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(text = stringResource(R.string.btn_pair_action), fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = onOpenSettings,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(text = stringResource(R.string.btn_open_dev_settings), fontSize = 11.sp)
                    }
                }
            }
        }
    }

    // Dialog 1: Pairing Method Selector
    if (showPairingMethodDialog) {
        AlertDialog(
            onDismissRequest = { showPairingMethodDialog = false },
            title = { Text(text = stringResource(R.string.title_pairing_methods)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.pairing_methods_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onLaunchPairingNotification()
                                onOpenSettings()
                                showPairingMethodDialog = false
                            },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(text = stringResource(R.string.btn_pair_by_notification), fontWeight = FontWeight.SemiBold)
                            Text(text = stringResource(R.string.btn_pair_by_notification_desc), style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showPairingMethodDialog = false
                                showSplitScreenPairingDialog = true
                            },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(text = stringResource(R.string.btn_pair_by_split), fontWeight = FontWeight.SemiBold)
                            Text(text = stringResource(R.string.btn_pair_by_split_desc), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPairingMethodDialog = false }) {
                    Text(text = stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    // Dialog 2: Ultra-compact Pairing Input for Split Screen
    if (showSplitScreenPairingDialog) {
        var inputPort by remember { mutableStateOf(pairingPort?.toString() ?: "") }
        var inputCode by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { if (!isPairingLoading) showSplitScreenPairingDialog = false },
            title = { Text(text = stringResource(R.string.title_split_pairing), fontSize = 17.sp) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = stringResource(R.string.dialog_split_pairing_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = inputPort,
                        onValueChange = { if (it.length <= 5 && it.all { c -> c.isDigit() }) inputPort = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.label_pairing_port)) },
                        placeholder = { Text("e.g. 41235") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = inputCode,
                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) inputCode = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.label_pairing_code)) },
                        placeholder = { Text("6 digits") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    if (isPairingLoading) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = stringResource(R.string.pairing_in_progress), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = inputPort.length in 4..5 && inputCode.length == 6 && !isPairingLoading,
                    onClick = {
                        val portInt = inputPort.toIntOrNull() ?: return@Button
                        isPairingLoading = true

                        coroutineScope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                adbManager.pairDevice("127.0.0.1", portInt, inputCode)
                            }
                            isPairingLoading = false
                            showSplitScreenPairingDialog = false
                            dialogPairingResultText = if (ok) {
                                onRestartMdns()
                                context.getString(R.string.pairing_success)
                            } else {
                                context.getString(R.string.pairing_failed, "Port or code rejected. Ensure the Settings pairing window is visible.")
                            }
                        }
                    }
                ) {
                    Text(text = stringResource(R.string.btn_pair_action))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isPairingLoading,
                    onClick = { showSplitScreenPairingDialog = false }
                ) {
                    Text(text = stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    // Dialog 3: USB TCP/IP Setup Guidance
    if (showUsbSetupDialog) {
        AlertDialog(
            onDismissRequest = { showUsbSetupDialog = false },
            title = { Text(text = stringResource(R.string.title_usb_setup)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = stringResource(R.string.usb_setup_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            onManualConnectPortChanged(5555)
                            showUsbSetupDialog = false
                        }
                    ) {
                        Text(text = stringResource(R.string.btn_connect_localhost_5555))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showUsbSetupDialog = false }) {
                    Text(text = stringResource(R.string.btn_accept))
                }
            }
        )
    }

    // Dialog 4: Manual Connection Port Override
    if (showManualConnectDialog) {
        var inputConnectPort by remember { mutableStateOf(effectiveConnectPort?.toString() ?: "") }

        AlertDialog(
            onDismissRequest = { showManualConnectDialog = false },
            title = { Text(text = stringResource(R.string.title_manual_connect_port)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = stringResource(R.string.dialog_manual_connect_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = inputConnectPort,
                        onValueChange = { if (it.length <= 5 && it.all { c -> c.isDigit() }) inputConnectPort = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.label_connect_port)) },
                        placeholder = { Text("e.g. 39541") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = inputConnectPort.length in 4..5,
                    onClick = {
                        val portInt = inputConnectPort.toIntOrNull()
                        if (portInt != null) {
                            onManualConnectPortChanged(portInt)
                        }
                        showManualConnectDialog = false
                    }
                ) {
                    Text(text = stringResource(R.string.btn_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualConnectDialog = false }) {
                    Text(text = stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    if (dialogPairingResultText != null) {
        AlertDialog(
            onDismissRequest = { dialogPairingResultText = null },
            title = { Text(text = stringResource(R.string.title_manual_pairing)) },
            text = { Text(text = dialogPairingResultText!!) },
            confirmButton = {
                TextButton(onClick = { dialogPairingResultText = null }) {
                    Text(text = stringResource(R.string.btn_accept))
                }
            }
        )
    }
}