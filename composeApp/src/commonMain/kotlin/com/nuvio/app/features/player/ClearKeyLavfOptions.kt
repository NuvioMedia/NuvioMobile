package com.nuvio.app.features.player

/**
 * libmpv's `demuxer-lavf-o` value for ClearKey `kid:key,kid:key` pairs (hex): FFmpeg's DASH demuxer
 * reads `cenc_decryption_keys`, MP4 `decryption_keys`. FFmpeg's HLS demuxer rejects SAMPLE-AES-CTR
 * key tags, so encrypted HLS needs ExoPlayer (Android) and cannot play on iOS.
 */
internal fun clearKeyLavfOptions(clearKeys: String): String {
    val keys = clearKeys.split(',').joinToString(":") { it.replace(':', '=') }
    // %n% quoting keeps FFmpeg's own `=`/`:` separators inside one mpv key-value entry.
    return "cenc_decryption_keys=%${keys.length}%$keys,decryption_keys=%${keys.length}%$keys"
}
