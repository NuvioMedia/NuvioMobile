package com.nuvio.app.features.livetv.data

import com.nuvio.app.features.livetv.model.EpgAssignment
import com.nuvio.app.features.livetv.model.EpgProgram

/** What's on now and next on one channel. */
data class NowNext(val now: EpgProgram?, val next: EpgProgram?)

/**
 * The TV guide on the device, kept the TV app's way (its EpgDatabase): every listing is stored
 * after a guide update and only the hours needed are read back, so the guide opens straight away
 * however big it is.
 *
 * Tables (same columns and indices as the TV app's "livetv_guide.db"):
 *  - programs(ch, start, stop, title, descr, cat, ep, icon, year, people) with programs_ch_start,
 *    programs_stop and programs_start
 *  - auto(ch PRIMARY KEY, source, xmltv): the guide channel each playlist channel was matched to
 *  - meta(k PRIMARY KEY, v): the fingerprint of the guide stored
 */
internal expect object EpgStore {
    /** Replaces the whole guide (after a guide update), in one transaction. */
    fun replaceAll(fingerprint: String, programs: Map<String, List<EpgProgram>>, auto: Map<String, EpgAssignment>)

    /** Replaces just these channels' listings and matches (Assign EPG). */
    fun replaceChannels(fingerprint: String, channels: Set<String>, programs: Map<String, List<EpgProgram>>, auto: Map<String, EpgAssignment>)

    /** What the stored guide was built from (null: nothing stored). */
    fun fingerprint(): String?

    /**
     * Listings on at any point in [fromMs, toMs): [light] leaves out descriptions and cast (several
     * times quicker to read); [channels] limits the read to those channels.
     */
    fun range(fromMs: Long, toMs: Long, light: Boolean = false, channels: Collection<String>? = null): Map<String, List<EpgProgram>>

    /** One channel's listings across [fromMs, toMs). */
    fun channelRange(ch: String, fromMs: Long, toMs: Long): List<EpgProgram>

    /** One show's full details (description, cast…). */
    fun program(ch: String, startMs: Long): EpgProgram?

    /**
     * Shows starting in [fromMs, toMs) and ending after [minStopMs] whose title (or, with
     * [inDescription], description) contains any of [words] (case-insensitive, 3+ letters). Up to
     * [limit] rows, with title, description and category only. For the Sports hub's channel search.
     */
    fun findByWords(fromMs: Long, toMs: Long, minStopMs: Long, words: Collection<String>, inDescription: Boolean = true, limit: Int = 4_000): List<Pair<String, EpgProgram>>

    /** What's on now and next for each of [channels] at [nowMs]. */
    fun nowNext(channels: Collection<String>, nowMs: Long): Map<String, NowNext>

    /** The guide channel each playlist channel was matched to. */
    fun autoMatches(): Map<String, EpgAssignment>

    /** Drops these channels' listings and matches. */
    fun deleteChannels(channels: Collection<String>)

    /** Drops listings that ended before [beforeMs]. Returns how many went. */
    fun purgeOlderThan(beforeMs: Long): Int

    /** Gives space left empty by deletions back to the system when there's a lot of it. */
    fun compact()

    /** Empties the guide. */
    fun clear()

    /** How many listings are stored. */
    fun count(): Long
}
