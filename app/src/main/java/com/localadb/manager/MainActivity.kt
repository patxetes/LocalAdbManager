package com.localadb.manager

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.localadb.manager.adb.AdbConnectionManager
import com.localadb.manager.adb.AdbMdnsManager
import com.localadb.manager.adb.PairingNotificationReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var mdnsManager: AdbMdnsManager
    private lateinit var adbConnectionManager: AdbConnectionManager

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* No-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mdnsManager = AdbMdnsManager(applicationContext)
        adbConnectionManager = AdbConnectionManager.getInstance(applicationContext)

        setupNotificationChannel()
        checkNotificationPermission()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ShellControlScreen(
                        mdnsManager = mdnsManager,
                        adbManager = adbConnectionManager,
                        onOpenSettings = { openDeveloperSettings() },
                        onShowNotification = { port -> showPairingNotification(port) }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mdnsManager.stopDiscovery()
    }

    private fun openDeveloperSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        startActivity(intent)
    }

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                PairingNotificationReceiver.CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionStatus = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            if (permissionStatus != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun showPairingNotification(pairingPort: Int) {
        val remoteInput = RemoteInput.Builder(PairingNotificationReceiver.KEY_TEXT_REPLY)
            .setLabel(getString(R.string.notification_input_label))
            .build()

        val intent = Intent(this, PairingNotificationReceiver::class.java).apply {
            putExtra(PairingNotificationReceiver.EXTRA_PAIRING_PORT, pairingPort)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            getString(R.string.notification_action_send),
            pendingIntent
        ).addRemoteInput(remoteInput).build()

        val notification = NotificationCompat.Builder(this, PairingNotificationReceiver.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(action)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(PairingNotificationReceiver.NOTIFICATION_ID, notification)
    }
}

@Composable
fun ShellControlScreen(
    mdnsManager: AdbMdnsManager,
    adbManager: AdbConnectionManager,
    onOpenSettings: () -> Unit,
    onShowNotification: (Int) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    // Estados reactivos de red
    var isScanning by remember { mutableStateOf(false) }
    var connectPort by remember { mutableStateOf<Int?>(null) }
    var pairingPort by remember { mutableStateOf<Int?>(null) }

    // Estados de conexión ADB y consola
    var isConnected by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var consoleOutput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val mdnsCallback = remember {
        object : AdbMdnsManager.DiscoveryCallback {
            override fun onConnectPortFound(port: Int) {
                connectPort = port
            }

            override fun onPairingPortFound(port: Int) {
                pairingPort = port
            }

            override fun onError(message: String) {
                errorMessage = message
                isScanning = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mdnsManager.stopDiscovery()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Título del Hito
        Text(
            text = stringResource(id = R.string.app_name),
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = stringResource(id = R.string.title_shell_exec),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Diagnóstico mDNS
        val connectText = if (connectPort != null) {
            stringResource(id = R.string.mdns_service_connect_found, connectPort!!)
        } else {
            stringResource(id = R.string.mdns_service_not_found)
        }
        Text(text = connectText, style = MaterialTheme.typography.bodyMedium)

        if (pairingPort != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(id = R.string.mdns_service_pairing_found, pairingPort!!),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Fila de botones de escaneo y ajustes
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = {
                    if (isScanning) {
                        mdnsManager.stopDiscovery()
                        isScanning = false
                    } else {
                        errorMessage = null
                        mdnsManager.startDiscovery(mdnsCallback)
                        isScanning = true
                    }
                }
            ) {
                Text(text = if (isScanning) stringResource(R.string.btn_stop_scan) else stringResource(R.string.btn_start_scan))
            }

            OutlinedButton(onClick = onOpenSettings) {
                Text(text = stringResource(R.string.btn_open_dev_settings))
            }
        }

        // Botón de emparejamiento (solo visible si se detecta puerto pairing)
        if (pairingPort != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { pairingPort?.let { onShowNotification(it) } }
            ) {
                Text(text = stringResource(R.string.btn_show_pairing_notification))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Estado de conexión ADB
        val adbStatusText = when {
            isConnecting -> stringResource(R.string.adb_status_connecting)
            isConnected -> stringResource(R.string.adb_status_connected, connectPort ?: 0)
            else -> stringResource(R.string.adb_status_disconnected)
        }
        Text(
            text = adbStatusText,
            style = MaterialTheme.typography.titleSmall,
            color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Botones de Conectar y Ejecutar comando Shell
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Botón Conectar / Desconectar
            Button(
                enabled = connectPort != null && !isConnecting,
                onClick = {
                    if (isConnected) {
                        adbManager.disconnectDevice()
                        isConnected = false
                    } else {
                        isConnecting = true
                        errorMessage = null
                        coroutineScope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                adbManager.connectDevice("127.0.0.1", connectPort!!)
                            }
                            isConnected = ok
                            isConnecting = false
                            if (!ok) {
                                errorMessage = "Fallo al conectar. ¿Está el dispositivo emparejado?"
                            }
                        }
                    }
                }
            ) {
                Text(text = if (isConnected) stringResource(R.string.btn_disconnect_adb) else stringResource(R.string.btn_connect_adb))
            }

            // Botón Ejecutar "id"
            Button(
                enabled = isConnected,
                onClick = {
                    coroutineScope.launch {
                        consoleOutput = "Ejecutando \"id\"..."
                        val result = withContext(Dispatchers.IO) {
                            adbManager.executeCommand("id")
                        }
                        consoleOutput = result
                    }
                }
            ) {
                Text(text = stringResource(R.string.btn_run_id_cmd))
            }
        }

        // Mensajes de error si los hubiera
        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Consola de Salida Shell (Simulación de Terminal Linux)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .background(Color(0xFF1E1E1E))
                .padding(12.dp)
        ) {
            Text(
                text = if (consoleOutput.isEmpty()) stringResource(R.string.shell_output_placeholder) else consoleOutput,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = if (consoleOutput.contains("uid=2000")) Color(0xFF4CAF50) else Color(0xFFD4D4D4)
            )
        }
    }
}