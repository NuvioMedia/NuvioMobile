package com.nuvio.app.features.player

import androidx.compose.ui.graphics.Color
import com.nuvio.app.features.player.skip.AutoSkipSegmentType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Player settings that Nuvio Auto overrides while Nuvio is on the car screen, layered over the profile's
 * saved settings (which are never changed). Each field is off/null unless its Nuvio Auto Setup toggle is on.
 */
object CarPlayerOverrides {

    /** What the steering wheel's next/previous track buttons do in the car. */
    enum class SteeringMode { DEFAULT, SEEK, EPISODES }

    data class Config(
        /** Video framing, e.g. Zoom to fill the wide car screen. */
        val resizeMode: PlayerResizeMode? = null,
        /** Load up to two minutes ahead, for tunnels and weak signal. */
        val bigBuffer: Boolean = false,
        /** Skip intros and recaps on their own. */
        val autoSkipIntros: Boolean = false,
        /** Start the next episode on its own, and look up its sources early. */
        val autoNextEpisode: Boolean = false,
        /** Subtitle size multiplier (1 = unchanged). */
        val subtitleScale: Float = 1f,
        /** Dark box behind subtitles, for sunlight. */
        val subtitleBackground: Boolean = false,
        val steering: SteeringMode = SteeringMode.DEFAULT,
    )

    private val _config = MutableStateFlow(Config())
    val config: StateFlow<Config> = _config.asStateFlow()

    /** Kept for the player screen's framing check. */
    private val _resizeMode = MutableStateFlow<PlayerResizeMode?>(null)
    val resizeMode: StateFlow<PlayerResizeMode?> = _resizeMode.asStateFlow()

    private val _nextEpisodeRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** "Next episode" from the steering wheel or the car buttons; the player screen handles it. */
    val nextEpisodeRequests: SharedFlow<Unit> = _nextEpisodeRequests.asSharedFlow()

    fun set(config: Config) {
        if (_config.value == config) return
        _config.value = config
        _resizeMode.value = config.resizeMode
        PlayerSettingsRepository.reapplyCarOverrides()
    }

    fun clear() = set(Config())

    fun setResizeMode(mode: PlayerResizeMode?) = set(_config.value.copy(resizeMode = mode))

    fun requestNextEpisode() {
        _nextEpisodeRequests.tryEmit(Unit)
    }

    internal fun overlay(raw: PlayerSettingsUiState): PlayerSettingsUiState {
        val c = _config.value
        if (c == Config()) return raw
        var s = raw
        c.resizeMode?.let { s = s.copy(resizeMode = it) }
        if (c.bigBuffer) {
            s = s.copy(
                bufferEngineEnabled = true,
                minBufferMs = maxOf(s.minBufferMs, 60_000),
                maxBufferMs = maxOf(s.maxBufferMs, 120_000),
                targetBufferSizeMb = maxOf(s.targetBufferSizeMb, 200),
            )
        }
        if (c.autoSkipIntros) {
            s = s.copy(
                skipIntroEnabled = true,
                autoSkipSegmentTypes = s.autoSkipSegmentTypes + setOf(AutoSkipSegmentType.INTRO, AutoSkipSegmentType.RECAP),
            )
        }
        if (c.autoNextEpisode) {
            s = s.copy(streamAutoPlayNextEpisodeEnabled = true, preloadNextEpisodeSources = true)
        }
        if (c.subtitleScale != 1f || c.subtitleBackground) {
            val style = s.subtitleStyle
            s = s.copy(
                subtitleStyle = style.copy(
                    fontSizeSp = (style.fontSizeSp * c.subtitleScale).toInt().coerceIn(10, 64),
                    backgroundColor = if (c.subtitleBackground && style.backgroundColor.alpha < 0.3f) {
                        Color.Black.copy(alpha = 0.6f)
                    } else {
                        style.backgroundColor
                    },
                ),
            )
        }
        return s
    }
}
