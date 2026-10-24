package com.localadb.manager.ui.screens

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localadb.manager.R
import com.localadb.manager.adb.AdbConnectionManager
import com.localadb.manager.backup.AdbRestoreManager
import com.localadb.manager.backup.ManifestLam
import com.localadb.manager.backup.RestoreResult
import com.localadb.manager.installer.AdbPackageInstaller
import com.localadb.manager.installer.ApkParser
import com.localadb.manager.installer.BundleAnalysisResult
import com.localadb.manager.installer.BundleSplitFilter
import com.localadb.manager.installer.InstallResult
import com.localadb.manager.installer.PackageDetails
import com.localadb.manager.ui.components.convertDrawableToBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tab 1 Screen: Package Installer supporting APKs, Multi-splits, and .lam containers.
 * Features real-time version downgrade inspection and container format detection.
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
        val uri = currentUri!!
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

private fun resolveFileSize(context: Context, uri: Uri): Long {
    var size = 0L
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