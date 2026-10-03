package com.localadb.manager.backup

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import com.localadb.manager.R
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
                            if (header[0] == 0.toByte()) break

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

    // =========================================================================
    // HITO 3.4: RESPALDO DE DATOS PRIVADOS (APPS EN MODO DEPURACIÓN VÍA RUN-AS)
    // =========================================================================

    /**
     * Extrae las bases de datos y preferencias privadas (/data/data/<paqueteId>) usando run-as.
     * Lee el flujo TAR en un solo canal continuo, filtra cachés en memoria y comprime a GZIP (.tar.gz)
     * terminando de forma determinista con el marcador de fin de archivo TAR (sin cuelgues de socket).
     */
    fun exportarDatosPrivados(
        context: Context,
        adbManager: AdbConnectionManager,
        targetPort: Int,
        app: AppInstalada,
        onProgreso: (porcentaje: Int, estado: String) -> Unit
    ): BackupResult {

        if (!app.esDepurable) {
            return BackupResult.Failure(context.getString(R.string.backup_err_not_debuggable))
        }

        val directorioDestino = obtenerDirectorioDestino()

        onProgreso(5, "Conectando al demonio ADB...")
        val conexionOk = adbManager.connectDevice("127.0.0.1", targetPort)
        if (!conexionOk) {
            return BackupResult.Failure("No se pudo conectar a ADB en el puerto $targetPort. Activa la depuración Wi-Fi.")
        }

        val nombreSanitizado = sanitizarNombreFichero(app.nombreVisible)
        val identificador = app.paqueteId
        val version = app.versionNombre.replace('/', '_')
        val ficheroSalida = File(directorioDestino, "${nombreSanitizado}_${identificador}_v${version}_datos_privados.tar.gz")

        try {
            onProgreso(20, context.getString(R.string.backup_status_extracting_data, app.nombreVisible))

            // run-as se posiciona automáticamente en el directorio de la app y ejecuta tar directamente
            val comandoRunAs = "exec:run-as $identificador tar -cf - ."
            val canalAdb = adbManager.abrirCanalRobusto(comandoRunAs)
            val tarInput = canalAdb.openInputStream()

            var totalBytesLeidos = 0L

            FileOutputStream(ficheroSalida).use { fos ->
                BufferedOutputStream(fos, BUFFER_SIZE).use { bos ->
                    GZIPOutputStream(bos).use { gzipSalida ->
                        val header = ByteArray(512)
                        val buffer = ByteArray(BUFFER_SIZE)

                        while (true) {
                            if (!readFully(tarInput, header)) break

                            // 1. Verificación de error de run-as en el primer bloque
                            if (totalBytesLeidos == 0L) {
                                val primerBloqueTexto = String(header, 0, 100, Charsets.UTF_8).trim()
                                if (primerBloqueTexto.startsWith("run-as:", ignoreCase = true) ||
                                    primerBloqueTexto.contains("not debuggable", ignoreCase = true) ||
                                    primerBloqueTexto.contains("package unknown", ignoreCase = true)
                                ) {
                                    throw Exception("Fallo en run-as: $primerBloqueTexto")
                                }
                            }

                            // 2. Comprobar si hemos alcanzado el bloque de cierre del TAR (512 ceros)
                            if (header[0] == 0.toByte()) {
                                // Escribimos los dos bloques de 512 ceros que marcan el final estándar de TAR
                                val zeroBlock = ByteArray(512)
                                gzipSalida.write(zeroBlock)
                                gzipSalida.write(zeroBlock)
                                break // Salida inmediata y limpia: el archivo ha terminado por completo
                            }

                            // 3. Lectura de cabecera POSIX TAR
                            val rawName = String(header, 0, 100, Charsets.US_ASCII).trimEnd('\u0000', ' ')
                            val sizeStr = String(header, 124, 12, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
                            val fileSize = sizeStr.toLongOrNull(8) ?: 0L
                            val pad = ((512 - (fileSize % 512)) % 512).toInt()

                            // Filtramos carpetas de caché volátiles (cache/ y code_cache/)
                            val esCache = rawName == "./cache" || rawName.startsWith("./cache/") ||
                                          rawName == "./code_cache" || rawName.startsWith("./code_cache/") ||
                                          rawName == "cache" || rawName.startsWith("cache/") ||
                                          rawName == "code_cache" || rawName.startsWith("code_cache/")

                            if (esCache) {
                                if (fileSize > 0) skipFully(tarInput, fileSize, buffer)
                                if (pad > 0) skipFully(tarInput, pad.toLong(), buffer)
                            } else {
                                gzipSalida.write(header, 0, 512)
                                totalBytesLeidos += 512

                                if (fileSize > 0) {
                                    val entryName = File(rawName).name
                                    val kb = totalBytesLeidos / 1024
                                    onProgreso(
                                        50,
                                        context.getString(R.string.backup_status_compressing_data, kb) + " ($entryName)"
                                    )

                                    copyExact(tarInput, gzipSalida, fileSize, buffer) { bytes ->
                                        totalBytesLeidos += bytes
                                    }
                                }

                                if (pad > 0) {
                                    copyExact(tarInput, gzipSalida, pad.toLong(), buffer) { bytes ->
                                        totalBytesLeidos += bytes
                                    }
                                }
                            }
                        }
                        gzipSalida.finish()
                        gzipSalida.flush()
                    }
                }
            }
            tarInput.close()
            canalAdb.close()

            prepararFicheroParaUsuario(context, adbManager, ficheroSalida)

            return if (ficheroSalida.exists() && ficheroSalida.length() > 64 && totalBytesLeidos >= 512) {
                onProgreso(100, "¡Copia de datos privados finalizada!")
                Log.i(ETIQUETA_LOG, "Datos privados respaldados: ${ficheroSalida.absolutePath} (${ficheroSalida.length()} bytes)")
                BackupResult.Success(ficheroSalida.absolutePath, ficheroSalida.length())
            } else {
                ficheroSalida.delete()
                BackupResult.Failure(context.getString(R.string.backup_err_no_data))
            }

        } catch (e: Exception) {
            ficheroSalida.delete()
            Log.e(ETIQUETA_LOG, "Error al respaldar datos privados con run-as", e)
            return BackupResult.Failure(e.localizedMessage ?: "Fallo durante la ejecución de run-as.")
        } finally {
            adbManager.disconnectDevice()
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