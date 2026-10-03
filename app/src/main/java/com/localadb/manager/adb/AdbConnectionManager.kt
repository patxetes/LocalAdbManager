package com.localadb.manager.adb

import android.content.Context
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.PrivateKey
import java.security.cert.Certificate

/**
 * Gestor de Conexión y Ejecución ADB.
 *
 * Responsabilidad:
 *  - Mantener el socket TLS con el demonio local (127.0.0.1).
 *  - Abrir canales virtuales (Streams) para ejecución de comandos shell.
 */
class AdbConnectionManager private constructor(context: Context) : AbsAdbConnectionManager() {

    // Obtenemos las claves RSA generadas en el Hito 1.1
    private val keyManager = AdbKeyManager(context)
    private val credentials = keyManager.getOrCreateCredentials()

    companion object {
        @Volatile
        private var instance: AdbConnectionManager? = null

        /**
         * Singleton con inicialización sincronizada para acceso concurrente seguro.
         */
        fun getInstance(context: Context): AdbConnectionManager {
            return instance ?: synchronized(this) {
                instance ?: AdbConnectionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // --- Métodos abstractos requeridos por libadb-android ---

    override fun getPrivateKey(): PrivateKey {
        return credentials.privateKey
    }

    override fun getCertificate(): Certificate {
        return credentials.certificate
    }

    override fun getDeviceName(): String {
        return "LocalAdbManager"
    }

    // --- Operaciones de red y control ---

    /**
     * Realiza el emparejamiento criptográfico inicial (SPAKE2 + TLS 1.3).
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

    /**
     * Establece la conexión persistente con el demonio ADB en el puerto especificado.
     *
     * @param host Generalmente "127.0.0.1" (interfaz loopback)
     * @param port Puerto de conexión obtenido mediante el escáner mDNS
     */
    fun connectDevice(host: String, port: Int): Boolean {
        return try {
            // Invoca el handshake de autenticación RSA de libadb-android
            connect(host, port)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Cierra el socket y libera los canales de comunicación activos.
     */
    fun disconnectDevice() {
        try {
            close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Ejecuta un comando en el sistema operativo mediante el canal "exec:" de ADB.
     *
     * En términos de C: equivale a abrir una tubería (pipe) con popen("comando", "r"),
     * leer la salida del descriptor de archivo hasta EOF y cerrar el descriptor.
     *
     * @param command Comando a ejecutar (ej: "id", "whoami", "pm list packages")
     * @return La respuesta de texto generada por el comando
     */
    fun executeCommand(command: String): String {
        return try {
            // Abrir un stream hacia el servicio "exec" de ADB
            val stream = openStream("exec:$command")

            // Leer los bytes devueltos por el sistema operativo
            val reader = BufferedReader(InputStreamReader(stream.openInputStream()))
            val outputBuilder = StringBuilder()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                outputBuilder.append(line).append("\n")
            }

            // Liberar la tubería (stream)
            stream.close()

            outputBuilder.toString().trim()
        } catch (e: Exception) {
            "Error al ejecutar comando: ${e.localizedMessage}"
        }
    }
}