package com.nuvio.app.features.player

/**
 * Matroska layout and Chapters parsing over bytes already read; no I/O. ExoPlayer's Matroska
 * extractor skips chapters, so the Android ExoPlayer path reads them with this (mpv lists them).
 */
internal object MatroskaChapterParser {
    const val ID_CHAPTERS = 0x1043A770L
    const val ID_SEGMENT = 0x18538067L
    const val ID_SEEK_HEAD = 0x114D9B74L
    const val ID_SEEK = 0x4DBBL
    const val ID_SEEK_ID = 0x53ABL
    const val ID_SEEK_POSITION = 0x53ACL
    const val ID_CLUSTER = 0x1F43B675L
    private const val ID_EDITION_ENTRY = 0x45B9L
    private const val ID_EDITION_FLAG_HIDDEN = 0x45BDL
    private const val ID_EDITION_FLAG_DEFAULT = 0x45DBL
    private const val ID_CHAPTER_ATOM = 0xB6L
    private const val ID_CHAPTER_TIME_START = 0x91L
    private const val ID_CHAPTER_FLAG_HIDDEN = 0x98L
    private const val ID_CHAPTER_FLAG_ENABLED = 0x4598L
    private const val ID_CHAPTER_DISPLAY = 0x80L
    private const val ID_CHAP_STRING = 0x85L

    /** An EBML element header at [offset]; [dataSize] is -1 for an unknown size. */
    data class Element(
        val id: Long,
        val offset: Long,
        val headerSize: Int,
        val dataSize: Long,
    ) {
        val unknownSize: Boolean get() = dataSize < 0L
        val dataOffset: Long get() = offset + headerSize
        val end: Long? get() = if (unknownSize) null else dataOffset + dataSize
    }

    data class TopLevelLayout(
        /** Absolute offset where the Segment payload starts; SeekHead positions are relative to it. */
        val segmentDataStart: Long,
        val seekHeadPosition: Long?,
        val chaptersPosition: Long?,
    )

    /** Where the SeekHead and Chapters start, from the head of the file. Null when not Matroska. */
    fun topLevelLayout(head: ByteArray): TopLevelLayout? {
        var position = 0L
        var segment: Element? = null
        for (attempt in 0 until 8) {
            val element = readElement(head, position) ?: return null
            if (element.id == ID_SEGMENT) {
                segment = element
                break
            }
            position = element.end ?: return null
        }
        val segmentDataStart = segment?.dataOffset ?: return null
        var seekHead: Long? = null
        var chapters: Long? = null
        position = segmentDataStart
        while (position < head.size) {
            val element = readElement(head, position) ?: break
            when (element.id) {
                ID_SEEK_HEAD -> if (seekHead == null) seekHead = position
                ID_CHAPTERS -> chapters = position
                ID_CLUSTER -> break
            }
            if (chapters != null) break
            position = element.end ?: break
        }
        return TopLevelLayout(segmentDataStart, seekHead, chapters)
    }

    /** Element id to its position relative to the Segment payload, from a whole SeekHead element. */
    fun seekHeadPositions(seekHead: ByteArray): Map<Long, Long> {
        val root = readElement(seekHead, 0L) ?: return emptyMap()
        if (root.id != ID_SEEK_HEAD) return emptyMap()
        val result = mutableMapOf<Long, Long>()
        children(seekHead, root).filter { it.id == ID_SEEK }.forEach { seek ->
            var id: Long? = null
            var position: Long? = null
            children(seekHead, seek).forEach { child ->
                when (child.id) {
                    ID_SEEK_ID -> id = readUnsigned(seekHead, child)
                    ID_SEEK_POSITION -> position = readUnsigned(seekHead, child)
                }
            }
            val targetId = id
            val targetPosition = position
            if (targetId != null && targetPosition != null && targetId !in result) {
                result[targetId] = targetPosition
            }
        }
        return result
    }

    /**
     * The chapters of the default edition (else the first one shown), from a whole Chapters element.
     * Hidden and disabled chapters are left out; nested chapters are not listed.
     */
    fun parseChapters(chapters: ByteArray): List<PlayerChapter> {
        val root = readElement(chapters, 0L) ?: return emptyList()
        if (root.id != ID_CHAPTERS) return emptyList()
        val editions = children(chapters, root).filter { it.id == ID_EDITION_ENTRY }
        val shown = editions.filterNot { flag(chapters, it, ID_EDITION_FLAG_HIDDEN, default = false) }
        val edition = shown.firstOrNull { flag(chapters, it, ID_EDITION_FLAG_DEFAULT, default = false) }
            ?: shown.firstOrNull()
            ?: return emptyList()
        return children(chapters, edition)
            .filter { it.id == ID_CHAPTER_ATOM }
            .filterNot { flag(chapters, it, ID_CHAPTER_FLAG_HIDDEN, default = false) }
            .filter { flag(chapters, it, ID_CHAPTER_FLAG_ENABLED, default = true) }
            .mapNotNull { atom ->
                val atomChildren = children(chapters, atom)
                val startNs = atomChildren.firstOrNull { it.id == ID_CHAPTER_TIME_START }
                    ?.let { readUnsigned(chapters, it) }
                    ?: return@mapNotNull null
                val title = atomChildren.firstOrNull { it.id == ID_CHAPTER_DISPLAY }
                    ?.let { display -> children(chapters, display).firstOrNull { it.id == ID_CHAP_STRING } }
                    ?.let { readUtf8(chapters, it) }
                    ?.takeIf { it.isNotBlank() }
                PlayerChapter(startMs = startNs / 1_000_000L, title = title)
            }
    }

    /** The element header at [offset] of [bytes], or null when it does not fit or is malformed. */
    fun readElement(bytes: ByteArray, offset: Long): Element? {
        if (offset < 0L || offset >= bytes.size) return null
        val start = offset.toInt()
        val idLength = vintLength(bytes[start].toInt() and 0xFF) ?: return null
        if (idLength > 4 || start + idLength >= bytes.size) return null
        var id = 0L
        for (i in 0 until idLength) id = (id shl 8) or (bytes[start + i].toLong() and 0xFFL)
        val sizeStart = start + idLength
        val sizeLength = vintLength(bytes[sizeStart].toInt() and 0xFF) ?: return null
        if (sizeStart + sizeLength > bytes.size) return null
        var size = (bytes[sizeStart].toInt() and (0xFF ushr sizeLength)).toLong()
        for (i in 1 until sizeLength) size = (size shl 8) or (bytes[sizeStart + i].toLong() and 0xFFL)
        val unknown = size == (1L shl (7 * sizeLength)) - 1L
        return Element(id = id, offset = offset, headerSize = idLength + sizeLength, dataSize = if (unknown) -1L else size)
    }

    private fun vintLength(firstByte: Int): Int? {
        if (firstByte == 0) return null
        var length = 1
        var mask = 0x80
        while (firstByte and mask == 0) {
            mask = mask ushr 1
            length++
        }
        return length
    }

    private fun flag(bytes: ByteArray, parent: Element, id: Long, default: Boolean): Boolean =
        children(bytes, parent).firstOrNull { it.id == id }
            ?.let { readUnsigned(bytes, it) }
            ?.let { it != 0L }
            ?: default

    private fun children(bytes: ByteArray, parent: Element): List<Element> {
        val end = parent.end?.coerceAtMost(bytes.size.toLong()) ?: return emptyList()
        val result = mutableListOf<Element>()
        var position = parent.dataOffset
        while (position < end) {
            val child = readElement(bytes, position) ?: break
            val childEnd = child.end ?: break
            if (childEnd > end || childEnd <= position) break
            result += child
            position = childEnd
        }
        return result
    }

    private fun readUnsigned(bytes: ByteArray, element: Element): Long? {
        if (element.dataSize !in 1L..8L) return null
        var value = 0L
        for (i in 0 until element.dataSize.toInt()) {
            value = (value shl 8) or (bytes[(element.dataOffset + i).toInt()].toLong() and 0xFFL)
        }
        return value
    }

    private fun readUtf8(bytes: ByteArray, element: Element): String? {
        val end = element.end ?: return null
        if (end > bytes.size) return null
        return bytes.decodeToString(element.dataOffset.toInt(), end.toInt()).trimEnd('\u0000').trim()
    }
}
