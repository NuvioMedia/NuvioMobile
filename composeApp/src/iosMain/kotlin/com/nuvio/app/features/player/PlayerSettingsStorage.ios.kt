package com.nuvio.app.features.player

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
import platform.Foundation.NSUserDefaults

actual object PlayerSettingsStorage {
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

    actual fun loadPendingExternalPlayback(): String? = NSUserDefaults.standardUserDefaults.stringForKey(pendingExternalPlaybackKey)

    actual fun savePendingExternalPlayback(value: String?) {
        if (value == null) {
            NSUserDefaults.standardUserDefaults.removeObjectForKey(pendingExternalPlaybackKey)
        } else {
            NSUserDefaults.standardUserDefaults.setObject(value, forKey = pendingExternalPlaybackKey)
        }
    }

    actual fun loadPlaybackBrightness(): Float? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(playbackBrightnessKey)
        return if (defaults.objectForKey(key) != null) defaults.floatForKey(key) else null
    }

    actual fun savePlaybackBrightness(level: Float) {
        NSUserDefaults.standardUserDefaults.setFloat(level, forKey = ProfileScopedKey.of(playbackBrightnessKey))
    }

    private fun loadBoolean(keyBase: String): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(keyBase)
        return if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else null
    }

    private fun saveBoolean(keyBase: String, enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(keyBase))
    }

    private fun loadInt(keyBase: String): Int? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(keyBase)
        return if (defaults.objectForKey(key) != null) defaults.integerForKey(key).toInt() else null
    }

    private fun saveInt(keyBase: String, value: Int) {
        NSUserDefaults.standardUserDefaults.setInteger(value.toLong(), forKey = ProfileScopedKey.of(keyBase))
    }

    actual fun loadShowLoadingOverlay(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(showLoadingOverlayKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveShowLoadingOverlay(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(showLoadingOverlayKey))
    }

    actual fun loadShowPlayerLoadingStatus(): Boolean? = loadBoolean(showPlayerLoadingStatusKey)

    actual fun saveShowPlayerLoadingStatus(enabled: Boolean) {
        saveBoolean(showPlayerLoadingStatusKey, enabled)
    }

    actual fun loadPauseOverlayEnabled(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(pauseOverlayEnabledKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun savePauseOverlayEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(pauseOverlayEnabledKey))
    }

    actual fun loadUseLegacyPlayerLayout(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(useLegacyPlayerLayoutKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveUseLegacyPlayerLayout(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(useLegacyPlayerLayoutKey))
    }

    actual fun loadShowParentalGuide(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(showParentalGuideKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveShowParentalGuide(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(showParentalGuideKey))
    }

    actual fun loadResizeMode(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(resizeModeKey)
        return defaults.stringForKey(key)
    }

    actual fun saveResizeMode(mode: String) {
        NSUserDefaults.standardUserDefaults.setObject(mode, forKey = ProfileScopedKey.of(resizeModeKey))
    }

    actual fun loadHoldToSpeedEnabled(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(holdToSpeedEnabledKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveHoldToSpeedEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(holdToSpeedEnabledKey))
    }

    actual fun loadHoldToSpeedValue(): Float? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(holdToSpeedValueKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.floatForKey(key)
        } else {
            null
        }
    }

    actual fun saveHoldToSpeedValue(speed: Float) {
        NSUserDefaults.standardUserDefaults.setFloat(speed, forKey = ProfileScopedKey.of(holdToSpeedValueKey))
    }

    actual fun loadTouchGesturesEnabled(): Boolean? = loadBoolean(touchGesturesEnabledKey)

    actual fun saveTouchGesturesEnabled(enabled: Boolean) {
        saveBoolean(touchGesturesEnabledKey, enabled)
    }

    actual fun loadExternalPlayerEnabled(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(externalPlayerEnabledKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveExternalPlayerEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(externalPlayerEnabledKey))
    }

    actual fun loadExternalPlayerForwardSubtitles(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(externalPlayerForwardSubtitlesKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveExternalPlayerForwardSubtitles(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(externalPlayerForwardSubtitlesKey))
    }

    actual fun loadExternalPlayerSendSkipSegments(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(externalPlayerSendSkipSegmentsKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveExternalPlayerSendSkipSegments(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(externalPlayerSendSkipSegmentsKey))
    }

    actual fun loadExternalPlayerId(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(externalPlayerIdKey)
        return defaults.stringForKey(key)
    }

    actual fun saveExternalPlayerId(playerId: String?) {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(externalPlayerIdKey)
        if (playerId.isNullOrBlank()) {
            defaults.removeObjectForKey(key)
        } else {
            defaults.setObject(playerId, forKey = key)
        }
    }

    actual fun loadPreferredAudioLanguage(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(preferredAudioLanguageKey)
        return defaults.stringForKey(key)
    }

    actual fun savePreferredAudioLanguage(language: String) {
        NSUserDefaults.standardUserDefaults.setObject(language, forKey = ProfileScopedKey.of(preferredAudioLanguageKey))
    }

    actual fun loadSecondaryPreferredAudioLanguage(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(secondaryPreferredAudioLanguageKey)
        return defaults.stringForKey(key)
    }

    actual fun saveSecondaryPreferredAudioLanguage(language: String?) {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(secondaryPreferredAudioLanguageKey)
        if (language.isNullOrBlank()) {
            defaults.removeObjectForKey(key)
        } else {
            defaults.setObject(language, forKey = key)
        }
    }

    actual fun loadPreferredSubtitleLanguage(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(preferredSubtitleLanguageKey)
        return defaults.stringForKey(key)
    }

    actual fun savePreferredSubtitleLanguage(language: String) {
        NSUserDefaults.standardUserDefaults.setObject(language, forKey = ProfileScopedKey.of(preferredSubtitleLanguageKey))
    }

    actual fun loadSecondaryPreferredSubtitleLanguage(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(secondaryPreferredSubtitleLanguageKey)
        return defaults.stringForKey(key)
    }

    actual fun saveSecondaryPreferredSubtitleLanguage(language: String?) {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(secondaryPreferredSubtitleLanguageKey)
        if (language.isNullOrBlank()) {
            defaults.removeObjectForKey(key)
        } else {
            defaults.setObject(language, forKey = key)
        }
    }

    actual fun loadSubtitleTextColor(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(subtitleTextColorKey)
        return defaults.stringForKey(key)
    }

    actual fun saveSubtitleTextColor(colorHex: String) {
        NSUserDefaults.standardUserDefaults.setObject(colorHex, forKey = ProfileScopedKey.of(subtitleTextColorKey))
    }

    actual fun loadSubtitleBackgroundColor(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(subtitleBackgroundColorKey)
        return defaults.stringForKey(key)
    }

    actual fun saveSubtitleBackgroundColor(colorHex: String) {
        NSUserDefaults.standardUserDefaults.setObject(colorHex, forKey = ProfileScopedKey.of(subtitleBackgroundColorKey))
    }

    actual fun loadSubtitleOutlineColor(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(subtitleOutlineColorKey)
        return defaults.stringForKey(key)
    }

    actual fun saveSubtitleOutlineColor(colorHex: String) {
        NSUserDefaults.standardUserDefaults.setObject(colorHex, forKey = ProfileScopedKey.of(subtitleOutlineColorKey))
    }

    actual fun loadSubtitleOutlineEnabled(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(subtitleOutlineEnabledKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveSubtitleOutlineEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(subtitleOutlineEnabledKey))
    }

    actual fun loadSubtitleOutlineWidth(): Int? = loadInt(subtitleOutlineWidthKey)

    actual fun saveSubtitleOutlineWidth(width: Int) {
        saveInt(subtitleOutlineWidthKey, width)
    }

    actual fun loadSubtitleBold(): Boolean? = loadBoolean(subtitleBoldKey)

    actual fun saveSubtitleBold(enabled: Boolean) {
        saveBoolean(subtitleBoldKey, enabled)
    }

    actual fun loadSubtitleFontSizeSp(): Int? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(subtitleFontSizeSpKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.integerForKey(key).toInt()
        } else {
            null
        }
    }

    actual fun saveSubtitleFontSizeSp(fontSizeSp: Int) {
        NSUserDefaults.standardUserDefaults.setInteger(fontSizeSp.toLong(), forKey = ProfileScopedKey.of(subtitleFontSizeSpKey))
    }

    actual fun loadSubtitleBottomOffset(): Int? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(subtitleBottomOffsetKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.integerForKey(key).toInt()
        } else {
            null
        }
    }

    actual fun saveSubtitleBottomOffset(bottomOffset: Int) {
        NSUserDefaults.standardUserDefaults.setInteger(bottomOffset.toLong(), forKey = ProfileScopedKey.of(subtitleBottomOffsetKey))
    }

    actual fun loadSubtitleStripSdh(): Boolean? = loadBoolean(subtitleStripSdhKey)

    actual fun saveSubtitleStripSdh(enabled: Boolean) {
        saveBoolean(subtitleStripSdhKey, enabled)
    }

    actual fun loadSubtitleUseForcedSubtitles(): Boolean? = loadBoolean(subtitleUseForcedSubtitlesKey)

    actual fun saveSubtitleUseForcedSubtitles(enabled: Boolean) {
        saveBoolean(subtitleUseForcedSubtitlesKey, enabled)
    }

    actual fun loadSubtitleShowOnlyPreferredLanguages(): Boolean? = loadBoolean(subtitleShowOnlyPreferredLanguagesKey)

    actual fun saveSubtitleShowOnlyPreferredLanguages(enabled: Boolean) {
        saveBoolean(subtitleShowOnlyPreferredLanguagesKey, enabled)
    }

    actual fun loadStreamReuseLastLinkEnabled(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(streamReuseLastLinkEnabledKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveStreamReuseLastLinkEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(streamReuseLastLinkEnabledKey))
    }

    actual fun loadStreamReuseLastLinkCacheHours(): Int? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(streamReuseLastLinkCacheHoursKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.integerForKey(key).toInt()
        } else {
            null
        }
    }

    actual fun saveStreamReuseLastLinkCacheHours(hours: Int) {
        NSUserDefaults.standardUserDefaults.setInteger(hours.toLong(), forKey = ProfileScopedKey.of(streamReuseLastLinkCacheHoursKey))
    }

    actual fun loadAndroidPlaybackEngine(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(androidPlaybackEngineKey))

    actual fun saveAndroidPlaybackEngine(engine: String) {
        NSUserDefaults.standardUserDefaults.setObject(engine, forKey = ProfileScopedKey.of(androidPlaybackEngineKey))
    }

    actual fun loadAndroidLibmpvVideoOutput(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(androidLibmpvVideoOutputKey))

    actual fun saveAndroidLibmpvVideoOutput(output: String) {
        NSUserDefaults.standardUserDefaults.setObject(output, forKey = ProfileScopedKey.of(androidLibmpvVideoOutputKey))
    }

    actual fun loadAndroidLibmpvHardwareDecodingEnabled(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(androidLibmpvHardwareDecodingEnabledKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveAndroidLibmpvHardwareDecodingEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(androidLibmpvHardwareDecodingEnabledKey))
    }

    actual fun loadAndroidLibmpvYuv420pEnabled(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(androidLibmpvYuv420pEnabledKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveAndroidLibmpvYuv420pEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(androidLibmpvYuv420pEnabledKey))
    }

    actual fun loadDecoderPriority(): Int? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(decoderPriorityKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.integerForKey(key).toInt()
        } else {
            null
        }
    }

    actual fun saveDecoderPriority(priority: Int) {
        NSUserDefaults.standardUserDefaults.setInteger(priority.toLong(), forKey = ProfileScopedKey.of(decoderPriorityKey))
    }

    actual fun loadMapDV7ToHevc(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(mapDV7ToHevcKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveMapDV7ToHevc(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(mapDV7ToHevcKey))
    }

    actual fun loadTunnelingEnabled(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(tunnelingEnabledKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveTunnelingEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(tunnelingEnabledKey))
    }

    actual fun loadStreamAutoPlayMode(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(streamAutoPlayModeKey)
        return defaults.stringForKey(key)
    }

    actual fun saveStreamAutoPlayMode(mode: String) {
        NSUserDefaults.standardUserDefaults.setObject(mode, forKey = ProfileScopedKey.of(streamAutoPlayModeKey))
    }

    actual fun loadStreamAutoPlaySource(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(streamAutoPlaySourceKey)
        return defaults.stringForKey(key)
    }

    actual fun saveStreamAutoPlaySource(source: String) {
        NSUserDefaults.standardUserDefaults.setObject(source, forKey = ProfileScopedKey.of(streamAutoPlaySourceKey))
    }

    @Suppress("UNCHECKED_CAST")
    actual fun loadStreamAutoPlaySelectedAddons(): Set<String>? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(streamAutoPlaySelectedAddonsKey)
        val array = defaults.arrayForKey(key) as? List<String> ?: return null
        return array.toSet()
    }

    actual fun saveStreamAutoPlaySelectedAddons(addons: Set<String>) {
        NSUserDefaults.standardUserDefaults.setObject(addons.toList(), forKey = ProfileScopedKey.of(streamAutoPlaySelectedAddonsKey))
    }

    @Suppress("UNCHECKED_CAST")
    actual fun loadStreamAutoPlaySelectedPlugins(): Set<String>? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(streamAutoPlaySelectedPluginsKey)
        val array = defaults.arrayForKey(key) as? List<String> ?: return null
        return array.toSet()
    }

    actual fun saveStreamAutoPlaySelectedPlugins(plugins: Set<String>) {
        NSUserDefaults.standardUserDefaults.setObject(plugins.toList(), forKey = ProfileScopedKey.of(streamAutoPlaySelectedPluginsKey))
    }

    actual fun loadStreamAutoPlayRegex(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(streamAutoPlayRegexKey)
        return defaults.stringForKey(key)
    }

    actual fun saveStreamAutoPlayRegex(regex: String) {
        NSUserDefaults.standardUserDefaults.setObject(regex, forKey = ProfileScopedKey.of(streamAutoPlayRegexKey))
    }

    actual fun loadStreamAutoPlayTimeoutSeconds(): Int? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(streamAutoPlayTimeoutSecondsKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.integerForKey(key).toInt()
        } else {
            null
        }
    }

    actual fun saveStreamAutoPlayTimeoutSeconds(seconds: Int) {
        NSUserDefaults.standardUserDefaults.setInteger(seconds.toLong(), forKey = ProfileScopedKey.of(streamAutoPlayTimeoutSecondsKey))
    }

    actual fun loadSkipIntroEnabled(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(skipIntroEnabledKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun loadAutoSkipSegmentTypes(): Set<String>? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(autoSkipSegmentTypesKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.stringArrayForKey(key)?.filterIsInstance<String>()?.toSet() ?: emptySet()
        } else null
    }

    actual fun saveAutoSkipSegmentTypes(types: Set<String>) {
        NSUserDefaults.standardUserDefaults.setObject(types.toList(), forKey = ProfileScopedKey.of(autoSkipSegmentTypesKey))
    }

    actual fun loadAutoSkipMovieCredits(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(autoSkipMovieCreditsKey)
        return if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else null
    }

    actual fun saveAutoSkipMovieCredits(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(autoSkipMovieCreditsKey))
    }

    actual fun loadAutoSkipPostCredits(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(autoSkipPostCreditsKey)
        return if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else null
    }

    actual fun saveAutoSkipPostCredits(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(autoSkipPostCreditsKey))
    }


    actual fun saveSkipIntroEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(skipIntroEnabledKey))
    }

    actual fun loadAnimeSkipEnabled(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(animeSkipEnabledKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveAnimeSkipEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(animeSkipEnabledKey))
    }

    actual fun loadAnimeSkipClientId(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(animeSkipClientIdKey)
        return defaults.stringForKey(key)
    }

    actual fun saveAnimeSkipClientId(clientId: String) {
        NSUserDefaults.standardUserDefaults.setObject(clientId, forKey = ProfileScopedKey.of(animeSkipClientIdKey))
    }

    actual fun loadIntroDbApiKey(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(introDbApiKeyKey)
        return defaults.stringForKey(key)
    }

    actual fun saveIntroDbApiKey(apiKey: String) {
        NSUserDefaults.standardUserDefaults.setObject(apiKey, forKey = ProfileScopedKey.of(introDbApiKeyKey))
    }

    actual fun loadIntroSubmitEnabled(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(introSubmitEnabledKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveIntroSubmitEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(introSubmitEnabledKey))
    }

    actual fun loadStreamAutoPlayNextEpisodeEnabled(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(streamAutoPlayNextEpisodeEnabledKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveStreamAutoPlayNextEpisodeEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(streamAutoPlayNextEpisodeEnabledKey))
    }

    actual fun loadStreamAutoPlayNextEpisodeFallbackEnabled(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(streamAutoPlayNextEpisodeFallbackEnabledKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveStreamAutoPlayNextEpisodeFallbackEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(streamAutoPlayNextEpisodeFallbackEnabledKey))
    }

    actual fun loadStreamAutoPlayPreferBingeGroup(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(streamAutoPlayPreferBingeGroupKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveStreamAutoPlayPreferBingeGroup(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(streamAutoPlayPreferBingeGroupKey))
    }

    actual fun loadStreamAutoPlayReuseBingeGroup(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(streamAutoPlayReuseBingeGroupKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveStreamAutoPlayReuseBingeGroup(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(streamAutoPlayReuseBingeGroupKey))
    }

    actual fun loadNextEpisodeThresholdMode(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(nextEpisodeThresholdModeKey)
        return defaults.stringForKey(key)
    }

    actual fun saveNextEpisodeThresholdMode(mode: String) {
        NSUserDefaults.standardUserDefaults.setObject(mode, forKey = ProfileScopedKey.of(nextEpisodeThresholdModeKey))
    }

    actual fun loadNextEpisodeThresholdPercent(): Float? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(nextEpisodeThresholdPercentKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.floatForKey(key)
        } else {
            null
        }
    }

    actual fun saveNextEpisodeThresholdPercent(percent: Float) {
        NSUserDefaults.standardUserDefaults.setFloat(percent, forKey = ProfileScopedKey.of(nextEpisodeThresholdPercentKey))
    }

    actual fun loadNextEpisodeThresholdMinutesBeforeEnd(): Float? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(nextEpisodeThresholdMinutesBeforeEndKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.floatForKey(key)
        } else {
            null
        }
    }

    actual fun saveNextEpisodeThresholdMinutesBeforeEnd(minutes: Float) {
        NSUserDefaults.standardUserDefaults.setFloat(minutes, forKey = ProfileScopedKey.of(nextEpisodeThresholdMinutesBeforeEndKey))
    }

    actual fun loadUseLibass(): Boolean? = null

    actual fun saveUseLibass(enabled: Boolean) {}

    actual fun loadLibassRenderType(): String? = null

    actual fun saveLibassRenderType(renderType: String) {}

    actual fun loadIosVideoOutputPreset(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(iosVideoOutputPresetKey))

    actual fun saveIosVideoOutputPreset(preset: String) {
        NSUserDefaults.standardUserDefaults.setObject(preset, forKey = ProfileScopedKey.of(iosVideoOutputPresetKey))
    }

    actual fun loadIosToneMappingMode(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(iosToneMappingModeKey))

    actual fun saveIosToneMappingMode(mode: String) {
        NSUserDefaults.standardUserDefaults.setObject(mode, forKey = ProfileScopedKey.of(iosToneMappingModeKey))
    }

    actual fun loadIosTargetPrimaries(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(iosTargetPrimariesKey))

    actual fun saveIosTargetPrimaries(primaries: String) {
        NSUserDefaults.standardUserDefaults.setObject(primaries, forKey = ProfileScopedKey.of(iosTargetPrimariesKey))
    }

    actual fun loadIosTargetTransfer(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(iosTargetTransferKey))

    actual fun saveIosTargetTransfer(transfer: String) {
        NSUserDefaults.standardUserDefaults.setObject(transfer, forKey = ProfileScopedKey.of(iosTargetTransferKey))
    }

    actual fun loadIosHardwareDecoderMode(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(iosHardwareDecoderModeKey))

    actual fun saveIosHardwareDecoderMode(mode: String) {
        NSUserDefaults.standardUserDefaults.setObject(mode, forKey = ProfileScopedKey.of(iosHardwareDecoderModeKey))
    }

    actual fun loadIosAudioOutputMode(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(iosAudioOutputModeKey))

    actual fun saveIosAudioOutputMode(mode: String) {
        NSUserDefaults.standardUserDefaults.setObject(mode, forKey = ProfileScopedKey.of(iosAudioOutputModeKey))
    }

    actual fun loadIosExtendedDynamicRangeEnabled(): Boolean? =
        loadBoolean(iosExtendedDynamicRangeEnabledKey)

    actual fun saveIosExtendedDynamicRangeEnabled(enabled: Boolean) {
        saveBoolean(iosExtendedDynamicRangeEnabledKey, enabled)
    }

    actual fun loadIosTargetColorspaceHintEnabled(): Boolean? =
        loadBoolean(iosTargetColorspaceHintEnabledKey)

    actual fun saveIosTargetColorspaceHintEnabled(enabled: Boolean) {
        saveBoolean(iosTargetColorspaceHintEnabledKey, enabled)
    }

    actual fun loadIosHdrComputePeakEnabled(): Boolean? =
        loadBoolean(iosHdrComputePeakEnabledKey)

    actual fun saveIosHdrComputePeakEnabled(enabled: Boolean) {
        saveBoolean(iosHdrComputePeakEnabledKey, enabled)
    }

    actual fun loadIosDebandEnabled(): Boolean? =
        loadBoolean(iosDebandEnabledKey)

    actual fun saveIosDebandEnabled(enabled: Boolean) {
        saveBoolean(iosDebandEnabledKey, enabled)
    }

    actual fun loadIosInterpolationEnabled(): Boolean? =
        loadBoolean(iosInterpolationEnabledKey)

    actual fun saveIosInterpolationEnabled(enabled: Boolean) {
        saveBoolean(iosInterpolationEnabledKey, enabled)
    }

    actual fun loadIosBrightness(): Int? = loadInt(iosBrightnessKey)

    actual fun saveIosBrightness(value: Int) {
        saveInt(iosBrightnessKey, value)
    }

    actual fun loadIosContrast(): Int? = loadInt(iosContrastKey)

    actual fun saveIosContrast(value: Int) {
        saveInt(iosContrastKey, value)
    }

    actual fun loadIosSaturation(): Int? = loadInt(iosSaturationKey)

    actual fun saveIosSaturation(value: Int) {
        saveInt(iosSaturationKey, value)
    }

    actual fun loadIosGamma(): Int? = loadInt(iosGammaKey)

    actual fun saveIosGamma(value: Int) {
        saveInt(iosGammaKey, value)
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
        syncKeys.forEach { key ->
            NSUserDefaults.standardUserDefaults.removeObjectForKey(ProfileScopedKey.of(key))
        }

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
