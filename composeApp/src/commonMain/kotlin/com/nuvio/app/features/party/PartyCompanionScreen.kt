package com.nuvio.app.features.party

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_back
import nuvio.composeapp.generated.resources.party_companion_chatting_as
import nuvio.composeapp.generated.resources.party_companion_seat_left
import nuvio.composeapp.generated.resources.party_error_ended
import nuvio.composeapp.generated.resources.party_reconnecting
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PartyCompanionScreen(onBack: () -> Unit) {
    val state by PartyCompanion.state.collectAsStateWithLifecycle()
    val link = state.link
    val error = state.error
    PartyPage(onBack = onBack) {
        when {
            link != null -> LinkedChat(link)
            error != null -> CompanionError(error = error, onBack = onBack)
        }
    }
}

@Composable
private fun LinkedChat(link: PartyCompanionLink) {
    val tokens = MaterialTheme.nuvio
    val seat = link.seat
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (seat != null) PartyAvatar(member = seat, size = 40.dp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(Res.string.party_companion_chatting_as, seat?.displayName.orEmpty()),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (link.reconnecting) stringResource(Res.string.party_reconnecting) else link.media.displayTitle(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (link.reconnecting) tokens.colors.accent else tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        PartyChatContent(
            members = link.members,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        )
    }
}

@Composable
private fun CompanionError(
    error: PartyCompanionError,
    onBack: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = Icons.Rounded.LinkOff,
            contentDescription = null,
            tint = tokens.colors.textMuted,
            modifier = Modifier.size(36.dp),
        )
        Text(
            text = stringResource(
                when (error) {
                    PartyCompanionError.SeatLeft -> Res.string.party_companion_seat_left
                    PartyCompanionError.Ended -> Res.string.party_error_ended
                },
            ),
            modifier = Modifier.widthIn(max = 360.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        DialogButton(
            text = stringResource(Res.string.action_back),
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
