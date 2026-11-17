package com.nuvio.app.features.party

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioIconActionButton
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.player.formatPlaybackTime
import com.nuvio.app.features.settings.SettingsSwitchRow
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_remove
import nuvio.composeapp.generated.resources.party_behind
import nuvio.composeapp.generated.resources.party_code_label
import nuvio.composeapp.generated.resources.party_copy_invite
import nuvio.composeapp.generated.resources.party_end
import nuvio.composeapp.generated.resources.party_end_message
import nuvio.composeapp.generated.resources.party_end_title
import nuvio.composeapp.generated.resources.party_host
import nuvio.composeapp.generated.resources.party_host_only_description
import nuvio.composeapp.generated.resources.party_host_only_note
import nuvio.composeapp.generated.resources.party_host_only_title
import nuvio.composeapp.generated.resources.party_invite_copied
import nuvio.composeapp.generated.resources.party_invite_message
import nuvio.composeapp.generated.resources.party_leave
import nuvio.composeapp.generated.resources.party_open_seat
import nuvio.composeapp.generated.resources.party_reconnecting
import nuvio.composeapp.generated.resources.party_remove_message
import nuvio.composeapp.generated.resources.party_remove_title
import nuvio.composeapp.generated.resources.party_seats
import nuvio.composeapp.generated.resources.party_share_description
import nuvio.composeapp.generated.resources.party_share_title
import nuvio.composeapp.generated.resources.party_you
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PartyOverview(
    party: ActiveParty,
    ringColor: Color,
    onRemove: (PartyMember) -> Unit,
    selfBehindMs: Long? = null,
) {
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val invite = stringResource(Res.string.party_invite_message, formatPartyCode(party.code), partyInviteLink(party.code))
    val copiedLabel = stringResource(Res.string.party_invite_copied)
    var copied by remember { mutableStateOf(false) }
    var memberToRemove by remember { mutableStateOf<PartyMember?>(null) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1800)
            copied = false
        }
    }
    val copyInvite = {
        clipboard.setText(AnnotatedString(invite))
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        copied = true
        NuvioToastController.show(copiedLabel)
    }

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        CodeSection(
            code = party.code,
            connected = party.connected,
            copied = copied,
            onCopy = copyInvite,
        )
        MembersSection(
            party = party,
            ringColor = ringColor,
            onRemove = { memberToRemove = it },
            onInvite = copyInvite,
            selfBehindMs = selfBehindMs,
        )
    }

    memberToRemove?.let { member ->
        ConfirmDialog(
            title = stringResource(Res.string.party_remove_title, member.displayName),
            message = stringResource(Res.string.party_remove_message),
            confirmLabel = stringResource(Res.string.action_remove),
            onConfirm = {
                memberToRemove = null
                onRemove(member)
            },
            onDismiss = { memberToRemove = null },
        )
    }
}

@Composable
private fun CodeSection(
    code: String,
    connected: Boolean,
    copied: Boolean,
    onCopy: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = connected,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                modifier = Modifier.weight(1f),
            ) { isConnected ->
                if (isConnected) {
                    Text(
                        text = stringResource(Res.string.party_code_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = tokens.colors.textMuted,
                    )
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PulsingDot()
                        Text(
                            text = stringResource(Res.string.party_reconnecting),
                            style = MaterialTheme.typography.labelLarge,
                            color = tokens.colors.accent,
                        )
                    }
                }
            }
            AnimatedContent(
                targetState = copied,
                transitionSpec = {
                    (scaleIn(spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), initialScale = 0.4f) + fadeIn()) togetherWith
                        (scaleOut(targetScale = 0.4f) + fadeOut())
                },
            ) { done ->
                NuvioIconActionButton(
                    icon = if (done) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(Res.string.party_copy_invite),
                    tint = if (done) tokens.colors.accent else tokens.colors.textPrimary,
                    onClick = onCopy,
                )
            }
        }
        CodeSlots(code = code)
    }
}

@Composable
private fun MembersSection(
    party: ActiveParty,
    ringColor: Color,
    onRemove: (PartyMember) -> Unit,
    onInvite: () -> Unit,
    selfBehindMs: Long?,
) {
    val tokens = MaterialTheme.nuvio
    val initialIds = remember { party.members.map { it.id }.toSet() }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.party_seats, party.members.size, party.maxMembers),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = tokens.colors.textMuted,
            )
            SeatDots(filled = party.members.size, total = party.maxMembers)
        }
        party.members.forEach { member ->
            key(member.id) {
                val appear = remember { MutableTransitionState(member.id in initialIds).apply { targetState = true } }
                AnimatedVisibility(
                    visibleState = appear,
                    enter = fadeIn(tween(240)) + expandVertically(tween(280)),
                ) {
                    MemberRow(
                        party = party,
                        member = member,
                        ringColor = ringColor,
                        onRemove = if (party.isHost && member.id != party.memberId) {
                            { onRemove(member) }
                        } else {
                            null
                        },
                        selfBehindMs = selfBehindMs,
                    )
                }
            }
        }
        if (party.members.size < party.maxMembers) {
            OpenSeatRow(onInvite = onInvite)
        }
    }
}

@Composable
private fun MemberRow(
    party: ActiveParty,
    member: PartyMember,
    ringColor: Color,
    onRemove: (() -> Unit)?,
    selfBehindMs: Long?,
) {
    val tokens = MaterialTheme.nuvio
    val isSelf = member.id == party.memberId
    val behindMs = if (isSelf) selfBehindMs else party.behindMs(member)
    val status = if (behindMs != null) {
        stringResource(Res.string.party_behind, formatPlaybackTime(behindMs))
    } else {
        stringResource(party.statusRes(member))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PartyAvatar(
            member = member,
            size = 44.dp,
            presence = party.presenceState(member),
            ringColor = ringColor,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isSelf) "${member.displayName} (${stringResource(Res.string.party_you)})" else member.displayName,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (member.id == party.hostMemberId) {
                    Text(
                        text = stringResource(Res.string.party_host),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.colors.accent,
                    )
                }
            }
            AnimatedContent(
                targetState = status,
                transitionSpec = {
                    (fadeIn(tween(200)) + slideInVertically(tween(240)) { it / 2 }) togetherWith
                        (fadeOut(tween(120)) + slideOutVertically(tween(160)) { -it / 2 })
                },
            ) { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                )
            }
        }
        if (onRemove != null) {
            NuvioIconActionButton(
                icon = Icons.Rounded.Close,
                contentDescription = stringResource(Res.string.action_remove),
                tint = tokens.colors.textMuted,
                onClick = onRemove,
            )
        }
    }
}

@Composable
private fun OpenSeatRow(onInvite: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onInvite)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenSeat(size = 44.dp)
        Text(
            text = stringResource(Res.string.party_open_seat),
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textSecondary,
        )
    }
}

@Composable
private fun SeatDots(filled: Int, total: Int) {
    val tokens = MaterialTheme.nuvio
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(total) { index ->
            val color by animateColorAsState(
                targetValue = if (index < filled) tokens.colors.accent else tokens.colors.borderDefault,
                animationSpec = tween(300),
            )
            Box(
                modifier = Modifier
                    .size(width = 14.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

@Composable
private fun PulsingDot() {
    val tokens = MaterialTheme.nuvio
    val transition = rememberInfiniteTransition()
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(tokens.colors.accent),
    )
}

@Composable
internal fun HostSettings(
    party: ActiveParty,
    onHostOnlyChange: (Boolean) -> Unit,
    onSharingChange: (Boolean) -> Unit,
    sharingNote: String? = null,
) {
    Column {
        PartySwitch(
            title = stringResource(Res.string.party_host_only_title),
            description = stringResource(Res.string.party_host_only_description),
            checked = party.hostOnly,
            onCheckedChange = onHostOnlyChange,
        )
        PartySwitch(
            title = stringResource(Res.string.party_share_title),
            description = stringResource(Res.string.party_share_description),
            checked = party.sharing,
            onCheckedChange = onSharingChange,
        )
        if (party.sharing && sharingNote != null) PartyNote(sharingNote)
    }
}

@Composable
private fun PartySwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    SettingsSwitchRow(
        title = title,
        description = description,
        checked = checked,
        isTablet = false,
        horizontalPadding = 0.dp,
        onCheckedChange = { value ->
            haptic.performHapticFeedback(if (value) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            onCheckedChange(value)
        },
    )
}

@Composable
internal fun HostOnlyNote() {
    val tokens = MaterialTheme.nuvio
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Lock,
            contentDescription = null,
            tint = tokens.colors.textSecondary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(Res.string.party_host_only_note),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textSecondary,
        )
    }
}

@Composable
internal fun PartyActions(
    party: ActiveParty,
    onLeave: () -> Unit,
    onEnd: () -> Unit,
    buttonModifier: Modifier = Modifier,
) {
    var confirmEnd by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DialogButton(
            text = stringResource(Res.string.party_leave),
            onClick = onLeave,
            modifier = buttonModifier.fillMaxWidth(),
        )
        if (party.isHost && party.members.size > 1) {
            DialogButton(
                text = stringResource(Res.string.party_end),
                onClick = { confirmEnd = true },
                style = DialogButtonStyle.Destructive,
                modifier = buttonModifier.fillMaxWidth(),
            )
        }
    }
    if (confirmEnd) {
        ConfirmDialog(
            title = stringResource(Res.string.party_end_title),
            message = stringResource(Res.string.party_end_message),
            confirmLabel = stringResource(Res.string.party_end),
            onConfirm = {
                confirmEnd = false
                onEnd()
            },
            onDismiss = { confirmEnd = false },
        )
    }
}

@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmStyle: DialogButtonStyle = DialogButtonStyle.Destructive,
) {
    val haptic = LocalHapticFeedback.current
    DialogSurface(
        onDismissRequest = onDismiss,
        title = title,
        message = message,
    ) {
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_cancel),
                onClick = onDismiss,
            )
            DialogButton(
                text = confirmLabel,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onConfirm()
                },
                style = confirmStyle,
            )
        }
    }
}
