package com.nuvio.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileBackgroundBackdrop
import com.nuvio.app.features.settings.AppBrandWordmark
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.app_brand_name
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LaunchScreen(
    profile: NuvioProfile?,
    backdropVisible: Boolean,
    showProfiles: Boolean,
    onLoadingShown: () -> Unit,
    modifier: Modifier = Modifier,
    profiles: @Composable () -> Unit,
) {
    val contentTransition = updateTransition(showProfiles, label = "launch_screen")
    val currentOnLoadingShown by rememberUpdatedState(onLoadingShown)
    LaunchedEffect(contentTransition.currentState, contentTransition.isRunning) {
        if (!contentTransition.currentState && !contentTransition.isRunning) {
            withFrameNanos { }
            currentOnLoadingShown()
        }
    }
    NuvioTheme {
        Box(modifier = modifier) {
            AnimatedVisibility(
                visible = backdropVisible,
                enter = fadeIn(tween(NuvioTokens.Motion.cinematicMillis)),
                exit = fadeOut(tween(NuvioTokens.Motion.slowMillis)),
                modifier = Modifier.fillMaxSize(),
            ) {
                ProfileBackgroundBackdrop(
                    profile = profile,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            contentTransition.AnimatedContent(
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    fadeIn(tween(NuvioTokens.Motion.slowMillis))
                        .togetherWith(fadeOut(tween(NuvioTokens.Motion.fastMillis)))
                },
            ) { profilesVisible ->
                if (profilesVisible) {
                    profiles()
                } else {
                    LoadingContent()
                }
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    val indicatorState = remember { MutableTransitionState(false).apply { targetState = true } }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppBrandWordmark(
                contentDescription = stringResource(Res.string.app_brand_name),
                modifier = Modifier.height(44.dp),
            )
            Spacer(modifier = Modifier.height(MaterialTheme.nuvio.spacing.sectionGap))
            AnimatedVisibility(
                visibleState = indicatorState,
                enter = fadeIn(
                    tween(
                        durationMillis = NuvioTokens.Motion.sheetEnterMillis,
                        delayMillis = NuvioTokens.Motion.cinematicMillis,
                    ),
                ),
            ) {
                NuvioLoadingIndicator()
            }
        }
    }
}
