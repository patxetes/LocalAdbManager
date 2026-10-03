package com.localadb.manager.installer

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.localadb.manager.adb.AdbConnectionManager
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.regex.Pattern
import java.util.zip.ZipFile

sealed class InstallResult {
    object Success : InstallResult()
    data class Failure(val reason: String) : InstallResult()
}

/**
 * Motor de instalación en streaming mediante sesiones de PackageInstaller.
 * Gestiona el ciclo de vida del canal ADB con reintentos y tolerancia a retardos del sistema.
 */
object AdbPackageInstaller {

    private const val ETIQUETA_LOG = "AdbPackageInstaller"
    private const val TAMANO_BUFFER = 64 * 1024 // 64 KB

    fun install(
        context: Context,
        adbManager: AdbConnectionManager,
        targetPort: Int,
        apkUri: Uri,
        bundleAnalysis: BundleAnalysisResult,
        onProgress: (percent: Int, status: String) -> Unit
    ): InstallResult {

        val stagingDir = File(context.cacheDir, "install_staging")
        limpiarDirectorio(stagingDir)
        stagingDir.mkdirs()

        // 1. Conexión limpia y aislada para esta instalación
        onProgress(2, "Conectando al demonio local ADB...")
        val conexionOk = adbManager.connectDevice("127.0.0.1", targetPort)
        if (!conexionOk) {
            return InstallResult.Failure("No se pudo conectar a ADB en el puerto $targetPort. Comprueba que la depuración Wi-Fi esté activa.")
        }

        try {
            val archivosAInstalar = mutableListOf<File>()

            if (bundleAnalysis.isBundle) {
                onProgress(8, "Extrayendo fragmentos del contenedor...")
                val splits = extraerSplitsConZipFile(context, apkUri, stagingDir)
                if (splits.isEmpty()) {
                    return InstallResult.Failure("No se encontraron APKs compatibles en el contenedor.")
                }
                archivosAInstalar.addAll(splits)
            } else {
                onProgress(8, "Preparando archivo APK...")
                val apkUnico = File(stagingDir, "base.apk")
                copiarUriAFichero(context, apkUri, apkUnico)
                archivosAInstalar.add(apkUnico)
            }

            // 2. Crear sesión en PackageInstaller con tolerancia a esperas
            onProgress(15, "Iniciando sesión en PackageInstaller...")
            val sesionId = crearSesionConReintentos(adbManager)
                ?: return InstallResult.Failure("El gestor de paquetes de Android está ocupado. Inténtalo de nuevo en unos segundos.")

            // 3. Transmitir los fragmentos
            val resultadoEnvio = transmitirArchivosASesion(
                adbManager = adbManager,
                sesionId = sesionId,
                archivos = archivosAInstalar,
                onProgress = onProgress
            )

            if (resultadoEnvio is InstallResult.Failure) {
                cancelarSesionAdb(adbManager, sesionId)
                return resultadoEnvio
            }

            // 4. Confirmar la sesión
            onProgress(95, "Validando firmas e instalando en el sistema...")
            val respuestaCommit = confirmarSesionAdb(adbManager, sesionId)

            return if (respuestaCommit.contains("Success", ignoreCase = true)) {
                InstallResult.Success
            } else {
                InstallResult.Failure(respuestaCommit.ifEmpty { "Error devuelto por el Package Manager al confirmar." })
            }

        } catch (e: Exception) {
            Log.e(ETIQUETA_LOG, "Excepción durante la instalación", e)
            return InstallResult.Failure(e.localizedMessage ?: "Error inesperado durante la instalación.")
        } finally {
            // Desconectar siempre el socket y borrar temporales de disco
            adbManager.disconnectDevice()
            limpiarDirectorio(stagingDir)
        }
    }

    private fun extraerSplitsConZipFile(context: Context, uri: Uri, stagingDir: File): List<File> {
        val resultado = mutableListOf<File>()
        val abisDispositivo = Build.SUPPORTED_ABIS

        val ficheroContenedor = File(stagingDir, "origen.tmp")
        copiarUriAFichero(context, uri, ficheroContenedor)

        try {
            val zip = ZipFile(ficheroContenedor)
            val entradas = zip.entries()

            while (entradas.hasMoreElements()) {
                val entrada = entradas.nextElement()
                val nombre = entrada.name

                if (nombre.endsWith(".apk", ignoreCase = true)) {
                    if (esSplitCompatible(nombre, abisDispositivo)) {
                        val nombreLimpio = File(nombre).name
                        val destino = File(stagingDir, nombreLimpio)

                        zip.getInputStream(entrada).use { input ->
                            FileOutputStream(destino).use { output ->
                                input.copyTo(output)
                            }
                        }

                        resultado.add(destino)
                        Log.d(ETIQUETA_LOG, "Split extraído: $nombreLimpio (${destino.length()} bytes)")
                    }
                }
            }
            zip.close()
        } catch (e: Exception) {
            Log.e(ETIQUETA_LOG, "Error leyendo contenedor con ZipFile", e)
        } finally {
            if (ficheroContenedor.exists()) {
                ficheroContenedor.delete()
            }
        }

        return normalizarBaseApk(resultado)
    }

    private fun normalizarBaseApk(ficheros: List<File>): List<File> {
        if (ficheros.isEmpty()) return ficheros

        val yaExisteBase = ficheros.firstOrNull { it.name.equals("base.apk", ignoreCase = true) }
        if (yaExisteBase != null) return ficheros

        val principal = ficheros.firstOrNull { it.name.contains("base", ignoreCase = true) }
            ?: ficheros.maxByOrNull { it.length() }
            ?: ficheros.first()

        val destinoBase = File(principal.parentFile, "base.apk")
        principal.renameTo(destinoBase)

        return ficheros.map { if (it == principal) destinoBase else it }
    }

    private fun transmitirArchivosASesion(
        adbManager: AdbConnectionManager,
        sesionId: String,
        archivos: List<File>,
        onProgress: (percent: Int, status: String) -> Unit
    ): InstallResult {
        val total = archivos.size

        for ((index, archivo) in archivos.withIndex()) {
            val tamano = archivo.length()
            val nombre = archivo.name

            val progreso = 20 + ((index * 70) / total)
            onProgress(progreso, "Transmitiendo ($index de $total): $nombre...")

            val comando = "exec:pm install-write -S $tamano $sesionId $nombre -"
            val canalAdb = adbManager.abrirCanalRobusto(comando)
            val socketOut: OutputStream = canalAdb.openOutputStream()

            FileInputStream(archivo).use { fis ->
                val buffer = ByteArray(TAMANO_BUFFER)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    socketOut.write(buffer, 0, read)
                }
            }

            socketOut.flush()

            val reader = BufferedReader(InputStreamReader(canalAdb.openInputStream()))
            val respuesta = reader.readLine()?.trim() ?: ""
            canalAdb.close()

            Log.d(ETIQUETA_LOG, "Escrito $nombre ($tamano bytes) -> Respuesta: $respuesta")

            if (!respuesta.contains("Success", ignoreCase = true)) {
                return InstallResult.Failure("Fallo al escribir [$nombre]: $respuesta")
            }

            // Pausa de 120 ms para asegurar que el kernel de Android complete el vaciado del socket
            Thread.sleep(120)
        }
        return InstallResult.Success
    }

    /**
     * Intenta crear la sesión con reintentos para evitar bloqueos si el sistema está cerrando un paquete anterior.
     */
    private fun crearSesionConReintentos(adbManager: AdbConnectionManager): String? {
        val comando = "pm install-create -r -t -d"
        val patron = Pattern.compile("\\[(\\d+)\\]")

        for (intento in 1..3) {
            val respuesta = adbManager.executeCommand(comando)
            val matcher = patron.matcher(respuesta)
            if (matcher.find()) {
                return matcher.group(1)
            }
            Log.w(ETIQUETA_LOG, "Intento $intento de crear sesión fallido ($respuesta). Esperando 400ms...")
            Thread.sleep(400)
        }
        return null
    }

    private fun confirmarSesionAdb(adbManager: AdbConnectionManager, sesionId: String): String {
        return adbManager.executeCommand("pm install-commit $sesionId")
    }

    private fun cancelarSesionAdb(adbManager: AdbConnectionManager, sesionId: String) {
        try {
            adbManager.executeCommand("pm install-abandon $sesionId")
        } catch (_: Exception) {}
    }

    private fun copiarUriAFichero(context: Context, uri: Uri, destino: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destino).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun limpiarDirectorio(dir: File) {
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun esSplitCompatible(nombre: String, abisSoportadas: Array<String>): Boolean {
        val abisConocidas = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        val minusculas = nombre.lowercase().replace('_', '-')

        val abiEncontrada = abisConocidas.firstOrNull { minusculas.contains(it) } ?: return true
        return abisSoportadas.any { it.lowercase().replace('_', '-') == abiEncontrada }
    }
}