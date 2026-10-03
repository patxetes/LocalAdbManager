package com.localadb.manager.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Gestor de descubrimiento de servicios mDNS (DNS-SD) para ADB inalámbrico.
 * Localiza los puertos efímeros de conexión (_adb-tls-connect) y emparejamiento (_adb-tls-pairing).
 */
class AdbMdnsManager(private val context: Context) {

    interface DiscoveryCallback {
        fun onConnectPortFound(port: Int)
        fun onPairingPortFound(port: Int)
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "AdbMdnsManager"
        const val SERVICE_TYPE_CONNECT = "_adb-tls-connect._tcp."
        const val SERVICE_TYPE_PAIRING = "_adb-tls-pairing._tcp."

        // Registro estático en memoria para que PairingNotificationReceiver capture el puerto en vivo
        @Volatile
        var latestPairingPort: Int? = null

        @Volatile
        var latestConnectPort: Int? = null
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var isDiscovering = false

    private var connectListener: NsdManager.DiscoveryListener? = null
    private var pairingListener: NsdManager.DiscoveryListener? = null

    fun startDiscovery(callback: DiscoveryCallback) {
        if (isDiscovering) {
            stopDiscovery()
        }
        isDiscovering = true

        connectListener = crearDiscoveryListener(SERVICE_TYPE_CONNECT) { port ->
            latestConnectPort = port
            callback.onConnectPortFound(port)
        }

        pairingListener = crearDiscoveryListener(SERVICE_TYPE_PAIRING) { port ->
            latestPairingPort = port
            callback.onPairingPortFound(port)
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE_CONNECT, NsdManager.PROTOCOL_DNS_SD, connectListener)
            nsdManager.discoverServices(SERVICE_TYPE_PAIRING, NsdManager.PROTOCOL_DNS_SD, pairingListener)
            Log.i(TAG, "Escaneo mDNS iniciado para conexión y emparejamiento.")
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando descubrimiento mDNS", e)
            callback.onError(e.localizedMessage ?: "Error al iniciar mDNS")
        }
    }

    fun stopDiscovery() {
        if (!isDiscovering) return
        isDiscovering = false

        try {
            connectListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Aviso deteniendo listener de conexión", e)
        }

        try {
            pairingListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Aviso deteniendo listener de emparejamiento", e)
        }

        connectListener = null
        pairingListener = null
    }

    private fun crearDiscoveryListener(serviceType: String, onPortResolved: (Int) -> Unit): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Descubrimiento iniciado para: $regType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Servicio encontrado: ${serviceInfo.serviceName} ($serviceType)")
                try {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                            Log.e(TAG, "Fallo al resolver servicio mDNS: $errorCode")
                        }

                        override fun onServiceResolved(resolvedService: NsdServiceInfo) {
                            val port = resolvedService.port
                            Log.i(TAG, "Servicio resuelto: ${resolvedService.serviceName} en puerto: $port")
                            if (port > 0) {
                                onPortResolved(port)
                            }
                        }
                    })
                } catch (e: Exception) {
                    Log.e(TAG, "Excepción resolviendo servicio mDNS", e)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Servicio perdido: ${serviceInfo.serviceName}")
                if (serviceType == SERVICE_TYPE_PAIRING) {
                    latestPairingPort = null
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Descubrimiento detenido para: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Fallo al iniciar descubrimiento ($serviceType): $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Fallo al detener descubrimiento ($serviceType): $errorCode")
            }
        }
    }
}