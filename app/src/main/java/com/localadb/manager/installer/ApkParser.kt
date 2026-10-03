package com.localadb.manager.installer

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/**
 * Estructura de datos que almacena la información descriptiva de un APK.
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
 * Utilidad encargada del análisis sintáctico de paquetes Android.
 */
object ApkParser {

    /**
     * Punto de entrada: Extrae los metadatos esenciales a partir del Uri del archivo.
     */
    fun extractDetails(context: Context, sourceUri: Uri): PackageDetails? {
        val totalBytes = resolveFileSize(context, sourceUri)
        val temporaryFile = createTemporaryCopy(context, sourceUri) ?: return null

        try {
            val packageManager = context.packageManager
            val packageInfo = inspectArchive(packageManager, temporaryFile.absolutePath) ?: return null

            return buildDetailsObject(packageManager, packageInfo, totalBytes)
        } finally {
            // Garantizar la liberación de espacio borrando el temporal
            if (temporaryFile.exists()) {
                temporaryFile.delete()
            }
        }
    }

    /**
     * Consulta el tamaño exacto del archivo al proveedor de contenidos de Android.
     */
    private fun resolveFileSize(context: Context, uri: Uri): Long {
        var size: Long = 0
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1) {
                    size = it.getLong(sizeIndex)
                }
            }
        }
        return size
    }

    /**
     * Vuelca el stream del Uri a un fichero temporal en caché para permitir la inspección.
     */
    private fun createTemporaryCopy(context: Context, uri: Uri): File? {
        return try {
            val targetFile = File(context.cacheDir, "inspection_cache.apk")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            targetFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Utiliza el PackageInstaller de Android para parsear el AndroidManifest binario interno.
     */
    @Suppress("DEPRECATION")
    private fun inspectArchive(pm: PackageManager, archivePath: String): PackageInfo? {
        return try {
            val flags = PackageManager.GET_META_DATA
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(archivePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                pm.getPackageArchiveInfo(archivePath, flags)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Construye y mapea los datos obtenidos en nuestra estructura PackageDetails.
     */
    private fun buildDetailsObject(
        pm: PackageManager,
        info: PackageInfo,
        fileSize: Long
    ): PackageDetails {
        val appInfo = info.applicationInfo
        appInfo?.sourceDir = null
        appInfo?.publicSourceDir = null

        val name = appInfo?.loadLabel(pm)?.toString() ?: info.packageName
        val icon = appInfo?.loadIcon(pm)

        val code: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

        return PackageDetails(
            displayName = name,
            identifier = info.packageName,
            versionName = info.versionName ?: "N/A",
            versionCode = code,
            fileSizeBytes = fileSize,
            appIcon = icon
        )
    }
}