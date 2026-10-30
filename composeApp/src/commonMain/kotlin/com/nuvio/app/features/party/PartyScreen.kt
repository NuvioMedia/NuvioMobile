package com.nuvio.app.features.party

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioPlatformExtraTopPadding
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.party_host_hint
import nuvio.composeapp.generated.resources.party_hosted_by
import nuvio.composeapp.generated.resources.party_join_action
import nuvio.composeapp.generated.resources.party_join_description
import nuvio.composeapp.generated.resources.party_join_title
import nuvio.composeapp.generated.resources.party_joined_title
import nuvio.composeapp.generated.resources.party_seats
import nuvio.composeapp.generated.resources.party_title
import nuvio.composeapp.generated.resources.party_watch_with_party
import org.jetbrains.compose.resources.stringResource

private enum class PartyScreenMode {
    Join,
    Joined,
    Party,
}

@Composable
fun PartyScreen(
    initialCode: String,
    onBack: () -> Unit,
    onWatch: (PartyMedia) -> Unit,
) {
    val state by PartyRepository.state.collectAsStateWithLifecycle()
    val companion by PartyCompanion.state.collectAsStateWithLifecycle()
    val linkable = initialCode.isEmpty() && state.party == null
    if (linkable) {
        DisposableEffect(Unit) {
            PartyCompanion.start()
            onDispose { PartyCompanion.leave() }
        }
    }
    AnimatedContent(
        targetState = linkable && companion.active,
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
        modifier = Modifier.fillMaxSize(),
    ) { linked ->
        if (linked) {
            PartyCompanionScreen(onBack = onBack)
        } else {
            PartyHome(initialCode = initialCode, onBack = onBack, onWatch = onWatch)
        }
    }
}

@Composable
private fun PartyHome(
    initialCode: String,
    onBack: () -> Unit,
    onWatch: (PartyMedia) -> Unit,
) {
    val state by PartyRepository.state.collectAsStateWithLifecycle()
    val party = state.party
    var joined by remember { mutableStateOf<ActiveParty?>(null) }
    var pendingCode by remember {
        mutableStateOf(initialCode.takeIf { isValidPartyCode(it) && normalizePartyCode(it) != party?.code }.orEmpty())
    }
    var joining by remember { mutableStateOf(party == null || pendingCode.isNotEmpty()) }
    val mode = when {
        joined != null -> PartyScreenMode.Joined
        party != null && !joining -> PartyScreenMode.Party
        else -> PartyScreenMode.Join
    }
    LaunchedEffect(joined) {
        val current = joined ?: return@LaunchedEffect
        delay(1600)
        onWatch(current.media)
        joined = null
    }

    AnimatedContent(
        targetState = mode == PartyScreenMode.Party,
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
        modifier = Modifier.fillMaxSize(),
    ) { inParty ->
        if (inParty) {
            PartyRoom(onBack = onBack, onWatch = onWatch)
            return@AnimatedContent
        }
        NuvioScreen(modifier = Modifier.fillMaxSize()) {
            stickyHeader {
                NuvioScreenHeader(
                    title = stringResource(Res.string.party_title),
                    onBack = onBack,
                )
            }
            item {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    AnimatedContent(
                        targetState = mode,
                        transitionSpec = {
                            (fadeIn(tween(260, delayMillis = 60)) + scaleIn(tween(320), initialScale = 0.96f)) togetherWith fadeOut(tween(140))
                        },
                        modifier = Modifier
                            .widthIn(max = 520.dp)
                            .fillMaxWidth(),
                    ) { target ->
                        when (target) {
                            PartyScreenMode.Join -> JoinContent(
                                initialCode = pendingCode,
                                onJoined = { newParty ->
                                    pendingCode = ""
                                    joining = false
                                    joined = newParty
                                },
                            )
                            PartyScreenMode.Joined -> (joined ?: party)?.let { JoinedContent(it) }
                            PartyScreenMode.Party -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PartyRoom(
    onBack: () -> Unit,
    onWatch: (PartyMedia) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val state by PartyRepository.state.collectAsStateWithLifecycle()
    val unread by PartyChat.unread.collectAsStateWithLifecycle()
    val party = state.party ?: return
    var showChat by rememberSaveable { mutableStateOf(unread > 0) }
    PartyPage(onBack = onBack) {
        PartyTabs(
            chatSelected = showChat,
            unread = unread,
            onSelect = { showChat = it },
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        AnimatedContent(
            targetState = showChat,
            transitionSpec = { fadeIn(tween(200, delayMillis = 90)) togetherWith fadeOut(tween(90)) },
            modifier = Modifier.fillMaxSize(),
        ) { chat ->
            if (chat) {
                PartyChatContent(
                    members = party.members,
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = nuvioSafeBottomPadding(tokens.spacing.screenBottom)),
                ) {
                    PartyContent(party, onWatch)
                }
            }
        }
    }
}

@Composable
internal fun PartyPage(
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.colors.background)
            .padding(top = tokens.spacing.screenTop + statusBarTop + nuvioPlatformExtraTopPadding)
            .padding(horizontal = tokens.spacing.screenHorizontal),
    ) {
        NuvioScreenHeader(
            title = stringResource(Res.string.party_title),
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally),
            content = content,
        )
    }
}

@Composable
private fun JoinContent(
    initialCode: String,
    onJoined: (ActiveParty) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val state by PartyRepository.state.collectAsStateWithLifecycle()
    val available by PartyRepository.available.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val focusRequester = remember { FocusRequester() }
    val shake = remember { Animatable(0f) }
    val prefilled = remember { isValidPartyCode(initialCode) }
    var code by remember {
        mutableStateOf(if (prefilled) "" else normalizePartyCode(initialCode).take(PARTY_CODE_LENGTH))
    }
    var error by remember { mutableStateOf<PartyError?>(null) }
    var preview by remember { mutableStateOf<PartyPreview?>(null) }
    var previewing by remember { mutableStateOf(false) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    val blockedError = when {
        !available -> PartyError.Unavailable
        !PartyRepository.canUse -> PartyError.SignInRequired
        else -> null
    }
    val shownError = blockedError ?: error

    suspend fun reject(result: PartyError) {
        haptic.performHapticFeedback(HapticFeedbackType.Reject)
        error = result
        shake.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = 420
                -14f at 60
                12f at 120
                -8f at 180
                6f at 240
                -3f at 300
            },
        )
    }

    fun submit() {
        if (!isValidPartyCode(code) || state.busy || blockedError != null) return
        scope.launch {
            error = null
            val result = PartyRepository.join(code)
            val party = PartyRepository.state.value.party
            if (result == null && party != null) {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onJoined(party)
            } else {
                reject(result ?: PartyError.Network)
            }
        }
    }

    fun lookUp(target: String) {
        previewJob?.cancel()
        previewing = true
        previewJob = scope.launch {
            val result = PartyRepository.preview(target)
            previewing = false
            val failure = partyErrorOf(result.error)
            when {
                failure == null && result.media != null -> {
                    preview = result
                    if (result.isFull) error = PartyError.Full
                }
                failure != null && failure != PartyError.Network -> reject(failure)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (blockedError != null) return@LaunchedEffect
        if (prefilled) {
            val target = normalizePartyCode(initialCode).take(PARTY_CODE_LENGTH)
            delay(250)
            target.indices.forEach { index ->
                delay(60)
                code = target.take(index + 1)
            }
            lookUp(target)
        } else {
            delay(650)
            runCatching { focusRequester.requestFocus() }
            delay(550)
            runCatching { focusRequester.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AnimatedContent(
            targetState = preview,
            contentKey = { it != null },
            transitionSpec = {
                (fadeIn(tween(240, delayMillis = 60)) + slideInVertically(tween(300)) { it / 10 }) togetherWith fadeOut(tween(140))
            },
        ) { current ->
            if (current != null) {
                PreviewCard(current)
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SeatsHero(
                        size = 72.dp,
                        ringColor = tokens.colors.background,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    Text(
                        text = stringResource(Res.string.party_join_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.colors.textPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(Res.string.party_join_description),
                        style = MaterialTheme.typography.bodyLarge,
                        color = tokens.colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        CodeField(
            code = code,
            onCodeChange = { value ->
                if (state.busy) return@CodeField
                if (value.length > code.length) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                code = value
                error = null
                preview = null
                previewJob?.cancel()
                previewing = false
                if (isValidPartyCode(value)) lookUp(value)
            },
            onDone = ::submit,
            focusRequester = focusRequester,
            enabled = blockedError == null,
            modifier = Modifier
                .padding(top = 8.dp)
                .graphicsLayer { translationX = shake.value.dp.toPx() },
        )
        AnimatedContent(
            targetState = shownError,
            transitionSpec = {
                (fadeIn(tween(180)) + slideInVertically(tween(220)) { -it / 2 }) togetherWith fadeOut(tween(120))
            },
        ) { current ->
            if (current != null) {
                Text(
                    text = stringResource(current.messageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.danger,
                    textAlign = TextAlign.Center,
                )
            }
        }
        DialogButton(
            text = stringResource(Res.string.party_join_action),
            onClick = ::submit,
            style = DialogButtonStyle.Primary,
            enabled = isValidPartyCode(code) && blockedError == null &&
                error !in setOf(PartyError.NotFound, PartyError.Removed, PartyError.RateLimited),
            loading = state.busy || previewing,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        )
        ExperimentalNote(textAlign = TextAlign.Center)
        AnimatedVisibility(
            visible = preview == null,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(120)),
        ) {
            Text(
                text = stringResource(Res.string.party_host_hint),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PreviewCard(preview: PartyPreview) {
    val tokens = MaterialTheme.nuvio
    val media = preview.media ?: return
    val host = preview.host
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MediaHeader(media, centered = true)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            if (host != null) {
                PartyAvatar(member = host, size = 28.dp)
                Text(
                    text = stringResource(Res.string.party_hosted_by, host.displayName),
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(Res.string.party_seats, preview.members, preview.maxMembers),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }
    }
}

@Composable
private fun MediaHeader(media: PartyMedia, centered: Boolean = false) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MediaTitle(media, centered)
        media.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MediaTitle(media: PartyMedia, centered: Boolean) {
    val tokens = MaterialTheme.nuvio
    var logoFailed by remember(media.logo) { mutableStateOf(false) }
    val logo = media.logo?.takeIf { it.isNotBlank() && !logoFailed }
    val textAlign = if (centered) TextAlign.Center else TextAlign.Start
    Column(
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (logo != null) {
            AsyncImage(
                model = logo,
                contentDescription = media.title,
                contentScale = ContentScale.Fit,
                alignment = if (centered) Alignment.Center else Alignment.CenterStart,
                onError = { logoFailed = true },
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(80.dp),
            )
        } else if (media.title.isNotBlank()) {
            Text(
                text = media.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
                textAlign = textAlign,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        media.episodeLabel()?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = tokens.colors.textSecondary,
                textAlign = textAlign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun JoinedContent(party: ActiveParty) {
    val tokens = MaterialTheme.nuvio
    val host = party.member(party.hostMemberId)?.displayName
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AvatarStack(
            members = party.members,
            size = 80.dp,
            animateIn = true,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            text = stringResource(Res.string.party_joined_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textPrimary,
        )
        if (host != null) {
            Text(
                text = stringResource(Res.string.party_hosted_by, host),
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textSecondary,
            )
        }
        MediaTitle(
            media = party.media,
            centered = true,
        )
    }
}

@Composable
private fun PartyContent(
    party: ActiveParty,
    onWatch: (PartyMedia) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        MediaHeader(party.media)
        PartyOverview(
            party = party,
            ringColor = tokens.colors.background,
            onRemove = { member -> PartyRepository.removeMember(member.id) },
        )
        if (party.isHost) {
            HostSettings(
                party = party,
                onHostOnlyChange = PartyRepository::setHostOnly,
                onSharingChange = PartyRepository::setSharing,
            )
        } else if (party.hostOnly) {
            HostOnlyNote()
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DialogButton(
                text = stringResource(Res.string.party_watch_with_party),
                onClick = { onWatch(party.media) },
                style = DialogButtonStyle.Primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            )
            PartyActions(
                party = party,
                onLeave = PartyRepository::leave,
                onEnd = PartyRepository::end,
                buttonModifier = Modifier.height(52.dp),
            )
        }
    }
}
