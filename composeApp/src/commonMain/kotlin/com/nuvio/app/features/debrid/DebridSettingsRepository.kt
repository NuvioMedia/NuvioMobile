package com.nuvio.app.features.debrid

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object DebridSettingsRepository {
    private val _uiState = MutableStateFlow(DebridSettings())
    val uiState: StateFlow<DebridSettings> = _uiState.asStateFlow()

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private var hasLoaded = false
    private var enabled = false
    private var cloudLibraryEnabled = true
    private var providerApiKeys = emptyMap<String, String>()
    private var preferredResolverProviderId = ""
    private var instantPlaybackPreparationLimit = 0
    private var streamMaxResults = 0
    private var streamSortMode = DebridStreamSortMode.DEFAULT
    private var streamMinimumQuality = DebridStreamMinimumQuality.ANY
    private var streamDolbyVisionFilter = DebridStreamFeatureFilter.ANY
    private var streamHdrFilter = DebridStreamFeatureFilter.ANY
    private var streamCodecFilter = DebridStreamCodecFilter.ANY
    private var streamPreferences = DebridStreamPreferences()
    private var streamNameTemplate = DebridStreamFormatterDefaults.NAME_TEMPLATE
    private var streamDescriptionTemplate = DebridStreamFormatterDefaults.DESCRIPTION_TEMPLATE

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun snapshot(): DebridSettings {
        ensureLoaded()
        return _uiState.value
    }

    fun setEnabled(value: Boolean) {
        ensureLoaded()
        if (value && !hasResolverProvider()) return
        if (enabled == value) return
        enabled = value
        publish()
        DebridSettingsStorage.saveEnabled(value)
    }

    fun setLinkResolvingEnabled(value: Boolean) {
        setEnabled(value)
    }

    fun setCloudLibraryEnabled(value: Boolean) {
        ensureLoaded()
        if (value && !hasCloudLibraryProvider()) return
        if (cloudLibraryEnabled == value) return
        cloudLibraryEnabled = value
        publish()
        DebridSettingsStorage.saveCloudLibraryEnabled(value)
    }

    fun setProviderApiKey(providerId: String, value: String) {
        ensureLoaded()
        val provider = DebridProviders.byId(providerId) ?: return
        val normalized = value.trim()
        if (providerApiKeys[provider.id].orEmpty() == normalized) return
        providerApiKeys = if (normalized.isBlank()) {
            providerApiKeys - provider.id
        } else {
            providerApiKeys + (provider.id to normalized)
        }
        normalizePreferredResolverProviderId(save = true)
        disableIfNoResolver()
        publish()
        DebridSettingsStorage.saveProviderApiKey(provider.id, normalized)
    }

    fun setTorboxApiKey(value: String) {
        setProviderApiKey(DebridProviders.TORBOX_ID, value)
    }

    fun setRealDebridApiKey(value: String) {
        setProviderApiKey(DebridProviders.REAL_DEBRID_ID, value)
    }

    fun setPremiumizeApiKey(value: String) {
        setProviderApiKey(DebridProviders.PREMIUMIZE_ID, value)
    }

    fun setPreferredResolverProviderId(providerId: String) {
        ensureLoaded()
        val normalized = DebridProviders.byId(providerId)?.id.orEmpty()
        val next = connectedResolverProviderIds()
            .firstOrNull { it == normalized }
            ?: connectedResolverProviderIds().firstOrNull().orEmpty()
        if (preferredResolverProviderId == next) return
        preferredResolverProviderId = next
        publish()
        DebridSettingsStorage.savePreferredResolverProviderId(next)
    }

    fun setInstantPlaybackPreparationLimit(value: Int) {
        ensureLoaded()
        val normalized = normalizeDebridInstantPlaybackPreparationLimit(value)
        if (instantPlaybackPreparationLimit == normalized) return
        instantPlaybackPreparationLimit = normalized
        publish()
        DebridSettingsStorage.saveInstantPlaybackPreparationLimit(normalized)
    }

    fun setStreamMaxResults(value: Int) {
        ensureLoaded()
        val normalized = normalizeDebridStreamMaxResults(value)
        if (streamMaxResults == normalized && streamPreferences.maxResults == normalized) return
        streamMaxResults = normalized
        streamPreferences = streamPreferences.copy(maxResults = normalized).normalized()
        publish()
        DebridSettingsStorage.saveStreamMaxResults(normalized)
        saveStreamPreferences()
    }

    fun setStreamSortMode(value: DebridStreamSortMode) {
        ensureLoaded()
        if (streamSortMode == value && streamPreferences.sortCriteria == sortCriteriaForLegacyMode(value)) return
        streamSortMode = value
        streamPreferences = streamPreferences.copy(sortCriteria = sortCriteriaForLegacyMode(value)).normalized()
        publish()
        DebridSettingsStorage.saveStreamSortMode(value.name)
        saveStreamPreferences()
    }

    fun setStreamMinimumQuality(value: DebridStreamMinimumQuality) {
        ensureLoaded()
        if (streamMinimumQuality == value && streamPreferences.requiredResolutions == resolutionsForMinimumQuality(value)) return
        streamMinimumQuality = value
        streamPreferences = streamPreferences.copy(requiredResolutions = resolutionsForMinimumQuality(value)).normalized()
        publish()
        DebridSettingsStorage.saveStreamMinimumQuality(value.name)
        saveStreamPreferences()
    }

    fun setStreamDolbyVisionFilter(value: DebridStreamFeatureFilter) {
        ensureLoaded()
        if (streamDolbyVisionFilter == value) return
        streamDolbyVisionFilter = value
        streamPreferences = streamPreferences.applyDolbyVisionFilter(value).normalized()
        publish()
        DebridSettingsStorage.saveStreamDolbyVisionFilter(value.name)
        saveStreamPreferences()
    }

    fun setStreamHdrFilter(value: DebridStreamFeatureFilter) {
        ensureLoaded()
        if (streamHdrFilter == value) return
        streamHdrFilter = value
        streamPreferences = streamPreferences.applyHdrFilter(value).normalized()
        publish()
        DebridSettingsStorage.saveStreamHdrFilter(value.name)
        saveStreamPreferences()
    }

    fun setStreamCodecFilter(value: DebridStreamCodecFilter) {
        ensureLoaded()
        if (streamCodecFilter == value) return
        streamCodecFilter = value
        streamPreferences = streamPreferences.applyCodecFilter(value).normalized()
        publish()
        DebridSettingsStorage.saveStreamCodecFilter(value.name)
        saveStreamPreferences()
    }

    fun setStreamPreferences(value: DebridStreamPreferences) {
        ensureLoaded()
        val normalized = value.normalized()
        val nextSortMode = legacyModeForSortCriteria(normalized.sortCriteria)
        val sortModeChanged = streamSortMode != nextSortMode
        if (streamPreferences == normalized && !sortModeChanged) return
        streamPreferences = normalized
        streamMaxResults = normalized.maxResults
        streamSortMode = nextSortMode
        publish()
        DebridSettingsStorage.saveStreamMaxResults(streamMaxResults)
        if (sortModeChanged) {
            DebridSettingsStorage.saveStreamSortMode(nextSortMode.name)
        }
        saveStreamPreferences()
    }

    fun setStreamNameTemplate(value: String) {
        ensureLoaded()
        val normalized = normalizeStreamTemplate(value, DebridTemplateKind.NAME)
        if (streamNameTemplate == normalized) return
        streamNameTemplate = normalized
        publish()
        DebridSettingsStorage.saveStreamNameTemplate(normalized)
    }

    fun setStreamDescriptionTemplate(value: String) {
        ensureLoaded()
        val normalized = normalizeStreamTemplate(value, DebridTemplateKind.DESCRIPTION)
        if (streamDescriptionTemplate == normalized) return
        streamDescriptionTemplate = normalized
        publish()
        DebridSettingsStorage.saveStreamDescriptionTemplate(normalized)
    }

    fun setStreamTemplates(nameTemplate: String, descriptionTemplate: String) {
        ensureLoaded()
        streamNameTemplate = normalizeStreamTemplate(nameTemplate, DebridTemplateKind.NAME)
        streamDescriptionTemplate = normalizeStreamTemplate(descriptionTemplate, DebridTemplateKind.DESCRIPTION)
        publish()
        DebridSettingsStorage.saveStreamNameTemplate(streamNameTemplate)
        DebridSettingsStorage.saveStreamDescriptionTemplate(streamDescriptionTemplate)
    }

    fun resetStreamTemplates() {
        setStreamTemplates(
            nameTemplate = DebridStreamFormatterDefaults.NAME_TEMPLATE,
            descriptionTemplate = DebridStreamFormatterDefaults.DESCRIPTION_TEMPLATE,
        )
    }

    private fun disableIfNoResolver() {
        if (!hasResolverProvider()) {
            enabled = false
            DebridSettingsStorage.saveEnabled(false)
        }
    }

    private fun hasCloudLibraryProvider(): Boolean =
        DebridProviders.visible().any { provider ->
            provider.supports(DebridProviderCapability.CloudLibrary) &&
                providerApiKeys[provider.id].orEmpty().isNotBlank()
        }

    private fun hasResolverProvider(): Boolean = connectedResolverProviderIds().isNotEmpty()

    private fun connectedResolverProviderIds(): List<String> =
        DebridProviders.visible().filter { provider ->
            (
                provider.supports(DebridProviderCapability.ClientResolve) ||
                    provider.supports(DebridProviderCapability.LocalTorrentResolve)
                ) &&
            providerApiKeys[provider.id].orEmpty().isNotBlank()
        }.map { it.id }

    private fun normalizePreferredResolverProviderId(save: Boolean = false) {
        val providerId = DebridProviders.byId(preferredResolverProviderId)?.id.orEmpty()
        val connectedResolverIds = connectedResolverProviderIds()
        val normalized = when {
            connectedResolverIds.isEmpty() -> preferredResolverProviderId
            providerId in connectedResolverIds -> providerId
            else -> connectedResolverIds.first()
        }
        if (preferredResolverProviderId != normalized) {
            preferredResolverProviderId = normalized
            if (save) {
                DebridSettingsStorage.savePreferredResolverProviderId(normalized)
            }
        }
    }

    private fun loadFromDisk() {
        hasLoaded = true
        providerApiKeys = DebridProviders.all()
            .mapNotNull { provider ->
                DebridSettingsStorage.loadProviderApiKey(provider.id)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { apiKey -> provider.id to apiKey }
            }
            .toMap()
        preferredResolverProviderId = DebridSettingsStorage.loadPreferredResolverProviderId()
            ?.let(DebridProviders::byId)
            ?.id
            .orEmpty()
        normalizePreferredResolverProviderId(save = providerApiKeys.isNotEmpty())
        enabled = DebridSettingsStorage.loadEnabled() ?: false
        cloudLibraryEnabled = DebridSettingsStorage.loadCloudLibraryEnabled() ?: true
        instantPlaybackPreparationLimit = normalizeDebridInstantPlaybackPreparationLimit(
            DebridSettingsStorage.loadInstantPlaybackPreparationLimit() ?: 0,
        )
        streamMaxResults = normalizeDebridStreamMaxResults(DebridSettingsStorage.loadStreamMaxResults() ?: 0)
        streamSortMode = enumValueOrDefault(
            DebridSettingsStorage.loadStreamSortMode(),
            DebridStreamSortMode.DEFAULT,
        )
        streamMinimumQuality = enumValueOrDefault(
            DebridSettingsStorage.loadStreamMinimumQuality(),
            DebridStreamMinimumQuality.ANY,
        )
        streamDolbyVisionFilter = enumValueOrDefault(
            DebridSettingsStorage.loadStreamDolbyVisionFilter(),
            DebridStreamFeatureFilter.ANY,
        )
        streamHdrFilter = enumValueOrDefault(
            DebridSettingsStorage.loadStreamHdrFilter(),
            DebridStreamFeatureFilter.ANY,
        )
        streamCodecFilter = enumValueOrDefault(
            DebridSettingsStorage.loadStreamCodecFilter(),
            DebridStreamCodecFilter.ANY,
        )
        val parsedStreamPreferences = parseStreamPreferences(DebridSettingsStorage.loadStreamPreferences())
        streamPreferences = parsedStreamPreferences
            ?: legacyStreamPreferences(
                maxResults = streamMaxResults,
                sortMode = streamSortMode,
                minimumQuality = streamMinimumQuality,
                dolbyVisionFilter = streamDolbyVisionFilter,
                hdrFilter = streamHdrFilter,
                codecFilter = streamCodecFilter,
            )
        if (parsedStreamPreferences != null) {
            val normalizedSortMode = legacyModeForSortCriteria(streamPreferences.sortCriteria)
            if (streamSortMode != normalizedSortMode) {
                streamSortMode = normalizedSortMode
                DebridSettingsStorage.saveStreamSortMode(normalizedSortMode.name)
            }
        }
        streamNameTemplate = normalizeStreamTemplate(
            DebridSettingsStorage.loadStreamNameTemplate()
                ?: DebridStreamFormatterDefaults.NAME_TEMPLATE,
            DebridTemplateKind.NAME,
        )
        streamDescriptionTemplate = normalizeStreamTemplate(
            DebridSettingsStorage.loadStreamDescriptionTemplate()
                ?: DebridStreamFormatterDefaults.DESCRIPTION_TEMPLATE,
            DebridTemplateKind.DESCRIPTION,
        )
        publish()
    }

    private fun publish() {
        _uiState.value = DebridSettings(
            enabled = enabled,
            cloudLibraryEnabled = cloudLibraryEnabled,
            providerApiKeys = providerApiKeys,
            preferredResolverProviderId = preferredResolverProviderId,
            instantPlaybackPreparationLimit = instantPlaybackPreparationLimit,
            streamMaxResults = streamMaxResults,
            streamSortMode = streamSortMode,
            streamMinimumQuality = streamMinimumQuality,
            streamDolbyVisionFilter = streamDolbyVisionFilter,
            streamHdrFilter = streamHdrFilter,
            streamCodecFilter = streamCodecFilter,
            streamPreferences = streamPreferences,
            streamNameTemplate = streamNameTemplate,
            streamDescriptionTemplate = streamDescriptionTemplate,
        )
    }

    private fun saveStreamPreferences() {
        DebridSettingsStorage.saveStreamPreferences(json.encodeToString(streamPreferences.normalized()))
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(default)

    private fun parseStreamPreferences(value: String?): DebridStreamPreferences? {
        if (value.isNullOrBlank()) return null
        return try {
            json.decodeFromString<DebridStreamPreferences>(value).normalized()
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private enum class DebridTemplateKind {
        NAME,
        DESCRIPTION,
    }

    private fun normalizeStreamTemplate(value: String, kind: DebridTemplateKind): String {
        val trimmed = value.trim()
        return when {
            trimmed.isBlank() -> ""
            kind == DebridTemplateKind.NAME && trimmed == DebridStreamFormatterDefaults.LEGACY_NAME_TEMPLATE -> ""
            kind == DebridTemplateKind.DESCRIPTION && trimmed == DebridStreamFormatterDefaults.LEGACY_DESCRIPTION_TEMPLATE -> ""
            else -> value
        }
    }
}

internal fun DebridStreamPreferences.normalized(): DebridStreamPreferences =
    copy(
        maxResults = normalizeDebridStreamMaxResults(maxResults),
        maxPerResolution = maxPerResolution.coerceIn(0, 100),
        maxPerQuality = maxPerQuality.coerceIn(0, 100),
        sizeMinGb = sizeMinGb.coerceIn(0, 100),
        sizeMaxGb = sizeMaxGb.coerceIn(0, 100),
        preferredResolutions = preferredResolutions.ifEmpty { DebridStreamResolution.defaultOrder },
        requiredResolutions = requiredResolutions,
        excludedResolutions = excludedResolutions,
        preferredQualities = preferredQualities.ifEmpty { DebridStreamQuality.defaultOrder },
        requiredQualities = requiredQualities,
        excludedQualities = excludedQualities,
        preferredVisualTags = preferredVisualTags.ifEmpty { DebridStreamVisualTag.defaultOrder },
        requiredVisualTags = requiredVisualTags,
        excludedVisualTags = excludedVisualTags,
        preferredAudioTags = preferredAudioTags.ifEmpty { DebridStreamAudioTag.defaultOrder },
        requiredAudioTags = requiredAudioTags,
        excludedAudioTags = excludedAudioTags,
        preferredAudioChannels = preferredAudioChannels.ifEmpty { DebridStreamAudioChannel.defaultOrder },
        requiredAudioChannels = requiredAudioChannels,
        excludedAudioChannels = excludedAudioChannels,
        preferredEncodes = preferredEncodes.ifEmpty { DebridStreamEncode.defaultOrder },
        requiredEncodes = requiredEncodes,
        excludedEncodes = excludedEncodes,
        preferredLanguages = preferredLanguages,
        requiredLanguages = requiredLanguages,
        excludedLanguages = excludedLanguages,
        requiredReleaseGroups = requiredReleaseGroups.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
        excludedReleaseGroups = excludedReleaseGroups.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
        sortCriteria = sortCriteria,
    )

private fun legacyStreamPreferences(
    maxResults: Int,
    sortMode: DebridStreamSortMode,
    minimumQuality: DebridStreamMinimumQuality,
    dolbyVisionFilter: DebridStreamFeatureFilter,
    hdrFilter: DebridStreamFeatureFilter,
    codecFilter: DebridStreamCodecFilter,
): DebridStreamPreferences =
    DebridStreamPreferences(
        maxResults = normalizeDebridStreamMaxResults(maxResults),
        sortCriteria = sortCriteriaForLegacyMode(sortMode),
        requiredResolutions = resolutionsForMinimumQuality(minimumQuality),
    )
        .applyDolbyVisionFilter(dolbyVisionFilter)
        .applyHdrFilter(hdrFilter)
        .applyCodecFilter(codecFilter)
        .normalized()

private fun DebridStreamPreferences.applyDolbyVisionFilter(
    filter: DebridStreamFeatureFilter,
): DebridStreamPreferences =
    when (filter) {
        DebridStreamFeatureFilter.ANY -> copy(
            requiredVisualTags = requiredVisualTags - dolbyVisionTags.toSet(),
            excludedVisualTags = excludedVisualTags - dolbyVisionTags.toSet(),
        )
        DebridStreamFeatureFilter.EXCLUDE -> copy(
            requiredVisualTags = requiredVisualTags - dolbyVisionTags.toSet(),
            excludedVisualTags = (excludedVisualTags + dolbyVisionTags).distinct(),
        )
        DebridStreamFeatureFilter.ONLY -> copy(
            requiredVisualTags = (requiredVisualTags + dolbyVisionTags).distinct(),
            excludedVisualTags = excludedVisualTags - dolbyVisionTags.toSet(),
        )
    }

private fun DebridStreamPreferences.applyHdrFilter(
    filter: DebridStreamFeatureFilter,
): DebridStreamPreferences =
    when (filter) {
        DebridStreamFeatureFilter.ANY -> copy(
            requiredVisualTags = requiredVisualTags - hdrTags.toSet(),
            excludedVisualTags = excludedVisualTags - hdrTags.toSet(),
        )
        DebridStreamFeatureFilter.EXCLUDE -> copy(
            requiredVisualTags = requiredVisualTags - hdrTags.toSet(),
            excludedVisualTags = (excludedVisualTags + hdrTags).distinct(),
        )
        DebridStreamFeatureFilter.ONLY -> copy(
            requiredVisualTags = (requiredVisualTags + hdrTags).distinct(),
            excludedVisualTags = excludedVisualTags - hdrTags.toSet(),
        )
    }

private fun DebridStreamPreferences.applyCodecFilter(
    filter: DebridStreamCodecFilter,
): DebridStreamPreferences =
    copy(
        requiredEncodes = when (filter) {
            DebridStreamCodecFilter.ANY -> emptyList()
            DebridStreamCodecFilter.H264 -> listOf(DebridStreamEncode.AVC)
            DebridStreamCodecFilter.HEVC -> listOf(DebridStreamEncode.HEVC)
            DebridStreamCodecFilter.AV1 -> listOf(DebridStreamEncode.AV1)
        },
    )

private fun resolutionsForMinimumQuality(quality: DebridStreamMinimumQuality): List<DebridStreamResolution> =
    DebridStreamResolution.defaultOrder.filter {
        it.value >= quality.minResolution && it != DebridStreamResolution.UNKNOWN
    }

private fun sortCriteriaForLegacyMode(mode: DebridStreamSortMode): List<DebridStreamSortCriterion> =
    when (mode) {
        DebridStreamSortMode.DEFAULT -> DebridStreamSortCriterion.originalOrder
        DebridStreamSortMode.QUALITY_DESC -> listOf(
            DebridStreamSortCriterion(DebridStreamSortKey.RESOLUTION, DebridStreamSortDirection.DESC),
            DebridStreamSortCriterion(DebridStreamSortKey.QUALITY, DebridStreamSortDirection.DESC),
            DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.DESC),
        )
        DebridStreamSortMode.SIZE_DESC -> listOf(DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.DESC))
        DebridStreamSortMode.SIZE_ASC -> listOf(DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.ASC))
    }

private fun legacyModeForSortCriteria(criteria: List<DebridStreamSortCriterion>): DebridStreamSortMode {
    val normalized = criteria.map { it.key to it.direction }
    val bestQuality = DebridStreamSortCriterion.defaultOrder.map { it.key to it.direction }
    fun legacySignature(mode: DebridStreamSortMode) = sortCriteriaForLegacyMode(mode).map { it.key to it.direction }
    return when {
        normalized.isEmpty() -> DebridStreamSortMode.DEFAULT
        normalized == bestQuality -> DebridStreamSortMode.QUALITY_DESC
        normalized == legacySignature(DebridStreamSortMode.QUALITY_DESC) -> DebridStreamSortMode.QUALITY_DESC
        normalized == legacySignature(DebridStreamSortMode.SIZE_DESC) -> DebridStreamSortMode.SIZE_DESC
        normalized == legacySignature(DebridStreamSortMode.SIZE_ASC) -> DebridStreamSortMode.SIZE_ASC
        else -> DebridStreamSortMode.DEFAULT
    }
}

private val dolbyVisionTags = listOf(
    DebridStreamVisualTag.DV,
    DebridStreamVisualTag.DV_ONLY,
    DebridStreamVisualTag.HDR_DV,
)

private val hdrTags = listOf(
    DebridStreamVisualTag.HDR,
    DebridStreamVisualTag.HDR10,
    DebridStreamVisualTag.HDR10_PLUS,
    DebridStreamVisualTag.HLG,
    DebridStreamVisualTag.HDR_ONLY,
    DebridStreamVisualTag.HDR_DV,
)
