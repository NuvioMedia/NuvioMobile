package com.nuvio.app.features.player

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import com.nuvio.app.core.auth.findCause
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_error_source_invalid_content
import nuvio.composeapp.generated.resources.player_error_stream_blocked
import nuvio.composeapp.generated.resources.player_error_stream_expired
import nuvio.composeapp.generated.resources.player_error_stream_rate_limited
import nuvio.composeapp.generated.resources.player_error_stream_removed
import nuvio.composeapp.generated.resources.player_error_stream_unavailable
import nuvio.composeapp.generated.resources.player_error_unsupported_format
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

// Ported from NuvioTV (PlayerRuntimeControllerErrorRecovery.kt and
// PlayerRuntimeControllerMpvEvents.kt) so both apps word playback errors the same way.

/** A plain-language reason for an HTTP status, shown with the technical error. */
internal fun httpStatusExplanation(code: Int): StringResource? = when (code) {
    401, 410 -> Res.string.player_error_stream_expired
    404 -> Res.string.player_error_stream_removed
    429 -> Res.string.player_error_stream_rate_limited
    in 500..599 -> Res.string.player_error_stream_unavailable
    in 400..499 -> Res.string.player_error_stream_blocked
    else -> null
}

internal suspend fun httpStatusExplanationText(code: Int): String =
    httpStatusExplanation(code)?.let { getString(it) }.orEmpty()

/** The reason shown above an mpv error that carries no HTTP status. */
internal suspend fun mpvNonHttpExplanationText(fileError: String?, logLine: String?): String {
    val raw = when (mpvNonHttpExplanationKind(fileError, logLine)) {
        MpvNonHttpExplanationKind.InvalidContent ->
            getString(Res.string.player_error_source_invalid_content, "")
        MpvNonHttpExplanationKind.UnsupportedFormat ->
            getString(Res.string.player_error_unsupported_format, "")
        MpvNonHttpExplanationKind.OpenFailed ->
            getString(Res.string.player_error_stream_unavailable)
    }
    return raw.trim().removeSuffix("[]").trim()
}

internal fun PlaybackException.findInvalidResponseCodeException(): HttpDataSource.InvalidResponseCodeException? =
    findCause<HttpDataSource.InvalidResponseCodeException>()

/** The error screen text for an ExoPlayer failure. */
@OptIn(UnstableApi::class)
internal suspend fun PlaybackException.toDisplayMessage(): String {
    val responseException = findInvalidResponseCodeException()
    if (responseException != null) {
        val code = responseException.responseCode
        val statusText = responseException.responseMessage?.takeIf { it.isNotBlank() }
        val providerHint = httpStatusExplanationText(code)
        return buildString {
            append("HTTP $code")
            statusText?.let { append(" $it") }
            append(" [$errorCodeName]")
            if (providerHint.isNotEmpty()) append("\n\n").append(providerHint)
        }
    }

    // The provider returned something that is not video, such as an error page.
    if (findCause<UnrecognizedInputFormatException>() != null) {
        return getString(Res.string.player_error_source_invalid_content, errorCodeName)
    }

    val decoderInit = findCause<MediaCodecRenderer.DecoderInitializationException>()
    if (decoderInit != null) {
        val decoderMessage = decoderInit.message?.trim()?.takeIf { it.isNotBlank() }
            ?: decoderInit.diagnosticInfo?.trim()?.takeIf { it.isNotBlank() }
        return if (decoderMessage != null) "$decoderMessage [$errorCodeName]" else errorCodeName
    }

    val meaningfulMessage = findMostRelevantCauseMessage() ?: cause?.message ?: message
    return if (meaningfulMessage != null) "$meaningfulMessage [$errorCodeName]" else errorCodeName
}

private fun Throwable.findMostRelevantCauseMessage(): String? {
    val candidates = buildList {
        var current: Throwable? = this@findMostRelevantCauseMessage
        while (current != null) {
            current.message
                ?.trim()
                ?.takeIf {
                    it.isNotBlank() &&
                        !it.equals("Playback error", ignoreCase = true) &&
                        !it.equals("Source error", ignoreCase = true) &&
                        !it.equals("Unexpected runtime error", ignoreCase = true)
                }
                ?.let(::add)
            current = current.cause
        }
    }
    return candidates.firstOrNull()
}
