package com.localadb.manager.installer

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Representa una parte individual (.apk) dentro de un paquete dividido.
 */
data class SplitPackageEntry(
    val fileName: String,
    val sizeBytes: Long
)

/**
 * Resultado estructurado del análisis del contenedor.
 */
data class BundleAnalysisResult(
    val isBundle: Boolean,
    val compatibleSplits: List<SplitPackageEntry>,
    val totalRequiredBytes: Long
)

/**
 * Analizador sintáctico de contenedores (APKS, XAPK, ZIP) y filtro de arquitecturas hardware.
 */
object BundleSplitFilter {

    private const val TAG = "BundleSplitFilter"

    // Familias de procesadores reconocidas por Android
    private val KNOWN_ABIS = listOf(
        "arm64-v8a", "arm64_v8a",
        "armeabi-v7a", "armeabi_v7a", "armeabi",
        "x86_64", "x86",
        "mips64", "mips",
        "riscv64"
    )

    /**
     * Inspecciona el archivo seleccionado. Si es un ZIP/Bundle, filtra las partes compatibles.
     */
    fun inspectAndFilter(context: Context, fileUri: Uri, totalFileSize: Long): BundleAnalysisResult {
        val splitsFound = mutableListOf<SplitPackageEntry>()
        val deviceSupportedAbis = Build.SUPPORTED_ABIS
        var isZipBundle = false

        try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(fileUri)
            if (inputStream != null) {
                val zipStream = ZipInputStream(inputStream)
                var currentEntry: ZipEntry?

                while (zipStream.nextEntry.also { currentEntry = it } != null) {
                    val entry = currentEntry ?: continue
                    val entryName = entry.name

                    // Solo procesamos archivos ejecutables .apk
                    if (entryName.endsWith(".apk", ignoreCase = true)) {
                        isZipBundle = true

                        if (isSplitCompatibleWithDevice(entryName, deviceSupportedAbis)) {
                            // Si el ZIP no declara el tamaño en la cabecera (-1), contamos los bytes descomprimidos
                            var actualSize = entry.size
                            if (actualSize <= 0) {
                                actualSize = countBytesInEntry(zipStream)
                            }

                            Log.d(TAG, "Split compatible detectado: $entryName (${actualSize / 1024} KB)")
                            splitsFound.add(SplitPackageEntry(entryName, actualSize))
                        } else {
                            Log.d(TAG, "Split descartado por incompatibilidad de ABI: $entryName")
                        }
                    }
                    zipStream.closeEntry()
                }
                zipStream.close()
                inputStream.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "El archivo no es un ZIP estándar o no se pudo leer: ${e.message}")
            isZipBundle = false
        }

        // Caso 1: Contenedor con múltiples partes compatibles
        if (isZipBundle && splitsFound.isNotEmpty()) {
            val totalBytes = splitsFound.sumOf { it.sizeBytes }
            return BundleAnalysisResult(
                isBundle = true,
                compatibleSplits = splitsFound,
                totalRequiredBytes = if (totalBytes > 0) totalBytes else totalFileSize
            )
        }

        // Caso 2: APK individual clásico
        return BundleAnalysisResult(
            isBundle = false,
            compatibleSplits = listOf(SplitPackageEntry("base.apk", totalFileSize)),
            totalRequiredBytes = totalFileSize
        )
    }

    /**
     * Cuenta los bytes de una entrada cuando la cabecera del ZIP tiene tamaño indefinido (-1).
     */
    private fun countBytesInEntry(zipStream: ZipInputStream): Long {
        var count = 0L
        val buffer = ByteArray(16 * 1024)
        var bytesRead: Int
        while (zipStream.read(buffer).also { bytesRead = it } != -1) {
            count += bytesRead
        }
        return count
    }

    /**
     * Comprueba si el fragmento corresponde a la arquitectura del dispositivo o es neutro (idioma/recursos).
     */
    private fun isSplitCompatibleWithDevice(fileName: String, supportedAbis: Array<String>): Boolean {
        val lowerName = fileName.lowercase()

        // Buscar si el nombre menciona alguna arquitectura conocida
        val matchedAbi = KNOWN_ABIS.firstOrNull { lowerName.contains(it) }

        // Si no menciona arquitectura, es un recurso común (base.apk, idioma o densidad) -> Compatible
        if (matchedAbi == null) {
            return true
        }

        val normalizedMatched = matchedAbi.replace('_', '-')

        // Verificar si la arquitectura está en la lista de arquitecturas que soporta el procesador
        for (supported in supportedAbis) {
            val normalizedSupported = supported.lowercase().replace('_', '-')
            if (normalizedMatched == normalizedSupported) {
                return true
            }
        }

        return false
    }
}