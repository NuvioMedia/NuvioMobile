package com.nuvio.app.core.i18n

import androidx.compose.runtime.Composable
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun localizedMediaStatusLabel(status: String, isSeries: Boolean): String {
    val resource = when (status.trim().lowercase()) {
        "ended" -> if (isSeries) Res.string.series_status_ended else Res.string.movie_status_ended
        "continuing", "returning series" -> if (isSeries) Res.string.series_status_continuing else Res.string.movie_status_continuing
        "current" -> if (isSeries) Res.string.series_status_current else Res.string.movie_status_current
        "cancelled", "canceled" -> if (isSeries) Res.string.series_status_cancelled else Res.string.movie_status_cancelled
        "released" -> if (isSeries) Res.string.series_status_released else Res.string.movie_status_released
        "planned" -> if (isSeries) Res.string.series_status_planned else Res.string.movie_status_planned
        "rumored" -> if (isSeries) Res.string.series_status_rumored else Res.string.movie_status_rumored
        "in production" -> if (isSeries) Res.string.series_status_in_production else Res.string.movie_status_in_production
        "post production" -> if (isSeries) Res.string.series_status_post_production else Res.string.movie_status_post_production
        else -> return status.trim()
    }
    return stringResource(resource)
}
