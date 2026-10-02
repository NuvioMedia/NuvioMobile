package com.nuvio.app.features.addons

import com.nuvio.app.core.auth.AuthState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddonSyncEligibilityTest {
    @Test
    fun `server push is disabled while authentication is loading`() {
        assertFalse(shouldPushAddonsToServer(AuthState.Loading))
    }

    @Test
    fun `server push is disabled when unauthenticated`() {
        assertFalse(shouldPushAddonsToServer(AuthState.Unauthenticated))
    }

    @Test
    fun `server push is disabled for local anonymous users`() {
        assertFalse(
            shouldPushAddonsToServer(
                AuthState.Authenticated(
                    userId = "guest-id",
                    email = null,
                    isAnonymous = true,
                ),
            ),
        )
    }

    @Test
    fun `server push is enabled for registered authenticated users`() {
        assertTrue(
            shouldPushAddonsToServer(
                AuthState.Authenticated(
                    userId = "member-id",
                    email = "member@example.com",
                    isAnonymous = false,
                ),
            ),
        )
    }
}
