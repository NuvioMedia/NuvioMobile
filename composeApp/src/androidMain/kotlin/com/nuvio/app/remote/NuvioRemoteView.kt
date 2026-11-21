package com.nuvio.app.remote

import android.content.Context
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.AppScreenTab
import com.nuvio.app.features.player.NuvioPlaybackRemote
import kotlinx.coroutines.delay

/** What the phone remote can do; implemented by Nuvio Auto (androidApp), which knows about the car. */
interface NuvioRemoteActions {
    fun isConnected(): Boolean
    fun search(query: String)
    fun voiceSearch()
    fun openTab(tab: AppScreenTab)
    fun back()
}

/** The Nuvio Auto phone remote as a View, for a ComponentActivity in androidApp. */
object NuvioRemoteView {
    /** Text from phone voice search, shown in the search box while it's sent to the car. */
    internal val heardText = mutableStateOf("")

    fun showHeard(text: String) {
        heardText.value = text
    }

    fun create(context: Context, actions: NuvioRemoteActions): View = ComposeView(context).apply {
        setContent {
            MaterialTheme(colorScheme = RemoteColors) {
                RemoteScreen(actions)
            }
        }
    }
}

private val Accent = Color(0xFF8B5CF6)
private val AccentBlue = Color(0xFF3B82F6)
private val AccentBrush = Brush.linearGradient(listOf(Accent, AccentBlue))
private val Bg = Color(0xFF09090F)
private val Card = Color(0xFF16161F)
private val CardHigh = Color(0xFF20202B)
private val Muted = Color(0xB3FFFFFF)

private val RemoteColors = darkColorScheme(
    primary = Accent,
    secondary = AccentBlue,
    background = Bg,
    surface = Card,
    onPrimary = Color.White,
    onSurface = Color.White,
    onBackground = Color.White,
)

@Composable
private fun RemoteScreen(actions: NuvioRemoteActions) {
    val playback by NuvioPlaybackRemote.state.collectAsState()
    var connected by remember { mutableStateOf(actions.isConnected()) }
    LaunchedEffect(Unit) {
        while (true) {
            connected = actions.isConnected()
            delay(1_000)
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        // Soft, blurred artwork behind everything while something plays.
        if (playback.active && !playback.artworkUrl.isNullOrBlank()) {
            AsyncImage(
                model = playback.artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(420.dp).blur(48.dp).alpha(0.45f),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.45f to Bg.copy(alpha = 0.92f), 1f to Bg),
            ),
        )
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Header(connected)
            Spacer(Modifier.height(20.dp))
            SearchBar(actions)
            Spacer(Modifier.height(16.dp))
            QuickNav(actions)
            Spacer(Modifier.height(24.dp))
            NowPlaying(playback)
            Spacer(Modifier.height(24.dp))
            Text(
                "For passengers. The car screen hides the video while driving if that's on in Nuvio Auto Setup.",
                color = Muted.copy(alpha = 0.5f),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun Header(connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(AccentBrush), Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, null, tint = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Nuvio remote", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Controls Nuvio on your car screen", fontSize = 13.sp, color = Muted)
        }
        val dot by animateColorAsState(if (connected) Color(0xFF22C55E) else Color(0xFF6B7280), label = "dot")
        Row(
            Modifier.clip(CircleShape).background(CardHigh).padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
            Text(if (connected) "Car" else "Offline", fontSize = 13.sp, color = Color.White)
        }
    }
}

@Composable
private fun SearchBar(actions: NuvioRemoteActions) {
    var text by remember { mutableStateOf("") }
    val heard by NuvioRemoteView.heardText
    LaunchedEffect(heard) { if (heard.isNotBlank()) text = heard }
    val focus = LocalFocusManager.current
    fun submit() {
        val query = text.trim()
        if (query.isEmpty()) return
        actions.search(query)
        focus.clearFocus()
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).padding(start = 4.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Search movies and shows", color = Muted) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Muted) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Accent,
            ),
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(AccentBrush).clickable { actions.voiceSearch() },
            Alignment.Center,
        ) {
            Icon(Icons.Rounded.Mic, "Voice search", tint = Color.White)
        }
    }
}

@Composable
private fun QuickNav(actions: NuvioRemoteActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NavChip(Icons.AutoMirrored.Rounded.ArrowBack, "Back", Modifier.weight(1f)) { actions.back() }
        NavChip(Icons.Rounded.Home, "Home", Modifier.weight(1f)) { actions.openTab(AppScreenTab.Home) }
        NavChip(Icons.Rounded.Search, "Search", Modifier.weight(1f)) { actions.openTab(AppScreenTab.Search) }
        NavChip(Icons.Rounded.VideoLibrary, "Library", Modifier.weight(1f)) { actions.openTab(AppScreenTab.Library) }
    }
}

@Composable
private fun NavChip(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Card).clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = Color.White)
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = Muted)
    }
}

@Composable
private fun NowPlaying(playback: NuvioPlaybackRemote.State) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Card).padding(18.dp)) {
        Text("NOW PLAYING", fontSize = 11.sp, letterSpacing = 1.5.sp, color = Muted, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        AnimatedContent(playback.active, label = "playing") { active ->
            if (!active) {
                Column {
                    Text("Nothing playing", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text(
                        "Pick something on the car screen, or search from here.",
                        fontSize = 14.sp,
                        color = Muted,
                    )
                }
            } else {
                PlayingContent(playback)
            }
        }
    }
}

@Composable
private fun PlayingContent(playback: NuvioPlaybackRemote.State) {
    Column {
        if (!playback.artworkUrl.isNullOrBlank()) {
            AsyncImage(
                model = playback.artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)),
            )
            Spacer(Modifier.height(14.dp))
        }
        Text(
            playback.title.orEmpty(),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        playback.subtitle?.let { Text(it, fontSize = 14.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Spacer(Modifier.height(8.dp))

        // Seek bar: follows playback, or the finger while dragging; seeks on release.
        val duration = playback.durationMs.coerceAtLeast(0L)
        var dragging by remember { mutableStateOf(false) }
        var dragFraction by remember { mutableFloatStateOf(0f) }
        val fraction = if (duration > 0) (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
        Slider(
            value = if (dragging) dragFraction else fraction,
            onValueChange = {
                dragging = true
                dragFraction = it
            },
            onValueChangeFinished = {
                if (duration > 0) NuvioPlaybackRemote.seekTo((dragFraction * duration).toLong())
                dragging = false
            },
            enabled = duration > 0,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Accent,
                inactiveTrackColor = CardHigh,
            ),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(if (dragging) (dragFraction * duration).toLong() else playback.positionMs), fontSize = 12.sp, color = Muted)
            Spacer(Modifier.weight(1f))
            Text(if (duration > 0) "-" + formatTime(duration - playback.positionMs) else "", fontSize = 12.sp, color = Muted)
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallText("−2m") { NuvioPlaybackRemote.seekBy(-120_000L) }
            RoundIcon(Icons.Rounded.Replay10, 56) { NuvioPlaybackRemote.seekBy(-10_000L) }
            Box(
                Modifier.size(76.dp).clip(CircleShape).background(AccentBrush)
                    .clickable { NuvioPlaybackRemote.togglePlayPause() },
                Alignment.Center,
            ) {
                Icon(
                    if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (playback.playing) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
            RoundIcon(Icons.Rounded.Forward30, 56) { NuvioPlaybackRemote.seekBy(30_000L) }
            SmallText("+2m") { NuvioPlaybackRemote.seekBy(120_000L) }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardHigh)
                .clickable { NuvioPlaybackRemote.nextEpisode() }.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.SkipNext, null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text("Next episode", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, sizeDp: Int, onClick: () -> Unit) {
    Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(CardHigh).clickable(onClick = onClick), Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun SmallText(label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), Alignment.Center) {
        Text(label, fontSize = 13.sp, color = Muted, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
