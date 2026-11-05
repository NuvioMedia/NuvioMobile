package com.nuvio.app.features.player

import android.content.res.Resources
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTrackNameProvider

@UnstableApi
class CustomDefaultTrackNameProvider(resources: Resources) : DefaultTrackNameProvider(resources) {

    override fun getTrackName(format: Format): String {
        var trackName = super.getTrackName(format)

        if (format.sampleMimeType != null) {
            var sampleFormat = resolveCodecName(format.sampleMimeType, format.codecs)
            if (sampleFormat == null) {
                sampleFormat = format.sampleMimeType
            }
            if (sampleFormat != null) {
                trackName += " ($sampleFormat)"
            }
        }

        if (format.label != null) {
            if (!trackName.startsWith(format.label!!)) {
                trackName += " - ${format.label}"
            }
        }

        return trackName
    }

    companion object {
        /**
         * Resolve a human-readable codec name from a MIME type, falling back to
         * [MimeTypes.getVideoMediaMimeType] / [MimeTypes.getMediaMimeType] when
         * the raw codecs string (e.g. `hvc1.2.4.L153.B0`) doesn't match directly.
         */
        fun resolveCodecName(mimeType: String?, codecs: String?): String? {
            formatNameFromMime(mimeType)?.let { return it }
            if (codecs == null) return null
            MimeTypes.getVideoMediaMimeType(codecs)?.let { formatNameFromMime(it) }?.let { return it }
            MimeTypes.getMediaMimeType(codecs)?.let { formatNameFromMime(it) }?.let { return it }
            return null
        }

        fun formatNameFromMime(mimeType: String?): String? {
            if (mimeType == null) return null

            return when (mimeType) {
                MimeTypes.AUDIO_DTS -> "DTS"
                MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
                MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
                MimeTypes.AUDIO_DTS_X -> "DTS:X"
                MimeTypes.AUDIO_TRUEHD -> "TrueHD"
                MimeTypes.AUDIO_AC3 -> "AC-3"
                MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
                MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3-JOC"
                MimeTypes.AUDIO_AC4 -> "AC-4"
                MimeTypes.AUDIO_AAC -> "AAC"
                MimeTypes.AUDIO_MPEG -> "MP3"
                MimeTypes.AUDIO_MPEG_L2 -> "MP2"
                MimeTypes.AUDIO_VORBIS -> "Vorbis"
                MimeTypes.AUDIO_OPUS -> "Opus"
                MimeTypes.AUDIO_FLAC -> "FLAC"
                MimeTypes.AUDIO_ALAC -> "ALAC"
                MimeTypes.AUDIO_WAV -> "WAV"
                MimeTypes.AUDIO_AMR -> "AMR"
                MimeTypes.AUDIO_AMR_NB -> "AMR-NB"
                MimeTypes.AUDIO_AMR_WB -> "AMR-WB"
                MimeTypes.AUDIO_IAMF -> "IAMF"
                MimeTypes.AUDIO_MPEGH_MHA1 -> "MPEG-H"
                MimeTypes.AUDIO_MPEGH_MHM1 -> "MPEG-H"
                MimeTypes.VIDEO_H264 -> "AVC"
                MimeTypes.VIDEO_H265 -> "HEVC"
                MimeTypes.VIDEO_AV1 -> "AV1"
                MimeTypes.VIDEO_VP8 -> "VP8"
                MimeTypes.VIDEO_VP9 -> "VP9"
                MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
                "application/pgs" -> "PGS"
                MimeTypes.APPLICATION_SUBRIP -> "SRT"
                MimeTypes.TEXT_SSA -> "SSA"
                MimeTypes.TEXT_VTT -> "VTT"
                MimeTypes.APPLICATION_TTML -> "TTML"
                MimeTypes.APPLICATION_TX3G -> "TX3G"
                MimeTypes.APPLICATION_DVBSUBS -> "DVB"
                else -> null
            }
        }

        fun getChannelLayoutName(channelCount: Int): String? {
            return when (channelCount) {
                1 -> "Mono"
                2 -> "Stereo"
                6 -> "5.1"
                8 -> "7.1"
                else -> if (channelCount > 0) "${channelCount}ch" else null
            }
        }
    }
}
