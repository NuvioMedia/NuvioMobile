package com.nuvio.app.features.livetv.ondemand

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal actual object OnDemandStore {
    private const val DB_NAME = "livetv_on_demand.db"
    private const val DB_VERSION = 1

    private var helper: Helper? = null

    fun initialize(context: Context) {
        if (helper == null) helper = Helper(context.applicationContext)
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        init {
            // Browsing On Demand doesn't wait while an import is writing.
            setWriteAheadLoggingEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE items (
                    pl TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL,
                    name TEXT NOT NULL, norm TEXT NOT NULL, icon TEXT, cat TEXT NOT NULL,
                    ext TEXT, tmdb TEXT, year INTEGER, rating TEXT, added INTEGER NOT NULL DEFAULT 0,
                    genre TEXT, h INTEGER, url TEXT,
                    PRIMARY KEY (pl, kind, id))"""
            )
            createIndices(db)
            db.execSQL(
                """CREATE TABLE categories (
                    pl TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL,
                    position INTEGER NOT NULL, PRIMARY KEY (pl, kind, id))"""
            )
            db.execSQL("CREATE TABLE posters (uid TEXT PRIMARY KEY, poster TEXT, checked INTEGER NOT NULL, title TEXT, tmdb TEXT)")
            db.execSQL("CREATE TABLE prefixes (pl TEXT NOT NULL, prefix TEXT NOT NULL, PRIMARY KEY (pl, prefix))")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    private fun createIndices(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS items_cat ON items(kind, pl, cat)")
        db.execSQL("CREATE INDEX IF NOT EXISTS items_norm ON items(kind, norm)")
        db.execSQL("CREATE INDEX IF NOT EXISTS items_tmdb ON items(kind, tmdb)")
        db.execSQL("CREATE INDEX IF NOT EXISTS items_added ON items(kind, added)")
    }

    private val writable: SQLiteDatabase get() = (helper ?: error("OnDemandStore not initialized")).writableDatabase
    private val readable: SQLiteDatabase? get() = helper?.readableDatabase

    private inline fun <T> tx(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        try {
            return block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    /** A title's details as one number: the same number means nothing to write. */
    private fun fingerprint(it: VodItem): Long {
        var h = 1125899906842597L
        for (s in arrayOf(it.name, it.icon, it.categoryId, it.ext, it.tmdbId, it.year?.toString(), it.rating, it.addedSec.toString(), it.genre, it.url)) {
            h = 31 * h + (s?.hashCode() ?: 7)
        }
        return if (h == 0L) 1L else h
    }

    actual fun replace(playlistId: String, kind: VodKind, categories: List<Pair<String, String>>, items: Sequence<VodItem>): Int {
        val db = writable
        val existing = HashMap<String, Long>()
        db.rawQuery("SELECT id, h FROM items WHERE pl = ? AND kind = ?", arrayOf(playlistId, kind.key)).use { c ->
            while (c.moveToNext()) existing[c.getString(0)] = if (c.isNull(1)) 0L else c.getLong(1)
        }
        // The first import of a catalog writes with the search indexes off and builds them once
        // at the end, much quicker than keeping them up to date title by title.
        val bulk = existing.isEmpty()
        var count = 0
        tx(db) {
            if (bulk) {
                db.execSQL("DROP INDEX IF EXISTS items_cat")
                db.execSQL("DROP INDEX IF EXISTS items_norm")
                db.execSQL("DROP INDEX IF EXISTS items_tmdb")
                db.execSQL("DROP INDEX IF EXISTS items_added")
            }
            db.delete("categories", "pl = ? AND kind = ?", arrayOf(playlistId, kind.key))
            categories.forEachIndexed { i, (id, name) ->
                db.insertWithOnConflict("categories", null, ContentValues().apply {
                    put("pl", playlistId); put("kind", kind.key); put("id", id); put("name", name); put("position", i)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            val stmt = db.compileStatement(
                "INSERT OR REPLACE INTO items (pl, kind, id, name, norm, icon, cat, ext, tmdb, year, rating, added, genre, h, url) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            )
            val seen = HashSet<String>(maxOf(16, existing.size))
            items.forEach { it ->
                count++
                seen.add(it.id)
                val h = fingerprint(it)
                if (existing[it.id] == h) return@forEach
                stmt.clearBindings()
                stmt.bindString(1, playlistId)
                stmt.bindString(2, kind.key)
                stmt.bindString(3, it.id)
                stmt.bindString(4, it.name)
                stmt.bindString(5, VodTitles.normalize(it.name))
                it.icon?.let { v -> stmt.bindString(6, v) } ?: stmt.bindNull(6)
                stmt.bindString(7, it.categoryId)
                it.ext?.let { v -> stmt.bindString(8, v) } ?: stmt.bindNull(8)
                it.tmdbId?.let { v -> stmt.bindString(9, v) } ?: stmt.bindNull(9)
                it.year?.let { v -> stmt.bindLong(10, v.toLong()) } ?: stmt.bindNull(10)
                it.rating?.let { v -> stmt.bindString(11, v) } ?: stmt.bindNull(11)
                stmt.bindLong(12, it.addedSec)
                it.genre?.let { v -> stmt.bindString(13, v) } ?: stmt.bindNull(13)
                stmt.bindLong(14, h)
                it.url?.let { v -> stmt.bindString(15, v) } ?: stmt.bindNull(15)
                stmt.executeInsert()
                existing[it.id] = h
            }
            if (existing.size > seen.size) {
                val del = db.compileStatement("DELETE FROM items WHERE pl = ? AND kind = ? AND id = ?")
                existing.keys.filter { it !in seen }.forEach { id ->
                    del.clearBindings(); del.bindString(1, playlistId); del.bindString(2, kind.key); del.bindString(3, id)
                    del.executeUpdateDelete()
                }
            }
            if (bulk) createIndices(db)
        }
        return count
    }

    actual fun removeExcept(keepPlaylistIds: Set<String>): Set<String> {
        val db = writable
        val existing = HashSet<String>()
        db.rawQuery("SELECT DISTINCT pl FROM items UNION SELECT DISTINCT pl FROM categories", null).use { c ->
            while (c.moveToNext()) existing += c.getString(0)
        }
        val dropped = existing - keepPlaylistIds
        tx(db) {
            dropped.forEach { pl ->
                db.delete("items", "pl = ?", arrayOf(pl))
                db.delete("categories", "pl = ?", arrayOf(pl))
                db.delete("prefixes", "pl = ?", arrayOf(pl))
            }
        }
        return dropped
    }

    actual fun hasAny(): Boolean {
        val db = readable ?: return false
        return db.rawQuery("SELECT 1 FROM items LIMIT 1", null).use { it.moveToFirst() }
    }

    actual fun count(kind: VodKind): Int {
        val db = readable ?: return 0
        return db.rawQuery("SELECT COUNT(*) FROM items WHERE kind = ?", arrayOf(kind.key)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    actual fun categories(kind: VodKind): List<VodCategory> {
        val db = readable ?: return emptyList()
        val out = ArrayList<VodCategory>()
        db.rawQuery(
            """SELECT c.pl, c.id, c.name, (SELECT COUNT(*) FROM items i WHERE i.pl = c.pl AND i.kind = c.kind AND i.cat = c.id)
               FROM categories c WHERE c.kind = ? ORDER BY c.pl, c.position""",
            arrayOf(kind.key)
        ).use { c ->
            while (c.moveToNext()) {
                val n = c.getInt(3)
                if (n > 0) out += VodCategory(c.getString(0), kind, c.getString(1), c.getString(2), n)
            }
        }
        return out
    }

    actual fun items(kind: VodKind, category: VodCategory?, excludeCategories: Set<String>, limit: Int, offset: Int, sort: VodSort, merge: Boolean): List<VodItem> {
        val where = StringBuilder("kind = ?")
        val args = arrayListOf(kind.key)
        if (category != null) {
            where.append(" AND pl = ? AND cat = ?")
            args += category.playlistId; args += category.id
        }
        excludeCategories.forEach { uid ->
            val parts = uid.split(':', limit = 3)
            if (parts.size == 3 && parts[0] == kind.key) {
                where.append(" AND NOT (pl = ? AND cat = ?)")
                args += parts[1]; args += parts[2]
            }
        }
        val order = sort.orderBy("rowid")
        val group = if (merge) " GROUP BY $VOD_DUP_KEY" else ""
        val copies = if (merge) ", COUNT(*) AS copies" else ""
        // A fixed last tie-break: titles that sort the same must come back in the same order on
        // every page, or a title shows on two pages and another on none.
        val tieBreak = if (merge) "MIN(rowid)" else "rowid"
        return query("SELECT *$copies FROM items WHERE $where$group ORDER BY $order, $tieBreak LIMIT $limit OFFSET $offset", args)
    }

    actual fun search(kind: VodKind, text: String, excludeCategories: Set<String>, limit: Int, sort: VodSort, merge: Boolean): List<VodItem> {
        val words = VodTitles.normalizeWords(text)
        if (words.isEmpty()) return emptyList()
        val result = runCatching {
            val joined = words.joinToString("")
            val starts = rowids(
                "SELECT rowid FROM items WHERE kind = ? AND norm >= ? AND norm < ? LIMIT 1500",
                listOf(kind.key, joined, joined + "￿")
            )
            val anywhere = rowids(
                "SELECT rowid FROM items WHERE kind = ? AND " + words.joinToString(" AND ") { "instr(norm, ?) > 0" } + " LIMIT 4000",
                listOf(kind.key) + words
            )
            val ids = LinkedHashSet<Long>(starts).apply { addAll(anywhere) }
            if (ids.isEmpty()) return@runCatching emptyList()
            val group = if (merge) " GROUP BY $VOD_DUP_KEY" else ""
            val copies = if (merge) ", COUNT(*) AS copies" else ""
            // Best first: what you typed at the start of the name, then the shortest names.
            val first = if (sort == VodSort.DEFAULT && starts.isNotEmpty()) "(rowid IN (${starts.joinToString(",")})) DESC, " else ""
            query(
                "SELECT *$copies FROM items WHERE rowid IN (${ids.joinToString(",")})$group ORDER BY $first${sort.orderBy("length(name)")} LIMIT $limit",
                emptyList()
            )
        }.getOrElse {
            // Anything unexpected: the plain search.
            val where = StringBuilder("kind = ?")
            val args = arrayListOf(kind.key)
            words.forEach { w -> where.append(" AND norm LIKE ?"); args += "%$w%" }
            val group = if (merge) " GROUP BY $VOD_DUP_KEY" else ""
            val copies = if (merge) ", COUNT(*) AS copies" else ""
            query("SELECT *$copies FROM items WHERE $where$group ORDER BY ${sort.orderBy("length(name)")} LIMIT $limit", args)
        }
        return result.filter { "${it.kind.key}:${it.playlistId}:${it.categoryId}" !in excludeCategories }
    }

    actual fun byTmdb(kind: VodKind, tmdbId: String): List<VodItem> =
        query("SELECT * FROM items WHERE kind = ? AND tmdb = ?", arrayListOf(kind.key, tmdbId))

    actual fun versions(item: VodItem): List<VodItem> =
        if (!item.tmdbId.isNullOrBlank()) query("SELECT * FROM items WHERE kind = ? AND tmdb = ?", arrayListOf(item.kind.key, item.tmdbId))
        else query(
            "SELECT * FROM items WHERE kind = ? AND (tmdb IS NULL OR tmdb = '') AND norm = ? AND IFNULL(year, 0) = ?",
            arrayListOf(item.kind.key, VodTitles.normalize(item.name), (item.year ?: 0).toString())
        )

    actual fun categoryNames(playlistId: String, kind: VodKind): Map<String, String> {
        val db = readable ?: return emptyMap()
        val out = HashMap<String, String>()
        db.rawQuery("SELECT id, name FROM categories WHERE pl = ? AND kind = ?", arrayOf(playlistId, kind.key)).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
        }
        return out
    }

    actual fun item(playlistId: String, kind: VodKind, id: String): VodItem? =
        query("SELECT * FROM items WHERE pl = ? AND kind = ? AND id = ?", arrayListOf(playlistId, kind.key, id)).firstOrNull()

    actual fun learnPrefixes(playlistId: String): Set<String> {
        val db = writable
        val names = ArrayList<String>()
        db.rawQuery("SELECT name FROM items WHERE pl = ?", arrayOf(playlistId)).use { c -> while (c.moveToNext()) names += c.getString(0) }
        val learned = VodTitles.learnPrefixes(names.asSequence())
        tx(db) {
            db.delete("prefixes", "pl = ?", arrayOf(playlistId))
            learned.forEach { p ->
                db.insertWithOnConflict("prefixes", null, ContentValues().apply { put("pl", playlistId); put("prefix", p) }, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
        return loadPrefixes()
    }

    actual fun hasLearnedPrefixes(playlistId: String): Boolean {
        val db = readable ?: return false
        return db.rawQuery("SELECT 1 FROM prefixes WHERE pl = ? LIMIT 1", arrayOf(playlistId)).use { it.moveToFirst() }
    }

    actual fun loadPrefixes(): Set<String> {
        val db = readable ?: return emptySet()
        val set = HashSet<String>()
        runCatching { db.rawQuery("SELECT prefix FROM prefixes", null).use { c -> while (c.moveToNext()) set += c.getString(0) } }
        return set
    }

    actual fun cachedMatch(uid: String): VodMatch? {
        val db = readable ?: return null
        return db.rawQuery("SELECT poster, checked, title, tmdb FROM posters WHERE uid = ?", arrayOf(uid)).use { c ->
            if (c.moveToFirst()) VodMatch(
                poster = c.getString(0) ?: "",
                checkedMs = c.getLong(1),
                title = if (c.isNull(2)) null else c.getString(2),
                tmdbId = if (c.isNull(3)) null else c.getString(3),
            ) else null
        }
    }

    actual fun cacheMatch(uid: String, match: VodMatch) {
        writable.insertWithOnConflict("posters", null, ContentValues().apply {
            put("uid", uid); put("poster", match.poster); put("checked", match.checkedMs)
            if (match.title != null) put("title", match.title) else putNull("title")
            if (match.tmdbId != null) put("tmdb", match.tmdbId) else putNull("tmdb")
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    actual fun compact() {
        val db = writable
        runCatching {
            val pageSize = db.rawQuery("PRAGMA page_size", null).use { if (it.moveToFirst()) it.getLong(0) else 4096L }
            val freePages = db.rawQuery("PRAGMA freelist_count", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
            val pageCount = db.rawQuery("PRAGMA page_count", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
            if (freePages * pageSize >= 8L * 1024 * 1024 && pageCount > 0 && freePages * 4 >= pageCount) db.execSQL("VACUUM")
        }
    }

    private fun query(sql: String, args: List<String>): List<VodItem> {
        val db = readable ?: return emptyList()
        val out = ArrayList<VodItem>()
        db.rawQuery(sql, args.toTypedArray()).use { c -> while (c.moveToNext()) out += c.toItem() }
        return out
    }

    private fun rowids(sql: String, args: List<String>): List<Long> {
        val db = readable ?: return emptyList()
        val out = ArrayList<Long>()
        db.rawQuery(sql, args.toTypedArray()).use { c -> while (c.moveToNext()) out += c.getLong(0) }
        return out
    }

    private fun Cursor.toItem(): VodItem = VodItem(
        playlistId = getString(getColumnIndexOrThrow("pl")),
        kind = VodKind.of(getString(getColumnIndexOrThrow("kind"))),
        id = getString(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")),
        icon = getStringOrNull("icon"),
        categoryId = getString(getColumnIndexOrThrow("cat")),
        ext = getStringOrNull("ext"),
        tmdbId = getStringOrNull("tmdb"),
        year = getColumnIndexOrThrow("year").let { i -> if (isNull(i)) null else getInt(i) },
        rating = getStringOrNull("rating"),
        addedSec = getLong(getColumnIndexOrThrow("added")),
        genre = getStringOrNull("genre"),
        url = getStringOrNull("url"),
        copies = getColumnIndex("copies").takeIf { it >= 0 }?.let { i -> getInt(i) } ?: 1,
    )

    private fun Cursor.getStringOrNull(col: String): String? =
        getColumnIndexOrThrow(col).let { i -> if (isNull(i)) null else getString(i) }
}
