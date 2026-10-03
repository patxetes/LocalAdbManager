package com.localadb.manager.backup

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Estructura de datos que representa una aplicación instalada en el dispositivo.
 */
data class AppInstalada(
    val nombreVisible: String,
    val paqueteId: String,
    val versionNombre: String,
    val versionCodigo: Long,
    val esDepurable: Boolean,      // Flag FLAG_DEBUGGABLE (indica si run-as puede leer sus datos)
    val esDeSistema: Boolean,
    val rutaBaseApk: String,       // Ruta física al base.apk (/data/app/...)
    val rutasSplits: List<String>, // Rutas a splits secundarios si la app es dividida
    val tamanoBytesAprox: Long,
    val icono: Drawable?
)

/**
 * Gestor de inspección y catalogación de paquetes del sistema operativo.
 */
object PackageCatalogManager {

    private const val ETIQUETA_LOG = "PackageCatalog"

    /**
     * Obtiene la lista completa de aplicaciones instaladas por el usuario,
     * excluyendo las librerías y componentes internos del sistema Android.
     */
    fun obtenerAplicacionesUsuario(context: Context): List<AppInstalada> {
        val packageManager = context.packageManager
        val listaResultado = mutableListOf<AppInstalada>()

        try {
            // Obtenemos todos los paquetes con sus metadatos básicos
            val flags = PackageManager.GET_META_DATA
            val paquetes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledPackages(flags)
            }

            for (infoPaquete in paquetes) {
                val appInfo = infoPaquete.applicationInfo ?: continue

                // Filtramos componentes internos del sistema salvo que hayan sido actualizados por el usuario
                if (esAplicacionDeUsuario(appInfo)) {
                    val appMapeada = construirModeloApp(packageManager, infoPaquete, appInfo)
                    listaResultado.add(appMapeada)
                }
            }

            // Ordenar alfabéticamente por nombre visible para una navegación cómoda
            listaResultado.sortBy { it.nombreVisible.lowercase() }

        } catch (e: Exception) {
            Log.e(ETIQUETA_LOG, "Error al consultar paquetes instalados", e)
        }

        return listaResultado
    }

    /**
     * Determina si la aplicación corresponde a una app instalada por el usuario.
     * Criterio: No tener la bandera FLAG_SYSTEM, o tener FLAG_UPDATED_SYSTEM_APP.
     */
    private fun esAplicacionDeUsuario(appInfo: ApplicationInfo): Boolean {
        val esSistema = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val esActualizada = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

        // Si no es de sistema, o si venía de fábrica pero el usuario la actualizó
        return !esSistema || esActualizada
    }

    /**
     * Mapea la información técnica del PackageInfo a nuestra estructura AppInstalada.
     */
    private fun construirModeloApp(
        pm: PackageManager,
        paquete: PackageInfo,
        appInfo: ApplicationInfo
    ): AppInstalada {
        val nombre = appInfo.loadLabel(pm).toString()
        val icono = appInfo.loadIcon(pm)

        // Comprobación clave: si tiene FLAG_DEBUGGABLE activa, podremos respaldar datos privados con run-as
        val esDebug = (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val esSistema = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

        val rutaBase = appInfo.sourceDir ?: ""
        val splits = appInfo.splitSourceDirs?.toList() ?: emptyList()

        // Calculamos el tamaño aproximado sumando los binarios APK en disco
        val tamanoTotal = calcularTamanoBinarios(rutaBase, splits)

        val codigoVersion: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            paquete.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            paquete.versionCode.toLong()
        }

        return AppInstalada(
            nombreVisible = nombre,
            paqueteId = paquete.packageName,
            versionNombre = paquete.versionName ?: "N/D",
            versionCodigo = codigoVersion,
            esDepurable = esDebug,
            esDeSistema = esSistema,
            rutaBaseApk = rutaBase,
            rutasSplits = splits,
            tamanoBytesAprox = tamanoTotal,
            icono = icono
        )
    }

    /**
     * Suma los bytes en disco del base.apk y de cualquier split adicional.
     */
    private fun calcularTamanoBinarios(rutaBase: String, splits: List<String>): Long {
        var bytes: Long = 0
        if (rutaBase.isNotEmpty()) {
            bytes += File(rutaBase).length()
        }
        for (split in splits) {
            bytes += File(split).length()
        }
        return bytes
    }
}