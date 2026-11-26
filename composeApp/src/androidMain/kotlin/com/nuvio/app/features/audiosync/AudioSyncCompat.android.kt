package com.nuvio.app.features.audiosync

import java.util.Locale

private val audioSyncLanguageNames: Map<String, String> by lazy {
    Locale.getISOLanguages().associateBy(
        { Locale(it).getDisplayLanguage(Locale.ENGLISH).lowercase() },
        { runCatching { Locale(it).isO3Language }.getOrDefault(it) },
    )
}

internal actual fun startAudioSyncThread(name: String, body: () -> Unit) {
    Thread(body, name).apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
        start()
    }
}

internal actual fun audioSyncIso3Language(value: String, code: String): String? {
    if (code.length == 2 || code.length == 3) {
        val iso3 = runCatching { Locale(code).isO3Language }.getOrNull()
        if (!iso3.isNullOrEmpty()) return iso3
    }
    val name = value.substringBefore('(').trim()
    return audioSyncLanguageNames[name]
        ?: audioSyncLanguageNames.entries.firstOrNull { it.key.length >= 4 && name.startsWith(it.key) }?.value
}
