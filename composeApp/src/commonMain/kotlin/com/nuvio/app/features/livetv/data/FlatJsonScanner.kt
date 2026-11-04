package com.nuvio.app.features.livetv.data

/**
 * Reads a panel's list (a JSON array of objects, or an object whose values are objects) a piece
 * at a time and hands over each object's simple fields as strings, the same ones [XtreamClient]
 * used to get from parsing the whole text. Nested values are skipped; nulls are left out.
 *
 * Big catalogs (100,000+ titles) used to be held whole in memory three times over (the bytes, the
 * text and the parsed tree) and ran phones out of memory. This keeps only the object being read.
 *
 * Feed it bytes with [feed] as they arrive, then call [finish].
 */
internal class FlatJsonScanner(private val onObject: (Map<String, String>) -> Unit) {
    private var depth = 0
    private var started = false
    private var inString = false
    private var escape = false
    private var unicodeLeft = 0
    private var unicodeValue = 0
    private val text = StringBuilder()
    private var inLiteral = false
    private val literal = StringBuilder()

    private var fields: HashMap<String, String>? = null
    private var expectKey = false
    private var key: String? = null

    /** UTF-8 bytes from the end of the last chunk that didn't make a whole character yet. */
    private var carry = ByteArray(0)

    /** Objects handed over so far. */
    var count = 0
        private set

    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        val all = if (carry.isEmpty()) bytes else carry + bytes.copyOf(length)
        val total = if (carry.isEmpty()) length else all.size
        val end = completeUtf8End(all, total)
        carry = if (end < total) all.copyOfRange(end, total) else ByteArray(0)
        if (end > 0) feedText(all.decodeToString(0, end))
    }

    fun feedText(chunk: String) {
        for (c in chunk) step(c)
    }

    /** Throws if the list was cut off part-way (what the old whole-text parse also did). */
    fun finish() {
        if (carry.isNotEmpty()) feedText(carry.decodeToString())
        carry = ByteArray(0)
        if (inLiteral) endLiteral()
        if (started && depth != 0) throw IllegalStateException("The provider's list ended part-way (after $count items)")
    }

    private fun step(c: Char) {
        if (inString) {
            stringChar(c)
            return
        }
        if (inLiteral) {
            if (c == ',' || c == '}' || c == ']' || c.isWhitespace()) endLiteral() else {
                literal.append(c)
                return
            }
        }
        when (c) {
            '"' -> {
                inString = true
                text.setLength(0)
            }
            '{', '[' -> {
                if (depth == 0) started = true
                if (depth == 1 && c == '{') {
                    fields = HashMap()
                    expectKey = true
                    key = null
                } else if (depth == 2) {
                    key = null // a nested value: skipped
                }
                depth++
            }
            '}', ']' -> {
                depth--
                if (depth == 1 && c == '}') {
                    fields?.let { count++; onObject(it) }
                    fields = null
                }
                if (depth < 0) depth = 0
            }
            ':' -> if (depth == 2) expectKey = false
            ',' -> if (depth == 2) {
                expectKey = true
                key = null
            }
            else -> if (!c.isWhitespace() && depth == 2 && !expectKey) {
                inLiteral = true
                literal.setLength(0)
                literal.append(c)
            }
        }
    }

    private fun stringChar(c: Char) {
        if (unicodeLeft > 0) {
            val d = c.digitToIntOrNull(16) ?: 0
            unicodeValue = unicodeValue * 16 + d
            if (--unicodeLeft == 0) appendChar(unicodeValue.toChar())
            return
        }
        if (escape) {
            escape = false
            when (c) {
                'n' -> appendChar('\n')
                't' -> appendChar('\t')
                'r' -> appendChar('\r')
                'b' -> appendChar('\b')
                'f' -> appendChar('\u000C')
                'u' -> {
                    unicodeLeft = 4
                    unicodeValue = 0
                }
                else -> appendChar(c) // \" \\ \/ and anything a lenient panel sends
            }
            return
        }
        when (c) {
            '\\' -> escape = true
            '"' -> {
                inString = false
                endString()
            }
            else -> appendChar(c)
        }
    }

    private fun appendChar(c: Char) {
        if (depth == 2) text.append(c) // only strings inside an item matter
    }

    private fun endString() {
        if (depth != 2) return
        val value = text.toString()
        if (expectKey) key = value else key?.let { fields?.put(it, value) }
    }

    private fun endLiteral() {
        inLiteral = false
        if (depth != 2 || expectKey) return
        val value = literal.toString()
        if (value != "null") key?.let { fields?.put(it, value) }
    }

    private companion object {
        /** How many leading bytes of [b] (of [length]) end on a whole UTF-8 character. */
        fun completeUtf8End(b: ByteArray, length: Int): Int {
            var i = length - 1
            var back = 0
            while (i >= 0 && back < 4) {
                val x = b[i].toInt() and 0xFF
                if (x and 0xC0 != 0x80) {
                    val need = when {
                        x < 0x80 -> 1
                        x >= 0xF0 -> 4
                        x >= 0xE0 -> 3
                        x >= 0xC0 -> 2
                        else -> 1
                    }
                    return if (back + 1 >= need) length else i
                }
                i--
                back++
            }
            return length
        }
    }
}
