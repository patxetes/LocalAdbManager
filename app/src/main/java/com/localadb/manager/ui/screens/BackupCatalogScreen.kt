package com.localadb.manager.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localadb.manager.R
import com.localadb.manager.adb.AdbConnectionManager
import com.localadb.manager.adb.AdbMdnsManager
import com.localadb.manager.backup.AdbBackupManager
import com.localadb.manager.backup.AdbRestoreManager
import com.localadb.manager.backup.AppInstalada
import com.localadb.manager.backup.BackupResult
import com.localadb.manager.backup.ManifestLam
import com.localadb.manager.backup.PackageCatalogManager
import com.localadb.manager.backup.RestoreResult
import com.localadb.manager.installer.AdbPackageInstaller
import com.localadb.manager.installer.BundleSplitFilter
import com.localadb.manager.installer.InstallResult
import com.localadb.manager.ui.components.ItemAppCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tab 2 Screen: Package Catalog, Backup Generator (.apk / .lam), and Restore Engine.
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