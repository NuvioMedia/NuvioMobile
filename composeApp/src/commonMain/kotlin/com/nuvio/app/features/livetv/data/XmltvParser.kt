package com.nuvio.app.features.livetv.data

import com.nuvio.app.features.livetv.model.EpgProgram

data class XmltvChannel(
    val id: String,
    val displayNames: List<String>,
    val icon: String?
)

data class XmltvResult(
    val channels: Map<String, XmltvChannel>,
    /** Programs keyed by XMLTV channel id, sorted by start time. */
    val programs: Map<String, List<EpgProgram>>,
    /** Reading stopped early here (a broken file): what was read before it is kept. */
    val error: String? = null,
    /**
     * Every channel id the guide's shows mention, kept or not (just the ids). For guides with no
     * channel list, so Change EPG can still offer all of them.
     */
    val programChannelIds: Set<String> = emptySet()
)

/**
 * Streaming XMLTV parser. Large provider guides are often 50–200 MB, so only
 * programs inside [windowStartMs, windowEndMs] and (once the channel list has
 * been read) only channels that match the playlist are kept in memory.
 *
 * Built on [XmlScanner], a small forgiving tag scanner (there is no XML parser in common
 * code): a stray "&" in a title, an HTML entity such as &nbsp; or an unclosed tag doesn't
 * stop the whole guide from being read, like the TV app's relaxed pull parser.
 */
object XmltvParser {

    fun parse(
        text: String,
        windowStartMs: Long,
        windowEndMs: Long,
        keepUnlisted: ((String) -> Boolean)? = null,
        onPrograms: ((String, List<EpgProgram>) -> Unit)? = null,
        selectChannels: (Map<String, XmltvChannel>) -> Set<String>?
    ): XmltvResult = parse(sequenceOf(text), windowStartMs, windowEndMs, keepUnlisted, onPrograms, selectChannels)

    fun parse(
        /** The guide's text in pieces (already unzipped), read one after the other. */
        chunks: Sequence<String>,
        windowStartMs: Long,
        windowEndMs: Long,
        /**
         * For a guide with no channel list before its shows (so [selectChannels] can't pick
         * any and returns null): whether to keep a channel's shows, asked once per channel id.
         * Null keeps them all.
         */
        keepUnlisted: ((String) -> Boolean)? = null,
        /**
         * Hands over each channel's shows as soon as that channel's run of shows ends (guides list
         * a channel's shows together), instead of keeping the whole guide in memory. A channel whose
         * shows are split up arrives in more than one part. With it, the result has no programs.
         */
        onPrograms: ((String, List<EpgProgram>) -> Unit)? = null,
        selectChannels: (Map<String, XmltvChannel>) -> Set<String>?
    ): XmltvResult {
        val parser = XmlScanner(chunks.iterator())

        val channels = LinkedHashMap<String, XmltvChannel>()
        val programs = HashMap<String, MutableList<EpgProgram>>()
        var wanted: Set<String>? = null
        var wantedResolved = false
        val seenIds = HashSet<String>()
        val unlistedAnswers = HashMap<String, Boolean>()
        var bufId: String? = null
        var buf = ArrayList<EpgProgram>()
        fun flush() {
            val id = bufId ?: return
            if (buf.isNotEmpty()) onPrograms?.invoke(id, buf.sortedBy { it.startMs })
            buf = ArrayList()
            bufId = null
        }

        var error: String? = null
        try {
            var event = parser.eventType
            while (event != XmlScanner.END_DOCUMENT) {
                if (event == XmlScanner.START_TAG) {
                    when (parser.name) {
                        "channel" -> readChannel(parser)?.let { channels[it.id] = it }
                        "programme" -> { // XMLTV's element name: keep this spelling
                            if (!wantedResolved) {
                                wanted = selectChannels(channels)
                                wantedResolved = true
                            }
                            val channelId = parser.getAttributeValue("channel")
                            if (channelId != null) seenIds += channelId
                            val w = wanted
                            val keep = channelId != null && when {
                                w != null -> channelId in w
                                keepUnlisted != null -> unlistedAnswers.getOrPut(channelId) { keepUnlisted(channelId) }
                                else -> true
                            }
                            if (keep) {
                                val program = readProgramme(parser, windowStartMs, windowEndMs)
                                if (program != null) {
                                    if (onPrograms != null) {
                                        if (bufId != channelId) { flush(); bufId = channelId }
                                        buf.add(program)
                                    } else {
                                        programs.getOrPut(channelId!!) { ArrayList() }.add(program)
                                    }
                                }
                            } else {
                                skipProgramme(parser)
                            }
                            // It ended at the next <programme> (it was never closed): read that one
                            // now rather than stepping past it.
                            if (parser.eventType == XmlScanner.START_TAG && parser.name == "programme") {
                                event = parser.eventType
                                continue
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            // A guide broken partway (bad characters, cut off): keep everything read up to there
            // instead of throwing the whole guide away.
            error = "line ${parser.lineNumber}: " + (e.message?.take(140) ?: e::class.simpleName)
            if (!wantedResolved) wanted = null
        }
        // The last channel's shows (also when the guide broke partway: what was read is kept).
        runCatching { flush() }
        val sorted = programs.mapValues { (_, list) -> list.sortedBy { it.startMs } }
        return XmltvResult(channels, sorted, error, seenIds)
    }

    private fun readChannel(parser: XmlScanner): XmltvChannel? {
        val id = parser.getAttributeValue("id") ?: run { skip(parser); return null }
        val names = mutableListOf<String>()
        var icon: String? = null
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlScanner.END_TAG && parser.depth == depth)) {
            if (event == XmlScanner.START_TAG) {
                when (parser.name) {
                    "display-name" -> readText(parser).takeIf { it.isNotBlank() }?.let { names.add(it.trim()) }
                    "icon" -> {
                        icon = parser.getAttributeValue("src")
                        skip(parser)
                    }
                    else -> skip(parser)
                }
            }
            if (event == XmlScanner.END_DOCUMENT) break
            event = parser.next()
        }
        return XmltvChannel(id, names, icon)
    }

    private fun readProgramme(parser: XmlScanner, windowStart: Long, windowEnd: Long): EpgProgram? {
        val start = parseTime(parser.getAttributeValue("start"))
        val stop = parseTime(parser.getAttributeValue("stop"))
        if (start == null || stop == null || stop <= windowStart || start >= windowEnd) {
            skipProgramme(parser)
            return null
        }
        var title: String? = null
        var desc: String? = null
        var category: String? = null
        var episode: String? = null
        var subTitle: String? = null
        var icon: String? = null
        var date: String? = null
        val people = ArrayList<String>()
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlScanner.END_TAG && parser.depth == depth)) {
            // Another show starting inside this one: this one was never closed (some guides
            // have hundreds of these). It ends here; the parser stays on the new one.
            if (event == XmlScanner.START_TAG && parser.name == "programme") break
            if (event == XmlScanner.START_TAG) {
                when (parser.name) {
                    "title" -> { val t = readText(parser); if (title == null) title = t }
                    "sub-title" -> { val t = readText(parser); if (subTitle == null) subTitle = t }
                    "desc" -> { val t = readText(parser); if (desc == null) desc = t }
                    "category" -> { val t = readText(parser); if (category == null) category = t }
                    "episode-num" -> {
                        val system = parser.getAttributeValue("system")
                        val t = readText(parser)
                        if (system == "onscreen" || episode == null) episode = formatEpisode(system, t)
                    }
                    "icon" -> { icon = parser.getAttributeValue("src"); skip(parser) }
                    "date" -> { val t = readText(parser); if (date == null) date = t }
                    "credits" -> readCredits(parser, people)
                    else -> skip(parser)
                }
                // A field that was never closed ended at the next show: this show ends there too.
                if (parser.eventType == XmlScanner.START_TAG && parser.name == "programme") break
            }
            if (event == XmlScanner.END_DOCUMENT) break
            event = parser.next()
        }
        // Only a bare year ("1990") is a release year. Many guides put the broadcast or episode
        // air date here instead ("20260929"), which would make every poster look "wrong year".
        val year = date?.trim()?.takeIf { it.length == 4 }?.toIntOrNull()?.takeIf { it in 1900..2100 }
            ?: yearIn(title) ?: yearIn(desc)
        val fullDesc = listOfNotNull(subTitle?.takeIf { it.isNotBlank() }, desc?.takeIf { it.isNotBlank() })
            .joinToString("\n").ifBlank { null }
        return EpgProgram(
            startMs = start,
            stopMs = stop,
            title = title?.trim().orEmpty().ifBlank { "No information" },
            // Very long descriptions are cut: a big guide holds several hundred thousand listings.
            description = fullDesc?.let { if (it.length > 800) it.take(800).trimEnd() + "…" else it },
            category = category?.trim(),
            episode = episode,
            icon = icon,
            year = year,
            people = people.take(12)
        )
    }

    private val yearInParens = Regex("""\((19\d{2}|20\d{2})\)""")

    /** "(1990)" in a title or description. Bare numbers are ignored: too easy to misread. */
    private fun yearIn(text: String?): Int? =
        text?.let { yearInParens.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private fun readCredits(parser: XmlScanner, out: MutableList<String>) {
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlScanner.END_TAG && parser.depth == depth)) {
            if (event == XmlScanner.START_TAG) {
                when (parser.name) {
                    "actor", "director" -> readText(parser).trim().takeIf { it.isNotEmpty() }?.let { out += it }
                    else -> skip(parser)
                }
            }
            if (event == XmlScanner.END_DOCUMENT) break
            event = parser.next()
        }
    }

    private fun formatEpisode(system: String?, raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (system == "xmltv_ns") {
            val parts = text.split('.')
            val season = parts.getOrNull(0)?.substringBefore('/')?.trim()?.toIntOrNull()
            val ep = parts.getOrNull(1)?.substringBefore('/')?.trim()?.toIntOrNull()
            return when {
                // A year used as the season (news: "S2026 E609") isn't a real episode number.
                season != null && season >= 100 -> null
                season != null && ep != null -> "S${season + 1} E${ep + 1}"
                ep != null -> "E${ep + 1}"
                else -> null
            }
        }
        // Other numbering systems (dd_progid, thetvdb, imdb, crid…) are database ids, not
        // something to show: only the on-screen form (or one without a system) is used.
        if (system != null && system != "onscreen") return null
        return cleanEpisode(text)
    }

    private val longDigits = Regex("""\d{5,}""")
    private val seasonNumber = Regex("""(?i)\bS(?:eason)?\s*(\d+)""")

    /**
     * An episode label worth showing ("S2 E5", "Ep. 12", "1x05"), or null for the ids some
     * guides put there instead: long strings of digits ("609178259") and year-based numbers
     * ("S2026E609"), which filled the end of every listing with numbers.
     */
    fun cleanEpisode(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty() || t.length > 24) return null
        val letters = t.count { it.isLetter() }
        val digits = t.count { it.isDigit() }
        if (digits == 0) return t // "Part One", "Finale"
        if (letters == 0 && digits > 4) return null // a plain id
        if (longDigits.containsMatchIn(t)) return null // ids with a prefix ("EP012345678")
        // Season numbers that are years.
        seasonNumber.find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it >= 100) return null }
        return t
    }

    /**
     * The text of the current element. An element that was never closed before the next show
     * started (a "<title>" with no "</title>") ends there, so the show after it isn't lost.
     */
    private fun readText(parser: XmlScanner): String {
        val sb = StringBuilder()
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlScanner.END_TAG && parser.depth == depth)) {
            if (event == XmlScanner.TEXT) sb.append(parser.text)
            if (event == XmlScanner.END_DOCUMENT) break
            if (event == XmlScanner.START_TAG && parser.name == "programme") break
            event = parser.next()
        }
        return sb.toString()
    }

    /** Skips a show, stopping at the next <programme> if this one was never closed. */
    private fun skipProgramme(parser: XmlScanner) {
        if (parser.eventType != XmlScanner.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlScanner.START_TAG -> if (parser.name == "programme") return else depth++
                XmlScanner.END_TAG -> depth--
                XmlScanner.END_DOCUMENT -> return
            }
        }
    }

    private fun skip(parser: XmlScanner) {
        if (parser.eventType != XmlScanner.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlScanner.START_TAG -> depth++
                XmlScanner.END_TAG -> depth--
                XmlScanner.END_DOCUMENT -> return
            }
        }
    }

    /** Days since 1970-01-01 for a calendar date (UTC), without creating a calendar. */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    /** Parses "YYYYMMDDhhmmss +zzzz" (seconds and offset optional). */
    internal fun parseTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val v = value.trim()
        val digits = v.takeWhile { it.isDigit() }
        if (digits.length < 12) return null
        val year = digits.substring(0, 4).toInt()
        val month = digits.substring(4, 6).toInt()
        val day = digits.substring(6, 8).toInt()
        val hour = digits.substring(8, 10).toInt()
        val minute = digits.substring(10, 12).toInt()
        val second = if (digits.length >= 14) digits.substring(12, 14).toInt() else 0
        if (month !in 1..12 || day !in 1..31) return null
        var ms = (daysFromCivil(year, month, day) * 86_400L + hour * 3_600L + minute * 60L + second) * 1_000L
        val offset = v.substring(digits.length).trim()
        if (offset.length >= 5 && (offset[0] == '+' || offset[0] == '-')) {
            val sign = if (offset[0] == '-') -1 else 1
            val oh = offset.substring(1, 3).toIntOrNull() ?: 0
            val om = offset.substring(3, 5).toIntOrNull() ?: 0
            ms -= sign * (oh * 3_600_000L + om * 60_000L)
        }
        return ms
    }
}

/**
 * A small, forgiving pull scanner for XML-ish text, with the shape of XmlPullParser: [next]
 * moves to the next event, and [name], [depth], [text] and [getAttributeValue] describe it.
 *
 * Forgiving means: comments, CDATA, processing instructions and DOCTYPE are skipped; a "<" that
 * doesn't start a tag is text; a bare "&" or an unknown entity is kept as written; an end tag
 * that matches nothing open is ignored, and one that closes an outer element closes everything
 * inside it first. The input is read in pieces, so a guide of hundreds of MB isn't held whole.
 */
internal class XmlScanner(private val chunks: Iterator<String>) {

    companion object {
        const val START_DOCUMENT = 0
        const val END_DOCUMENT = 1
        const val START_TAG = 2
        const val END_TAG = 3
        const val TEXT = 4
        /** Consumed text is dropped from the buffer once it passes this, so memory stays flat. */
        private const val COMPACT_AT = 256 * 1024
    }

    private val buf = StringBuilder()
    private var pos = 0
    private var exhausted = false

    /** The names of the open elements, innermost last. */
    private val stack = ArrayList<String>()

    /** End tags still to be reported (an end tag that closed several elements at once). */
    private val pendingEnds = ArrayDeque<String>()

    var eventType: Int = START_DOCUMENT
        private set
    var name: String = ""
        private set
    var text: String = ""
        private set
    /** Like XmlPullParser: 1 at the root's start and end tag, 0 outside. */
    var depth: Int = 0
        private set
    var lineNumber: Int = 1
        private set

    private var attrs: Map<String, String> = emptyMap()
    private var selfClosing = false

    fun getAttributeValue(attrName: String): String? = attrs[attrName]

    fun next(): Int {
        // Text already read is dropped here, where nothing holds an index into the buffer.
        if (pos > COMPACT_AT) {
            buf.deleteRange(0, pos)
            pos = 0
        }
        // A self-closing tag is a START_TAG followed by its END_TAG, like a pull parser.
        if (eventType == START_TAG && selfClosing) {
            selfClosing = false
            return endTag(stack.removeAt(stack.lastIndex))
        }
        if (eventType == END_TAG) depth--
        pendingEnds.removeFirstOrNull()?.let { return endTag(it) }
        while (true) {
            if (!ensure(1)) return endDocument()
            val c = buf[pos]
            if (c == '<') {
                if (!ensure(2)) { pos++; return endDocument() }
                when (val c1 = buf[pos + 1]) {
                    '/' -> {
                        val close = indexOf('>', pos + 2)
                        val raw = if (close < 0) buf.substring(pos + 2).trim() else buf.substring(pos + 2, close).trim()
                        advance(if (close < 0) buf.length else close + 1)
                        val tagName = raw.takeWhile { !it.isWhitespace() }
                        val i = stack.lastIndexOf(tagName)
                        if (i < 0) continue // closes nothing that's open: ignored
                        // Everything opened inside it was never closed: close those first.
                        while (stack.size - 1 > i) pendingEnds.addLast(stack.removeAt(stack.lastIndex))
                        val own = stack.removeAt(stack.lastIndex)
                        if (pendingEnds.isEmpty()) return endTag(own)
                        pendingEnds.addLast(own)
                        return endTag(pendingEnds.removeFirst())
                    }
                    '!' -> {
                        if (startsWithAt(pos, "<!--")) skipPast("-->", pos + 4)
                        else if (startsWithAt(pos, "<![CDATA[")) {
                            val end = indexOf("]]>", pos + 9)
                            val t = if (end < 0) buf.substring(pos + 9) else buf.substring(pos + 9, end)
                            advance(if (end < 0) buf.length else end + 3)
                            if (t.isNotEmpty()) return textEvent(t)
                        } else skipPast(">", pos + 2)
                        continue
                    }
                    '?' -> { skipPast("?>", pos + 2); continue }
                    else -> if (c1.isLetter() || c1 == '_' || c1 == ':') {
                        val close = indexOf('>', pos + 2)
                        val body = if (close < 0) buf.substring(pos + 1) else buf.substring(pos + 1, close)
                        advance(if (close < 0) buf.length else close + 1)
                        return startTag(body)
                    }
                }
                // "<" followed by anything else: ordinary text.
            }
            // Text up to the next tag start.
            var end = pos
            while (true) {
                val lt = indexOf('<', end)
                if (lt < 0) { end = buf.length; break }
                if (!ensure(lt - pos + 2)) { end = buf.length; break }
                val n = buf[lt + 1]
                if (n == '/' || n == '!' || n == '?' || n.isLetter() || n == '_' || n == ':') { end = lt; break }
                end = lt + 1
            }
            val raw = buf.substring(pos, end)
            advance(end)
            if (raw.isEmpty()) continue
            return textEvent(decodeEntities(raw))
        }
    }

    private fun startTag(body: String): Int {
        var b = body.trimEnd()
        selfClosing = b.endsWith("/")
        if (selfClosing) b = b.dropLast(1)
        var i = 0
        while (i < b.length && !b[i].isWhitespace() && b[i] != '/') i++
        name = b.substring(0, i)
        attrs = if (i < b.length) parseAttributes(b, i) else emptyMap()
        text = ""
        stack.add(name)
        depth++
        eventType = START_TAG
        return START_TAG
    }

    private fun endTag(tagName: String): Int {
        name = tagName
        attrs = emptyMap()
        text = ""
        eventType = END_TAG
        return END_TAG
    }

    private fun textEvent(t: String): Int {
        text = t
        name = ""
        attrs = emptyMap()
        eventType = TEXT
        return TEXT
    }

    private fun endDocument(): Int {
        eventType = END_DOCUMENT
        name = ""
        text = ""
        attrs = emptyMap()
        return END_DOCUMENT
    }

    private fun parseAttributes(b: String, from: Int): Map<String, String> {
        val out = HashMap<String, String>(8)
        var i = from
        val n = b.length
        while (i < n) {
            while (i < n && (b[i].isWhitespace() || b[i] == '/')) i++
            if (i >= n) break
            val nameStart = i
            while (i < n && !b[i].isWhitespace() && b[i] != '=' && b[i] != '/') i++
            val attrName = b.substring(nameStart, i)
            while (i < n && b[i].isWhitespace()) i++
            if (i >= n || b[i] != '=') { if (attrName.isNotEmpty()) out[attrName] = ""; continue }
            i++
            while (i < n && b[i].isWhitespace()) i++
            if (i >= n) { out[attrName] = ""; break }
            val q = b[i]
            val value: String
            if (q == '"' || q == '\'') {
                val end = b.indexOf(q, i + 1)
                value = if (end < 0) b.substring(i + 1) else b.substring(i + 1, end)
                i = if (end < 0) n else end + 1
            } else {
                val start = i
                while (i < n && !b[i].isWhitespace()) i++
                value = b.substring(start, i)
            }
            out[attrName] = decodeEntities(value)
        }
        return out
    }

    // ---- buffer management

    /** Makes sure at least [count] unread chars are buffered, reading more pieces if needed. */
    private fun ensure(count: Int): Boolean {
        while (buf.length - pos < count) if (!fill()) return false
        return true
    }

    private fun fill(): Boolean {
        if (exhausted) return false
        while (chunks.hasNext()) {
            val chunk = chunks.next()
            if (chunk.isEmpty()) continue
            buf.append(chunk)
            return true
        }
        exhausted = true
        return false
    }

    private fun indexOf(ch: Char, from: Int): Int {
        var start = from
        while (true) {
            val i = buf.indexOf(ch, start)
            if (i >= 0) return i
            start = buf.length
            if (!fill()) return -1
        }
    }

    private fun indexOf(s: String, from: Int): Int {
        var start = from
        while (true) {
            val i = buf.indexOf(s, start)
            if (i >= 0) return i
            // The match may straddle the piece boundary: back up by the needle's length.
            start = (buf.length - s.length + 1).coerceAtLeast(from)
            if (!fill()) return -1
        }
    }

    private fun startsWithAt(at: Int, prefix: String): Boolean {
        if (!ensure(at - pos + prefix.length)) return false
        return buf.startsWith(prefix, at)
    }

    private fun skipPast(marker: String, from: Int) {
        val i = indexOf(marker, from)
        advance(if (i < 0) buf.length else i + marker.length)
    }

    private fun advance(to: Int) {
        for (i in pos until to) if (buf[i] == '\n') lineNumber++
        pos = to
    }

    // ---- entities

    private fun decodeEntities(s: String): String {
        if (s.indexOf('&') < 0) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') { out.append(c); i++; continue }
            val semi = s.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 10) { out.append(c); i++; continue }
            val entity = s.substring(i + 1, semi)
            val decoded = when (entity) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "nbsp" -> " "
                else -> when {
                    entity.startsWith("#x") || entity.startsWith("#X") ->
                        entity.substring(2).toIntOrNull(16)?.takeIf { it in 1..0x10FFFF }?.let { codePointToString(it) }
                    entity.startsWith("#") ->
                        entity.substring(1).toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let { codePointToString(it) }
                    else -> null
                }
            }
            if (decoded == null) { out.append(c); i++ } else { out.append(decoded); i = semi + 1 }
        }
        return out.toString()
    }

    private fun codePointToString(cp: Int): String =
        if (cp < 0x10000) cp.toChar().toString()
        else {
            val v = cp - 0x10000
            charArrayOf(((v shr 10) + 0xD800).toChar(), ((v and 0x3FF) + 0xDC00).toChar()).concatToString()
        }
}
