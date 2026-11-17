package com.nuvio.app.features.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import com.nuvio.app.features.autosync.AutoSyncLibassSubtitles
import com.nuvio.tv.libass.media.AssHandler
import com.nuvio.tv.libass.media.extractor.AssMatroskaExtractor
import com.nuvio.tv.libass.media.parser.AssSubtitleParserFactory
import com.nuvio.tv.libass.media.render.AssRenderersFactory
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

private val assHandlersByPlayer = Collections.synchronizedMap(WeakHashMap<ExoPlayer, AssHandler>())
private val assMediaSourceFactoriesByPlayer =
    Collections.synchronizedMap(WeakHashMap<ExoPlayer, DefaultMediaSourceFactory>())

@OptIn(UnstableApi::class)
internal fun ExoPlayer.Builder.buildWithAssSupportCompat(
    context: Context,
    dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(context),
    extractorsFactory: ExtractorsFactory = DefaultExtractorsFactory(),
    renderersFactory: RenderersFactory = DefaultRenderersFactory(context),
    audioDelayUsProvider: () -> Long = { 0L },
): ExoPlayer {
    val assHandler = AssHandler()
    val assSubtitleParserFactory = CompatAssSubtitleParserFactory(assHandler)
    val assExtractorsFactory = extractorsFactory.withAssMkvSupportCompat(
        subtitleParserFactory = assSubtitleParserFactory,
        assHandler = assHandler
    )

    val mediaSourceFactory = DefaultMediaSourceFactory(
        dataSourceFactory,
        assExtractorsFactory
    )
    mediaSourceFactory.setSubtitleParserFactory(assSubtitleParserFactory)

    // Wrap the renderers factory with AssRenderersFactory to add a
    // NoSampleRenderer-based position fallback for tunneled playback.
    val assRenderersFactory = AssRenderersFactory(assHandler, renderersFactory)
    val player = this
        .setMediaSourceFactory(AudioDelayMediaSourceFactory(mediaSourceFactory, audioDelayUsProvider))
        .setRenderersFactory(assRenderersFactory)
        .build()

    assHandlersByPlayer[player] = assHandler
    assMediaSourceFactoriesByPlayer[player] = mediaSourceFactory
    assHandler.init(player)
    return player
}

internal fun ExoPlayer.getAssHandlerCompat(): AssHandler? = assHandlersByPlayer[this]

/**
 * Whether a text track is ASS/SSA. Subtitles parsed during extraction (e.g. addon subtitles
 * attached as sideloaded tracks) report `application/x-media3-cues` and keep the original type
 * in [Format.codecs], so both are checked, as in NuvioTV's `hasAssSsaTextTrack`.
 */
internal fun Format.isAssSubtitleFormat(): Boolean {
    if (sampleMimeType == MimeTypes.TEXT_SSA) return true
    return codecs
        ?.split(',')
        ?.map { it.trim().lowercase(Locale.US) }
        ?.any { codec ->
            codec == MimeTypes.TEXT_SSA || codec == "s_text/ass" || codec == "s_text/ssa" || codec.endsWith("/x-ssa")
        } == true
}

/** The libass-aware media source factory, for sources built outside the player (e.g. merged audio). */
internal fun ExoPlayer.getAssMediaSourceFactoryCompat(): DefaultMediaSourceFactory? = assMediaSourceFactoriesByPlayer[this]

@OptIn(UnstableApi::class)
internal fun ExoPlayer.releaseWithAssSupportCompat() {
    val assHandler = assHandlersByPlayer.remove(this)
    assMediaSourceFactoriesByPlayer.remove(this)
    try {
        release()
    } finally {
        assHandler?.release()
    }
}

@OptIn(UnstableApi::class)
private class CompatAssSubtitleParserFactory(
    private val assHandler: AssHandler
) : SubtitleParser.Factory {
    private val delegate = AssSubtitleParserFactory(assHandler)

    override fun supportsFormat(format: Format): Boolean {
        return delegate.supportsFormat(normalizeSsaFormat(format))
    }

    override fun getCueReplacementBehavior(format: Format): Int {
        return delegate.getCueReplacementBehavior(normalizeSsaFormat(format))
    }

    override fun create(format: Format): SubtitleParser {
        val normalized = normalizeSsaFormat(format)
        return AutoSyncLibassSubtitles.wrapParser(assHandler, normalized, delegate.create(normalized)) // AutoSync hook
    }

    private fun normalizeSsaFormat(format: Format): Format {
        val isSsaByCodecs = format.codecs == MimeTypes.TEXT_SSA
        val isSsaByMime = format.sampleMimeType == MimeTypes.TEXT_SSA
        if (isSsaByCodecs && !isSsaByMime) {
            return format.buildUpon()
                .setSampleMimeType(MimeTypes.TEXT_SSA)
                .build()
        }
        return format
    }
}

@OptIn(UnstableApi::class)
private fun ExtractorsFactory.withAssMkvSupportCompat(
    subtitleParserFactory: SubtitleParser.Factory,
    assHandler: AssHandler
): ExtractorsFactory {
    return ExtractorsFactory {
        val extractors = createExtractors()
        extractors.forEachIndexed { index, extractor ->
            if (extractor.underlyingImplementation is MatroskaExtractor) {
                extractors[index] = extractor.replaceInnermostExtractor(
                    AssMatroskaExtractor(subtitleParserFactory, assHandler),
                )
            }
        }
        extractors
    }
}
