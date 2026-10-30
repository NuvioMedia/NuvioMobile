package com.nuvio.app.features.party

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio

@Composable
internal fun CodeField(
    code: String,
    onCodeChange: (String) -> Unit,
    onDone: () -> Unit,
    focusRequester: FocusRequester,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = TextFieldValue(code, selection = TextRange(code.length)),
        onValueChange = { value ->
            val next = normalizePartyCode(value.text).take(PARTY_CODE_LENGTH)
            if (next != code) onCodeChange(next)
        },
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(color = Color.Transparent),
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused },
        decorationBox = {
            CodeSlots(
                code = code,
                activeIndex = if (focused && enabled) code.length.coerceAtMost(PARTY_CODE_LENGTH - 1) else null,
                textStyle = MaterialTheme.typography.headlineLarge,
                slotHeight = 64.dp,
            )
        },
    )
}

@Composable
internal fun CodeSlots(
    code: String,
    modifier: Modifier = Modifier,
    activeIndex: Int? = null,
    textStyle: TextStyle = MaterialTheme.typography.headlineSmall,
    slotHeight: Dp = 48.dp,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(PARTY_CODE_LENGTH) { index ->
            CodeSlot(
                char = code.getOrNull(index),
                active = index == activeIndex,
                textStyle = textStyle,
                modifier = Modifier
                    .weight(1f)
                    .height(slotHeight),
            )
        }
    }
}

@Composable
private fun CodeSlot(
    char: Char?,
    active: Boolean,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val lineColor by animateColorAsState(
        targetValue = when {
            active -> tokens.colors.accent
            char != null -> tokens.colors.textSecondary
            else -> tokens.colors.borderDefault
        },
        animationSpec = tween(180),
    )
    val lineHeight by animateDpAsState(if (active) 3.dp else 2.dp, tween(180))
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = char,
                transitionSpec = {
                    (fadeIn(tween(120)) + scaleIn(spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), initialScale = 0.5f)) togetherWith
                        fadeOut(tween(90))
                },
            ) { value ->
                if (value != null) {
                    Text(
                        text = value.toString(),
                        style = textStyle,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.colors.textPrimary,
                    )
                } else if (active) {
                    Caret()
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(lineHeight)
                .clip(CircleShape)
                .background(lineColor),
        )
    }
}

@Composable
private fun Caret() {
    val tokens = MaterialTheme.nuvio
    val transition = rememberInfiniteTransition()
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
    )
    Box(
        modifier = Modifier
            .width(2.dp)
            .height(26.dp)
            .alpha(alpha)
            .background(tokens.colors.accent),
    )
}
