package com.nuvio.app.features.debrid

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PremiumizeDeviceAuthTest {
    @Test
    fun `maps pending and slow down oauth states to pending`() {
        assertEquals(
            DebridDeviceAuthorizationTokenResult.Pending,
            premiumizeDeviceAuthorizationTokenResult(tokenError("authorization_pending")),
        )
        assertEquals(
            DebridDeviceAuthorizationTokenResult.Pending,
            premiumizeDeviceAuthorizationTokenResult(tokenError("slow_down")),
        )
    }

    @Test
    fun `maps success expired denied and invalid oauth states`() {
        assertTrue(
            premiumizeDeviceAuthorizationTokenResult(
                DebridApiResponse(
                    status = 200,
                    body = PremiumizeDeviceTokenDto(accessToken = "pm-token", tokenType = "Bearer"),
                    rawBody = "",
                ),
            ) is DebridDeviceAuthorizationTokenResult.Authorized,
        )
        assertEquals(
            DebridDeviceAuthorizationTokenResult.Expired,
            premiumizeDeviceAuthorizationTokenResult(tokenError("invalid_grant")),
        )
        assertTrue(
            premiumizeDeviceAuthorizationTokenResult(tokenError("access_denied")) is
                DebridDeviceAuthorizationTokenResult.Failed,
        )
    }

    @Test
    fun `missing Premiumize client id fails before device flow starts`() = runBlocking {
        val api = PremiumizeDebridProviderApi(clientIdProvider = { "" })

        val failed = try {
            api.startDeviceAuthorization("Lentra")
            false
        } catch (_: IllegalStateException) {
            true
        }

        assertTrue(failed)
    }

    private fun tokenError(error: String): DebridApiResponse<PremiumizeDeviceTokenDto> =
        DebridApiResponse(
            status = 400,
            body = PremiumizeDeviceTokenDto(error = error, errorDescription = error),
            rawBody = """{"error":"$error"}""",
        )
}
