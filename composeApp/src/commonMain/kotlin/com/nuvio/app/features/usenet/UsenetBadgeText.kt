package com.nuvio.app.features.usenet

import androidx.compose.runtime.Composable
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.usenet_badge_available
import nuvio.composeapp.generated.resources.usenet_badge_episode_failed
import nuvio.composeapp.generated.resources.usenet_badge_episode_played
import nuvio.composeapp.generated.resources.usenet_badge_season_pack
import nuvio.composeapp.generated.resources.usenet_badge_unavailable
import org.jetbrains.compose.resources.stringResource

/**
 * The text a label badge is shown with. Built-in Usenet badges keep their English names, which
 * tell them apart, and are shown translated; any other badge is shown by its name.
 */
@Composable
fun usenetBadgeText(name: String): String = when (name) {
    USENET_BADGE_UNAVAILABLE -> stringResource(Res.string.usenet_badge_unavailable)
    USENET_BADGE_AVAILABLE -> stringResource(Res.string.usenet_badge_available)
    USENET_BADGE_SEASON_PACK -> stringResource(Res.string.usenet_badge_season_pack)
    else -> USENET_SIBLING_BADGE.matchEntire(name)?.let { match ->
        val episode = match.groupValues[1]
        if (match.groupValues[2] == "FAILED") stringResource(Res.string.usenet_badge_episode_failed, episode)
        else stringResource(Res.string.usenet_badge_episode_played, episode)
    } ?: name
}
