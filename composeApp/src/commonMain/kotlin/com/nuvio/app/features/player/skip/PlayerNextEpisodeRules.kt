package com.nuvio.app.features.player.skip

import com.nuvio.app.core.format.formatDayFirstReleaseDate
import com.nuvio.app.core.time.daysUntilEpisodeRelease
import com.nuvio.app.core.time.parseEpisodeReleaseLocalDate
import com.nuvio.app.features.details.MetaVideo
import kotlinx.coroutines.CancellationException
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.cw_airs_date
import nuvio.composeapp.generated.resources.cw_airs_in_days
import nuvio.composeapp.generated.resources.cw_airs_today
import nuvio.composeapp.generated.resources.cw_airs_tomorrow
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

object PlayerNextEpisodeRules {

    fun resolveNextEpisode(
        videos: List<MetaVideo>,
        currentSeason: Int?,
        currentEpisode: Int?,
    ): MetaVideo? {
        if (currentSeason == null || currentEpisode == null) return null
        val sortedEpisodes = videos
            .filter { it.season != null && it.episode != null }
            .sortedWith(
                compareBy<MetaVideo> { it.season ?: Int.MAX_VALUE }
                    .thenBy { it.episode ?: Int.MAX_VALUE }
            )

        val currentIndex = sortedEpisodes.indexOfFirst {
            it.season == currentSeason && it.episode == currentEpisode
        }
        if (currentIndex < 0) return null
        return sortedEpisodes.getOrNull(currentIndex + 1)
    }

    fun shouldShowNextEpisodeCard(
        positionMs: Long,
        durationMs: Long,
        skipIntervals: List<SkipInterval>,
        thresholdMode: NextEpisodeThresholdMode,
        thresholdPercent: Float,
        thresholdMinutesBeforeEnd: Float,
    ): Boolean {
        val outroSegments = skipIntervals.filter { it.type in OUTRO_SEGMENT_TYPES }

        if (outroSegments.isNotEmpty()) {
            if (durationMs <= 0L) return false
            val latestOutroEndMs = (outroSegments.maxOf { it.endTime } * 1_000.0).toLong()
            val postOutroGapMs = durationMs - latestOutroEndMs

            // Calculate the user's configured threshold as milliseconds from end.
            val userThresholdMs = when (thresholdMode) {
                NextEpisodeThresholdMode.PERCENTAGE -> {
                    val clampedPercent = thresholdPercent.coerceIn(97f, 100f)
                    ((1.0 - clampedPercent / 100.0) * durationMs).toLong()
                }
                NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
                    val clampedMinutes = thresholdMinutesBeforeEnd.coerceIn(0f, 3.5f)
                    (clampedMinutes * 60_000f).toLong()
                }
            }

            return if (postOutroGapMs > userThresholdMs) {
                when (thresholdMode) {
                    NextEpisodeThresholdMode.PERCENTAGE -> {
                        val clampedPercent = thresholdPercent.coerceIn(97f, 100f)
                        (positionMs.toDouble() / durationMs.toDouble()) >= (clampedPercent / 100.0)
                    }
                    NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
                        val clampedMinutes = thresholdMinutesBeforeEnd.coerceIn(0f, 3.5f)
                        val remainingMs = durationMs - positionMs
                        remainingMs <= (clampedMinutes * 60_000f).toLong()
                    }
                }
            } else {
                // Outro ends close to the file end — fire at earliest outro start.
                positionMs / 1_000.0 >= outroSegments.minOf { it.startTime }
            }
        }

        // Fallback to the settings threshold when no outro data exists.
        if (durationMs <= 0L) return false
        return when (thresholdMode) {
            NextEpisodeThresholdMode.PERCENTAGE -> {
                val clampedPercent = thresholdPercent.coerceIn(97f, 100f)
                (positionMs.toDouble() / durationMs.toDouble()) >= (clampedPercent / 100.0)
            }
            NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
                val clampedMinutes = thresholdMinutesBeforeEnd.coerceIn(0f, 3.5f)
                val remainingMs = durationMs - positionMs
                remainingMs <= (clampedMinutes * 60_000f).toLong()
            }
        }
    }

    fun hasEpisodeAired(raw: String?): Boolean {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        val dateStr = parseEpisodeReleaseLocalDate(value) ?: when {
            value.length >= 10 -> value.substring(0, 10)
            else -> return true
        }
        // Parse YYYY-MM-DD
        val parts = dateStr.split("-")
        if (parts.size != 3) return true
        val year = parts[0].toIntOrNull() ?: return true
        val month = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return true
        val day = parts[2].toIntOrNull()?.takeIf { it in 1..31 } ?: return true

        val today = currentDateComponents()
        return compareDate(year, month, day, today.year, today.month, today.day) <= 0
    }

    suspend fun formatUnairedEpisodeMessage(
        released: String?,
        airsPrefix: String,
        tbaLabel: String,
        todayComponents: DateComponents = currentDateComponents(),
    ): String {
        val prefix = airsPrefix.takeIf { it.isNotBlank() } ?: "Airs"
        val tba = tbaLabel.takeIf { it.isNotBlank() } ?: "TBA"

        val trimmed = released?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed == null) {
            return "$prefix $tba"
        }

        val targetLocalDate = parseEpisodeReleaseLocalDate(trimmed)
        if (targetLocalDate == null) {
            return "$prefix $tba"
        }

        val todayIso = "${todayComponents.year.toString().padStart(4, '0')}-${todayComponents.month.toString().padStart(2, '0')}-${todayComponents.day.toString().padStart(2, '0')}"
        val daysUntil = daysUntilEpisodeRelease(
            todayIsoDate = todayIso,
            releasedDate = trimmed,
        )

        return when {
            daysUntil == 0 -> try {
                getString(Res.string.cw_airs_today)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                "$prefix Today"
            }
            daysUntil == 1 -> try {
                getString(Res.string.cw_airs_tomorrow)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                "$prefix Tomorrow"
            }
            daysUntil != null && daysUntil in 2..3 -> try {
                getPluralString(Res.plurals.cw_airs_in_days, daysUntil, daysUntil)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                "$prefix in $daysUntil days"
            }
            else -> {
                val formattedDate = formatDayFirstReleaseDate(
                    raw = trimmed,
                    includeYear = true,
                ) ?: return "$prefix $tba"

                try {
                    getString(Res.string.cw_airs_date, formattedDate)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    "$prefix $formattedDate"
                }
            }
        }
    }

    fun formatUnairedEpisodeMessageSync(
        released: String?,
        airsPrefix: String = "Airs",
        tbaLabel: String = "TBA",
        todayComponents: DateComponents = currentDateComponents(),
        getStringResource: ((StringResource, Array<out Any>) -> String)? = null,
        getPluralResource: ((PluralStringResource, Int, Array<out Any>) -> String)? = null,
    ): String {
        val prefix = airsPrefix.takeIf { it.isNotBlank() } ?: "Airs"
        val tba = tbaLabel.takeIf { it.isNotBlank() } ?: "TBA"

        val trimmed = released?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed == null) {
            return "$prefix $tba"
        }

        val targetLocalDate = parseEpisodeReleaseLocalDate(trimmed)
        if (targetLocalDate == null) {
            return "$prefix $tba"
        }

        val todayIso = "${todayComponents.year.toString().padStart(4, '0')}-${todayComponents.month.toString().padStart(2, '0')}-${todayComponents.day.toString().padStart(2, '0')}"
        val daysUntil = daysUntilEpisodeRelease(
            todayIsoDate = todayIso,
            releasedDate = trimmed,
        )

        return when {
            daysUntil == 0 -> getStringResource?.invoke(Res.string.cw_airs_today, emptyArray())
                ?: "$prefix Today"
            daysUntil == 1 -> getStringResource?.invoke(Res.string.cw_airs_tomorrow, emptyArray())
                ?: "$prefix Tomorrow"
            daysUntil != null && daysUntil in 2..3 -> getPluralResource?.invoke(
                Res.plurals.cw_airs_in_days,
                daysUntil,
                arrayOf(daysUntil),
            ) ?: "$prefix in $daysUntil days"
            else -> {
                val formattedDate = formatDayFirstReleaseDate(
                    raw = trimmed,
                    includeYear = true,
                ) ?: return "$prefix $tba"

                getStringResource?.invoke(Res.string.cw_airs_date, arrayOf(formattedDate))
                    ?: "$prefix $formattedDate"
            }
        }
    }

    private fun compareDate(
        y1: Int, m1: Int, d1: Int,
        y2: Int, m2: Int, d2: Int,
    ): Int {
        if (y1 != y2) return y1.compareTo(y2)
        if (m1 != m2) return m1.compareTo(m2)
        return d1.compareTo(d2)
    }

    val OUTRO_SEGMENT_TYPES = setOf("outro", "ed", "mixed-ed")
}

internal expect fun currentDateComponents(): DateComponents

data class DateComponents(val year: Int, val month: Int, val day: Int)
