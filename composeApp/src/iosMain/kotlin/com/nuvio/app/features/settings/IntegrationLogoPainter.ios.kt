package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import com.nuvio.app.features.simkl.SimklBrandAsset
import com.nuvio.app.features.simkl.simklBrandPainter
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.haze_logo
import nuvio.composeapp.generated.resources.imdb_data_logo
import nuvio.composeapp.generated.resources.introdb_favicon
import nuvio.composeapp.generated.resources.mdblist_logo
import nuvio.composeapp.generated.resources.rating_tmdb
import nuvio.composeapp.generated.resources.trakt_tv_favicon
import org.jetbrains.compose.resources.painterResource

@Composable
internal actual fun integrationLogoPainter(logo: IntegrationLogo): Painter =
    when (logo) {
        IntegrationLogo.Tmdb -> painterResource(Res.drawable.rating_tmdb)
        IntegrationLogo.Trakt -> painterResource(Res.drawable.trakt_tv_favicon)
        IntegrationLogo.Simkl -> simklBrandPainter(SimklBrandAsset.Glyph)
        IntegrationLogo.MdbList -> painterResource(Res.drawable.mdblist_logo)
        IntegrationLogo.IntroDb -> painterResource(Res.drawable.introdb_favicon)
        IntegrationLogo.ImdbData -> painterResource(Res.drawable.imdb_data_logo)
        IntegrationLogo.Haze -> painterResource(Res.drawable.haze_logo)
    }
