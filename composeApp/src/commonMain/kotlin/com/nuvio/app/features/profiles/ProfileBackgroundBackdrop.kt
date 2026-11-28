package com.nuvio.app.features.profiles

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.ProfileMeshBackground
import com.nuvio.app.features.membership.MemberAccessRepository
import com.nuvio.app.features.membership.ProfileBackgroundRepository
import com.nuvio.app.features.membership.ProfileBackgroundSelection
import com.nuvio.app.features.membership.resolveProfileBackground

@Composable
fun ProfileBackgroundBackdrop(
    profile: NuvioProfile?,
    modifier: Modifier = Modifier,
) {
    val memberAccess by remember {
        MemberAccessRepository.ensureStarted()
        MemberAccessRepository.access
    }.collectAsStateWithLifecycle()
    val backgroundCatalog by ProfileBackgroundRepository.catalog.collectAsStateWithLifecycle()
    val profileColor = remember(profile?.avatarColorHex) {
        profile?.avatarColorHex?.let(::parseHexColor) ?: Color(0xFF1E88E5)
    }
    val backgroundSelection = remember(
        profile?.profileBackgroundId,
        profile?.profileBackgroundUrl,
        memberAccess.entitlements,
    ) {
        profile?.let { resolveProfileBackground(it, memberAccess.entitlements) }
    }

    BoxWithConstraints(modifier = modifier) {
        val isPortrait = maxHeight > maxWidth
        LaunchedEffect(backgroundSelection, isPortrait) {
            val selectedId = (backgroundSelection as? ProfileBackgroundSelection.Catalog)?.id
            if (selectedId != null) {
                ProfileBackgroundRepository.loadSelectedAndPreload(selectedId, isPortrait)
            }
        }
        val backgroundModel: Any? = when (backgroundSelection) {
            is ProfileBackgroundSelection.Catalog -> backgroundCatalog
                .firstOrNull { it.id == backgroundSelection.id }
                ?.let { background ->
                    if (isPortrait) {
                        background.portraitImageBytes ?: background.landscapeImageBytes
                    } else {
                        background.landscapeImageBytes
                    }
                }
            is ProfileBackgroundSelection.Custom -> backgroundSelection.url
            null -> null
        }

        ProfileMeshBackground(
            profileColor = profileColor,
            modifier = Modifier.fillMaxSize(),
        )
        Crossfade(
            targetState = backgroundModel,
            animationSpec = tween(NuvioTokens.Motion.slowMillis),
            modifier = Modifier.fillMaxSize(),
            label = "profile_background",
        ) { model ->
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.28f)),
                )
            }
        }
    }
}
