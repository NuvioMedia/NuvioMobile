package com.nuvio.app.features.player

import com.nuvio.app.features.player.skip.AutoSkipSegmentType
import com.nuvio.app.features.player.skip.SkipInterval
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

internal const val AUTO_SKIP_NOTIFICATION_DURATION_MS = 1_400L

internal suspend fun SkipInterval.autoSkipNotificationMessage(seekPositionMs: Long): String? {
    val resource = when (AutoSkipSegmentType.fromSkipIntervalType(type)) {
        AutoSkipSegmentType.INTRO -> Res.string.player_auto_skip_intro_notification
        AutoSkipSegmentType.RECAP -> Res.string.player_auto_skip_recap_notification
        AutoSkipSegmentType.OUTRO -> Res.string.player_auto_skip_outro_notification
        AutoSkipSegmentType.MOVIE_CREDITS -> Res.string.player_auto_skip_movie_credits_notification
        null -> return null
    }
    return getString(resource, formatPlaybackTime(seekPositionMs))
}
