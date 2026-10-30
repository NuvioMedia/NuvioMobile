package com.nuvio.app.features.autosync

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlin.math.min

internal object SubtitleCharsetDetector {

    private fun safeCharset(name: String, fallback: Charset = StandardCharsets.UTF_8): Charset {
        return try {
            Charset.forName(name)
        } catch (_: Exception) {
            fallback
        }
    }

    private val CHARSET_WIN1254: Charset = safeCharset("windows-1254")
    private val CHARSET_WIN1255: Charset = safeCharset("windows-1255")
    private val CHARSET_WIN1256: Charset = safeCharset("windows-1256")
    private val CHARSET_WIN1251: Charset = safeCharset("windows-1251")
    private val CHARSET_WIN1250: Charset = safeCharset("windows-1250")
    private val CHARSET_WIN1253: Charset = safeCharset("windows-1253")
    private val CHARSET_WIN1252: Charset = safeCharset("windows-1252", StandardCharsets.ISO_8859_1)
    private val CHARSET_WIN874: Charset = safeCharset("windows-874", safeCharset("TIS-620", StandardCharsets.ISO_8859_1))
    private val CHARSET_WIN1258: Charset = safeCharset("windows-1258")
    private val CHARSET_KOI8_R: Charset = safeCharset("KOI8-R")
    private val CHARSET_GB18030: Charset = safeCharset("GB18030")
    private val CHARSET_BIG5: Charset = safeCharset("Big5")
    private val CHARSET_SHIFT_JIS: Charset = safeCharset("Shift_JIS")
    private val CHARSET_EUC_KR: Charset = safeCharset("EUC-KR")

    private const val MAX_SAMPLE_BYTES = 4096

    fun decode(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
        languageHint: String? = null
    ): String {
        if (length <= 0) return ""

        if (length >= 3 && (bytes[offset].toInt() and 0xFF) == 0xEF &&
            (bytes[offset + 1].toInt() and 0xFF) == 0xBB &&
            (bytes[offset + 2].toInt() and 0xFF) == 0xBF
        ) {
            val utf8 = String(bytes, offset + 3, length - 3, StandardCharsets.UTF_8)
            return repairDoubleEncodedUtf8IfNeeded(utf8, languageHint)
        }
        if (length >= 2 && (bytes[offset].toInt() and 0xFF) == 0xFF &&
            (bytes[offset + 1].toInt() and 0xFF) == 0xFE
        ) {
            return String(bytes, offset + 2, length - 2, StandardCharsets.UTF_16LE)
        }
        if (length >= 2 && (bytes[offset].toInt() and 0xFF) == 0xFE &&
            (bytes[offset + 1].toInt() and 0xFF) == 0xFF
        ) {
            return String(bytes, offset + 2, length - 2, StandardCharsets.UTF_16BE)
        }

        if (isFastValidUtf8(bytes, offset, length)) {
            val utf8 = String(bytes, offset, length, StandardCharsets.UTF_8)
            return repairDoubleEncodedUtf8IfNeeded(utf8, languageHint)
        }

        val hintedCharset = resolveCharsetFromLanguageHint(bytes, offset, length, languageHint)
        val charset = hintedCharset ?: detectUniversalCharset(bytes, offset, length)
        val decoded = String(bytes, offset, length, charset)
        return repairDoubleEncodedUtf8IfNeeded(decoded, languageHint)
    }

    fun normalizeToUtf8(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
        languageHint: String? = null
    ): ByteArray {
        val decoded = decode(bytes, offset, length, languageHint)
        return decoded.toByteArray(StandardCharsets.UTF_8)
    }

    fun repairDoubleEncodedUtf8IfNeeded(text: String, languageHint: String? = null): String {
        if (text.isEmpty()) return text

        val normalized = languageHint?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_')
        val isHebrewHint = normalized == "heb" || normalized == "he" || normalized == "iw" || normalized?.startsWith("hebrew") == true

        // Double-encoded UTF-8 subtitles (where Windows-1255 Hebrew bytes were decoded as Latin-1 and saved as UTF-8)
        // are an issue specific to Hebrew providers like Ktuvit.
        // If a non-Hebrew language hint is present (e.g. Russian, Romanian, Spanish), or if the text contains standard Latin/Cyrillic words,
        // do not attempt conversion.
        if (!isHebrewHint && !normalized.isNullOrBlank()) {
            return text
        }

        val words = text.split("\\s+".toRegex()).filter { it.length >= 2 && it.any { c -> c.isLetter() } }
        if (words.isEmpty()) return text

        // In true double-encoded Hebrew text, dialogue words consist almost exclusively of Latin-1 characters in \u00E0..\u00FA
        val mojibakeWordsCount = words.count { word ->
            word.all { c -> (c in '\u00E0'..'\u00FA') || c in "<i></i>-.,!?:'\"<>/\\_()" }
        }

        val threshold = if (isHebrewHint) 5 else 10
        if (mojibakeWordsCount >= threshold && (mojibakeWordsCount * 2 >= words.size)) {
            try {
                // Ensure text can be encoded to Windows-1252 cleanly without unmappable replacement characters
                val encoder = CHARSET_WIN1252.newEncoder()
                if (!encoder.canEncode(text)) return text

                val win1252Bytes = text.toByteArray(CHARSET_WIN1252)
                val hebrewCandidate = String(win1252Bytes, CHARSET_WIN1255)
                val hebrewChars = hebrewCandidate.count { it in '\u0590'..'\u05FF' }
                if (hebrewChars >= 10) {
                    return hebrewCandidate
                }
            } catch (_: Exception) {}
        }

        return text
    }

    private fun resolveCharsetFromLanguageHint(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        languageHint: String?
    ): Charset? {
        if (languageHint.isNullOrBlank()) return null
        val normalized = languageHint.trim().lowercase()
        val lang = normalized.substringBefore('-').substringBefore('_')

        return when {
            lang == "heb" || lang == "he" || lang == "iw" || lang.startsWith("hebrew") -> CHARSET_WIN1255
            lang == "ara" || lang == "ar" || lang.startsWith("arabic") -> CHARSET_WIN1256
            lang == "ell" || lang == "el" || lang == "gre" || lang.startsWith("greek") -> CHARSET_WIN1253
            lang == "tur" || lang == "tr" || lang.startsWith("turkish") -> CHARSET_WIN1254
            lang == "rus" || lang == "ru" || lang == "ukr" || lang == "uk" || lang == "bel" || lang == "be" ||
                lang == "bul" || lang == "bg" || lang == "mkd" || lang == "mk" || lang == "srp" || lang == "sr" ||
                lang.startsWith("russian") || lang.startsWith("ukrainian") || lang.startsWith("bulgarian") ||
                lang.startsWith("serbian") || lang.startsWith("cyrillic") -> {
                if (hasKoi8Vowels(bytes, offset, length)) CHARSET_KOI8_R else CHARSET_WIN1251
            }
            lang == "por" || lang == "pt" || lang.startsWith("portuguese") ||
                lang == "spa" || lang == "es" || lang.startsWith("spanish") ||
                lang == "fra" || lang == "fre" || lang == "fr" || lang.startsWith("french") ||
                lang == "deu" || lang == "ger" || lang == "de" || lang.startsWith("german") ||
                lang == "ita" || lang == "it" || lang.startsWith("italian") ||
                lang == "nld" || lang == "dut" || lang == "nl" || lang.startsWith("dutch") ||
                lang == "eng" || lang == "en" || lang.startsWith("english") ||
                lang == "dan" || lang == "da" || lang.startsWith("danish") ||
                lang == "swe" || lang == "sv" || lang.startsWith("swedish") ||
                lang == "nor" || lang == "no" || lang.startsWith("norwegian") ||
                lang == "fin" || lang == "fi" || lang.startsWith("finnish") ||
                lang == "cat" || lang == "ca" || lang.startsWith("catalan") ||
                lang == "glg" || lang == "gl" || lang.startsWith("galician") ||
                lang == "eus" || lang == "baq" || lang == "eu" || lang.startsWith("basque") -> CHARSET_WIN1252
            lang == "tha" || lang == "th" || lang.startsWith("thai") -> CHARSET_WIN874
            lang == "vie" || lang == "vi" || lang.startsWith("vietnamese") -> CHARSET_WIN1258
            lang == "pol" || lang == "pl" || lang == "ces" || lang == "cs" || lang == "cze" || lang == "hun" ||
                lang == "hu" || lang == "slv" || lang == "sl" || lang == "hrv" || lang == "hr" || lang == "ron" ||
                lang == "ro" || lang == "rum" || lang == "slk" || lang == "sk" || lang.startsWith("polish") ||
                lang.startsWith("czech") || lang.startsWith("hungarian") || lang.startsWith("romanian") ||
                lang.startsWith("croatian") || lang.startsWith("slovak") || lang.startsWith("slovenian") -> CHARSET_WIN1250
            lang == "zho" || lang == "zh" || lang == "chi" || lang.startsWith("chinese") -> {
                if (normalized.contains("tw") || normalized.contains("hk") || normalized.contains("traditional") || normalized.contains("hant")) {
                    CHARSET_BIG5
                } else if (normalized.contains("cn") || normalized.contains("sg") || normalized.contains("simplified") || normalized.contains("hans")) {
                    CHARSET_GB18030
                } else {
                    if (isCjkClean(bytes, offset, length, CHARSET_BIG5)) CHARSET_BIG5 else CHARSET_GB18030
                }
            }
            lang == "jpn" || lang == "ja" || lang.startsWith("japanese") -> CHARSET_SHIFT_JIS
            lang == "kor" || lang == "ko" || lang.startsWith("korean") -> CHARSET_EUC_KR
            else -> null
        }
    }

    private fun hasKoi8Vowels(bytes: ByteArray, offset: Int, length: Int): Boolean {
        val sampleLength = min(length, MAX_SAMPLE_BYTES)
        val end = offset + sampleLength
        var russianVowels = 0
        var koi8Vowels = 0
        for (i in offset until end) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0xEE || b == 0xE0 || b == 0xE5 || b == 0xE8 || b == 0xFF || b == 0xFB) russianVowels++
            if (b == 0xCF || b == 0xC1 || b == 0xC5 || b == 0xC9 || b == 0xD5 || b == 0xDF) koi8Vowels++
        }
        return koi8Vowels > russianVowels && koi8Vowels >= 3
    }

    private fun isFastValidUtf8(bytes: ByteArray, offset: Int, length: Int): Boolean {
        var i = offset
        val end = offset + length
        while (i < end) {
            val b1 = bytes[i++].toInt() and 0xFF
            if (b1 <= 0x7F) continue
            if (b1 in 0xC2..0xDF) {
                if (i >= end || (bytes[i++].toInt() and 0xC0) != 0x80) return false
            } else if (b1 in 0xE0..0xEF) {
                if (i + 1 >= end || (bytes[i++].toInt() and 0xC0) != 0x80 || (bytes[i++].toInt() and 0xC0) != 0x80) return false
            } else if (b1 in 0xF0..0xF4) {
                if (i + 2 >= end || (bytes[i++].toInt() and 0xC0) != 0x80 || (bytes[i++].toInt() and 0xC0) != 0x80 || (bytes[i++].toInt() and 0xC0) != 0x80) return false
            } else {
                return false
            }
        }
        return true
    }

    private fun detectUniversalCharset(bytes: ByteArray, offset: Int, length: Int): Charset {
        var startOffset = offset
        val totalEnd = offset + length
        while (startOffset < totalEnd && (bytes[startOffset].toInt() and 0xFF) < 0x80) {
            startOffset++
        }
        if (startOffset >= totalEnd) return StandardCharsets.UTF_8

        val sampleOffset = if (startOffset > offset) (startOffset - min(100, startOffset - offset)) else offset
        val sampleLength = min(totalEnd - sampleOffset, MAX_SAMPLE_BYTES)
        val end = sampleOffset + sampleLength

        var asciiLetters = 0
        var nonAscii = 0
        var consecutiveNonAscii = 0
        var maxConsecutiveNonAscii = 0

        for (i in sampleOffset until end) {
            val b = bytes[i].toInt() and 0xFF
            if ((b in 'a'.code..'z'.code) || (b in 'A'.code..'Z'.code)) {
                asciiLetters++
                consecutiveNonAscii = 0
            } else if (b >= 0x80) {
                nonAscii++
                consecutiveNonAscii++
                if (consecutiveNonAscii > maxConsecutiveNonAscii) {
                    maxConsecutiveNonAscii = consecutiveNonAscii
                }
            } else {
                consecutiveNonAscii = 0
            }
        }

        if (nonAscii == 0) return StandardCharsets.UTF_8

        // Japanese Shift_JIS check (Hiragana in 0x82 0x9F..0xF1)
        var hiraganaCount = 0
        var sjisIdx = sampleOffset
        while (sjisIdx < end - 1) {
            val b1 = bytes[sjisIdx].toInt() and 0xFF
            val b2 = bytes[sjisIdx + 1].toInt() and 0xFF
            if (b1 == 0x82 && b2 in 0x9F..0xF1) {
                hiraganaCount++
                sjisIdx++
            }
            sjisIdx++
        }
        if (hiraganaCount >= 2) {
            return CHARSET_SHIFT_JIS
        }

        // Korean EUC-KR syllables check (Hangul block match)
        val hangulScore = countBlockMatches(bytes, sampleOffset, sampleLength, CHARSET_EUC_KR, Character.UnicodeBlock.HANGUL_SYLLABLES)
        val gbHanziScore = countBlockMatches(bytes, sampleOffset, sampleLength, CHARSET_GB18030, Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS)

        if (hangulScore >= 4 && hangulScore >= gbHanziScore && maxConsecutiveNonAscii >= 4) {
            return CHARSET_EUC_KR
        }

        // Thai single-byte consonants check (0xA1..0xBF)
        var thaiConsonantsEarly = 0
        for (i in sampleOffset until end) {
            val b = bytes[i].toInt() and 0xFF
            if (b in 0xA1..0xBF) thaiConsonantsEarly++
        }
        if (thaiConsonantsEarly * 4 >= nonAscii && thaiConsonantsEarly >= 4) {
            return CHARSET_WIN874
        }

        if (gbHanziScore >= 4 && maxConsecutiveNonAscii >= 6) {
            var big5TrailCount = 0
            var i = sampleOffset
            while (i < end - 1) {
                val b1 = bytes[i].toInt() and 0xFF
                val b2 = bytes[i + 1].toInt() and 0xFF
                if (b1 in 0xA1..0xF9 && b2 in 0x40..0x7E) {
                    big5TrailCount++
                    i++
                }
                i++
            }
            if (big5TrailCount >= 2 && isCjkClean(bytes, sampleOffset, sampleLength, CHARSET_BIG5)) {
                return CHARSET_BIG5
            }
            return CHARSET_GB18030
        }

        // If the file consists predominantly of Latin characters with standard accents (Portuguese, Spanish, French, German, Italian, etc.),
        // evaluate Western European, Turkish, Central European, Vietnamese without false positive non-Latin alphabet triggers.
        if (asciiLetters >= nonAscii || maxConsecutiveNonAscii <= 2) {
            var turkishScore = 0
            var ceScore = 0
            var vietnameseScore = 0
            for (i in sampleOffset until end) {
                val b = bytes[i].toInt() and 0xFF
                if (b == 0xCC || b == 0xD2 || b == 0xF2 || b == 0xF5) vietnameseScore++
                if (b == 0xF0 || b == 0xFE || b == 0xFD || b == 0xD0 || b == 0xDE || b == 0xDD) turkishScore++
                // Only unambiguous Central European distinct characters (to avoid overlap with Italian/French accented letters)
                if (b == 0xB9 || b == 0xB3 || b == 0x9C || b == 0x9F || b == 0x9A || b == 0x9E || b == 0x8C || b == 0x8F || b == 0x8A || b == 0x8E || b == 0x8D || b == 0x9D || b == 0xCF || b == 0xEF || b == 0xBE) ceScore++
            }
            if (turkishScore >= 2 && turkishScore > ceScore) return CHARSET_WIN1254
            if (ceScore >= 2 && ceScore >= vietnameseScore) return CHARSET_WIN1250
            if (vietnameseScore >= 2) return CHARSET_WIN1258
            return CHARSET_WIN1252
        }

        // Statistical evaluation for non-Latin single-byte alphabets (Hebrew, Arabic, Thai, Cyrillic, Greek)
        var thaiConsonants = 0
        var hebrewLetters = 0
        var arabicAlCount = 0
        var russianVowels = 0
        var koi8Vowels = 0
        var greekVowels = 0
        var bytes0xC0to0xDF = 0

        for (i in sampleOffset until end) {
            val b = bytes[i].toInt() and 0xFF
            if (b in 0xA1..0xBF) thaiConsonants++
            if (b in 0xE0..0xFA) hebrewLetters++
            if (b in 0xC0..0xDF) bytes0xC0to0xDF++
            if (b == 0xEE || b == 0xE0 || b == 0xE5 || b == 0xE8 || b == 0xFF || b == 0xFB || b == 0xF3 || b == 0xFD || b == 0xFE) russianVowels++
            if (b == 0xCF || b == 0xC1 || b == 0xC5 || b == 0xC9 || b == 0xD5 || b == 0xDF) koi8Vowels++
            if (b == 0xE1 || b == 0xEF || b == 0xE5 || b == 0xE7 || b == 0xFD || b == 0xFE || b == 0xE9 || b == 0xF5 || b == 0xF9 || b == 0xDC || b == 0xDD || b == 0xDE || b == 0xDF || b == 0xFA || b == 0xFB || b == 0xFC) greekVowels++
            if (i < end - 1 && b == 0xC7 && (bytes[i + 1].toInt() and 0xFF) == 0xE1) arabicAlCount++
        }

        // Hebrew check: consonants (0xE0..0xFA) make up all non-ASCII bytes in non-Latin script
        if (hebrewLetters == nonAscii && hebrewLetters >= 4 && bytes0xC0to0xDF == 0) {
            return CHARSET_WIN1255
        }

        // Thai check (0xA1..0xBF consonants)
        if (thaiConsonants * 4 >= nonAscii && thaiConsonants >= 4) {
            return CHARSET_WIN874
        }

        // Arabic check
        if (arabicAlCount > 0) {
            return CHARSET_WIN1256
        }

        // Cyrillic check
        if (koi8Vowels > russianVowels && koi8Vowels >= 3) {
            return CHARSET_KOI8_R
        }
        if (russianVowels > greekVowels && russianVowels >= 4) {
            return CHARSET_WIN1251
        }

        // Greek check
        if (greekVowels > russianVowels && greekVowels >= 4) {
            return CHARSET_WIN1253
        }

        // Turkish / Central European / Vietnamese / Western European fallback
        var turkishScore = 0
        var ceScore = 0
        var vietnameseScore = 0
        for (i in sampleOffset until end) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0xCC || b == 0xD2 || b == 0xF2 || b == 0xF5) vietnameseScore++
            if (b == 0xF0 || b == 0xFE || b == 0xFD || b == 0xD0 || b == 0xDE || b == 0xDD) turkishScore++
            if (b == 0xB9 || b == 0xB3 || b == 0x9C || b == 0x9F || b == 0x9A || b == 0x9E || b == 0x8C || b == 0x8F || b == 0x8A || b == 0x8E || b == 0x8D || b == 0x9D || b == 0xCF || b == 0xEF || b == 0xBE) ceScore++
        }
        if (turkishScore > ceScore && turkishScore > 0) return CHARSET_WIN1254
        if (ceScore > 0 && ceScore >= vietnameseScore) return CHARSET_WIN1250
        if (vietnameseScore >= 2) return CHARSET_WIN1258

        return CHARSET_WIN1252
    }

    private fun isCjkClean(bytes: ByteArray, offset: Int, length: Int, cs: Charset): Boolean {
        return try {
            val decoder: CharsetDecoder = cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes, offset, length))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun countBlockMatches(bytes: ByteArray, offset: Int, length: Int, cs: Charset, vararg blocks: Character.UnicodeBlock): Int {
        return try {
            val decoder: CharsetDecoder = cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val cb: CharBuffer = decoder.decode(ByteBuffer.wrap(bytes, offset, length))
            var matched = 0
            for (i in 0 until cb.length) {
                val c = cb.get(i)
                val b = Character.UnicodeBlock.of(c)
                for (target in blocks) {
                    if (b == target) {
                        matched++
                        break
                    }
                }
            }
            matched
        } catch (_: Exception) {
            0
        }
    }
}
