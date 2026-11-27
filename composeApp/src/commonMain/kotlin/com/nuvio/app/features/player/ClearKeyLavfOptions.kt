package com.nuvio.app.features.player

/**
 * libmpv's `demuxer-lavf-o` value for ClearKey `kid:key,kid:key` pairs (hex): FFmpeg's DASH demuxer
 * reads `cenc_decryption_keys`, MP4 `decryption_keys`, and HLS hands `seg_format_options` to the MP4
 * demuxer of its fMP4 segments. HLS also needs an FFmpeg whose demuxer keeps the full
 * `SAMPLE-AES-CTR` method (an 11-byte buffer cut it to `SAMPLE-AES`); Android keeps HLS on ExoPlayer.
 */
internal fun clearKeyLavfOptions(clearKeys: String): String {
    val keys = clearKeys.split(',').joinToString(":") { it.replace(':', '=') }
    // Nested in seg_format_options, the dictionary's own `=`/`:` separators are escaped one level.
    val segmentOptions = "decryption_keys=" + keys.replace("=", "\\=").replace(":", "\\:")
    // %n% quoting keeps FFmpeg's separators inside one mpv key-value entry.
    fun quoted(value: String) = "%${value.length}%$value"
    return "cenc_decryption_keys=${quoted(keys)},decryption_keys=${quoted(keys)}," +
        "seg_format_options=${quoted(segmentOptions)}"
}
