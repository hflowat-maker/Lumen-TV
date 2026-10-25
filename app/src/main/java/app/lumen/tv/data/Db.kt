package app.lumen.tv.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement

class Db(context: Context) : SQLiteOpenHelper(context, "lumen.db", null, 1) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createCatalog(db)
        db.execSQL(
            "CREATE TABLE profile(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, " +
                "color INTEGER NOT NULL, kid INTEGER NOT NULL, pin TEXT, created INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE favorite(profile INTEGER NOT NULL, type TEXT NOT NULL, item INTEGER NOT NULL, " +
                "added INTEGER NOT NULL, PRIMARY KEY(profile, type, item))"
        )
        db.execSQL(
            "CREATE TABLE progress(profile INTEGER NOT NULL, kind TEXT NOT NULL, item TEXT NOT NULL, " +
                "series INTEGER NOT NULL DEFAULT 0, season INTEGER NOT NULL DEFAULT 0, episode INTEGER NOT NULL DEFAULT 0, " +
                "title TEXT, subtitle TEXT, image TEXT, position INTEGER NOT NULL, duration INTEGER NOT NULL, " +
                "ext TEXT, updated INTEGER NOT NULL, next INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(profile, kind, item))"
        )
        db.execSQL("CREATE INDEX progress_upd ON progress(profile, updated)")
        db.execSQL(
            "CREATE TABLE kid_cat(type TEXT NOT NULL, cat TEXT NOT NULL, allowed INTEGER NOT NULL, " +
                "PRIMARY KEY(type, cat))"
        )
        db.execSQL(
            "CREATE TABLE recent_live(profile INTEGER NOT NULL, item INTEGER NOT NULL, watched INTEGER NOT NULL, " +
                "PRIMARY KEY(profile, item))"
        )
    }

    private fun createCatalog(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE categories(type TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, " +
                "pos INTEGER NOT NULL, adult INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(type, id))"
        )
        db.execSQL(
            "CREATE TABLE live(id INTEGER PRIMARY KEY, num INTEGER, name TEXT, norm TEXT, icon TEXT, " +
                "cat TEXT, epg TEXT, archive INTEGER, adult INTEGER, pos INTEGER)"
        )
        db.execSQL("CREATE INDEX live_cat ON live(cat, pos)")
        db.execSQL(
            "CREATE TABLE movie(id INTEGER PRIMARY KEY, name TEXT, norm TEXT, icon TEXT, cat TEXT, " +
                "rating REAL, added INTEGER, ext TEXT, year TEXT, pos INTEGER)"
        )
        db.execSQL("CREATE INDEX movie_cat ON movie(cat, pos)")
        db.execSQL("CREATE INDEX movie_added ON movie(added)")
        db.execSQL(
            "CREATE TABLE series(id INTEGER PRIMARY KEY, name TEXT, norm TEXT, cover TEXT, backdrop TEXT, " +
                "cat TEXT, rating REAL, plot TEXT, genre TEXT, year TEXT, modified INTEGER, pos INTEGER)"
        )
        db.execSQL("CREATE INDEX series_cat ON series(cat, pos)")
        db.execSQL("CREATE INDEX series_mod ON series(modified)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        listOf("categories", "live", "movie", "series").forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
        createCatalog(db)
    }

    // ---------------- Écriture du catalogue ----------------

    private fun SQLiteStatement.str(i: Int, s: String?) = if (s == null) bindNull(i) else bindString(i, s)

    private inline fun tx(block: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun writeCategories(db: SQLiteDatabase, type: ContentType, cats: List<Category>, adultIds: Set<String>) {
        db.delete("categories", "type=?", arrayOf(type.key))
        val st = db.compileStatement("INSERT OR REPLACE INTO categories(type, id, name, pos, adult) VALUES(?,?,?,?,?)")
        cats.forEachIndexed { i, c ->
            st.clearBindings()
            st.bindString(1, type.key)
            st.bindString(2, c.id)
            st.bindString(3, c.name)
            st.bindLong(4, i.toLong())
            st.bindLong(5, if (c.id in adultIds || ADULT.containsMatchIn(c.name)) 1 else 0)
            st.executeInsert()
        }
        // Catégories enfants : détection automatique uniquement pour les nouvelles catégories,
        // les choix faits dans les réglages sont conservés.
        val kid = db.compileStatement("INSERT OR IGNORE INTO kid_cat(type, cat, allowed) VALUES(?,?,?)")
        cats.forEach { c ->
            kid.clearBindings()
            kid.bindString(1, type.key)
            kid.bindString(2, c.id)
            val allowed = KIDS.containsMatchIn(c.name) && !ADULT.containsMatchIn(c.name)
            kid.bindLong(3, if (allowed) 1 else 0)
            kid.executeInsert()
        }
    }

    fun replaceLive(cats: List<Category>, rows: List<Channel>) = tx { db ->
        val adultCats = rows.filter { it.adult }.map { it.categoryId }.toSet()
        writeCategories(db, ContentType.LIVE, cats, adultCats)
        db.delete("live", null, null)
        val st = db.compileStatement(
            "INSERT OR REPLACE INTO live(id, num, name, norm, icon, cat, epg, archive, adult, pos) VALUES(?,?,?,?,?,?,?,?,?,?)"
        )
        rows.forEachIndexed { i, c ->
            st.clearBindings()
            st.bindLong(1, c.id.toLong())
            st.bindLong(2, c.num.toLong())
            st.bindString(3, c.name)
            st.bindString(4, Text.normalize(c.name))
            st.str(5, c.icon)
            st.bindString(6, c.categoryId)
            st.str(7, c.epgId)
            st.bindLong(8, if (c.hasArchive) 1 else 0)
            st.bindLong(9, if (c.adult) 1 else 0)
            st.bindLong(10, i.toLong())
            st.executeInsert()
        }
    }

    fun replaceMovies(cats: List<Category>, rows: List<Tile>) = tx { db ->
        writeCategories(db, ContentType.MOVIE, cats, emptySet())
        db.delete("movie", null, null)
        val st = db.compileStatement(
            "INSERT OR REPLACE INTO movie(id, name, norm, icon, cat, rating, added, ext, year, pos) VALUES(?,?,?,?,?,?,?,?,?,?)"
        )
        rows.forEachIndexed { i, m ->
            val title = Text.cleanTitle(m.title)
            st.clearBindings()
            st.bindLong(1, m.id.toLong())
            st.bindString(2, title)
            st.bindString(3, Text.normalize(title))
            st.str(4, m.image)
            st.bindString(5, m.categoryId)
            st.bindDouble(6, m.rating)
            st.bindLong(7, m.added)
            st.bindString(8, m.ext)
            st.str(9, m.year ?: Text.extractYear(m.title))
            st.bindLong(10, i.toLong())
            st.executeInsert()
        }
    }

    fun replaceSeries(cats: List<Category>, rows: List<Tile>) = tx { db ->
        writeCategories(db, ContentType.SERIES, cats, emptySet())
        db.delete("series", null, null)
        val st = db.compileStatement(
            "INSERT OR REPLACE INTO series(id, name, norm, cover, backdrop, cat, rating, plot, genre, year, modified, pos) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)"
        )
        rows.forEachIndexed { i, s ->
            val title = Text.cleanTitle(s.title)
            st.clearBindings()
            st.bindLong(1, s.id.toLong())
            st.bindString(2, title)
            st.bindString(3, Text.normalize(title))
            st.str(4, s.image)
            st.str(5, s.backdrop)
            st.bindString(6, s.categoryId)
            st.bindDouble(7, s.rating)
            st.str(8, s.plot)
            st.str(9, s.genre)
            st.str(10, s.year ?: Text.extractYear(s.title))
            st.bindLong(11, s.added)
            st.bindLong(12, i.toLong())
            st.executeInsert()
        }
    }

    fun clearCatalog() = tx { db ->
        listOf("categories", "live", "movie", "series", "kid_cat").forEach { db.delete(it, null, null) }
    }

    // ---------------- Lecture du catalogue ----------------

    private fun table(type: ContentType) = when (type) {
        ContentType.LIVE -> "live"
        ContentType.MOVIE -> "movie"
        ContentType.SERIES -> "series"
    }

    /** Clause SQL qui restreint une colonne de catégorie selon le profil. */
    fun catClause(type: ContentType, f: ContentFilter, column: String = "cat"): String = when {
        f.kid -> "$column IN (SELECT cat FROM kid_cat WHERE type='${type.key}' AND allowed=1)"
        f.hideAdult -> "$column NOT IN (SELECT id FROM categories WHERE type='${type.key}' AND adult=1)" +
            if (type == ContentType.LIVE && column == "cat") " AND adult=0" else ""
        else -> "1=1"
    }

    fun categories(type: ContentType, f: ContentFilter): List<Category> {
        val t = table(type)
        val sql = "SELECT c.id, c.name, (SELECT COUNT(*) FROM $t x WHERE x.cat = c.id) AS n, c.adult " +
            "FROM categories c WHERE c.type = ? AND ${catClause(type, f, "c.id")} ORDER BY c.pos"
        return readableDatabase.rawQuery(sql, arrayOf(type.key)).use { c ->
            val out = ArrayList<Category>(c.count)
            while (c.moveToNext()) {
                val n = c.getInt(2)
                if (n > 0) out.add(Category(type, c.getString(0), c.getString(1), n, c.getInt(3) == 1))
            }
            out
        }
    }

    fun allCategories(type: ContentType): List<Pair<Category, Boolean>> {
        val t = table(type)
        val sql = "SELECT c.id, c.name, (SELECT COUNT(*) FROM $t x WHERE x.cat = c.id) AS n, " +
            "COALESCE((SELECT allowed FROM kid_cat k WHERE k.type = c.type AND k.cat = c.id), 0) " +
            "FROM categories c WHERE c.type = ? AND c.adult = 0 ORDER BY c.pos"
        return readableDatabase.rawQuery(sql, arrayOf(type.key)).use { c ->
            val out = ArrayList<Pair<Category, Boolean>>()
            while (c.moveToNext()) {
                if (c.getInt(2) > 0) out.add(Category(type, c.getString(0), c.getString(1), c.getInt(2)) to (c.getInt(3) == 1))
            }
            out
        }
    }

    fun setKidCategory(type: ContentType, id: String, allowed: Boolean) {
        val cv = ContentValues().apply {
            put("type", type.key); put("cat", id); put("allowed", if (allowed) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("kid_cat", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun Cursor.channel(): Channel = Channel(
        id = getInt(0), num = getInt(1), name = getString(2) ?: "", icon = getString(3),
        categoryId = getString(4) ?: "", epgId = getString(5), hasArchive = getInt(6) == 1,
    )

    private val channelCols = "id, num, name, icon, cat, epg, archive"

    private fun channels(sql: String, args: Array<String>? = null): List<Channel> =
        readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<Channel>(c.count)
            while (c.moveToNext()) out.add(c.channel())
            out
        }

    fun channelsIn(categoryId: String, f: ContentFilter): List<Channel> =
        channels("SELECT $channelCols FROM live WHERE cat = ? AND ${catClause(ContentType.LIVE, f)} ORDER BY pos", arrayOf(categoryId))

    fun allChannels(f: ContentFilter): List<Channel> =
        channels("SELECT $channelCols FROM live WHERE ${catClause(ContentType.LIVE, f)} ORDER BY pos")

    fun channelsByIds(ids: List<Int>, f: ContentFilter): List<Channel> {
        if (ids.isEmpty()) return emptyList()
        val list = channels(
            "SELECT $channelCols FROM live WHERE id IN (${ids.joinToString(",")}) AND ${catClause(ContentType.LIVE, f)}"
        )
        val order = ids.withIndex().associate { it.value to it.index }
        return list.sortedBy { order[it.id] ?: Int.MAX_VALUE }
    }

    private val movieCols = "id, name, icon, rating, year, ext, cat, added"

    private fun Cursor.movie(): Tile = Tile(
        type = ContentType.MOVIE, id = getInt(0), title = getString(1) ?: "", image = getString(2),
        rating = getDouble(3), year = getString(4), ext = getString(5) ?: "mp4",
        categoryId = getString(6) ?: "", added = getLong(7),
    )

    private val seriesCols = "id, name, cover, backdrop, rating, year, plot, genre, cat, modified"

    private fun Cursor.series(): Tile = Tile(
        type = ContentType.SERIES, id = getInt(0), title = getString(1) ?: "", image = getString(2),
        backdrop = getString(3), rating = getDouble(4), year = getString(5), plot = getString(6),
        genre = getString(7), categoryId = getString(8) ?: "", added = getLong(9),
    )

    private fun tiles(type: ContentType, sql: String, args: Array<String>? = null): List<Tile> =
        readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<Tile>(c.count)
            while (c.moveToNext()) out.add(if (type == ContentType.MOVIE) c.movie() else c.series())
            out
        }

    private fun cols(type: ContentType) = if (type == ContentType.MOVIE) movieCols else seriesCols

    fun tilesIn(type: ContentType, categoryId: String, f: ContentFilter, limit: Int = -1): List<Tile> {
        val lim = if (limit > 0) " LIMIT $limit" else ""
        return tiles(
            type,
            "SELECT ${cols(type)} FROM ${table(type)} WHERE cat = ? AND ${catClause(type, f)} ORDER BY pos$lim",
            arrayOf(categoryId),
        )
    }

    // ---------------- Requêtes par regroupement (langue / genre) ----------------

    private fun inList(ids: Collection<String>): String =
        ids.joinToString(",") { "'" + it.replace("'", "''") + "'" }

    /** Clause : dans les catégories du groupe, ou (séries) dans la langue et avec un genre correspondant. */
    private fun groupWhere(
        type: ContentType,
        catIds: Collection<String>,
        genreLike: List<String>,
        langCatIds: Collection<String>,
        f: ContentFilter,
    ): Pair<String, Array<String>> {
        val parts = ArrayList<String>()
        val args = ArrayList<String>()
        if (catIds.isNotEmpty()) parts += "cat IN (${inList(catIds)})"
        if (type == ContentType.SERIES && genreLike.isNotEmpty() && langCatIds.isNotEmpty()) {
            val likes = genreLike.joinToString(" OR ") { "genre LIKE ?" }
            parts += "(cat IN (${inList(langCatIds)}) AND ($likes))"
            genreLike.forEach { args += "%$it%" }
        }
        if (parts.isEmpty()) parts += "0"
        return "(${parts.joinToString(" OR ")}) AND ${catClause(type, f)}" to args.toTypedArray()
    }

    fun groupTiles(
        type: ContentType,
        catIds: Collection<String>,
        f: ContentFilter,
        limit: Int,
        genreLike: List<String> = emptyList(),
        langCatIds: Collection<String> = emptyList(),
    ): List<Tile> {
        val (where, args) = groupWhere(type, catIds, genreLike, langCatIds, f)
        val order = if (type == ContentType.MOVIE) "added DESC" else "modified DESC"
        return tiles(type, "SELECT ${cols(type)} FROM ${table(type)} WHERE $where ORDER BY $order LIMIT $limit", args)
    }

    fun groupCount(
        type: ContentType,
        catIds: Collection<String>,
        f: ContentFilter,
        genreLike: List<String> = emptyList(),
        langCatIds: Collection<String> = emptyList(),
    ): Int {
        val (where, args) = groupWhere(type, catIds, genreLike, langCatIds, f)
        return readableDatabase.rawQuery("SELECT COUNT(*) FROM ${table(type)} WHERE $where", args).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun channelsInCats(catIds: Collection<String>, f: ContentFilter): List<Channel> {
        if (catIds.isEmpty()) return emptyList()
        return channels(
            "SELECT $channelCols FROM live WHERE cat IN (${inList(catIds)}) AND ${catClause(ContentType.LIVE, f)} ORDER BY pos"
        )
    }

    fun channelCountInCats(catIds: Collection<String>, f: ContentFilter): Int {
        if (catIds.isEmpty()) return 0
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM live WHERE cat IN (${inList(catIds)}) AND ${catClause(ContentType.LIVE, f)}", null
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun recent(type: ContentType, f: ContentFilter, limit: Int): List<Tile> {
        val order = if (type == ContentType.MOVIE) "added" else "modified"
        return tiles(type, "SELECT ${cols(type)} FROM ${table(type)} WHERE ${catClause(type, f)} ORDER BY $order DESC LIMIT $limit")
    }

    fun tilesByIds(type: ContentType, ids: List<Int>, f: ContentFilter): List<Tile> {
        if (ids.isEmpty()) return emptyList()
        val list = tiles(
            type,
            "SELECT ${cols(type)} FROM ${table(type)} WHERE id IN (${ids.joinToString(",")}) AND ${catClause(type, f)}",
        )
        val order = ids.withIndex().associate { it.value to it.index }
        return list.sortedBy { order[it.id] ?: Int.MAX_VALUE }
    }

    fun tile(type: ContentType, id: Int): Tile? =
        tiles(type, "SELECT ${cols(type)} FROM ${table(type)} WHERE id = $id").firstOrNull()

    private fun escapeLike(q: String) = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    fun searchTiles(type: ContentType, query: String, f: ContentFilter, limit: Int = 60): List<Tile> {
        val q = escapeLike(Text.normalize(query))
        if (q.isBlank()) return emptyList()
        return tiles(
            type,
            "SELECT ${cols(type)} FROM ${table(type)} WHERE norm LIKE ? ESCAPE '\\' AND ${catClause(type, f)} " +
                "ORDER BY (norm LIKE ? ESCAPE '\\') DESC, length(name) LIMIT $limit",
            arrayOf("%$q%", "$q%"),
        )
    }

    fun searchChannels(query: String, f: ContentFilter, limit: Int = 40): List<Channel> {
        val q = escapeLike(Text.normalize(query))
        if (q.isBlank()) return emptyList()
        return channels(
            "SELECT $channelCols FROM live WHERE norm LIKE ? ESCAPE '\\' AND ${catClause(ContentType.LIVE, f)} " +
                "ORDER BY (norm LIKE ? ESCAPE '\\') DESC, length(name) LIMIT $limit",
            arrayOf("%$q%", "$q%"),
        )
    }

    fun counts(): Triple<Int, Int, Int> {
        fun n(t: String) = readableDatabase.rawQuery("SELECT COUNT(*) FROM $t", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return Triple(n("live"), n("movie"), n("series"))
    }

    // ---------------- Profils ----------------

    fun profiles(): List<Profile> = readableDatabase.rawQuery(
        "SELECT id, name, color, kid, pin FROM profile ORDER BY id", null
    ).use { c ->
        val out = ArrayList<Profile>()
        while (c.moveToNext()) out.add(Profile(c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3) == 1, c.getString(4)))
        out
    }

    fun saveProfile(p: Profile): Long {
        val cv = ContentValues().apply {
            put("name", p.name); put("color", p.color); put("kid", if (p.isKid) 1 else 0)
            if (p.pin == null) putNull("pin") else put("pin", p.pin)
        }
        return if (p.id > 0) {
            writableDatabase.update("profile", cv, "id=?", arrayOf(p.id.toString()))
            p.id
        } else {
            cv.put("created", System.currentTimeMillis())
            writableDatabase.insert("profile", null, cv)
        }
    }

    fun deleteProfile(id: Long) = tx { db ->
        val a = arrayOf(id.toString())
        db.delete("profile", "id=?", a)
        db.delete("favorite", "profile=?", a)
        db.delete("progress", "profile=?", a)
        db.delete("recent_live", "profile=?", a)
    }

    // ---------------- Favoris ----------------

    fun favorites(profile: Long): List<Pair<ContentType, Int>> = readableDatabase.rawQuery(
        "SELECT type, item FROM favorite WHERE profile=? ORDER BY added DESC", arrayOf(profile.toString())
    ).use { c ->
        val out = ArrayList<Pair<ContentType, Int>>()
        while (c.moveToNext()) out.add(ContentType.of(c.getString(0)) to c.getInt(1))
        out
    }

    fun setFavorite(profile: Long, type: ContentType, id: Int, fav: Boolean) {
        if (fav) {
            val cv = ContentValues().apply {
                put("profile", profile); put("type", type.key); put("item", id); put("added", System.currentTimeMillis())
            }
            writableDatabase.insertWithOnConflict("favorite", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } else {
            writableDatabase.delete("favorite", "profile=? AND type=? AND item=?", arrayOf(profile.toString(), type.key, id.toString()))
        }
    }

    // ---------------- Progression ----------------

    fun progress(profile: Long): List<WatchProgress> = readableDatabase.rawQuery(
        "SELECT kind, item, series, season, episode, title, subtitle, image, position, duration, ext, updated, next " +
            "FROM progress WHERE profile=? ORDER BY updated DESC",
        arrayOf(profile.toString()),
    ).use { c ->
        val out = ArrayList<WatchProgress>()
        while (c.moveToNext()) {
            out.add(
                WatchProgress(
                    kind = c.getString(0), itemId = c.getString(1), seriesId = c.getInt(2), season = c.getInt(3),
                    episode = c.getInt(4), title = c.getString(5) ?: "", subtitle = c.getString(6), image = c.getString(7),
                    position = c.getLong(8), duration = c.getLong(9), ext = c.getString(10) ?: "mp4",
                    updated = c.getLong(11), isNext = c.getInt(12) == 1,
                )
            )
        }
        out
    }

    fun saveProgress(profile: Long, p: WatchProgress) {
        val cv = ContentValues().apply {
            put("profile", profile); put("kind", p.kind); put("item", p.itemId); put("series", p.seriesId)
            put("season", p.season); put("episode", p.episode); put("title", p.title); put("subtitle", p.subtitle)
            put("image", p.image); put("position", p.position); put("duration", p.duration); put("ext", p.ext)
            put("updated", p.updated); put("next", if (p.isNext) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("progress", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteProgress(profile: Long, kind: String, item: String) {
        writableDatabase.delete("progress", "profile=? AND kind=? AND item=?", arrayOf(profile.toString(), kind, item))
    }

    // ---------------- Chaînes récentes ----------------

    fun recentChannelIds(profile: Long, limit: Int = 20): List<Int> = readableDatabase.rawQuery(
        "SELECT item FROM recent_live WHERE profile=? ORDER BY watched DESC LIMIT $limit", arrayOf(profile.toString())
    ).use { c ->
        val out = ArrayList<Int>()
        while (c.moveToNext()) out.add(c.getInt(0))
        out
    }

    fun touchChannel(profile: Long, id: Int) {
        val cv = ContentValues().apply { put("profile", profile); put("item", id); put("watched", System.currentTimeMillis()) }
        writableDatabase.insertWithOnConflict("recent_live", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    companion object {
        val ADULT = Regex("(?i)(\\bxxx\\b|\\badult|adulte|\\+ ?18|18 ?\\+|\\bporn|\\bsexy?\\b|hot ?club|playboy|erotic|érotique|brazzers|hustler|redlight|private ?tv)")
        val KIDS = Regex("(?i)(kid|enfant|jeunesse|junior|famil|cartoon|dessin|anim|disney|pixar|dreamworks|toon|nick|gulli|tiji|teletoon|télétoon|baby|bébé|piwi|boomerang|okoo|zouzous|pokemon|pokémon|kartoon|ludo|canal ?j|tfou|ghibli)")
    }
}
