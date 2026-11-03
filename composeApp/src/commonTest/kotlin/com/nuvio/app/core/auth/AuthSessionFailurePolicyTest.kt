package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthSessionFailurePolicyTest {
    @Test
    fun `401 remaining after refresh retry is treated as an invalid remote session`() {
        assertTrue(isInvalidRemoteSessionFailure(401, "Unauthorized"))
    }

    @Test
    fun `403 permission denial must not clear local account credentials`() {
        assertFalse(isInvalidRemoteSessionFailure(403, "permission denied by row-level security"))
    }

    @Test
    fun `403 remains non terminal even when message mentions an expired jwt`() {
        assertFalse(isInvalidRemoteSessionFailure(403, "JWT expired"))
    }

    @Test
    fun `explicit invalid jwt message remains detectable without status code`() {
        assertTrue(isInvalidRemoteSessionFailure(null, "Invalid JWT: token expired"))
    }

    @Test
    fun `remote user deletion is detectable without status code`() {
        assertTrue(isInvalidRemoteSessionFailure(null, "User does not exist"))
    }

    @Test
    fun `401 user lookup retries after refreshing the Supabase session`() {
        assertTrue(shouldRetryRemoteSessionValidationAfterRefresh(401))
        assertFalse(shouldRetryRemoteSessionValidationAfterRefresh(403))
        assertFalse(shouldRetryRemoteSessionValidationAfterRefresh(500))
    }

    @Test
    fun `expired jwt message retries refresh except for forbidden responses`() {
        assertTrue(shouldRetryRemoteSessionValidationAfterRefresh(null, "JWT expired"))
        assertFalse(shouldRetryRemoteSessionValidationAfterRefresh(403, "JWT expired"))
    }

    @Test
    fun `invalid refresh token is terminal and clears account scoped data`() {
        assertTrue(isInvalidRemoteSessionFailure(400, "Invalid Refresh Token", "RefreshTokenNotFound"))
        assertTrue(isInvalidRemoteSessionFailure(400, "Refresh token already used", "refresh_token_already_used"))
    }

    @Test
    fun `transient auth service errors do not clear account scoped data`() {
        assertFalse(isInvalidRemoteSessionFailure(429, "Too many requests", "TooManyRequests"))
        assertFalse(isInvalidRemoteSessionFailure(503, "Service unavailable", null))
    }

}
