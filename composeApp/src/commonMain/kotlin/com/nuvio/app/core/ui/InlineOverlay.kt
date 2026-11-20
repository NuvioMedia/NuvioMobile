package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties

/**
 * True where Nuvio can't open separate windows for dialogs and bottom sheets: the Android Auto screen draws
 * Nuvio in a Presentation, and Android refuses dialog windows there (no activity to attach them to). There,
 * dialogs and sheets are drawn inside Nuvio's own window by [InlineOverlayHost] instead.
 */
val LocalUseInlineOverlays = staticCompositionLocalOf { false }

private class InlineOverlayEntry(
    val locals: State<CompositionLocalContext>,
    val alignment: Alignment,
    val onDismissRequest: State<() -> Unit>,
    val content: State<@Composable BoxScope.() -> Unit>,
)

private val inlineOverlays = mutableStateListOf<InlineOverlayEntry>()

/**
 * Shows [content] above the whole app (in [InlineOverlayHost]) for as long as this is composed, with a scrim
 * that dismisses on tap and on Back. Theme and other locals come from where it's called.
 */
@Composable
fun InlineOverlay(
    onDismissRequest: () -> Unit,
    alignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val locals = rememberUpdatedState(currentCompositionLocalContext)
    val dismiss = rememberUpdatedState(onDismissRequest)
    val body = rememberUpdatedState(content)
    val entry = remember { InlineOverlayEntry(locals, alignment, dismiss, body) }
    DisposableEffect(entry) {
        inlineOverlays.add(entry)
        onDispose { inlineOverlays.remove(entry) }
    }
}

/** Where [InlineOverlay]s draw; placed above the whole app. */
@Composable
fun InlineOverlayHost() {
    inlineOverlays.forEachIndexed { index, entry ->
        key(entry) {
            CompositionLocalProvider(entry.locals.value) {
                PlatformBackHandler(enabled = index == inlineOverlays.lastIndex) { entry.onDismissRequest.value() }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.nuvio.colors.overlayScrim)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            entry.onDismissRequest.value()
                        },
                ) {
                    Box(
                        Modifier
                            .align(entry.alignment)
                            // Taps inside the dialog stay inside it.
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                    ) {
                        entry.content.value(this)
                    }
                }
            }
        }
    }
}

/** [BasicAlertDialog], or the same content drawn inline where dialog windows aren't possible. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NuvioBasicDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    if (LocalUseInlineOverlays.current) {
        InlineOverlay(onDismissRequest = onDismissRequest) { content() }
    } else {
        BasicAlertDialog(onDismissRequest = onDismissRequest, properties = properties, content = content)
    }
}
