package com.nuvio.app.features.livetv.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.nuvio.app.features.livetv.model.EpgAssignment
import com.nuvio.app.features.livetv.model.EpgProgram

internal actual object EpgStore {
    private const val DB_NAME = "livetv_guide.db"
    private const val DB_VERSION = 3

    /** The longest a single show is taken to run, for reading "what's on now" by start time. */
    private const val MAX_SHOW_MS = 8L * 60 * 60 * 1000

    private var helper: Helper? = null

    fun initialize(context: Context) {
        if (helper == null) helper = Helper(context.applicationContext)
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        init {
            // Readers don't wait for writers: the guide stays readable while an update is stored.
            setWriteAheadLoggingEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE programs (
                    ch TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT NOT NULL,
                    descr TEXT, cat TEXT, ep TEXT, icon TEXT, year INTEGER, people TEXT)"""
            )
            createIndices(db)
            db.execSQL("CREATE TABLE auto (ch TEXT PRIMARY KEY, source TEXT NOT NULL, xmltv TEXT NOT NULL)")
            db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS programs_start ON programs(start)") }
                    .onFailure {
                        db.execSQL("DROP TABLE IF EXISTS programs")
                        db.execSQL("DROP TABLE IF EXISTS auto")
                        db.execSQL("DROP TABLE IF EXISTS meta")
                        onCreate(db)
                    }
            }
        }
    }

    private fun createIndices(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS programs_ch_start ON programs(ch, start)")
        db.execSQL("CREATE INDEX IF NOT EXISTS programs_stop ON programs(stop)")
        db.execSQL("CREATE INDEX IF NOT EXISTS programs_start ON programs(start)")
    }

    private val writable: SQLiteDatabase get() = (helper ?: error("EpgStore not initialized")).writableDatabase
    private val readable: SQLiteDatabase? get() = helper?.readableDatabase

    private inline fun <T> tx(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        try {
            return block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    private fun insertAll(db: SQLiteDatabase, programs: Map<String, List<EpgProgram>>) {
        val stmt = db.compileStatement(
            "INSERT INTO programs (ch, start, stop, title, descr, cat, ep, icon, year, people) VALUES (?,?,?,?,?,?,?,?,?,?)"
        )
        programs.forEach { (ch, list) ->
            list.forEach { p ->
                stmt.clearBindings()
                stmt.bindString(1, ch)
                stmt.bindLong(2, p.startMs)
                stmt.bindLong(3, p.stopMs)
                stmt.bindString(4, p.title)
                p.description?.let { stmt.bindString(5, it) } ?: stmt.bindNull(5)
                p.category?.let { stmt.bindString(6, it) } ?: stmt.bindNull(6)
                p.episode?.let { stmt.bindString(7, it) } ?: stmt.bindNull(7)
                p.icon?.let { stmt.bindString(8, it) } ?: stmt.bindNull(8)
                p.year?.let { stmt.bindLong(9, it.toLong()) } ?: stmt.bindNull(9)
                if (p.people.isEmpty()) stmt.bindNull(10) else stmt.bindString(10, p.people.joinToString("\u001F"))
                stmt.executeInsert()
            }
        }
    }

    private fun putAuto(db: SQLiteDatabase, auto: Map<String, EpgAssignment>) {
        auto.forEach { (ch, a) ->
            db.insertWithOnConflict("auto", null, ContentValues().apply {
                put("ch", ch); put("source", a.sourceId); put("xmltv", a.xmltvId)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    private fun putFingerprint(db: SQLiteDatabase, fingerprint: String) {
        db.insertWithOnConflict("meta", null, ContentValues().apply { put("k", "fingerprint"); put("v", fingerprint) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    actual fun replaceAll(fingerprint: String, programs: Map<String, List<EpgProgram>>, auto: Map<String, EpgAssignment>) {
        val db = writable
        tx(db) {
            db.execSQL("DROP INDEX IF EXISTS programs_ch_start")
            db.execSQL("DROP INDEX IF EXISTS programs_stop")
            db.execSQL("DROP INDEX IF EXISTS programs_start")
            db.delete("programs", null, null)
            db.delete("auto", null, null)
            insertAll(db, programs)
            putAuto(db, auto)
            createIndices(db)
            putFingerprint(db, fingerprint)
        }
    }

    actual fun replaceChannels(fingerprint: String, channels: Set<String>, programs: Map<String, List<EpgProgram>>, auto: Map<String, EpgAssignment>) {
        val db = writable
        tx(db) {
            channels.forEach { ch ->
                db.delete("programs", "ch = ?", arrayOf(ch))
                db.delete("auto", "ch = ?", arrayOf(ch))
            }
            insertAll(db, programs.filterKeys { it in channels })
            putAuto(db, auto.filterKeys { it in channels })
            putFingerprint(db, fingerprint)
        }
    }

    actual fun fingerprint(): String? {
        val db = readable ?: return null
        return db.rawQuery("SELECT v FROM meta WHERE k = 'fingerprint'", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }

    actual fun range(fromMs: Long, toMs: Long, light: Boolean, channels: Collection<String>?): Map<String, List<EpgProgram>> {
        val db = readable ?: return emptyMap()
        val out = HashMap<String, ArrayList<EpgProgram>>()
        val columns = if (light) "ch, start, stop, title, ep, icon" else "ch, start, stop, title, descr, cat, ep, icon, year, people"
        val timeArgs = listOf((fromMs - MAX_SHOW_MS).toString(), toMs.toString(), fromMs.toString())
        fun read(chFilter: String, chArgs: List<String>) {
            val sql = "SELECT $columns FROM programs WHERE $chFilter start >= ? AND start < ? AND stop > ?"
            db.rawQuery(sql, (chArgs + timeArgs).toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    val p = if (light) EpgProgram(
                        startMs = c.getLong(1),
                        stopMs = c.getLong(2),
                        title = c.getString(3),
                        episode = if (c.isNull(4)) null else c.getString(4),
                        icon = if (c.isNull(5)) null else c.getString(5)
                    ) else EpgProgram(
                        startMs = c.getLong(1),
                        stopMs = c.getLong(2),
                        title = c.getString(3),
                        description = if (c.isNull(4)) null else c.getString(4),
                        category = if (c.isNull(5)) null else c.getString(5),
                        episode = if (c.isNull(6)) null else c.getString(6),
                        icon = if (c.isNull(7)) null else c.getString(7),
                        year = if (c.isNull(8)) null else c.getInt(8),
                        people = if (c.isNull(9)) emptyList() else c.getString(9).split('\u001F')
                    )
                    out.getOrPut(c.getString(0)) { ArrayList() } += p
                }
            }
        }
        if (channels != null) {
            channels.distinct().chunked(400).forEach { part ->
                read("ch IN (" + part.joinToString(",") { "?" } + ") AND", part)
            }
        } else {
            read("", emptyList())
        }
        out.values.forEach { l -> l.sortBy { it.startMs } }
        return out
    }

    actual fun channelRange(ch: String, fromMs: Long, toMs: Long): List<EpgProgram> =
        range(fromMs, toMs, light = false, channels = listOf(ch))[ch].orEmpty()

    actual fun program(ch: String, startMs: Long): EpgProgram? =
        range(startMs, startMs + 1, light = false, channels = listOf(ch))[ch]?.firstOrNull { it.startMs == startMs }

    actual fun findByWords(fromMs: Long, toMs: Long, minStopMs: Long, words: Collection<String>, inDescription: Boolean, limit: Int): List<Pair<String, EpgProgram>> {
        val db = readable ?: return emptyList()
        val clean = words.map { it.trim() }.filter { it.length >= 3 }.distinct()
        if (clean.isEmpty()) return emptyList()
        val out = ArrayList<Pair<String, EpgProgram>>()
        val seen = HashSet<String>()
        fun like(w: String) = "%" + w.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        clean.chunked(if (inDescription) 180 else 360).forEach { part ->
            if (out.size >= limit) return@forEach
            val conds = part.flatMap {
                if (inDescription) listOf("title LIKE ? ESCAPE '\\'", "descr LIKE ? ESCAPE '\\'") else listOf("title LIKE ? ESCAPE '\\'")
            }.joinToString(" OR ")
            val args = listOf(fromMs.toString(), toMs.toString(), minStopMs.toString()) +
                part.flatMap { w -> if (inDescription) listOf(like(w), like(w)) else listOf(like(w)) }
            val sql = "SELECT ch, start, stop, title, descr, cat FROM programs WHERE start >= ? AND start < ? AND stop > ? AND ($conds) LIMIT ${limit - out.size}"
            db.rawQuery(sql, args.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    val ch = c.getString(0)
                    val start = c.getLong(1)
                    if (!seen.add("$ch|$start")) continue
                    out += ch to EpgProgram(
                        startMs = start,
                        stopMs = c.getLong(2),
                        title = c.getString(3),
                        description = if (c.isNull(4)) null else c.getString(4),
                        category = if (c.isNull(5)) null else c.getString(5)
                    )
                }
            }
        }
        return out
    }

    actual fun scanListings(fromMs: Long, toMs: Long, minStopMs: Long, onRow: (ch: String, start: Long, stop: Long, title: String, descr: String?, cat: String?) -> Unit) {
        val db = readable ?: return
        db.rawQuery(
            "SELECT ch, start, stop, title, descr, cat FROM programs WHERE start >= ? AND start < ? AND stop > ?",
            arrayOf(fromMs.toString(), toMs.toString(), minStopMs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                onRow(
                    c.getString(0), c.getLong(1), c.getLong(2), c.getString(3),
                    if (c.isNull(4)) null else c.getString(4),
                    if (c.isNull(5)) null else c.getString(5)
                )
            }
        }
    }

    actual fun nowNext(channels: Collection<String>, nowMs: Long): Map<String, NowNext> {
        if (channels.isEmpty()) return emptyMap()
        // Shows on now, plus the next few hours, read light.
        val window = range(nowMs, nowMs + 6 * 60 * 60 * 1000L, light = true, channels = channels)
        val out = HashMap<String, NowNext>(window.size)
        window.forEach { (ch, list) ->
            val now = list.firstOrNull { nowMs >= it.startMs && nowMs < it.stopMs }
            val next = list.firstOrNull { it.startMs >= (now?.stopMs ?: nowMs) }
            out[ch] = NowNext(now, next)
        }
        return out
    }

    actual fun autoMatches(): Map<String, EpgAssignment> {
        val db = readable ?: return emptyMap()
        val out = HashMap<String, EpgAssignment>()
        db.rawQuery("SELECT ch, source, xmltv FROM auto", null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = EpgAssignment(c.getString(1), c.getString(2))
        }
        return out
    }

    actual fun deleteChannels(channels: Collection<String>) {
        if (channels.isEmpty()) return
        val db = writable
        tx(db) {
            channels.forEach { ch ->
                db.delete("programs", "ch = ?", arrayOf(ch))
                db.delete("auto", "ch = ?", arrayOf(ch))
            }
        }
    }

    actual fun purgeOlderThan(beforeMs: Long): Int {
        val db = writable
        return tx(db) { db.delete("programs", "stop < ?", arrayOf(beforeMs.toString())) }
    }

    actual fun compact() {
        val db = writable
        runCatching {
            val pageSize = db.rawQuery("PRAGMA page_size", null).use { if (it.moveToFirst()) it.getLong(0) else 4096L }
            val freePages = db.rawQuery("PRAGMA freelist_count", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
            val pageCount = db.rawQuery("PRAGMA page_count", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
            // Worth it once a quarter of the file (and at least 8 MB) is empty space.
            val wasted = freePages * pageSize
            if (wasted >= 8L * 1024 * 1024 && pageCount > 0 && freePages * 4 >= pageCount) {
                db.execSQL("VACUUM")
            }
        }
    }

    actual fun clear() {
        val db = writable
        tx(db) {
            db.delete("programs", null, null)
            db.delete("auto", null, null)
            db.delete("meta", null, null)
        }
    }

    actual fun count(): Long {
        val db = readable ?: return 0L
        return db.rawQuery("SELECT COUNT(*) FROM programs", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    }
}
