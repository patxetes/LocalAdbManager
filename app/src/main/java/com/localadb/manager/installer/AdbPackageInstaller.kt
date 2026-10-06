package com.localadb.manager.installer

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import com.localadb.manager.adb.AdbConnectionManager
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

sealed class InstallResult {
    object Success : InstallResult()
    data class Failure(val reason: String) : InstallResult()
}

/**
 * Package installer engine using streaming ADB PackageInstaller sessions.
 * Compatible with Android 11 through Android 17 (uses native 'cmd package' with exact -S size).
 */
object AdbPackageInstaller {

    private const val TAG = "AdbPackageInstaller"
    private const val BUFFER_SIZE = 64 * 1024 // 64 KB

    fun install(
        context: Context,
        adbManager: AdbConnectionManager,
        targetPort: Int,
        apkUri: Uri,
        bundleAnalysis: BundleAnalysisResult,
        permitirDowngrade: Boolean = false,
        onProgress: (percent: Int, status: String) -> Unit
    ): InstallResult {

        onProgress(5, "Connecting to local ADB daemon (127.0.0.1:$targetPort)...")
        val isConnected = adbManager.connectDevice("127.0.0.1", targetPort)
        if (!isConnected) {
            return InstallResult.Failure("Could not connect to ADB on 127.0.0.1:$targetPort. Verify Wireless Debugging is enabled and port is synced.")
        }

        val totalBytes = resolveFileSize(context, apkUri)
        var sessionId: Int? = null

        try {
            onProgress(15, "Creating Android installation session...")

            // 1. Create PackageInstaller session with exact total size for preallocation
            val (createdId, errorDetail) = createSessionWithDiagnostics(adbManager, totalBytes, permitirDowngrade)
            if (createdId == null) {
                return InstallResult.Failure("Failed to create Android install session:\n\n$errorDetail")
            }
            sessionId = createdId
            Log.i(TAG, "Install session created with ID: $sessionId (Downgrade allowed: $permitirDowngrade)")

            // 2. Stream APK binaries (Monolithic or Multi-split bundle)
            if (bundleAnalysis.isBundle) {
                val (splitsSuccess, splitsError) = streamSplits(context, adbManager, sessionId, apkUri, totalBytes, onProgress)
                if (!splitsSuccess) {
                    abandonSession(adbManager, sessionId)
                    return InstallResult.Failure("Error streaming package splits to session $sessionId:\n\n$splitsError")
                }
            } else {
                val (monoSuccess, monoError) = streamMonolithic(context, adbManager, sessionId, apkUri, totalBytes, onProgress)
                if (!monoSuccess) {
                    abandonSession(adbManager, sessionId)
                    return InstallResult.Failure("Error streaming base APK to session $sessionId:\n\n$monoError")
                }
            }

            // 3. Commit and finalize installation session
            onProgress(90, "Finalizing installation session ($sessionId)...")
            var commitResponse = adbManager.executeCommand("cmd package install-commit $sessionId").trim()
            if (commitResponse.contains("Unknown", ignoreCase = true) || commitResponse.contains("Error:", ignoreCase = true)) {
                commitResponse = adbManager.executeCommand("pm install-commit $sessionId").trim()
            }
            Log.i(TAG, "Commit response for session $sessionId: $commitResponse")

            return if (commitResponse.contains("Success", ignoreCase = true)) {
                onProgress(100, "Installation completed!")
                InstallResult.Success
            } else {
                abandonSession(adbManager, sessionId)
                InstallResult.Failure("Android rejected installation:\n\n${commitResponse.ifEmpty { "Unknown error during install-commit" }}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Exception during installation", e)
            if (sessionId != null) {
                abandonSession(adbManager, sessionId)
            }
            return InstallResult.Failure("Installation exception: ${e.localizedMessage}")
        } finally {
            adbManager.disconnectDevice()
        }
    }

    /**
     * Attempts to create an install session using 'cmd package' and fallback to 'pm'.
     */
    private fun createSessionWithDiagnostics(
        adbManager: AdbConnectionManager,
        totalBytes: Long,
        allowDowngrade: Boolean
    ): Pair<Int?, String> {
        val sizeParam = if (totalBytes > 0) "-S $totalBytes" else ""

        val commandsToTry = if (allowDowngrade) {
            listOf(
                "cmd package install-create -r -t -d $sizeParam".trim(),
                "cmd package install-create -r -t -d",
                "cmd package install-create -r -t $sizeParam".trim(),
                "cmd package install-create -r -t",
                "pm install-create -r -t -d $sizeParam".trim(),
                "pm install-create -r -t -d",
                "pm install-create -r -t"
            )
        } else {
            listOf(
                "cmd package install-create -r -t $sizeParam".trim(),
                "cmd package install-create -r -t",
                "cmd package install-create -r",
                "pm install-create -r -t $sizeParam".trim(),
                "pm install-create -r -t",
                "pm install-create -r"
            )
        }

        val logOutput = StringBuilder()

        for (cmd in commandsToTry) {
            val resp = adbManager.executeCommand(cmd).trim()
            Log.i(TAG, "Trying: '$cmd' -> Output: '$resp'")
            val id = extractSessionId(resp)
            if (id != null) {
                return Pair(id, resp)
            }
            logOutput.appendLine("$cmd -> $resp")
        }

        return Pair(null, logOutput.toString().trim())
    }

    private fun extractSessionId(response: String): Int? {
        val pattern = Regex("""\[(\d+)\]""")
        val match = pattern.find(response)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun streamMonolithic(
        context: Context,
        adbManager: AdbConnectionManager,
        sessionId: Int,
        apkUri: Uri,
        totalBytes: Long,
        onProgress: (Int, String) -> Unit
    ): Pair<Boolean, String> {
        val sizeArg = if (totalBytes > 0) "-S $totalBytes" else ""
        val cmd = "cmd package install-write $sizeArg $sessionId base.apk -"
        Log.i(TAG, "Opening write stream: $cmd")

        val channel = try {
            adbManager.abrirCanalRobusto("exec:$cmd")
        } catch (e: Exception) {
            try {
                adbManager.abrirCanalRobusto("exec:pm install-write $sizeArg $sessionId base.apk -")
            } catch (ex: Exception) {
                return Pair(false, "Failed to open ADB write channel: ${ex.localizedMessage}")
            }
        }

        val input: InputStream = context.contentResolver.openInputStream(apkUri)
            ?: return Pair(false, "Could not open selected APK file.")

        val output: OutputStream = channel.openOutputStream()
        var totalWritten = 0L
        val buffer = ByteArray(BUFFER_SIZE)

        try {
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                totalWritten += read
                if (totalBytes > 0) {
                    val pct = (20 + (totalWritten * 65 / totalBytes)).toInt().coerceIn(20, 85)
                    onProgress(pct, "Streaming APK (${totalWritten / (1024 * 1024)} MB)...")
                }
            }
            output.flush()
            output.close()
            input.close()
            channel.close()
            return Pair(true, "")
        } catch (e: Exception) {
            Log.e(TAG, "Failed streaming monolithic APK", e)
            return Pair(false, e.localizedMessage ?: e.toString())
        }
    }

    /**
     * Stages the package bundle temporarily to local cache to read entries via ZipFile.
     * Selects all configuration/density/language splits and the best matching ABI split
     * so that Android never encounters an INSTALL_FAILED_MISSING_SPLIT error.
     */
    private fun streamSplits(
        context: Context,
        adbManager: AdbConnectionManager,
        sessionId: Int,
        apkUri: Uri,
        totalBytes: Long,
        onProgress: (Int, String) -> Unit
    ): Pair<Boolean, String> {

        val stagedBundle = File(context.cacheDir, "staged_bundle_${System.currentTimeMillis()}.zip")

        try {
            // Copy bundle to internal cache to allow random-access Central Directory reading via ZipFile
            context.contentResolver.openInputStream(apkUri)?.use { input ->
                FileOutputStream(stagedBundle).use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                }
            } ?: return Pair(false, "Could not open package bundle from URI.")

            val zipFile = ZipFile(stagedBundle)
            var totalWritten = 0L

            zipFile.use { zip ->
                val allApkEntries = zip.entries().asSequence()
                    .filter { it.name.endsWith(".apk", ignoreCase = true) && !it.isDirectory }
                    .toList()

                if (allApkEntries.isEmpty()) {
                    return Pair(false, "No valid APK files found in bundle archive.")
                }

                // Filter splits intelligently: keep base, all density/lang splits, and best matching ABI
                val targetEntriesToStream = selectRequiredSplits(allApkEntries)

                for (entry in targetEntriesToStream) {
                    val entryName = entry.name
                    val simpleName = entryName.substringAfterLast('/')
                    val exactSize = entry.size

                    if (exactSize <= 0) {
                        Log.w(TAG, "Skipping empty APK entry: $entryName")
                        continue
                    }

                    val sizeArg = "-S $exactSize"
                    val cmd = "cmd package install-write $sizeArg $sessionId \"$simpleName\" -"
                    Log.i(TAG, "Streaming split ($exactSize bytes): $cmd")

                    val channel = try {
                        adbManager.abrirCanalRobusto("exec:$cmd")
                    } catch (e: Exception) {
                        adbManager.abrirCanalRobusto("exec:pm install-write $sizeArg $sessionId \"$simpleName\" -")
                    }

                    val output = channel.openOutputStream()
                    val buffer = ByteArray(BUFFER_SIZE)

                    zip.getInputStream(entry).use { stream ->
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            totalWritten += read
                            if (totalBytes > 0) {
                                val pct = (20 + (totalWritten * 65 / totalBytes)).toInt().coerceIn(20, 85)
                                onProgress(pct, "Streaming $simpleName (${exactSize / 1024} KB)...")
                            }
                        }
                        output.flush()
                    }
                    output.close()
                    channel.close()
                }
            }
            return Pair(true, "")
        } catch (e: Exception) {
            Log.e(TAG, "Failed streaming splits from ZipFile", e)
            return Pair(false, e.localizedMessage ?: e.toString())
        } finally {
            stagedBundle.delete()
        }
    }

    /**
     * Resolves the complete set of splits required for installation.
     * Keeps base APK, all density/locale splits, and selects the best matching ABI split to avoid duplicate ABI conflicts.
     */
    private fun selectRequiredSplits(entries: List<ZipEntry>): List<ZipEntry> {
        val abiKeywords = listOf("arm64-v8a", "arm64_v8a", "armeabi-v7a", "armeabi_v7a", "armeabi", "x86_64", "x86")
        val supportedAbis = Build.SUPPORTED_ABIS.map { it.lowercase().replace('-', '_') }

        val abiSplits = mutableListOf<ZipEntry>()
        val nonAbiSplits = mutableListOf<ZipEntry>()

        for (entry in entries) {
            val lower = entry.name.lowercase().replace('-', '_')
            val isAbiSplit = abiKeywords.any { lower.contains(it.replace('-', '_')) }
            if (isAbiSplit) {
                abiSplits.add(entry)
            } else {
                nonAbiSplits.add(entry)
            }
        }

        // If no ABI splits exist, stream all entries (e.g. pure resource bundles or monolithic in zip)
        if (abiSplits.isEmpty()) {
            return entries
        }

        // Find best matching ABI from device supported ABIs
        var bestAbiSplitGroup: List<ZipEntry> = emptyList()
        for (deviceAbi in supportedAbis) {
            val matching = abiSplits.filter { it.name.lowercase().replace('-', '_').contains(deviceAbi) }
            if (matching.isNotEmpty()) {
                bestAbiSplitGroup = matching
                break
            }
        }

        // If no supported ABI matched (e.g. x86 emulator installing ARM-only XAPK), pick first available ABI to allow native bridge translation
        if (bestAbiSplitGroup.isEmpty()) {
            val firstAbiName = abiKeywords.firstOrNull { kw -> abiSplits.any { it.name.lowercase().replace('-', '_').contains(kw.replace('-', '_')) } }
            bestAbiSplitGroup = if (firstAbiName != null) {
                abiSplits.filter { it.name.lowercase().replace('-', '_').contains(firstAbiName.replace('-', '_')) }
            } else {
                listOf(abiSplits.first())
            }
        }

        // Combine: Base APK + All Density & Language Splits + Selected ABI splits
        return nonAbiSplits + bestAbiSplitGroup
    }

    private fun abandonSession(adbManager: AdbConnectionManager, sessionId: Int) {
        try {
            adbManager.executeCommand("cmd package install-abandon $sessionId")
        } catch (e: Exception) {
            try {
                adbManager.executeCommand("pm install-abandon $sessionId")
            } catch (ignored: Exception) {}
        }
    }

    private fun resolveFileSize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                it.statSize
            } ?: 0L
        } catch (e: Exception) {
            var size = 0L
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.SIZE)
                    if (index != -1) {
                        size = it.getLong(index)
                    }
                }
            }
            size
        }
    }
}