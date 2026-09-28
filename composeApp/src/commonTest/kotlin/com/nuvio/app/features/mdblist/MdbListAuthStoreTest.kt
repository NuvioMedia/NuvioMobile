package com.nuvio.app.features.mdblist

import io.ktor.utils.io.errors.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.Test

class MdbListAuthStoreTest {
    @Test
    fun `profile switches load isolated credentials and reject stale scope after switching back`() {
        val harness = MdbListTestHarness()
        harness.connected()
        val first = harness.store.scope()
        harness.store.selectProfile(2)
        assertFalse(harness.store.state.value.isAuthenticated)
        harness.connected("second", "second-refresh")
        harness.store.selectProfile(1)

        assertEquals("access-one", harness.store.authorization()?.tokens?.accessToken)
        assertFalse(harness.store.isCurrent(first))
        assertFalse(harness.store.saveUser(MdbListUser(99, "stale"), first))
        assertFalse(harness.store.clearAuth(first))
    }

    @Test
    fun `credentials survive process restart through persistence`() {
        val harness = MdbListTestHarness()
        harness.connected()
        harness.store.saveUser(MdbListUser(42, "viewer"), harness.store.scope())

        val reloaded = MdbListAuthStore(harness.persistence)

        assertEquals("access-one", reloaded.authorization()?.tokens?.accessToken)
        assertEquals("refresh-one", reloaded.authorization()?.tokens?.refreshToken)
        assertEquals(42L, reloaded.state.value.user?.id)
    }

    @Test
    fun `failed durable write never publishes connection`() {
        val harness = MdbListTestHarness()
        harness.persistence.failWrites = true

        assertFailsWith<IOException> { harness.connected() }

        assertFalse(harness.store.state.value.isAuthenticated)
        assertNull(harness.store.authorization())
    }

    @Test
    fun `removing a profile deletes only that profiles credentials`() {
        val harness = MdbListTestHarness()
        harness.connected()
        harness.store.selectProfile(2)
        harness.connected("second", "second-refresh")

        harness.store.removeProfile(1)

        assertTrue(harness.store.state.value.isAuthenticated)
        assertEquals(setOf(2), harness.persistence.profiles.keys)
        harness.store.removeProfile(2)
        assertFalse(harness.store.state.value.isAuthenticated)
        assertTrue(harness.persistence.profiles.isEmpty())
    }

    @Test
    fun `account cleanup deletes every profile and invalidates current operations`() {
        val harness = MdbListTestHarness()
        harness.connected()
        harness.store.selectProfile(2)
        harness.connected("second", "second-refresh")
        val scope = harness.store.scope()

        harness.store.clearAllProfiles()

        assertTrue(harness.persistence.profiles.isEmpty())
        assertFalse(harness.store.state.value.isAuthenticated)
        assertFalse(harness.store.isCurrent(scope))
    }

    @Test
    fun `late unauthorized response for rotated token cannot clear new credentials`() {
        val harness = MdbListTestHarness()
        harness.connected()
        val original = requireNotNull(harness.store.authorization())
        harness.store.refreshTokens(MdbListTokens("new", "new-refresh", harness.now + 600_000L), original)

        assertFalse(harness.store.clearAuth(original.scope, expectedAccessToken = "access-one"))
        assertEquals("new", harness.store.authorization()?.tokens?.accessToken)
    }

    @Test
    fun `corrupt persisted state does not appear connected`() {
        val persistence = MdbListTestPersistence()
        persistence.profiles[1] = "invalid"

        assertFalse(MdbListAuthStore(persistence).state.value.isAuthenticated)
    }
}
