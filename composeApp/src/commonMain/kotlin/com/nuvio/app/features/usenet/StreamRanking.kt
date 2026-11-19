package com.nuvio.app.features.usenet

import kotlinx.serialization.Serializable

/** Whether [value] compiles as a bounded-time pattern: no lookaround or backreferences. */
internal expect fun isValidRankingPattern(value: String): Boolean

/** A versioned native profile. It contains data, never executable scripts or remote dependencies. */
@Serializable
data class StreamRankingProfile(
    val version: Int = 1,
    val name: String = "My profile",
    val tiers: List<StreamRankingTier> = emptyList(),
    val sort: List<StreamRankingSort> = listOf(
        StreamRankingSort(StreamRankingKey.SIZE_PREFERENCE),
        StreamRankingSort(StreamRankingKey.TIER),
        StreamRankingSort(StreamRankingKey.SCORE),
        StreamRankingSort(StreamRankingKey.LANGUAGE),
        StreamRankingSort(StreamRankingKey.SIZE, descending = false)
    ),
    val preferredLanguages: List<String> = emptyList(),
    val preferredMovieSizeGb: Double = 0.0,
    val preferredEpisodeSizeGb: Double = 0.0,
    /** Soft diversity limit: extra releases follow the first N from each resolution/quality bucket. */
    val topPerBucket: Int = 3,
    val rules: List<StreamRankingRule> = emptyList()
) {
    fun validate() {
        require(version == 1) { "Unsupported profile version" }
        require(name.isNotBlank() && name.length <= 80) { "Profile name must contain 1–80 characters" }
        require(tiers.size <= 24) { "Use at most 24 quality tiers" }
        tiers.forEach {
            require(it.name.isNotBlank() && it.name.length <= 80) { "Each tier needs a short name" }
            require(it.resolutions.size <= 10 && it.resolutions.all { r -> r in listOf(0, 360, 480, 576, 720, 1080, 1440, 2160) }) {
                "Invalid tier resolution"
            }
            require(it.qualities.distinct().size == it.qualities.size) { "A quality appears twice in a tier" }
        }
        require(sort.isNotEmpty() && sort.size <= StreamRankingKey.entries.size && sort.map { it.key }.distinct().size == sort.size) {
            "Choose distinct sorting priorities"
        }
        require(preferredLanguages.size <= 24 && preferredLanguages.all { it.matches(Regex("[a-z]{2,8}")) }) {
            "Languages must use lowercase codes such as en, ja or multi"
        }
        require(preferredMovieSizeGb.isFinite() && preferredMovieSizeGb in 0.0..1000.0 &&
            preferredEpisodeSizeGb.isFinite() && preferredEpisodeSizeGb in 0.0..1000.0) { "Preferred sizes must be between 0 and 1000 GB" }
        require(topPerBucket in 0..20) { "Results per category must be between 0 and 20; 0 disables the limit" }
        require(rules.size <= 80) { "Use at most 80 rules" }
        rules.forEachIndexed { index, rule ->
            try { rule.validate() } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Rule ${index + 1} (${rule.name.take(80)}): ${e.message}", e)
            }
        }
    }
}

@Serializable data class StreamRankingTier(
    val name: String,
    val resolutions: List<Int> = emptyList(),
    val qualities: List<StreamReleaseQuality> = emptyList()
)
@Serializable data class StreamRankingSort(val key: StreamRankingKey, val descending: Boolean = true)
@Serializable enum class StreamRankingKey { TIER, SCORE, LANGUAGE, RESOLUTION, QUALITY, SIZE, AGE, INDEXER, SIZE_PREFERENCE }
@Serializable enum class StreamReleaseQuality { REMUX, BLURAY, WEB_DL, WEBRIP, HDTV, DVD, CAM, UNKNOWN }
@Serializable enum class StreamRankingField { TITLE, GROUP, QUALITY, RESOLUTION, CODEC, VISUAL, AUDIO, LANGUAGE, INDEXER, SIZE_GB, AGE_DAYS, MEDIA_TYPE }
@Serializable enum class StreamRankingOperator { IS, IS_NOT, CONTAINS, MATCHES, AT_LEAST, AT_MOST }
@Serializable enum class StreamRankingAction { BOOST, EXCLUDE, REQUIRE }

@Serializable data class StreamRankingCondition(
    val field: StreamRankingField = StreamRankingField.TITLE,
    val operator: StreamRankingOperator = StreamRankingOperator.CONTAINS,
    val value: String = ""
) {
    fun validate() {
        require(value.isNotBlank() && value.length <= 512) { "Conditions need a value of at most 512 characters" }
        val numeric = field in setOf(StreamRankingField.SIZE_GB, StreamRankingField.AGE_DAYS, StreamRankingField.RESOLUTION)
        require(if (numeric) operator in setOf(StreamRankingOperator.IS, StreamRankingOperator.IS_NOT,
            StreamRankingOperator.AT_LEAST, StreamRankingOperator.AT_MOST)
        else operator !in setOf(StreamRankingOperator.AT_LEAST, StreamRankingOperator.AT_MOST)) { "Operator is not supported for this field" }
        if (numeric) require(value.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true) { "Enter a positive number or zero" }
        if (operator == StreamRankingOperator.MATCHES) {
            require(value.length <= 256) { "Regex patterns may contain at most 256 characters" }
            require(isValidRankingPattern(value)) {
                "Invalid regex. Use regular expressions without lookaround or backreferences"
            }
        }
    }
}

@Serializable data class StreamRankingRule(
    val name: String = "New rule",
    val enabled: Boolean = true,
    val action: StreamRankingAction = StreamRankingAction.BOOST,
    val score: Int = 100,
    val matchAny: Boolean = false,
    val conditions: List<StreamRankingCondition> = emptyList(),
    /** Filters are only applied if this many candidates would remain; 0 means a strict filter. */
    val minimumRemaining: Int = 0
) {
    fun validate() {
        require(name.isNotBlank() && name.length <= 80) { "Each rule needs a name of at most 80 characters" }
        require(score in -10000..10000) { "Rule scores must be between -10000 and 10000" }
        require(conditions.size in 1..8) { "Each rule needs 1–8 conditions" }
        require(minimumRemaining in 0..200) { "Minimum remaining results must be between 0 and 200" }
        conditions.forEach { it.validate() }
    }
}

@Serializable enum class StreamRankingPreset { ORIGINAL, BALANCED, BEST_QUALITY, SMALLER_FILES, HD_SDR, CUSTOM }

@Serializable data class StreamRankingSettings(
    val preset: StreamRankingPreset = StreamRankingPreset.ORIGINAL,
    val custom: StreamRankingProfile? = null,
    /** A quick preference for built-in presets; custom profiles keep their own language priorities. */
    val preferredLanguage: String = ""
) {
    fun validate() {
        custom?.validate()
        require(preferredLanguage.isEmpty() || preferredLanguage in StreamRankingPresets.languages) { "Choose a supported language" }
        require(preset != StreamRankingPreset.CUSTOM || custom != null) { "Save a custom profile before selecting it" }
    }
    fun profile(): StreamRankingProfile? = when (preset) {
        StreamRankingPreset.ORIGINAL -> null
        StreamRankingPreset.CUSTOM -> custom
        else -> StreamRankingPresets.profile(preset).let { profile ->
            if (preferredLanguage.isEmpty()) profile else profile.copy(
                preferredLanguages = listOf(preferredLanguage),
                sort = listOf(StreamRankingSort(StreamRankingKey.LANGUAGE)) + profile.sort.filter { it.key != StreamRankingKey.LANGUAGE }
            )
        }
    }
}

/**
 * Nuvio's own profiles. Quality tiers are inspired by Tam-Taro's approach; release-group membership
 * was checked against TRaSH Guides' Radarr tiers (October 2026). These are source-specific boosts,
 * not guarantees about a release, and unknown groups remain eligible.
 * They match the presets of NuvioTV's built-in Usenet sorting.
 */
object StreamRankingPresets {
    val languages = listOf("en", "ja", "de", "fr", "nl", "es", "it", "pt", "ko", "zh", "hi", "ru", "uk", "ar", "pl", "tr", "multi", "dual")
    private fun condition(field: StreamRankingField, value: String) = StreamRankingCondition(field, StreamRankingOperator.IS, value)
    private fun groups(name: String, qualities: String, names: String, score: Int) = StreamRankingRule(
        name = name, score = score, conditions = listOf(condition(StreamRankingField.QUALITY, qualities), condition(StreamRankingField.GROUP, names))
    )
    private val groupRules = listOf(
        groups("Remux • leading groups", "REMUX", "3L,ATELiER,BiZKiT,BLURANiUM,BMF,CiNEPHiLES,FraMeSToR,PiRAMiDHEAD,PmP,WiLDCAT,ZQ", 300),
        groups("Remux • established groups", "REMUX", "NCmt,playBD,SiCFoI,SURFINBIRD,TEPES", 200),
        groups("Remux • other recommended groups", "REMUX", "12GaugeShotgun,decibeL,EPSiLON,HiFi,iFT,KRaLiMaRKo,NTb,PTP,SumVision,TOA,TRiToN", 100),
        groups("WEB • leading groups", "WEB_DL,WEBRIP", "ABBIE,AJP69,APEX,PAXA,PEXA,XEPA,BLUTONiUM,BYNDR,CMRG,CRFW,CRUD,FLUX,GNOME,HONE,KiNGS,Kitsune,MADSKY,NOSiViD,NTb,NTG,RAWR,SiC,TEPES,TheFarm,ZoroSenpai", 300),
        groups("WEB • established groups", "WEB_DL,WEBRIP", "dB,Flights,MiU,monkee,MZABI,PHOENiX,playWEB,SbR,SMURF,TOMMY,XEBEC,4KBEC,CEBEX", 200),
        groups("WEB • other recommended groups", "WEB_DL,WEBRIP", "BLOOM,Dooky,GNOMiSSiON,NINJACENTRAL,NPMS,ROCCaT,SiGMA,SLiGNOME,SwAgLaNdEr", 100),
        groups("Blu-ray • leading groups", "BLURAY", "ATELiER,BBQ,BMF,c0kE,Chotab,CRiSC,CtrlHD,D-Z0N3,Dariush,decibeL,DON,EbP,EDPH,Geek,LolHD,NCmt,PTer,TayTO,TDD,TnP,VietHD,ZQ,ZoroSenpai,MainFrame,W4NK3R", 250)
    )
    private val compressedGroups = "aXXo,MeGusta,mSD,mHD,nHD,nSD,YIFY,YTS"
    private val upscalePattern = "(?:^|[ ._-])(?:upscaled?|ai[ ._-]?enhanced|ai[ ._-]?upscale)(?:$|[ ._-])"
    private val baseRules = groupRules + listOf(
        StreamRankingRule("Avoid heavily compressed groups when alternatives exist", action = StreamRankingAction.EXCLUDE,
            minimumRemaining = 5, conditions = listOf(condition(StreamRankingField.GROUP, compressedGroups))),
        StreamRankingRule("Avoid labelled upscales when alternatives exist", action = StreamRankingAction.EXCLUDE,
            minimumRemaining = 5, conditions = listOf(StreamRankingCondition(StreamRankingField.TITLE, StreamRankingOperator.MATCHES, upscalePattern))),
        StreamRankingRule("Heavily compressed release group", score = -1000,
            conditions = listOf(condition(StreamRankingField.GROUP, compressedGroups))),
        StreamRankingRule("Repack / proper", score = 15, conditions = listOf(
            StreamRankingCondition(StreamRankingField.TITLE, StreamRankingOperator.MATCHES, "(?:^|[ ._-])(?:repack[0-9]*|proper[0-9]*)(?:$|[ ._-])"))),
        StreamRankingRule("Hybrid HDR / Dolby Vision", score = 10, conditions = listOf(condition(StreamRankingField.VISUAL, "HDR_DV"))),
        StreamRankingRule("Lossless / immersive audio", score = 10, conditions = listOf(condition(StreamRankingField.AUDIO, "ATMOS,TRUEHD,DTS_HD"))),
        StreamRankingRule("Upscale / AI label", score = -500, conditions = listOf(
            StreamRankingCondition(StreamRankingField.TITLE, StreamRankingOperator.MATCHES, upscalePattern)))
    )
    private fun tier(name: String, resolution: Int, vararg quality: StreamReleaseQuality) =
        StreamRankingTier(name, listOf(resolution), quality.toList())
    private val qualityTiers = listOf(
        tier("4K remux", 2160, StreamReleaseQuality.REMUX),
        tier("4K Blu-ray encode", 2160, StreamReleaseQuality.BLURAY),
        tier("1080p remux", 1080, StreamReleaseQuality.REMUX),
        tier("4K WEB-DL", 2160, StreamReleaseQuality.WEB_DL),
        tier("4K WEBRip", 2160, StreamReleaseQuality.WEBRIP),
        tier("1080p Blu-ray encode", 1080, StreamReleaseQuality.BLURAY),
        tier("1080p WEB-DL", 1080, StreamReleaseQuality.WEB_DL),
        tier("1080p WEBRip", 1080, StreamReleaseQuality.WEBRIP),
        tier("720p and HD", 720, StreamReleaseQuality.REMUX, StreamReleaseQuality.BLURAY, StreamReleaseQuality.WEB_DL,
            StreamReleaseQuality.WEBRIP, StreamReleaseQuality.HDTV),
        StreamRankingTier("Other known HD", listOf(2160, 1440, 1080), listOf(StreamReleaseQuality.REMUX,
            StreamReleaseQuality.BLURAY, StreamReleaseQuality.WEB_DL, StreamReleaseQuality.WEBRIP, StreamReleaseQuality.HDTV))
    )
    private val balanced = StreamRankingProfile(
        name = "Balanced", tiers = qualityTiers, preferredMovieSizeGb = 30.0, preferredEpisodeSizeGb = 5.0,
        rules = baseRules, topPerBucket = 3
    )
    private val best = balanced.copy(name = "Best Quality", preferredMovieSizeGb = 0.0, preferredEpisodeSizeGb = 0.0,
        topPerBucket = 4, sort = listOf(StreamRankingSort(StreamRankingKey.TIER), StreamRankingSort(StreamRankingKey.SCORE),
            StreamRankingSort(StreamRankingKey.LANGUAGE), StreamRankingSort(StreamRankingKey.SIZE)))
    private val smaller = balanced.copy(name = "Smaller Files", preferredMovieSizeGb = 12.0, preferredEpisodeSizeGb = 2.0,
        topPerBucket = 2,
        tiers = listOf(
            tier("1080p WEB-DL", 1080, StreamReleaseQuality.WEB_DL),
            tier("1080p WEBRip", 1080, StreamReleaseQuality.WEBRIP),
            tier("1080p Blu-ray encode", 1080, StreamReleaseQuality.BLURAY),
            tier("4K WEB-DL", 2160, StreamReleaseQuality.WEB_DL),
            tier("4K WEBRip", 2160, StreamReleaseQuality.WEBRIP),
            tier("720p and HD", 720, StreamReleaseQuality.REMUX, StreamReleaseQuality.BLURAY, StreamReleaseQuality.WEB_DL,
            StreamReleaseQuality.WEBRIP, StreamReleaseQuality.HDTV),
            tier("1080p remux", 1080, StreamReleaseQuality.REMUX),
            tier("4K Blu-ray encode", 2160, StreamReleaseQuality.BLURAY),
            tier("4K remux", 2160, StreamReleaseQuality.REMUX)
        ),
        rules = baseRules + StreamRankingRule("Efficient video codec", score = 30,
            conditions = listOf(condition(StreamRankingField.CODEC, "HEVC,AV1")))
    )
    private val hdSdr = smaller.copy(name = "1080p / SDR", tiers = listOf(
        tier("1080p WEB-DL", 1080, StreamReleaseQuality.WEB_DL),
        tier("1080p Blu-ray encode", 1080, StreamReleaseQuality.BLURAY),
        tier("1080p WEBRip", 1080, StreamReleaseQuality.WEBRIP),
        tier("720p and HD", 720, StreamReleaseQuality.WEB_DL, StreamReleaseQuality.BLURAY,
            StreamReleaseQuality.WEBRIP, StreamReleaseQuality.HDTV)
    ) + qualityTiers,
        // Preferences rather than filters: keep alternatives when a release has no SDR label.
        sort = listOf(StreamRankingSort(StreamRankingKey.SCORE)) + smaller.sort.filter { it.key != StreamRankingKey.SCORE },
        rules = baseRules + listOf(
            StreamRankingRule("Prefer HD without HDR or Dolby Vision labels", score = 3000, conditions = listOf(
                StreamRankingCondition(StreamRankingField.RESOLUTION, StreamRankingOperator.AT_LEAST, "720"),
                StreamRankingCondition(StreamRankingField.RESOLUTION, StreamRankingOperator.AT_MOST, "1080"),
                condition(StreamRankingField.VISUAL, "NO_HDR_LABEL"))),
            StreamRankingRule("Prefer AVC video", score = 30, conditions = listOf(condition(StreamRankingField.CODEC, "AVC"))),
            StreamRankingRule("Prefer AAC or Dolby Digital Plus audio", score = 20,
                conditions = listOf(condition(StreamRankingField.AUDIO, "AAC,DDP")))
        ))
    fun profile(preset: StreamRankingPreset): StreamRankingProfile = when (preset) {
        StreamRankingPreset.BEST_QUALITY -> best
        StreamRankingPreset.SMALLER_FILES -> smaller
        StreamRankingPreset.HD_SDR -> hdSdr
        else -> balanced
    }
}
