package com.nuvio.app.features.player

import android.app.Application
import android.content.Context
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncInt
import com.nuvio.app.core.sync.encodeSyncString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerLoadingSettingsTest {
    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_player_settings", Context.MODE_PRIVATE).edit().clear().commit()
        PlayerSettingsStorage.initialize(context)
        PlayerSettingsRepository.clearLocalState()
    }

    @AfterTest
    fun clearState() {
        PlayerSettingsRepository.clearLocalState()
    }

    @Test
    fun statusPreferenceSurvivesReloadAndSyncIndependentlyOfTheOverlay() {
        PlayerSettingsRepository.ensureLoaded()
        assertEquals(true, PlayerSettingsRepository.uiState.value.showPlayerLoadingStatus)
        PlayerSettingsRepository.setShowPlayerLoadingStatus(false)
        PlayerSettingsRepository.setShowLoadingOverlay(false)
        PlayerSettingsRepository.setShowLoadingOverlay(true)
        PlayerSettingsRepository.clearLocalState()
        PlayerSettingsRepository.ensureLoaded()
        assertEquals(false, PlayerSettingsRepository.uiState.value.showPlayerLoadingStatus)

        val payload = PlayerSettingsStorage.exportToSyncPayload()
        assertEquals(false, payload.decodeSyncBoolean("show_player_loading_status"))
        PlayerSettingsStorage.saveShowPlayerLoadingStatus(true)
        PlayerSettingsStorage.replaceFromSyncPayload(payload)
        assertEquals(false, PlayerSettingsStorage.loadShowPlayerLoadingStatus())
    }

    @Test
    fun olderSyncedSettingsRestoreTheDefaultOnlyForTheActiveProfile() {
        val preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("nuvio_player_settings", Context.MODE_PRIVATE)
        val otherKey = ProfileScopedKey.of("show_player_loading_status", 99)
        preferences.edit().putBoolean(otherKey, false).commit()
        PlayerSettingsStorage.saveShowPlayerLoadingStatus(false)
        PlayerSettingsStorage.replaceFromSyncPayload(buildJsonObject {})

        assertNull(PlayerSettingsStorage.loadShowPlayerLoadingStatus())
        assertEquals(false, preferences.getBoolean(otherKey, true))
        PlayerSettingsRepository.clearLocalState()
        PlayerSettingsRepository.ensureLoaded()
        assertEquals(true, PlayerSettingsRepository.uiState.value.showPlayerLoadingStatus)
    }

    @Test
    fun deviceBufferAndCacheSettingsStayLocalAcrossSync() {
        PlayerSettingsStorage.saveExoNativeMemoryEnabled(true)
        PlayerSettingsStorage.saveVodCacheEnabled(true)
        PlayerSettingsStorage.saveVodCacheSizeMode("manual")
        PlayerSettingsStorage.saveVodCacheSizeMb(800)
        PlayerSettingsStorage.saveBufferEngineEnabled(true)
        PlayerSettingsStorage.saveMinBufferMs(20_000)
        PlayerSettingsStorage.saveMaxBufferMs(60_000)
        PlayerSettingsStorage.saveBufferForPlaybackMs(4_000)
        PlayerSettingsStorage.saveBufferForPlaybackAfterRebufferMs(6_000)
        PlayerSettingsStorage.saveBackBufferDurationMs(10_000)
        PlayerSettingsStorage.saveTargetBufferSizeMb(120)

        val payload = PlayerSettingsStorage.exportToSyncPayload()
        listOf(
            "exo_native_memory_enabled",
            "vod_cache_enabled",
            "vod_cache_size_mode",
            "vod_cache_size_mb",
            "buffer_engine_enabled",
            "min_buffer_ms",
            "max_buffer_ms",
            "buffer_for_playback_ms",
            "buffer_for_playback_after_rebuffer_ms",
            "back_buffer_duration_ms",
            "target_buffer_size_mb",
        ).forEach { key ->
            assertFalse(payload.containsKey(key))
        }

        PlayerSettingsStorage.replaceFromSyncPayload(
            buildJsonObject {
                put("exo_native_memory_enabled", encodeSyncBoolean(false))
                put("vod_cache_enabled", encodeSyncBoolean(false))
                put("vod_cache_size_mode", encodeSyncString("auto"))
                put("vod_cache_size_mb", encodeSyncInt(100))
                put("buffer_engine_enabled", encodeSyncBoolean(false))
                put("min_buffer_ms", encodeSyncInt(1_000))
                put("max_buffer_ms", encodeSyncInt(2_000))
                put("buffer_for_playback_ms", encodeSyncInt(1_000))
                put("buffer_for_playback_after_rebuffer_ms", encodeSyncInt(1_000))
                put("back_buffer_duration_ms", encodeSyncInt(0))
                put("target_buffer_size_mb", encodeSyncInt(50))
            },
        )

        assertEquals(true, PlayerSettingsStorage.loadExoNativeMemoryEnabled())
        assertEquals(true, PlayerSettingsStorage.loadVodCacheEnabled())
        assertEquals("manual", PlayerSettingsStorage.loadVodCacheSizeMode())
        assertEquals(800, PlayerSettingsStorage.loadVodCacheSizeMb())
        assertEquals(true, PlayerSettingsStorage.loadBufferEngineEnabled())
        assertEquals(20_000, PlayerSettingsStorage.loadMinBufferMs())
        assertEquals(60_000, PlayerSettingsStorage.loadMaxBufferMs())
        assertEquals(4_000, PlayerSettingsStorage.loadBufferForPlaybackMs())
        assertEquals(6_000, PlayerSettingsStorage.loadBufferForPlaybackAfterRebufferMs())
        assertEquals(10_000, PlayerSettingsStorage.loadBackBufferDurationMs())
        assertEquals(120, PlayerSettingsStorage.loadTargetBufferSizeMb())
    }
}
