package com.nuvio.app.features.player

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.extractor.mp4.PsshAtomUtil
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.io.encoding.Base64

/**
 * ClearKey DRM for a stream whose add-on hands out the keys (`behaviorHints.clearKeys`): the
 * license is answered locally, so there is no license server and no proxy.
 *
 * Every audio and video format gets the same ClearKey init data for all the known key ids. The
 * stream's own signalling then no longer matters: a Widevine/PlayReady-only PSSH, an MPD without
 * `cenc:default_KID` or no PSSH at all play the same way, in DASH, HLS and MP4, and one session
 * serves every track. Text and image tracks are left as they are: their renderers take no DRM.
 */
internal class ClearKeyDrmSessionManager(clearKeys: String) : DrmSessionManager {

    private val pairs: List<Pair<ByteArray, ByteArray>> = clearKeys.split(',').map { pair ->
        pair.substringBefore(':').hexBytes() to pair.substringAfter(':').hexBytes()
    }

    private val delegate: DrmSessionManager = DefaultDrmSessionManager.Builder()
        .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
        .setMultiSession(true)
        .build(LocalMediaDrmCallback(licenseResponse(pairs).toByteArray(Charsets.UTF_8)))

    // Common-system PSSH, as Media3 builds from an MPD default_KID: the one the ClearKey CDM reads.
    private val schemeData = DrmInitData.SchemeData(
        C.COMMON_PSSH_UUID,
        MimeTypes.VIDEO_MP4,
        PsshAtomUtil.buildPsshAtom(
            C.COMMON_PSSH_UUID,
            pairs.map { (kid, _) -> ByteBuffer.wrap(kid).let { UUID(it.long, it.long) } }.toTypedArray(),
            /* data= */ null
        )
    )

    override fun prepare() = delegate.prepare()

    override fun release() = delegate.release()

    override fun setPlayer(playbackLooper: Looper, playerId: PlayerId) =
        delegate.setPlayer(playbackLooper, playerId)

    override fun preacquireSession(
        eventDispatcher: DrmSessionEventListener.EventDispatcher?,
        format: Format
    ): DrmSessionManager.DrmSessionReference = delegate.preacquireSession(eventDispatcher, format.withClearKey())

    override fun acquireSession(
        eventDispatcher: DrmSessionEventListener.EventDispatcher?,
        format: Format
    ): DrmSession? = delegate.acquireSession(eventDispatcher, format.withClearKey())

    override fun getCryptoType(format: Format): Int = delegate.getCryptoType(format.withClearKey())

    private fun Format.withClearKey(): Format {
        val protectedTrack = drmInitData != null ||
            MimeTypes.isVideo(sampleMimeType) || MimeTypes.isAudio(sampleMimeType)
        if (!protectedTrack) return this
        // The scheme type (cenc/cbcs) stays: the session manager checks the device supports it.
        val initData = DrmInitData(drmInitData?.schemeType ?: C.CENC_TYPE_cenc, schemeData)
        return buildUpon().setDrmInitData(initData).build()
    }

    companion object {
        private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

        /** The W3C ClearKey JSON Web Key set the CDM takes as the license. */
        internal fun licenseResponse(pairs: List<Pair<ByteArray, ByteArray>>): String =
            pairs.joinToString(",", prefix = "{\"keys\":[", postfix = "],\"type\":\"temporary\"}") { (kid, key) ->
                "{\"kty\":\"oct\",\"kid\":\"${base64Url.encode(kid)}\",\"k\":\"${base64Url.encode(key)}\"}"
            }

        private fun String.hexBytes(): ByteArray = ByteArray(length / 2) { index ->
            substring(2 * index, 2 * index + 2).toInt(16).toByte()
        }
    }
}
