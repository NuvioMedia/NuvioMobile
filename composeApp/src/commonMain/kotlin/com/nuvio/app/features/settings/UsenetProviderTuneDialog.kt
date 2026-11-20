package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.usenet.TuneFailure
import com.nuvio.app.features.usenet.TuneOutcome
import com.nuvio.app.features.usenet.TuneProgress
import com.nuvio.app.features.usenet.TuneReading
import com.nuvio.app.features.usenet.UsenetConfiguration
import com.nuvio.app.features.usenet.UsenetPlatform
import com.nuvio.app.features.usenet.UsenetProvider
import com.nuvio.app.features.usenet.UsenetProviderTestResult
import com.nuvio.app.features.usenet.UsenetRepository
import kotlin.math.roundToLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.usenet_cancel
import nuvio.composeapp.generated.resources.usenet_provider_tune_again
import nuvio.composeapp.generated.resources.usenet_provider_tune_apply
import nuvio.composeapp.generated.resources.usenet_provider_tune_close
import nuvio.composeapp.generated.resources.usenet_provider_tune_connections_running
import nuvio.composeapp.generated.resources.usenet_provider_tune_data
import nuvio.composeapp.generated.resources.usenet_provider_tune_data_warning
import nuvio.composeapp.generated.resources.usenet_provider_tune_fastest
import nuvio.composeapp.generated.resources.usenet_provider_tune_intro
import nuvio.composeapp.generated.resources.usenet_provider_tune_limit
import nuvio.composeapp.generated.resources.usenet_provider_tune_limit_running
import nuvio.composeapp.generated.resources.usenet_provider_tune_missing
import nuvio.composeapp.generated.resources.usenet_provider_tune_no_limit
import nuvio.composeapp.generated.resources.usenet_provider_tune_over_limit
import nuvio.composeapp.generated.resources.usenet_provider_tune_port_running
import nuvio.composeapp.generated.resources.usenet_provider_tune_preparing
import nuvio.composeapp.generated.resources.usenet_provider_tune_reading
import nuvio.composeapp.generated.resources.usenet_provider_tune_recommended
import nuvio.composeapp.generated.resources.usenet_provider_tune_start
import nuvio.composeapp.generated.resources.usenet_provider_tune_stop
import nuvio.composeapp.generated.resources.usenet_provider_tune_stopped
import nuvio.composeapp.generated.resources.usenet_provider_tune_test_data
import nuvio.composeapp.generated.resources.usenet_provider_tune_title
import nuvio.composeapp.generated.resources.usenet_provider_tune_too_slow
import nuvio.composeapp.generated.resources.usenet_provider_tune_unchanged
import org.jetbrains.compose.resources.stringResource

/**
 * NuvioTV's Tune connections for one saved provider: before, during and after a run. A run uses up
 * to about 4 GB of test data on a fast line, which the dialog says before it starts. Nothing is
 * saved until Apply, which stores the measured port and connection allowance and hands them to
 * [onApplied].
 */
@Composable
internal fun ProviderTuneDialog(
    provider: UsenetProvider,
    engine: UsenetConfiguration,
    onApplied: (UsenetProvider) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var running by remember { mutableStateOf(false) }
    var stopped by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<TuneProgress?>(null) }
    var outcome by remember { mutableStateOf<TuneOutcome?>(null) }
    val success = outcome as? TuneOutcome.Success
    val canApply = success != null && changes(success, provider)
    val notStarted = !running && !stopped && outcome == null

    fun start() {
        job?.cancel()
        running = true
        stopped = false
        progress = null
        outcome = null
        // The engine's pipeline for the chosen profile, so the measurement matches playback.
        val pipeline = if (engine.profile == UsenetConfiguration.PROFILE_LOW_MEMORY) 2 else 4
        job = scope.launch {
            val result = UsenetPlatform.tuneProvider(provider, engine.allowPrivateNetwork, pipeline) { update ->
                scope.launch { if (running) progress = update }
            }
            running = false
            outcome = result
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        stopped = stopped || running
        running = false
    }

    fun apply() {
        val result = success?.result ?: return
        val tuned = provider.copy(port = result.port, tls = result.tls, connections = result.connections)
        val saved = UsenetRepository.updateSources { config ->
            config.copy(providers = config.providers.map { if (it.id == provider.id) tuned else it })
        }.isSuccess
        if (saved) onApplied(tuned)
        onDismiss()
    }

    DialogSurface(
        onDismissRequest = { if (running) stop() else onDismiss() },
        title = stringResource(Res.string.usenet_provider_tune_title, provider.name.ifBlank { provider.host }),
    ) {
        val tokens = MaterialTheme.nuvio
        val readings = success?.result?.readings ?: progress?.readings.orEmpty()
        Column(
            modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (notStarted) {
                Body(stringResource(Res.string.usenet_provider_tune_intro))
                Text(
                    stringResource(Res.string.usenet_provider_tune_data_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.danger,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (running) Body(progressText(progress))
            success?.let { Summary(it, provider) }
            (outcome as? TuneOutcome.Failed)?.let { Body(failureText(it)) }
            if (stopped && !running && outcome == null) Body(stringResource(Res.string.usenet_provider_tune_stopped))
            readings.forEach { reading ->
                Text(readingText(reading), style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
            }
        }
        DialogButtons {
            when {
                running -> DialogButton(
                    text = stringResource(Res.string.usenet_provider_tune_stop),
                    onClick = ::stop,
                    style = DialogButtonStyle.Primary,
                )
                canApply -> {
                    DialogButton(text = stringResource(Res.string.usenet_cancel), onClick = onDismiss)
                    DialogButton(text = stringResource(Res.string.usenet_provider_tune_apply), onClick = ::apply, style = DialogButtonStyle.Primary)
                }
                outcome != null || stopped -> {
                    DialogButton(text = stringResource(Res.string.usenet_provider_tune_again), onClick = ::start)
                    DialogButton(text = stringResource(Res.string.usenet_provider_tune_close), onClick = onDismiss, style = DialogButtonStyle.Primary)
                }
                else -> {
                    DialogButton(text = stringResource(Res.string.usenet_cancel), onClick = onDismiss)
                    DialogButton(text = stringResource(Res.string.usenet_provider_tune_start), onClick = ::start, style = DialogButtonStyle.Primary)
                }
            }
        }
    }
}

private fun changes(outcome: TuneOutcome.Success, provider: UsenetProvider) = outcome.result.let {
    it.port != provider.port || it.tls != provider.tls || it.connections != provider.connections
}

@Composable
private fun Summary(outcome: TuneOutcome.Success, provider: UsenetProvider) {
    val result = outcome.result
    Body(
        if (result.limit != null) stringResource(Res.string.usenet_provider_tune_limit, result.limit)
        else stringResource(Res.string.usenet_provider_tune_no_limit, result.tried),
    )
    if (result.limit != null && provider.connections > result.limit) {
        Body(stringResource(Res.string.usenet_provider_tune_over_limit, provider.connections))
    }
    val fastest = result.readings
        .filter { it.port == result.port && it.tls == result.tls && it.connections == result.fastestConnections }
        .map { it.mbps }.average()
    Body(stringResource(Res.string.usenet_provider_tune_fastest, result.fastestConnections, mbps(fastest)))
    Body(
        if (changes(outcome, provider)) {
            stringResource(Res.string.usenet_provider_tune_recommended, result.port, result.connections, provider.port, provider.connections)
        } else {
            stringResource(Res.string.usenet_provider_tune_unchanged)
        },
    )
    Text(
        stringResource(Res.string.usenet_provider_tune_data, dataSize(result.downloadedBytes)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.nuvio.colors.textMuted,
    )
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textPrimary)
}

@Composable
private fun progressText(progress: TuneProgress?): String = when (progress?.phase) {
    null -> stringResource(Res.string.usenet_provider_tune_preparing)
    TuneProgress.Phase.LIMIT -> stringResource(Res.string.usenet_provider_tune_limit_running, progress.connections)
    TuneProgress.Phase.PORT -> stringResource(Res.string.usenet_provider_tune_port_running, progress.port, progress.connections)
    TuneProgress.Phase.CONNECTIONS -> stringResource(Res.string.usenet_provider_tune_connections_running, progress.connections)
}

@Composable
private fun readingText(reading: TuneReading) =
    stringResource(Res.string.usenet_provider_tune_reading, reading.port, reading.connections, mbps(reading.mbps))

@Composable
private fun failureText(failure: TuneOutcome.Failed): String = when (failure.reason) {
    TuneFailure.CONNECT -> providerTestMessage(failure.connect ?: UsenetProviderTestResult.UNREACHABLE, anonymous = false)
    TuneFailure.TEST_DATA -> stringResource(Res.string.usenet_provider_tune_test_data)
    TuneFailure.MISSING_ARTICLES -> stringResource(Res.string.usenet_provider_tune_missing)
    TuneFailure.TOO_SLOW -> stringResource(Res.string.usenet_provider_tune_too_slow)
}

private fun mbps(value: Double): String = if (value.isFinite()) value.roundToLong().toString() else "0"

/** A byte count as MB or GB, the way the dialog states the data a run used. */
internal fun dataSize(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    return if (mb >= 1000) "${decimal(mb / 1000, 2)} GB" else "${decimal(mb, 0)} MB"
}
