package com.nuvio.app.features.usenet

internal object UsenetEngineUrls {
    private const val MAX_PORTS = 8
    private val ports = LinkedHashSet<Int>()
    private val sessionUrl = Regex("""^http://127\.0\.0\.1:(\d{1,5})/stream/[0-9a-f]{48}/""")
    private val subtitleUrl = Regex("""^http://127\.0\.0\.1:(\d{1,5})/subtitle/[0-9a-f]{48}/""")

    fun remember(port: Int) = synchronized(ports) {
        ports.remove(port)
        ports.add(port)
        while (ports.size > MAX_PORTS) ports.remove(ports.first())
    }

    fun isSession(url: String): Boolean = matches(sessionUrl, url)

    fun isSubtitle(url: String): Boolean = matches(subtitleUrl, url)

    /**
     * The player URL for a session [path] the engine returned, "/stream/<id>/<name>". A disc clip's
     * name keeps its BDMV/STREAM folders, so the player finds BDMV/CLIPINF beside it; any other
     * file is its base name, as before. Each segment is encoded as Uri.encode does.
     */
    fun stream(endpoint: String, id: String, path: String): String {
        val name = path.removePrefix("/stream/$id/")
        return "$endpoint/stream/$id/" + name.split('/').joinToString("/", transform = ::encodeSegment)
    }

    private fun encodeSegment(segment: String): String = buildString {
        for (byte in segment.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xFF
            if (c < 0x80 && (c.toChar().isLetterOrDigit() || c.toChar() in "_-!.~'()*")) {
                append(c.toChar())
            } else {
                append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
            }
        }
    }

    internal fun clear() = synchronized(ports) { ports.clear() }

    private fun matches(pattern: Regex, url: String): Boolean {
        val port = pattern.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return synchronized(ports) { port in ports }
    }
}
