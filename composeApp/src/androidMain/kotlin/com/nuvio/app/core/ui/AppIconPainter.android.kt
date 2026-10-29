package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import com.nuvio.app.R

@Composable
actual fun appIconPainter(icon: AppIconResource): Painter =
    painterResource(
        id = when (icon) {
            AppIconResource.PlayerPlay -> R.drawable.ic_player_play
            AppIconResource.PlayerPause -> R.drawable.ic_player_pause
            AppIconResource.PlayerAspectRatio -> R.drawable.ic_player_aspect_ratio
            AppIconResource.PlayerSubtitles -> R.drawable.ic_player_subtitles
            AppIconResource.PlayerAudioFilled -> R.drawable.ic_player_audio_filled
            AppIconResource.PlayerSource -> R.drawable.ic_player_source
            AppIconResource.PlayerEpisodes -> R.drawable.ic_player_episodes
            AppIconResource.LibraryAddPlus -> R.drawable.library_add_plus
        }
    )
