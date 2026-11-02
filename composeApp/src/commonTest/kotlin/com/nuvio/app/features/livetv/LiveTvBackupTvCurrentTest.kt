package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.data.LiveTvPreferencesStore
import com.nuvio.app.features.livetv.data.exportForSync
import com.nuvio.app.features.livetv.data.importFromSync
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Settings the Android TV app has that the phone app does not use (menu layout, On Demand play
 * mode, and anything the TV adds later) must pass through a phone untouched, so a backup that
 * goes TV -> phone -> TV never loses them.
 */
class LiveTvBackupTvCurrentTest {

    private class TestStore : LiveTvPreferencesStore() {
        init { installPersistence(load = { null }, save = {}) }
    }

    private fun entry(t: String, v: kotlinx.serialization.json.JsonElement) = buildJsonObject { put("t", t); put("v", v) }

    private fun tvOnlySettings(): JsonObject = buildJsonObject {
        put("side_menu_line", entry("b", JsonPrimitive(true)))
        put("side_menu_fit", entry("b", JsonPrimitive(false)))
        put("on_demand_play_direct", entry("b", JsonPrimitive(true)))
        // Per-profile copies of a TV setting, and a key a later TV version might add.
        put("side_menu_line_p2", entry("b", JsonPrimitive(false)))
        put("a_setting_added_by_a_future_tv_version", entry("str", JsonPrimitive("keep me")))
        put("a_future_number", entry("i", JsonPrimitive(42)))
    }

    @Test
    fun tvOnlySettingsSurviveAPhoneRoundTrip() {
        val incoming = tvOnlySettings()
        val phone = TestStore()
        phone.importFromSync(incoming)

        val exported = phone.exportForSync()
        for ((key, value) in incoming) {
            assertTrue(exported.containsKey(key), "$key was dropped on the phone")
            assertEquals(value, exported[key], "$key changed on the phone")
        }
    }

    @Test
    fun importReplacesSettingsTheOtherDeviceNoLongerHas() {
        val phone = TestStore()
        phone.importFromSync(tvOnlySettings())
        // A later TV backup without one of them: the phone follows it (same rule as the TV's own import).
        val later = buildJsonObject {
            put("side_menu_fit", entry("b", JsonPrimitive(false)))
        }
        phone.importFromSync(later)
        val exported = phone.exportForSync()
        assertEquals(setOf("side_menu_fit"), exported.keys)
    }
}
