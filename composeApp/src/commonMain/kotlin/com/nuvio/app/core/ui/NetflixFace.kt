package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.bebas_neue
import org.jetbrains.compose.resources.Font

/** True while the app wears Dave's Netflix-style face (Settings > Appearance > Netflix face). */
val LocalNetflixFace = staticCompositionLocalOf { false }

/**
 * Whether the Netflix layouts should be used here. The Android Auto car screen keeps the layouts it
 * was tuned for, so this is false there even when the face is on.
 */
@Composable
@ReadOnlyComposable
fun netflixFaceActive(): Boolean = LocalNetflixFace.current && !LocalUseInlineOverlays.current

object NetflixColors {
    val Red = Color(0xFFE50914)
    val RedDark = Color(0xFFB20710)
    val Black = Color(0xFF000000)
    val Surface = Color(0xFF141414)
    val Card = Color(0xFF232323)
    val Elevated = Color(0xFF2B2B2B)
    val ButtonGrey = Color(0xFF6D6D6E).copy(alpha = 0.7f)
    val ChipBorder = Color(0xFFB3B3B3).copy(alpha = 0.55f)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFB3B3B3)
    val TextMuted = Color(0xFF808080)
    val ProgressTrack = Color(0xFF5A5A5A)
    val Match = Color(0xFF46D369)
}

val NetflixDisplayFamily: FontFamily
    @Composable
    get() = FontFamily(Font(Res.font.bebas_neue, FontWeight.Normal))

/** The red block wordmark in the top-left of the home screen. */
@Composable
fun NetflixWordmark(
    modifier: Modifier = Modifier,
    text: String = "NUVIO",
    fontSize: Int = 34,
) {
    Text(
        text = text,
        modifier = modifier,
        style = TextStyle(
            fontFamily = NetflixDisplayFamily,
            fontSize = fontSize.sp,
            letterSpacing = 1.sp,
            color = NetflixColors.Red,
        ),
        maxLines = 1,
    )
}

@Composable
fun NetflixRowTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style = TextStyle(
            fontFamily = NetflixSans,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            lineHeight = 22.sp,
            color = NetflixColors.TextPrimary,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** White "▶ Play" button. */
@Composable
fun NetflixPlayButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 40.dp,
    icon: ImageVector = Icons.Rounded.PlayArrow,
) {
    NetflixButton(
        label = label,
        icon = icon,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        height = height,
        background = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
        content = Color.Black,
    )
}

/** Translucent grey "+ My List" style button. */
@Composable
fun NetflixGreyButton(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 40.dp,
) {
    NetflixButton(
        label = label,
        icon = icon,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        height = height,
        background = NetflixColors.ButtonGrey,
        content = Color.White,
    )
}

@Composable
private fun NetflixButton(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    height: Dp,
    background: Color,
    content: Color,
) {
    Row(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(PaddingValues(horizontal = 14.dp)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = TextStyle(
                fontFamily = NetflixSans,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = content,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Icon with a small label underneath, used for "My List / Rate / Share" rows. */
@Composable
fun NetflixIconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .widthIn(min = 64.dp)
            .clip(RoundedCornerShape(6.dp))
            .netflixCombinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(5.dp))
        Text(
            text = label,
            style = TextStyle(
                fontFamily = NetflixSans,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                color = NetflixColors.TextSecondary,
            ),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun Modifier.netflixCombinedClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
): Modifier = this.then(
    Modifier.combinedClickable(
        onClick = onClick,
        onLongClick = onLongClick,
        role = Role.Button,
    ),
)

/** Netflix's red progress bar on a dark track. */
@Composable
fun NetflixProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 3.dp,
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .height(height)
            .background(NetflixColors.ProgressTrack),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .height(height)
                .fillMaxWidthFraction(fraction.coerceIn(0f, 1f))
                .background(NetflixColors.Red),
        )
    }
}

private fun Modifier.fillMaxWidthFraction(fraction: Float): Modifier = this.fillMaxWidth(fraction)
