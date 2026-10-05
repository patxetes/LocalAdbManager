package com.localadb.manager.installer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.localadb.manager.adb.AdbConnectionManager
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

sealed class InstallResult {
    object Success : InstallResult()
    data class Failure(val reason: String) : InstallResult()
}

/**
 * Motor de instalación de aplicaciones en streaming mediante sesiones nativas de PackageInstaller por ADB.
 * Optimizado para Android 14–17 (soporte de cmd package con tamaño exacto -S y commit directo).
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
        onProgress: (percent: Int, status: String) -> Unit
    ): InstallResult {

        onProgress(5, "Conectando al demonio ADB local (127.0.0.1:$targetPort)...")
        val conectado = adbManager.connectDevice("127.0.0.1", targetPort)
        if (!conectado) {
            return InstallResult.Failure("No se pudo conectar a ADB en 127.0.0.1:$targetPort. Verifica que la depuración inalámbrica esté activa y el puerto sincronizado.")
        }

        val totalBytes = resolveFileSize(context, apkUri)
        var sessionId: Int? = null

        try {
            onProgress(15, "Creando sesión de instalación en Android...")

            // 1. Crear sesión de PackageInstaller adaptada a Android 17
            val (creadoId, errorDetalle) = crearSesionConDiagnostico(adbManager, totalBytes)
            if (creadoId == null) {
                return InstallResult.Failure("Fallo al crear sesión de instalación en Android:\n\n$errorDetalle")
            }
            sessionId = creadoId
            Log.i(TAG, "Sesión de instalación creada con ID: $sessionId")

            // 2. Transmisión de binarios (Monolítico o Splits)
            if (bundleAnalysis.isBundle) {
                val (exitoSplits, errorSplits) = transmitirSplits(context, adbManager, sessionId, apkUri, bundleAnalysis, totalBytes, onProgress)
                if (!exitoSplits) {
                    abandonarSesion(adbManager, sessionId)
                    return InstallResult.Failure("Error al transmitir los fragmentos a la sesión $sessionId:\n\n$errorSplits")
                }
            } else {
                val (exitoMonolitico, errorMonolitico) = transmitirMonolitico(context, adbManager, sessionId, apkUri, totalBytes, onProgress)
                if (!exitoMonolitico) {
                    abandonarSesion(adbManager, sessionId)
                    return InstallResult.Failure("Error al transmitir el APK base a la sesión $sessionId:\n\n$errorMonolitico")
                }
            }

            // 3. Confirmar e instalar la sesión
            onProgress(90, "Confirmando sesión de instalación ($sessionId)...")
            var respCommit = adbManager.executeCommand("cmd package install-commit $sessionId").trim()
            if (respCommit.contains("Unknown", ignoreCase = true) || respCommit.contains("Error:", ignoreCase = true)) {
                respCommit = adbManager.executeCommand("pm install-commit $sessionId").trim()
            }
            Log.i(TAG, "Respuesta commit sesión $sessionId: $respCommit")

            return if (respCommit.contains("Success", ignoreCase = true)) {
                onProgress(100, "¡Instalación completada!")
                InstallResult.Success
            } else {
                abandonarSesion(adbManager, sessionId)
                InstallResult.Failure("Android rechazó la instalación:\n\n${respCommit.ifEmpty { "Error desconocido en install-commit" }}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Excepción durante la instalación", e)
            if (sessionId != null) {
                abandonarSesion(adbManager, sessionId)
            }
            return InstallResult.Failure("Excepción durante la instalación: ${e.localizedMessage}")
        } finally {
            adbManager.disconnectDevice()
        }
    }

    /**
     * Crea la sesión de instalación probando flags con 'cmd package' y preasignación de tamaño.
     */
    private fun crearSesionConDiagnostico(adbManager: AdbConnectionManager, totalBytes: Long): Pair<Int?, String> {
        val sizeParam = if (totalBytes > 0) "-S $totalBytes" else ""

        val comandosAProbar = listOf(
            "cmd package install-create -r -t $sizeParam".trim(),
            "cmd package install-create -r -t",
            "cmd package install-create -r -t -d $sizeParam".trim(),
            "cmd package install-create -r",
            "pm install-create -r -t $sizeParam".trim(),
            "pm install-create -r -t",
            "pm install-create -r"
        )

        val registroRespuestas = StringBuilder()

        for (cmd in comandosAProbar) {
            val resp = adbManager.executeCommand(cmd).trim()
            Log.i(TAG, "Probando: '$cmd' -> Salida: '$resp'")
            val id = extraerSessionId(resp)
            if (id != null) {
                return Pair(id, resp)
            }
            registroRespuestas.appendLine("$cmd -> $resp")
        }

        return Pair(null, registroRespuestas.toString().trim())
    }

    private fun extraerSessionId(respuesta: String): Int? {
        val pattern = Regex("""\[(\d+)\]""")
        val match = pattern.find(respuesta)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun transmitirMonolitico(
        context: Context,
        adbManager: AdbConnectionManager,
        sessionId: Int,
        apkUri: Uri,
        totalBytes: Long,
        onProgress: (Int, String) -> Unit
    ): Pair<Boolean, String> {
        val sizeArg = if (totalBytes > 0) "-S $totalBytes" else ""
        val cmd = "cmd package install-write $sizeArg $sessionId base.apk -"
        Log.i(TAG, "Abriendo canal de escritura: $cmd")

        val canal = try {
            adbManager.abrirCanalRobusto("exec:$cmd")
        } catch (e: Exception) {
            try {
                adbManager.abrirCanalRobusto("exec:pm install-write $sizeArg $sessionId base.apk -")
            } catch (ex: Exception) {
                return Pair(false, "No se pudo abrir el canal ADB para install-write: ${ex.localizedMessage}")
            }
        }

        val input: InputStream = context.contentResolver.openInputStream(apkUri)
            ?: return Pair(false, "No se pudo leer el archivo APK seleccionado.")

        val output: OutputStream = canal.openOutputStream()

        var totalEscrito = 0L
        val buffer = ByteArray(BUFFER_SIZE)

        try {
            var leidos: Int
            while (input.read(buffer).also { leidos = it } != -1) {
                output.write(buffer, 0, leidos)
                totalEscrito += leidos
                if (totalBytes > 0) {
                    val pct = (20 + (totalEscrito * 65 / totalBytes)).toInt().coerceIn(20, 85)
                    onProgress(pct, "Transmitiendo APK (${totalEscrito / (1024 * 1024)} MB)...")
                }
            }
            output.flush()
            output.close()
            input.close()
            canal.close()
            return Pair(true, "")
        } catch (e: Exception) {
            Log.e(TAG, "Fallo transmitiendo APK monolítico", e)
            return Pair(false, e.localizedMessage ?: e.toString())
        }
    }

    private fun transmitirSplits(
        context: Context,
        adbManager: AdbConnectionManager,
        sessionId: Int,
        apkUri: Uri,
        bundleAnalysis: BundleAnalysisResult,
        totalBytes: Long,
        onProgress: (Int, String) -> Unit
    ): Pair<Boolean, String> {
        val rawInput = context.contentResolver.openInputStream(apkUri)
            ?: return Pair(false, "No se pudo leer el paquete de splits.")

        var totalEscrito = 0L
        val nombresCompatibles = bundleAnalysis.compatibleSplits.map { it.toString().substringAfterLast('/') }

        try {
            ZipInputStream(rawInput).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    val simpleName = entryName.substringAfterLast('/')

                    val esSplitValido = entryName.endsWith(".apk", ignoreCase = true) && !entry.isDirectory &&
                                        (nombresCompatibles.isEmpty() || nombresCompatibles.any { it.contains(simpleName) || simpleName.contains(it) } || simpleName == "base.apk")

                    if (esSplitValido) {
                        val sizeArg = if (entry.size > 0) "-S ${entry.size}" else ""
                        val cmd = "cmd package install-write $sizeArg $sessionId \"$simpleName\" -"
                        Log.i(TAG, "Transmitiendo split: $cmd")

                        val canal = try {
                            adbManager.abrirCanalRobusto("exec:$cmd")
                        } catch (e: Exception) {
                            adbManager.abrirCanalRobusto("exec:pm install-write $sizeArg $sessionId \"$simpleName\" -")
                        }

                        val output: OutputStream = canal.openOutputStream()
                        val buffer = ByteArray(BUFFER_SIZE)

                        var leidos: Int
                        while (zip.read(buffer).also { leidos = it } != -1) {
                            output.write(buffer, 0, leidos)
                            totalEscrito += leidos
                            if (totalBytes > 0) {
                                val pct = (20 + (totalEscrito * 65 / totalBytes)).toInt().coerceIn(20, 85)
                                onProgress(pct, "Transmitiendo $simpleName...")
                            }
                        }
                        output.flush()
                        output.close()
                        canal.close()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            return Pair(true, "")
        } catch (e: Exception) {
            Log.e(TAG, "Fallo transmitiendo splits", e)
            return Pair(false, e.localizedMessage ?: e.toString())
        }
    }

    private fun abandonarSesion(adbManager: AdbConnectionManager, sessionId: Int) {
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