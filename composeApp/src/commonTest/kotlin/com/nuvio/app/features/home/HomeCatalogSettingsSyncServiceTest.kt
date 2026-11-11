package com.nuvio.app.features.home

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeCatalogSettingsSyncServiceTest {
    @Test
    fun `shared settings merge preserves unknown remote fields`() {
        val remote = buildJsonObject {
            put("future_setting", "preserved")
            put("show_catalog_type", false)
        }
        val local = buildJsonObject {
            put("show_catalog_type", true)
            put("hide_unreleased_content", true)
        }

        val merged = mergeHomeCatalogSettingsJson(remoteJson = remote, localJson = local)

        assertEquals("preserved", merged.getValue("future_setting").jsonPrimitive.content)
        assertEquals(true, merged.getValue("show_catalog_type").jsonPrimitive.content.toBoolean())
        assertEquals(true, merged.getValue("hide_unreleased_content").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `payload from an older client leaves the watchlist row off`() {
        val payload = Json { ignoreUnknownKeys = true }
            .decodeFromString<SyncHomeCatalogPayload>("""{"show_catalog_type":false,"items":[]}""")

        assertEquals(false, payload.showCatalogType)
        assertEquals(false, payload.showWatchlistRow)
    }

    @Test
    fun `older client push keeps the watchlist row field`() {
        val remote = buildJsonObject { put("show_watchlist_row", true) }
        val olderClient = buildJsonObject { put("show_catalog_type", true) }

        val merged = mergeHomeCatalogSettingsJson(remoteJson = remote, localJson = olderClient)

        assertEquals(true, merged.getValue("show_watchlist_row").jsonPrimitive.content.toBoolean())
    }
}
