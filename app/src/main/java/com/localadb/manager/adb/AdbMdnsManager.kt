/*
 * Based on AdbMdns from Shizuku / anyapk (c) RikkaApps / sam1am
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.localadb.manager.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * mDNS (DNS-SD) service discovery manager for Wireless ADB.
 * Discovers ephemeral connection (_adb-tls-connect) and pairing (_adb-tls-pairing) ports.
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

        // Static memory cache allowing real-time port resolution by notification receivers
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

        connectListener = createDiscoveryListener(SERVICE_TYPE_CONNECT) { port ->
            latestConnectPort = port
            callback.onConnectPortFound(port)
        }

        pairingListener = createDiscoveryListener(SERVICE_TYPE_PAIRING) { port ->
            latestPairingPort = port
            callback.onPairingPortFound(port)
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE_CONNECT, NsdManager.PROTOCOL_DNS_SD, connectListener)
            nsdManager.discoverServices(SERVICE_TYPE_PAIRING, NsdManager.PROTOCOL_DNS_SD, pairingListener)
            Log.i(TAG, "Started mDNS discovery for connect and pairing services.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting mDNS discovery", e)
            callback.onError(e.localizedMessage ?: "Error starting mDNS")
        }
    }

    fun stopDiscovery() {
        if (!isDiscovering) return
        isDiscovering = false

        try {
            connectListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Warning stopping connect discovery listener", e)
        }

        try {
            pairingListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Warning stopping pairing discovery listener", e)
        }

        connectListener = null
        pairingListener = null
    }

    private fun createDiscoveryListener(serviceType: String, onPortResolved: (Int) -> Unit): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Discovery started for: $regType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName} ($serviceType)")
                try {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                            Log.e(TAG, "Failed resolving mDNS service: $errorCode")
                        }

                        override fun onServiceResolved(resolvedService: NsdServiceInfo) {
                            val port = resolvedService.port
                            Log.i(TAG, "Service resolved: ${resolvedService.serviceName} at port: $port")
                            if (port > 0) {
                                onPortResolved(port)
                            }
                        }
                    })
                } catch (e: Exception) {
                    Log.e(TAG, "Exception resolving mDNS service", e)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                if (serviceType == SERVICE_TYPE_PAIRING) {
                    latestPairingPort = null
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped for: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Failed starting discovery ($serviceType): $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Failed stopping discovery ($serviceType): $errorCode")
            }
        }
    }
}