package com.nuvio.app.features.party

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.player.PlayerDialogButton
import com.nuvio.app.features.player.PlayerPanelHeader
import com.nuvio.app.features.player.PlayerSidePanel
import com.nuvio.app.features.player.formatPlaybackTime
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.party_behind_title
import nuvio.composeapp.generated.resources.party_catch_up
import nuvio.composeapp.generated.resources.party_experimental
import nuvio.composeapp.generated.resources.party_now_watching
import nuvio.composeapp.generated.resources.party_share_unavailable
import nuvio.composeapp.generated.resources.party_sign_in_required
import nuvio.composeapp.generated.resources.party_start_action
import nuvio.composeapp.generated.resources.party_start_description
import nuvio.composeapp.generated.resources.party_start_title
import nuvio.composeapp.generated.resources.party_switch
import nuvio.composeapp.generated.resources.party_switch_confirm
import nuvio.composeapp.generated.resources.party_switch_message
import nuvio.composeapp.generated.resources.party_switch_title
import nuvio.composeapp.generated.resources.party_title
import nuvio.composeapp.generated.resources.party_unavailable
import nuvio.composeapp.generated.resources.party_watch_with_party
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PartyPanel(
    visible: Boolean,
    state: PartyUiState,
    playerMedia: PartyMedia,
    isSupported: Boolean,
    canStart: Boolean,
    onStart: () -> Unit,
    onSwitchParty: () -> Unit,
    onWatchWithParty: () -> Unit,
    onLeave: () -> Unit,
    onEnd: () -> Unit,
    onRemove: (PartyMember) -> Unit,
    onHostOnlyChange: (Boolean) -> Unit,
    canShareStream: Boolean,
    onSharingChange: (Boolean) -> Unit,
    behindMs: Long?,
    onCatchUp: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val partyId = state.party?.id
    var lastPartyId by remember { mutableStateOf(partyId) }
    LaunchedEffect(partyId) {
        if (partyId != null && lastPartyId == null && visible) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        lastPartyId = partyId
    }

    PlayerSidePanel(
        visible = visible,
        onDismiss = onDismiss,
        width = 420.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 24.dp, end = 24.dp, top = 24.dp),
        ) {
            PlayerPanelHeader(title = stringResource(Res.string.party_title)) {
                PlayerDialogButton(
                    label = stringResource(Res.string.action_close),
                    onClick = onDismiss,
                )
            }
            Spacer(Modifier.height(20.dp))
            AnimatedContent(
                targetState = state.party,
                contentKey = { it != null },
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
                modifier = Modifier.fillMaxSize(),
            ) { party ->
                if (party == null) {
                    StartContent(
                        busy = state.busy,
                        isSupported = isSupported,
                        canStart = canStart,
                        onStart = onStart,
                    )
                } else {
                    PartyContent(
                        party = party,
                        playerMedia = playerMedia,
                        behindMs = behindMs,
                        onCatchUp = onCatchUp,
                        onSwitchParty = onSwitchParty,
                        onWatchWithParty = onWatchWithParty,
                        onLeave = onLeave,
                        onEnd = onEnd,
                        onRemove = onRemove,
                        onHostOnlyChange = onHostOnlyChange,
                        canShareStream = canShareStream,
                        onSharingChange = onSharingChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun StartContent(
    busy: Boolean,
    isSupported: Boolean,
    canStart: Boolean,
    onStart: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SeatsHero(
            size = 56.dp,
            ringColor = tokens.colors.surfaceElevated,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            text = stringResource(Res.string.party_start_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textPrimary,
        )
        Text(
            text = stringResource(Res.string.party_start_description),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textSecondary,
        )
        when {
            !isSupported -> PartyNote(stringResource(Res.string.party_unavailable))
            !canStart -> PartyNote(stringResource(Res.string.party_sign_in_required))
            else -> DialogButton(
                text = stringResource(Res.string.party_start_action),
                onClick = onStart,
                style = DialogButtonStyle.Primary,
                loading = busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
        ExperimentalNote()
    }
}

@Composable
private fun PartyContent(
    party: ActiveParty,
    playerMedia: PartyMedia,
    behindMs: Long?,
    onCatchUp: () -> Unit,
    onSwitchParty: () -> Unit,
    onWatchWithParty: () -> Unit,
    onLeave: () -> Unit,
    onEnd: () -> Unit,
    onRemove: (PartyMember) -> Unit,
    onHostOnlyChange: (Boolean) -> Unit,
    canShareStream: Boolean,
    onSharingChange: (Boolean) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    var confirmSwitch by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        var lastBehindMs by remember { mutableStateOf(0L) }
        if (behindMs != null) lastBehindMs = behindMs
        AnimatedVisibility(
            visible = behindMs != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(Res.string.party_behind_title, formatPlaybackTime(lastBehindMs)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                )
                DialogButton(
                    text = stringResource(Res.string.party_catch_up),
                    onClick = onCatchUp,
                    style = DialogButtonStyle.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        PartyOverview(
            party = party,
            ringColor = tokens.colors.surfaceElevated,
            onRemove = onRemove,
            selfBehindMs = behindMs,
        )
        if (party.isHost) {
            HostSettings(
                party = party,
                onHostOnlyChange = onHostOnlyChange,
                onSharingChange = onSharingChange,
                sharingNote = stringResource(Res.string.party_share_unavailable).takeUnless { canShareStream },
            )
        }
        AnimatedVisibility(
            visible = !party.isHost && party.hostOnly,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            HostOnlyNote()
        }
        AnimatedVisibility(
            visible = playerMedia.key != party.media.key,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(Res.string.party_now_watching, party.media.displayTitle()),
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                )
                DialogButton(
                    text = stringResource(Res.string.party_watch_with_party),
                    onClick = onWatchWithParty,
                    style = DialogButtonStyle.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (party.isHost) {
                    DialogButton(
                        text = stringResource(Res.string.party_switch, playerMedia.displayTitle()),
                        onClick = { confirmSwitch = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        PartyActions(
            party = party,
            onLeave = onLeave,
            onEnd = onEnd,
        )
    }
    if (confirmSwitch) {
        ConfirmDialog(
            title = stringResource(Res.string.party_switch_title),
            message = stringResource(Res.string.party_switch_message, playerMedia.displayTitle()),
            confirmLabel = stringResource(Res.string.party_switch_confirm),
            confirmStyle = DialogButtonStyle.Primary,
            onConfirm = {
                confirmSwitch = false
                onSwitchParty()
            },
            onDismiss = { confirmSwitch = false },
        )
    }
}

@Composable
internal fun SeatsHero(
    size: Dp,
    ringColor: Color,
    modifier: Modifier = Modifier,
) {
    val profile = rememberProfileAvatar()
    val step = size * 0.72f
    Box(modifier = modifier.width(size + step * 2)) {
        OpenSeat(size = size, modifier = Modifier.offset(x = step * 2))
        OpenSeat(size = size, modifier = Modifier.offset(x = step))
        PartyAvatar(
            name = profile.name,
            imageUrl = profile.imageUrl,
            color = profile.color,
            size = size,
            modifier = Modifier
                .zIndex(1f)
                .border(3.dp, ringColor, CircleShape),
        )
    }
}

@Composable
internal fun PartyNote(text: String) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = tokens.colors.textMuted,
    )
}

@Composable
internal fun ExperimentalNote(textAlign: TextAlign = TextAlign.Start) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = stringResource(Res.string.party_experimental),
        style = MaterialTheme.typography.bodySmall,
        color = tokens.colors.textMuted,
        textAlign = textAlign,
    )
}
