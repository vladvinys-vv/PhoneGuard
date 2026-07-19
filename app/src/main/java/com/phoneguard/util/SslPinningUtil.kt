package com.phoneguard.util

import java.io.InputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import android.util.Base64

object SslPinningUtil {

    fun generatePinFromCertificate(inputStream: InputStream): String {
        val certificateFactory = CertificateFactory.getInstance("X.509")
        val certificate = certificateFactory.generateCertificate(inputStream) as X509Certificate
        val publicKey = certificate.publicKey.encoded
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    fun generatePinFromPem(pem: String): String {
        val cleanedPem = pem.replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replace("\\s".toRegex(), "")
        val decoded = android.util.Base64.decode(cleanedPem, android.util.Base64.DEFAULT)
        return generatePinFromCertificate(decoded.inputStream())
    }
}