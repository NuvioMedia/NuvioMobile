package com.nuvio.app.features.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.rememberCoroutineScope
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.cast_action
import nuvio.composeapp.generated.resources.cast_choose_device
import nuvio.composeapp.generated.resources.cast_chromecast_label
import nuvio.composeapp.generated.resources.cast_chromecast_playing
import nuvio.composeapp.generated.resources.cast_close
import nuvio.composeapp.generated.resources.cast_detects
import nuvio.composeapp.generated.resources.cast_devices_in_network
import nuvio.composeapp.generated.resources.cast_dlna_label
import nuvio.composeapp.generated.resources.cast_dlna_playing
import nuvio.composeapp.generated.resources.cast_error
import nuvio.composeapp.generated.resources.cast_footer_chromecast_first
import nuvio.composeapp.generated.resources.cast_no_devices
import nuvio.composeapp.generated.resources.cast_not_found_tv
import nuvio.composeapp.generated.resources.cast_check_chromecast
import nuvio.composeapp.generated.resources.cast_phone_tv_same_wifi
import nuvio.composeapp.generated.resources.cast_playing
import nuvio.composeapp.generated.resources.cast_playing_on_tv
import nuvio.composeapp.generated.resources.cast_refresh
import nuvio.composeapp.generated.resources.cast_samsung_badge
import nuvio.composeapp.generated.resources.cast_scan_again
import nuvio.composeapp.generated.resources.cast_scan_devices
import nuvio.composeapp.generated.resources.cast_scanning
import nuvio.composeapp.generated.resources.cast_searching
import nuvio.composeapp.generated.resources.cast_stop
import nuvio.composeapp.generated.resources.cast_try_again
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastBottomSheet(
    isVisible: Boolean,
    castRequest: DlnaCastRequest?,
    onDismiss: () -> Unit,
    onDeviceSelected: (DlnaDevice) -> Unit = {},
) {
    if (!isVisible) return
    val unifiedState by UnifiedCastRepository.state.collectAsState()
    val unifiedDevices by UnifiedCastRepository.devices.collectAsState()
    val isUnifiedCasting = unifiedState is UnifiedCastState.Casting
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        UnifiedCastRepository.startScan()
    }

    fun dismiss() {
        scope.launch { dismissNuvioBottomSheet(sheetState, onDismiss) }
    }

    NuvioModalBottomSheet(
        onDismissRequest = { scope.launch { dismissNuvioBottomSheet(sheetState, onDismiss) } },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header info
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = when {
                        unifiedState is UnifiedCastState.Scanning -> stringResource(Res.string.cast_searching)
                        unifiedState is UnifiedCastState.DevicesFound -> stringResource(Res.string.cast_choose_device)
                        unifiedState is UnifiedCastState.NoDevices -> stringResource(Res.string.cast_no_devices)
                        isUnifiedCasting -> stringResource(Res.string.cast_playing_on_tv)
                        unifiedState is UnifiedCastState.Error -> stringResource(Res.string.cast_error)
                        else -> stringResource(Res.string.cast_devices_in_network)
                    },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    UnifiedCastRepository.startScan()
                }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = stringResource(Res.string.cast_refresh))
                }
            }

            Text(
                text = stringResource(Res.string.cast_phone_tv_same_wifi),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                unifiedState is UnifiedCastState.Scanning -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(stringResource(Res.string.cast_scanning), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                unifiedState is UnifiedCastState.NoDevices -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.Tv, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(stringResource(Res.string.cast_not_found_tv), style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(stringResource(Res.string.cast_check_chromecast), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(onClick = { UnifiedCastRepository.startScan() }) { Text(stringResource(Res.string.cast_scan_again)) }
                        }
                    }
                }
                (unifiedState as? UnifiedCastState.Error)?.let { it.message } != null -> {
                    val msg = (unifiedState as UnifiedCastState.Error).message
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                        Text(msg, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(onClick = { UnifiedCastRepository.startScan() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.cast_try_again)) }
                }
                isUnifiedCasting -> {
                    val casting = unifiedState as UnifiedCastState.Casting
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (casting.device.protocol == CastProtocol.CHROMECAST) Icons.Rounded.SmartDisplay else Icons.Rounded.CastConnected,
                                    contentDescription = null, tint = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(casting.device.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (casting.device.protocol == CastProtocol.CHROMECAST) stringResource(Res.string.cast_chromecast_playing) else stringResource(Res.string.cast_dlna_playing),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { UnifiedCastRepository.stop() }, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.cast_stop)) }
                        Button(onClick = { dismiss() }, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.cast_close)) }
                    }
                }
                else -> {
                    if (unifiedDevices.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { UnifiedCastRepository.startScan() }) { Text(stringResource(Res.string.cast_scan_devices)) }
                                Text(stringResource(Res.string.cast_detects), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(unifiedDevices) { device ->
                                UnifiedDeviceRow(device = device, onClick = {
                                    if (castRequest != null) {
                                        UnifiedCastRepository.castToDevice(device, castRequest, chromecastPoster = null)
                                        device.dlnaDevice?.let { onDeviceSelected(it) }
                                    }
                                    dismiss()
                                })
                            }
                        }
                    }
                }
            }

            if (unifiedState is UnifiedCastState.DevicesFound) {
                Text(
                    text = stringResource(Res.string.cast_footer_chromecast_first),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DlnaDeviceRow(device: DlnaDevice, onClick: () -> Unit) {
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Tv, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.friendlyName, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!device.modelName.isNullOrBlank()) {
                        Text(device.modelName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    device.ipAddress?.let {
                        Text("• $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (device.locationUrl.contains("7676") || device.locationUrl.contains("smp_")) {
                    Text(stringResource(Res.string.cast_samsung_badge), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                }
            }
            Icon(Icons.Rounded.Cast, contentDescription = stringResource(Res.string.cast_action), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun UnifiedDeviceRow(device: UnifiedCastDevice, onClick: () -> Unit) {
    val isCast = device.protocol == CastProtocol.CHROMECAST
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(42.dp).clip(CircleShape).background(
                    if (isCast) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isCast) Icons.Rounded.SmartDisplay else Icons.Rounded.Tv,
                    contentDescription = null,
                    tint = if (isCast) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (isCast) stringResource(Res.string.cast_chromecast_label) else stringResource(Res.string.cast_dlna_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isCast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                    device.ipAddress?.let {
                        Text("• $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    device.chromecastDescription?.takeIf { it.isNotBlank() }?.let {
                        Text("• $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!isCast && (device.dlnaDevice?.locationUrl?.contains("7676") == true)) {
                    Text(stringResource(Res.string.cast_samsung_badge), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                }
            }
            Icon(
                imageVector = if (isCast) Icons.Rounded.SmartDisplay else Icons.Rounded.Cast,
                contentDescription = stringResource(Res.string.cast_action),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
