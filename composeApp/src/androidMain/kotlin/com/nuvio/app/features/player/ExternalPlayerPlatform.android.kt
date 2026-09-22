package com.nuvio.app.features.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.external_player_android_system
import org.jetbrains.compose.resources.getString
import java.io.File
import java.net.URI

private const val AndroidSystemPlayerId = "android_system"

internal actual object ExternalPlayerPlatform {
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    actual fun defaultPlayerId(): String? = AndroidSystemPlayerId

    actual fun availablePlayers(): List<ExternalPlayerApp> =
        listOf(
            ExternalPlayerApp(
                AndroidSystemPlayerId,
                runBlocking { getString(Res.string.external_player_android_system) },
            ),
        )

    actual fun open(
        request: ExternalPlayerPlaybackRequest,
        playerId: String?,
    ): ExternalPlayerOpenResult {
        val context = appContext ?: return ExternalPlayerOpenResult.Failed
        val uri = request.sourceUrl.toExternalPlaybackUri(context)
            ?: return ExternalPlayerOpenResult.Failed
        val intent = buildExternalPlayerIntent(context, request, uri).apply {
            // Required when launching from application context (fire-and-forget path)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            ExternalPlayerOpenResult.Opened
        } catch (_: ActivityNotFoundException) {
            ExternalPlayerOpenResult.NoPlayerAvailable
        } catch (_: Throwable) {
            ExternalPlayerOpenResult.Failed
        }
    }

    actual fun buildIntent(
        request: ExternalPlayerPlaybackRequest,
        playerId: String?,
    ): ExternalPlayerIntentResult {
        val context = appContext ?: return ExternalPlayerIntentResult.Failed
        val uri = request.sourceUrl.toExternalPlaybackUri(context)
            ?: return ExternalPlayerIntentResult.Failed
        val intent = buildExternalPlayerIntent(context, request, uri)
        return ExternalPlayerIntentResult.Success(intent)
    }

    private fun buildExternalPlayerIntent(
        context: Context,
        request: ExternalPlayerPlaybackRequest,
        uri: Uri,
    ): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, request.sourceUrl.videoMimeType())
        addCategory(Intent.CATEGORY_DEFAULT)
        if (uri.scheme.equals("content", ignoreCase = true)) {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // Title extras
        val displayTitle = request.buildPlayerTitle(includeEpisodeTitle = true)
        putExtra(Intent.EXTRA_TITLE, displayTitle)
        putExtra("title", displayTitle)
        putExtra("forcename", displayTitle) // Vimu Player

        // Resume position extras
        if (request.resumePositionMs > 0L) {
            putExtra("position", request.resumePositionMs.toInt())   // MX Player / Just Player / mpv
            putExtra("startfrom", request.resumePositionMs.toInt()) // Vimu Player
            putExtra("forceresume", true)                           // Vimu: enable resume for network streams
            putExtra("from_start", false)                           // VLC: don't force start from beginning
        }

        // Request that the player returns result with position/duration.
        // Required by MX Player; harmless for other players.
        putExtra("return_result", true)

        // Episode/movie skip segments; the receiving player controls their handling.
        // Players that don't understand this extra simply ignore it.
        request.skipSegmentsJson?.let { putExtra("skip_segments", it) }

        // Headers
        if (request.sourceHeaders.isNotEmpty()) {
            val headerArray = request.sourceHeaders.entries
                .map { "${it.key}: ${it.value}" }
                .toTypedArray()
            putExtra("headers", headerArray)
        }

        // Subtitle extras
        val subtitles = request.subtitles
        if (!subtitles.isNullOrEmpty()) {
            val subtitleUris = subtitles.map { Uri.parse(it.url) }.toTypedArray()
            val subtitleNames = subtitles.map { it.name }.toTypedArray()
            val subtitleFilenames = subtitles.map { "${it.lang}_${it.name}.srt" }.toTypedArray()

            // Grant read permission for content:// URIs via ClipData.
            // FLAG_GRANT_READ_URI_PERMISSION only covers intent.data, not extras.
            // Adding all subtitle URIs to ClipData ensures the receiving player
            // gets read access to all of them.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val clipData = android.content.ClipData(
                "subtitles",
                arrayOf("application/x-subrip", "text/vtt"),
                android.content.ClipData.Item(subtitleUris.first())
            )
            subtitleUris.drop(1).forEach { subtitleUri ->
                clipData.addItem(android.content.ClipData.Item(subtitleUri))
            }
            setClipData(clipData)

            // MX Player / mpv-android / Nova
            putExtra("subs", subtitleUris)
            putExtra("subs.name", subtitleNames)
            putExtra("subs.filename", subtitleFilenames)
            putExtra("subs.enable", arrayOf(subtitleUris.first()))

            // Just Player
            putExtra("subtitle_uri", subtitleUris)
            putExtra("subtitle_name", subtitleNames)

            // VLC (single subtitle — use first one)
            putExtra("subtitles_location", subtitleUris.first())

            // Vimu Player
            putExtra("forcedsrt", subtitles.first().url)
        }
    }

    private fun String.toExternalPlaybackUri(context: Context): Uri? {
        val trimmed = trim()
        if (trimmed.isBlank()) return null
        if (!trimmed.startsWith("file:", ignoreCase = true)) {
            return Uri.parse(trimmed)
        }

        val localFile = runCatching { File(URI(trimmed)) }.getOrNull() ?: return Uri.parse(trimmed)
        return runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                localFile,
            )
        }.getOrNull()
    }
}

private fun String.videoMimeType(): String {
    val normalized = substringBefore('?').substringBefore('#').lowercase()
    return when {
        normalized.endsWith(".m3u8") -> "application/x-mpegURL"
        normalized.endsWith(".mpd") -> "application/dash+xml"
        normalized.endsWith(".mkv") -> "video/x-matroska"
        normalized.endsWith(".webm") -> "video/webm"
        normalized.endsWith(".avi") -> "video/x-msvideo"
        normalized.endsWith(".mov") -> "video/quicktime"
        else -> "video/*"
    }
}
