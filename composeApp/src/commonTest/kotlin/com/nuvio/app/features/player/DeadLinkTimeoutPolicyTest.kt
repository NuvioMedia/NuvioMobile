package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals

class DeadLinkTimeoutPolicyTest {
    @Test
    fun timeoutDefaultsAndClampsToFiveThroughSixtySeconds() {
        assertEquals(10, DeadLinkTimeoutPolicy.DEFAULT_SECONDS)
        assertEquals(5, DeadLinkTimeoutPolicy.MIN_SECONDS)
        assertEquals(60, DeadLinkTimeoutPolicy.MAX_SECONDS)

        assertEquals(5, DeadLinkTimeoutPolicy.normalizeSeconds(Int.MIN_VALUE))
        assertEquals(5, DeadLinkTimeoutPolicy.normalizeSeconds(0))
        assertEquals(5, DeadLinkTimeoutPolicy.normalizeSeconds(4))
        assertEquals(5, DeadLinkTimeoutPolicy.normalizeSeconds(5))
        assertEquals(10, DeadLinkTimeoutPolicy.normalizeSeconds(10))
        assertEquals(60, DeadLinkTimeoutPolicy.normalizeSeconds(60))
        assertEquals(60, DeadLinkTimeoutPolicy.normalizeSeconds(61))
        assertEquals(60, DeadLinkTimeoutPolicy.normalizeSeconds(Int.MAX_VALUE))
    }

    @Test
    fun installedAddonSourcesArePrioritizedOverOtherSources() {
        val addon = StreamItem(addonName = "Addon", addonId = "addon:one")
        val plugin = StreamItem(addonName = "Plugin", addonId = "plugin:one")
        assertEquals(0, deadLinkSourcePriority(addon))
        assertEquals(1, deadLinkSourcePriority(plugin))
    }
}
