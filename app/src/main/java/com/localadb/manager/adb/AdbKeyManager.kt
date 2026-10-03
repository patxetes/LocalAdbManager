package com.localadb.manager.adb

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * Módulo Criptográfico.
 *
 * Responsabilidad: Generar y persistir en disco el par de claves RSA (2048 bits)
 * y el certificado autofirmado X.509 necesarios para autenticarse con el demonio ADB.
 */
class AdbKeyManager(context: Context) {

    // Rutas de archivos en el almacenamiento privado de la app (equivalente a /data/data/<pkg>/files/)
    private val privateKeyFile = File(context.filesDir, "adb_private.pk8")
    private val certificateFile = File(context.filesDir, "adb_cert.der")
    private val bcProvider = BouncyCastleProvider()

    init {
        // Registrar el proveedor criptográfico BouncyCastle si no está presente
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(bcProvider)
        }
    }

    /**
     * Estructura de datos que contiene el par de credenciales activas.
     */
    data class AdbCredentials(
        val privateKey: PrivateKey,
        val certificate: Certificate
    )

    /**
     * Obtiene las credenciales existentes de disco o genera unas nuevas si es la primera vez.
     */
    @Synchronized
    fun getOrCreateCredentials(): AdbCredentials {
        return if (privateKeyFile.exists() && certificateFile.exists()) {
            loadCredentialsFromDisk()
        } else {
            generateAndPersistCredentials()
        }
    }

    /**
     * Carga los bytes binarios de las claves guardadas y reconstruye los objetos Java.
     */
    private fun loadCredentialsFromDisk(): AdbCredentials {
        // 1. Leer clave privada en formato PKCS#8
        val keyBytes = privateKeyFile.readBytes()
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance("RSA")
        val privateKey = keyFactory.generatePrivate(keySpec)

        // 2. Leer certificado X.509 en formato DER
        val certBytes = certificateFile.readBytes()
        val certFactory = CertificateFactory.getInstance("X.509")
        val certificate = certFactory.generateCertificate(certBytes.inputStream())

        return AdbCredentials(privateKey, certificate)
    }

    /**
     * Genera un par RSA nuevo, crea el certificado autofirmado y los guarda en disco.
     */
    private fun generateAndPersistCredentials(): AdbCredentials {
        // 1. Generación de clave RSA 2048 bits
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
        val keyPair = generator.generateKeyPair()

        // 2. Generación del certificado X.509 autofirmado
        val certificate = buildX509Certificate(keyPair)

        // 3. Volcar a disco en formato binario
        privateKeyFile.writeBytes(keyPair.private.encoded)
        certificateFile.writeBytes(certificate.encoded)

        return AdbCredentials(keyPair.private, certificate)
    }

    /**
     * Construye un certificado X.509 válido por 10 años firmado por la propia clave privada.
     */
    private fun buildX509Certificate(keyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val startDate = Date(now)
        val endDate = Date(now + 10L * 365 * 24 * 60 * 60 * 1000) // 10 años
        val serialNumber = BigInteger.valueOf(now)
        val commonName = X500Name("CN=LocalAdbManager")

        val certBuilder = JcaX509v3CertificateBuilder(
            commonName,
            serialNumber,
            startDate,
            endDate,
            commonName,
            keyPair.public
        )

        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(bcProvider)
            .build(keyPair.private)

        return JcaX509CertificateConverter()
            .setProvider(bcProvider)
            .getCertificate(certBuilder.build(signer))
    }
}