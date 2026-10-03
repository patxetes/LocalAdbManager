package com.localadb.manager.adb

import android.content.Context
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.security.PrivateKey
import java.security.cert.Certificate

/**
 * Módulo de Conexión ADB.
 *
 * Implementa la clase abstracta de libadb-android inyectando las claves
 * que cargamos desde AdbKeyManager.
 */
class AdbConnectionManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val keyManager = AdbKeyManager(context)
    private val credentials = keyManager.getOrCreateCredentials()

    companion object {
        @Volatile
        private var instance: AdbConnectionManager? = null

        /**
         * Patrón Singleton: garantiza una única instancia en memoria compartida.
         */
        fun getInstance(context: Context): AdbConnectionManager {
            return instance ?: synchronized(this) {
                instance ?: AdbConnectionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun getPrivateKey(): PrivateKey {
        return credentials.privateKey
    }

    override fun getCertificate(): Certificate {
        return credentials.certificate
    }

    override fun getDeviceName(): String {
        return "LocalAdbManager"
    }

    /**
     * Ejecuta el emparejamiento con el demonio local usando SPAKE2 sobre TLS.
     *
     * @param host Generalmente "127.0.0.1" (interfaz loopback)
     * @param port Puerto de emparejamiento descubierto por mDNS
     * @param pairingCode Código de 6 dígitos que muestra el sistema
     */
    fun pairDevice(host: String, port: Int, pairingCode: String): Boolean {
        return try {
            pair(host, port, pairingCode)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}