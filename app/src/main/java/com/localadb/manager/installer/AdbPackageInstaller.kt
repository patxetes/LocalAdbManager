package com.localadb.manager.installer

import android.content.Context
import android.net.Uri
import com.localadb.manager.adb.AdbConnectionManager
import java.io.InputStream
import java.io.OutputStream
import java.util.regex.Pattern

/**
 * Resultado devuelto al finalizar el proceso de instalación.
 */
sealed class InstallResult {
    object Success : InstallResult()
    data class Failure(val reason: String) : InstallResult()
}

/**
 * Motor de instalación en streaming mediante sesiones del PackageInstaller de Android vía ADB.
 */
object AdbPackageInstaller {

    private const val BUFFER_SIZE = 64 * 1024 // Buffer de lectura de 64 KB (óptimo para sockets)

    /**
     * Orquestador principal de la instalación: ejecuta las 3 fases (create -> write -> commit).
     *
     * @param context Contexto de la aplicación para resolver el Uri
     * @param adbManager Conexión activa con permisos de shell (UID 2000)
     * @param apkUri Uri del archivo APK seleccionado
     * @param totalBytes Tamaño en bytes del APK
     * @param onProgress Callback para reportar el porcentaje (0 a 100) y el mensaje de estado
     */
    fun install(
        context: Context,
        adbManager: AdbConnectionManager,
        apkUri: Uri,
        totalBytes: Long,
        onProgress: (percent: Int, status: String) -> Unit
    ): InstallResult {

        // FASE 1: Crear la sesión en el gestor de paquetes
        onProgress(0, "Creando sesión de instalación...")
        val sessionId = createSession(adbManager, totalBytes)
            ?: return InstallResult.Failure("No se pudo iniciar la sesión en el PackageInstaller.")

        try {
            // FASE 2: Transmitir los bytes del APK al socket stdin de ADB
            onProgress(0, "Transmitiendo binario...")
            val streamSuccess = writeStreamToSession(context, adbManager, sessionId, apkUri, totalBytes, onProgress)
            if (!streamSuccess) {
                abandonSession(adbManager, sessionId)
                return InstallResult.Failure("Fallo durante la transferencia de bytes hacia ADB.")
            }

            // FASE 3: Confirmar la sesión para que el SO instale la app
            onProgress(100, "Instalando en el sistema...")
            val commitOutput = commitSession(adbManager, sessionId)

            return if (commitOutput.contains("Success", ignoreCase = true)) {
                InstallResult.Success
            } else {
                InstallResult.Failure(commitOutput.ifEmpty { "Error desconocido al confirmar instalación." })
            }

        } catch (e: Exception) {
            abandonSession(adbManager, sessionId)
            return InstallResult.Failure(e.localizedMessage ?: "Excepción no controlada durante instalación.")
        }
    }

    /**
     * Paso 1: Ejecuta 'pm install-create' y parsea el Session ID devuelto.
     * Flags:
     *  -r: Reinstalar la aplicación manteniendo datos previos.
     *  -t: Permitir instalación de paquetes marcados para testeo.
     *  -d: Permitir degradación de versión (downgrade).
     *  -S: Tamaño total esperado en bytes.
     */
    private fun createSession(adbManager: AdbConnectionManager, totalBytes: Long): String? {
        val command = "pm install-create -r -t -d -S $totalBytes"
        val response = adbManager.executeCommand(command)

        // El sistema responde con el patrón: "Success: [12345678]"
        val matcher = Pattern.compile("\\[(\\d+)\\]").matcher(response)
        return if (matcher.find()) {
            matcher.group(1)
        } else {
            null
        }
    }

    /**
     * Paso 2: Abre un canal virtual en ADB ('pm install-write ... -') y canaliza los bytes del APK.
     */
    private fun writeStreamToSession(
        context: Context,
        adbManager: AdbConnectionManager,
        sessionId: String,
        apkUri: Uri,
        totalBytes: Long,
        onProgress: (percent: Int, status: String) -> Unit
    ): Boolean {
        val inputStream: InputStream = context.contentResolver.openInputStream(apkUri) ?: return false

        return try {
            // El '-' al final indica al comando que debe leer del stdin del stream
            val command = "exec:pm install-write -S $totalBytes $sessionId base.apk -"
            val adbStream = adbManager.openStream(command)
            val outputStream: OutputStream = adbStream.openOutputStream()

            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            var totalBytesSent: Long = 0

            // Bombeo continuo de bytes con actualización periódica de porcentaje
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesSent += bytesRead

                if (totalBytes > 0) {
                    val percent = ((totalBytesSent * 100) / totalBytes).toInt()
                    onProgress(percent, "Transmitiendo: $percent%")
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()
            adbStream.close()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            try { inputStream.close() } catch (_: Exception) {}
            false
        }
    }

    /**
     * Paso 3: Ejecuta 'pm install-commit' para que el sistema valide firmas y consolide la app.
     */
    private fun commitSession(adbManager: AdbConnectionManager, sessionId: String): String {
        val command = "pm install-commit $sessionId"
        return adbManager.executeCommand(command)
    }

    /**
     * Cancela la sesión en el gestor de paquetes para liberar memoria si ocurrió algún fallo.
     */
    private fun abandonSession(adbManager: AdbConnectionManager, sessionId: String) {
        try {
            adbManager.executeCommand("pm install-abandon $sessionId")
        } catch (_: Exception) {}
    }
}