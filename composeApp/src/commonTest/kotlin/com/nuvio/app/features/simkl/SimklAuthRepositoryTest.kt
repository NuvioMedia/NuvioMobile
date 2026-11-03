package com.nuvio.app.features.simkl

import kotlin.test.Test
import kotlin.test.assertEquals

class SimklAuthRepositoryTest {

    @Test
    fun `successful refresh responses are accepted`() {
        listOf(200, 201).forEach { status ->
            assertEquals(
                SimklTokenRefreshResponseAction.ACCEPT,
                simklTokenRefreshResponseAction(status),
            )
        }
    }

    @Test
    fun `invalid grant errors invalidate credentials`() {
        assertEquals(
            SimklTokenRefreshResponseAction.INVALIDATE,
            simklTokenRefreshResponseAction(400, "invalid_grant"),
        )
        assertEquals(
            SimklTokenRefreshResponseAction.TRANSIENT_FAILURE,
            simklTokenRefreshResponseAction(401, "invalid_token"),
        )
        assertEquals(
            SimklTokenRefreshResponseAction.INVALIDATE,
            simklTokenRefreshResponseAction(400, "INVALID_GRANT"),
        )
        // OAuth invalid_grant is a client-error response; a server error must not
        // invalidate a stored grant even if an intermediary returns a stale body.
        assertEquals(
            SimklTokenRefreshResponseAction.TRANSIENT_FAILURE,
            simklTokenRefreshResponseAction(500, "invalid_grant"),
        )
    }

    @Test
    fun `transient oauth errors preserve credentials`() {
        assertEquals(
            SimklTokenRefreshResponseAction.TRANSIENT_FAILURE,
            simklTokenRefreshResponseAction(400, "invalid_request"),
        )
        assertEquals(
            SimklTokenRefreshResponseAction.TRANSIENT_FAILURE,
            simklTokenRefreshResponseAction(400, null),
        )
    }

    @Test
    fun `server and rate limit failures preserve credentials`() {
        listOf(401, 403, 408, 429, 500, 502, 503).forEach { status ->
            assertEquals(
                SimklTokenRefreshResponseAction.TRANSIENT_FAILURE,
                simklTokenRefreshResponseAction(status, "temporarily_unavailable"),
            )
        }
    }
}
