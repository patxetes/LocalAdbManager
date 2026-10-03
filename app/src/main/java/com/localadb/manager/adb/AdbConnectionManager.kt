package com.localadb.manager.adb

import android.content.Context
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.PrivateKey
import java.security.cert.Certificate

/**
 * Gestor de la conexión ADB local con recuperación automática ante caídas de socket.
 */
class AdbConnectionManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val gestorClaves = AdbKeyManager(context)
    private val credenciales = gestorClaves.getOrCreateCredentials()

    // Memoria del último destino conectado para permitir reconexión transparente
    private var ultimoHost: String? = null
    private var ultimoPuerto: Int = -1

    companion object {
        private const val ETIQUETA_LOG = "AdbConnectionManager"

        @Volatile
        private var instanciaUnica: AdbConnectionManager? = null

        fun getInstance(context: Context): AdbConnectionManager {
            return instanciaUnica ?: synchronized(this) {
                instanciaUnica ?: AdbConnectionManager(context.applicationContext).also {
                    instanciaUnica = it
                }
            }
        }
    }

    override fun getPrivateKey(): PrivateKey {
        return credenciales.privateKey
    }

    override fun getCertificate(): Certificate {
        return credenciales.certificate
    }

    override fun getDeviceName(): String {
        return "LocalAdbManager"
    }

    /**
     * Empareja mediante SPAKE2.
     */
    fun pairDevice(host: String, port: Int, pairingCode: String): Boolean {
        return try {
            pair(host, port, pairingCode)
            true
        } catch (e: Exception) {
            Log.e(ETIQUETA_LOG, "Fallo al emparejar con $host:$port", e)
            false
        }
    }

    /**
     * Conecta al puerto del demonio adbd y registra los datos de destino.
     */
    fun connectDevice(host: String, port: Int): Boolean {
        return try {
            disconnectDevice()
            connect(host, port)
            ultimoHost = host
            ultimoPuerto = port
            Log.i(ETIQUETA_LOG, "Conectado exitosamente a ADB en $host:$port")
            true
        } catch (e: Exception) {
            Log.e(ETIQUETA_LOG, "Error al conectar a ADB en $host:$port", e)
            false
        }
    }

    /**
     * Cierra el socket de forma segura.
     */
    fun disconnectDevice() {
        try {
            close()
        } catch (_: Exception) {}
    }

    /**
     * Abre un flujo virtual con intento de reconexión si el socket murió tras una instalación previa.
     */
    fun abrirCanalRobusto(destino: String): AdbStream {
        return try {
            openStream(destino)
        } catch (e: Exception) {
            Log.w(ETIQUETA_LOG, "Canal caído ($destino). Intentando reconexión automática...", e)
            val host = ultimoHost
            val puerto = ultimoPuerto

            if (host != null && puerto > 0) {
                disconnectDevice()
                connect(host, puerto)
                // Segundo intento tras reconectar el socket
                openStream(destino)
            } else {
                throw e
            }
        }
    }

    /**
     * Ejecuta comandos en shell capturando la salida y recuperando el enlace si estaba caído.
     */
    fun executeCommand(command: String): String {
        return try {
            val canal = abrirCanalRobusto("exec:$command")
            val lector = BufferedReader(InputStreamReader(canal.openInputStream()))
            val constructorSalida = StringBuilder()

            var lineaLeida: String?
            while (lector.readLine().also { lineaLeida = it } != null) {
                constructorSalida.append(lineaLeida).append("\n")
            }

            canal.close()
            constructorSalida.toString().trim()
        } catch (e: Exception) {
            Log.e(ETIQUETA_LOG, "Error ejecutando comando shell: $command", e)
            "Error: ${e.localizedMessage}"
        }
    }
}