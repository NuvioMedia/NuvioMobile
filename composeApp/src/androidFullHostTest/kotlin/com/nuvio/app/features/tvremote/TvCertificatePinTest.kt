package com.nuvio.app.features.tvremote

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.Test
import kotlin.test.assertFailsWith

class TvCertificatePinTest {
    private val certificate = CertificateFactory.getInstance("X.509").generateCertificate("""
-----BEGIN CERTIFICATE-----
MIIBjzCCARWgAwIBAgIJAOHusRY0oSafMAoGCCqGSM49BAMDMB0xGzAZBgNVBAMT
Ek51dmlvIHRlc3QgZml4dHVyZTAeFw0yNjA5MjgxNjA2MDRaFw0zNjA5MjUxNjA2
MDRaMB0xGzAZBgNVBAMTEk51dmlvIHRlc3QgZml4dHVyZTB2MBAGByqGSM49AgEG
BSuBBAAiA2IABH8nkd1o6WNoUBNghqp2kh/WBZKC+2gyHe2qaf59BG9lb/gP2d5v
9i2tIhSyU+ntR+SFAH4cSX+nxPT7fVxglVxU/VUxr7bsyKCiRbWo79hOEE7jqdgP
sE1Fi6LtbsjOLKMhMB8wHQYDVR0OBBYEFEomLhReFCUk3mIlIMigKX6+UYD+MAoG
CCqGSM49BAMDA2gAMGUCMQDpZCf4I8iXdTR3B7SR/3euD4KYXJiUwzca7W6qVazI
g3GJoloszUvRuUNRTPBtzt8CMC1LI2xnRRXyikaHOIxN9r0W8Qqoda4w58YlKqDj
vDhIaV2KdPJRXgJevofSrzfCAQ==
-----END CERTIFICATE-----
""".trimIndent().byteInputStream()) as X509Certificate

    @Test fun scannedCertificateIsAcceptedAndDifferentPinIsRejected() {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
        PinnedTvTrust(digest).checkServerTrusted(arrayOf(certificate), "EC")
        assertFailsWith<CertificateException> { PinnedTvTrust("0".repeat(64)).checkServerTrusted(arrayOf(certificate), "EC") }
        assertFailsWith<CertificateException> { PinnedTvTrust(digest).checkServerTrusted(emptyArray(), "EC") }
    }
}