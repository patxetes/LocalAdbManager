package com.localadb.manager.backup

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import com.localadb.manager.R
import com.localadb.manager.adb.AdbConnectionManager
import com.localadb.manager.installer.AdbPackageInstaller
import com.localadb.manager.installer.BundleSplitFilter
import com.localadb.manager.installer.InstallResult
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

sealed class RestoreResult {
    data class Success(val paqueteId: String, val tamanoBytes: Long) : RestoreResult()
    data class Failure(val motivo: String) : RestoreResult()
}

data class ManifestLam(
    val formatVersion: Int,
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val isSplit: Boolean,
    val hasPrivateData: Boolean,
    val timestamp: Long,
    val artifacts: List<ArtifactRecord> = emptyList()
)

/**
 * Motor de restauración completa de aplicaciones.
 * Orquesta la instalación de binarios y la inyección de datos privados desde archivos .lam, .apks o .tar.gz
 * con verificación criptográfica SHA-256 y blindaje contra ataques de Path Traversal (Tar-Slip).
 */
object AdbRestoreManager {

    private const val ETIQUETA_LOG = "AdbRestoreManager"
    private const val BUFFER_SIZE = 64 * 1024 // 64 KB

    /**
     * Lee y analiza el archivo manifest.json contenido en un paquete .lam, incluyendo la lista de artefactos SHA-256.
     */
    fun leerManifestDeLam(context: Context, uri: Uri): ManifestLam? {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "manifest.json" || entry.name.endsWith("/manifest.json")) {
                        val jsonString = zip.readBytes().toString(Charsets.UTF_8)
                        val json = JSONObject(jsonString)

                        val artifactsList = mutableListOf<ArtifactRecord>()
                        val artifactsArray = json.optJSONArray("artifacts")
                        if (artifactsArray != null) {
                            for (i in 0 until artifactsArray.length()) {
                                val item = artifactsArray.getJSONObject(i)
                                artifactsList.add(
                                    ArtifactRecord(
                                        path = item.optString("path", ""),
                                        sha256 = item.optString("sha256", ""),
                                        sizeBytes = item.optLong("sizeBytes", 0L)
                                    )
                                )
                            }
                        }

                        return ManifestLam(
                            formatVersion = json.optInt("formatVersion", 1),
                            appName = json.optString("appName", "Aplicación"),
                            packageName = json.optString("packageName", ""),
                            versionName = json.optString("versionName", "1.0"),
                            versionCode = json.optLong("versionCode", 1L),
                            isSplit = json.optBoolean("isSplit", false),
                            hasPrivateData = json.optBoolean("hasPrivateData", false),
                            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                            artifacts = artifactsList
                        )
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.e(ETIQUETA_LOG, "Error al leer manifest.json del .lam", e)
        }
        return null
    }

    /**
     * Orquestador de restauración para contenedores .lam.
     * Permite instalar solo el APK o realizar la restauración completa (APK + Datos privados con verificación SHA-256).
     */
    fun restaurarPaqueteLam(
        context: Context,
        adbManager: AdbConnectionManager,
        targetPort: Int,
        uri: Uri,
        soloApk: Boolean = false,
        onProgreso: (porcentaje: Int, estado: String) -> Unit
    ): RestoreResult {

        onProgreso(5, context.getString(R.string.restore_status_inspecting_lam))
        val manifest = leerManifestDeLam(context, uri)
            ?: return RestoreResult.Failure(context.getString(R.string.restore_err_invalid_lam))

        val paqueteId = manifest.packageName
        val nombreApp = manifest.appName

        // Protección: evitar auto-restaurarse a sí misma para que am force-stop no mate este proceso
        if (paqueteId == context.packageName && !soloApk) {
            return RestoreResult.Failure(context.getString(R.string.restore_err_self_restore))
        }

        // 1. Paso 1/2: Instalar los binarios APK contenidos en apks/ mediante streaming ADB
        val etiquetaInstalacion = if (soloApk) {
            context.getString(R.string.install_status_creating_session)
        } else {
            context.getString(R.string.restore_status_installing_apk, nombreApp)
        }
        onProgreso(15, etiquetaInstalacion)

        val totalBytes = resolverTamanoArchivo(context, uri)
        val analysis = BundleSplitFilter.inspectAndFilter(context, uri, totalBytes)

        if (analysis.compatibleSplits.isEmpty()) {
            return RestoreResult.Failure("No se encontraron binarios APK válidos dentro del paquete .lam.")
        }

        val resultadoInstall = AdbPackageInstaller.install(
            context = context,
            adbManager = adbManager,
            targetPort = targetPort,
            apkUri = uri,
            bundleAnalysis = analysis
        ) { pct, status ->
            val pesoProgreso = if (soloApk || !manifest.hasPrivateData) 85 else 40
            onProgreso(15 + (pct * pesoProgreso / 100), status)
        }

        if (resultadoInstall is InstallResult.Failure) {
            return RestoreResult.Failure("Fallo al instalar binarios: ${resultadoInstall.reason}")
        }

        // 2. Paso 2/2: Inyectar datos privados con verificación SHA-256 y protección Tar-Slip
        if (!soloApk && manifest.hasPrivateData) {
            onProgreso(60, context.getString(R.string.restore_status_injecting, paqueteId))
            val expectedSha = manifest.artifacts.find { it.path == "data.tar.gz" }?.sha256

            val resDatos = inyectarDatosPrivadosDesdeLam(context, adbManager, targetPort, uri, paqueteId, expectedSha) { pct, status ->
                onProgreso(60 + (pct * 35 / 100), "Paso 2/2: $status")
            }

            if (resDatos is RestoreResult.Failure) {
                return RestoreResult.Failure("Binarios instalados con éxito, pero falló la restauración de datos: ${resDatos.motivo}")
            }
        }

        onProgreso(100, "¡Restauración finalizada con éxito!")
        return RestoreResult.Success(paqueteId, totalBytes)
    }

    /**
     * Extrae data.tar.gz de un .lam, verifica su SHA-256, filtra rutas inseguras (Tar-Slip)
     * e inyecta los datos por streaming en /data/data/<paqueteId> vía run-as.
     */
    private fun inyectarDatosPrivadosDesdeLam(
        context: Context,
        adbManager: AdbConnectionManager,
        targetPort: Int,
        uri: Uri,
        paqueteId: String,
        expectedSha256: String?,
        onProgreso: (porcentaje: Int, estado: String) -> Unit
    ): RestoreResult {
        val pm = context.packageManager

        val appInfo: ApplicationInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(paqueteId, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(paqueteId, 0)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return RestoreResult.Failure(context.getString(R.string.restore_err_pkg_not_installed, paqueteId))
        }

        val esDebug = (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!esDebug) {
            return RestoreResult.Failure(context.getString(R.string.restore_err_not_debuggable, paqueteId))
        }

        val conexionOk = adbManager.connectDevice("127.0.0.1", targetPort)
        if (!conexionOk) {
            return RestoreResult.Failure("Error al conectar a ADB en el puerto $targetPort.")
        }

        try {
            val comandoRunAs = "exec:run-as $paqueteId tar -xf -"
            val canal = adbManager.abrirCanalRobusto(comandoRunAs)
            val canalOut: OutputStream = canal.openOutputStream()

            val rawInput = context.contentResolver.openInputStream(uri)
                ?: return RestoreResult.Failure("No se pudo abrir el archivo .lam.")

            var totalBytesInyectados = 0L

            ZipInputStream(rawInput).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "data.tar.gz" || entry.name.endsWith("/data.tar.gz")) {
                        val md = MessageDigest.getInstance("SHA-256")
                        val dis = DigestInputStream(zip, md)

                        GZIPInputStream(dis).use { gzipInput ->
                            val header = ByteArray(512)
                            val buffer = ByteArray(BUFFER_SIZE)

                            while (true) {
                                if (!readFully(gzipInput, header)) break
                                if (header[0] == 0.toByte()) break

                                val rawName = String(header, 0, 100, Charsets.US_ASCII).trimEnd('\u0000', ' ')

                                // Blindaje contra Tar-Slip (Path Traversal)
                                if (rawName.startsWith("/") || rawName.contains("../") || rawName.contains("/..") || rawName == "..") {
                                    throw Exception(context.getString(R.string.restore_err_tar_slip, rawName))
                                }

                                val sizeStr = String(header, 124, 12, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
                                val fileSize = sizeStr.toLongOrNull(8) ?: 0L
                                val pad = ((512 - (fileSize % 512)) % 512).toInt()

                                canalOut.write(header, 0, 512)
                                totalBytesInyectados += 512

                                if (fileSize > 0) {
                                    copyExact(gzipInput, canalOut, fileSize, buffer) { bytes ->
                                        totalBytesInyectados += bytes
                                        val kb = totalBytesInyectados / 1024
                                        onProgreso(50, context.getString(R.string.restore_status_injecting, paqueteId) + " (${kb} KB)")
                                    }
                                }

                                if (pad > 0) {
                                    copyExact(gzipInput, canalOut, pad.toLong(), buffer) { bytes ->
                                        totalBytesInyectados += bytes
                                    }
                                }
                            }
                            canalOut.flush()
                        }

                        // Validación criptográfica SHA-256
                        if (!expectedSha256.isNullOrBlank()) {
                            val computedHash = md.digest().joinToString("") { "%02x".format(it) }
                            if (!computedHash.equals(expectedSha256, ignoreCase = true)) {
                                throw Exception(context.getString(R.string.restore_err_checksum_mismatch, "data.tar.gz"))
                            }
                        }
                        break
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            canalOut.close()
            canal.close()

            // Detener el proceso para que la app objetivo recargue su estado limpio
            onProgreso(95, context.getString(R.string.restore_status_restarting))
            adbManager.executeCommand("am force-stop $paqueteId")

            return if (totalBytesInyectados > 0) {
                RestoreResult.Success(paqueteId, totalBytesInyectados)
            } else {
                RestoreResult.Failure("El archivo .lam no contenía una copia de datos privados válida.")
            }
        } catch (e: Exception) {
            Log.e(ETIQUETA_LOG, "Error inyectando datos privados desde .lam", e)
            return RestoreResult.Failure(e.localizedMessage ?: "Error inesperado al inyectar datos.")
        } finally {
            adbManager.disconnectDevice()
        }
    }

    // =========================================================================
    // RESTAURACIÓN DE ARCHIVOS INDEPENDIENTES (.tar.gz)
    // =========================================================================

    fun deducirPaqueteIdDeNombre(nombreArchivo: String): String? {
        val regex = Regex("_([a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+)_v.*_datos_privados\\.tar\\.gz", RegexOption.IGNORE_CASE)
        val match = regex.find(nombreArchivo)
        return match?.groupValues?.get(1)
    }

    fun resolverNombreArchivo(context: Context, uri: Uri): String {
        var nombre = "archivo_backup"
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    nombre = it.getString(index) ?: nombre
                }
            }
        }
        return nombre
    }

    fun resolverTamanoArchivo(context: Context, uri: Uri): Long {
        var tamano = 0L
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.SIZE)
                if (index != -1) {
                    tamano = it.getLong(index)
                }
            }
        }
        return tamano
    }

    fun restaurarDatosPrivados(
        context: Context,
        adbManager: AdbConnectionManager,
        targetPort: Int,
        uri: Uri,
        paqueteIdEspecificado: String? = null,
        onProgreso: (porcentaje: Int, estado: String) -> Unit
    ): RestoreResult {

        val nombreArchivo = resolverNombreArchivo(context, uri)
        val paqueteId = paqueteIdEspecificado ?: deducirPaqueteIdDeNombre(nombreArchivo)
            ?: return RestoreResult.Failure("No se pudo identificar el identificador de paquete a partir de: $nombreArchivo")

        if (paqueteId == context.packageName) {
            return RestoreResult.Failure(context.getString(R.string.restore_err_self_restore))
        }

        val pm = context.packageManager

        val appInfo: ApplicationInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(paqueteId, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(paqueteId, 0)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return RestoreResult.Failure(context.getString(R.string.restore_err_pkg_not_installed, paqueteId))
        }

        val esDebug = (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!esDebug) {
            return RestoreResult.Failure(context.getString(R.string.restore_err_not_debuggable, paqueteId))
        }

        onProgreso(5, "Conectando al demonio ADB...")
        val conexionOk = adbManager.connectDevice("127.0.0.1", targetPort)
        if (!conexionOk) {
            return RestoreResult.Failure("No se pudo conectar a ADB en el puerto $targetPort.")
        }

        try {
            onProgreso(15, context.getString(R.string.restore_status_preparing, paqueteId))

            val comandoRunAs = "exec:run-as $paqueteId tar -xf -"
            val canal = adbManager.abrirCanalRobusto(comandoRunAs)
            val canalOut: OutputStream = canal.openOutputStream()

            val rawInput: InputStream = context.contentResolver.openInputStream(uri)
                ?: return RestoreResult.Failure("No se pudo abrir el archivo de copia.")

            var totalBytesInyectados = 0L

            onProgreso(30, context.getString(R.string.restore_status_injecting, paqueteId))

            rawInput.use { input ->
                GZIPInputStream(input).use { gzipInput ->
                    val header = ByteArray(512)
                    val buffer = ByteArray(BUFFER_SIZE)

                    while (true) {
                        if (!readFully(gzipInput, header)) break
                        if (header[0] == 0.toByte()) break

                        val rawName = String(header, 0, 100, Charsets.US_ASCII).trimEnd('\u0000', ' ')

                        // Blindaje contra Tar-Slip (Path Traversal)
                        if (rawName.startsWith("/") || rawName.contains("../") || rawName.contains("/..") || rawName == "..") {
                            throw Exception(context.getString(R.string.restore_err_tar_slip, rawName))
                        }

                        val sizeStr = String(header, 124, 12, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
                        val fileSize = sizeStr.toLongOrNull(8) ?: 0L
                        val pad = ((512 - (fileSize % 512)) % 512).toInt()

                        canalOut.write(header, 0, 512)
                        totalBytesInyectados += 512

                        if (fileSize > 0) {
                            copyExact(gzipInput, canalOut, fileSize, buffer) { bytes ->
                                totalBytesInyectados += bytes
                                val kb = totalBytesInyectados / 1024
                                onProgreso(50, context.getString(R.string.restore_status_injecting, paqueteId) + " (${kb} KB)")
                            }
                        }

                        if (pad > 0) {
                            copyExact(gzipInput, canalOut, pad.toLong(), buffer) { bytes ->
                                totalBytesInyectados += bytes
                            }
                        }
                    }
                    canalOut.flush()
                }
            }
            canalOut.close()
            canal.close()

            onProgreso(90, context.getString(R.string.restore_status_restarting))
            adbManager.executeCommand("am force-stop $paqueteId")

            return if (totalBytesInyectados > 0) {
                onProgreso(100, "¡Restauración finalizada!")
                RestoreResult.Success(paqueteId, totalBytesInyectados)
            } else {
                RestoreResult.Failure("El archivo de copia no contenía datos válidos.")
            }

        } catch (e: ZipException) {
            Log.e(ETIQUETA_LOG, "Archivo .tar.gz corrupto o inválido", e)
            return RestoreResult.Failure("El archivo no es un archivo .tar.gz válido.")
        } catch (e: Exception) {
            Log.e(ETIQUETA_LOG, "Error durante la restauración con run-as", e)
            return RestoreResult.Failure(e.localizedMessage ?: "Error inesperado durante la restauración.")
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
}