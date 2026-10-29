package com.nuvio.app.features.party

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.format.formatLocalTime
import com.nuvio.app.core.ui.ResizeForKeyboard
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.party_chat_empty_message
import nuvio.composeapp.generated.resources.party_chat_empty_title
import nuvio.composeapp.generated.resources.party_chat_new_messages
import nuvio.composeapp.generated.resources.party_chat_not_sent
import nuvio.composeapp.generated.resources.party_chat_placeholder
import nuvio.composeapp.generated.resources.party_chat_send
import nuvio.composeapp.generated.resources.party_tab_chat
import nuvio.composeapp.generated.resources.party_tab_party
import org.jetbrains.compose.resources.stringResource

internal data class PartyChatRow(
    val message: PartyChatMessage,
    val startsGroup: Boolean,
    val endsGroup: Boolean,
    val showTime: Boolean,
)

internal fun partyChatRows(messages: List<PartyChatMessage>): List<PartyChatRow> =
    messages.mapIndexed { index, message ->
        val previous = messages.getOrNull(index - 1)
        val next = messages.getOrNull(index + 1)
        val showTime = previous == null || message.sentAt - previous.sentAt > TIME_GAP_MS
        val nextShowsTime = next != null && next.sentAt - message.sentAt > TIME_GAP_MS
        PartyChatRow(
            message = message,
            startsGroup = showTime || previous?.author != message.author,
            endsGroup = next == null || nextShowsTime || next.author != message.author,
            showTime = showTime,
        )
    }

private val PartyChatMessage.author: String
    get() = if (mine) "" else memberId

@Composable
internal fun PartyChatContent(
    members: List<PartyMember>,
    modifier: Modifier = Modifier,
) {
    val messages by PartyChat.messages.collectAsStateWithLifecycle()
    val rows = remember(messages) { partyChatRows(messages).asReversed() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    ResizeForKeyboard()
    DisposableEffect(Unit) {
        PartyChat.setOpen(true)
        onDispose {
            PartyChat.setOpen(false)
            keyboard?.hide()
        }
    }
    Column(modifier = modifier.imePadding()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
        ) {
            if (rows.isEmpty()) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    EmptyChat(
                        compact = maxHeight < 150.dp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            } else {
                MessageList(rows = rows, members = members)
            }
        }
        Composer()
    }
}

@Composable
private fun MessageList(
    rows: List<PartyChatRow>,
    members: List<PartyMember>,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var newBelow by remember { mutableStateOf(false) }
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val newest = rows.firstOrNull()?.message
    LaunchedEffect(newest?.key) {
        if (newest == null) return@LaunchedEffect
        if (newest.mine || listState.firstVisibleItemIndex <= 1) {
            listState.animateScrollToItem(0)
        } else {
            newBelow = true
        }
    }
    LaunchedEffect(atBottom) {
        if (atBottom) newBelow = false
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val bubbleWidth = maxWidth * 0.78f
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(rows, key = { it.message.key }) { row ->
                MessageRow(
                    row = row,
                    members = members,
                    bubbleWidth = bubbleWidth,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        NewMessagesButton(
            visible = newBelow,
            listState = listState,
            onClick = {
                newBelow = false
                scope.launch { listState.animateScrollToItem(0) }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun MessageRow(
    row: PartyChatRow,
    members: List<PartyMember>,
    bubbleWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val message = row.message
    val member = members.firstOrNull { it.id == message.memberId }
        ?: PartyMember(id = message.memberId, displayName = message.name, avatarUrl = message.avatarUrl, avatarColor = message.avatarColor)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (row.startsGroup) 12.dp else 2.dp),
        horizontalAlignment = if (message.mine) Alignment.End else Alignment.Start,
    ) {
        if (row.showTime) {
            Text(
                text = formatLocalTime(message.sentAt),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
                textAlign = TextAlign.Center,
            )
        }
        if (row.startsGroup && !message.mine) {
            Text(
                text = member.displayName,
                modifier = Modifier
                    .padding(start = AVATAR_SIZE + AVATAR_GAP, bottom = 4.dp)
                    .widthIn(max = bubbleWidth),
                style = MaterialTheme.typography.labelMedium,
                color = tokens.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            if (!message.mine) {
                if (row.endsGroup) {
                    PartyAvatar(member = member, size = AVATAR_SIZE)
                } else {
                    Spacer(Modifier.size(AVATAR_SIZE))
                }
                Spacer(Modifier.width(AVATAR_GAP))
            }
            Bubble(row = row, maxWidth = bubbleWidth)
        }
        if (message.status == PartyChatStatus.Failed) {
            Text(
                text = stringResource(Res.string.party_chat_not_sent),
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { PartyChat.retry(message.key) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.danger,
            )
        }
    }
}

@Composable
private fun Bubble(
    row: PartyChatRow,
    maxWidth: Dp,
) {
    val tokens = MaterialTheme.nuvio
    val message = row.message
    val alpha by animateFloatAsState(if (message.status == PartyChatStatus.Sent) 1f else 0.55f)
    val joinedTop = if (row.startsGroup) BUBBLE_RADIUS else BUBBLE_JOIN_RADIUS
    val joinedBottom = if (row.endsGroup) BUBBLE_RADIUS else BUBBLE_JOIN_RADIUS
    val shape = if (message.mine) {
        RoundedCornerShape(topStart = BUBBLE_RADIUS, topEnd = joinedTop, bottomEnd = joinedBottom, bottomStart = BUBBLE_RADIUS)
    } else {
        RoundedCornerShape(topStart = joinedTop, topEnd = BUBBLE_RADIUS, bottomEnd = BUBBLE_RADIUS, bottomStart = joinedBottom)
    }
    Text(
        text = message.body,
        modifier = Modifier
            .widthIn(max = maxWidth)
            .graphicsLayer { this.alpha = alpha }
            .clip(shape)
            .background(if (message.mine) tokens.colors.accent else tokens.colors.surfaceCard)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = if (message.mine) tokens.colors.onAccent else tokens.colors.textPrimary,
    )
}

@Composable
private fun NewMessagesButton(
    visible: Boolean,
    listState: LazyListState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    AnimatedVisibility(
        visible = visible && listState.firstVisibleItemIndex > 0,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(tokens.colors.accent)
                .clickable(onClick = onClick)
                .padding(start = 12.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.ArrowDownward,
                contentDescription = null,
                tint = tokens.colors.onAccent,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(Res.string.party_chat_new_messages),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.onAccent,
            )
        }
    }
}

@Composable
private fun Composer() {
    val tokens = MaterialTheme.nuvio
    val haptic = LocalHapticFeedback.current
    var text by remember { mutableStateOf(PartyChat.draft.value) }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val canSend = text.isNotBlank()
    val send = {
        if (text.isNotBlank()) {
            PartyChat.send(text)
            text = ""
            PartyChat.draft.value = ""
            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = if (keyboardOpen) 8.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { value ->
                if (value.length == text.length + 1 && value.count { it == '\n' } > text.count { it == '\n' }) {
                    send()
                } else {
                    text = value.take(PartyChat.MAX_LENGTH)
                    PartyChat.draft.value = text
                }
            },
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = tokens.colors.textPrimary),
            cursorBrush = SolidColor(tokens.colors.accent),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { send() }),
            maxLines = 4,
            decorationBox = { field ->
                Box(
                    modifier = Modifier
                        .heightIn(min = CONTROL_SIZE)
                        .clip(RoundedCornerShape(CONTROL_SIZE / 2))
                        .background(tokens.colors.surfaceCard)
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.party_chat_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textMuted,
                            maxLines = 1,
                        )
                    }
                    field()
                }
            },
        )
        SendButton(enabled = canSend, onClick = send)
    }
}

@Composable
private fun SendButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val background by animateColorAsState(if (enabled) tokens.colors.accent else tokens.colors.surfaceCard)
    val tint by animateColorAsState(if (enabled) tokens.colors.onAccent else tokens.colors.textMuted)
    Box(
        modifier = Modifier
            .size(CONTROL_SIZE)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.ArrowUpward,
            contentDescription = stringResource(Res.string.party_chat_send),
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun EmptyChat(
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!compact) {
            Icon(
                imageVector = Icons.Rounded.ChatBubbleOutline,
                contentDescription = null,
                tint = tokens.colors.textMuted,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .size(32.dp),
            )
        }
        Text(
            text = stringResource(Res.string.party_chat_empty_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (!compact) {
            Text(
                text = stringResource(Res.string.party_chat_empty_message),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun PartyTabs(
    chatSelected: Boolean,
    unread: Int,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.colors.surfaceCard)
            .padding(3.dp),
    ) {
        val segment = maxWidth / 2
        val offset by animateDpAsState(
            targetValue = if (chatSelected) segment else 0.dp,
            animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
        )
        Box(
            modifier = Modifier
                .offset { IntOffset(offset.roundToPx(), 0) }
                .width(segment)
                .fillMaxHeight()
                .clip(RoundedCornerShape(9.dp))
                .background(tokens.colors.textPrimary.copy(alpha = 0.12f)),
        )
        Row(modifier = Modifier.fillMaxSize()) {
            PartyTab(
                label = stringResource(Res.string.party_tab_party),
                selected = !chatSelected,
                badge = 0,
                onClick = { onSelect(false) },
            )
            PartyTab(
                label = stringResource(Res.string.party_tab_chat),
                selected = chatSelected,
                badge = unread,
                onClick = { onSelect(true) },
            )
        }
    }
}

@Composable
private fun RowScope.PartyTab(
    label: String,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val color by animateColorAsState(if (selected) tokens.colors.textPrimary else tokens.colors.textSecondary)
    Row(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = color,
        )
        UnreadBadge(
            count = badge,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
internal fun UnreadBadge(
    count: Int,
    modifier: Modifier = Modifier,
    icon: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    var shown by remember { mutableStateOf(count) }
    if (count > 0) shown = count
    AnimatedVisibility(
        visible = count > 0,
        enter = scaleIn() + fadeIn(),
        exit = scaleOut() + fadeOut(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 18.dp)
                .widthIn(min = 18.dp)
                .clip(CircleShape)
                .background(tokens.colors.accent)
                .padding(horizontal = if (icon) 7.dp else 5.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon) {
                Icon(
                    imageVector = Icons.Rounded.ChatBubbleOutline,
                    contentDescription = null,
                    tint = tokens.colors.onAccent,
                    modifier = Modifier.size(12.dp),
                )
            }
            Text(
                text = if (shown > 9) "9+" else shown.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = tokens.colors.onAccent,
            )
        }
    }
}

@Composable
internal fun keyboardCrowds(height: Dp): Boolean {
    val density = LocalDensity.current
    val keyboard = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    return keyboard > 0.dp && height - keyboard < CROWDED_HEIGHT
}

private val AVATAR_SIZE = 28.dp
private val AVATAR_GAP = 8.dp
private val BUBBLE_RADIUS = 18.dp
private val BUBBLE_JOIN_RADIUS = 6.dp
private val CONTROL_SIZE = 44.dp
private val CROWDED_HEIGHT = 340.dp
private const val TIME_GAP_MS = 10 * 60 * 1000L
