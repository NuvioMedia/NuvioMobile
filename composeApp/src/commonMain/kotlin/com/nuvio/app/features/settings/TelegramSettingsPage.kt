package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.telegram.TelegramIntegrationBridge
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_telegram_title
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.telegramSettingsContent(isTablet: Boolean) {
    item {
        TelegramSettingsContent(isTablet = isTablet)
    }
}

@Composable
private fun TelegramSettingsContent(isTablet: Boolean) {
    val state by TelegramIntegrationBridge.snapshot.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val horizontalPadding = if (isTablet) 2.dp else 0.dp

    var apiId by rememberSaveable { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var loginCode by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var botToken by remember { mutableStateOf("") }
    var channelInput by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(Res.string.settings_telegram_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = "Connect a Telegram account, select the channels or groups to search, and play matching video files directly from Telegram.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!TelegramIntegrationBridge.isAvailable()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Telegram is available in the Android Full build.", style = MaterialTheme.typography.titleMedium)
                    Text("This installation does not contain the TDLib native runtime.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            return@Column
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Account", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when (state.authState) {
                            "Ready" -> "Connected${state.accountName?.let { " as $it" }.orEmpty()}"
                            "Connecting" -> "Connecting to Telegram…"
                            "WaitPhone" -> "Phone number required"
                            "WaitCode" -> "Login code required"
                            "WaitPassword" -> "Two-step verification required"
                            "WaitQr" -> "Approve the QR login in Telegram"
                            "Error" -> "Connection error"
                            else -> if (state.configured) "Not connected" else "Not configured"
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.isBusy) androidx.compose.material3.CircularProgressIndicator()
                }

                if (!state.authenticated) {
                    OutlinedTextField(
                        value = apiId,
                        onValueChange = { apiId = it.filter(Char::isDigit) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Telegram API ID") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = apiHash,
                        onValueChange = { apiHash = it.trim() },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Telegram API hash") },
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            scope.launch { TelegramIntegrationBridge.configureAndConnect(apiId, apiHash) }
                        },
                        enabled = !state.isBusy && apiId.isNotBlank() && apiHash.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.configured) "Save API credentials and reconnect" else "Connect with API credentials")
                    }

                    when (state.authState) {
                        "WaitPhone" -> {
                            OutlinedTextField(
                                value = phone,
                                onValueChange = { phone = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Phone number with country code") },
                                singleLine = true,
                            )
                            Button(
                                onClick = { scope.launch { TelegramIntegrationBridge.signInWithPhone(phone) } },
                                enabled = phone.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Send login code") }
                            OutlinedButton(
                                onClick = { scope.launch { TelegramIntegrationBridge.requestQrLogin() } },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Use QR login instead") }
                            HorizontalDivider()
                            OutlinedTextField(
                                value = botToken,
                                onValueChange = { botToken = it.trim() },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Bot token (optional)") },
                                singleLine = true,
                            )
                            OutlinedButton(
                                onClick = { scope.launch { TelegramIntegrationBridge.signInWithBotToken(botToken) } },
                                enabled = botToken.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Sign in as bot") }
                        }
                        "WaitCode" -> {
                            OutlinedTextField(
                                value = loginCode,
                                onValueChange = { loginCode = it.filter(Char::isDigit) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Telegram login code") },
                                singleLine = true,
                            )
                            Button(
                                onClick = { scope.launch { TelegramIntegrationBridge.signInWithCode(loginCode) } },
                                enabled = loginCode.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Verify code") }
                        }
                        "WaitPassword" -> {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Two-step verification password") },
                                singleLine = true,
                            )
                            Button(
                                onClick = { scope.launch { TelegramIntegrationBridge.signInWithPassword(password) } },
                                enabled = password.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Verify password") }
                        }
                        "WaitQr" -> {
                            Text("Open Telegram on another device and approve this login link:")
                            Text(state.qrLink.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    Text(
                        "Telegram account authenticated. Only sources enabled below are searched for media.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { scope.launch { TelegramIntegrationBridge.disconnect() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Disconnect Telegram") }
                }

                state.error?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (state.authenticated) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Channel and group sources", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Add a public @username or a chat ID. You must already have access to the channel in Telegram. Select the sources Nuvio is allowed to search.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = channelInput,
                        onValueChange = { channelInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Channel username or chat ID") },
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                if (TelegramIntegrationBridge.addChatSource(channelInput)) channelInput = ""
                            }
                        },
                        enabled = channelInput.isNotBlank() && !state.isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Add source") }
                    OutlinedButton(
                        onClick = { scope.launch { TelegramIntegrationBridge.refreshChats() } },
                        enabled = !state.isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Refresh available chats") }

                    if (state.chats.isEmpty()) {
                        Text(
                            "No groups or channels loaded yet. Refresh after joining the desired channels in Telegram, or add a public channel by username.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.chats.forEach { chat ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = chat.id in state.selectedChatIds,
                                    onCheckedChange = { selected ->
                                        scope.launch { TelegramIntegrationBridge.toggleChatSource(chat.id, selected) }
                                    },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(chat.title, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${if (chat.isChannel) "Channel" else "Group"} · ${chat.id}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        "${state.selectedChatIds.size} source(s) selected",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Telegram cache", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Cached media: ${formatTelegramCacheSize(state.cacheSizeBytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = { scope.launch { TelegramIntegrationBridge.clearCache() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Clear Telegram file cache") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun formatTelegramCacheSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "${((bytes * 100L) / (1024L * 1024L * 1024L)) / 100.0} GB"
    bytes >= 1024L * 1024L -> "${((bytes * 10L) / (1024L * 1024L)) / 10.0} MB"
    else -> "${bytes / 1024L} KB"
}
