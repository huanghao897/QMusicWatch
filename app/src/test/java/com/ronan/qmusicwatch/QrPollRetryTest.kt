package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.network.ControlPlaneHttpException
import com.ronan.qmusicwatch.network.qrPollRetryDelayMs
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class QrPollRetryTest {
    @Test fun transientNetworkAndGatewayFailuresKeepTheExistingQr() {
        for (status in listOf(429, 500, 502, 503, 504)) {
            val error = ControlPlaneHttpException(status, "", 0, "temporary")
            assertEquals(1_000L, qrPollRetryDelayMs(error, 1, 120_000, 0))
        }
        assertEquals(2_000L, qrPollRetryDelayMs(IOException("offline"), 2, 120_000, 0))
        assertEquals(4_000L, qrPollRetryDelayMs(IOException("offline"), 30, 120_000, 0))
    }

    @Test fun respectsRetryAfterWithoutWaitingBeyondQrExpiry() {
        val error = ControlPlaneHttpException(429, "RATE_LIMITED", 6_000, "busy")
        assertEquals(6_000L, qrPollRetryDelayMs(error, 1, 120_000, 0))
        assertEquals(500L, qrPollRetryDelayMs(error, 1, 10_000, 9_500))
        assertNull(qrPollRetryDelayMs(error, 1, 10_000, 10_000))
    }

    @Test fun doesNotRetryInvalidOrDisabledSessions() {
        assertNull(qrPollRetryDelayMs(ControlPlaneHttpException(404, "QR_SESSION_NOT_FOUND", 0, "missing"), 1, 120_000, 0))
        assertNull(qrPollRetryDelayMs(ControlPlaneHttpException(503, "QR_LOGIN_DISABLED", 0, "disabled"), 1, 120_000, 0))
        assertNull(qrPollRetryDelayMs(IllegalArgumentException("invalid QR"), 1, 120_000, 0))
    }
}
