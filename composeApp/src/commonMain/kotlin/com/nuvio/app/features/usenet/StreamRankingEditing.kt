package com.nuvio.app.features.usenet

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One release of a sorting preview: its name, and its size, age and indexer when given. */
data class RankingSample(
    val title: String,
    val sizeGb: Double? = null,
    val ageDays: Double? = null,
    val indexer: String = "",
)

/** A previewed release in its sorted place: score, tier, the rules it matched, and whether it is a category extra. */
data class RankingPreviewRow(
    val title: String,
    val score: Int,
    val tier: String?,
    val matches: List<String>,
    val overflow: Boolean,
)

/** A preview: the releases in sorted order, and those the profile's filters left out. */
data class RankingPreview(val results: List<RankingPreviewRow>, val excluded: List<String>)

/** The file format NuvioTV exports and imports, so a profile moves between the apps unchanged. */
@Serializable
private data class RankingExport(
    val format: String = FORMAT,
    val version: Int = 1,
    val profile: StreamRankingProfile,
) {
    companion object {
        const val FORMAT = "nuvio.stream-ranking"
    }
}

/**
 * The edits the custom sorting editor makes, kept out of the UI so they match NuvioTV's phone
 * editor and can be tested. Each returns a new profile; validation happens on Save.
 */
object StreamRankingEditing {
    const val MAX_TIERS = 24
    const val MAX_RULES = 80
    const val MAX_CONDITIONS = 8
    private const val MAX_IMPORT_CHARS = 60_000

    val resolutions = listOf(2160, 1440, 1080, 720, 576, 480, 360)
    val qualities = StreamReleaseQuality.entries
    private val numericFields = setOf(StreamRankingField.RESOLUTION, StreamRankingField.SIZE_GB, StreamRankingField.AGE_DAYS)

    /** One release per line: name | size in GB | age in days | indexer. */
    val defaultSamples = """
        Movie.2025.2160p.UHD.BluRay.REMUX.HDR.TrueHD.Atmos-FraMeSToR | 65 | 120
        Movie.2025.1080p.BluRay.REMUX-CiNEPHiLES | 18 | 90
        Movie.2025.2160p.WEB-DL.DDP.HDR-FLUX | 16 | 20
        Movie.2025.2160p.WEB-DL-Unknown | 8 | 100
        Movie.2025.1080p.WEB-DL.HEVC-NTb | 4 | 5
        Movie.2025.720p.WEBRip-Other | 1 | 2
    """.trimIndent()

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /**
     * The settings' sorting as an editable profile: the saved custom one, else a copy of the active
     * preset or the plain sort, under a name of its own so it never reads as the preset itself.
     */
    fun startingDraft(settings: StreamRankingSettings, sort: UsenetSort): StreamRankingProfile =
        settings.custom ?: (settings.profile() ?: original(sort)).copy(name = NEW_PROFILE_NAME)

    const val NEW_PROFILE_NAME = "My profile"

    /** NuvioTV's equivalent of the plain sort order, so editing starts from what the list shows now. */
    fun original(sort: UsenetSort): StreamRankingProfile {
        val primary = when (sort) {
            UsenetSort.QUALITY -> StreamRankingSort(StreamRankingKey.RESOLUTION)
            UsenetSort.LARGEST -> StreamRankingSort(StreamRankingKey.SIZE)
            UsenetSort.SMALLEST -> StreamRankingSort(StreamRankingKey.SIZE, descending = false)
            UsenetSort.NEWEST -> StreamRankingSort(StreamRankingKey.AGE)
            UsenetSort.INDEXER -> StreamRankingSort(StreamRankingKey.INDEXER)
        }
        return StreamRankingProfile(
            name = "Current sorting",
            topPerBucket = 0,
            sort = listOf(
                primary,
                StreamRankingSort(StreamRankingKey.INDEXER),
                StreamRankingSort(StreamRankingKey.RESOLUTION),
                StreamRankingSort(StreamRankingKey.SIZE),
            ).distinctBy { it.key },
        )
    }

    fun <T> List<T>.moved(index: Int, offset: Int): List<T> {
        val target = index + offset
        if (index !in indices || target !in indices) return this
        return toMutableList().apply { add(target, removeAt(index)) }
    }

    fun <T> List<T>.replaced(index: Int, value: T): List<T> =
        if (index in indices) toMutableList().apply { set(index, value) } else this

    fun <T> List<T>.without(index: Int): List<T> =
        if (index in indices) toMutableList().apply { removeAt(index) } else this

    /** The sorting keys not used yet, the ones Add priority can offer. */
    fun unusedKeys(profile: StreamRankingProfile): List<StreamRankingKey> =
        StreamRankingKey.entries.filter { key -> profile.sort.none { it.key == key } }

    fun addPriority(profile: StreamRankingProfile, key: StreamRankingKey): StreamRankingProfile =
        if (profile.sort.any { it.key == key }) profile else profile.copy(sort = profile.sort + StreamRankingSort(key))

    /** A new tier; tiers only sort once Quality tier is a priority, so it is added first when missing. */
    fun addTier(profile: StreamRankingProfile, name: String): StreamRankingProfile {
        if (profile.tiers.size >= MAX_TIERS) return profile
        return withKey(profile, StreamRankingKey.TIER, first = true).copy(tiers = profile.tiers + StreamRankingTier(name))
    }

    /** A new score rule on release group; scores only sort once Rule score is a priority. */
    fun addRule(profile: StreamRankingProfile, name: String): StreamRankingProfile {
        if (profile.rules.size >= MAX_RULES) return profile
        val rule = StreamRankingRule(
            name = name,
            conditions = listOf(StreamRankingCondition(StreamRankingField.GROUP, StreamRankingOperator.IS, "")),
        )
        return withKey(profile, StreamRankingKey.SCORE, first = false).copy(rules = profile.rules + rule)
    }

    fun setRuleAction(profile: StreamRankingProfile, index: Int, action: StreamRankingAction): StreamRankingProfile {
        val rule = profile.rules.getOrNull(index) ?: return profile
        val updated = profile.copy(rules = profile.rules.replaced(index, rule.copy(action = action)))
        return if (action == StreamRankingAction.BOOST) withKey(updated, StreamRankingKey.SCORE, first = false) else updated
    }

    fun addCondition(rule: StreamRankingRule): StreamRankingRule =
        if (rule.conditions.size >= MAX_CONDITIONS) rule
        else rule.copy(conditions = rule.conditions + StreamRankingCondition(StreamRankingField.TITLE, StreamRankingOperator.CONTAINS, ""))

    /** A new field keeps no value or operator that would not fit it. */
    fun withField(condition: StreamRankingCondition, field: StreamRankingField): StreamRankingCondition {
        if (field == condition.field) return condition
        val numeric = field in numericFields
        return StreamRankingCondition(
            field = field,
            operator = if (numeric) StreamRankingOperator.AT_LEAST else StreamRankingOperator.IS,
            value = if (numeric) "0" else "",
        )
    }

    fun operatorsFor(field: StreamRankingField): List<StreamRankingOperator> =
        if (field in numericFields) {
            listOf(StreamRankingOperator.IS, StreamRankingOperator.IS_NOT, StreamRankingOperator.AT_LEAST, StreamRankingOperator.AT_MOST)
        } else {
            listOf(StreamRankingOperator.IS, StreamRankingOperator.IS_NOT, StreamRankingOperator.CONTAINS, StreamRankingOperator.MATCHES)
        }

    /** "en, ja" as language codes, in order and lowercase. */
    fun parseLanguages(text: String): List<String> =
        text.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    fun exportJson(profile: StreamRankingProfile): String = json.encodeToString(RankingExport.serializer(), RankingExport(profile = profile))

    /** A profile from an exported file's text; throws [IllegalArgumentException] when it is not a valid one. */
    fun importJson(text: String): StreamRankingProfile {
        require(text.length <= MAX_IMPORT_CHARS) { "The profile is too large" }
        val export = runCatching { json.decodeFromString(RankingExport.serializer(), text.trim()) }
            .getOrElse { throw IllegalArgumentException("This is not a Nuvio sorting profile") }
        require(export.format == RankingExport.FORMAT && export.version == 1) { "This is not a Nuvio sorting profile" }
        export.profile.validate()
        return export.profile
    }

    /** The preview lines; throws [IllegalArgumentException] for a size or age that is not a number. */
    fun parseSamples(text: String): List<RankingSample> {
        val samples = text.lines().filter { it.isNotBlank() }.map { line ->
            val fields = line.split('|').map { it.trim() }
            fun number(index: Int): Double? {
                val value = fields.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: return null
                return value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                    ?: throw IllegalArgumentException("\"$value\" is not a size or age")
            }
            RankingSample(title = fields[0], sizeGb = number(1), ageDays = number(2), indexer = fields.getOrNull(3).orEmpty())
        }
        require(samples.size in 1..50) { "Preview between 1 and 50 releases" }
        require(samples.all { it.title.isNotEmpty() && it.title.length <= 2048 }) { "A release name is missing or too long" }
        return samples
    }

    private fun withKey(profile: StreamRankingProfile, key: StreamRankingKey, first: Boolean): StreamRankingProfile {
        if (profile.sort.any { it.key == key }) return profile
        val sort = profile.sort.toMutableList()
        sort.add(if (first) 0 else minOf(1, sort.size), StreamRankingSort(key))
        return profile.copy(sort = sort)
    }
}
