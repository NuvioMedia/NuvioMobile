package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.data.LiveTvBackup
import com.nuvio.app.features.livetv.data.LiveTvCodec
import com.nuvio.app.features.livetv.data.LiveTvKeys
import com.nuvio.app.features.livetv.data.LiveTvPreferencesStore
import com.nuvio.app.features.livetv.data.PrefValue
import com.nuvio.app.features.livetv.data.exportForSync
import com.nuvio.app.features.livetv.data.importFromSync
import com.nuvio.app.features.livetv.data.syncHasSources
import com.nuvio.app.features.livetv.model.EpgSource
import com.nuvio.app.features.livetv.model.PlaylistSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTvBackupTest {

    /** A store with no persistence, as a test needs: everything stays in memory. */
    private class TestStore : LiveTvPreferencesStore() {
        init { installPersistence(load = { null }, save = {}) }
    }

    private val m3u = PlaylistSource(
        id = "pl-m3u", name = "Free TV", url = "https://example.com/list.m3u",
        userAgent = "TiviMate", useEmbeddedEpg = true,
        lastUpdatedMs = 1_700_000_000_000L, lastError = "HTTP 502", channelCount = 1234,
        importVod = false, importLive = true, streamFormat = "auto", logoSource = "epg"
    )
    private val xtream = PlaylistSource(
        id = "pl-xc", name = "Provider", url = "",
        xtreamServer = "http://xc.example.com:8080", xtreamUsername = "user1", xtreamPassword = "secret",
        lastUpdatedMs = 1_700_000_100_000L, channelCount = 9876,
        importVod = true, importLive = true, streamFormat = "m3u8",
        serverTimezone = "Europe/London", catchupNoHlsSince = 1_699_999_999_000L
    )
    private val epg = EpgSource(
        id = "epg-1", name = "EPG", url = "https://example.com/guide.xml.gz",
        lastUpdatedMs = 1_700_000_200_000L, lastError = null, programCount = 55_000
    )

    /** The export JSON the way the TV app writes it: {"t": …, "v": …} per DataStore key. */
    private fun tvExport(): JsonObject = buildJsonObject {
        put(LiveTvKeys.playlists, entry("str", JsonPrimitive(LiveTvCodec.encodePlaylists(listOf(m3u, xtream)))))
        put(LiveTvKeys.epgs, entry("str", JsonPrimitive(LiveTvCodec.encodeEpgs(listOf(epg)))))
        put(LiveTvKeys.favorites, entry("str", JsonPrimitive(LiveTvCodec.encodeStringList(listOf("pl-m3u:bbc1", "pl-xc:123")))))
        put(LiveTvKeys.hiddenChannels, entry("s", buildJsonArray { add("pl-m3u:shop"); add("pl-m3u:infomercial") }))
        put(LiveTvKeys.hiddenGroups, entry("s", buildJsonArray { add("pl-xc:Adult") }))
        put(LiveTvKeys.showNames, entry("b", JsonPrimitive(false)))
        put(LiveTvKeys.epgOffset, entry("i", JsonPrimitive(-60)))
        put("some_long_setting", entry("l", JsonPrimitive(1_234_567_890_123L)))
        put(LiveTvKeys.channelNames, entry("str", JsonPrimitive(LiveTvCodec.encodeStringMap(mapOf("pl-m3u:bbc1" to "BBC One")))))
        put(LiveTvKeys.channelOrder, entry("str", JsonPrimitive(LiveTvCodec.encodeListMap(mapOf("pl-m3u:News" to listOf("a", "b"))))))
        put(LiveTvKeys.epgOverrides, entry("str", JsonPrimitive(LiveTvCodec.encodeStringMap(mapOf("pl-m3u:bbc1" to "epg-1" + LiveTvKeys.EPG_SEP + "bbc1.uk")))))
        // Per-device keys that must never travel, including a second profile's copy.
        put(LiveTvKeys.recent, entry("str", JsonPrimitive(LiveTvCodec.encodeStringList(listOf("pl-m3u:bbc1")))))
        put("recent_p2", entry("str", JsonPrimitive(LiveTvCodec.encodeStringList(listOf("pl-xc:9")))))
        put(LiveTvKeys.lastChannel, entry("str", JsonPrimitive("pl-m3u:bbc1")))
        put(LiveTvKeys.developerTools, entry("b", JsonPrimitive(true)))
    }

    private fun entry(t: String, v: kotlinx.serialization.json.JsonElement) = buildJsonObject { put("t", t); put("v", v) }

    private fun JsonObject.value(key: String) = this[key]!!.jsonObject["v"]!!

    @Test
    fun exportMatchesTvFormatAfterRoundTrip() {
        val store = TestStore()
        val incoming = tvExport()

        val changed = store.importFromSync(incoming)
        assertTrue(changed.isEmpty(), "nothing was here before, so nothing 'changed': $changed")

        val exported = store.exportForSync()

        // Per-device keys are absent, both the plain key and a profile's copy.
        assertFalse(exported.containsKey(LiveTvKeys.recent))
        assertFalse(exported.containsKey("recent_p2"))
        assertFalse(exported.containsKey(LiveTvKeys.lastChannel))
        assertFalse(exported.containsKey(LiveTvKeys.developerTools))
        assertTrue(LiveTvKeys.SYNC_EXCLUDED.contains("recent_p17"))
        assertFalse(LiveTvKeys.SYNC_EXCLUDED.contains("favorites_p2"))

        // Every synced key came through with the same type tag and value.
        val expected = buildJsonObject {
            incoming.forEach { (k, v) -> if (k !in LiveTvKeys.SYNC_EXCLUDED) put(k, v) }
        }
        assertEquals(expected.keys, exported.keys)
        for (k in expected.keys) {
            if (k == LiveTvKeys.playlists || k == LiveTvKeys.epgs) continue
            assertEquals(expected[k]!!.jsonObject["t"], exported[k]!!.jsonObject["t"], "type of $k")
            val a = expected.value(k)
            val b = exported.value(k)
            if (a is JsonArray) assertEquals(a.map { it.jsonPrimitive.content }.toSet(), (b as JsonArray).map { it.jsonPrimitive.content }.toSet(), "set $k")
            else assertEquals(a, b, "value of $k")
        }
        assertEquals("b", exported[LiveTvKeys.showNames]!!.jsonObject["t"]!!.jsonPrimitive.content)
        assertEquals("i", exported[LiveTvKeys.epgOffset]!!.jsonObject["t"]!!.jsonPrimitive.content)
        assertEquals("l", exported["some_long_setting"]!!.jsonObject["t"]!!.jsonPrimitive.content)
        assertEquals("s", exported[LiveTvKeys.hiddenChannels]!!.jsonObject["t"]!!.jsonPrimitive.content)

        // Playlists and guides: the per-device download status is scrubbed on export...
        val exportedPlaylists = LiveTvCodec.decodePlaylists(exported.value(LiveTvKeys.playlists).jsonPrimitive.content)
        assertEquals(2, exportedPlaylists.size)
        exportedPlaylists.forEach { p ->
            assertEquals(0L, p.lastUpdatedMs); assertNull(p.lastError); assertEquals(0, p.channelCount)
            assertEquals("", p.serverTimezone); assertEquals(0L, p.catchupNoHlsSince)
        }
        val xc = exportedPlaylists.first { it.id == "pl-xc" }
        assertEquals("http://xc.example.com:8080", xc.xtreamServer)
        assertEquals("user1", xc.xtreamUsername)
        assertEquals("secret", xc.xtreamPassword)
        assertEquals("m3u8", xc.streamFormat)
        assertTrue(xc.importVod)
        val exportedEpgs = LiveTvCodec.decodeEpgs(exported.value(LiveTvKeys.epgs).jsonPrimitive.content)
        assertEquals(listOf(epg.copy(lastUpdatedMs = 0, lastError = null, programCount = 0)), exportedEpgs)

        // ...and what the device stores still has them (import keeps what the TV sent, since the
        // sources were new here).
        val stored = LiveTvCodec.decodePlaylists((store.rawData.value[LiveTvKeys.playlists] as PrefValue.Str).value)
        assertEquals("Europe/London", stored.first { it.id == "pl-xc" }.serverTimezone)
        assertEquals("HTTP 502", stored.first { it.id == "pl-m3u" }.lastError)

        // A second round trip is stable.
        val store2 = TestStore()
        store2.importFromSync(exported)
        assertEquals(exported.toString(), store2.exportForSync().toString())
        assertTrue(store.syncHasSources(exported))
        assertFalse(store.syncHasSources(buildJsonObject { put("x", entry("b", JsonPrimitive(true))) }))
        assertFalse(LiveTvBackup.syncHasSources(null))
    }

    @Test
    fun importKeepsLocalStatusAndReportsLoginChanges() {
        val store = TestStore()
        store.importFromSync(tvExport())
        // This device has downloaded both since.
        store.updatePlaylists { list ->
            list.map {
                when (it.id) {
                    "pl-xc" -> it.copy(lastUpdatedMs = 42L, channelCount = 7, serverTimezone = "America/New_York", lastError = null)
                    else -> it.copy(lastUpdatedMs = 43L, channelCount = 8)
                }
            }
        }
        store.updateEpgSources { list -> list.map { it.copy(lastUpdatedMs = 44L, programCount = 9, lastError = "boom") } }

        // The other device changed the Xtream password and left the M3U alone.
        val remote = tvExport().let { base ->
            buildJsonObject {
                base.forEach { (k, v) -> put(k, v) }
                put(LiveTvKeys.playlists, entry("str", JsonPrimitive(LiveTvCodec.encodePlaylists(listOf(m3u, xtream.copy(xtreamPassword = "newpass"))))))
            }
        }
        val changed = store.importFromSync(remote)
        assertEquals(setOf("pl-xc"), changed)

        val after = LiveTvCodec.decodePlaylists((store.rawData.value[LiveTvKeys.playlists] as PrefValue.Str).value)
        val xc = after.first { it.id == "pl-xc" }
        assertEquals("newpass", xc.xtreamPassword)
        // Local per-device fields preserved on import.
        assertEquals(42L, xc.lastUpdatedMs)
        assertEquals(7, xc.channelCount)
        assertEquals("America/New_York", xc.serverTimezone)
        val free = after.first { it.id == "pl-m3u" }
        assertEquals(43L, free.lastUpdatedMs)
        assertEquals(8, free.channelCount)
        val epgAfter = LiveTvCodec.decodeEpgs((store.rawData.value[LiveTvKeys.epgs] as PrefValue.Str).value).single()
        assertEquals(44L, epgAfter.lastUpdatedMs)
        assertEquals(9, epgAfter.programCount)
        assertEquals("boom", epgAfter.lastError)

        // Per-device keys in the incoming data are ignored, and this device's own are untouched.
        store.recordWatched("pl-xc:555")
        store.importFromSync(remote)
        assertEquals("pl-xc:555", (store.rawData.value[LiveTvKeys.lastChannel] as PrefValue.Str).value)
        assertEquals(listOf("pl-xc:555"), store.currentUserState().recent)

        // A synced key the other device no longer has is removed here.
        val without = buildJsonObject { remote.forEach { (k, v) -> if (k != LiveTvKeys.channelNames) put(k, v) } }
        store.importFromSync(without)
        assertFalse(store.rawData.value.containsKey(LiveTvKeys.channelNames))
    }

    @Test
    fun envelopeRoundTrips() {
        val data = tvExport()
        val env = LiveTvBackup.envelope(data, savedAtMs = 1_700_000_000_000L, deviceId = "dev-1", device = "Pixel")
        assertEquals(1, env["format"]!!.jsonPrimitive.content.toInt())
        val info = LiveTvBackup.parseEnvelope(env.toString())!!
        assertEquals(1_700_000_000_000L, info.savedAtMs)
        assertEquals("dev-1", info.deviceId)
        assertEquals("Pixel", info.device)
        assertEquals(data.toString(), info.data.toString())
    }
}
