package com.localadb.manager

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.localadb.manager.adb.AdbConnectionManager
import com.localadb.manager.adb.AdbMdnsManager
import com.localadb.manager.adb.PairingNotificationReceiver
import com.localadb.manager.ui.components.AdbHeaderCard
import com.localadb.manager.ui.screens.BackupCatalogScreen
import com.localadb.manager.ui.screens.InstallerScreen
import kotlinx.coroutines.launch

// Global application build tag displayed on top header badge
const val APP_BUILD_TAG = "v1.0.0-beta-Modular_UI"

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
 * Top-level application coordinator managing horizontal page swiping and tab synchronization.
 */
@Composable
fun AppMainNavigation(
    mdnsManager: AdbMdnsManager,
    adbManager: AdbConnectionManager,
    initialApkUri: Uri?,
    onOpenSettings: () -> Unit,
    onLaunchPairingNotification: (Int?) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 2 })

    var connectPort by remember { mutableStateOf<Int?>(null) }
    var pairingPort by remember { mutableStateOf<Int?>(null) }
    var manualConnectPort by remember { mutableStateOf<Int?>(null) }

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

        // Global ADB connection header card with status, sync, and pairing dialogs
        AdbHeaderCard(
            adbManager = adbManager,
            effectiveConnectPort = effectiveConnectPort,
            pairingPort = pairingPort,
            buildTag = APP_BUILD_TAG,
            onRestartMdns = { restartMdnsDiscovery() },
            onManualConnectPortChanged = { port -> manualConnectPort = port },
            onOpenSettings = onOpenSettings,
            onLaunchPairingNotification = { onLaunchPairingNotification(pairingPort) }
        )

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
                    onShowPairingDialog = { onLaunchPairingNotification(pairingPort) }
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