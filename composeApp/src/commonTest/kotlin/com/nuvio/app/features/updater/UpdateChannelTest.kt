package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateChannelTest {
    @Test
    fun prereleaseBuildsDefaultToBeta() {
        listOf("1.1.0-beta", "v1.1.0-beta.2", "1.1.0-rc.1+123").forEach { version ->
            assertEquals(UpdateChannel.BETA, UpdateChannel.defaultForVersion(version))
        }
    }

    @Test
    fun finalAndUnknownVersionsDefaultToStable() {
        listOf("1.1.0", "V1.1.0+123", "unknown").forEach { version ->
            assertEquals(UpdateChannel.STABLE, UpdateChannel.defaultForVersion(version))
        }
    }

    @Test
    fun savedChannelsAreCaseInsensitive() {
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromStoredValue("stable"))
        assertEquals(UpdateChannel.BETA, UpdateChannel.fromStoredValue("BETA"))
        assertNull(UpdateChannel.fromStoredValue(null))
        assertNull(UpdateChannel.fromStoredValue("nightly"))
    }
}
