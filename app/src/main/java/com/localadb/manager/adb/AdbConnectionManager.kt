package com.localadb.manager.adb

import android.content.Context
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Local ADB daemon connection and pairing manager (127.0.0.1).
 * Extends AbsAdbConnectionManager with coroutine Mutex serialization and ReentrantLock thread-safety.
 */
class AdbConnectionManager private constructor(private val context: Context) : AbsAdbConnectionManager() {

    private var mPrivateKey: PrivateKey? = null
    private var mCertificate: Certificate? = null

    // Thread-level lock for immediate socket operations
    private val socketOperationLock = ReentrantLock()

    // Coroutine-level mutex for long-running exclusive operations (install sessions, streaming backups)
    val sessionMutex = Mutex()

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
            initializeKeys()
        }
        return mPrivateKey!!
    }

    override fun getCertificate(): Certificate {
        if (mCertificate == null) {
            initializeKeys()
        }
        return mCertificate!!
    }

    override fun getDeviceName(): String {
        return "LocalAdbManager"
    }

    /**
     * Executes a suspending action holding the exclusive ADB session mutex.
     * Prevents background discovery, health checks, or parallel operations from interfering.
     */
    suspend fun <T> withAdbLock(block: suspend () -> T): T {
        return sessionMutex.withLock {
            block()
        }
    }

    @Synchronized
    private fun initializeKeys() {
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
                Log.w(TAG, "Error reading existing keys, regenerating key pair...", e)
            }
        }

        try {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val keyPair: KeyPair = kpg.generateKeyPair()

            val now = System.currentTimeMillis()
            val notBefore = Date(now - 1000L * 60 * 60 * 24)
            val notAfter = Date(now + 1000L * 60 * 60 * 24 * 365 * 10) // 10 years validity

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
            Log.e(TAG, "Failed generating cryptographic certificates for ADB", e)
            throw RuntimeException("Could not generate ADB authentication keys", e)
        }
    }

    /**
     * Pairs the device using the ADB TLS Pairing protocol.
     * Thread-safe via socketOperationLock.
     */
    fun pairDevice(host: String = "127.0.0.1", port: Int, code: String): Boolean {
        if (port !in 1024..65535) {
            Log.e(TAG, "Pairing port out of range: $port")
            return false
        }
        val sanitizedCode = code.trim()
        if (sanitizedCode.length != 6 || !sanitizedCode.all { it.isDigit() }) {
            Log.e(TAG, "Invalid pairing code: $sanitizedCode")
            return false
        }

        return socketOperationLock.withLock {
            try {
                Log.i(TAG, "Starting TLS pairing with $host:$port...")
                pair(host, port, sanitizedCode)
                Log.i(TAG, "Pairing successful with $host:$port")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed pairing with $host:$port", e)
                false
            }
        }
    }

    /**
     * Connects to the local ADB daemon (127.0.0.1:targetPort).
     * Atomically disconnects existing connection before establishing a new one.
     */
    fun connectDevice(host: String = "127.0.0.1", port: Int): Boolean {
        return socketOperationLock.withLock {
            disconnectDeviceInternal()
            try {
                Log.i(TAG, "Connecting to ADB on $host:$port...")
                connect(host, port)
                Log.i(TAG, "Connected to ADB on $host:$port")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to ADB on $host:$port", e)
                disconnectDeviceInternal()
                false
            }
        }
    }

    fun disconnectDevice() {
        socketOperationLock.withLock {
            disconnectDeviceInternal()
        }
    }

    private fun disconnectDeviceInternal() {
        try {
            disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Warning disconnecting ADB", e)
        }
    }

    /**
     * Opens an ADB channel for bidirectional streaming (exec/shell).
     */
    fun abrirCanalRobusto(command: String): AdbStream {
        socketOperationLock.withLock {
            return openStream(command)
        }
    }

    /**
     * Executes a single command on ADB shell capturing both STDOUT and STDERR (2>&1).
     * Thread-safe against concurrent command executions.
     */
    fun executeCommand(command: String): String {
        return socketOperationLock.withLock {
            try {
                // Ensure stderr redirection (2>&1) so error messages do not trigger premature EOF
                val commandWithRedirection = if (command.contains("2>&1")) command else "$command 2>&1"
                val stream = openStream("exec:$commandWithRedirection")
                val output = StringBuilder()
                val input = stream.openInputStream()
                val buffer = ByteArray(1024)
                var bytesRead: Int

                try {
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.append(String(buffer, 0, bytesRead, Charsets.UTF_8))
                    }
                } catch (e: Exception) {
                    // Retain any output read before stream closure
                    if (output.isEmpty()) {
                        throw e
                    }
                }
                stream.close()
                output.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Error executing ADB command: $command", e)
                "Error: ${e.localizedMessage}"
            }
        }
    }
}