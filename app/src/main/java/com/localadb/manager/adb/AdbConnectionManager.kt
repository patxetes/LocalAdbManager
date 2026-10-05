package com.localadb.manager.adb

import android.content.Context
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * Gestor de conexión y emparejamiento ADB local (127.0.0.1).
 * Implementa AbsAdbConnectionManager con captura garantizada de stdout y stderr (2>&1).
 */
class AdbConnectionManager private constructor(private val context: Context) : AbsAdbConnectionManager() {

    private var mPrivateKey: PrivateKey? = null
    private var mCertificate: Certificate? = null

    companion object {
        private const val TAG = "AdbConnectionManager"

        @Volatile
        private var INSTANCE: AdbConnectionManager? = null

        fun getInstance(context: Context): AdbConnectionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AdbConnectionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    override fun getPrivateKey(): PrivateKey {
        if (mPrivateKey == null) {
            inicializarClaves()
        }
        return mPrivateKey!!
    }

    override fun getCertificate(): Certificate {
        if (mCertificate == null) {
            inicializarClaves()
        }
        return mCertificate!!
    }

    override fun getDeviceName(): String {
        return "LocalAdbManager"
    }

    @Synchronized
    private fun inicializarClaves() {
        val privFile = File(context.filesDir, "adb_private.pk8")
        val certFile = File(context.filesDir, "adb_cert.der")

        if (privFile.exists() && certFile.exists()) {
            try {
                val privBytes = privFile.readBytes()
                val spec = PKCS8EncodedKeySpec(privBytes)
                val kf = KeyFactory.getInstance("RSA")
                mPrivateKey = kf.generatePrivate(spec)

                val certBytes = certFile.readBytes()
                val cf = CertificateFactory.getInstance("X.509")
                mCertificate = cf.generateCertificate(certBytes.inputStream())
                return
            } catch (e: Exception) {
                Log.w(TAG, "Error leyendo certificados existentes, regenerando par de claves...", e)
            }
        }

        try {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val keyPair: KeyPair = kpg.generateKeyPair()

            val now = System.currentTimeMillis()
            val notBefore = Date(now - 1000L * 60 * 60 * 24)
            val notAfter = Date(now + 1000L * 60 * 60 * 24 * 365 * 10)

            val issuer = X500Name("CN=LocalAdbManager")
            val serial = BigInteger.valueOf(now)
            val builder = JcaX509v3CertificateBuilder(
                issuer,
                serial,
                notBefore,
                notAfter,
                issuer,
                keyPair.public
            )

            val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
            val holder = builder.build(signer)
            val cert = JcaX509CertificateConverter().getCertificate(holder)

            privFile.writeBytes(keyPair.private.encoded)
            certFile.writeBytes(cert.encoded)

            mPrivateKey = keyPair.private
            mCertificate = cert
        } catch (e: Exception) {
            Log.e(TAG, "Fallo generando certificados criptográficos para ADB", e)
            throw RuntimeException("No se pudieron generar las claves de autenticación ADB", e)
        }
    }

    fun pairDevice(host: String = "127.0.0.1", port: Int, code: String): Boolean {
        if (port !in 1024..65535) return false
        val sanitizedCode = code.trim()
        if (sanitizedCode.length != 6 || !sanitizedCode.all { it.isDigit() }) return false

        return try {
            Log.i(TAG, "Iniciando emparejamiento TLS con $host:$port...")
            pair(host, port, sanitizedCode)
            Log.i(TAG, "Emparejamiento exitoso con $host:$port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Fallo durante el emparejamiento con $host:$port", e)
            false
        }
    }

    fun connectDevice(host: String = "127.0.0.1", port: Int): Boolean {
        disconnectDevice()
        return try {
            Log.i(TAG, "Conectando a ADB en $host:$port...")
            connect(host, port)
            Log.i(TAG, "Conectado a ADB en $host:$port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error al conectar a ADB en $host:$port", e)
            disconnectDevice()
            false
        }
    }

    fun disconnectDevice() {
        try {
            disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Aviso cerrando conexión ADB", e)
        }
    }

    fun abrirCanalRobusto(command: String): AdbStream {
        return openStream(command)
    }

    /**
     * Ejecuta una orden en la shell de ADB capturando tanto STDOUT como STDERR (2>&1)
     * para evitar que los fallos del sistema cierren el flujo silenciosamente con 'Stream closed'.
     */
    fun executeCommand(command: String): String {
        return try {
            // Forzamos redirección de errores a la salida estándar para capturar mensajes de fallo
            val comandoConRedireccion = if (command.contains("2>&1")) command else "$command 2>&1"
            val stream = openStream("exec:$comandoConRedireccion")
            val output = StringBuilder()
            val input = stream.openInputStream()
            val buffer = ByteArray(1024)
            var bytesRead: Int

            try {
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.append(String(buffer, 0, bytesRead, Charsets.UTF_8))
                }
            } catch (e: Exception) {
                // Si ya teníamos texto leído antes del cierre del canal, lo preservamos
                if (output.isEmpty()) {
                    throw e
                }
            }
            stream.close()
            output.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error ejecutando comando ADB: $command", e)
            "Error: ${e.localizedMessage}"
        }
    }
}