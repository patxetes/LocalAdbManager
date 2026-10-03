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
    val timestamp: Long
)

/**
 * Motor de restauración completa de aplicaciones.
 * Orquesta la instalación de binarios y la inyección de datos privados desde archivos .lam, .apks o .tar.gz.
 */
object AdbRestoreManager {

    private const val ETIQUETA_LOG = "AdbRestoreManager"
    private const val BUFFER_SIZE = 64 * 1024 // 64 KB

    /**
     * Lee y analiza el archivo manifest.json contenido en un paquete .lam.
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
                        return ManifestLam(
                            formatVersion = json.optInt("formatVersion", 1),
                            appName = json.optString("appName", "Aplicación"),
                            packageName = json.optString("packageName", ""),
                            versionName = json.optString("versionName", "1.0"),
                            versionCode = json.optLong("versionCode", 1L),
                            isSplit = json.optBoolean("isSplit", false),
                            hasPrivateData = json.optBoolean("hasPrivateData", false),
                            timestamp = json.optLong("timestamp", System.currentTimeMillis())
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
     * Orquestador de restauración completa para contenedores .lam (Paso 1: APKs -> Paso 2: Datos run-as).
     */
    fun restaurarPaqueteLam(
        context: Context,
        adbManager: AdbConnectionManager,
        targetPort: Int,
        uri: Uri,
        onProgreso: (porcentaje: Int, estado: String) -> Unit
    ): RestoreResult {

        onProgreso(5, context.getString(R.string.restore_status_inspecting_lam))
        val manifest = leerManifestDeLam(context, uri)
            ?: return RestoreResult.Failure(context.getString(R.string.restore_err_invalid_lam))

        val paqueteId = manifest.packageName
        val nombreApp = manifest.appName

        // Protección: evitar auto-restaurarse a sí misma para que am force-stop no mate este proceso
        if (paqueteId == context.packageName) {
            return RestoreResult.Failure(context.getString(R.string.restore_err_self_restore))
        }

        // 1. Paso 1/2: Instalar los binarios APK contenidos en apks/ mediante streaming ADB
        onProgreso(15, context.getString(R.string.restore_status_installing_apk, nombreApp))
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
            onProgreso(15 + (pct * 35 / 100), "Paso 1/2: $status")
        }

        if (resultadoInstall is InstallResult.Failure) {
            return RestoreResult.Failure("Fallo al instalar binarios: ${resultadoInstall.reason}")
        }

        // 2. Paso 2/2: Inyectar datos privados si están incluidos en el .lam
        if (manifest.hasPrivateData) {
            onProgreso(55, context.getString(R.string.restore_status_injecting, paqueteId))
            val resDatos = inyectarDatosPrivadosDesdeLam(context, adbManager, targetPort, uri, paqueteId) { pct, status ->
                onProgreso(55 + (pct * 40 / 100), "Paso 2/2: $status")
            }

            if (resDatos is RestoreResult.Failure) {
                return RestoreResult.Failure("Binarios instalados con éxito, pero falló la restauración de datos: ${resDatos.motivo}")
            }
        }

        onProgreso(100, "¡Restauración completa finalizada!")
        return RestoreResult.Success(paqueteId, totalBytes)
    }

    /**
     * Extrae data.tar.gz de un .lam y lo inyecta por streaming en /data/data/<paqueteId> vía run-as.
     */
    private fun inyectarDatosPrivadosDesdeLam(
        context: Context,
        adbManager: AdbConnectionManager,
        targetPort: Int,
        uri: Uri,
        paqueteId: String,
        onProgreso: (porcentaje: Int, estado: String) -> Unit
    ): RestoreResult {
        val pm = context.packageManager

        // Verificar que la app recién instalada es depurable
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
                        GZIPInputStream(zip).use { gzipInput ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var leidos: Int
                            while (gzipInput.read(buffer).also { leidos = it } != -1) {
                                canalOut.write(buffer, 0, leidos)
                                totalBytesInyectados += leidos
                                val kb = totalBytesInyectados / 1024
                                onProgreso(50, context.getString(R.string.restore_status_injecting, paqueteId) + " (${kb} KB)")
                            }
                            canalOut.flush()
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
                    val buffer = ByteArray(BUFFER_SIZE)
                    var leidos: Int
                    while (gzipInput.read(buffer).also { leidos = it } != -1) {
                        canalOut.write(buffer, 0, leidos)
                        totalBytesInyectados += leidos
                        val kb = totalBytesInyectados / 1024
                        onProgreso(
                            50,
                            context.getString(R.string.restore_status_injecting, paqueteId) + " (${kb} KB)"
                        )
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
}