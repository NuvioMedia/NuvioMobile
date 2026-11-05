package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.data.LiveTvPreferencesStore
import com.nuvio.app.features.livetv.data.exportForSync
import com.nuvio.app.features.livetv.data.importFromSync
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class VpnBackupCompatibilityTest {
    @Test fun genericStoreKeepsOpaqueVpnBackupOnExport() {
        val store = object : LiveTvPreferencesStore() {
            init { installPersistence(load = { null }, save = {}) }
        }
        val vpn = buildJsonObject { put("t", "str"); put("v", "opaque future VPN configuration") }
        store.importFromSync(buildJsonObject { put("vpn_profiles_v1", vpn) })
        // A generic backup store must preserve the VPN entry without applying device-global profiles.
        assertEquals(vpn, store.exportForSync()["vpn_profiles_v1"])
    }
}
