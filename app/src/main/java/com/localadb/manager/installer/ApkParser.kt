package com.localadb.manager.installer

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Almacena los metadatos visuales y técnicos extraídos de un paquete.
 */
data class PackageDetails(
    val displayName: String,
    val identifier: String,
    val versionName: String,
    val versionCode: Long,
    val fileSizeBytes: Long,
    val appIcon: Drawable?
)

/**
 * Analizador universal de paquetes Android.
 * Extrae nombre, versión e icono real desde APK, XAPK, APKS o ZIP.
 */
object ApkParser {

    private const val TAG_LOG = "ApkParser"

    fun extractDetails(context: Context, sourceUri: Uri): PackageDetails? {
        val totalBytes = obtenerTamanoFichero(context, sourceUri)

        // Archivo temporal para inspeccionar con PackageManager
        val archivoTemporal = File(context.cacheDir, "inspeccion_temporal.tmp")
        if (!copiarStreamAFichero(context, sourceUri, archivoTemporal)) {
            return null
        }

        val packageManager = context.packageManager

        try {
            // Intento 1: ¿Es un APK simple?
            val infoDirecta = parsearManifiesto(packageManager, archivoTemporal.absolutePath)
            if (infoDirecta != null) {
                return construirDetalles(packageManager, infoDirecta, archivoTemporal.absolutePath, totalBytes)
            }

            // Intento 2: ¿Es un contenedor (XAPK/APKS/ZIP)? Extraemos el base.apk
            val baseExtraido = extraerBaseDeContenedor(archivoTemporal, context.cacheDir)
            if (baseExtraido != null) {
                try {
                    val infoBundle = parsearManifiesto(packageManager, baseExtraido.absolutePath)
                    if (infoBundle != null) {
                        return construirDetalles(packageManager, infoBundle, baseExtraido.absolutePath, totalBytes)
                    }
                } finally {
                    baseExtraido.delete()
                }
            }

            return null
        } catch (e: Exception) {
            Log.e(TAG_LOG, "Error al extraer metadatos del paquete", e)
            return null
        } finally {
            if (archivoTemporal.exists()) {
                archivoTemporal.delete()
            }
        }
    }

    private fun extraerBaseDeContenedor(ficheroZip: File, directorioSalida: File): File? {
        return try {
            val zip = ZipFile(ficheroZip)
            val entradas = zip.entries().toList()

            val entradasApk = entradas.filter { it.name.endsWith(".apk", ignoreCase = true) }
            if (entradasApk.isEmpty()) {
                zip.close()
                return null
            }

            // Seleccionar el APK principal
            val entradaPrincipal: ZipEntry = entradasApk.firstOrNull { it.name.contains("base", ignoreCase = true) }
                ?: entradasApk.maxByOrNull { it.size }
                ?: entradasApk.first()

            val ficheroSalida = File(directorioSalida, "inspeccion_base.apk")
            zip.getInputStream(entradaPrincipal).use { input ->
                FileOutputStream(ficheroSalida).use { output ->
                    input.copyTo(output)
                }
            }
            zip.close()
            ficheroSalida
        } catch (e: Exception) {
            Log.w(TAG_LOG, "No se pudo extraer base del contenedor: ${e.message}")
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun parsearManifiesto(pm: PackageManager, rutaFichero: String): PackageInfo? {
        return try {
            val flags = PackageManager.GET_META_DATA
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(rutaFichero, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                pm.getPackageArchiveInfo(rutaFichero, flags)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Construye el modelo de datos asignando sourceDir para cargar el icono real.
     */
    private fun construirDetalles(
        pm: PackageManager,
        info: PackageInfo,
        rutaArchivoReal: String,
        tamanoBytes: Long
    ): PackageDetails {
        val appInfo = info.applicationInfo

        // Clave: sourceDir debe apuntar al archivo físico para cargar los recursos gráficos del APK
        appInfo?.sourceDir = rutaArchivoReal
        appInfo?.publicSourceDir = rutaArchivoReal

        val nombreApp = appInfo?.loadLabel(pm)?.toString() ?: info.packageName
        val icono = appInfo?.loadIcon(pm)

        val codigoVersion: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

        return PackageDetails(
            displayName = nombreApp,
            identifier = info.packageName,
            versionName = info.versionName ?: "N/D",
            versionCode = codigoVersion,
            fileSizeBytes = tamanoBytes,
            appIcon = icono
        )
    }

    private fun obtenerTamanoFichero(context: Context, uri: Uri): Long {
        var tamano: Long = 0
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

    private fun copiarStreamAFichero(context: Context, origen: Uri, destino: File): Boolean {
        return try {
            context.contentResolver.openInputStream(origen)?.use { input ->
                FileOutputStream(destino).use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG_LOG, "Error copiando stream de URI", e)
            false
        }
    }
}