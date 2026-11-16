package com.nuvio.app.features.autosync

import java.nio.charset.Charset
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared detector must decode exactly like the Android one it replaced, and subtitle bytes in
 * legacy encodings with a language hint must decode back to the original text.
 */
class SubtitleCharsetDetectorTest {

    private fun subtitle(vararg lines: String): String = buildString {
        lines.forEachIndexed { index, line ->
            append(index + 1).append('\n')
            append("00:00:0${index}.000 --> 00:00:0${index}.900\n")
            append(line).append("\n\n")
        }
    }

    private fun assertRoundTrip(text: String, charset: String, languageHint: String?) {
        val bytes = text.toByteArray(Charset.forName(charset))
        assertEquals(text, SubtitleCharsetDetector.decode(bytes, languageHint = languageHint), "$charset hint=$languageHint")
    }

    private val polish = subtitle("Zażółć gęślą jaźń, proszę.", "Łódź i Kraków są piękne.", "Śnieg pada już od rana.")
    private val russian = subtitle("Привет, как дела сегодня?", "Это очень хорошая новость.", "Мы поедем завтра утром.")
    private val hebrew = subtitle("שלום, מה שלומך היום?", "זה רעיון טוב מאוד.", "נלך מחר בבוקר לים.")
    private val greek = subtitle("Καλημέρα, τι κάνεις σήμερα;", "Αυτό είναι πολύ καλό.", "Θα φύγουμε αύριο το πρωί.")
    private val french = subtitle("Ça va très bien, merci.", "Où est la gare, s'il vous plaît?", "Il était une fois à Noël.")

    @Test
    fun `utf8 and boms`() {
        assertRoundTrip(polish, "UTF-8", null)
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + polish.toByteArray(Charsets.UTF_8)
        assertEquals(polish, SubtitleCharsetDetector.decode(withBom))
        val utf16le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + russian.toByteArray(Charsets.UTF_16LE)
        assertEquals(russian, SubtitleCharsetDetector.decode(utf16le))
        val utf16be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + russian.toByteArray(Charsets.UTF_16BE)
        assertEquals(russian, SubtitleCharsetDetector.decode(utf16be))
    }

    @Test
    fun `legacy single byte charsets with a language hint`() {
        assertRoundTrip(polish, "windows-1250", "pol")
        assertRoundTrip(russian, "windows-1251", "ru")
        assertRoundTrip(russian, "KOI8-R", "rus")
        assertRoundTrip(hebrew, "windows-1255", "he")
        assertRoundTrip(greek, "windows-1253", "el")
        assertRoundTrip(french, "windows-1252", "fr")
    }

    @Test
    fun `legacy single byte charsets without a hint`() {
        assertRoundTrip(russian, "windows-1251", null)
        assertRoundTrip(hebrew, "windows-1255", null)
        assertRoundTrip(french, "windows-1252", null)
    }

    private val corpus = listOf(polish, russian, hebrew, greek, french,
        subtitle("你好，今天天气很好。", "我们明天早上去公园散步。"),
        subtitle("你好，今天天氣很好。", "我們明天早上去公園散步。"),
        subtitle("こんにちは、元気ですか？", "それはとてもいい考えですね。"),
        subtitle("안녕하세요, 오늘 날씨가 좋네요.", "내일 아침에 공원에 갑시다."),
        subtitle("مرحبا، كيف حالك اليوم؟", "هذه فكرة جيدة جدا."),
        subtitle("Merhaba, bugün nasılsın? Çok güzel.", "Şimdi gidiyoruz, ığdır."),
        subtitle("Xin chào, hôm nay bạn thế nào?", "Đây là một ý tưởng rất hay."),
        subtitle("สวัสดีครับ วันนี้เป็นอย่างไรบ้าง", "นี่เป็นความคิดที่ดีมาก"),
    )
    private val charsets = listOf("UTF-8", "UTF-16LE", "windows-1250", "windows-1251", "windows-1252",
        "windows-1253", "windows-1254", "windows-1255", "windows-1256", "windows-1258", "windows-874",
        "KOI8-R", "GB18030", "Big5", "Shift_JIS", "EUC-KR")
    private val hints = listOf(null, "", "pol", "ru", "uk", "he", "heb", "ar", "el", "tr", "fr", "en", "vi",
        "th", "zh", "zh-TW", "zh-CN", "zh-Hant", "ja", "ko", "srp", "hungarian", "pt-BR", "xx")

    private fun assertSameAsLegacy(bytes: ByteArray, hint: String?, label: String) {
        assertEquals(
            LegacySubtitleCharsetDetector.decode(bytes, languageHint = hint),
            SubtitleCharsetDetector.decode(bytes, languageHint = hint),
            label,
        )
    }

    @Test
    fun `decodes exactly like the Android detector it replaced`() {
        for (text in corpus) {
            for (charset in charsets) {
                val encoded = runCatching { text.toByteArray(Charset.forName(charset)) }.getOrNull() ?: continue
                for (hint in hints) assertSameAsLegacy(encoded, hint, "$charset hint=$hint text=${text.take(40)}")
            }
            val mojibake = String(text.toByteArray(Charset.forName("windows-1255")), Charset.forName("windows-1252"))
            for (hint in hints) assertSameAsLegacy(mojibake.toByteArray(Charsets.UTF_8), hint, "mojibake hint=$hint")
        }
        val random = Random(20261105)
        repeat(400) { round ->
            val bytes = ByteArray(random.nextInt(1, 600)) { random.nextInt(0, 256).toByte() }
            assertSameAsLegacy(bytes, hints[round % hints.size], "random $round")
        }
    }

    @Test
    fun `cjk charsets with a language hint`() {
        val chinese = subtitle("你好，今天天气很好。", "我们明天早上去公园散步。", "这是一个非常好的主意。")
        assertRoundTrip(chinese, "GB18030", "zh-CN")
        val traditional = subtitle("你好，今天天氣很好。", "我們明天早上去公園散步。", "這是一個非常好的主意。")
        assertRoundTrip(traditional, "Big5", "zh-TW")
        val japanese = subtitle("こんにちは、元気ですか？", "それはとてもいい考えですね。", "あした の あさ に いきます。")
        assertRoundTrip(japanese, "Shift_JIS", "ja")
        val korean = subtitle("안녕하세요, 오늘 날씨가 좋네요.", "내일 아침에 공원에 갑시다.", "정말 좋은 생각이에요.")
        assertRoundTrip(korean, "EUC-KR", "ko")
    }

    @Test
    fun `double encoded hebrew is repaired`() {
        // Windows-1255 Hebrew bytes read as windows-1252 and saved as UTF-8.
        val mojibake = String(hebrew.toByteArray(Charset.forName("windows-1255")), Charset.forName("windows-1252"))
        assertEquals(hebrew, SubtitleCharsetDetector.decode(mojibake.toByteArray(Charsets.UTF_8), languageHint = "heb"))
    }
}
