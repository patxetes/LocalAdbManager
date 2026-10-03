package com.localadb.manager

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.localadb.manager.adb.AdbConnectionManager
import com.localadb.manager.adb.AdbMdnsManager
import com.localadb.manager.adb.PairingNotificationReceiver
import com.localadb.manager.installer.AdbPackageInstaller
import com.localadb.manager.installer.ApkParser
import com.localadb.manager.installer.BundleAnalysisResult
import com.localadb.manager.installer.BundleSplitFilter
import com.localadb.manager.installer.InstallResult
import com.localadb.manager.installer.PackageDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var mdnsManager: AdbMdnsManager
    private lateinit var adbConnectionManager: AdbConnectionManager
    private val selectedApkUri = mutableStateOf<Uri?>(null)

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* No-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mdnsManager = AdbMdnsManager(applicationContext)
        adbConnectionManager = AdbConnectionManager.getInstance(applicationContext)

        setupNotificationChannel()
        checkNotificationPermission()
        captureIncomingApkIntent(intent)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    InstallerScreen(
                        mdnsManager = mdnsManager,
                        adbManager = adbConnectionManager,
                        initialApkUri = selectedApkUri.value,
                        onOpenSettings = { openDeveloperSettings() },
                        onShowNotification = { port -> showPairingNotification(port) }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureIncomingApkIntent(intent)
    }

    private fun captureIncomingApkIntent(incomingIntent: Intent?) {
        if (incomingIntent?.action == Intent.ACTION_VIEW) {
            incomingIntent.data?.let { uri ->
                selectedApkUri.value = uri
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
            val status = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            if (status != PackageManager.PERMISSION_GRANTED) {
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
fun InstallerScreen(
    mdnsManager: AdbMdnsManager,
    adbManager: AdbConnectionManager,
    initialApkUri: Uri?,
    onOpenSettings: () -> Unit,
    onShowNotification: (Int) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var connectPort by remember { mutableStateOf<Int?>(null) }
    var pairingPort by remember { mutableStateOf<Int?>(null) }

    var currentUri by remember { mutableStateOf(initialApkUri) }
    var parsedApk by remember { mutableStateOf<PackageDetails?>(null) }
    var bundleAnalysis by remember { mutableStateOf<BundleAnalysisResult?>(null) }
    var parsingError by remember { mutableStateOf<String?>(null) }

    var isInstalling by remember { mutableStateOf(false) }
    var installProgress by remember { mutableFloatStateOf(0f) }
    var installStatusMessage by remember { mutableStateOf<String?>(null) }

    val mdnsCallback = remember {
        object : AdbMdnsManager.DiscoveryCallback {
            override fun onConnectPortFound(port: Int) {
                connectPort = port
            }
            override fun onPairingPortFound(port: Int) {
                pairingPort = port
            }
            override fun onError(message: String) {}
        }
    }

    // 1. Escaneo automático al abrir la aplicación
    LaunchedEffect(Unit) {
        mdnsManager.startDiscovery(mdnsCallback)
    }

    DisposableEffect(Unit) {
        onDispose { mdnsManager.stopDiscovery() }
    }

    fun processSelectedUri(uri: Uri) {
        currentUri = uri
        installStatusMessage = null
        parsingError = null

        coroutineScope.launch {
            val totalBytes = resolveFileSize(context, uri)

            val analysis = withContext(Dispatchers.IO) {
                BundleSplitFilter.inspectAndFilter(context, uri, totalBytes)
            }
            bundleAnalysis = analysis

            val details = withContext(Dispatchers.IO) {
                ApkParser.extractDetails(context, uri)
            }

            if (details != null) {
                parsedApk = details
            } else {
                parsingError = "No se pudieron extraer los metadatos del paquete."
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            processSelectedUri(uri)
        }
    }

    DisposableEffect(initialApkUri) {
        if (initialApkUri != null) {
            processSelectedUri(initialApkUri)
        }
        onDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(id = R.string.app_name),
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = stringResource(id = R.string.title_installer),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Estado del puerto de conexión detectado
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val estadoPuerto = if (connectPort != null) {
                stringResource(id = R.string.mdns_service_connect_found, connectPort!!)
            } else {
                "Buscando depuración Wi-Fi..."
            }
            Text(
                text = estadoPuerto,
                style = MaterialTheme.typography.bodyMedium,
                color = if (connectPort != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedButton(onClick = onOpenSettings) {
                Text(text = stringResource(R.string.btn_open_dev_settings))
            }
        }

        if (pairingPort != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { pairingPort?.let { onShowNotification(it) } }) {
                Text(text = stringResource(R.string.btn_show_pairing_notification))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Selector manual de archivos
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                filePickerLauncher.launch(
                    arrayOf(
                        "application/vnd.android.package-archive",
                        "application/zip",
                        "application/octet-stream",
                        "*/*"
                    )
                )
            }
        ) {
            Text(text = stringResource(R.string.btn_select_apk))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Tarjeta con información e ICONO REAL del paquete
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.apk_info_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (parsedApk != null) {
                    val apk = parsedApk!!
                    val sizeMb = apk.fileSizeBytes / (1024.0 * 1024.0)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val iconoBitmap = remember(apk.appIcon) {
                            apk.appIcon?.let { convertirDrawableABitmap(it) }
                        }

                        if (iconoBitmap != null) {
                            Image(
                                bitmap = iconoBitmap.asImageBitmap(),
                                contentDescription = apk.displayName,
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                        }

                        Column {
                            Text(
                                text = apk.displayName,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = apk.identifier,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = stringResource(R.string.apk_version, apk.versionName, apk.versionCode),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    if (bundleAnalysis?.isBundle == true) {
                        val count = bundleAnalysis?.compatibleSplits?.size ?: 0
                        Text(
                            text = stringResource(R.string.apk_splits_detected, count),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.apk_single_detected),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    Text(
                        text = stringResource(R.string.apk_size, sizeMb),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else if (parsingError != null) {
                    Text(
                        text = stringResource(R.string.apk_parsing_error, parsingError!!),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        text = stringResource(R.string.apk_no_file_selected),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Botón inteligente Todo en Uno: escanea si hace falta, conecta e instala
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = bundleAnalysis != null && currentUri != null && !isInstalling,
            onClick = {
                val uri = currentUri ?: return@Button
                val analysis = bundleAnalysis ?: return@Button

                isInstalling = true
                installProgress = 0f
                installStatusMessage = "Localizando puerto ADB..."

                coroutineScope.launch {
                    // Si el puerto aún no fue resuelto por mDNS, esperamos hasta 3 segundos
                    var puerto = connectPort
                    if (puerto == null) {
                        mdnsManager.startDiscovery(mdnsCallback)
                        for (i in 1..15) {
                            delay(200)
                            puerto = connectPort
                            if (puerto != null) break
                        }
                    }

                    if (puerto == null) {
                        isInstalling = false
                        installStatusMessage = "Error: No se detecta el puerto de depuración Wi-Fi. Asegúrate de activarlo en Ajustes."
                        return@launch
                    }

                    val result = withContext(Dispatchers.IO) {
                        AdbPackageInstaller.install(
                            context = context,
                            adbManager = adbManager,
                            targetPort = puerto,
                            apkUri = uri,
                            bundleAnalysis = analysis
                        ) { percent, status ->
                            installProgress = percent / 100f
                            installStatusMessage = status
                        }
                    }

                    isInstalling = false
                    installStatusMessage = when (result) {
                        is InstallResult.Success -> "¡Instalación completada con éxito!"
                        is InstallResult.Failure -> "Error: ${result.reason}"
                    }
                }
            }
        ) {
            Text(text = stringResource(R.string.btn_install_apk))
        }

        if (isInstalling || installStatusMessage != null) {
            Spacer(modifier = Modifier.height(16.dp))

            if (isInstalling) {
                LinearProgressIndicator(
                    progress = { installProgress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            installStatusMessage?.let { msg ->
                val isError = msg.startsWith("Error", ignoreCase = true)
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

private fun convertirDrawableABitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        return drawable.bitmap
    }
    val ancho = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
    val alto = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
    val bitmap = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}

private fun resolveFileSize(context: Context, uri: Uri): Long {
    var size: Long = 0
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex != -1) {
                size = it.getLong(sizeIndex)
            }
        }
    }
    return size
}