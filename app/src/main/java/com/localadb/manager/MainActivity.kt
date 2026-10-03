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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.localadb.manager.adb.AdbMdnsManager
import com.localadb.manager.adb.PairingNotificationReceiver

class MainActivity : ComponentActivity() {

    private lateinit var mdnsManager: AdbMdnsManager

    // Lanzador para solicitar permiso de notificación en Android 13+
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* No-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mdnsManager = AdbMdnsManager(applicationContext)

        // Configuración inicial del sistema
        setupNotificationChannel()
        checkNotificationPermission()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PairingScreen(
                        mdnsManager = mdnsManager,
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
        // 1. Configurar campo de texto interactivo
        val remoteInput = RemoteInput.Builder(PairingNotificationReceiver.KEY_TEXT_REPLY)
            .setLabel(getString(R.string.notification_input_label))
            .build()

        // 2. Intent hacia nuestro BroadcastReceiver
        val intent = Intent(this, PairingNotificationReceiver::class.java).apply {
            putExtra(PairingNotificationReceiver.EXTRA_PAIRING_PORT, pairingPort)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        // 3. Crear el botón de acción dentro de la notificación
        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            getString(R.string.notification_action_send),
            pendingIntent
        ).addRemoteInput(remoteInput).build()

        // 4. Mostrar la notificación persistente
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
fun PairingScreen(
    mdnsManager: AdbMdnsManager,
    onOpenSettings: () -> Unit,
    onShowNotification: (Int) -> Unit
) {
    var isScanning by remember { mutableStateOf(false) }
    var connectPort by remember { mutableStateOf<Int?>(null) }
    var pairingPort by remember { mutableStateOf<Int?>(null) }
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
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(id = R.string.app_name),
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = stringResource(id = R.string.title_pairing),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Diagnóstico de puertos
        val connectText = if (connectPort != null) {
            stringResource(id = R.string.mdns_service_connect_found, connectPort!!)
        } else {
            stringResource(id = R.string.mdns_service_not_found)
        }
        Text(text = connectText, style = MaterialTheme.typography.bodyMedium)

        Spacer(modifier = Modifier.height(8.dp))

        if (pairingPort != null) {
            Text(
                text = stringResource(id = R.string.mdns_service_pairing_found, pairingPort!!),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Botón 1: Iniciar Escaneo mDNS
        Button(
            onClick = {
                if (isScanning) {
                    mdnsManager.stopDiscovery()
                    isScanning = false
                } else {
                    errorMessage = null
                    connectPort = null
                    pairingPort = null
                    mdnsManager.startDiscovery(mdnsCallback)
                    isScanning = true
                }
            }
        ) {
            Text(text = if (isScanning) stringResource(R.string.btn_stop_scan) else stringResource(R.string.btn_start_scan))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Botón 2: Abrir Ajustes de desarrollador directamente
        OutlinedButton(onClick = onOpenSettings) {
            Text(text = stringResource(R.string.btn_open_dev_settings))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Botón 3: Lanzar Notificación interactiva
        Button(
            enabled = pairingPort != null,
            onClick = {
                pairingPort?.let { onShowNotification(it) }
            }
        ) {
            Text(text = stringResource(R.string.btn_show_pairing_notification))
        }
    }
}