package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals

class DeadLinkTimeoutPolicyTest {
    @Test
    fun timeoutDefaultsAndClampsToFiveThroughSixtySeconds() {
        assertEquals(10, DeadLinkTimeoutPolicy.DEFAULT_SECONDS)
        assertEquals(5, DeadLinkTimeoutPolicy.normalizeSeconds(0))
        assertEquals(10, DeadLinkTimeoutPolicy.normalizeSeconds(10))
        assertEquals(60, DeadLinkTimeoutPolicy.normalizeSeconds(90))
    }

    @Test
    fun addonSourcesAreRankedBeforePluginSources() {
        val addon = StreamItem(url = "https://example.test/addon.m3u8", addonName = "Addon", addonId = "addon:one")
        val plugin = StreamItem(url = "https://example.test/plugin.m3u8", addonName = "Plugin", addonId = "plugin:one")
        assertEquals(0, deadLinkSourcePriority(addon))
        assertEquals(1, deadLinkSourcePriority(plugin))
    }
}
