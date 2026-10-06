package com.localadb.manager

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.input.KeyboardType
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

// Global application build version tag displayed in the top header
const val APP_BUILD_TAG = "v1.0.0-beta-Downgrade_Guard"

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
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppMainNavigation(
                        mdnsManager = mdnsManager,
                        adbManager = adbConnectionManager,
                        initialApkUri = selectedApkUri.value,
                        onOpenSettings = { openDeveloperSettings() },
                        onLaunchPairingNotification = { port -> launchPairingNotification(port) }
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
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
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

    fun launchPairingNotification(pairingPort: Int?) {
        checkNotificationPermission()

        val remoteInput = RemoteInput.Builder(PairingNotificationReceiver.KEY_TEXT_REPLY)
            .setLabel("Code (e.g. 123456 or 'Port Code')")
            .build()

        val intent = Intent(this, PairingNotificationReceiver::class.java).apply {
            if (pairingPort != null) {
                putExtra(PairingNotificationReceiver.EXTRA_PAIRING_PORT, pairingPort)
            }
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
            .setAutoCancel(true)
            .addAction(action)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(PairingNotificationReceiver.NOTIFICATION_ID, notification)
    }
}

/**
 * Main application navigation container featuring gesture swiping, global header, and build version tag.
 */
@Composable
fun AppMainNavigation(
    mdnsManager: AdbMdnsManager,
    adbManager: AdbConnectionManager,
    initialApkUri: Uri?,
    onOpenSettings: () -> Unit,
    onLaunchPairingNotification: (Int?) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 2 })

    var connectPort by remember { mutableStateOf<Int?>(null) }
    var pairingPort by remember { mutableStateOf<Int?>(null) }
    var manualConnectPort by remember { mutableStateOf<Int?>(null) }

    var showPairingMethodDialog by remember { mutableStateOf(false) }
    var showSplitScreenPairingDialog by remember { mutableStateOf(false) }
    var showManualConnectDialog by remember { mutableStateOf(false) }
    var showUsbSetupDialog by remember { mutableStateOf(false) }

    var dialogPairingResultText by remember { mutableStateOf<String?>(null) }
    var isPairingLoading by remember { mutableStateOf(false) }

    val effectiveConnectPort = manualConnectPort ?: connectPort

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

    fun restartMdnsDiscovery() {
        connectPort = null
        pairingPort = null
        mdnsManager.stopDiscovery()
        mdnsManager.startDiscovery(mdnsCallback)
    }

    LaunchedEffect(Unit) {
        mdnsManager.startDiscovery(mdnsCallback)
    }

    DisposableEffect(Unit) {
        onDispose { mdnsManager.stopDiscovery() }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // Global header card showing app title, build version tag, connection status, and quick action buttons
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                // Header row 1: Application Title and Visible Build Version Tag
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
                            text = APP_BUILD_TAG,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Header row 2: Connection port and action buttons
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
                            onClick = { restartMdnsDiscovery() },
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

        TabRow(selectedTabIndex = pagerState.currentPage) {
            Tab(
                selected = pagerState.currentPage == 0,
                onClick = {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(0)
                    }
                },
                text = { Text(text = stringResource(R.string.tab_installer)) }
            )
            Tab(
                selected = pagerState.currentPage == 1,
                onClick = {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(1)
                    }
                },
                text = { Text(text = stringResource(R.string.tab_backups)) }
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> InstallerScreen(
                    adbManager = adbManager,
                    connectPort = effectiveConnectPort,
                    initialApkUri = initialApkUri,
                    onOpenSettings = onOpenSettings,
                    onShowPairingDialog = { showPairingMethodDialog = true }
                )
                1 -> BackupCatalogScreen(
                    adbManager = adbManager,
                    connectPort = effectiveConnectPort,
                    mdnsManager = mdnsManager,
                    mdnsCallback = mdnsCallback
                )
            }
        }
    }

    // Dialog 1: Pairing method selector (Notification vs Split Screen)
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
                                onLaunchPairingNotification(pairingPort)
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

    // Dialog 2: Ultra-compact pairing input for Split Screen
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
                                restartMdnsDiscovery()
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

    // Dialog 3: USB TCP/IP setup guidance
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
                            manualConnectPort = 5555
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

    // Dialog 4: Manual connection port override
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
                            manualConnectPort = portInt
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

/**
 * Tab 2: Package Catalog, Backup generation, and Restoration pipeline.
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

    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var installedApps by remember { mutableStateOf<List<AppInstalada>>(emptyList()) }

    var selectedAppForOptions by remember { mutableStateOf<AppInstalada?>(null) }
    var selectedLamForOptions by remember { mutableStateOf<Pair<Uri, ManifestLam>?>(null) }

    var activeOperationName by remember { mutableStateOf<String?>(null) }
    var progressDialogTitle by remember { mutableStateOf("") }
    var operationProgress by remember { mutableFloatStateOf(0f) }
    var operationStatusText by remember { mutableStateOf("") }
    var resultDialogText by remember { mutableStateOf<String?>(null) }

    fun reloadCatalog() {
        isLoading = true
        Thread {
            val result = PackageCatalogManager.obtenerAplicacionesUsuario(context)
            installedApps = result
            isLoading = false
        }.start()
    }

    LaunchedEffect(Unit) {
        reloadCatalog()
    }

    val filteredApps = remember(searchQuery, installedApps) {
        if (searchQuery.isBlank()) {
            installedApps
        } else {
            val query = searchQuery.trim().lowercase()
            installedApps.filter {
                it.nombreVisible.lowercase().contains(query) ||
                it.paqueteId.lowercase().contains(query)
            }
        }
    }

    fun executeBackup(app: AppInstalada, isFullLamPackage: Boolean) {
        selectedAppForOptions = null
        activeOperationName = app.nombreVisible
        progressDialogTitle = context.getString(R.string.backup_dialog_title)
        operationProgress = 0f
        operationStatusText = if (isFullLamPackage) {
            context.getString(R.string.backup_status_creating_lam, app.nombreVisible)
        } else {
            context.getString(R.string.backup_status_extracting, app.nombreVisible)
        }

        coroutineScope.launch {
            var port = connectPort
            if (port == null) {
                mdnsManager.startDiscovery(mdnsCallback)
                for (i in 1..15) {
                    delay(200)
                    port = connectPort
                    if (port != null) break
                }
            }

            if (port == null) {
                activeOperationName = null
                resultDialogText = context.getString(R.string.mdns_service_not_found)
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                if (isFullLamPackage) {
                    AdbBackupManager.exportarPaqueteCompletoLam(
                        context = context,
                        adbManager = adbManager,
                        targetPort = port,
                        app = app
                    ) { percent, status ->
                        operationProgress = percent / 100f
                        operationStatusText = status
                    }
                } else {
                    AdbBackupManager.exportarApk(
                        context = context,
                        adbManager = adbManager,
                        targetPort = port,
                        app = app
                    ) { percent, status ->
                        operationProgress = percent / 100f
                        operationStatusText = status
                    }
                }
            }

            activeOperationName = null
            resultDialogText = when (result) {
                is BackupResult.Success -> {
                    val mb = result.tamanoBytes / (1024.0 * 1024.0)
                    val formattedWeight = if (mb >= 1.0) {
                        String.format("%.2f MB", mb)
                    } else {
                        String.format("%.2f KB", result.tamanoBytes / 1024.0)
                    }
                    context.getString(R.string.backup_status_success, result.rutaFichero, formattedWeight)
                }
                is BackupResult.Failure -> {
                    context.getString(R.string.backup_status_failed, result.motivo)
                }
            }
        }
    }

    fun executeLamRestoration(uri: Uri, apkOnly: Boolean) {
        val fileName = AdbRestoreManager.resolverNombreArchivo(context, uri)

        coroutineScope.launch {
            var port = connectPort
            if (port == null) {
                mdnsManager.startDiscovery(mdnsCallback)
                for (i in 1..15) {
                    delay(200)
                    port = connectPort
                    if (port != null) break
                }
            }

            if (port == null) {
                resultDialogText = context.getString(R.string.mdns_service_not_found)
                return@launch
            }

            progressDialogTitle = context.getString(R.string.restore_dialog_title)
            activeOperationName = fileName
            operationProgress = 0f
            operationStatusText = context.getString(R.string.restore_status_inspecting_lam)

            val result = withContext(Dispatchers.IO) {
                AdbRestoreManager.restaurarPaqueteLam(
                    context = context,
                    adbManager = adbManager,
                    targetPort = port,
                    uri = uri,
                    soloApk = apkOnly
                ) { percent, status ->
                    operationProgress = percent / 100f
                    operationStatusText = status
                }
            }

            activeOperationName = null
            resultDialogText = when (result) {
                is RestoreResult.Success -> {
                    val manifest = AdbRestoreManager.leerManifestDeLam(context, uri)
                    val appName = manifest?.appName ?: "App"
                    if (apkOnly) {
                        context.getString(R.string.restore_status_apk_only_success, appName, result.paqueteId)
                    } else {
                        context.getString(R.string.restore_status_lam_success, appName, result.paqueteId)
                    }
                }
                is RestoreResult.Failure -> {
                    context.getString(R.string.restore_status_failed, result.motivo)
                }
            }
            reloadCatalog()
        }
    }

    fun executeRestoration(uri: Uri) {
        val fileName = AdbRestoreManager.resolverNombreArchivo(context, uri)

        coroutineScope.launch {
            var port = connectPort
            if (port == null) {
                mdnsManager.startDiscovery(mdnsCallback)
                for (i in 1..15) {
                    delay(200)
                    port = connectPort
                    if (port != null) break
                }
            }

            if (port == null) {
                resultDialogText = context.getString(R.string.mdns_service_not_found)
                return@launch
            }

            if (fileName.endsWith(".lam", ignoreCase = true)) {
                val manifest = withContext(Dispatchers.IO) {
                    AdbRestoreManager.leerManifestDeLam(context, uri)
                }

                if (manifest != null && manifest.hasPrivateData) {
                    selectedLamForOptions = Pair(uri, manifest)
                } else {
                    executeLamRestoration(uri, apkOnly = true)
                }
            } else if (fileName.endsWith(".tar.gz", ignoreCase = true) || fileName.endsWith(".tgz", ignoreCase = true)) {
                progressDialogTitle = context.getString(R.string.restore_dialog_title)
                activeOperationName = fileName
                operationProgress = 0f
                operationStatusText = context.getString(R.string.restore_status_preparing, fileName)

                val result = withContext(Dispatchers.IO) {
                    AdbRestoreManager.restaurarDatosPrivados(
                        context = context,
                        adbManager = adbManager,
                        targetPort = port,
                        uri = uri
                    ) { percent, status ->
                        operationProgress = percent / 100f
                        operationStatusText = status
                    }
                }

                activeOperationName = null
                resultDialogText = when (result) {
                    is RestoreResult.Success -> {
                        val mb = result.tamanoBytes / (1024.0 * 1024.0)
                        val formattedSize = if (mb >= 1.0) {
                            String.format("%.2f MB", mb)
                        } else {
                            String.format("%.2f KB", result.tamanoBytes / 1024.0)
                        }
                        context.getString(R.string.restore_status_success, result.paqueteId, formattedSize)
                    }
                    is RestoreResult.Failure -> {
                        context.getString(R.string.restore_status_failed, result.motivo)
                    }
                }
            } else if (fileName.endsWith(".apk", ignoreCase = true) ||
                     fileName.endsWith(".apks", ignoreCase = true) ||
                     fileName.endsWith(".xapk", ignoreCase = true) ||
                     fileName.endsWith(".zip", ignoreCase = true)) {

                progressDialogTitle = context.getString(R.string.title_installer)
                activeOperationName = fileName
                operationProgress = 0f
                operationStatusText = context.getString(R.string.install_status_creating_session)

                val totalBytes = resolveFileSize(context, uri)
                val analysis = withContext(Dispatchers.IO) {
                    BundleSplitFilter.inspectAndFilter(context, uri, totalBytes)
                }

                val result = withContext(Dispatchers.IO) {
                    AdbPackageInstaller.install(
                        context = context,
                        adbManager = adbManager,
                        targetPort = port,
                        apkUri = uri,
                        bundleAnalysis = analysis,
                        permitirDowngrade = false
                    ) { percent, status ->
                        operationProgress = percent / 100f
                        operationStatusText = status
                    }
                }

                activeOperationName = null
                resultDialogText = when (result) {
                    is InstallResult.Success -> context.getString(R.string.install_status_success)
                    is InstallResult.Failure -> context.getString(R.string.install_status_failed, result.reason)
                }
                reloadCatalog()
            } else {
                resultDialogText = context.getString(R.string.restore_err_unrecognized_file)
            }
        }
    }

    val restoreFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            executeRestoration(uri)
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
            value = searchQuery,
            onValueChange = { searchQuery = it },
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
                text = stringResource(R.string.backup_total_apps, filteredApps.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedButton(onClick = { reloadCatalog() }) {
                Text(text = stringResource(R.string.btn_refresh_catalog))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (isLoading) {
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
        } else if (filteredApps.isEmpty()) {
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
                items(filteredApps, key = { it.paqueteId }) { app ->
                    ItemAppCatalog(
                        app = app,
                        isBusy = activeOperationName != null,
                        onDirectApkCopy = { executeBackup(app, isFullLamPackage = false) },
                        onOpenOptions = { selectedAppForOptions = app }
                    )
                }
            }
        }
    }

    if (selectedAppForOptions != null) {
        val app = selectedAppForOptions!!
        AlertDialog(
            onDismissRequest = { selectedAppForOptions = null },
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

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { executeBackup(app, isFullLamPackage = false) },
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

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { executeBackup(app, isFullLamPackage = true) },
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
                TextButton(onClick = { selectedAppForOptions = null }) {
                    Text(text = stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    if (selectedLamForOptions != null) {
        val (uri, manifest) = selectedLamForOptions!!
        AlertDialog(
            onDismissRequest = { selectedLamForOptions = null },
            title = { Text(text = stringResource(R.string.title_restore_options)) },
            text = {
                Column {
                    Text(text = manifest.appName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        text = manifest.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val targetUri = uri
                                selectedLamForOptions = null
                                executeLamRestoration(targetUri, apkOnly = true)
                            },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(text = stringResource(R.string.restore_opt_only_apk), fontWeight = FontWeight.SemiBold)
                            Text(
                                text = stringResource(R.string.restore_opt_desc_only_apk),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val targetUri = uri
                                selectedLamForOptions = null
                                executeLamRestoration(targetUri, apkOnly = false)
                            },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(text = stringResource(R.string.restore_opt_full), fontWeight = FontWeight.SemiBold)
                            Text(
                                text = stringResource(R.string.restore_opt_desc_full),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { selectedLamForOptions = null }) {
                    Text(text = stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    if (activeOperationName != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(text = progressDialogTitle) },
            text = {
                Column {
                    Text(text = activeOperationName!!, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { operationProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = operationStatusText, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {}
        )
    }

    if (resultDialogText != null) {
        AlertDialog(
            onDismissRequest = { resultDialogText = null },
            title = { Text(text = stringResource(R.string.backup_dialog_result_title)) },
            text = { Text(text = resultDialogText!!) },
            confirmButton = {
                TextButton(onClick = { resultDialogText = null }) {
                    Text(text = stringResource(R.string.btn_accept))
                }
            }
        )
    }
}

@Composable
fun ItemAppCatalog(
    app: AppInstalada,
    isBusy: Boolean,
    onDirectApkCopy: () -> Unit,
    onOpenOptions: () -> Unit
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
            val iconBitmap = remember(app.icono) {
                app.icono?.let { convertDrawableToBitmap(it) }
            }

            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap.asImageBitmap(),
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

                val badgeBgColor = if (app.esDepurable) Color(0xFF2E7D32) else MaterialTheme.colorScheme.secondaryContainer
                val badgeTextColor = if (app.esDepurable) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
                val badgeText = if (app.esDepurable) {
                    stringResource(R.string.backup_badge_debuggable)
                } else {
                    stringResource(R.string.backup_badge_standard)
                }

                Box(
                    modifier = Modifier
                        .background(badgeBgColor, shape = RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = badgeTextColor
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            val buttonText = if (app.esDepurable) {
                stringResource(R.string.btn_backup_action)
            } else {
                stringResource(R.string.btn_backup_apk)
            }

            Button(
                enabled = !isBusy,
                onClick = {
                    if (app.esDepurable) {
                        onOpenOptions()
                    } else {
                        onDirectApkCopy()
                    }
                }
            ) {
                Text(text = buttonText, fontSize = 12.sp)
            }
        }
    }
}

/**
 * Tab 1: Package Installer Screen with Container Format Identification and Downgrade Detection.
 */
@Composable
fun InstallerScreen(
    adbManager: AdbConnectionManager,
    connectPort: Int?,
    initialApkUri: Uri?,
    onOpenSettings: () -> Unit,
    onShowPairingDialog: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var currentUri by remember { mutableStateOf(initialApkUri) }
    var parsedApk by remember { mutableStateOf<PackageDetails?>(null) }
    var bundleAnalysis by remember { mutableStateOf<BundleAnalysisResult?>(null) }
    var manifestLam by remember { mutableStateOf<ManifestLam?>(null) }
    var detectedContainerFormat by remember { mutableStateOf("APK") }

    var installedPkgInfo by remember { mutableStateOf<PackageInfo?>(null) }
    var isDowngradeDetected by remember { mutableStateOf(false) }
    var isInstalledDebuggable by remember { mutableStateOf(false) }
    var showDowngradeWarningDialog by remember { mutableStateOf(false) }

    var showLamRestoreDialog by remember { mutableStateOf(false) }
    var parsingError by remember { mutableStateOf<String?>(null) }

    var isInstalling by remember { mutableStateOf(false) }
    var installProgress by remember { mutableFloatStateOf(0f) }
    var installStatusMessage by remember { mutableStateOf<String?>(null) }

    fun inspectInstalledVersion(pkgId: String, incomingVersionCode: Long) {
        val pm = context.packageManager
        installedPkgInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkgId, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkgId, 0)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

        val installedCode = if (installedPkgInfo != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                installedPkgInfo!!.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                installedPkgInfo!!.versionCode.toLong()
            }
        } else -1L

        isDowngradeDetected = installedPkgInfo != null && incomingVersionCode < installedCode
        isInstalledDebuggable = installedPkgInfo?.applicationInfo?.let {
            (it.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } ?: false
    }

    fun processSelectedUri(uri: Uri) {
        currentUri = uri
        installStatusMessage = null
        parsingError = null
        manifestLam = null
        installedPkgInfo = null
        isDowngradeDetected = false
        isInstalledDebuggable = false

        coroutineScope.launch {
            val totalBytes = resolveFileSize(context, uri)
            val fileName = AdbRestoreManager.resolverNombreArchivo(context, uri)
            val lowerName = fileName.lowercase()

            // Resolve explicit file container format type (APK, APKS, XAPK, LAM, ZIP)
            detectedContainerFormat = when {
                lowerName.endsWith(".xapk") -> "XAPK"
                lowerName.endsWith(".apks") -> "APKS"
                lowerName.endsWith(".lam") -> "LAM"
                lowerName.endsWith(".zip") -> "ZIP"
                lowerName.endsWith(".apk") -> "APK"
                else -> "APK"
            }

            if (detectedContainerFormat == "LAM") {
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
                    inspectInstalledVersion(manifest.packageName, manifest.versionCode)
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
                    inspectInstalledVersion(details.identifier, details.versionCode)
                } else {
                    parsingError = "Could not parse package metadata."
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

    // Unified install runner handling monolithic APKs, splits, and .lam bundles
    fun executeInstallation(allowDowngrade: Boolean = false, apkOnlyInLam: Boolean = false) {
        val uri = currentUri ?: return
        showDowngradeWarningDialog = false
        showLamRestoreDialog = false

        isInstalling = true
        installProgress = 0f
        installStatusMessage = "Connecting to ADB daemon..."

        coroutineScope.launch {
            val port = connectPort
            if (port == null) {
                isInstalling = false
                installStatusMessage = "Error: ADB port not detected. Sync port or check Settings."
                return@launch
            }

            if (manifestLam != null) {
                val result = withContext(Dispatchers.IO) {
                    AdbRestoreManager.restaurarPaqueteLam(
                        context = context,
                        adbManager = adbManager,
                        targetPort = port,
                        uri = uri,
                        soloApk = apkOnlyInLam
                    ) { percent, status ->
                        installProgress = percent / 100f
                        installStatusMessage = status
                    }
                }

                isInstalling = false
                installStatusMessage = when (result) {
                    is RestoreResult.Success -> {
                        if (apkOnlyInLam) "App installed successfully!"
                        else "App and user data restored successfully!"
                    }
                    is RestoreResult.Failure -> "Error: ${result.motivo}"
                }
            } else {
                val analysis = bundleAnalysis ?: run {
                    isInstalling = false
                    installStatusMessage = "Error: Could not analyze package."
                    return@launch
                }

                val result = withContext(Dispatchers.IO) {
                    AdbPackageInstaller.install(
                        context = context,
                        adbManager = adbManager,
                        targetPort = port,
                        apkUri = uri,
                        bundleAnalysis = analysis,
                        permitirDowngrade = allowDowngrade
                    ) { percent, status ->
                        installProgress = percent / 100f
                        installStatusMessage = status
                    }
                }

                isInstalling = false
                installStatusMessage = when (result) {
                    is InstallResult.Success -> "Installation completed successfully!"
                    is InstallResult.Failure -> "Error: ${result.reason}"
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(id = R.string.title_installer),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 4.dp)
        )

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
                        val iconBitmap = remember(apk.appIcon) {
                            apk.appIcon?.let { convertDrawableToBitmap(it) }
                        }

                        if (iconBitmap != null) {
                            Image(
                                bitmap = iconBitmap.asImageBitmap(),
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

                    // Version diagnostic and downgrade status compared against installed app
                    if (installedPkgInfo != null) {
                        val installedCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            installedPkgInfo!!.longVersionCode
                        } else {
                            @Suppress("DEPRECATION")
                            installedPkgInfo!!.versionCode.toLong()
                        }

                        Text(
                            text = stringResource(R.string.apk_version_installed, installedPkgInfo!!.versionName ?: "N/A", installedCode),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        val (badgeColor, badgeLabel) = when {
                            apk.versionCode < installedCode -> Color(0xFFE65100) to stringResource(R.string.apk_version_status_downgrade)
                            apk.versionCode > installedCode -> Color(0xFF2E7D32) to stringResource(R.string.apk_version_status_update)
                            else -> Color(0xFF1565C0) to stringResource(R.string.apk_version_status_same)
                        }

                        Box(
                            modifier = Modifier
                                .background(badgeColor, shape = RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = badgeLabel,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.apk_version_status_new),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Explicit container format display (APK, APKS, XAPK, LAM, ZIP)
                    val formatLabelText = when {
                        manifestLam != null || detectedContainerFormat == "LAM" -> {
                            "Formato: LAM (Contenedor con APKs + Datos)"
                        }
                        bundleAnalysis?.isBundle == true -> {
                            val count = bundleAnalysis?.compatibleSplits?.size ?: 0
                            "Formato: $detectedContainerFormat (Bundle con $count partes detectadas)"
                        }
                        else -> {
                            "Formato: $detectedContainerFormat (Monolítico independiente)"
                        }
                    }

                    Text(
                        text = formatLabelText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )

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

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = (bundleAnalysis != null || manifestLam != null) && currentUri != null && !isInstalling,
            onClick = {
                if (isDowngradeDetected) {
                    showDowngradeWarningDialog = true
                } else {
                    executeInstallation(allowDowngrade = false, apkOnlyInLam = false)
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

    // Downgrade warning confirmation modal (strictly distinguishes Debug vs Release policies)
    if (showDowngradeWarningDialog && parsedApk != null && installedPkgInfo != null) {
        val apk = parsedApk!!
        val installedCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            installedPkgInfo!!.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            installedPkgInfo!!.versionCode.toLong()
        }

        if (isInstalledDebuggable) {
            // Case A: Debuggable App - System allows forced in-place downgrade via -d flag
            AlertDialog(
                onDismissRequest = { showDowngradeWarningDialog = false },
                title = { Text(text = stringResource(R.string.title_downgrade_warning)) },
                text = {
                    Text(
                        text = stringResource(
                            R.string.downgrade_warning_debug_desc,
                            apk.versionName,
                            apk.versionCode,
                            installedPkgInfo!!.versionName ?: "N/A",
                            installedCode
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            executeInstallation(allowDowngrade = true, apkOnlyInLam = false)
                        }
                    ) {
                        Text(text = stringResource(R.string.btn_force_downgrade))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDowngradeWarningDialog = false }) {
                        Text(text = stringResource(R.string.btn_cancel))
                    }
                }
            )
        } else {
            // Case B: Production/Release App - Android kernel/SELinux strictly forbids in-place downgrade
            AlertDialog(
                onDismissRequest = { showDowngradeWarningDialog = false },
                title = { Text(text = stringResource(R.string.title_downgrade_blocked)) },
                text = {
                    Text(
                        text = stringResource(
                            R.string.downgrade_blocked_release_desc,
                            apk.versionName,
                            apk.versionCode,
                            installedPkgInfo!!.versionName ?: "N/A",
                            installedCode
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { showDowngradeWarningDialog = false }
                    ) {
                        Text(text = stringResource(R.string.btn_understood))
                    }
                }
            )
        }
    }

    // Modal dialog to select between APK-only or Full Restoration for .lam packages
    if (showLamRestoreDialog && manifestLam != null && currentUri != null) {
        val manifest = manifestLam!!
        AlertDialog(
            onDismissRequest = { showLamRestoreDialog = false },
            title = { Text(text = stringResource(R.string.title_restore_options)) },
            text = {
                Column {
                    Text(text = manifest.appName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        text = manifest.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                executeInstallation(allowDowngrade = false, apkOnlyInLam = true)
                            },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(text = stringResource(R.string.restore_opt_only_apk), fontWeight = FontWeight.SemiBold)
                            Text(
                                text = stringResource(R.string.restore_opt_desc_only_apk),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                executeInstallation(allowDowngrade = false, apkOnlyInLam = false)
                            },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(text = stringResource(R.string.restore_opt_full), fontWeight = FontWeight.SemiBold)
                            Text(
                                text = stringResource(R.string.restore_opt_desc_full),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLamRestoreDialog = false }) {
                    Text(text = stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

private fun convertDrawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        return drawable.bitmap
    }
    val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
    val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
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