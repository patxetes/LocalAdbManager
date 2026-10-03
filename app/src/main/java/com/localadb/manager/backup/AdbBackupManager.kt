package com.localadb.manager.backup

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import com.localadb.manager.R
import com.localadb.manager.adb.AdbConnectionManager
import org.json.JSONObject
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
 * Genera copias individuales (.apk / .apks) o contenedores unificados .lam (APK + Datos + Manifiesto).
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
    // CONTENEDOR UNIFICADO LOCAL ADB MANAGER (.lam = APK + DATOS + MANIFEST)
    // =========================================================================

    /**
     * Empaqueta el APK/Splits, las bases de datos y preferencias (/data/data) y el manifest.json
     * en un único contenedor .lam.
     */
    fun exportarPaqueteCompletoLam(
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
        val ficheroSalida = File(directorioDestino, "${nombreSanitizado}_${identificador}_v${version}_completo.lam")

        try {
            onProgreso(10, context.getString(R.string.backup_status_creating_lam, app.nombreVisible))

            FileOutputStream(ficheroSalida).use { fos ->
                BufferedOutputStream(fos, BUFFER_SIZE).use { bos ->
                    ZipOutputStream(bos).use { zipLam ->
                        zipLam.setLevel(Deflater.BEST_SPEED)

                        // 1. Escribir manifest.json en la raíz del .lam
                        val manifestJson = JSONObject().apply {
                            put("formatVersion", 1)
                            put("appName", app.nombreVisible)
                            put("packageName", app.paqueteId)
                            put("versionName", app.versionNombre)
                            put("versionCode", app.versionCodigo)
                            put("isSplit", app.rutasSplits.isNotEmpty())
                            put("hasPrivateData", true)
                            put("timestamp", System.currentTimeMillis())
                        }.toString(2)

                        zipLam.putNextEntry(ZipEntry("manifest.json"))
                        zipLam.write(manifestJson.toByteArray(Charsets.UTF_8))
                        zipLam.closeEntry()

                        // 2. Empaquetar binarios APK dentro del directorio apks/ del .lam
                        onProgreso(25, context.getString(R.string.backup_status_extracting, app.nombreVisible))
                        empaquetarApksEnZip(adbManager, app.rutaBaseApk, app.rutasSplits, zipLam, "apks/") { pct, status ->
                            onProgreso(25 + (pct * 35 / 100), status)
                        }

                        // 3. Empaquetar datos privados con run-as en data.tar.gz dentro del .lam
                        onProgreso(65, context.getString(R.string.backup_status_extracting_data, app.nombreVisible))
                        zipLam.putNextEntry(ZipEntry("data.tar.gz"))
                        empaquetarDatosPrivadosEnGzip(context, adbManager, app.paqueteId, zipLam) { kb ->
                            onProgreso(65 + minOf(30, (kb / 50).toInt()), context.getString(R.string.backup_status_compressing_data, kb))
                        }
                        zipLam.closeEntry()

                        zipLam.finish()
                        zipLam.flush()
                    }
                }
            }

            prepararFicheroParaUsuario(context, adbManager, ficheroSalida)
            Log.i(ETIQUETA_LOG, "Paquete .lam creado con éxito: ${ficheroSalida.absolutePath} (${ficheroSalida.length()} bytes)")

            return if (ficheroSalida.exists() && ficheroSalida.length() > 1024) {
                onProgreso(100, "¡Copia completa finalizada!")
                BackupResult.Success(ficheroSalida.absolutePath, ficheroSalida.length())
            } else {
                ficheroSalida.delete()
                BackupResult.Failure("Fallo al generar el contenedor .lam.")
            }

        } catch (e: Exception) {
            ficheroSalida.delete()
            Log.e(ETIQUETA_LOG, "Error al exportar paquete .lam", e)
            return BackupResult.Failure(e.localizedMessage ?: "Error al crear el paquete .lam.")
        } finally {
            adbManager.disconnectDevice()
        }
    }

    // =========================================================================
    // RESPALDO DE BINARIOS (APK / BUNDLE APKS INDEPENDIENTES)
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
                val ficheroSalida = File(directorioDestino, "${nombreSanitizado}_${identificador}_v${version}.apk")
                exportarApkMonolitico(context, adbManager, app.rutaBaseApk, ficheroSalida, onProgreso)
            } else {
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

    private fun exportarSplitsApks(
        context: Context,
        adbManager: AdbConnectionManager,
        rutaBase: String,
        rutasSplits: List<String>,
        ficheroDestino: File,
        onProgreso: (porcentaje: Int, estado: String) -> Unit
    ): BackupResult {
        onProgreso(15, "Iniciando empaquetado de fragmentos...")

        try {
            FileOutputStream(ficheroDestino).use { fos ->
                BufferedOutputStream(fos, BUFFER_SIZE).use { bos ->
                    ZipOutputStream(bos).use { zipSalida ->
                        zipSalida.setLevel(Deflater.BEST_SPEED)
                        empaquetarApksEnZip(adbManager, rutaBase, rutasSplits, zipSalida, "", onProgreso)
                        zipSalida.finish()
                        zipSalida.flush()
                    }
                }
            }

            prepararFicheroParaUsuario(context, adbManager, ficheroDestino)
            Log.i(ETIQUETA_LOG, "Bundle exportado con éxito: ${ficheroDestino.absolutePath} (${ficheroDestino.length()} bytes)")

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
    // RESPALDO DE DATOS PRIVADOS INDEPENDIENTES (.tar.gz)
    // =========================================================================

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

            FileOutputStream(ficheroSalida).use { fos ->
                BufferedOutputStream(fos, BUFFER_SIZE).use { bos ->
                    empaquetarDatosPrivadosEnGzip(context, adbManager, app.paqueteId, bos) { kb ->
                        onProgreso(
                            50,
                            context.getString(R.string.backup_status_compressing_data, kb)
                        )
                    }
                    bos.flush()
                }
            }

            prepararFicheroParaUsuario(context, adbManager, ficheroSalida)

            return if (ficheroSalida.exists() && ficheroSalida.length() > 64) {
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

    // =========================================================================
    // RUTINAS DE EMPAQUETADO ATÓMICO (REUTILIZABLES PARA .APKS Y .LAM)
    // =========================================================================

    private fun empaquetarApksEnZip(
        adbManager: AdbConnectionManager,
        rutaBase: String,
        rutasSplits: List<String>,
        zipSalida: ZipOutputStream,
        prefijoEntrada: String = "",
        onProgreso: (porcentaje: Int, estado: String) -> Unit
    ) {
        val appDir = File(rutaBase).parentFile?.absolutePath
            ?: throw Exception("No se pudo localizar el directorio de la aplicación.")

        val totalPartes = 1 + rutasSplits.size
        var partesProcesadas = 0

        val baseName = File(rutaBase).name
        val splitNames = rutasSplits.map { File(it).name }
        val fileArgs = (listOf(baseName) + splitNames).joinToString(" ") { "\"$it\"" }
        val comandoTar = "exec:sh -c \"cd '$appDir' && tar -cf - $fileArgs\""

        val canal = adbManager.abrirCanalRobusto(comandoTar)
        val tarInput = canal.openInputStream()

        val header = ByteArray(512)
        val buffer = ByteArray(BUFFER_SIZE)

        try {
            while (true) {
                if (!readFully(tarInput, header)) break
                if (header[0] == 0.toByte()) break

                val rawName = String(header, 0, 100, Charsets.US_ASCII).trimEnd('\u0000', ' ')
                val sizeStr = String(header, 124, 12, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
                val fileSize = sizeStr.toLongOrNull(8) ?: 0L

                if (rawName.endsWith(".apk", ignoreCase = true) && fileSize > 0) {
                    partesProcesadas++
                    val entryName = prefijoEntrada + File(rawName).name
                    val pct = (partesProcesadas * 100) / totalPartes
                    onProgreso(pct, "Guardando ($partesProcesadas de $totalPartes): ${File(rawName).name}...")

                    zipSalida.putNextEntry(ZipEntry(entryName))
                    copyExact(tarInput, zipSalida, fileSize, buffer) {}
                    zipSalida.closeEntry()

                    val pad = ((512 - (fileSize % 512)) % 512).toInt()
                    if (pad > 0) skipFully(tarInput, pad.toLong(), buffer)
                } else if (fileSize > 0) {
                    val pad = ((512 - (fileSize % 512)) % 512).toInt()
                    skipFully(tarInput, fileSize + pad, buffer)
                }
            }
        } finally {
            tarInput.close()
            canal.close()
        }
    }

    private fun empaquetarDatosPrivadosEnGzip(
        context: Context,
        adbManager: AdbConnectionManager,
        identificador: String,
        destinoStream: OutputStream,
        onProgresoKb: (Long) -> Unit
    ) {
        val comandoRunAs = "exec:run-as $identificador tar -cf - ."
        val canalAdb = adbManager.abrirCanalRobusto(comandoRunAs)
        val tarInput = canalAdb.openInputStream()

        var totalBytesLeidos = 0L
        val gzipSalida = GZIPOutputStream(destinoStream)
        val header = ByteArray(512)
        val buffer = ByteArray(BUFFER_SIZE)

        try {
            while (true) {
                if (!readFully(tarInput, header)) break

                if (totalBytesLeidos == 0L) {
                    val primerBloque = String(header, 0, 100, Charsets.UTF_8).trim()
                    if (primerBloque.startsWith("run-as:", ignoreCase = true) ||
                        primerBloque.contains("not debuggable", ignoreCase = true)
                    ) {
                        throw Exception("Fallo en run-as: $primerBloque")
                    }
                }

                if (header[0] == 0.toByte()) {
                    val zeroBlock = ByteArray(512)
                    gzipSalida.write(zeroBlock)
                    gzipSalida.write(zeroBlock)
                    break
                }

                val rawName = String(header, 0, 100, Charsets.US_ASCII).trimEnd('\u0000', ' ')
                val sizeStr = String(header, 124, 12, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
                val fileSize = sizeStr.toLongOrNull(8) ?: 0L
                val pad = ((512 - (fileSize % 512)) % 512).toInt()

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
                        copyExact(tarInput, gzipSalida, fileSize, buffer) { bytes ->
                            totalBytesLeidos += bytes
                        }
                        onProgresoKb(totalBytesLeidos / 1024)
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
        } finally {
            tarInput.close()
            canalAdb.close()
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