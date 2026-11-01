package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal const val MaxSubtitleFontBytes = 10 * 1024 * 1024

@Serializable
internal data class SubtitleFont(val id: String, val name: String)

internal object SubtitleFonts {
    fun installed(): List<SubtitleFont> = runCatching {
        val bytes = SubtitleFontStorage.read("fonts.json") ?: return emptyList()
        Json.decodeFromString<List<SubtitleFont>>(bytes.decodeToString()).filter {
            it.id.matches(Regex("custom-[a-f0-9-]{36}"))
        }
    }.getOrDefault(emptyList())

    @OptIn(ExperimentalUuidApi::class)
    fun import(name: String, bytes: ByteArray): SubtitleFont {
        require(isSupportedSubtitleFont(name, bytes))
        val fonts = installed()
        fonts.firstOrNull { SubtitleFontStorage.read(it.id)?.contentEquals(bytes) == true }?.let { return it }
        val font = SubtitleFont("custom-${Uuid.random()}", name.substringBeforeLast('.').take(80))
        SubtitleFontStorage.write(font.id, bytes)
        try {
            requireNotNull(SubtitleFontStorage.fontFamily(font.id))
            SubtitleFontStorage.write("fonts.json", Json.encodeToString(fonts + font).encodeToByteArray())
        } catch (error: Exception) {
            SubtitleFontStorage.remove(font.id)
            throw error
        }
        return font
    }

    fun remove(id: String) {
        SubtitleFontStorage.write("fonts.json", Json.encodeToString(installed().filterNot { it.id == id }).encodeToByteArray())
        SubtitleFontStorage.remove(id)
    }
}

internal fun isSupportedSubtitleFont(name: String, bytes: ByteArray): Boolean {
    if (name.substringAfterLast('.', "").lowercase() !in setOf("ttf", "otf")) return false
    if (bytes.size !in 12..MaxSubtitleFontBytes) return false
    val signature = bytes.take(4).map { it.toInt() and 255 }
    if (signature != listOf(0, 1, 0, 0) && bytes.decodeToString(0, 4) != "OTTO") return false
    fun uint16(offset: Int) = ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
    fun uint32(offset: Int): Long = (0..3).fold(0L) { value, i -> (value shl 8) or (bytes[offset + i].toLong() and 255) }
    val tables = uint16(4)
    if (tables == 0 || 12L + tables * 16L > bytes.size) return false
    return (0 until tables).all { index ->
        val offset = 12 + index * 16
        val start = uint32(offset + 8)
        val length = uint32(offset + 12)
        start <= bytes.size && length <= bytes.size - start
    }
}

@Composable
internal fun rememberSubtitleFontFamily(id: String): FontFamily {
    val family = produceState<FontFamily>(FontFamily.SansSerif, id) {
        value = withContext(Dispatchers.Default) {
            when (id) {
                "serif" -> FontFamily.Serif
                "mono" -> FontFamily.Monospace
                "default" -> SubtitleFontStorage.defaultFamily()
                "sans" -> FontFamily.SansSerif
                else -> runCatching { SubtitleFontStorage.fontFamily(id) }.getOrNull() ?: SubtitleFontStorage.defaultFamily()
            }
        }
    }
    return family.value
}

internal expect object SubtitleFontStorage {
    fun defaultFamily(): FontFamily
    fun read(name: String): ByteArray?
    fun write(name: String, bytes: ByteArray)
    fun remove(name: String)
    fun fontFamily(id: String): FontFamily?
}

@Composable
internal expect fun rememberSubtitleFontImporter(onResult: (Result<SubtitleFont>) -> Unit): () -> Unit
