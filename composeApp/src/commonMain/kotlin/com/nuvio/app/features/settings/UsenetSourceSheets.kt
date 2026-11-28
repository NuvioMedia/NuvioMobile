package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.usenet.DEFAULT_API_PATH
import com.nuvio.app.features.usenet.USENET_PRIORITIES
import com.nuvio.app.features.usenet.UsenetIndexer
import com.nuvio.app.features.usenet.UsenetPlatform
import com.nuvio.app.features.usenet.UsenetProvider
import com.nuvio.app.features.usenet.UsenetProviderTestResult
import com.nuvio.app.features.usenet.UsenetRepository
import com.nuvio.app.features.usenet.UsenetSourceConfiguration
import com.nuvio.app.features.usenet.isValidUserAgent
import com.nuvio.app.features.usenet.replaceOrAdd
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.usenet_add_indexer
import nuvio.composeapp.generated.resources.usenet_add_provider
import nuvio.composeapp.generated.resources.usenet_cancel
import nuvio.composeapp.generated.resources.usenet_delete
import nuvio.composeapp.generated.resources.usenet_delete_confirm
import nuvio.composeapp.generated.resources.usenet_edit_indexer
import nuvio.composeapp.generated.resources.usenet_edit_provider
import nuvio.composeapp.generated.resources.usenet_fetch_default
import nuvio.composeapp.generated.resources.usenet_fetch_limit
import nuvio.composeapp.generated.resources.usenet_fetch_limit_description
import nuvio.composeapp.generated.resources.usenet_fetch_unlimited
import nuvio.composeapp.generated.resources.usenet_indexer_api_path
import nuvio.composeapp.generated.resources.usenet_indexer_hint
import nuvio.composeapp.generated.resources.usenet_indexer_invalid
import nuvio.composeapp.generated.resources.usenet_indexer_key
import nuvio.composeapp.generated.resources.usenet_indexer_priority_hint
import nuvio.composeapp.generated.resources.usenet_indexer_url
import nuvio.composeapp.generated.resources.usenet_move_down
import nuvio.composeapp.generated.resources.usenet_move_up
import nuvio.composeapp.generated.resources.usenet_priority
import nuvio.composeapp.generated.resources.usenet_provider_connections
import nuvio.composeapp.generated.resources.usenet_provider_host
import nuvio.composeapp.generated.resources.usenet_provider_invalid
import nuvio.composeapp.generated.resources.usenet_provider_password
import nuvio.composeapp.generated.resources.usenet_provider_port
import nuvio.composeapp.generated.resources.usenet_provider_priority_hint
import nuvio.composeapp.generated.resources.usenet_provider_test
import nuvio.composeapp.generated.resources.usenet_provider_test_auth
import nuvio.composeapp.generated.resources.usenet_provider_test_connected
import nuvio.composeapp.generated.resources.usenet_provider_test_private
import nuvio.composeapp.generated.resources.usenet_provider_test_refused
import nuvio.composeapp.generated.resources.usenet_provider_test_running
import nuvio.composeapp.generated.resources.usenet_provider_test_success
import nuvio.composeapp.generated.resources.usenet_provider_test_tls
import nuvio.composeapp.generated.resources.usenet_provider_test_unreachable
import nuvio.composeapp.generated.resources.usenet_provider_tls
import nuvio.composeapp.generated.resources.usenet_provider_username
import nuvio.composeapp.generated.resources.usenet_save
import nuvio.composeapp.generated.resources.usenet_provider_tune
import nuvio.composeapp.generated.resources.usenet_provider_tune_description
import nuvio.composeapp.generated.resources.usenet_provider_tune_data_warning
import nuvio.composeapp.generated.resources.usenet_download_user_agent_description
import nuvio.composeapp.generated.resources.usenet_search_user_agent_description
import nuvio.composeapp.generated.resources.usenet_user_agent
import nuvio.composeapp.generated.resources.usenet_user_agent_invalid
import nuvio.composeapp.generated.resources.usenet_user_agent_reset
import nuvio.composeapp.generated.resources.usenet_source_delete
import nuvio.composeapp.generated.resources.usenet_source_enabled
import nuvio.composeapp.generated.resources.usenet_source_name
import nuvio.composeapp.generated.resources.usenet_test_failed
import nuvio.composeapp.generated.resources.usenet_test_indexer
import nuvio.composeapp.generated.resources.usenet_test_running
import nuvio.composeapp.generated.resources.usenet_test_success
import org.jetbrains.compose.resources.stringResource

/** Which source an editor sheet shows: a new one, or the saved one with this id. */
internal sealed interface UsenetSourceEditor {
    data class Provider(val existing: UsenetProvider?) : UsenetSourceEditor
    data class Indexer(val existing: UsenetIndexer?) : UsenetSourceEditor
}

/** Moves the element with [id] one place up (-1) or down (+1) within its priority. */
internal fun <T> List<T>.moveWithinPriority(id: String, direction: Int, idOf: (T) -> String, priorityOf: (T) -> Int): List<T> {
    val from = indexOfFirst { idOf(it) == id }
    val to = from + direction
    if (from < 0 || to !in indices || priorityOf(this[to]) != priorityOf(this[from])) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun UsenetProviderSheet(
    existing: UsenetProvider?,
    allowPrivateNetwork: Boolean,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initial = existing ?: UsenetProvider()
    var name by remember { mutableStateOf(initial.name) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var tls by remember { mutableStateOf(initial.tls) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    var connections by remember { mutableStateOf(initial.connections.toString()) }
    var priority by remember { mutableStateOf(initial.priority) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var invalid by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<UsenetProviderTestResult, Boolean>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var tuning by remember { mutableStateOf(false) }

    fun entered(): UsenetProvider? {
        val portValue = port.trim().toIntOrNull() ?: return null
        val connectionValue = connections.trim().toIntOrNull() ?: return null
        return initial.copy(
            name = name.trim(), host = host.trim(), port = portValue, tls = tls, username = username.trim(),
            password = password, connections = connectionValue, priority = priority, enabled = enabled,
        )
    }

    fun close() = scope.launch { dismissNuvioBottomSheet(sheetState) { onDismiss() } }

    fun save() {
        val provider = entered()
        val saved = provider != null && UsenetRepository.updateSources { config ->
            config.copy(providers = config.providers.replaceOrAdd(provider) { it.id })
        }.isSuccess
        if (saved) close() else invalid = true
    }

    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = tokens.spacing.sheetPadding, end = tokens.spacing.sheetPadding, bottom = tokens.spacing.sheetPadding),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            Text(
                text = stringResource(if (existing == null) Res.string.usenet_add_provider else Res.string.usenet_edit_provider),
                style = MaterialTheme.typography.titleLarge,
                color = tokens.colors.textPrimary,
            )
            UsenetTextField(name, { name = it; invalid = false }, stringResource(Res.string.usenet_source_name),
                capitalization = KeyboardCapitalization.Words)
            UsenetTextField(host, { host = it; invalid = false; testResult = null }, stringResource(Res.string.usenet_provider_host),
                keyboardType = KeyboardType.Uri)
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
                UsenetTextField(port, { port = it.filter(Char::isDigit); invalid = false; testResult = null },
                    stringResource(Res.string.usenet_provider_port), keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                UsenetTextField(connections, { connections = it.filter(Char::isDigit); invalid = false },
                    stringResource(Res.string.usenet_provider_connections), keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
            }
            UsenetSwitchLine(stringResource(Res.string.usenet_provider_tls), tls) {
                tls = it
                // The usual ports follow TLS until the user picks another.
                if (it && port == "119") port = "563" else if (!it && port == "563") port = "119"
                testResult = null
            }
            UsenetTextField(username, { username = it; invalid = false; testResult = null },
                stringResource(Res.string.usenet_provider_username))
            SettingsSecretTextField(
                value = password,
                onValueChange = { password = it; invalid = false; testResult = null },
                label = stringResource(Res.string.usenet_provider_password),
                modifier = Modifier.fillMaxWidth(),
                shape = tokens.shapes.button,
                colors = sheetFieldColors(),
                imeAction = ImeAction.Done,
            )
            UsenetPriorityChips(priority, stringResource(Res.string.usenet_provider_priority_hint)) { priority = it }
            UsenetSwitchLine(stringResource(Res.string.usenet_source_enabled), enabled) { enabled = it }

            OutlinedButton(
                onClick = {
                    val provider = entered()
                    if (provider == null) {
                        invalid = true
                        return@OutlinedButton
                    }
                    testing = true
                    testResult = null
                    scope.launch {
                        try {
                            val result = UsenetPlatform.testProvider(provider, allowPrivateNetwork)
                            if (result == UsenetProviderTestResult.INVALID) invalid = true
                            else testResult = result to (provider.username.isEmpty() && provider.password.isEmpty())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            testResult = UsenetProviderTestResult.UNREACHABLE to false
                        } finally {
                            testing = false
                        }
                    }
                },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth(),
                shape = tokens.shapes.button,
            ) {
                if (testing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8), verticalAlignment = Alignment.CenterVertically) {
                        NuvioLoadingIndicator(modifier = Modifier.size(tokens.icons.sm))
                        Text(stringResource(Res.string.usenet_provider_test_running))
                    }
                } else {
                    Text(stringResource(Res.string.usenet_provider_test))
                }
            }
            testResult?.let { (result, anonymous) ->
                Text(
                    text = providerTestMessage(result, anonymous),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result == UsenetProviderTestResult.SUCCESS) tokens.colors.accent else tokens.colors.danger,
                )
            }
            if (invalid) {
                Text(stringResource(Res.string.usenet_provider_invalid), style = MaterialTheme.typography.bodySmall, color = tokens.colors.danger)
            }
            // Tuning measures the saved provider, so it is offered once the provider exists.
            if (existing != null) {
                OutlinedButton(
                    onClick = { tuning = true },
                    enabled = !testing,
                    modifier = Modifier.fillMaxWidth(),
                    shape = tokens.shapes.button,
                ) { Text(stringResource(Res.string.usenet_provider_tune)) }
                Text(
                    stringResource(Res.string.usenet_provider_tune_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
                Text(
                    stringResource(Res.string.usenet_provider_tune_data_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.danger,
                )
            }
            SheetActions(
                existing = existing != null,
                onSave = ::save,
                onDelete = { confirmDelete = true },
                onMove = { direction ->
                    UsenetRepository.updateSources { config ->
                        config.copy(providers = config.providers.moveWithinPriority(initial.id, direction, { it.id }, { it.priority }))
                    }
                },
            )
        }
    }

    if (confirmDelete) {
        UsenetDeleteDialog(
            onConfirm = {
                confirmDelete = false
                UsenetRepository.updateSources { config -> config.copy(providers = config.providers.filterNot { it.id == initial.id }) }
                close()
            },
            onDismiss = { confirmDelete = false },
        )
    }

    if (tuning) {
        val saved = UsenetRepository.sources().providers.find { it.id == initial.id }
        if (saved == null) {
            tuning = false
        } else {
            ProviderTuneDialog(
                provider = saved,
                engine = UsenetRepository.engineSettings(),
                // The sheet shows what was applied, so its Save keeps it.
                onApplied = { tuned ->
                    port = tuned.port.toString()
                    tls = tuned.tls
                    connections = tuned.connections.toString()
                },
                onDismiss = { tuning = false },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun UsenetIndexerSheet(
    existing: UsenetIndexer?,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initial = existing ?: UsenetIndexer()
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.apiUrl) }
    var apiPath by remember { mutableStateOf(initial.apiPath.ifEmpty { if (existing == null) DEFAULT_API_PATH else "" }) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var priority by remember { mutableStateOf(initial.priority) }
    var fetchLimit by remember { mutableStateOf(initial.fetchResultsLimit) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var invalid by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testPassed by remember { mutableStateOf<Boolean?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun entered() = initial.copy(
        name = name.trim(), apiUrl = url.trim(), apiPath = apiPath.trim(), apiKey = apiKey.trim(),
        priority = priority, fetchResultsLimit = fetchLimit, enabled = enabled,
    )

    fun close() = scope.launch { dismissNuvioBottomSheet(sheetState) { onDismiss() } }

    fun save() {
        val indexer = entered()
        val saved = UsenetRepository.updateSources { config ->
            config.copy(indexers = config.indexers.replaceOrAdd(indexer) { it.id })
        }.isSuccess
        if (saved) close() else invalid = true
    }

    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = tokens.spacing.sheetPadding, end = tokens.spacing.sheetPadding, bottom = tokens.spacing.sheetPadding),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            Text(
                text = stringResource(if (existing == null) Res.string.usenet_add_indexer else Res.string.usenet_edit_indexer),
                style = MaterialTheme.typography.titleLarge,
                color = tokens.colors.textPrimary,
            )
            Text(
                text = stringResource(Res.string.usenet_indexer_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )
            UsenetTextField(name, { name = it; invalid = false }, stringResource(Res.string.usenet_source_name),
                capitalization = KeyboardCapitalization.Words)
            UsenetTextField(url, { url = it; invalid = false; testPassed = null }, stringResource(Res.string.usenet_indexer_url),
                keyboardType = KeyboardType.Uri)
            UsenetTextField(apiPath, { apiPath = it; invalid = false; testPassed = null }, stringResource(Res.string.usenet_indexer_api_path),
                keyboardType = KeyboardType.Uri)
            SettingsSecretTextField(
                value = apiKey,
                onValueChange = { apiKey = it; invalid = false; testPassed = null },
                label = stringResource(Res.string.usenet_indexer_key),
                modifier = Modifier.fillMaxWidth(),
                shape = tokens.shapes.button,
                colors = sheetFieldColors(),
                imeAction = ImeAction.Done,
            )
            UsenetPriorityChips(priority, stringResource(Res.string.usenet_indexer_priority_hint)) { priority = it }
            Text(stringResource(Res.string.usenet_fetch_limit), style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary)
            Text(stringResource(Res.string.usenet_fetch_limit_description), style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
                val choices = listOf<Int?>(null) + UsenetSourceConfiguration.INDEXER_RESULT_CHOICES + 0
                choices.forEach { choice ->
                    UsenetChip(
                        label = when (choice) {
                            null -> stringResource(Res.string.usenet_fetch_default)
                            0 -> stringResource(Res.string.usenet_fetch_unlimited)
                            else -> choice.toString()
                        },
                        selected = fetchLimit == choice,
                    ) { fetchLimit = choice }
                }
            }
            UsenetSwitchLine(stringResource(Res.string.usenet_source_enabled), enabled) { enabled = it }

            OutlinedButton(
                onClick = {
                    testing = true
                    testPassed = null
                    scope.launch {
                        try {
                            testPassed = UsenetPlatform.testIndexer(entered())
                        } finally {
                            testing = false
                        }
                    }
                },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth(),
                shape = tokens.shapes.button,
            ) {
                if (testing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8), verticalAlignment = Alignment.CenterVertically) {
                        NuvioLoadingIndicator(modifier = Modifier.size(tokens.icons.sm))
                        Text(stringResource(Res.string.usenet_test_running))
                    }
                } else {
                    Text(stringResource(Res.string.usenet_test_indexer))
                }
            }
            testPassed?.let { passed ->
                Text(
                    text = stringResource(if (passed) Res.string.usenet_test_success else Res.string.usenet_test_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (passed) tokens.colors.accent else tokens.colors.danger,
                )
            }
            if (invalid) {
                Text(stringResource(Res.string.usenet_indexer_invalid), style = MaterialTheme.typography.bodySmall, color = tokens.colors.danger)
            }
            SheetActions(
                existing = existing != null,
                onSave = ::save,
                onDelete = { confirmDelete = true },
                onMove = { direction ->
                    UsenetRepository.updateSources { config ->
                        config.copy(indexers = config.indexers.moveWithinPriority(initial.id, direction, { it.id }, { it.priority }))
                    }
                },
            )
        }
    }

    if (confirmDelete) {
        UsenetDeleteDialog(
            onConfirm = {
                confirmDelete = false
                UsenetRepository.updateSources { config -> config.copy(indexers = config.indexers.filterNot { it.id == initial.id }) }
                close()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun SheetActions(
    existing: Boolean,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s4))
    Button(
        onClick = onSave,
        modifier = Modifier.fillMaxWidth().height(NuvioTokens.Space.s56),
        shape = tokens.shapes.button,
        colors = ButtonDefaults.buttonColors(containerColor = tokens.colors.accent, contentColor = tokens.colors.onAccent),
    ) {
        Text(stringResource(Res.string.usenet_save))
    }
    if (existing) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row {
                TextButton(onClick = { onMove(-1) }) { Text(stringResource(Res.string.usenet_move_up)) }
                TextButton(onClick = { onMove(1) }) { Text(stringResource(Res.string.usenet_move_down)) }
            }
            TextButton(onClick = onDelete) {
                Text(stringResource(Res.string.usenet_source_delete), color = tokens.colors.danger)
            }
        }
    }
}

@Composable
private fun UsenetDeleteDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    DialogSurface(onDismissRequest = onDismiss, title = stringResource(Res.string.usenet_source_delete)) {
        Text(
            text = stringResource(Res.string.usenet_delete_confirm),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textSecondary,
        )
        DialogButtons {
            DialogButton(text = stringResource(Res.string.usenet_cancel), onClick = onDismiss)
            DialogButton(text = stringResource(Res.string.usenet_delete), onClick = onConfirm, style = DialogButtonStyle.Primary)
        }
    }
}

/**
 * Edits a User-Agent, opening with the one being sent so Nuvio's own can be read. Blank, or
 * Nuvio's own unchanged, keeps the automatic one, which follows app updates.
 */
@Composable
internal fun UsenetUserAgentDialog(
    title: String,
    agent: String,
    search: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(title) { mutableStateOf(agent) }
    var invalid by remember(title) { mutableStateOf(false) }
    fun save(agent: String) {
        onSave(agent.trim())
        onDismiss()
    }
    DialogSurface(
        onDismissRequest = onDismiss,
        title = title,
        message = stringResource(
            if (search) Res.string.usenet_search_user_agent_description else Res.string.usenet_download_user_agent_description,
        ),
    ) {
        UsenetTextField(value = value, onValueChange = { value = it; invalid = false }, label = stringResource(Res.string.usenet_user_agent))
        if (invalid) {
            Text(
                text = stringResource(Res.string.usenet_user_agent_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        DialogButtons {
            DialogButton(text = stringResource(Res.string.usenet_user_agent_reset), onClick = { save("") })
            DialogButton(text = stringResource(Res.string.usenet_cancel), onClick = onDismiss)
            DialogButton(
                text = stringResource(Res.string.usenet_save),
                onClick = { if (isValidUserAgent(value.trim())) save(value) else invalid = true },
                style = DialogButtonStyle.Primary,
            )
        }
    }
}

@Composable
private fun UsenetTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            autoCorrectEnabled = false,
            keyboardType = keyboardType,
            imeAction = ImeAction.Next,
        ),
        shape = MaterialTheme.nuvio.shapes.button,
        colors = sheetFieldColors(),
    )
}

@Composable
private fun UsenetSwitchLine(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = tokens.colors.onAccent,
                checkedTrackColor = tokens.colors.accent,
                uncheckedThumbColor = tokens.colors.textMuted,
                uncheckedTrackColor = tokens.colors.borderDefault,
            ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsenetPriorityChips(selected: Int, hint: String, onSelect: (Int) -> Unit) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
        Text(stringResource(Res.string.usenet_priority), style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
            USENET_PRIORITIES.forEach { value ->
                UsenetChip(label = value.toString(), selected = selected == value) { onSelect(value) }
            }
        }
    }
}

@Composable
internal fun UsenetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = tokens.colors.accent,
            selectedLabelColor = tokens.colors.onAccent,
        ),
    )
}

@Composable
internal fun providerTestMessage(result: UsenetProviderTestResult, anonymous: Boolean): String = when (result) {
    UsenetProviderTestResult.SUCCESS -> stringResource(
        if (anonymous) Res.string.usenet_provider_test_connected else Res.string.usenet_provider_test_success,
    )
    UsenetProviderTestResult.UNREACHABLE -> stringResource(Res.string.usenet_provider_test_unreachable)
    UsenetProviderTestResult.TLS -> stringResource(Res.string.usenet_provider_test_tls)
    UsenetProviderTestResult.AUTH -> stringResource(Res.string.usenet_provider_test_auth)
    UsenetProviderTestResult.REFUSED -> stringResource(Res.string.usenet_provider_test_refused)
    UsenetProviderTestResult.PRIVATE_NETWORK -> stringResource(Res.string.usenet_provider_test_private)
    UsenetProviderTestResult.INVALID -> stringResource(Res.string.usenet_provider_invalid)
}
