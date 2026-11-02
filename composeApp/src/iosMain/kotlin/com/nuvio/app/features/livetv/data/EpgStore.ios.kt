package com.nuvio.app.features.livetv.data

import com.nuvio.app.features.livetv.model.EpgAssignment
import com.nuvio.app.features.livetv.model.EpgProgram

/** In-memory stand-in until Live TV is built for iOS. */
internal actual object EpgStore {
    private var programs: Map<String, List<EpgProgram>> = emptyMap()
    private var auto: Map<String, EpgAssignment> = emptyMap()
    private var fp: String? = null

    actual fun replaceAll(fingerprint: String, programs: Map<String, List<EpgProgram>>, auto: Map<String, EpgAssignment>) {
        this.programs = programs
        this.auto = auto
        fp = fingerprint
    }

    actual fun replaceChannels(fingerprint: String, channels: Set<String>, programs: Map<String, List<EpgProgram>>, auto: Map<String, EpgAssignment>) {
        this.programs = this.programs.filterKeys { it !in channels } + programs.filterKeys { it in channels }
        this.auto = this.auto.filterKeys { it !in channels } + auto.filterKeys { it in channels }
        fp = fingerprint
    }

    actual fun fingerprint(): String? = fp

    actual fun range(fromMs: Long, toMs: Long, light: Boolean, channels: Collection<String>?): Map<String, List<EpgProgram>> {
        val keys = channels?.toSet()
        return programs.filterKeys { keys == null || it in keys }
            .mapValues { (_, l) -> l.filter { it.stopMs > fromMs && it.startMs < toMs } }
            .filterValues { it.isNotEmpty() }
    }

    actual fun channelRange(ch: String, fromMs: Long, toMs: Long): List<EpgProgram> = range(fromMs, toMs, false, listOf(ch))[ch].orEmpty()

    actual fun program(ch: String, startMs: Long): EpgProgram? = programs[ch]?.firstOrNull { it.startMs == startMs }

    actual fun findByWords(fromMs: Long, toMs: Long, minStopMs: Long, words: Collection<String>, inDescription: Boolean, limit: Int): List<Pair<String, EpgProgram>> {
        val clean = words.map { it.trim().lowercase() }.filter { it.length >= 3 }.distinct()
        if (clean.isEmpty()) return emptyList()
        val out = ArrayList<Pair<String, EpgProgram>>()
        programs.forEach { (ch, list) ->
            list.forEach { p ->
                if (out.size >= limit || p.startMs < fromMs || p.startMs >= toMs || p.stopMs <= minStopMs) return@forEach
                val text = (p.title + if (inDescription) " " + p.description.orEmpty() else "").lowercase()
                if (clean.any { it in text }) out += ch to p
            }
        }
        return out
    }

    actual fun nowNext(channels: Collection<String>, nowMs: Long): Map<String, NowNext> {
        val out = HashMap<String, NowNext>()
        channels.forEach { ch ->
            val list = programs[ch] ?: return@forEach
            val now = list.firstOrNull { nowMs >= it.startMs && nowMs < it.stopMs }
            val next = list.firstOrNull { it.startMs >= (now?.stopMs ?: nowMs) }
            out[ch] = NowNext(now, next)
        }
        return out
    }

    actual fun autoMatches(): Map<String, EpgAssignment> = auto

    actual fun deleteChannels(channels: Collection<String>) {
        val set = channels.toSet()
        programs = programs.filterKeys { it !in set }
        auto = auto.filterKeys { it !in set }
    }

    actual fun purgeOlderThan(beforeMs: Long): Int {
        var n = 0
        programs = programs.mapValues { (_, l) -> l.filter { keep -> (keep.stopMs >= beforeMs).also { if (!it) n++ } } }
        return n
    }

    actual fun compact() {}

    actual fun clear() {
        programs = emptyMap(); auto = emptyMap(); fp = null
    }

    actual fun count(): Long = programs.values.sumOf { it.size }.toLong()
}
