package com.nuvio.app.features.watchprogress

import androidx.compose.runtime.Composable
import com.nuvio.app.core.format.formatReleaseDateWithoutYear
import com.nuvio.app.core.time.daysUntilEpisodeRelease
import com.nuvio.app.core.time.parseEpisodeReleaseEpochMs
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun computeAirDateBadgeText(
    releasedIso: String?,
    todayIsoDate: String,
): String? {
    if (releasedIso.isNullOrBlank() || todayIsoDate.isBlank()) {
        return null
    }

    val releaseEpoch = parseEpisodeReleaseEpochMs(releasedIso)
    if (releaseEpoch != null && WatchProgressClock.nowEpochMs() >= releaseEpoch) {
        return null
    }

    val daysUntil = daysUntilEpisodeRelease(
        todayIsoDate = todayIsoDate,
        releasedDate = releasedIso,
    ) ?: return null

    return when {
        daysUntil < 0 -> null
        daysUntil == 0 -> stringResource(Res.string.cw_airs_today)
        daysUntil == 1 -> stringResource(Res.string.cw_airs_tomorrow)
        daysUntil in 2..7 -> pluralStringResource(Res.plurals.cw_airs_in_days, daysUntil, daysUntil)
        else -> {
            val formattedDate = formatReleaseDateWithoutYear(releasedIso)
            stringResource(Res.string.cw_airs_date, formattedDate)
        }
    }
}

fun parseReleaseDateToEpochMs(raw: String?): Long? {
    return parseEpisodeReleaseEpochMs(raw)
}

class ReleaseAlertState(
    val isReleaseAlert: Boolean,
    val isNewSeasonRelease: Boolean,
)

fun calculateReleaseAlertState(
    seedLastUpdatedEpochMs: Long,
    seedSeasonNumber: Int?,
    nextSeasonNumber: Int?,
    releasedIso: String?,
    releaseEpochMs: Long? = parseReleaseDateToEpochMs(releasedIso),
    nowEpochMs: Long = WatchProgressClock.nowEpochMs(),
): ReleaseAlertState {
    val releaseEpoch = releaseEpochMs
    val nowMs = nowEpochMs

    if (releaseEpoch == null) {
        return ReleaseAlertState(false, false)
    }

    val hasAired = nowMs >= releaseEpoch
    val sixtyDaysMs = 60L * 24 * 60 * 60 * 1000
    val isReleaseAlert = hasAired &&
        releaseEpoch > seedLastUpdatedEpochMs &&
        (nowMs - releaseEpoch) < sixtyDaysMs

    val isNewSeasonRelease = isReleaseAlert &&
        seedSeasonNumber != null &&
        nextSeasonNumber != null &&
        nextSeasonNumber != seedSeasonNumber

    return ReleaseAlertState(
        isReleaseAlert = isReleaseAlert,
        isNewSeasonRelease = isNewSeasonRelease
    )
}
