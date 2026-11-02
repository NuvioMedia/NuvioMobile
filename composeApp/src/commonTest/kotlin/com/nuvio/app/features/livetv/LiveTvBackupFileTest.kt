package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.data.BackupFileException
import com.nuvio.app.features.livetv.data.LiveTvBackupApply
import com.nuvio.app.features.livetv.data.LiveTvCodec
import com.nuvio.app.features.livetv.data.LiveTvKeys
import com.nuvio.app.features.livetv.data.LiveTvPreferencesStore
import com.nuvio.app.features.livetv.data.exportBackupFileText
import com.nuvio.app.features.livetv.data.importBackupFile
import com.nuvio.app.features.livetv.data.importFromSync
import com.nuvio.app.features.livetv.data.optInt
import com.nuvio.app.features.livetv.data.optJSONObject
import com.nuvio.app.features.livetv.data.optLong
import com.nuvio.app.features.livetv.data.optString
import com.nuvio.app.features.livetv.data.parseBackupFile
import com.nuvio.app.features.livetv.data.parseObject
import com.nuvio.app.features.livetv.model.PlaylistSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Import and export of backup files, against a backup shaped like a real Nuvio TV's (the same
 * 127 keys and type tags: a second profile's "_p3" copies, Sports, a parental PIN, five Xtream
 * logins) with made-up values.
 */
class LiveTvBackupFileTest {

    private class TestStore : LiveTvPreferencesStore() {
        init { installPersistence(load = { null }, save = {}) }
    }

    private fun entry(t: String, v: JsonElement) = buildJsonObject { put("t", t); put("v", v) }
    private fun bool(v: Boolean) = entry("b", JsonPrimitive(v))
    private fun int(v: Int) = entry("i", JsonPrimitive(v))
    private fun long(v: Long) = entry("l", JsonPrimitive(v))
    private fun str(v: String) = entry("str", JsonPrimitive(v))
    private fun strSet(vararg v: String) = entry("s", buildJsonArray { v.forEach { add(it) } })

    private val playlists: List<PlaylistSource> = (1..5).map { n ->
        PlaylistSource(
            id = "pl-000$n-0000-0000-0000-00000000000$n", name = "Provider $n", url = "",
            xtreamServer = "http://xc$n.example.test:8080", xtreamUsername = "user$n", xtreamPassword = "pass$n",
            importVod = n % 2 == 0, importLive = true, streamFormat = "auto", logoSource = "epg"
        )
    }

    /** The TV's "data" map: every key it had, same type tag, invented values. */
    private fun tvData(): JsonObject = buildJsonObject {
        put(LiveTvKeys.playlists, str(LiveTvCodec.encodePlaylists(playlists)))
        listOf<Pair<String, JsonObject>>(
        "aspect_mode" to str("value-1"),
        "audio_passthrough" to bool(true),
        "auto_backup_channel" to bool(false),
        "auto_play_last" to bool(true),
        "auto_reconnect" to bool(false),
        "banner_seconds" to int(6),
        "bar_hidden" to str("value-7"),
        "browse_by_channel_name" to bool(true),
        "buffer_size" to str("value-9"),
        "calendar_country" to str("value-10"),
        "calendar_hide_spoilers" to bool(false),
        "calendar_hide_watched" to bool(true),
        "calendar_in_menu" to bool(false),
        "calendar_new_episode_cards" to bool(true),
        "calendar_ok_action" to str("value-15"),
        "calendar_skip_news_talk" to bool(true),
        "calendar_sports" to bool(false),
        "calendar_upcoming_row" to bool(true),
        "calendar_view" to str("value-19"),
        "captions_by_default" to bool(true),
        "catchup_cache" to bool(false),
        "catchup_prefer_hls" to bool(true),
        "catchup_shift_minutes" to int(23),
        "channel_sort" to str("value-24"),
        "channel_surf" to bool(false),
        "clock_24h" to bool(true),
        "clock_background" to bool(false),
        "clock_mode" to str("value-28"),
        "clock_opacity" to int(29),
        "clock_position" to str("value-30"),
        "clock_size" to str("value-31"),
        "compact_rows" to bool(true),
        "epg_future_days" to int(33),
        "epg_offset_minutes" to int(34),
        "epg_past_hours" to int(35),
        "epg_refresh_hours" to int(36),
        "frame_rate_mode" to str("value-37"),
        "fullscreen_on_select" to bool(true),
        "group_playlist_headings" to bool(false),
        "group_sort" to str("value-40"),
        "guide_backdrop" to str("value-41"),
        "guide_info_background" to str("value-42"),
        "guide_info_hidden" to str("value-43"),
        "guide_search_catalog" to bool(true),
        "guide_search_live" to bool(false),
        "guide_search_ondemand" to bool(true),
        "guide_size" to str("value-47"),
        "hidden_channels" to strSet("pl-0001-0000-0000-0000-000000000001:ch48", "pl-0002-0000-0000-0000-000000000002:ch48"),
        "hidden_channels_p3" to strSet("pl-0001-0000-0000-0000-000000000001:ch49", "pl-0002-0000-0000-0000-000000000002:ch49"),
        "home_row_above_cw" to bool(true),
        "home_row_compact" to bool(false),
        "home_row_enabled" to bool(true),
        "home_row_hidden_profiles" to strSet("pl-0001-0000-0000-0000-000000000001:ch53", "pl-0002-0000-0000-0000-000000000002:ch53"),
        "home_row_hide_placeholders" to bool(true),
        "home_row_limit" to int(55),
        "home_row_opens_guide" to bool(true),
        "home_row_show_next" to bool(false),
        "home_row_sort" to str("value-58"),
        "home_row_source" to str("value-59"),
        "home_row_title" to str("value-60"),
        "launcher_card_shape" to str("value-61"),
        "launcher_rows" to str("value-62"),
        "lock_adult_content" to bool(false),
        "logo_sources_off" to str("value-64"),
        "mark_playing_channel" to bool(false),
        "match_frame_rate" to bool(true),
        "name_removals" to str("value-67"),
        "on_demand_addon_posters" to bool(true),
        "on_demand_auto_play" to bool(false),
        "on_demand_in_sidebar" to bool(true),
        "on_demand_in_streams" to bool(false),
        "on_demand_streams_position" to str("value-72"),
        "open_on" to str("value-73"),
        "overlay_mode" to bool(true),
        "panel_background" to str("value-75"),
        "panel_row" to str("value-76"),
        "parental_pin" to str("1234"),
        "playlist_refresh_hours" to int(78),
        "profile_seeded_p3" to bool(true),
        "recent_in_panel" to bool(false),
        "record_post_minutes" to int(82),
        "record_pre_minutes" to int(83),
        "recording_space_rule" to str("value-84"),
        "recordings_in_sidebar" to bool(false),
        "remember_group" to bool(true),
        "resume_in_preview" to bool(false),
        "reverse_zap" to bool(true),
        "rewind_always" to bool(false),
        "search_order" to str("value-90"),
        "search_preview" to bool(false),
        "sequential_numbers" to bool(true),
        "show_all_group" to bool(false),
        "show_channel_logos" to bool(true),
        "show_channel_names" to bool(false),
        "show_channel_numbers" to bool(true),
        "show_episode_numbers" to bool(false),
        "show_favorites_group" to bool(true),
        "show_frame_rate" to bool(false),
        "show_group_counts" to bool(true),
        "show_in_search" to bool(false),
        "show_in_sidebar" to bool(true),
        "show_now_line" to bool(false),
        "show_playlist_in_info" to bool(true),
        "show_posters" to bool(false),
        "show_preview" to bool(true),
        "show_program_details" to bool(false),
        "show_program_times" to bool(true),
        "show_quality_badges" to bool(false),
        "show_recent_group" to bool(true),
        "side_menu_hidden" to str("value-111"),
        "side_menu_order" to str("value-112"),
        "small_header" to bool(false),
        "solid_highlight" to bool(true),
        "sports" to str("{\"v\": 1, \"follow\": [\"team-115\"]}"),
        "sports_mine" to str("{\"v\": 1, \"follow\": [\"team-116\"]}"),
        "sports_mine_p3" to str("{\"v\": 1, \"follow\": [\"team-117\"]}"),
        "start_on_live_tv" to bool(true),
        "start_page" to str("value-119"),
        "timeshift_enabled" to bool(true),
        "timeshift_minutes" to int(121),
        "vod_default_sort" to str("value-122"),
        "vod_merge_duplicates" to bool(false),
        "vod_search_catalog" to bool(true),
        "vod_search_live" to bool(false),
        "vod_search_ondemand" to bool(true),
        "zap_mode" to str("value-127"),
        ).forEach { (k, v) -> put(k, v) }
    }

    private fun tvFile(data: JsonObject = tvData(), format: Int = 1): String = buildJsonObject {
        put("format", format)
        put("savedAt", 1_760_000_000_000L)
        put("deviceId", "tv-0000-fixture")
        put("device", "Living room TV")
        put("data", data)
    }.toString()

    @Test
    fun parsesTheEnvelopeAndABareDataMap() {
        val info = parseBackupFile(tvFile()).getOrThrow()
        assertEquals(1_760_000_000_000L, info.savedAtMs)
        assertEquals("Living room TV", info.device)
        assertEquals("tv-0000-fixture", info.deviceId)
        assertEquals(127, info.keyCount)
        assertEquals(5, info.playlistCount)
        assertEquals(0, info.epgCount)
        assertTrue(info.hasSources)

        val bare = parseBackupFile(tvData().toString()).getOrThrow()
        assertEquals(0L, bare.savedAtMs)
        assertEquals("", bare.device)
        assertEquals(127, bare.keyCount)
        assertEquals(5, bare.playlistCount)

        // A missing format is the current one; an unknown newer one is refused.
        assertEquals(5, parseBackupFile("""{"data":${tvData()}}""").getOrThrow().playlistCount)
        assertEquals(BackupFileException.Reason.NEWER_FORMAT, reason(tvFile(format = 2)))
    }

    @Test
    fun refusesWhatIsNotABackup() {
        assertEquals(BackupFileException.Reason.NOT_JSON, reason("hello"))
        assertEquals(BackupFileException.Reason.NOT_JSON, reason("[1, 2]"))
        assertEquals(BackupFileException.Reason.NOT_BACKUP, reason("{}"))
        assertEquals(BackupFileException.Reason.NOT_BACKUP, reason("""{"name": "Nuvio", "version": 3}"""))
        assertEquals(BackupFileException.Reason.NOT_BACKUP, reason("""{"data": {"x": 1}}"""))
        assertEquals(BackupFileException.Reason.NOT_BACKUP, reason("""{"data": {"x": {"t": "zz", "v": 1}}}"""))
    }

    private fun reason(text: String) = assertIs<BackupFileException>(parseBackupFile(text).exceptionOrNull()).reason

    @Test
    fun importThenExportKeepsEveryKey() = runTest {
        val store = TestStore()
        val reloads = ArrayList<Set<String>>()
        val original = tvData()

        val summary = importBackupFile(tvFile(original), store) { reloads += it }.getOrThrow()
        assertEquals(5, summary.playlistCount)
        assertEquals(0, summary.epgCount)
        assertTrue(summary.changedPlaylists.isEmpty())
        assertEquals(1, reloads.size, "the repository is reloaded once")
        assertEquals(127, store.rawData.value.size)

        val exportedText = exportBackupFileText(store, deviceId = "phone-1", deviceName = "Pixel", nowMs = 42L)
        val env = parseObject(exportedText)
        assertEquals(1, env.optInt("format"))
        assertEquals(42L, env.optLong("savedAt"))
        assertEquals("phone-1", env.optString("deviceId"))
        assertEquals("Pixel", env.optString("device"))
        val exported = assertNotNull(env.optJSONObject("data"))

        // Every key came back with the same type tag and value (sets compared as sets).
        assertEquals(original.keys, exported.keys)
        for (k in original.keys) {
            val a = original[k]!!.jsonObject
            val b = exported[k]!!.jsonObject
            assertEquals(a["t"], b["t"], "type of $k")
            if (k == LiveTvKeys.playlists) continue
            val va = a["v"]!!
            val vb = b["v"]!!
            if (va is JsonArray) assertEquals(va.map { it.jsonPrimitive.content }.toSet(), (vb as JsonArray).map { it.jsonPrimitive.content }.toSet(), "set $k")
            else assertEquals(va, vb, "value of $k")
        }
        for (k in listOf("hidden_channels", "hidden_channels_p3", "sports", "sports_mine", "sports_mine_p3", "profile_seeded_p3", "parental_pin")) {
            assertTrue(k in exported, k)
        }
        assertEquals("1234", exported.optJSONObject("parental_pin")!!.optString("v"))

        val outPlaylists = LiveTvCodec.decodePlaylists(exported.optJSONObject(LiveTvKeys.playlists)!!.optString("v"))
        assertEquals(playlists.map { it.id }, outPlaylists.map { it.id })
        playlists.zip(outPlaylists).forEach { (i, o) ->
            assertEquals(i.xtreamServer, o.xtreamServer)
            assertEquals(i.xtreamUsername, o.xtreamUsername)
            assertEquals(i.xtreamPassword, o.xtreamPassword)
            assertEquals(i.importVod, o.importVod)
            assertEquals(0L, o.lastUpdatedMs); assertNull(o.lastError); assertEquals(0, o.channelCount)
        }

        // Importing the export again changes nothing, so nothing is reloaded.
        val again = importBackupFile(exportedText, store) { reloads += it }.getOrThrow()
        assertTrue(again.changedPlaylists.isEmpty())
        assertEquals(1, reloads.size)

        // The same file into another device gives the same export.
        val other = TestStore()
        importBackupFile(exportedText, other) {}.getOrThrow()
        assertEquals(exported.toString(), parseObject(exportBackupFileText(other, deviceId = "x", deviceName = "y", nowMs = 1L)).optJSONObject("data").toString())
    }

    @Test
    fun importReplacesTheSetupButKeepsPerDeviceState() = runTest {
        val store = TestStore()
        // This device had its own setup, something only it keeps, and a profile copy of a key.
        store.importFromSync(buildJsonObject {
            put(LiveTvKeys.playlists, str(LiveTvCodec.encodePlaylists(listOf(PlaylistSource(id = "old", name = "Old", url = "https://old.example.test/list.m3u")))))
            put(LiveTvKeys.favorites, str(LiveTvCodec.encodeStringList(listOf("old:1"))))
            put("favorites_p2", str(LiveTvCodec.encodeStringList(listOf("old:2"))))
        })
        store.recordWatched("old:1")

        val reloads = ArrayList<Set<String>>()
        importBackupFile(tvFile(), store) { reloads += it }.getOrThrow()
        val raw = store.rawData.value
        assertFalse(raw.containsKey(LiveTvKeys.favorites), "a key the backup doesn't have is removed")
        assertFalse(raw.containsKey("favorites_p2"))
        assertTrue(raw.containsKey(LiveTvKeys.lastChannel), "per-device keys stay")
        assertEquals(listOf("old:1"), store.currentUserState().recent)
        assertEquals(playlists.map { it.id }, store.currentPlaylists().map { it.id })
        assertEquals(1, reloads.size)
    }

    @Test
    fun importRefusesABackupWithoutSources() = runTest {
        val store = TestStore()
        val before = store.rawData.value
        val noSources = buildJsonObject { put("show_channel_names", bool(false)) }
        assertFalse(parseBackupFile(tvFile(noSources)).getOrThrow().hasSources)
        val e = assertIs<BackupFileException>(importBackupFile(tvFile(noSources), store) { error("must not reload") }.exceptionOrNull())
        assertEquals(BackupFileException.Reason.NO_SOURCES, e.reason)
        assertEquals(before, store.rawData.value)

        val bad = assertIs<BackupFileException>(importBackupFile("not json", store) { error("must not reload") }.exceptionOrNull())
        assertEquals(BackupFileException.Reason.NOT_JSON, bad.reason)
    }

    @Test
    fun applyReloadsOnlyWhenSomethingChanged() = runTest {
        val store = TestStore()
        val reloads = ArrayList<Set<String>>()
        val first = LiveTvBackupApply.apply(tvData(), store) { reloads += it }
        assertTrue(first.changedAnything)
        assertEquals(1, reloads.size)
        val second = LiveTvBackupApply.apply(tvData(), store) { reloads += it }
        assertFalse(second.changedAnything)
        assertEquals(1, reloads.size)
    }
}
