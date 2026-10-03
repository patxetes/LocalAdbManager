package com.localadb.manager.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Módulo de Descubrimiento de Red (mDNS / ZeroConf).
 *
 * Responsabilidad: Localizar dinámicamente en qué puertos TCP efímeros
 * está escuchando el demonio ADB del dispositivo.
 */
class AdbMdnsManager(context: Context) {

    private val tag = "AdbMdnsManager"
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    // Servicios estándar emitidos por Android 11+
    private val serviceTypeConnect = "_adb-tls-connect._tcp."
    private val serviceTypePairing = "_adb-tls-pairing._tcp."

    // Punteros a los listeners activos para poder cancelarlos
    private var connectListener: NsdManager.DiscoveryListener? = null
    private var pairingListener: NsdManager.DiscoveryListener? = null

    /**
     * Interfaz de retorno de eventos (Callbacks estilo punteros a función en C)
     */
    interface DiscoveryCallback {
        fun onConnectPortFound(port: Int)
        fun onPairingPortFound(port: Int)
        fun onError(message: String)
    }

    /**
     * Inicia la escucha de ambos tipos de servicio en la red local.
     */
    fun startDiscovery(callback: DiscoveryCallback) {
        stopDiscovery() // Detener búsquedas previas si las hubiera

        connectListener = createDiscoveryListener(serviceTypeConnect, callback)
        pairingListener = createDiscoveryListener(serviceTypePairing, callback)

        try {
            nsdManager.discoverServices(serviceTypeConnect, NsdManager.PROTOCOL_DNS_SD, connectListener)
            nsdManager.discoverServices(serviceTypePairing, NsdManager.PROTOCOL_DNS_SD, pairingListener)
            Log.d(tag, "Búsqueda mDNS iniciada con éxito.")
        } catch (e: Exception) {
            Log.e(tag, "Fallo al iniciar mDNS", e)
            callback.onError(e.localizedMessage ?: "Error iniciando mDNS")
        }
    }

    /**
     * Detiene la escucha y libera recursos del socket de multidifusión.
     */
    fun stopDiscovery() {
        safelyStop(connectListener)
        connectListener = null

        safelyStop(pairingListener)
        pairingListener = null
    }

    private fun safelyStop(listener: NsdManager.DiscoveryListener?) {
        if (listener != null) {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.w(tag, "Aviso deteniendo listener: ${e.message}")
            }
        }
    }

    private fun createDiscoveryListener(
        targetServiceType: String,
        callback: DiscoveryCallback
    ): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(tag, "Escuchando servicio: $regType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(tag, "Servicio detectado: ${serviceInfo.serviceName}. Resolviendo dirección...")
                resolveService(serviceInfo, targetServiceType, callback)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(tag, "Servicio perdido: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(tag, "Escucha detenida: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                callback.onError("Error de inicio mDNS: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(tag, "Error deteniendo mDNS: $errorCode")
            }
        }
    }

    private fun resolveService(
        serviceInfo: NsdServiceInfo,
        targetServiceType: String,
        callback: DiscoveryCallback
    ) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(tag, "Fallo resolviendo puerto para ${serviceInfo.serviceName}: $errorCode")
            }

            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                val port = resolvedInfo.port
                Log.i(tag, "Servicio resuelto -> $targetServiceType en puerto $port")

                if (targetServiceType == serviceTypeConnect) {
                    callback.onConnectPortFound(port)
                } else if (targetServiceType == serviceTypePairing) {
                    callback.onPairingPortFound(port)
                }
            }
        })
    }
}