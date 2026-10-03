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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.localadb.manager.adb.AdbConnectionManager
import com.localadb.manager.adb.AdbMdnsManager
import com.localadb.manager.adb.PairingNotificationReceiver
import com.localadb.manager.backup.AdbBackupManager
import com.localadb.manager.backup.AdbRestoreManager
import com.localadb.manager.backup.AppInstalada
import com.localadb.manager.backup.BackupResult
import com.localadb.manager.backup.ManifestLam
import com.localadb.manager.backup.PackageCatalogManager
import com.localadb.manager.backup.RestoreResult
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
                    AppNavegacionPrincipal(
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

/**
 * Contenedor principal con barra de pestañas (Instalador / Copias de Seguridad).
 */
@Composable
fun AppNavegacionPrincipal(
    mdnsManager: AdbMdnsManager,
    adbManager: AdbConnectionManager,
    initialApkUri: Uri?,
    onOpenSettings: () -> Unit,
    onShowNotification: (Int) -> Unit
) {
    var pestanaActual by remember { mutableIntStateOf(0) }
    var connectPort by remember { mutableStateOf<Int?>(null) }
    var pairingPort by remember { mutableStateOf<Int?>(null) }

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

    LaunchedEffect(Unit) {
        mdnsManager.startDiscovery(mdnsCallback)
    }

    DisposableEffect(Unit) {
        onDispose { mdnsManager.stopDiscovery() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = pestanaActual) {
            Tab(
                selected = pestanaActual == 0,
                onClick = { pestanaActual = 0 },
                text = { Text(text = stringResource(R.string.tab_installer)) }
            )
            Tab(
                selected = pestanaActual == 1,
                onClick = { pestanaActual = 1 },
                text = { Text(text = stringResource(R.string.tab_backups)) }
            )
        }

        if (pestanaActual == 0) {
            InstallerScreen(
                adbManager = adbManager,
                connectPort = connectPort,
                pairingPort = pairingPort,
                initialApkUri = initialApkUri,
                onOpenSettings = onOpenSettings,
                onShowNotification = onShowNotification
            )
        } else {
            BackupCatalogScreen(
                adbManager = adbManager,
                connectPort = connectPort,
                mdnsManager = mdnsManager,
                mdnsCallback = mdnsCallback
            )
        }
    }
}

/**
 * Pestaña 2: Catálogo de Aplicaciones, Exportación y Motor de Restauración.
 */
@Composable
fun BackupCatalogScreen(
    adbManager: AdbConnectionManager,
    connectPort: Int?,
    mdnsManager: AdbMdnsManager,
    mdnsCallback: AdbMdnsManager.DiscoveryCallback
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var cargando by remember { mutableStateOf(true) }
    var textoBusqueda by remember { mutableStateOf("") }
    var listaApps by remember { mutableStateOf<List<AppInstalada>>(emptyList()) }

    var appSeleccionadaParaOpciones by remember { mutableStateOf<AppInstalada?>(null) }

    // Estados para operaciones en segundo plano (Backup y Restore)
    var operacionEnProceso by remember { mutableStateOf<String?>(null) }
    var tituloDialogoProgreso by remember { mutableStateOf("") }
    var progresoOperacion by remember { mutableFloatStateOf(0f) }
    var estadoOperacionTexto by remember { mutableStateOf("") }
    var dialogoResultadoTexto by remember { mutableStateOf<String?>(null) }

    fun recargarCatalogo() {
        cargando = true
        Thread {
            val resultado = PackageCatalogManager.obtenerAplicacionesUsuario(context)
            listaApps = resultado
            cargando = false
        }.start()
    }

    LaunchedEffect(Unit) {
        recargarCatalogo()
    }

    val appsFiltradas = remember(textoBusqueda, listaApps) {
        if (textoBusqueda.isBlank()) {
            listaApps
        } else {
            val query = textoBusqueda.trim().lowercase()
            listaApps.filter {
                it.nombreVisible.lowercase().contains(query) ||
                it.paqueteId.lowercase().contains(query)
            }
        }
    }

    // Ejecuta Backup (Solo APK o Contenedor Unificado .lam)
    fun ejecutarBackup(app: AppInstalada, paqueteCompletoLam: Boolean) {
        appSeleccionadaParaOpciones = null
        operacionEnProceso = app.nombreVisible
        tituloDialogoProgreso = context.getString(R.string.backup_dialog_title)
        progresoOperacion = 0f
        estadoOperacionTexto = if (paqueteCompletoLam) {
            context.getString(R.string.backup_status_creating_lam, app.nombreVisible)
        } else {
            context.getString(R.string.backup_status_extracting, app.nombreVisible)
        }

        coroutineScope.launch {
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
                operacionEnProceso = null
                dialogoResultadoTexto = context.getString(R.string.mdns_service_not_found)
                return@launch
            }

            val resultado = withContext(Dispatchers.IO) {
                if (paqueteCompletoLam) {
                    AdbBackupManager.exportarPaqueteCompletoLam(
                        context = context,
                        adbManager = adbManager,
                        targetPort = puerto,
                        app = app
                    ) { percent, status ->
                        progresoOperacion = percent / 100f
                        estadoOperacionTexto = status
                    }
                } else {
                    AdbBackupManager.exportarApk(
                        context = context,
                        adbManager = adbManager,
                        targetPort = puerto,
                        app = app
                    ) { percent, status ->
                        progresoOperacion = percent / 100f
                        estadoOperacionTexto = status
                    }
                }
            }

            operacionEnProceso = null
            dialogoResultadoTexto = when (resultado) {
                is BackupResult.Success -> {
                    val mb = resultado.tamanoBytes / (1024.0 * 1024.0)
                    val pesoFormateado = if (mb >= 1.0) {
                        String.format("%.2f MB", mb)
                    } else {
                        String.format("%.2f KB", resultado.tamanoBytes / 1024.0)
                    }
                    context.getString(R.string.backup_status_success, resultado.rutaFichero, pesoFormateado)
                }
                is BackupResult.Failure -> {
                    context.getString(R.string.backup_status_failed, resultado.motivo)
                }
            }
        }
    }

    // Orquestador de Restauración (.lam, .tar.gz, .apk, .apks)
    fun ejecutarRestauracion(uri: Uri) {
        val nombreArchivo = AdbRestoreManager.resolverNombreArchivo(context, uri)

        coroutineScope.launch {
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
                dialogoResultadoTexto = context.getString(R.string.mdns_service_not_found)
                return@launch
            }

            // Caso 1: Contenedor Unificado .lam (Paso 1: Instalar APKs -> Paso 2: Inyectar Datos)
            if (nombreArchivo.endsWith(".lam", ignoreCase = true)) {
                tituloDialogoProgreso = context.getString(R.string.restore_dialog_title)
                operacionEnProceso = nombreArchivo
                progresoOperacion = 0f
                estadoOperacionTexto = context.getString(R.string.restore_status_inspecting_lam)

                val resultado = withContext(Dispatchers.IO) {
                    AdbRestoreManager.restaurarPaqueteLam(
                        context = context,
                        adbManager = adbManager,
                        targetPort = puerto,
                        uri = uri
                    ) { percent, status ->
                        progresoOperacion = percent / 100f
                        estadoOperacionTexto = status
                    }
                }

                operacionEnProceso = null
                dialogoResultadoTexto = when (resultado) {
                    is RestoreResult.Success -> {
                        val manifest = AdbRestoreManager.leerManifestDeLam(context, uri)
                        val appName = manifest?.appName ?: "Aplicación"
                        context.getString(R.string.restore_status_lam_success, appName, resultado.paqueteId)
                    }
                    is RestoreResult.Failure -> {
                        context.getString(R.string.restore_status_failed, resultado.motivo)
                    }
                }
                recargarCatalogo()
            }
            // Caso 2: Datos Privados individuales (.tar.gz)
            else if (nombreArchivo.endsWith(".tar.gz", ignoreCase = true) || nombreArchivo.endsWith(".tgz", ignoreCase = true)) {
                tituloDialogoProgreso = context.getString(R.string.restore_dialog_title)
                operacionEnProceso = nombreArchivo
                progresoOperacion = 0f
                estadoOperacionTexto = context.getString(R.string.restore_status_preparing, nombreArchivo)

                val resultado = withContext(Dispatchers.IO) {
                    AdbRestoreManager.restaurarDatosPrivados(
                        context = context,
                        adbManager = adbManager,
                        targetPort = puerto,
                        uri = uri
                    ) { percent, status ->
                        progresoOperacion = percent / 100f
                        estadoOperacionTexto = status
                    }
                }

                operacionEnProceso = null
                dialogoResultadoTexto = when (resultado) {
                    is RestoreResult.Success -> {
                        val mb = resultado.tamanoBytes / (1024.0 * 1024.0)
                        val tamanoFormateado = if (mb >= 1.0) {
                            String.format("%.2f MB", mb)
                        } else {
                            String.format("%.2f KB", resultado.tamanoBytes / 1024.0)
                        }
                        context.getString(R.string.restore_status_success, resultado.paqueteId, tamanoFormateado)
                    }
                    is RestoreResult.Failure -> {
                        context.getString(R.string.restore_status_failed, resultado.motivo)
                    }
                }
            }
            // Caso 3: Binarios individuales (.apk / .apks / .xapk / .zip)
            else if (nombreArchivo.endsWith(".apk", ignoreCase = true) ||
                     nombreArchivo.endsWith(".apks", ignoreCase = true) ||
                     nombreArchivo.endsWith(".xapk", ignoreCase = true) ||
                     nombreArchivo.endsWith(".zip", ignoreCase = true)) {

                tituloDialogoProgreso = context.getString(R.string.title_installer)
                operacionEnProceso = nombreArchivo
                progresoOperacion = 0f
                estadoOperacionTexto = context.getString(R.string.install_status_creating_session)

                val totalBytes = resolveFileSize(context, uri)
                val analysis = withContext(Dispatchers.IO) {
                    BundleSplitFilter.inspectAndFilter(context, uri, totalBytes)
                }

                val resultado = withContext(Dispatchers.IO) {
                    AdbPackageInstaller.install(
                        context = context,
                        adbManager = adbManager,
                        targetPort = puerto,
                        apkUri = uri,
                        bundleAnalysis = analysis
                    ) { percent, status ->
                        progresoOperacion = percent / 100f
                        estadoOperacionTexto = status
                    }
                }

                operacionEnProceso = null
                dialogoResultadoTexto = when (resultado) {
                    is InstallResult.Success -> context.getString(R.string.install_status_success)
                    is InstallResult.Failure -> context.getString(R.string.install_status_failed, resultado.reason)
                }
                recargarCatalogo()
            } else {
                dialogoResultadoTexto = context.getString(R.string.restore_err_unrecognized_file)
            }
        }
    }

    val restoreFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            ejecutarRestauracion(uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.title_backup_catalog),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Selector principal de restauración de copias
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                restoreFilePickerLauncher.launch(
                    arrayOf(
                        "application/vnd.android.package-archive",
                        "application/gzip",
                        "application/x-gzip",
                        "application/x-tar",
                        "application/zip",
                        "application/octet-stream",
                        "*/*"
                    )
                )
            }
        ) {
            Text(text = stringResource(R.string.btn_restore_file))
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = textoBusqueda,
            onValueChange = { textoBusqueda = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(text = stringResource(R.string.backup_search_hint)) },
            singleLine = true,
            shape = RoundedCornerShape(10.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.backup_total_apps, appsFiltradas.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedButton(onClick = { recargarCatalogo() }) {
                Text(text = stringResource(R.string.btn_refresh_catalog))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (cargando) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = stringResource(R.string.backup_loading_apps), style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else if (appsFiltradas.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.backup_no_apps_found),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(appsFiltradas, key = { it.paqueteId }) { app ->
                    ItemAppCatalogo(
                        app = app,
                        estaEnProgreso = operacionEnProceso != null,
                        onCopiaDirectaApk = { ejecutarBackup(app, paqueteCompletoLam = false) },
                        onAbrirOpciones = { appSeleccionadaParaOpciones = app }
                    )
                }
            }
        }
    }

    // Diálogo modal exclusivo para Apps en modo depuración (ofrece APK o Contenedor Completo .lam)
    if (appSeleccionadaParaOpciones != null) {
        val app = appSeleccionadaParaOpciones!!
        AlertDialog(
            onDismissRequest = { appSeleccionadaParaOpciones = null },
            title = { Text(text = stringResource(R.string.title_backup_options)) },
            text = {
                Column {
                    Text(text = app.nombreVisible, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        text = app.paqueteId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Opción A: Copia de APK / APKS
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { ejecutarBackup(app, paqueteCompletoLam = false) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(text = stringResource(R.string.backup_opt_apk), fontWeight = FontWeight.SemiBold)
                            Text(
                                text = stringResource(R.string.backup_opt_desc_apk),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Opción B: Copia Completa (APK + Datos Privados en .lam)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { ejecutarBackup(app, paqueteCompletoLam = true) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = stringResource(R.string.backup_opt_full_lam),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.backup_opt_desc_full_lam),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { appSeleccionadaParaOpciones = null }) {
                    Text(text = stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    // Diálogo de progreso en tiempo real (Backup / Restore / Instalación)
    if (operacionEnProceso != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(text = tituloDialogoProgreso) },
            text = {
                Column {
                    Text(text = operacionEnProceso!!, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { progresoOperacion },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = estadoOperacionTexto, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {}
        )
    }

    // Diálogo informativo del resultado final
    if (dialogoResultadoTexto != null) {
        AlertDialog(
            onDismissRequest = { dialogoResultadoTexto = null },
            title = { Text(text = stringResource(R.string.backup_dialog_result_title)) },
            text = { Text(text = dialogoResultadoTexto!!) },
            confirmButton = {
                TextButton(onClick = { dialogoResultadoTexto = null }) {
                    Text(text = stringResource(R.string.btn_accept))
                }
            }
        )
    }
}

/**
 * Fila visual de cada aplicación en el catálogo.
 */
@Composable
fun ItemAppCatalogo(
    app: AppInstalada,
    estaEnProgreso: Boolean,
    onCopiaDirectaApk: () -> Unit,
    onAbrirOpciones: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val bitmapIcono = remember(app.icono) {
                app.icono?.let { convertirDrawableABitmap(it) }
            }

            if (bitmapIcono != null) {
                Image(
                    bitmap = bitmapIcono.asImageBitmap(),
                    contentDescription = app.nombreVisible,
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.nombreVisible,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = app.paqueteId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "v${app.versionNombre} (${app.versionCodigo})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                val colorFondo = if (app.esDepurable) Color(0xFF2E7D32) else MaterialTheme.colorScheme.secondaryContainer
                val colorTexto = if (app.esDepurable) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
                val textoInsignia = if (app.esDepurable) {
                    stringResource(R.string.backup_badge_debuggable)
                } else {
                    stringResource(R.string.backup_badge_standard)
                }

                Box(
                    modifier = Modifier
                        .background(colorFondo, shape = RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = textoInsignia,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = colorTexto
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            val textoBoton = if (app.esDepurable) {
                stringResource(R.string.btn_backup_action)
            } else {
                stringResource(R.string.btn_backup_apk)
            }

            Button(
                enabled = !estaEnProgreso,
                onClick = {
                    if (app.esDepurable) {
                        onAbrirOpciones()
                    } else {
                        onCopiaDirectaApk()
                    }
                }
            ) {
                Text(text = textoBoton, fontSize = 12.sp)
            }
        }
    }
}

/**
 * Pestaña 1: Pantalla del Instalador.
 */
@Composable
fun InstallerScreen(
    adbManager: AdbConnectionManager,
    connectPort: Int?,
    pairingPort: Int?,
    initialApkUri: Uri?,
    onOpenSettings: () -> Unit,
    onShowNotification: (Int) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var currentUri by remember { mutableStateOf(initialApkUri) }
    var parsedApk by remember { mutableStateOf<PackageDetails?>(null) }
    var bundleAnalysis by remember { mutableStateOf<BundleAnalysisResult?>(null) }
    var manifestLam by remember { mutableStateOf<ManifestLam?>(null) }
    var parsingError by remember { mutableStateOf<String?>(null) }

    var isInstalling by remember { mutableStateOf(false) }
    var installProgress by remember { mutableFloatStateOf(0f) }
    var installStatusMessage by remember { mutableStateOf<String?>(null) }

    fun processSelectedUri(uri: Uri) {
        currentUri = uri
        installStatusMessage = null
        parsingError = null
        manifestLam = null

        coroutineScope.launch {
            val totalBytes = resolveFileSize(context, uri)
            val nombre = AdbRestoreManager.resolverNombreArchivo(context, uri)

            // Si es un contenedor unificado .lam
            if (nombre.endsWith(".lam", ignoreCase = true)) {
                val manifest = withContext(Dispatchers.IO) {
                    AdbRestoreManager.leerManifestDeLam(context, uri)
                }
                if (manifest != null) {
                    manifestLam = manifest
                    parsedApk = PackageDetails(
                        displayName = manifest.appName,
                        identifier = manifest.packageName,
                        versionName = manifest.versionName,
                        versionCode = manifest.versionCode,
                        fileSizeBytes = totalBytes,
                        appIcon = null
                    )
                } else {
                    parsingError = context.getString(R.string.restore_err_invalid_lam)
                }
            } else {
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val estadoPuerto = if (connectPort != null) {
                stringResource(id = R.string.mdns_service_connect_found, connectPort)
            } else {
                stringResource(id = R.string.mdns_service_not_found)
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

                    if (manifestLam != null) {
                        Text(
                            text = stringResource(R.string.apk_lam_detected),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else if (bundleAnalysis?.isBundle == true) {
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

        val puertoActivo = connectPort
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = (bundleAnalysis != null || manifestLam != null) && currentUri != null && !isInstalling,
            onClick = {
                val uri = currentUri ?: return@Button

                isInstalling = true
                installProgress = 0f
                installStatusMessage = "Localizando puerto ADB..."

                coroutineScope.launch {
                    var puerto = puertoActivo
                    if (puerto == null) {
                        for (i in 1..15) {
                            delay(200)
                            puerto = connectPort
                            if (puerto != null) break
                        }
                    }

                    if (puerto == null) {
                        isInstalling = false
                        installStatusMessage = "Error: No se detecta el puerto de depuración Wi-Fi. Actívalo en Ajustes."
                        return@launch
                    }

                    // Si es un paquete .lam
                    if (manifestLam != null) {
                        val result = withContext(Dispatchers.IO) {
                            AdbRestoreManager.restaurarPaqueteLam(
                                context = context,
                                adbManager = adbManager,
                                targetPort = puerto,
                                uri = uri
                            ) { percent, status ->
                                installProgress = percent / 100f
                                installStatusMessage = status
                            }
                        }

                        isInstalling = false
                        installStatusMessage = when (result) {
                            is RestoreResult.Success -> "¡Restauración completa finalizada con éxito!"
                            is RestoreResult.Failure -> "Error: ${result.motivo}"
                        }
                    } else {
                        val analysis = bundleAnalysis ?: return@launch
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