package app.lumen.tv.data

import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class SyncProgress(
    val running: Boolean = false,
    val live: Int = -1,
    val movies: Int = -1,
    val series: Int = -1,
    val error: String? = null,
) {
    val done: Boolean get() = !running && error == null && live >= 0 && movies >= 0 && series >= 0
}

private const val SHELF_LIMIT = 40

class Repo(
    private val prefs: Prefs,
    private val api: XtreamApi,
    private val db: Db,
    private val http: okhttp3.OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Incrémenté après chaque synchro : les écrans relisent la base. */
    private val _catalogVersion = MutableStateFlow(0)
    val catalogVersion: StateFlow<Int> = _catalogVersion.asStateFlow()

    private val _sync = MutableStateFlow(SyncProgress())
    val sync: StateFlow<SyncProgress> = _sync.asStateFlow()
    private val syncMutex = Mutex()

    private val _profile = MutableStateFlow<Profile?>(null)
    val profile: StateFlow<Profile?> = _profile.asStateFlow()

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, WatchProgress>>(emptyMap())
    val progress: StateFlow<Map<String, WatchProgress>> = _progress.asStateFlow()

    /** Incrémenté quand les réglages de filtrage changent. */
    private val _filterVersion = MutableStateFlow(0)
    val filterVersion: StateFlow<Int> = _filterVersion.asStateFlow()

    private val movieInfoCache = LruCache<Int, MovieInfo>(80)
    private val seriesInfoCache = LruCache<Int, SeriesInfo>(40)
    private val epgCache = ConcurrentHashMap<Int, Pair<Long, List<EpgEntry>>>()
    private val epgGate = Semaphore(4)

    val account: Account? get() = prefs.account

    val filter: ContentFilter
        get() = ContentFilter(kid = _profile.value?.isKid == true, hideAdult = prefs.hideAdult)

    fun hasCatalog(): Boolean = prefs.lastSync > 0

    fun catalogIsStale(): Boolean = System.currentTimeMillis() - prefs.lastSync > 12 * 3600_000L

    private fun requireAccount(): Account = prefs.account ?: throw XtreamException("Aucun compte configuré")

    // ---------------- Compte & synchro ----------------

    suspend fun login(acc: Account): AccountInfo {
        val info = api.login(acc)
        prefs.account = acc
        storeAccountInfo(info)
        return info
    }

    private fun storeAccountInfo(info: AccountInfo) {
        prefs.accountExp = info.expDate ?: 0
        prefs.accountMaxCons = info.maxConnections
        prefs.accountActiveCons = info.activeConnections
        prefs.accountStatus = info.status
        prefs.streamBase = info.streamBase
        android.util.Log.i(
            app.lumen.tv.CrashReport.TAG,
            "Compte : ${info.status}, connexions ${info.activeConnections}/${info.maxConnections}, " +
                "essai=${info.isTrial}, formats=${info.formats}, diffusion=${info.streamBase}",
        )
        // Certains fournisseurs n'autorisent que le HLS : on s'adapte automatiquement.
        val formats = info.formats.map { it.lowercase() }
        if (formats.isNotEmpty() && "ts" !in formats && "m3u8" in formats) prefs.liveFormat = "m3u8"
    }

    suspend fun refreshAccountInfo(): AccountInfo? = try {
        api.login(requireAccount()).also(::storeAccountInfo)
    } catch (e: Exception) {
        null
    }

    /** Met à jour l'état du compte (connexions, adresse de diffusion) sans bloquer. */
    fun refreshAccountInBackground() {
        scope.launch { refreshAccountInfo() }
    }

    fun syncInBackground() {
        scope.launch { runCatching { sync() } }
    }

    suspend fun sync() {
        syncMutex.withLock { doSync() }
    }

    /** Lance la mise à jour du catalogue ; elle continue même si l'écran qui l'a lancée se ferme. */
    fun startSync(): kotlinx.coroutines.Deferred<Result<Unit>> = scope.async { runCatching { sync() } }

    private suspend fun doSync() {
        val acc = requireAccount()
        _sync.value = SyncProgress(running = true)
        try {
            coroutineScope {
                val live = async {
                    val cats = api.categories(acc, ContentType.LIVE)
                    val rows = api.liveStreams(acc)
                    db.replaceLive(cats, rows)
                    _sync.update { it.copy(live = rows.size) }
                }
                val movies = async {
                    val cats = api.categories(acc, ContentType.MOVIE)
                    val rows = api.vodStreams(acc)
                    db.replaceMovies(cats, rows)
                    _sync.update { it.copy(movies = rows.size) }
                }
                val series = async {
                    val cats = api.categories(acc, ContentType.SERIES)
                    val rows = api.series(acc)
                    db.replaceSeries(cats, rows)
                    _sync.update { it.copy(series = rows.size) }
                }
                live.await(); movies.await(); series.await()
            }
            prefs.lastSync = System.currentTimeMillis()
            _sync.update { it.copy(running = false) }
            _catalogVersion.update { it + 1 }
        } catch (e: Exception) {
            val msg = (e as? XtreamException)?.message ?: e.message ?: "Erreur inconnue"
            _sync.update { it.copy(running = false, error = msg) }
            throw e
        }
    }

    suspend fun logout() {
        withContext(Dispatchers.IO) {
            prefs.clearAccount()
            db.clearCatalog()
            movieInfoCache.evictAll()
            seriesInfoCache.evictAll()
            epgCache.clear()
        }
        _catalogVersion.update { it + 1 }
    }

    fun bumpFilters() {
        _filterVersion.update { it + 1 }
    }

    // ---------------- Profils ----------------

    suspend fun profiles(): List<Profile> = withContext(Dispatchers.IO) {
        var list = db.profiles()
        if (list.isEmpty()) {
            db.saveProfile(Profile(0, "Moi", 0, isKid = false, pin = null))
            db.saveProfile(Profile(0, "Enfants", 3, isKid = true, pin = null))
            list = db.profiles()
        }
        list
    }

    suspend fun restoreProfile(): Profile? = withContext(Dispatchers.IO) {
        val id = prefs.activeProfileId
        val p = profiles().firstOrNull { it.id == id }
        if (p != null) selectProfile(p)
        p
    }

    suspend fun selectProfile(p: Profile) = withContext(Dispatchers.IO) {
        prefs.activeProfileId = p.id
        _langs.value = Lang.decode(prefs.langFor(p.id)) ?: if (p.isKid) Lang.KIDS else Lang.DEFAULT
        _profile.value = p
        reloadUserData()
    }

    // ---------------- Langues ----------------

    private val _langs = MutableStateFlow(Lang.DEFAULT)
    val langs: StateFlow<Set<Lang>> = _langs.asStateFlow()

    /** Coche ou décoche une langue. Refuse de décocher la dernière (renvoie false). */
    fun toggleLang(lang: Lang): Boolean {
        val cur = _langs.value
        val next = if (lang in cur) cur - lang else cur + lang
        if (next.isEmpty()) return false
        _langs.value = next
        _profile.value?.let { prefs.setLangFor(it.id, Lang.encode(next)) }
        return true
    }

    @androidx.compose.runtime.Immutable
    data class LangCount(val movies: Int, val series: Int, val channels: Int)

    /** Nombre de films, séries et chaînes par langue, pour le panneau Langues. */
    suspend fun langCounts(): Map<Lang, LangCount> = withContext(Dispatchers.IO) {
        val f = filter
        fun count(type: ContentType): Map<Lang, Int> = Classifier.classify(type, db.categories(type, f))
            .groupBy { it.lang }.mapValues { e -> e.value.sumOf { it.count } }
        val m = count(ContentType.MOVIE)
        val s = count(ContentType.SERIES)
        val l = count(ContentType.LIVE)
        Lang.entries.associateWith { LangCount(m[it] ?: 0, s[it] ?: 0, l[it] ?: 0) }
    }

    // ---------------- Étagères ----------------

    private fun index(type: ContentType, f: ContentFilter): List<CatInfo> = Classifier.classify(type, db.categories(type, f))

    private fun langMap(infos: List<CatInfo>): Map<String, Lang> = infos.associate { it.id to it.lang }

    /** Étagère de films ou de séries, tronquée avec une carte « Tout voir ». */
    private fun vodShelf(
        type: ContentType,
        key: String,
        title: String,
        subtitle: (Int) -> String?,
        catIds: List<String>,
        langOf: Map<String, Lang>,
        f: ContentFilter,
        genreLike: List<String> = emptyList(),
        langCatIds: List<String> = emptyList(),
    ): Shelf? {
        if (catIds.isEmpty() && genreLike.isEmpty()) return null
        val tiles = db.groupTiles(type, catIds, f, SHELF_LIMIT + 1, genreLike, langCatIds)
        if (tiles.isEmpty()) return null
        val total = if (tiles.size > SHELF_LIMIT) db.groupCount(type, catIds, f, genreLike, langCatIds) else tiles.size
        val items = ArrayList<ShelfItem>(SHELF_LIMIT + 1)
        tiles.take(SHELF_LIMIT).mapTo(items) { TileItem(it, langOf[it.categoryId]) }
        if (total > SHELF_LIMIT) items += SeeAllItem(GridTarget(key, type, title, catIds, genreLike, langCatIds), total)
        return Shelf(key, title, subtitle(total), items)
    }

    private fun channelShelf(key: String, title: String, subtitle: (Int) -> String?, channels: List<Channel>, langOf: Map<String, Lang>): Shelf? {
        if (channels.isEmpty()) return null
        return Shelf(key, title, subtitle(channels.size), channels.map { ChannelItem(it, langOf[it.categoryId]) })
    }

    private fun unit(type: ContentType, n: Int): String = when (type) {
        ContentType.MOVIE -> Text.count(n, "film", "films")
        ContentType.SERIES -> Text.count(n, "série", "séries")
        ContentType.LIVE -> Text.count(n, "chaîne", "chaînes")
    }

    private fun langTitle(type: ContentType, lang: Lang): String = when (type) {
        ContentType.MOVIE -> when (lang) {
            Lang.FR -> "Films français"
            Lang.EN -> "Films en anglais"
            Lang.TR -> "Films turcs"
            Lang.OTHER -> "Films, autres langues"
        }
        ContentType.SERIES -> when (lang) {
            Lang.FR -> "Séries françaises"
            Lang.EN -> "Séries en anglais"
            Lang.TR -> "Séries turques"
            Lang.OTHER -> "Séries, autres langues"
        }
        ContentType.LIVE -> when (lang) {
            Lang.FR -> "Chaînes françaises"
            Lang.EN -> "Chaînes en anglais"
            Lang.TR -> "Chaînes turques"
            Lang.OTHER -> "Chaînes, autres langues"
        }
    }

    private fun langSubtitle(type: ContentType, lang: Lang, total: Int): String {
        val sub = if (lang == Lang.TR) (if (type == ContentType.SERIES) "Sous-titrées en arabe, " else "Sous-titrés en arabe, ") else ""
        val what = if (type == ContentType.SERIES) "mises à jour en dernier" else "ajoutés en dernier"
        return "$sub$what, ${unit(type, total)} au total".replaceFirstChar { it.uppercase() }
    }

    suspend fun shelves(tab: Tab): List<Shelf> = withContext(Dispatchers.IO) {
        when (tab) {
            Tab.HOME -> homeShelves()
            Tab.LIVE -> liveShelves()
            Tab.MOVIES -> vodShelves(ContentType.MOVIE)
            Tab.SERIES -> vodShelves(ContentType.SERIES)
            Tab.MINE -> mineShelves()
        }
    }

    private suspend fun homeShelves(): List<Shelf> {
        val f = filter
        val langs = _langs.value
        val out = ArrayList<Shelf>()
        val resume = continueWatching()
        if (resume.isNotEmpty()) {
            out += Shelf("resume", "Reprendre", Text.count(resume.size, "programme en cours", "programmes en cours"), resume.map { ResumeItem(it) })
        }
        val movies = index(ContentType.MOVIE, f)
        val series = index(ContentType.SERIES, f)
        val mLang = langMap(movies)
        val sLang = langMap(series)
        val sIds = Classifier.idsFor(series, langs)
        val since = System.currentTimeMillis() / 1000 - 24 * 3600
        val fresh = db.countSince(ContentType.SERIES, sIds, f, since)
        vodShelf(
            ContentType.SERIES, "home:episodes", "Nouveaux épisodes",
            { if (fresh > 0) "${unit(ContentType.SERIES, fresh)} mises à jour depuis hier" else "Les séries mises à jour en dernier" },
            sIds, sLang, f,
        )?.let { out += it }
        if (Lang.TR in langs && langs.size > 1) {
            vodShelf(
                ContentType.SERIES, "home:series:tr", langTitle(ContentType.SERIES, Lang.TR),
                { langSubtitle(ContentType.SERIES, Lang.TR, it) }, Classifier.idsFor(series, Lang.TR), sLang, f,
            )?.let { out += it }
        }
        for (lang in Lang.entries) {
            if (lang !in langs) continue
            vodShelf(
                ContentType.MOVIE, "home:movies:${lang.key}", langTitle(ContentType.MOVIE, lang),
                { langSubtitle(ContentType.MOVIE, lang, it) }, Classifier.idsFor(movies, lang), mLang, f,
            )?.let { out += it }
        }
        val mine = myList()
        if (mine.isNotEmpty()) {
            out += Shelf("home:mine", "Ma liste", Text.count(mine.size, "favori", "favoris"),
                mine.map { TileItem(it, (if (it.type == ContentType.MOVIE) mLang else sLang)[it.categoryId]) })
        }
        val recent = recentChannels()
        if (recent.isNotEmpty()) {
            val lLang = langMap(index(ContentType.LIVE, f))
            channelShelf("home:recent-live", "Chaînes récentes", { "Les dernières chaînes regardées" }, recent, lLang)?.let { out += it }
        }
        return out
    }

    private fun vodShelves(type: ContentType): List<Shelf> {
        val f = filter
        val langs = _langs.value
        val infos = index(type, f)
        val langOf = langMap(infos)
        val ids = Classifier.idsFor(infos, langs)
        val out = ArrayList<Shelf>()
        val newest = if (type == ContentType.MOVIE) "Derniers ajouts" else "Nouveaux épisodes"
        vodShelf(type, "${type.key}:new", newest, { "${unit(type, it)} dans tes langues" }, ids, langOf, f)?.let { out += it }
        if (langs.size > 1) {
            for (lang in listOf(Lang.FR, Lang.EN, Lang.TR)) {
                if (lang !in langs) continue
                vodShelf(type, "${type.key}:lang:${lang.key}", langTitle(type, lang), { langSubtitle(type, lang, it) },
                    Classifier.idsFor(infos, lang), langOf, f)?.let { out += it }
            }
        }
        val genreLangIds = infos.filter { it.lang in langs && (it.lang == Lang.FR || it.lang == Lang.EN) }.map { it.id }
        for (g in Classifier.genreGroups(type, infos, langs)) {
            vodShelf(type, "${type.key}:g:${g.key}", g.label, { unit(type, it) }, g.catIds, langOf, f,
                g.itemGenreKeywords, genreLangIds)?.let { out += it }
        }
        if (Lang.OTHER in langs) {
            for (g in Classifier.otherLanguageGroups(infos)) {
                vodShelf(type, "${type.key}:${g.key}", g.label, { "Autre langue, ${unit(type, it)}" }, g.catIds, langOf, f)?.let { out += it }
            }
        }
        return out
    }

    private suspend fun liveShelves(): List<Shelf> {
        val f = filter
        val langs = _langs.value
        val infos = index(ContentType.LIVE, f)
        val langOf = langMap(infos)
        val out = ArrayList<Shelf>()
        channelShelf("live:fav", "Mes chaînes", { unit(ContentType.LIVE, it) }, favoriteChannels(), langOf)?.let { out += it }
        channelShelf("live:recent", "Regardées récemment", { "Les dernières chaînes ouvertes" }, recentChannels(), langOf)?.let { out += it }
        for (g in Classifier.genreGroups(ContentType.LIVE, infos, langs)) {
            channelShelf("live:g:${g.key}", g.label, { unit(ContentType.LIVE, it) }, db.channelsInCats(g.catIds, f), langOf)?.let { out += it }
        }
        if (Lang.TR in langs) {
            channelShelf("live:tr", langTitle(ContentType.LIVE, Lang.TR), { unit(ContentType.LIVE, it) },
                db.channelsInCats(Classifier.idsFor(infos, Lang.TR), f), langOf)?.let { out += it }
        }
        if (Lang.OTHER in langs) {
            for (g in Classifier.otherLanguageGroups(infos)) {
                channelShelf("live:${g.key}", g.label, { "Autre langue, ${unit(ContentType.LIVE, it)}" }, db.channelsInCats(g.catIds, f), langOf)?.let { out += it }
            }
        }
        return out
    }

    private suspend fun mineShelves(): List<Shelf> {
        val f = filter
        val out = ArrayList<Shelf>()
        val resume = continueWatching()
        if (resume.isNotEmpty()) {
            out += Shelf("mine:resume", "Reprendre", Text.count(resume.size, "programme en cours", "programmes en cours"), resume.map { ResumeItem(it) })
        }
        val mLang = langMap(index(ContentType.MOVIE, f))
        val sLang = langMap(index(ContentType.SERIES, f))
        val lLang = langMap(index(ContentType.LIVE, f))
        val movies = db.tilesByIds(ContentType.MOVIE, favoriteOrder(ContentType.MOVIE), f)
        if (movies.isNotEmpty()) out += Shelf("mine:movies", "Films", unit(ContentType.MOVIE, movies.size), movies.map { TileItem(it, mLang[it.categoryId]) })
        val series = db.tilesByIds(ContentType.SERIES, favoriteOrder(ContentType.SERIES), f)
        if (series.isNotEmpty()) out += Shelf("mine:series", "Séries", unit(ContentType.SERIES, series.size), series.map { TileItem(it, sLang[it.categoryId]) })
        channelShelf("mine:live", "Chaînes", { unit(ContentType.LIVE, it) }, favoriteChannels(), lLang)?.let { out += it }
        return out
    }

    /** Contenu complet d'une étagère « Tout voir ». */
    suspend fun gridTiles(t: GridTarget): List<Tile> = withContext(Dispatchers.IO) {
        db.groupTiles(t.type, t.catIds, filter, 5000, t.genreLike, t.langCatIds)
    }

    suspend fun saveProfile(p: Profile): Long = withContext(Dispatchers.IO) {
        val id = db.saveProfile(p)
        if (_profile.value?.id == id) _profile.value = p.copy(id = id)
        id
    }

    suspend fun deleteProfile(id: Long) = withContext(Dispatchers.IO) { db.deleteProfile(id) }

    private fun reloadUserData() {
        val pid = _profile.value?.id ?: return
        _favorites.value = db.favorites(pid).map { "${it.first.key}:${it.second}" }.toSet()
        _progress.value = db.progress(pid).associateBy { it.key }
    }

    // ---------------- Catalogue ----------------

    suspend fun categories(type: ContentType): List<Category> = withContext(Dispatchers.IO) { db.categories(type, filter) }

    suspend fun tilesIn(type: ContentType, categoryId: String, limit: Int = -1): List<Tile> =
        withContext(Dispatchers.IO) { db.tilesIn(type, categoryId, filter, limit) }

    suspend fun recent(type: ContentType, limit: Int = 30): List<Tile> =
        withContext(Dispatchers.IO) { db.recent(type, filter, limit) }

    suspend fun tile(type: ContentType, id: Int): Tile? = withContext(Dispatchers.IO) { db.tile(type, id) }

    suspend fun channelsIn(categoryId: String): List<Channel> = withContext(Dispatchers.IO) { db.channelsIn(categoryId, filter) }

    suspend fun channelsInCats(ids: List<String>): List<Channel> = withContext(Dispatchers.IO) { db.channelsInCats(ids, filter) }

    suspend fun allChannels(): List<Channel> = withContext(Dispatchers.IO) { db.allChannels(filter) }

    suspend fun favoriteChannels(): List<Channel> = withContext(Dispatchers.IO) {
        val ids = _favorites.value.filter { it.startsWith("live:") }.mapNotNull { it.substringAfter(':').toIntOrNull() }
        val order = favoriteOrder(ContentType.LIVE)
        db.channelsByIds(order.ifEmpty { ids }, filter)
    }

    suspend fun recentChannels(): List<Channel> = withContext(Dispatchers.IO) {
        val pid = _profile.value?.id ?: return@withContext emptyList()
        db.channelsByIds(db.recentChannelIds(pid), filter)
    }

    private fun favoriteOrder(type: ContentType): List<Int> {
        val pid = _profile.value?.id ?: return emptyList()
        return db.favorites(pid).filter { it.first == type }.map { it.second }
    }

    suspend fun favoriteTiles(type: ContentType): List<Tile> = withContext(Dispatchers.IO) {
        db.tilesByIds(type, favoriteOrder(type), filter)
    }

    /** Films et séries favoris, du plus récent au plus ancien ajout. */
    suspend fun myList(): List<Tile> = withContext(Dispatchers.IO) {
        val pid = _profile.value?.id ?: return@withContext emptyList()
        val favs = db.favorites(pid).filter { it.first != ContentType.LIVE }
        val movies = db.tilesByIds(ContentType.MOVIE, favs.filter { it.first == ContentType.MOVIE }.map { it.second }, filter)
            .associateBy { it.id }
        val series = db.tilesByIds(ContentType.SERIES, favs.filter { it.first == ContentType.SERIES }.map { it.second }, filter)
            .associateBy { it.id }
        favs.mapNotNull { (type, id) -> if (type == ContentType.MOVIE) movies[id] else series[id] }
    }

    suspend fun search(query: String): Triple<List<Channel>, List<Tile>, List<Tile>> = withContext(Dispatchers.IO) {
        val f = filter
        Triple(
            db.searchChannels(query, f),
            db.searchTiles(ContentType.MOVIE, query, f),
            db.searchTiles(ContentType.SERIES, query, f),
        )
    }

    suspend fun kidCategories(type: ContentType): List<Pair<Category, Boolean>> =
        withContext(Dispatchers.IO) { db.allCategories(type) }

    suspend fun setKidCategory(type: ContentType, id: String, allowed: Boolean) =
        withContext(Dispatchers.IO) { db.setKidCategory(type, id, allowed) }

    fun counts(): Triple<Int, Int, Int> = db.counts()

    // ---------------- Fiches détaillées ----------------

    fun cachedMovieInfo(id: Int): MovieInfo? = movieInfoCache.get(id)

    suspend fun movieInfo(id: Int): MovieInfo {
        movieInfoCache.get(id)?.let { return it }
        val info = api.movieInfo(requireAccount(), id)
        movieInfoCache.put(id, info)
        return info
    }

    fun cachedSeriesInfo(id: Int): SeriesInfo? = seriesInfoCache.get(id)

    suspend fun seriesInfo(id: Int, fallbackName: String = "", force: Boolean = false): SeriesInfo {
        if (!force) seriesInfoCache.get(id)?.let { return it }
        val raw = api.seriesInfo(requireAccount(), id)
        val name = raw.name.ifBlank { fallbackName }
        val info = raw.copy(
            name = Text.cleanTitle(name),
            episodes = raw.episodes.map { it.copy(title = Text.cleanEpisodeTitle(it.title, name, it.number)) },
        )
        seriesInfoCache.put(id, info)
        return info
    }

    // ---------------- Guide des programmes ----------------

    fun cachedEpg(streamId: Int): List<EpgEntry>? = epgCache[streamId]?.second

    suspend fun epg(streamId: Int): List<EpgEntry> {
        val now = System.currentTimeMillis()
        epgCache[streamId]?.let { (at, list) ->
            val stillValid = now - at < 15 * 60_000L && (list.isEmpty() || list.any { it.end > now })
            if (stillValid) return list
        }
        return epgGate.withPermit {
            val list = try {
                api.shortEpg(requireAccount(), streamId, 8).filter { it.end > now - 60_000 }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            epgCache[streamId] = now to list
            list
        }
    }

    // ---------------- Lecture ----------------

    private val AUTH_OK = Regex("\"auth\"\\s*:\\s*\"?1")

    /** Adresse de diffusion de secours (server_info) quand elle diffère de l'adresse saisie. */
    fun alternateStreamBase(): String? {
        val main = prefs.account?.server ?: return null
        val alt = prefs.discoveredStreamBase ?: prefs.streamBase ?: return null
        return if (sameOrigin(alt, main)) null else alt
    }

    private fun sameOrigin(x: String, y: String): Boolean {
        val a = android.net.Uri.parse(x)
        val m = android.net.Uri.parse(y)
        val ap = if (a.port > 0) a.port else if (a.scheme == "https") 443 else 80
        val mp = if (m.port > 0) m.port else if (m.scheme == "https") 443 else 80
        return a.host.equals(m.host, ignoreCase = true) && ap == mp && a.scheme == m.scheme
    }

    /**
     * Le serveur refuse les vidéos sur l'adresse saisie : on cherche, en parallèle, un port
     * courant des serveurs Xtream qui accepte le compte puis la vidéo demandée.
     */
    data class StreamFix(val base: String? = null, val url: String? = null, val stack: String? = null)

    /** Cherche une façon de lire le flux refusé : autre pile réseau, autre port, autre forme d'adresse. */
    suspend fun findStreamFix(failingUrl: String, userAgent: String, currentStack: String): StreamFix? {
        findStack(failingUrl, userAgent, currentStack)?.let { return StreamFix(stack = it) }
        findStreamBase(failingUrl, userAgent)?.let { return StreamFix(base = it) }
        return findUrlVariant(failingUrl, userAgent)?.let { StreamFix(url = it) }
    }

    /** Compare la même demande faite de plusieurs façons (Java, OkHttp, IPv4) et garde celle qui passe. */
    private suspend fun findStack(url: String, userAgent: String, currentStack: String): String? = withContext(Dispatchers.IO) {
        val host = android.net.Uri.parse(url).host
        runCatching {
            val addrs = java.net.InetAddress.getAllByName(host).joinToString { it.hostAddress ?: "?" }
            android.util.Log.i(app.lumen.tv.CrashReport.TAG, "DNS $host : $addrs")
        }
        fun javaCode(): Int = runCatching {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 7000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept-Encoding", "identity")
            c.setRequestProperty("Range", "bytes=0-1023")
            try { c.responseCode } finally { c.disconnect() }
        }.getOrDefault(-1)
        fun okCode(plain: Boolean, ipv4: Boolean): Int = runCatching {
            val b = http.newBuilder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(7, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            if (ipv4) b.dns(app.lumen.tv.player.Ipv4FirstDns)
            val rb = okhttp3.Request.Builder().url(url).header("User-Agent", userAgent).header("Range", "bytes=0-1023")
            if (plain) rb.header("Accept", "*/*").header("Accept-Encoding", "identity")
            b.build().newCall(rb.build()).execute().use { it.code }
        }.getOrDefault(-1)
        val tests = linkedMapOf(
            app.lumen.tv.player.PlayerEngine.STACK_JAVA to { javaCode() },
            app.lumen.tv.player.PlayerEngine.STACK_OKHTTP_PLAIN to { okCode(plain = true, ipv4 = false) },
            app.lumen.tv.player.PlayerEngine.STACK_OKHTTP_IPV4 to { okCode(plain = true, ipv4 = true) },
            app.lumen.tv.player.PlayerEngine.STACK_OKHTTP to { okCode(plain = false, ipv4 = false) },
        )
        var winner: String? = null
        for ((name, test) in tests) {
            val code = test()
            android.util.Log.i(app.lumen.tv.CrashReport.TAG, "Test pile $name : HTTP $code")
            if (winner == null && code in 200..399) winner = name
        }
        if (winner != null && winner != currentStack) {
            prefs.httpStack = winner
            android.util.Log.i(app.lumen.tv.CrashReport.TAG, "Pile réseau retenue : $winner")
            winner
        } else null
    }

    private suspend fun probeCode(url: String, userAgent: String): Int = withContext(Dispatchers.IO) {
        val probe = http.newBuilder()
            .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(9, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        runCatching {
            probe.newCall(
                okhttp3.Request.Builder().url(url).header("User-Agent", userAgent).header("Range", "bytes=0-1023").build()
            ).execute().use { it.code }
        }.getOrDefault(-1)
    }

    /** Teste d'autres formes d'adresse (HLS, forme courte, autre extension) sur le même serveur. */
    private suspend fun findUrlVariant(failingUrl: String, userAgent: String): String? {
        val uri = android.net.Uri.parse(failingUrl)
        val seg = uri.encodedPath?.trim('/')?.split('/') ?: return null
        if (seg.size != 4) return null
        val (kind, u, p, file) = seg
        val id = file.substringBefore('.')
        val ext = file.substringAfter('.', "")
        val base = "${uri.scheme}://${uri.encodedAuthority}"
        val variants: List<Pair<String, String>> = when (kind) {
            "live" -> listOf(
                "hls" to "$base/live/$u/$p/$id.m3u8",
                "short" to "$base/$u/$p/$id",
                "shortts" to "$base/$u/$p/$id.ts",
                "noext" to "$base/live/$u/$p/$id",
            )
            "movie", "series" -> buildList {
                add("noext" to "$base/$kind/$u/$p/$id")
                if (ext != "mp4") add("mp4" to "$base/$kind/$u/$p/$id.mp4")
                if (ext != "mkv") add("mkv" to "$base/$kind/$u/$p/$id.mkv")
            }
            else -> emptyList()
        }
        for ((style, url) in variants) {
            val code = probeCode(url, userAgent)
            android.util.Log.i(app.lumen.tv.CrashReport.TAG, "Variante $kind/$style : HTTP $code")
            if (code in 200..399) {
                when {
                    kind == "live" && style == "hls" -> prefs.liveFormat = "m3u8"
                    kind == "live" -> prefs.liveUrlStyle = style
                    style == "noext" -> prefs.vodUrlStyle = "noext"
                }
                android.util.Log.i(app.lumen.tv.CrashReport.TAG, "Forme d'adresse retenue : $kind/$style")
                return url
            }
        }
        return null
    }

    suspend fun findStreamBase(failingUrl: String, userAgent: String): String? = withContext(Dispatchers.IO) {
        val acc = prefs.account ?: return@withContext null
        val original = android.net.Uri.parse(failingUrl)
        val host = original.host ?: return@withContext null
        val currentPort = if (original.port > 0) original.port else 80
        val ports = listOf(8080, 25461, 8000, 8880, 2052, 2082, 2086, 2095, 8888, 8081, 8090, 7070, 8789, 80)
            .filter { it != currentPort }
        val probe = http.newBuilder()
            .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(9, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
        val path = original.encodedPath ?: return@withContext null
        val found = coroutineScope {
            ports.map { port ->
                async {
                    val base = "http://$host:$port"
                    val apiOk = runCatching {
                        probe.newCall(
                            okhttp3.Request.Builder()
                                .url("$base/player_api.php?username=${enc(acc.username)}&password=${enc(acc.password)}")
                                .header("User-Agent", userAgent)
                                .build()
                        ).execute().use { r -> r.isSuccessful && AUTH_OK.containsMatchIn(r.body?.string().orEmpty()) }
                    }.getOrDefault(false)
                    if (!apiOk) return@async null
                    val code = runCatching {
                        probe.newCall(
                            okhttp3.Request.Builder()
                                .url("$base$path")
                                .header("User-Agent", userAgent)
                                .header("Range", "bytes=0-1023")
                                .build()
                        ).execute().use { r -> r.code }
                    }.getOrDefault(-1)
                    android.util.Log.i(app.lumen.tv.CrashReport.TAG, "Test du port $port : compte OK, vidéo HTTP $code")
                    if (code in 200..399) base else null
                }
            }.awaitAll().firstOrNull { it != null }
        }
        if (found != null) {
            android.util.Log.i(app.lumen.tv.CrashReport.TAG, "Adresse de diffusion trouvée : $found")
            prefs.discoveredStreamBase = found
            prefs.preferStreamBase = true
        } else {
            android.util.Log.w(app.lumen.tv.CrashReport.TAG, "Aucun port courant n'accepte les vidéos sur $host")
        }
        found
    }

    private fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")

    fun liveUrl(id: Int): String {
        val acc = requireAccount()
        return when (prefs.liveUrlStyle) {
            "short" -> "${acc.server}/${enc(acc.username)}/${enc(acc.password)}/$id"
            "shortts" -> "${acc.server}/${enc(acc.username)}/${enc(acc.password)}/$id.ts"
            "noext" -> "${acc.server}/live/${enc(acc.username)}/${enc(acc.password)}/$id"
            else -> api.liveUrl(acc, id, prefs.liveFormat)
        }
    }

    fun movieUrl(id: Int, ext: String): String {
        val url = api.movieUrl(requireAccount(), id, ext)
        return if (prefs.vodUrlStyle == "noext") url.substringBeforeLast('.') else url
    }

    fun episodeUrl(id: String, ext: String): String {
        val url = api.episodeUrl(requireAccount(), id, ext)
        return if (prefs.vodUrlStyle == "noext") url.substringBeforeLast('.') else url
    }

    fun isFavorite(type: ContentType, id: Int): Boolean = "${type.key}:$id" in _favorites.value

    fun toggleFavorite(type: ContentType, id: Int): Boolean {
        val pid = _profile.value?.id ?: return false
        val key = "${type.key}:$id"
        val now = key !in _favorites.value
        _favorites.value = if (now) _favorites.value + key else _favorites.value - key
        scope.launch { db.setFavorite(pid, type, id, now) }
        return now
    }

    fun touchChannel(id: Int) {
        val pid = _profile.value?.id ?: return
        scope.launch { db.touchChannel(pid, id) }
    }

    fun movieProgress(id: Int): WatchProgress? = _progress.value["${ProgressKind.MOVIE}:$id"]
    fun episodeProgress(id: String): WatchProgress? = _progress.value["${ProgressKind.EPISODE}:$id"]

    fun saveMovieProgress(tile: Tile, position: Long, duration: Long) {
        if (duration <= 0 || position < 5_000) return
        val wp = WatchProgress(
            kind = ProgressKind.MOVIE, itemId = tile.id.toString(), seriesId = 0, season = 0, episode = 0,
            title = tile.title, subtitle = tile.year, image = tile.backdrop ?: tile.image,
            position = position, duration = duration, ext = tile.ext,
            updated = System.currentTimeMillis(), isNext = false,
        )
        putProgress(wp)
    }

    fun saveEpisodeProgress(series: Tile, ep: Episode, position: Long, duration: Long, next: Episode?) {
        if (duration <= 0 || position < 5_000) return
        val now = System.currentTimeMillis()
        val wp = WatchProgress(
            kind = ProgressKind.EPISODE, itemId = ep.id, seriesId = series.id, season = ep.season, episode = ep.number,
            title = series.title, subtitle = "${ep.label} · ${ep.title}", image = ep.image ?: series.backdrop ?: series.image,
            position = position, duration = duration, ext = ep.ext, updated = now, isNext = false,
        )
        putProgress(wp)
        if (wp.finished && next != null && episodeProgress(next.id) == null) {
            putProgress(
                WatchProgress(
                    kind = ProgressKind.EPISODE, itemId = next.id, seriesId = series.id, season = next.season,
                    episode = next.number, title = series.title, subtitle = "${next.label} · ${next.title}",
                    image = next.image ?: series.backdrop ?: series.image, position = 0, duration = next.durationSecs * 1000L,
                    ext = next.ext, updated = now + 1, isNext = true,
                )
            )
        }
    }

    private fun putProgress(wp: WatchProgress) {
        val pid = _profile.value?.id ?: return
        _progress.value = _progress.value + (wp.key to wp)
        scope.launch { db.saveProgress(pid, wp) }
    }

    fun removeProgress(kind: String, itemId: String) {
        val pid = _profile.value?.id ?: return
        _progress.value = _progress.value - "$kind:$itemId"
        scope.launch { db.deleteProgress(pid, kind, itemId) }
    }

    /** Rangée « Continuer à regarder » : un élément par film, et le dernier épisode par série. */
    fun continueWatching(): List<WatchProgress> {
        val seenSeries = HashSet<Int>()
        val out = ArrayList<WatchProgress>()
        for (p in _progress.value.values.sortedByDescending { it.updated }) {
            if (p.kind == ProgressKind.EPISODE) {
                if (!seenSeries.add(p.seriesId)) continue
                if (p.isNext || (!p.finished && p.position > 30_000)) out.add(p)
            } else if (!p.finished && p.position > 30_000) {
                out.add(p)
            }
            if (out.size >= 20) break
        }
        return out
    }
}
