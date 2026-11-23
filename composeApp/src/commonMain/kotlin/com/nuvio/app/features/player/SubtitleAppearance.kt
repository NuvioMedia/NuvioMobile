package com.nuvio.app.features.player

import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SubtitleAppearance(
    val fontId: String = "default",
    val backgroundRadius: Int = 0,
    val shadowEnabled: Boolean = false,
    val shadowOpacity: Int = 75,
    val shadowSize: Int = 2,
    val customizeAss: Boolean = false,
) {
    // Keep the existing constructor used by Swift callers.
    constructor(
        fontId: String,
        backgroundRadius: Int,
        shadowEnabled: Boolean,
        shadowOpacity: Int,
        shadowSize: Int,
    ) : this(fontId, backgroundRadius, shadowEnabled, shadowOpacity, shadowSize, customizeAss = false)

    fun normalized(): SubtitleAppearance = copy(
        fontId = fontId.takeIf { it in BuiltInSubtitleFonts || it.matches(Regex("custom-[a-f0-9-]{36}")) } ?: "default",
        backgroundRadius = backgroundRadius.coerceIn(0, 24),
        shadowOpacity = shadowOpacity.coerceIn(0, 100),
        shadowSize = shadowSize.coerceIn(1, 8),
    )
}

internal val BuiltInSubtitleFonts = listOf("default", "sans", "serif", "mono")
private val subtitleAppearanceJson = Json { ignoreUnknownKeys = true }

internal fun decodeSubtitleAppearance(value: String?): SubtitleAppearance =
    value?.let { runCatching { subtitleAppearanceJson.decodeFromString<SubtitleAppearance>(it).normalized() }.getOrNull() }
        ?: SubtitleAppearance()

internal fun SubtitleAppearance.encode(): String = subtitleAppearanceJson.encodeToString(normalized())

@Serializable
private data class StoredAssSubtitleStyle(
    val textColor: String? = null,
    val backgroundColor: String? = null,
    val outlineColor: String? = null,
    val outlineEnabled: Boolean? = null,
    val outlineWidth: Int? = null,
    val bold: Boolean? = null,
    val fontSizeSp: Int? = null,
    val bottomOffset: Int? = null,
    val appearance: SubtitleAppearance? = null,
)

internal fun decodeAssSubtitleStyle(value: String?): SubtitleStyleState {
    val defaults = SubtitleStyleState.DEFAULT
    val stored = value?.let {
        runCatching { subtitleAppearanceJson.decodeFromString<StoredAssSubtitleStyle>(it) }.getOrNull()
    } ?: return defaults
    return defaults.copy(
        textColor = subtitleColorFromStorage(stored.textColor) ?: defaults.textColor,
        backgroundColor = subtitleColorFromStorage(stored.backgroundColor) ?: defaults.backgroundColor,
        outlineColor = subtitleColorFromStorage(stored.outlineColor) ?: defaults.outlineColor,
        outlineEnabled = stored.outlineEnabled ?: defaults.outlineEnabled,
        outlineWidth = (stored.outlineWidth ?: defaults.outlineWidth).coerceIn(subtitleOutlineWidthRange),
        bold = stored.bold ?: defaults.bold,
        fontSizeSp = (stored.fontSizeSp ?: defaults.fontSizeSp).coerceIn(subtitleFontSizeRangeSp),
        bottomOffset = (stored.bottomOffset ?: defaults.bottomOffset).coerceIn(0, 200),
        appearance = stored.appearance?.normalized() ?: defaults.appearance,
    )
}

internal fun SubtitleStyleState.encodeAssStyle(): String = subtitleAppearanceJson.encodeToString(
    StoredAssSubtitleStyle(
        textColor = textColor.toStorageHexString(),
        backgroundColor = backgroundColor.toStorageHexString(),
        outlineColor = outlineColor.toStorageHexString(),
        outlineEnabled = outlineEnabled,
        outlineWidth = outlineWidth,
        bold = bold,
        fontSizeSp = fontSizeSp,
        bottomOffset = bottomOffset,
        appearance = appearance.normalized(),
    ),
)

internal val SubtitleStyleState.usesStyledOverlay: Boolean
    get() = appearance.fontId != "default" ||
        backgroundColor.alpha > 0f ||
        appearance.shadowEnabled ||
        (outlineEnabled && outlineWidth != SUBTITLE_OUTLINE_WIDTH_DEFAULT)

object SubtitleRendering {
    private fun isAss(codec: String): Boolean =
        codec.equals("ass", ignoreCase = true) || codec.equals("ssa", ignoreCase = true)

    fun assOverride(codec: String, customizeAss: Boolean, assScale: Double = 1.0): String = when {
        !isAss(codec) -> "no"
        customizeAss -> "force"
        abs(assScale - 1.0) > 0.001 -> "scale"
        else -> "no"
    }

    fun supportsTextOverlay(codec: String): Boolean = codec.lowercase() in setOf(
        "subrip", "srt", "webvtt", "text", "mov_text", "microdvd", "mpl2", "subviewer", "subviewer1", "sami", "realtext",
    )
}

internal val SubtitleStyleState.assScale: Double
    get() = fontSizeSp.toDouble() / SubtitleStyleState.DEFAULT.fontSizeSp

internal fun SubtitleStyleState.resetAppearance(): SubtitleStyleState = SubtitleStyleState.DEFAULT.copy(
    stripSdh = stripSdh,
    useForcedSubtitles = useForcedSubtitles,
    showOnlyPreferredLanguages = showOnlyPreferredLanguages,
)
