package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** Flat black tab bar with icon-over-label items, like Netflix's phone app. */
@Composable
fun NetflixBottomBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.97f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(Color.White.copy(alpha = 0.08f)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(nuvioBottomNavigationBarInsets().asPaddingValues())
                .padding(top = 6.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
fun RowScope.NetflixBottomBarItem(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    drawable: DrawableResource? = null,
    customIcon: (@Composable () -> Unit)? = null,
) {
    val tint = if (selected) Color.White else NetflixColors.TextMuted
    Column(
        modifier = Modifier
            .weight(1f)
            .selectable(
                selected = selected,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            when {
                customIcon != null -> customIcon()
                icon != null -> Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                drawable != null -> Icon(painterResource(drawable), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            style = TextStyle(
                fontFamily = NetflixSans,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 10.sp,
                color = tint,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
