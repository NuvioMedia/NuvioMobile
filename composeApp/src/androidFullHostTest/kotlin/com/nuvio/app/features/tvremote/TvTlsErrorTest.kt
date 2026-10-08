package com.nuvio.app.features.tvremote

import java.security.cert.CertificateExpiredException
import javax.net.ssl.SSLHandshakeException
import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class TvTlsErrorTest {
    @Test fun serverHandshakeFailureDoesNotClaimTheScannedCertificateIsWrong() {
        val message = TvRemoteRepository.errorMessage(SSLHandshakeException("internal_error"), pairing = true)
        assertContains(message, "Update Nuvio on the TV")
        assertFalse(message.contains("certificate", ignoreCase = true))
    }

    @Test fun certificateMismatchAndInvalidDatesHaveDistinctRecoveryInstructions() {
        val mismatch = SSLHandshakeException("handshake failed").apply { initCause(TvCertificateMismatchException()) }
        assertContains(TvRemoteRepository.errorMessage(mismatch, pairing = true), "certificate changed")
        val expired = SSLHandshakeException("handshake failed").apply { initCause(CertificateExpiredException()) }
        assertContains(TvRemoteRepository.errorMessage(expired, pairing = true), "date and time")
    }
}
