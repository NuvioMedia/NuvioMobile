package com.nuvio.app.features.player

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncFloat
import com.nuvio.app.core.sync.decodeSyncInt
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.decodeSyncStringSet
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncFloat
import com.nuvio.app.core.sync.encodeSyncInt
import com.nuvio.app.core.sync.encodeSyncString
import com.nuvio.app.core.sync.encodeSyncStringSet
import com.nuvio.app.core.storage.ProfileScopedKey
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

actual object PlayerSettingsStorage {
    private const val preferencesName = "nuvio_player_settings"
    private const val pendingExternalPlaybackKey = "pending_external_playback"
    private const val playbackBrightnessKey = "playback_brightness"
    private const val useLegacyPlayerLayoutKey = "use_legacy_player_layout"
    private const val showLoadingOverlayKey = "show_loading_overlay"
    private const val showPlayerLoadingStatusKey = "show_player_loading_status"
    private const val pauseOverlayEnabledKey = "pause_overlay_enabled"
    private const val showParentalGuideKey = "show_parental_guide"
    private const val resizeModeKey = "resize_mode"
    private const val holdToSpeedEnabledKey = "hold_to_speed_enabled"
    private const val holdToSpeedValueKey = "hold_to_speed_value"
    private const val touchGesturesEnabledKey = "touch_gestures_enabled"
    private const val externalPlayerEnabledKey = "external_player_enabled"
    private const val externalPlayerForwardSubtitlesKey = "external_player_forward_subtitles"
    private const val externalPlayerSendSkipSegmentsKey = "external_player_send_skip_segments"
    private const val externalPlayerIdKey = "external_player_id"
    private const val preferredAudioLanguageKey = "preferred_audio_language"
    private const val secondaryPreferredAudioLanguageKey = "secondary_preferred_audio_language"
    private const val preferredSubtitleLanguageKey = "preferred_subtitle_language"
    private const val secondaryPreferredSubtitleLanguageKey = "secondary_preferred_subtitle_language"
    private const val subtitleTextColorKey = "subtitle_text_color"
    private const val subtitleBackgroundColorKey = "subtitle_background_color"
    private const val subtitleOutlineColorKey = "subtitle_outline_color"
    private const val subtitleOutlineEnabledKey = "subtitle_outline_enabled"
    private const val subtitleOutlineWidthKey = "subtitle_outline_width"
    private const val subtitleBoldKey = "subtitle_bold"
    private const val subtitleFontSizeSpKey = "subtitle_font_size_sp"
    private const val subtitleBottomOffsetKey = "subtitle_bottom_offset"
    private const val subtitleStripSdhKey = "subtitle_strip_sdh"
    private const val subtitleUseForcedSubtitlesKey = "subtitle_use_forced_subtitles"
    private const val subtitleShowOnlyPreferredLanguagesKey = "subtitle_show_only_preferred_languages"
    private const val streamReuseLastLinkEnabledKey = "stream_reuse_last_link_enabled"
    private const val streamReuseLastLinkCacheHoursKey = "stream_reuse_last_link_cache_hours"
    private const val androidPlaybackEngineKey = "android_playback_engine"
    private const val androidLibmpvVideoOutputKey = "android_libmpv_video_output"
    private const val androidLibmpvHardwareDecodingEnabledKey = "android_libmpv_hardware_decoding_enabled"
    private const val androidLibmpvYuv420pEnabledKey = "android_libmpv_yuv420p_enabled"
    private const val decoderPriorityKey = "decoder_priority"
    private const val mapDV7ToHevcKey = "map_dv7_to_hevc"
    private const val tunnelingEnabledKey = "tunneling_enabled"
    private const val streamAutoPlayModeKey = "stream_auto_play_mode"
    private const val streamAutoPlaySourceKey = "stream_auto_play_source"
    private const val streamAutoPlaySelectedAddonsKey = "stream_auto_play_selected_addons"
    private const val streamAutoPlaySelectedPluginsKey = "stream_auto_play_selected_plugins"
    private const val streamAutoPlayRegexKey = "stream_auto_play_regex"
    private const val streamAutoPlayTimeoutSecondsKey = "stream_auto_play_timeout_seconds"
    private const val skipIntroEnabledKey = "skip_intro_enabled"
    private const val autoSkipMovieCreditsKey = "auto_skip_movie_credits"
    private const val autoSkipSegmentTypesKey = "auto_skip_segment_types"
    private const val autoSkipPostCreditsKey = "auto_skip_post_credits"
    private const val animeSkipEnabledKey = "animeskip_enabled"
    private const val animeSkipClientIdKey = "animeskip_client_id"
    private const val introDbApiKeyKey = "introdb_api_key"
    private const val introSubmitEnabledKey = "intro_submit_enabled"
    private const val streamAutoPlayNextEpisodeEnabledKey = "stream_auto_play_next_episode_enabled"
    private const val streamAutoPlayNextEpisodeFallbackEnabledKey = "stream_auto_play_next_episode_fallback_enabled"
    private const val streamAutoPlayPreferBingeGroupKey = "stream_auto_play_prefer_binge_group"
    private const val streamAutoPlayReuseBingeGroupKey = "stream_auto_play_reuse_binge_group"
    private const val nextEpisodeThresholdModeKey = "next_episode_threshold_mode"
    private const val nextEpisodeThresholdPercentKey = "next_episode_threshold_percent_v2"
    private const val nextEpisodeThresholdMinutesBeforeEndKey = "next_episode_threshold_minutes_before_end_v2"
    private const val useLibassKey = "use_libass"
    private const val libassRenderTypeKey = "libass_render_type"
    private const val iosVideoOutputPresetKey = "ios_video_output_preset"
    private const val iosToneMappingModeKey = "ios_tone_mapping_mode"
    private const val iosTargetPrimariesKey = "ios_target_primaries"
    private const val iosTargetTransferKey = "ios_target_transfer"
    private const val iosHardwareDecoderModeKey = "ios_hardware_decoder_mode"
    private const val iosAudioOutputModeKey = "ios_audio_output_mode"
    private const val iosExtendedDynamicRangeEnabledKey = "ios_extended_dynamic_range_enabled"
    private const val iosTargetColorspaceHintEnabledKey = "ios_target_colorspace_hint_enabled"
    private const val iosHdrComputePeakEnabledKey = "ios_hdr_compute_peak_enabled"
    private const val iosDebandEnabledKey = "ios_deband_enabled"
    private const val iosInterpolationEnabledKey = "ios_interpolation_enabled"
    private const val iosBrightnessKey = "ios_brightness"
    private const val iosContrastKey = "ios_contrast"
    private const val iosSaturationKey = "ios_saturation"
    private const val iosGammaKey = "ios_gamma"
    private val syncKeys = listOf(
        showLoadingOverlayKey,
        showPlayerLoadingStatusKey,
        pauseOverlayEnabledKey,
        showParentalGuideKey,
        resizeModeKey,
        holdToSpeedEnabledKey,
        holdToSpeedValueKey,
        touchGesturesEnabledKey,
        externalPlayerEnabledKey,
        externalPlayerForwardSubtitlesKey,
        externalPlayerSendSkipSegmentsKey,
        externalPlayerIdKey,
        preferredAudioLanguageKey,
        secondaryPreferredAudioLanguageKey,
        preferredSubtitleLanguageKey,
        secondaryPreferredSubtitleLanguageKey,
        subtitleTextColorKey,
        subtitleBackgroundColorKey,
        subtitleOutlineColorKey,
        subtitleOutlineEnabledKey,
        subtitleOutlineWidthKey,
        subtitleBoldKey,
        subtitleFontSizeSpKey,
        subtitleBottomOffsetKey,
        subtitleStripSdhKey,
        subtitleUseForcedSubtitlesKey,
        subtitleShowOnlyPreferredLanguagesKey,
        streamReuseLastLinkEnabledKey,
        streamReuseLastLinkCacheHoursKey,
        androidPlaybackEngineKey,
        androidLibmpvVideoOutputKey,
        androidLibmpvHardwareDecodingEnabledKey,
        androidLibmpvYuv420pEnabledKey,
        decoderPriorityKey,
        mapDV7ToHevcKey,
        tunnelingEnabledKey,
        streamAutoPlayModeKey,
        streamAutoPlaySourceKey,
        streamAutoPlaySelectedAddonsKey,
        streamAutoPlaySelectedPluginsKey,
        streamAutoPlayRegexKey,
        streamAutoPlayTimeoutSecondsKey,
        skipIntroEnabledKey,
        autoSkipMovieCreditsKey,
        autoSkipSegmentTypesKey,
        autoSkipPostCreditsKey,
        animeSkipEnabledKey,
        animeSkipClientIdKey,
        streamAutoPlayNextEpisodeEnabledKey,
        streamAutoPlayNextEpisodeFallbackEnabledKey,
        streamAutoPlayPreferBingeGroupKey,
        streamAutoPlayReuseBingeGroupKey,
        nextEpisodeThresholdModeKey,
        nextEpisodeThresholdPercentKey,
        nextEpisodeThresholdMinutesBeforeEndKey,
        useLibassKey,
        libassRenderTypeKey,
        iosVideoOutputPresetKey,
        iosToneMappingModeKey,
        iosTargetPrimariesKey,
        iosTargetTransferKey,
        iosHardwareDecoderModeKey,
        iosAudioOutputModeKey,
        iosExtendedDynamicRangeEnabledKey,
        iosTargetColorspaceHintEnabledKey,
        iosHdrComputePeakEnabledKey,
        iosDebandEnabledKey,
        iosInterpolationEnabledKey,
        iosBrightnessKey,
        iosContrastKey,
        iosSaturationKey,
        iosGammaKey,
    )

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadPendingExternalPlayback(): String? = preferences?.getString(pendingExternalPlaybackKey, null)

    actual fun savePendingExternalPlayback(value: String?) {
        preferences?.edit()?.putString(pendingExternalPlaybackKey, value)?.apply()
    }

    actual fun loadPlaybackBrightness(): Float? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(playbackBrightnessKey)
            if (sharedPreferences.contains(key)) sharedPreferences.getFloat(key, 0f) else null
        }

    actual fun savePlaybackBrightness(level: Float) {
        preferences?.edit()?.putFloat(ProfileScopedKey.of(playbackBrightnessKey), level)?.apply()
    }

    actual fun loadShowLoadingOverlay(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(showLoadingOverlayKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, true)
            } else {
                null
            }
        }

    actual fun saveShowLoadingOverlay(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(showLoadingOverlayKey), enabled)
            ?.apply()
    }

    actual fun loadShowPlayerLoadingStatus(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(showPlayerLoadingStatusKey)
            if (sharedPreferences.contains(key)) sharedPreferences.getBoolean(key, true) else null
        }

    actual fun saveShowPlayerLoadingStatus(enabled: Boolean) {
        preferences?.edit()?.putBoolean(ProfileScopedKey.of(showPlayerLoadingStatusKey), enabled)?.apply()
    }

    actual fun loadPauseOverlayEnabled(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(pauseOverlayEnabledKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, true)
            } else {
                null
            }
        }

    actual fun savePauseOverlayEnabled(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(pauseOverlayEnabledKey), enabled)
            ?.apply()
    }

    actual fun loadUseLegacyPlayerLayout(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(useLegacyPlayerLayoutKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, false)
            } else {
                null
            }
        }

    actual fun saveUseLegacyPlayerLayout(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(useLegacyPlayerLayoutKey), enabled)
            ?.apply()
    }

    actual fun loadShowParentalGuide(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(showParentalGuideKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, true)
            } else {
                null
            }
        }

    actual fun saveShowParentalGuide(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(showParentalGuideKey), enabled)
            ?.apply()
    }

    actual fun loadResizeMode(): String? =
        preferences?.getString(ProfileScopedKey.of(resizeModeKey), null)

    actual fun saveResizeMode(mode: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(resizeModeKey), mode)
            ?.apply()
    }

    actual fun loadHoldToSpeedEnabled(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(holdToSpeedEnabledKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, true)
            } else {
                null
            }
        }

    actual fun saveHoldToSpeedEnabled(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(holdToSpeedEnabledKey), enabled)
            ?.apply()
    }

    actual fun loadHoldToSpeedValue(): Float? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(holdToSpeedValueKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getFloat(key, 2f)
            } else {
                null
            }
        }

    actual fun saveHoldToSpeedValue(speed: Float) {
        preferences
            ?.edit()
            ?.putFloat(ProfileScopedKey.of(holdToSpeedValueKey), speed)
            ?.apply()
    }

    actual fun loadTouchGesturesEnabled(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(touchGesturesEnabledKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, true)
            } else {
                null
            }
        }

    actual fun saveTouchGesturesEnabled(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(touchGesturesEnabledKey), enabled)
            ?.apply()
    }

    actual fun loadExternalPlayerEnabled(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(externalPlayerEnabledKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, false)
            } else {
                null
            }
        }

    actual fun saveExternalPlayerEnabled(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(externalPlayerEnabledKey), enabled)
            ?.apply()
    }

    actual fun loadExternalPlayerForwardSubtitles(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(externalPlayerForwardSubtitlesKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, false)
            } else {
                null
            }
        }

    actual fun saveExternalPlayerForwardSubtitles(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(externalPlayerForwardSubtitlesKey), enabled)
            ?.apply()
    }

    actual fun loadExternalPlayerSendSkipSegments(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(externalPlayerSendSkipSegmentsKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, false)
            } else {
                null
            }
        }

    actual fun saveExternalPlayerSendSkipSegments(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(externalPlayerSendSkipSegmentsKey), enabled)
            ?.apply()
    }

    actual fun loadExternalPlayerId(): String? =
        preferences?.getString(ProfileScopedKey.of(externalPlayerIdKey), null)

    actual fun saveExternalPlayerId(playerId: String?) {
        preferences
            ?.edit()
            ?.apply {
                val key = ProfileScopedKey.of(externalPlayerIdKey)
                if (playerId.isNullOrBlank()) {
                    remove(key)
                } else {
                    putString(key, playerId)
                }
            }
            ?.apply()
    }

    actual fun loadPreferredAudioLanguage(): String? =
        preferences?.getString(ProfileScopedKey.of(preferredAudioLanguageKey), null)

    actual fun savePreferredAudioLanguage(language: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(preferredAudioLanguageKey), language)
            ?.apply()
    }

    actual fun loadSecondaryPreferredAudioLanguage(): String? =
        preferences?.getString(ProfileScopedKey.of(secondaryPreferredAudioLanguageKey), null)

    actual fun saveSecondaryPreferredAudioLanguage(language: String?) {
        preferences
            ?.edit()
            ?.apply {
                val key = ProfileScopedKey.of(secondaryPreferredAudioLanguageKey)
                if (language.isNullOrBlank()) {
                    remove(key)
                } else {
                    putString(key, language)
                }
            }
            ?.apply()
    }

    actual fun loadPreferredSubtitleLanguage(): String? =
        preferences?.getString(ProfileScopedKey.of(preferredSubtitleLanguageKey), null)

    actual fun savePreferredSubtitleLanguage(language: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(preferredSubtitleLanguageKey), language)
            ?.apply()
    }

    actual fun loadSecondaryPreferredSubtitleLanguage(): String? =
        preferences?.getString(ProfileScopedKey.of(secondaryPreferredSubtitleLanguageKey), null)

    actual fun saveSecondaryPreferredSubtitleLanguage(language: String?) {
        preferences
            ?.edit()
            ?.apply {
                val key = ProfileScopedKey.of(secondaryPreferredSubtitleLanguageKey)
                if (language.isNullOrBlank()) {
                    remove(key)
                } else {
                    putString(key, language)
                }
            }
            ?.apply()
    }

    actual fun loadSubtitleTextColor(): String? =
        preferences?.getString(ProfileScopedKey.of(subtitleTextColorKey), null)

    actual fun saveSubtitleTextColor(colorHex: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(subtitleTextColorKey), colorHex)
            ?.apply()
    }

    actual fun loadSubtitleBackgroundColor(): String? =
        preferences?.getString(ProfileScopedKey.of(subtitleBackgroundColorKey), null)

    actual fun saveSubtitleBackgroundColor(colorHex: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(subtitleBackgroundColorKey), colorHex)
            ?.apply()
    }

    actual fun loadSubtitleOutlineColor(): String? =
        preferences?.getString(ProfileScopedKey.of(subtitleOutlineColorKey), null)

    actual fun saveSubtitleOutlineColor(colorHex: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(subtitleOutlineColorKey), colorHex)
            ?.apply()
    }

    actual fun loadSubtitleOutlineEnabled(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(subtitleOutlineEnabledKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, SubtitleStyleState.DEFAULT.outlineEnabled)
            } else {
                null
            }
        }

    actual fun saveSubtitleOutlineEnabled(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(subtitleOutlineEnabledKey), enabled)
            ?.apply()
    }

    actual fun loadSubtitleOutlineWidth(): Int? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(subtitleOutlineWidthKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getInt(key, SubtitleStyleState.DEFAULT.outlineWidth)
            } else {
                null
            }
        }

    actual fun saveSubtitleOutlineWidth(width: Int) {
        preferences
            ?.edit()
            ?.putInt(ProfileScopedKey.of(subtitleOutlineWidthKey), width)
            ?.apply()
    }

    actual fun loadSubtitleBold(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(subtitleBoldKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, SubtitleStyleState.DEFAULT.bold)
            } else {
                null
            }
        }

    actual fun saveSubtitleBold(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(subtitleBoldKey), enabled)
            ?.apply()
    }

    actual fun loadSubtitleFontSizeSp(): Int? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(subtitleFontSizeSpKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getInt(key, SubtitleStyleState.DEFAULT.fontSizeSp)
            } else {
                null
            }
        }

    actual fun saveSubtitleFontSizeSp(fontSizeSp: Int) {
        preferences
            ?.edit()
            ?.putInt(ProfileScopedKey.of(subtitleFontSizeSpKey), fontSizeSp)
            ?.apply()
    }

    actual fun loadSubtitleBottomOffset(): Int? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(subtitleBottomOffsetKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getInt(key, SubtitleStyleState.DEFAULT.bottomOffset)
            } else {
                null
            }
        }

    actual fun saveSubtitleBottomOffset(bottomOffset: Int) {
        preferences
            ?.edit()
            ?.putInt(ProfileScopedKey.of(subtitleBottomOffsetKey), bottomOffset)
            ?.apply()
    }

    actual fun loadSubtitleStripSdh(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(subtitleStripSdhKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, SubtitleStyleState.DEFAULT.stripSdh)
            } else {
                null
            }
        }

    actual fun saveSubtitleStripSdh(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(subtitleStripSdhKey), enabled)
            ?.apply()
    }

    actual fun loadSubtitleUseForcedSubtitles(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(subtitleUseForcedSubtitlesKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, SubtitleStyleState.DEFAULT.useForcedSubtitles)
            } else {
                null
            }
        }

    actual fun saveSubtitleUseForcedSubtitles(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(subtitleUseForcedSubtitlesKey), enabled)
            ?.apply()
    }

    actual fun loadSubtitleShowOnlyPreferredLanguages(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(subtitleShowOnlyPreferredLanguagesKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, SubtitleStyleState.DEFAULT.showOnlyPreferredLanguages)
            } else {
                null
            }
        }

    actual fun saveSubtitleShowOnlyPreferredLanguages(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(subtitleShowOnlyPreferredLanguagesKey), enabled)
            ?.apply()
    }

    actual fun loadStreamReuseLastLinkEnabled(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(streamReuseLastLinkEnabledKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, false)
            } else {
                null
            }
        }

    actual fun saveStreamReuseLastLinkEnabled(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(streamReuseLastLinkEnabledKey), enabled)
            ?.apply()
    }

    actual fun loadStreamReuseLastLinkCacheHours(): Int? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(streamReuseLastLinkCacheHoursKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getInt(key, 24)
            } else {
                null
            }
        }

    actual fun saveStreamReuseLastLinkCacheHours(hours: Int) {
        preferences
            ?.edit()
            ?.putInt(ProfileScopedKey.of(streamReuseLastLinkCacheHoursKey), hours)
            ?.apply()
    }

    actual fun loadAndroidPlaybackEngine(): String? =
        preferences?.getString(ProfileScopedKey.of(androidPlaybackEngineKey), null)

    actual fun saveAndroidPlaybackEngine(engine: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(androidPlaybackEngineKey), engine)
            ?.apply()
    }

    actual fun loadAndroidLibmpvVideoOutput(): String? =
        preferences?.getString(ProfileScopedKey.of(androidLibmpvVideoOutputKey), null)

    actual fun saveAndroidLibmpvVideoOutput(output: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(androidLibmpvVideoOutputKey), output)
            ?.apply()
    }

    actual fun loadAndroidLibmpvHardwareDecodingEnabled(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(androidLibmpvHardwareDecodingEnabledKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, true)
            } else {
                null
            }
        }

    actual fun saveAndroidLibmpvHardwareDecodingEnabled(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(androidLibmpvHardwareDecodingEnabledKey), enabled)
            ?.apply()
    }

    actual fun loadAndroidLibmpvYuv420pEnabled(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(androidLibmpvYuv420pEnabledKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, false)
            } else {
                null
            }
        }

    actual fun saveAndroidLibmpvYuv420pEnabled(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(androidLibmpvYuv420pEnabledKey), enabled)
            ?.apply()
    }

    actual fun loadDecoderPriority(): Int? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(decoderPriorityKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getInt(key, 1)
            } else {
                null
            }
        }

    actual fun saveDecoderPriority(priority: Int) {
        preferences
            ?.edit()
            ?.putInt(ProfileScopedKey.of(decoderPriorityKey), priority)
            ?.apply()
    }

    actual fun loadMapDV7ToHevc(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(mapDV7ToHevcKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, false)
            } else {
                null
            }
        }

    actual fun saveMapDV7ToHevc(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(mapDV7ToHevcKey), enabled)
            ?.apply()
    }

    actual fun loadTunnelingEnabled(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(tunnelingEnabledKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, false)
            } else {
                null
            }
        }

    actual fun saveTunnelingEnabled(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(tunnelingEnabledKey), enabled)
            ?.apply()
    }

    actual fun loadStreamAutoPlayMode(): String? =
        preferences?.getString(ProfileScopedKey.of(streamAutoPlayModeKey), null)

    actual fun saveStreamAutoPlayMode(mode: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(streamAutoPlayModeKey), mode)
            ?.apply()
    }

    actual fun loadStreamAutoPlaySource(): String? =
        preferences?.getString(ProfileScopedKey.of(streamAutoPlaySourceKey), null)

    actual fun saveStreamAutoPlaySource(source: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(streamAutoPlaySourceKey), source)
            ?.apply()
    }

    actual fun loadStreamAutoPlaySelectedAddons(): Set<String>? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(streamAutoPlaySelectedAddonsKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getStringSet(key, emptySet()) ?: emptySet()
            } else {
                null
            }
        }

    actual fun saveStreamAutoPlaySelectedAddons(addons: Set<String>) {
        preferences
            ?.edit()
            ?.putStringSet(ProfileScopedKey.of(streamAutoPlaySelectedAddonsKey), addons)
            ?.apply()
    }

    actual fun loadStreamAutoPlaySelectedPlugins(): Set<String>? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(streamAutoPlaySelectedPluginsKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getStringSet(key, emptySet()) ?: emptySet()
            } else {
                null
            }
        }

    actual fun saveStreamAutoPlaySelectedPlugins(plugins: Set<String>) {
        preferences
            ?.edit()
            ?.putStringSet(ProfileScopedKey.of(streamAutoPlaySelectedPluginsKey), plugins)
            ?.apply()
    }

    actual fun loadStreamAutoPlayRegex(): String? =
        preferences?.getString(ProfileScopedKey.of(streamAutoPlayRegexKey), null)

    actual fun saveStreamAutoPlayRegex(regex: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(streamAutoPlayRegexKey), regex)
            ?.apply()
    }

    actual fun loadStreamAutoPlayTimeoutSeconds(): Int? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(streamAutoPlayTimeoutSecondsKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getInt(key, 3)
            } else {
                null
            }
        }

    actual fun saveStreamAutoPlayTimeoutSeconds(seconds: Int) {
        preferences
            ?.edit()
            ?.putInt(ProfileScopedKey.of(streamAutoPlayTimeoutSecondsKey), seconds)
            ?.apply()
    }

    actual fun loadSkipIntroEnabled(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(skipIntroEnabledKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, true)
            } else {
                null
            }
        }

    actual fun loadAutoSkipSegmentTypes(): Set<String>? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(autoSkipSegmentTypesKey)
            if (sharedPreferences.contains(key)) sharedPreferences.getStringSet(key, emptySet())?.toSet() else null
        }

    actual fun saveAutoSkipSegmentTypes(types: Set<String>) {
        preferences?.edit()?.putStringSet(ProfileScopedKey.of(autoSkipSegmentTypesKey), types)?.apply()
    }

    actual fun loadAutoSkipMovieCredits(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(autoSkipMovieCreditsKey)
            if (sharedPreferences.contains(key)) sharedPreferences.getBoolean(key, false) else null
        }

    actual fun saveAutoSkipMovieCredits(enabled: Boolean) {
        preferences?.edit()?.putBoolean(ProfileScopedKey.of(autoSkipMovieCreditsKey), enabled)?.apply()
    }

    actual fun loadAutoSkipPostCredits(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(autoSkipPostCreditsKey)
            if (sharedPreferences.contains(key)) sharedPreferences.getBoolean(key, false) else null
        }

    actual fun saveAutoSkipPostCredits(enabled: Boolean) {
        preferences?.edit()?.putBoolean(ProfileScopedKey.of(autoSkipPostCreditsKey), enabled)?.apply()
    }


    actual fun saveSkipIntroEnabled(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(skipIntroEnabledKey), enabled)
            ?.apply()
    }

    actual fun loadAnimeSkipEnabled(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(animeSkipEnabledKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, false)
            } else {
                null
            }
        }

    actual fun saveAnimeSkipEnabled(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(animeSkipEnabledKey), enabled)
            ?.apply()
    }

    actual fun loadAnimeSkipClientId(): String? =
        preferences?.getString(ProfileScopedKey.of(animeSkipClientIdKey), null)

    actual fun saveAnimeSkipClientId(clientId: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(animeSkipClientIdKey), clientId)
            ?.apply()
    }

    actual fun loadIntroDbApiKey(): String? =
        preferences?.getString(ProfileScopedKey.of(introDbApiKeyKey), null)

    actual fun saveIntroDbApiKey(apiKey: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(introDbApiKeyKey), apiKey)
            ?.apply()
    }

    actual fun loadIntroSubmitEnabled(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(introSubmitEnabledKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, false)
            } else {
                null
            }
        }

    actual fun saveIntroSubmitEnabled(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(introSubmitEnabledKey), enabled)
            ?.apply()
    }

    actual fun loadStreamAutoPlayNextEpisodeEnabled(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(streamAutoPlayNextEpisodeEnabledKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, false)
            } else {
                null
            }
        }

    actual fun saveStreamAutoPlayNextEpisodeEnabled(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(streamAutoPlayNextEpisodeEnabledKey), enabled)
            ?.apply()
    }

    actual fun loadStreamAutoPlayNextEpisodeFallbackEnabled(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(streamAutoPlayNextEpisodeFallbackEnabledKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, true)
            } else {
                null
            }
        }

    actual fun saveStreamAutoPlayNextEpisodeFallbackEnabled(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(streamAutoPlayNextEpisodeFallbackEnabledKey), enabled)
            ?.apply()
    }

    actual fun loadStreamAutoPlayPreferBingeGroup(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(streamAutoPlayPreferBingeGroupKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, true)
            } else {
                null
            }
        }

    actual fun saveStreamAutoPlayPreferBingeGroup(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(streamAutoPlayPreferBingeGroupKey), enabled)
            ?.apply()
    }

    actual fun loadStreamAutoPlayReuseBingeGroup(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(streamAutoPlayReuseBingeGroupKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, false)
            } else {
                null
            }
        }

    actual fun saveStreamAutoPlayReuseBingeGroup(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(streamAutoPlayReuseBingeGroupKey), enabled)
            ?.apply()
    }

    actual fun loadNextEpisodeThresholdMode(): String? =
        preferences?.getString(ProfileScopedKey.of(nextEpisodeThresholdModeKey), null)

    actual fun saveNextEpisodeThresholdMode(mode: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(nextEpisodeThresholdModeKey), mode)
            ?.apply()
    }

    actual fun loadNextEpisodeThresholdPercent(): Float? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(nextEpisodeThresholdPercentKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getFloat(key, 99f)
            } else {
                null
            }
        }

    actual fun saveNextEpisodeThresholdPercent(percent: Float) {
        preferences
            ?.edit()
            ?.putFloat(ProfileScopedKey.of(nextEpisodeThresholdPercentKey), percent)
            ?.apply()
    }

    actual fun loadNextEpisodeThresholdMinutesBeforeEnd(): Float? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(nextEpisodeThresholdMinutesBeforeEndKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getFloat(key, 2f)
            } else {
                null
            }
        }

    actual fun saveNextEpisodeThresholdMinutesBeforeEnd(minutes: Float) {
        preferences
            ?.edit()
            ?.putFloat(ProfileScopedKey.of(nextEpisodeThresholdMinutesBeforeEndKey), minutes)
            ?.apply()
    }

    actual fun loadUseLibass(): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(useLibassKey)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, false)
            } else {
                null
            }
        }

    actual fun saveUseLibass(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(useLibassKey), enabled)
            ?.apply()
    }

    actual fun loadLibassRenderType(): String? =
        preferences?.getString(ProfileScopedKey.of(libassRenderTypeKey), null)

    actual fun saveLibassRenderType(renderType: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(libassRenderTypeKey), renderType)
            ?.apply()
    }

    actual fun loadIosVideoOutputPreset(): String? =
        preferences?.getString(ProfileScopedKey.of(iosVideoOutputPresetKey), null)

    actual fun saveIosVideoOutputPreset(preset: String) {
        preferences?.edit()?.putString(ProfileScopedKey.of(iosVideoOutputPresetKey), preset)?.apply()
    }

    actual fun loadIosToneMappingMode(): String? =
        preferences?.getString(ProfileScopedKey.of(iosToneMappingModeKey), null)

    actual fun saveIosToneMappingMode(mode: String) {
        preferences?.edit()?.putString(ProfileScopedKey.of(iosToneMappingModeKey), mode)?.apply()
    }

    actual fun loadIosTargetPrimaries(): String? =
        preferences?.getString(ProfileScopedKey.of(iosTargetPrimariesKey), null)

    actual fun saveIosTargetPrimaries(primaries: String) {
        preferences?.edit()?.putString(ProfileScopedKey.of(iosTargetPrimariesKey), primaries)?.apply()
    }

    actual fun loadIosTargetTransfer(): String? =
        preferences?.getString(ProfileScopedKey.of(iosTargetTransferKey), null)

    actual fun saveIosTargetTransfer(transfer: String) {
        preferences?.edit()?.putString(ProfileScopedKey.of(iosTargetTransferKey), transfer)?.apply()
    }

    actual fun loadIosHardwareDecoderMode(): String? =
        preferences?.getString(ProfileScopedKey.of(iosHardwareDecoderModeKey), null)

    actual fun saveIosHardwareDecoderMode(mode: String) {
        preferences?.edit()?.putString(ProfileScopedKey.of(iosHardwareDecoderModeKey), mode)?.apply()
    }

    actual fun loadIosAudioOutputMode(): String? =
        preferences?.getString(ProfileScopedKey.of(iosAudioOutputModeKey), null)

    actual fun saveIosAudioOutputMode(mode: String) {
        preferences?.edit()?.putString(ProfileScopedKey.of(iosAudioOutputModeKey), mode)?.apply()
    }

    private fun loadIosBoolean(keyBase: String, defaultValue: Boolean): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(keyBase)
            if (sharedPreferences.contains(key)) sharedPreferences.getBoolean(key, defaultValue) else null
        }

    private fun saveIosBoolean(keyBase: String, enabled: Boolean) {
        preferences?.edit()?.putBoolean(ProfileScopedKey.of(keyBase), enabled)?.apply()
    }

    private fun loadIosInt(keyBase: String): Int? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(keyBase)
            if (sharedPreferences.contains(key)) sharedPreferences.getInt(key, 0) else null
        }

    private fun saveIosInt(keyBase: String, value: Int) {
        preferences?.edit()?.putInt(ProfileScopedKey.of(keyBase), value)?.apply()
    }

    actual fun loadIosExtendedDynamicRangeEnabled(): Boolean? =
        loadIosBoolean(iosExtendedDynamicRangeEnabledKey, true)

    actual fun saveIosExtendedDynamicRangeEnabled(enabled: Boolean) {
        saveIosBoolean(iosExtendedDynamicRangeEnabledKey, enabled)
    }

    actual fun loadIosTargetColorspaceHintEnabled(): Boolean? =
        loadIosBoolean(iosTargetColorspaceHintEnabledKey, true)

    actual fun saveIosTargetColorspaceHintEnabled(enabled: Boolean) {
        saveIosBoolean(iosTargetColorspaceHintEnabledKey, enabled)
    }

    actual fun loadIosHdrComputePeakEnabled(): Boolean? =
        loadIosBoolean(iosHdrComputePeakEnabledKey, true)

    actual fun saveIosHdrComputePeakEnabled(enabled: Boolean) {
        saveIosBoolean(iosHdrComputePeakEnabledKey, enabled)
    }

    actual fun loadIosDebandEnabled(): Boolean? =
        loadIosBoolean(iosDebandEnabledKey, false)

    actual fun saveIosDebandEnabled(enabled: Boolean) {
        saveIosBoolean(iosDebandEnabledKey, enabled)
    }

    actual fun loadIosInterpolationEnabled(): Boolean? =
        loadIosBoolean(iosInterpolationEnabledKey, false)

    actual fun saveIosInterpolationEnabled(enabled: Boolean) {
        saveIosBoolean(iosInterpolationEnabledKey, enabled)
    }

    actual fun loadIosBrightness(): Int? = loadIosInt(iosBrightnessKey)

    actual fun saveIosBrightness(value: Int) {
        saveIosInt(iosBrightnessKey, value)
    }

    actual fun loadIosContrast(): Int? = loadIosInt(iosContrastKey)

    actual fun saveIosContrast(value: Int) {
        saveIosInt(iosContrastKey, value)
    }

    actual fun loadIosSaturation(): Int? = loadIosInt(iosSaturationKey)

    actual fun saveIosSaturation(value: Int) {
        saveIosInt(iosSaturationKey, value)
    }

    actual fun loadIosGamma(): Int? = loadIosInt(iosGammaKey)

    actual fun saveIosGamma(value: Int) {
        saveIosInt(iosGammaKey, value)
    }

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadShowLoadingOverlay()?.let { put(showLoadingOverlayKey, encodeSyncBoolean(it)) }
        loadShowPlayerLoadingStatus()?.let { put(showPlayerLoadingStatusKey, encodeSyncBoolean(it)) }
        loadPauseOverlayEnabled()?.let { put(pauseOverlayEnabledKey, encodeSyncBoolean(it)) }
        loadShowParentalGuide()?.let { put(showParentalGuideKey, encodeSyncBoolean(it)) }
        loadResizeMode()?.let { put(resizeModeKey, encodeSyncString(it)) }
        loadHoldToSpeedEnabled()?.let { put(holdToSpeedEnabledKey, encodeSyncBoolean(it)) }
        loadHoldToSpeedValue()?.let { put(holdToSpeedValueKey, encodeSyncFloat(it)) }
        loadTouchGesturesEnabled()?.let { put(touchGesturesEnabledKey, encodeSyncBoolean(it)) }
        loadExternalPlayerEnabled()?.let { put(externalPlayerEnabledKey, encodeSyncBoolean(it)) }
        loadExternalPlayerForwardSubtitles()?.let { put(externalPlayerForwardSubtitlesKey, encodeSyncBoolean(it)) }
        loadExternalPlayerSendSkipSegments()?.let { put(externalPlayerSendSkipSegmentsKey, encodeSyncBoolean(it)) }
        loadExternalPlayerId()?.let { put(externalPlayerIdKey, encodeSyncString(it)) }
        loadPreferredAudioLanguage()?.let { put(preferredAudioLanguageKey, encodeSyncString(it)) }
        loadSecondaryPreferredAudioLanguage()?.let { put(secondaryPreferredAudioLanguageKey, encodeSyncString(it)) }
        loadPreferredSubtitleLanguage()?.let { put(preferredSubtitleLanguageKey, encodeSyncString(it)) }
        loadSecondaryPreferredSubtitleLanguage()?.let { put(secondaryPreferredSubtitleLanguageKey, encodeSyncString(it)) }
        loadSubtitleTextColor()?.let { put(subtitleTextColorKey, encodeSyncString(it)) }
        loadSubtitleBackgroundColor()?.let { put(subtitleBackgroundColorKey, encodeSyncString(it)) }
        loadSubtitleOutlineColor()?.let { put(subtitleOutlineColorKey, encodeSyncString(it)) }
        loadSubtitleOutlineEnabled()?.let { put(subtitleOutlineEnabledKey, encodeSyncBoolean(it)) }
        loadSubtitleOutlineWidth()?.let { put(subtitleOutlineWidthKey, encodeSyncInt(it)) }
        loadSubtitleBold()?.let { put(subtitleBoldKey, encodeSyncBoolean(it)) }
        loadSubtitleFontSizeSp()?.let { put(subtitleFontSizeSpKey, encodeSyncInt(it)) }
        loadSubtitleBottomOffset()?.let { put(subtitleBottomOffsetKey, encodeSyncInt(it)) }
        loadSubtitleStripSdh()?.let { put(subtitleStripSdhKey, encodeSyncBoolean(it)) }
        loadSubtitleUseForcedSubtitles()?.let { put(subtitleUseForcedSubtitlesKey, encodeSyncBoolean(it)) }
        loadSubtitleShowOnlyPreferredLanguages()?.let { put(subtitleShowOnlyPreferredLanguagesKey, encodeSyncBoolean(it)) }
        loadStreamReuseLastLinkEnabled()?.let { put(streamReuseLastLinkEnabledKey, encodeSyncBoolean(it)) }
        loadStreamReuseLastLinkCacheHours()?.let { put(streamReuseLastLinkCacheHoursKey, encodeSyncInt(it)) }
        loadAndroidPlaybackEngine()?.let { put(androidPlaybackEngineKey, encodeSyncString(it)) }
        loadAndroidLibmpvVideoOutput()?.let { put(androidLibmpvVideoOutputKey, encodeSyncString(it)) }
        loadAndroidLibmpvHardwareDecodingEnabled()?.let {
            put(androidLibmpvHardwareDecodingEnabledKey, encodeSyncBoolean(it))
        }
        loadAndroidLibmpvYuv420pEnabled()?.let { put(androidLibmpvYuv420pEnabledKey, encodeSyncBoolean(it)) }
        loadDecoderPriority()?.let { put(decoderPriorityKey, encodeSyncInt(it)) }
        loadMapDV7ToHevc()?.let { put(mapDV7ToHevcKey, encodeSyncBoolean(it)) }
        loadTunnelingEnabled()?.let { put(tunnelingEnabledKey, encodeSyncBoolean(it)) }
        loadStreamAutoPlayMode()?.let { put(streamAutoPlayModeKey, encodeSyncString(it)) }
        loadStreamAutoPlaySource()?.let { put(streamAutoPlaySourceKey, encodeSyncString(it)) }
        loadStreamAutoPlaySelectedAddons()?.let { put(streamAutoPlaySelectedAddonsKey, encodeSyncStringSet(it)) }
        loadStreamAutoPlaySelectedPlugins()?.let { put(streamAutoPlaySelectedPluginsKey, encodeSyncStringSet(it)) }
        loadStreamAutoPlayRegex()?.let { put(streamAutoPlayRegexKey, encodeSyncString(it)) }
        loadStreamAutoPlayTimeoutSeconds()?.let { put(streamAutoPlayTimeoutSecondsKey, encodeSyncInt(it)) }
        loadSkipIntroEnabled()?.let { put(skipIntroEnabledKey, encodeSyncBoolean(it)) }
        loadAutoSkipMovieCredits()?.let { put(autoSkipMovieCreditsKey, encodeSyncBoolean(it)) }
        loadAutoSkipSegmentTypes()?.let { put(autoSkipSegmentTypesKey, encodeSyncStringSet(it)) }
        loadAutoSkipPostCredits()?.let { put(autoSkipPostCreditsKey, encodeSyncBoolean(it)) }
        loadAnimeSkipEnabled()?.let { put(animeSkipEnabledKey, encodeSyncBoolean(it)) }
        loadAnimeSkipClientId()?.let { put(animeSkipClientIdKey, encodeSyncString(it)) }
        loadStreamAutoPlayNextEpisodeEnabled()?.let { put(streamAutoPlayNextEpisodeEnabledKey, encodeSyncBoolean(it)) }
        loadStreamAutoPlayNextEpisodeFallbackEnabled()?.let { put(streamAutoPlayNextEpisodeFallbackEnabledKey, encodeSyncBoolean(it)) }
        loadStreamAutoPlayPreferBingeGroup()?.let { put(streamAutoPlayPreferBingeGroupKey, encodeSyncBoolean(it)) }
        loadStreamAutoPlayReuseBingeGroup()?.let { put(streamAutoPlayReuseBingeGroupKey, encodeSyncBoolean(it)) }
        loadNextEpisodeThresholdMode()?.let { put(nextEpisodeThresholdModeKey, encodeSyncString(it)) }
        loadNextEpisodeThresholdPercent()?.let { put(nextEpisodeThresholdPercentKey, encodeSyncFloat(it)) }
        loadNextEpisodeThresholdMinutesBeforeEnd()?.let { put(nextEpisodeThresholdMinutesBeforeEndKey, encodeSyncFloat(it)) }
        loadUseLibass()?.let { put(useLibassKey, encodeSyncBoolean(it)) }
        loadLibassRenderType()?.let { put(libassRenderTypeKey, encodeSyncString(it)) }
        loadIosVideoOutputPreset()?.let { put(iosVideoOutputPresetKey, encodeSyncString(it)) }
        loadIosToneMappingMode()?.let { put(iosToneMappingModeKey, encodeSyncString(it)) }
        loadIosTargetPrimaries()?.let { put(iosTargetPrimariesKey, encodeSyncString(it)) }
        loadIosTargetTransfer()?.let { put(iosTargetTransferKey, encodeSyncString(it)) }
        loadIosHardwareDecoderMode()?.let { put(iosHardwareDecoderModeKey, encodeSyncString(it)) }
        loadIosAudioOutputMode()?.let { put(iosAudioOutputModeKey, encodeSyncString(it)) }
        loadIosExtendedDynamicRangeEnabled()?.let { put(iosExtendedDynamicRangeEnabledKey, encodeSyncBoolean(it)) }
        loadIosTargetColorspaceHintEnabled()?.let { put(iosTargetColorspaceHintEnabledKey, encodeSyncBoolean(it)) }
        loadIosHdrComputePeakEnabled()?.let { put(iosHdrComputePeakEnabledKey, encodeSyncBoolean(it)) }
        loadIosDebandEnabled()?.let { put(iosDebandEnabledKey, encodeSyncBoolean(it)) }
        loadIosInterpolationEnabled()?.let { put(iosInterpolationEnabledKey, encodeSyncBoolean(it)) }
        loadIosBrightness()?.let { put(iosBrightnessKey, encodeSyncInt(it)) }
        loadIosContrast()?.let { put(iosContrastKey, encodeSyncInt(it)) }
        loadIosSaturation()?.let { put(iosSaturationKey, encodeSyncInt(it)) }
        loadIosGamma()?.let { put(iosGammaKey, encodeSyncInt(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        preferences?.edit()?.apply {
            syncKeys.forEach { remove(ProfileScopedKey.of(it)) }
        }?.apply()

        payload.decodeSyncBoolean(showLoadingOverlayKey)?.let(::saveShowLoadingOverlay)
        payload.decodeSyncBoolean(showPlayerLoadingStatusKey)?.let(::saveShowPlayerLoadingStatus)
        payload.decodeSyncBoolean(pauseOverlayEnabledKey)?.let(::savePauseOverlayEnabled)
        payload.decodeSyncBoolean(showParentalGuideKey)?.let(::saveShowParentalGuide)
        payload.decodeSyncString(resizeModeKey)?.let(::saveResizeMode)
        payload.decodeSyncBoolean(holdToSpeedEnabledKey)?.let(::saveHoldToSpeedEnabled)
        payload.decodeSyncFloat(holdToSpeedValueKey)?.let(::saveHoldToSpeedValue)
        payload.decodeSyncBoolean(touchGesturesEnabledKey)?.let(::saveTouchGesturesEnabled)
        payload.decodeSyncBoolean(externalPlayerEnabledKey)?.let(::saveExternalPlayerEnabled)
        payload.decodeSyncBoolean(externalPlayerForwardSubtitlesKey)?.let(::saveExternalPlayerForwardSubtitles)
        payload.decodeSyncBoolean(externalPlayerSendSkipSegmentsKey)?.let(::saveExternalPlayerSendSkipSegments)
        payload.decodeSyncString(externalPlayerIdKey)?.let(::saveExternalPlayerId)
        payload.decodeSyncString(preferredAudioLanguageKey)?.let(::savePreferredAudioLanguage)
        payload.decodeSyncString(secondaryPreferredAudioLanguageKey)?.let(::saveSecondaryPreferredAudioLanguage)
        payload.decodeSyncString(preferredSubtitleLanguageKey)?.let(::savePreferredSubtitleLanguage)
        payload.decodeSyncString(secondaryPreferredSubtitleLanguageKey)?.let(::saveSecondaryPreferredSubtitleLanguage)
        payload.decodeSyncString(subtitleTextColorKey)?.let(::saveSubtitleTextColor)
        payload.decodeSyncString(subtitleBackgroundColorKey)?.let(::saveSubtitleBackgroundColor)
        payload.decodeSyncString(subtitleOutlineColorKey)?.let(::saveSubtitleOutlineColor)
        payload.decodeSyncBoolean(subtitleOutlineEnabledKey)?.let(::saveSubtitleOutlineEnabled)
        payload.decodeSyncInt(subtitleOutlineWidthKey)?.let(::saveSubtitleOutlineWidth)
        payload.decodeSyncBoolean(subtitleBoldKey)?.let(::saveSubtitleBold)
        payload.decodeSyncInt(subtitleFontSizeSpKey)?.let(::saveSubtitleFontSizeSp)
        payload.decodeSyncInt(subtitleBottomOffsetKey)?.let(::saveSubtitleBottomOffset)
        payload.decodeSyncBoolean(subtitleStripSdhKey)?.let(::saveSubtitleStripSdh)
        payload.decodeSyncBoolean(subtitleUseForcedSubtitlesKey)?.let(::saveSubtitleUseForcedSubtitles)
        payload.decodeSyncBoolean(subtitleShowOnlyPreferredLanguagesKey)?.let(::saveSubtitleShowOnlyPreferredLanguages)
        payload.decodeSyncBoolean(streamReuseLastLinkEnabledKey)?.let(::saveStreamReuseLastLinkEnabled)
        payload.decodeSyncInt(streamReuseLastLinkCacheHoursKey)?.let(::saveStreamReuseLastLinkCacheHours)
        payload.decodeSyncString(androidPlaybackEngineKey)?.let(::saveAndroidPlaybackEngine)
        payload.decodeSyncString(androidLibmpvVideoOutputKey)?.let(::saveAndroidLibmpvVideoOutput)
        payload.decodeSyncBoolean(androidLibmpvHardwareDecodingEnabledKey)
            ?.let(::saveAndroidLibmpvHardwareDecodingEnabled)
        payload.decodeSyncBoolean(androidLibmpvYuv420pEnabledKey)?.let(::saveAndroidLibmpvYuv420pEnabled)
        payload.decodeSyncInt(decoderPriorityKey)?.let(::saveDecoderPriority)
        payload.decodeSyncBoolean(mapDV7ToHevcKey)?.let(::saveMapDV7ToHevc)
        payload.decodeSyncBoolean(tunnelingEnabledKey)?.let(::saveTunnelingEnabled)
        payload.decodeSyncString(streamAutoPlayModeKey)?.let(::saveStreamAutoPlayMode)
        payload.decodeSyncString(streamAutoPlaySourceKey)?.let(::saveStreamAutoPlaySource)
        payload.decodeSyncStringSet(streamAutoPlaySelectedAddonsKey)?.let(::saveStreamAutoPlaySelectedAddons)
        payload.decodeSyncStringSet(streamAutoPlaySelectedPluginsKey)?.let(::saveStreamAutoPlaySelectedPlugins)
        payload.decodeSyncString(streamAutoPlayRegexKey)?.let(::saveStreamAutoPlayRegex)
        payload.decodeSyncInt(streamAutoPlayTimeoutSecondsKey)?.let(::saveStreamAutoPlayTimeoutSeconds)
        payload.decodeSyncBoolean(skipIntroEnabledKey)?.let(::saveSkipIntroEnabled)
        payload.decodeSyncBoolean(autoSkipMovieCreditsKey)?.let(::saveAutoSkipMovieCredits)
        payload.decodeSyncStringSet(autoSkipSegmentTypesKey)?.let(::saveAutoSkipSegmentTypes)
        payload.decodeSyncBoolean(autoSkipPostCreditsKey)?.let(::saveAutoSkipPostCredits)
        payload.decodeSyncBoolean(animeSkipEnabledKey)?.let(::saveAnimeSkipEnabled)
        payload.decodeSyncString(animeSkipClientIdKey)?.let(::saveAnimeSkipClientId)
        payload.decodeSyncString(introDbApiKeyKey)?.let(::saveIntroDbApiKey)
        payload.decodeSyncBoolean(introSubmitEnabledKey)?.let(::saveIntroSubmitEnabled)
        payload.decodeSyncBoolean(streamAutoPlayNextEpisodeEnabledKey)?.let(::saveStreamAutoPlayNextEpisodeEnabled)
        payload.decodeSyncBoolean(streamAutoPlayNextEpisodeFallbackEnabledKey)?.let(::saveStreamAutoPlayNextEpisodeFallbackEnabled)
        payload.decodeSyncBoolean(streamAutoPlayPreferBingeGroupKey)?.let(::saveStreamAutoPlayPreferBingeGroup)
        payload.decodeSyncBoolean(streamAutoPlayReuseBingeGroupKey)?.let(::saveStreamAutoPlayReuseBingeGroup)
        payload.decodeSyncString(nextEpisodeThresholdModeKey)?.let(::saveNextEpisodeThresholdMode)
        payload.decodeSyncFloat(nextEpisodeThresholdPercentKey)?.let(::saveNextEpisodeThresholdPercent)
        payload.decodeSyncFloat(nextEpisodeThresholdMinutesBeforeEndKey)?.let(::saveNextEpisodeThresholdMinutesBeforeEnd)
        payload.decodeSyncBoolean(useLibassKey)?.let(::saveUseLibass)
        payload.decodeSyncString(libassRenderTypeKey)?.let(::saveLibassRenderType)
        payload.decodeSyncString(iosVideoOutputPresetKey)?.let(::saveIosVideoOutputPreset)
        payload.decodeSyncString(iosToneMappingModeKey)?.let(::saveIosToneMappingMode)
        payload.decodeSyncString(iosTargetPrimariesKey)?.let(::saveIosTargetPrimaries)
        payload.decodeSyncString(iosTargetTransferKey)?.let(::saveIosTargetTransfer)
        payload.decodeSyncString(iosHardwareDecoderModeKey)?.let(::saveIosHardwareDecoderMode)
        payload.decodeSyncString(iosAudioOutputModeKey)?.let(::saveIosAudioOutputMode)
        payload.decodeSyncBoolean(iosExtendedDynamicRangeEnabledKey)?.let(::saveIosExtendedDynamicRangeEnabled)
        payload.decodeSyncBoolean(iosTargetColorspaceHintEnabledKey)?.let(::saveIosTargetColorspaceHintEnabled)
        payload.decodeSyncBoolean(iosHdrComputePeakEnabledKey)?.let(::saveIosHdrComputePeakEnabled)
        payload.decodeSyncBoolean(iosDebandEnabledKey)?.let(::saveIosDebandEnabled)
        payload.decodeSyncBoolean(iosInterpolationEnabledKey)?.let(::saveIosInterpolationEnabled)
        payload.decodeSyncInt(iosBrightnessKey)?.let(::saveIosBrightness)
        payload.decodeSyncInt(iosContrastKey)?.let(::saveIosContrast)
        payload.decodeSyncInt(iosSaturationKey)?.let(::saveIosSaturation)
        payload.decodeSyncInt(iosGammaKey)?.let(::saveIosGamma)
    }
}
