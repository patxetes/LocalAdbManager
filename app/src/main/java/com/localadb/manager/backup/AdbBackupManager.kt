package com.localadb.manager.backup

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import com.localadb.manager.adb.AdbConnectionManager
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

sealed class BackupResult {
    data class Success(val rutaFichero: String, val tamanoBytes: Long) : BackupResult()
    data class Failure(val motivo: String) : BackupResult()
}

/**
 * Motor de copias de seguridad de aplicaciones y datos de usuario.
 * Utiliza tuberías atómicas directas para sortear SELinux y restricciones de Scoped Storage (FUSE).
 */
object AdbBackupManager {

    private const val ETIQUETA_LOG = "AdbBackupManager"
    private const val BUFFER_SIZE = 64 * 1024 // 64 KB

    private fun obtenerDirectorioDestino(): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "LocalAdbBackups"
        )
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    // =========================================================================
    // RESPALDO DE BINARIOS (APK / BUNDLE APKS)
    // =========================================================================

    /**
     * Extrae el APK simple o empaqueta los splits en formato .apks.
     */
    fun exportarApk(
        context: Context,
        adbManager: AdbConnectionManager,
        targetPort: Int,
        app: AppInstalada,
        onProgreso: (porcentaje: Int, estado: String) -> Unit
    ): BackupResult {

        val directorioDestino = obtenerDirectorioDestino()

        onProgreso(5, "Conectando al demonio ADB...")
        val conexionOk = adbManager.connectDevice("127.0.0.1", targetPort)
        if (!conexionOk) {
            return BackupResult.Failure("No se pudo conectar a ADB en el puerto $targetPort. Activa la depuración Wi-Fi.")
        }

        val nombreSanitizado = sanitizarNombreFichero(app.nombreVisible)
        val identificador = app.paqueteId
        val version = app.versionNombre.replace('/', '_')

        try {
            return if (app.rutasSplits.isEmpty()) {
                // Caso A: App monolítica clásica -> Copia directa con cp a nivel de kernel
                val ficheroSalida = File(directorioDestino, "${nombreSanitizado}_${identificador}_v${version}.apk")
                exportarApkMonolitico(context, adbManager, app.rutaBaseApk, ficheroSalida, onProgreso)
            } else {
                // Caso B: App multi-split -> Streaming atómico TAR a ZIP sin carpetas intermedias en disco
                val ficheroSalida = File(directorioDestino, "${nombreSanitizado}_${identificador}_v${version}.apks")
                exportarSplitsApks(context, adbManager, app.rutaBaseApk, app.rutasSplits, ficheroSalida, onProgreso)
            }
        } catch (e: Exception) {
            Log.e(ETIQUETA_LOG, "Excepción durante la copia de ${app.paqueteId}", e)
            return BackupResult.Failure(e.localizedMessage ?: "Error inesperado durante la copia.")
        } finally {
            adbManager.disconnectDevice()
        }
    }

    /**
     * Copia un APK monolítico usando 'cp' a nivel de kernel (UID 2000).
     */
    private fun exportarApkMonolitico(
        context: Context,
        adbManager: AdbConnectionManager,
        rutaRemota: String,
        ficheroDestino: File,
        onProgreso: (porcentaje: Int, estado: String) -> Unit
    ): BackupResult {
        onProgreso(30, "Copiando archivo APK...")

        val comando = "cp \"$rutaRemota\" \"${ficheroDestino.absolutePath}\""
        val respuesta = adbManager.executeCommand(comando).trim()

        if (ficheroDestino.exists() && ficheroDestino.length() > 0) {
            prepararFicheroParaUsuario(context, adbManager, ficheroDestino)
            onProgreso(100, "¡Copia finalizada!")
            Log.i(ETIQUETA_LOG, "Copia completada: ${ficheroDestino.absolutePath} (${ficheroDestino.length()} bytes)")
            return BackupResult.Success(ficheroDestino.absolutePath, ficheroDestino.length())
        } else {
            ficheroDestino.delete()
            return BackupResult.Failure("Fallo al copiar APK con ADB: ${respuesta.ifEmpty { "Error de permisos o archivo no encontrado." }}")
        }
    }

    /**
     * Extrae todos los splits en un único canal continuo usando 'tar' en shell y
     * transformándolo al vuelo en un contenedor ZIP (.apks) válido en Java.
     * Cero archivos temporales en disco y cero problemas de Scoped Storage.
     */
    private fun exportarSplitsApks(
        context: Context,
        adbManager: AdbConnectionManager,
        rutaBase: String,
        rutasSplits: List<String>,
        ficheroDestino: File,
        onProgreso: (porcentaje: Int, estado: String) -> Unit
    ): BackupResult {
        val appDir = File(rutaBase).parentFile?.absolutePath
            ?: return BackupResult.Failure("No se pudo localizar el directorio de origen de los fragmentos.")

        val totalPartes = 1 + rutasSplits.size
        var partesProcesadas = 0
        var totalBytesEscritos = 0L

        // Argumentos relativos para tar: "base.apk" "split1.apk" "split2.apk" ...
        val baseName = File(rutaBase).name
        val splitNames = rutasSplits.map { File(it).name }
        val fileArgs = (listOf(baseName) + splitNames).joinToString(" ") { "\"$it\"" }
        val comandoTar = "exec:sh -c \"cd '$appDir' && tar -cf - $fileArgs\""

        onProgreso(15, "Iniciando empaquetado de fragmentos...")

        try {
            val canal = adbManager.abrirCanalRobusto(comandoTar)
            val tarInput = canal.openInputStream()

            FileOutputStream(ficheroDestino).use { fos ->
                BufferedOutputStream(fos, BUFFER_SIZE).use { bos ->
                    ZipOutputStream(bos).use { zipSalida ->
                        zipSalida.setLevel(Deflater.BEST_SPEED)

                        val header = ByteArray(512)
                        val buffer = ByteArray(BUFFER_SIZE)

                        while (true) {
                            if (!readFully(tarInput, header)) break
                            if (header[0] == 0.toByte()) break // Fin de archivo TAR (bloque de ceros)

                            // Lectura de campos cabecera estándar POSIX TAR (UStar)
                            val rawName = String(header, 0, 100, Charsets.US_ASCII).trimEnd('\u0000', ' ')
                            val sizeStr = String(header, 124, 12, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
                            val fileSize = sizeStr.toLongOrNull(8) ?: 0L

                            if (rawName.endsWith(".apk", ignoreCase = true) && fileSize > 0) {
                                partesProcesadas++
                                val entryName = File(rawName).name
                                val porcentaje = 15 + ((partesProcesadas * 80) / totalPartes)
                                onProgreso(porcentaje, "Guardando ($partesProcesadas de $totalPartes): $entryName...")

                                zipSalida.putNextEntry(ZipEntry(entryName))
                                copyExact(tarInput, zipSalida, fileSize, buffer) { bytes ->
                                    totalBytesEscritos += bytes
                                }
                                zipSalida.closeEntry()

                                // Salto del relleno de alineación a bloque de 512 bytes de TAR
                                val pad = ((512 - (fileSize % 512)) % 512).toInt()
                                if (pad > 0) {
                                    skipFully(tarInput, pad.toLong(), buffer)
                                }
                            } else if (fileSize > 0) {
                                val pad = ((512 - (fileSize % 512)) % 512).toInt()
                                skipFully(tarInput, fileSize + pad, buffer)
                            }
                        }
                        zipSalida.finish()
                        zipSalida.flush()
                    }
                }
            }
            tarInput.close()
            canal.close()

            prepararFicheroParaUsuario(context, adbManager, ficheroDestino)
            Log.i(ETIQUETA_LOG, "Bundle exportado con éxito: ${ficheroDestino.absolutePath} ($totalBytesEscritos bytes)")

            return if (ficheroDestino.exists() && ficheroDestino.length() > 0) {
                onProgreso(100, "¡Copia de bundle completada!")
                BackupResult.Success(ficheroDestino.absolutePath, ficheroDestino.length())
            } else {
                ficheroDestino.delete()
                BackupResult.Failure("El bundle generado está vacío o incompleto.")
            }
        } catch (e: Exception) {
            ficheroDestino.delete()
            Log.e(ETIQUETA_LOG, "Error al exportar bundle multi-split", e)
            return BackupResult.Failure(e.localizedMessage ?: "Error inesperado al empaquetar el bundle.")
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) {
                return offset == buffer.size
            }
            offset += read
        }
        return true
    }

    private fun copyExact(
        input: InputStream,
        output: OutputStream,
        count: Long,
        buffer: ByteArray,
        onBytesRead: (Long) -> Unit
    ) {
        var remaining = count
        while (remaining > 0) {
            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read == -1) {
                throw java.io.EOFException("Flujo TAR interrumpido prematuramente. Faltaban $remaining bytes.")
            }
            output.write(buffer, 0, read)
            remaining -= read
            onBytesRead(read.toLong())
        }
    }

    private fun skipFully(input: InputStream, count: Long, buffer: ByteArray) {
        var remaining = count
        while (remaining > 0) {
            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read == -1) break
            remaining -= read
        }
    }

    // =========================================================================
    // RESPALDO DE DATOS PRIVADOS (APPS EN MODO DEPURACIÓN VÍA RUN-AS)
    // =========================================================================

    /**
     * Extrae las bases de datos y preferencias privadas (/data/data/<paqueteId>) usando run-as.
     */
    fun exportarDatosPrivados(
        context: Context,
        adbManager: AdbConnectionManager,
        targetPort: Int,
        app: AppInstalada,
        onProgreso: (porcentaje: Int, estado: String) -> Unit
    ): BackupResult {

        if (!app.esDepurable) {
            return BackupResult.Failure("Android solo permite extraer datos privados de apps compiladas en modo depuración (debuggable).")
        }

        val directorioDestino = obtenerDirectorioDestino()

        onProgreso(5, "Conectando al demonio ADB...")
        val conexionOk = adbManager.connectDevice("127.0.0.1", targetPort)
        if (!conexionOk) {
            return BackupResult.Failure("No se pudo conectar a ADB en el puerto $targetPort.")
        }

        val nombreSanitizado = sanitizarNombreFichero(app.nombreVisible)
        val identificador = app.paqueteId
        val version = app.versionNombre.replace('/', '_')
        val ficheroSalida = File(directorioDestino, "${nombreSanitizado}_${identificador}_v${version}_datos_privados.tar.gz")

        try {
            onProgreso(25, "Extrayendo base de datos y preferencias con run-as...")

            val comandoRunAs = "exec:run-as $identificador sh -c \"cd /data/data/$identificador && tar -cf - .\""
            val canalAdb = adbManager.abrirCanalRobusto(comandoRunAs)
            val entradaAdb: InputStream = canalAdb.openInputStream()

            var totalBytesLeidos = 0L

            FileOutputStream(ficheroSalida).use { fos ->
                BufferedOutputStream(fos, BUFFER_SIZE).use { bos ->
                    GZIPOutputStream(bos).use { gzipSalida ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var leidos: Int
                        while (entradaAdb.read(buffer).also { leidos = it } != -1) {
                            gzipSalida.write(buffer, 0, leidos)
                            totalBytesLeidos += leidos
                            onProgreso(60, "Comprimiendo datos privados (${totalBytesLeidos / 1024} KB)...")
                        }
                        gzipSalida.flush()
                    }
                }
            }
            entradaAdb.close()
            canalAdb.close()

            prepararFicheroParaUsuario(context, adbManager, ficheroSalida)

            return if (ficheroSalida.exists() && ficheroSalida.length() > 0) {
                onProgreso(100, "¡Copia de datos privados finalizada!")
                BackupResult.Success(ficheroSalida.absolutePath, ficheroSalida.length())
            } else {
                ficheroSalida.delete()
                BackupResult.Failure("No se pudieron extraer datos (la app no tiene datos guardados o run-as falló).")
            }

        } catch (e: Exception) {
            Log.e(ETIQUETA_LOG, "Error al respaldar datos privados con run-as", e)
            ficheroSalida.delete()
            return BackupResult.Failure(e.localizedMessage ?: "Fallo durante la ejecución de run-as.")
        } finally {
            adbManager.disconnectDevice()
        }
    }

    private fun prepararFicheroParaUsuario(context: Context, adbManager: AdbConnectionManager, fichero: File) {
        fichero.setReadable(true, false)
        adbManager.executeCommand("chmod 664 \"${fichero.absolutePath}\"")
        MediaScannerConnection.scanFile(context, arrayOf(fichero.absolutePath), null, null)
    }

    private fun sanitizarNombreFichero(nombreOriginal: String): String {
        return nombreOriginal
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|\\s]"), "_")
            .take(30)
    }
}