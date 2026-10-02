package com.nuvio.app.features.tvremote

import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.google.zxing.client.android.Intents
import com.nuvio.app.features.settings.SettingsNavigationRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
internal actual fun TvConnectionSettingsRow(isTablet: Boolean) {
    if (!AndroidTvRemote.initialized) return
    val state by AndroidTvRemote.repository.state.collectAsState()
    SettingsNavigationRow(
        title = "TV connection", description = when {
            state.pairingDeviceName != null -> "Pairing with ${state.pairingDeviceName}…"
            state.connected -> "Connected to ${state.deviceName}"
            state.disconnected && state.deviceName != null -> "Disconnected from ${state.deviceName}"
            state.deviceName != null -> "Connecting to ${state.deviceName}…"
            else -> "Connect to Nuvio on your Android TV"
        },
        icon = Icons.Rounded.Tv, isTablet = isTablet,
        onClick = { AndroidTvRemote.sheet.value = true },
    )
}

@Composable
internal actual fun TvRemoteCard(modifier: Modifier) {
    if (!AndroidTvRemote.initialized) return
    val state by AndroidTvRemote.repository.state.collectAsState()
    val snapshot = state.snapshot
    if (snapshot == null) { Spacer(modifier.height(0.dp)); return }
    Surface(modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth()
        .clickable { AndroidTvRemote.sheet.value = true }, shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Artwork(snapshot, Modifier.size(42.dp))
            Column(Modifier.weight(1f)) {
                Text(if (state.connected) "Playing on ${snapshot.deviceName}" else "Reconnecting to ${snapshot.deviceName}…", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(snapshot.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(enabled = state.canControl, onClick = {
                AndroidTvRemote.userInteraction()
                AndroidTvRemote.repository.send(if (snapshot.state in setOf("playing", "buffering")) "pause" else "play")
            }) { Text(if (snapshot.state in setOf("playing", "buffering")) "Pause" else "Play") }
        }
    }
}

@Composable
internal actual fun TvRemoteOverlay() {
    if (!AndroidTvRemote.initialized) return
    TvRemoteConnectionOverlay(AndroidTvRemote.repository, AndroidTvRemote.sheet)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TvRemoteConnectionOverlay(repository: TvRemoteRepository, sheet: MutableStateFlow<Boolean>) {
    val open by sheet.collectAsState()
    val state by repository.state.collectAsState()
    var scanError by rememberSaveable { mutableStateOf<String?>(null) }
    // Keep this registered even when the sheet is closed. Android may recreate the
    // process while the camera is open, resetting the process-local sheet flag.
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        sheet.value = true
        val contents = result.contents
        if (contents != null) {
            scanError = null
            repository.pair(contents)
        } else {
            scanError = if (result.originalIntent?.getBooleanExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, false) == true)
                "Camera permission is required to scan the TV code."
            else "Scan cancelled. Scan the TV code again to connect."
        }
    }
    if (!open) return
    ModalBottomSheet(onDismissRequest = { sheet.value = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("TV connection", style = MaterialTheme.typography.headlineSmall)
            val snapshot = state.snapshot
            if (snapshot != null) {
                Text("Playing on ${snapshot.deviceName}", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(snapshot, Modifier.size(92.dp))
                    Column(Modifier.weight(1f)) {
                        Text(snapshot.title, style = MaterialTheme.typography.titleLarge)
                        if (snapshot.subtitle().isNotBlank()) Text(snapshot.subtitle(), style = MaterialTheme.typography.bodyMedium)
                        if (snapshot.state == "buffering" && state.connected) Text("Buffering…")
                    }
                }
                RemoteTransportControls(state)
            } else Text(when {
                state.pairingDeviceName != null -> "Pairing with ${state.pairingDeviceName}…"
                state.deviceName == null -> "On your TV, open Nuvio Settings → Connect phone. Keep both devices on your home network."
                state.connected -> "Connected to ${state.deviceName}. Start playing on the TV to see controls here."
                state.disconnected -> "Disconnected from ${state.deviceName}"
                else -> "Connecting to ${state.deviceName}…"
            })
            if (state.pairingDeviceName != null || state.connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            scanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.deviceName != null && state.pairingDeviceName == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    if (state.disconnected) repository.reconnect(userRequested = true) else repository.disconnect()
                }) { Text(if (state.disconnected) "Reconnect" else "Disconnect") }
                TextButton(onClick = repository::forget) { Text("Forget TV") }
            }
            OutlinedButton(enabled = state.pairingDeviceName == null, onClick = {
                scanError = null
                runCatching { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Scan the code in Nuvio TV Settings → Connect phone")
                    .setBeepEnabled(false).setOrientationLocked(false)) }
                    .onFailure { scanError = "Unable to open the camera. Check camera permission and try again." }
            }) { Text(if (state.deviceName == null) "Scan TV code" else "Pair another TV") }
            Text("A quiet connection notification keeps TV playback detection active in the background, even while your TV is idle. If detection stops, reopen Nuvio. Disconnect to stop background detection.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Artwork(snapshot: RemoteSnapshot, modifier: Modifier) {
    Surface(modifier.clip(MaterialTheme.shapes.small), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Tv, contentDescription = null)
            AsyncImage(model = snapshot.artwork?.takeIf { it.startsWith("https://") }, contentDescription = null,
                modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
private fun RemoteTransportControls(state: TvRemoteState) {
    val snapshot = state.snapshot ?: return
    val repository = AndroidTvRemote.repository
    var clock by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.connected, snapshot.state) {
        while (true) { clock = SystemClock.elapsedRealtime(); delay(500) }
    }
    var dragging by remember(snapshot.sessionId) { mutableStateOf<Float?>(null) }
    val position = dragging?.toLong() ?: state.position(clock)
    val canSeek = state.canControl && snapshot.canSeek
    Slider(value = position.toFloat().coerceIn(0f, snapshot.durationMs.coerceAtLeast(1).toFloat()),
        onValueChange = { dragging = it }, valueRange = 0f..snapshot.durationMs.coerceAtLeast(1).toFloat(),
        enabled = canSeek, onValueChangeFinished = {
            dragging?.let { AndroidTvRemote.userInteraction(); repository.send("seek", it.toLong(), snapshot.sessionId) }
            dragging = null
        })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(position), style = MaterialTheme.typography.labelSmall)
        Text(if (snapshot.canSeek) formatTime(snapshot.durationMs) else "Seeking unavailable", style = MaterialTheme.typography.labelSmall)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        OutlinedButton(enabled = canSeek, onClick = { AndroidTvRemote.userInteraction(); repository.seekBy(-10_000) }) { Text("−10 s") }
        Button(enabled = state.canControl, onClick = {
            AndroidTvRemote.userInteraction()
            repository.send(if (snapshot.state in setOf("playing", "buffering")) "pause" else "play", expectedSession = snapshot.sessionId)
        }) { Text(if (snapshot.state in setOf("playing", "buffering")) "Pause" else "Play") }
        OutlinedButton(enabled = canSeek, onClick = { AndroidTvRemote.userInteraction(); repository.seekBy(10_000) }) { Text("+10 s") }
    }
}

private fun formatTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
