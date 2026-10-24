package com.localadb.manager

import android.Manifest
import android.app.LocaleManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
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
import com.localadb.manager.ui.screens.BackupCatalogScreen
import com.localadb.manager.ui.screens.InstallerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// Global application build tag displayed on the bottom bar
const val APP_BUILD_TAG = "v1.0.0-beta-Material3_UI"

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
            // Global State for Dark Mode
            var isDarkTheme by remember { mutableStateOf(false) }
            val colorScheme = if (isDarkTheme) darkColorScheme() else lightColorScheme()

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppMainNavigation(
                        mdnsManager = mdnsManager,
                        adbManager = adbConnectionManager,
                        initialApkUri = selectedApkUri.value,
                        isDarkTheme = isDarkTheme,
                        onThemeToggle = { isDarkTheme = !isDarkTheme },
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
            .setLabel("Código (ej: 123456 o 'Puerto Código')")
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
 * Top-level application coordinator managing Scaffold, AppBar, and Navigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppMainNavigation(
    mdnsManager: AdbMdnsManager,
    adbManager: AdbConnectionManager,
    initialApkUri: Uri?,
    isDarkTheme: Boolean,
    onThemeToggle: () -> Unit,
    onOpenSettings: () -> Unit,
    onLaunchPairingNotification: (Int?) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 2 })

    var connectPort by remember { mutableStateOf<Int?>(null) }
    var pairingPort by remember { mutableStateOf<Int?>(null) }
    var manualConnectPort by remember { mutableStateOf<Int?>(null) }

    var showMenu by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showPairingMethodDialog by remember { mutableStateOf(false) }
    var showSplitScreenPairingDialog by remember { mutableStateOf(false) }
    var showManualConnectDialog by remember { mutableStateOf(false) }

    var dialogPairingResultText by remember { mutableStateOf<String?>(null) }
    var isPairingLoading by remember { mutableStateOf(false) }

    val effectiveConnectPort = manualConnectPort ?: connectPort
    val isConnected = effectiveConnectPort != null

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

    Scaffold(
        modifier = Modifier.systemBarsPadding(), // Ensures app respects system status bar & bottom gestures
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    // Status LED
                    Box(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(14.dp)
                            .background(if (isConnected) Color(0xFF2E7D32) else Color(0xFFC62828), CircleShape)
                    )

                    // Gear Menu
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Opciones",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_sync)) },
                            onClick = {
                                showMenu = false
                                restartMdnsDiscovery()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_pair)) },
                            onClick = {
                                showMenu = false
                                showPairingMethodDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_settings)) },
                            onClick = {
                                showMenu = false
                                onOpenSettings()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_help)) },
                            onClick = {
                                showMenu = false
                                showHelpDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_language)) },
                            onClick = {
                                showMenu = false
                                showLanguageDialog = true
                            }
                        )
                        // Dark Mode switch aligned to the right and scaled down to 70%
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_dark_mode)) },
                            trailingIcon = {
                                Switch(
                                    checked = isDarkTheme,
                                    onCheckedChange = null,
                                    modifier = Modifier.scale(0.7f)
                                )
                            },
                            onClick = {
                                showMenu = false
                                onThemeToggle()
                            }
                        )
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .navigationBarsPadding() // Pushes BottomAppBar above system navigation gesture bar
                    .height(48.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val portText = if (isConnected) {
                        stringResource(R.string.mdns_service_connect_found, effectiveConnectPort!!)
                    } else {
                        stringResource(R.string.mdns_service_not_found)
                    }

                    // Clickable port info to manually change it
                    Text(
                        text = portText,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable { showManualConnectDialog = true }
                    )
                    
                    Text(
                        text = APP_BUILD_TAG,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Navigation TabRow synced with HorizontalPager
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

            // Two-page gesture pager decoupling Installer and BackupCatalog screens
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
    }

    // =========================================================================
    // DIALOGS
    // =========================================================================

    // Language Selector Dialog
    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(text = stringResource(R.string.title_language)) },
            text = {
                Column {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showLanguageDialog = false
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("es")
                                } else {
                                    val locale = Locale("es")
                                    Locale.setDefault(locale)
                                    val config = context.resources.configuration
                                    config.setLocale(locale)
                                    context.resources.updateConfiguration(config, context.resources.displayMetrics)
                                    (context as? ComponentActivity)?.recreate()
                                }
                            },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(text = stringResource(R.string.lang_es), modifier = Modifier.padding(16.dp), fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showLanguageDialog = false
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("en")
                                } else {
                                    val locale = Locale("en")
                                    Locale.setDefault(locale)
                                    val config = context.resources.configuration
                                    config.setLocale(locale)
                                    context.resources.updateConfiguration(config, context.resources.displayMetrics)
                                    (context as? ComponentActivity)?.recreate()
                                }
                            },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(text = stringResource(R.string.lang_en), modifier = Modifier.padding(16.dp), fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(text = stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    // Help & Tutorials Dialog
    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text(text = stringResource(R.string.title_help)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = stringResource(R.string.help_section_1_title),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.help_section_1_desc),
                        style = MaterialTheme.typography.bodySmall
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stringResource(R.string.help_section_2_title),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.help_section_2_desc),
                        style = MaterialTheme.typography.bodySmall
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            manualConnectPort = 5555
                            showHelpDialog = false
                        }
                    ) {
                        Text(text = stringResource(R.string.btn_connect_localhost_5555))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) {
                    Text(text = stringResource(R.string.btn_understood))
                }
            }
        )
    }

    // Pairing Method Selector (Notification vs Split Screen)
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

    // Ultra-compact Pairing Input for Split Screen
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

    // Manual Connection Port Override
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