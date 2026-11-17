package com.nuvio.app.features.autosync

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.nuvio.app.features.streams.StreamSubtitle
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Creates the AutoSync coordinator for one libmpv source and disposes it with that source.
 * Pass the libmpv controller through [AutoSyncMpvCoordinator.wrap] before `onControllerReady`.
 */
@Composable
internal fun rememberLibmpvAutoSyncCoordinator(
    scope: CoroutineScope,
    sourceUrl: String,
    sourceHeaders: Map<String, String>,
    externalSubtitles: List<StreamSubtitle>,
    preferredSubtitleLanguage: String?,
): AutoSyncMpvCoordinator {
    val context = LocalContext.current
    val latestExternalSubtitles = rememberUpdatedState(externalSubtitles)
    val latestPreferredLanguage = rememberUpdatedState(preferredSubtitleLanguage)
    val coordinator = remember(sourceUrl, sourceHeaders, scope) {
        AutoSyncMpvCoordinator(
            scope = scope,
            sourceUrl = sourceUrl,
            sourceHeaders = sourceHeaders,
            getSubtitleHeaders = { subtitleUrl ->
                latestExternalSubtitles.value
                    .firstOrNull { it.url == subtitleUrl }
                    ?.headers
                    .orEmpty()
            },
            getPreferredLanguage = { latestPreferredLanguage.value },
            platform = AndroidAutoSyncMpvPlatform(context.applicationContext),
        )
    }
    DisposableEffect(coordinator) {
        onDispose { coordinator.dispose() }
    }
    return coordinator
}

private class AndroidAutoSyncMpvPlatform(private val context: Context) : AutoSyncMpvPlatform {
    override suspend fun writeSubtitleFile(fileName: String, stalePrefix: String, content: String): String? =
        withContext(Dispatchers.IO) {
            val cacheDir = File(context.cacheDir, "subtitles").also { it.mkdirs() }
            val file = File(cacheDir, fileName)
            cacheDir.listFiles { candidate -> candidate.name.startsWith(stalePrefix) && candidate != file }
                ?.forEach { it.delete() }
            file.writeText(content, Charsets.UTF_8)
            file.absolutePath
        }

    override fun showMessage(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}
