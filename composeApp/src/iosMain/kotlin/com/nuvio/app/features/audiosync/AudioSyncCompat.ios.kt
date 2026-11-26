package com.nuvio.app.features.audiosync

import com.nuvio.app.features.player.normalizeLanguageCode
import platform.Foundation.NSQualityOfServiceUtility
import platform.Foundation.NSThread

private val iso1ToIso3 = mapOf(
    "af" to "afr", "am" to "amh", "ar" to "ara", "az" to "aze", "be" to "bel",
    "bg" to "bul", "bn" to "ben", "bo" to "bod", "bs" to "bos", "ca" to "cat",
    "cs" to "ces", "cy" to "cym", "da" to "dan", "de" to "deu", "el" to "ell",
    "en" to "eng", "eo" to "epo", "es" to "spa", "et" to "est", "eu" to "eus",
    "fa" to "fas", "fi" to "fin", "fil" to "fil", "fr" to "fra", "ga" to "gle",
    "gl" to "glg", "gu" to "guj", "he" to "heb", "hi" to "hin", "hr" to "hrv",
    "hu" to "hun", "hy" to "hye", "id" to "ind", "in" to "ind", "is" to "isl",
    "it" to "ita", "iw" to "heb", "ja" to "jpn", "ji" to "yid", "ka" to "kat",
    "kk" to "kaz", "km" to "khm", "kn" to "kan", "ko" to "kor", "ku" to "kur",
    "ky" to "kir", "la" to "lat", "lo" to "lao", "lt" to "lit", "lv" to "lav",
    "mi" to "mri", "mk" to "mkd", "ml" to "mal", "mn" to "mon", "mr" to "mar",
    "ms" to "msa", "mt" to "mlt", "my" to "mya", "nb" to "nor", "ne" to "nep",
    "nl" to "nld", "nn" to "nor", "no" to "nor", "pa" to "pan", "pl" to "pol",
    "ps" to "pus", "pt" to "por", "ro" to "ron", "ru" to "rus", "si" to "sin",
    "sk" to "slk", "sl" to "slv", "sq" to "sqi", "sr" to "srp", "sv" to "swe",
    "sw" to "swa", "ta" to "tam", "te" to "tel", "th" to "tha", "tl" to "tgl",
    "tr" to "tur", "uk" to "ukr", "ur" to "urd", "uz" to "uzb", "vi" to "vie",
    "yi" to "yid", "zh" to "zho", "zu" to "zul",
)

internal actual fun startAudioSyncThread(name: String, body: () -> Unit) {
    val thread = NSThread(block = body)
    thread.name = name
    thread.qualityOfService = NSQualityOfServiceUtility
    thread.start()
}

internal actual fun audioSyncIso3Language(value: String, code: String): String? {
    iso1ToIso3[code]?.let { return it }
    val normalized = normalizeLanguageCode(value)?.substringBefore('-')
    if (!normalized.isNullOrEmpty()) {
        iso1ToIso3[normalized]?.let { return it }
        if (normalized.length == 3) return normalized
    }
    return code.takeIf { it.length == 3 }
}
