package com.nuvio.app.features.plugins

import kotlin.test.assertEquals
import kotlin.test.Test

class PluginMediaTypeTest {
    @Test
    fun `mobile normalizer feeds series correction without changing legacy plugins`() {
        val normalized = normalizePluginType("series")
        assertEquals("tv", normalized)
        assertEquals("series", jsPluginMediaType(normalized, listOf("series"), 1, 1))
        assertEquals("tv", jsPluginMediaType(normalized, listOf("tv"), 1, 1))
    }

    @Test
    fun `episodic tv uses declared series type`() {
        assertEquals("series", jsPluginMediaType("tv", listOf("movie", "series"), 1, 1))
        assertEquals("series", jsPluginMediaType("TV", listOf("SERIES", "tv"), 0, 1))
    }

    @Test
    fun `legacy tv only plugins retain tv`() {
        assertEquals("tv", jsPluginMediaType("tv", listOf("movie", "tv"), 1, 1))
        assertEquals("tv", jsPluginMediaType("tv", emptyList(), 1, 1))
    }

    @Test
    fun `tv without season and episode remains tv`() {
        assertEquals("tv", jsPluginMediaType("tv", listOf("series", "tv"), null, null))
        assertEquals("tv", jsPluginMediaType("tv", listOf("series"), 1, null))
        assertEquals("tv", jsPluginMediaType("tv", listOf("series"), null, 1))
    }

    @Test
    fun `other request types remain unchanged`() {
        for (type in listOf("movie", "series", "anime", "channel", "custom")) {
            assertEquals(type, jsPluginMediaType(type, listOf("series", type), 1, 1))
            assertEquals(type, jsPluginMediaType(type, listOf("series", type), null, null))
        }
    }
}
