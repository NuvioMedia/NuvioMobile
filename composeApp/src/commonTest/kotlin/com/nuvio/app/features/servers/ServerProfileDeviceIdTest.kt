package com.nuvio.app.features.servers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ServerProfileDeviceIdTest {
    @Test
    fun primaryProfileKeepsTheInstallationId() {
        assertEquals("device-1", profileScopedDeviceId("device-1", 1))
    }

    @Test
    fun secondaryProfilesGetTheirOwnDeviceId() {
        assertEquals("device-1-p2", profileScopedDeviceId("device-1", 2))
        assertEquals("device-1-p3", profileScopedDeviceId("device-1", 3))
        assertNotEquals(profileScopedDeviceId("device-1", 2), profileScopedDeviceId("device-1", 3))
    }
}
