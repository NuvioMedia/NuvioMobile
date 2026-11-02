package com.nuvio.app.features.livetv.sports

import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.data.CivilTime
import com.nuvio.app.features.livetv.data.LiveTvTimeZones
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** A feed couldn't be read ("HTTP 404", "no table"). */
internal class FeedError(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A map shared between coroutines (stands in for ConcurrentHashMap in shared code). */
internal class SyncMap<K, V> {
    private val lock = SynchronizedObject()
    private val map = HashMap<K, V>()

    operator fun get(key: K): V? = synchronized(lock) { map[key] }
    operator fun set(key: K, value: V) { synchronized(lock) { map[key] = value } }
    fun containsKey(key: K): Boolean = synchronized(lock) { map.containsKey(key) }
    fun remove(key: K): V? = synchronized(lock) { map.remove(key) }
    val isEmpty: Boolean get() = synchronized(lock) { map.isEmpty() }
    val size: Int get() = synchronized(lock) { map.size }
    fun snapshot(): Map<K, V> = synchronized(lock) { HashMap(map) }
    fun getOrPut(key: K, make: () -> V): V = synchronized(lock) { map.getOrPut(key, make) }
    fun values(): List<V> = synchronized(lock) { map.values.toList() }
    fun clear() = synchronized(lock) { map.clear() }
}

/** A set shared between coroutines. */
internal class SyncSet<T> {
    private val lock = SynchronizedObject()
    private val set = HashSet<T>()
    fun add(v: T): Boolean = synchronized(lock) { set.add(v) }
    operator fun contains(v: T): Boolean = synchronized(lock) { set.contains(v) }
    fun snapshot(): Set<T> = synchronized(lock) { HashSet(set) }
}

internal fun floorMod(a: Int, n: Int): Int = ((a % n) + n) % n

/** "7:30 PM" or "19:30" (the Live TV clock setting), for text built off the main thread. */
internal fun clockText(ms: Long, use24h: Boolean = LiveTvPreferences.currentSettings().use24HourClock): String {
    val t = CivilTime.at(ms, LiveTvTimeZones.SYSTEM)
    if (use24h) return t.hour.toString().padStart(2, '0') + ":" + t.minute.toString().padStart(2, '0')
    val h12 = when (val h = t.hour % 12) { 0 -> 12; else -> h }
    return "$h12:${t.minute.toString().padStart(2, '0')} ${if (t.hour < 12) "AM" else "PM"}"
}

/** "2:00" from 120 minutes. */
internal fun hoursMinutes(minutes: Int): String = "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"
