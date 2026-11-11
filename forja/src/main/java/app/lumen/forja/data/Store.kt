package app.lumen.forja.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Mémoire locale de l'app : reprise de lecture et « Ma liste ». */
object Store {
    private const val KEY_PROGRESS = "progress"
    private const val KEY_FAVORITES = "favorites"
    private const val MAX_PROGRESS = 400

    private lateinit var prefs: SharedPreferences

    private val _progress = MutableStateFlow<Map<Int, Progress>>(emptyMap())
    val progress: StateFlow<Map<Int, Progress>> = _progress

    private val _favorites = MutableStateFlow<List<Favorite>>(emptyList())
    val favorites: StateFlow<List<Favorite>> = _favorites

    fun init(context: Context) {
        prefs = context.getSharedPreferences("forja_store", Context.MODE_PRIVATE)
        _progress.value = runCatching { readProgress() }.getOrDefault(emptyMap())
        _favorites.value = runCatching { readFavorites() }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------- reprise

    fun saveProgress(p: Progress) {
        val map = _progress.value.toMutableMap()
        map[p.id] = p
        val trimmed = if (map.size > MAX_PROGRESS) {
            map.values.sortedByDescending { it.updated }.take(MAX_PROGRESS).associateBy { it.id }
        } else map
        _progress.value = trimmed
        writeProgress(trimmed)
    }

    /** Retire une série (ou un film) de « Continuer à regarder ». */
    fun removeFromContinue(groupKey: String) {
        val map = _progress.value.filterValues { it.groupKey != groupKey }
        _progress.value = map
        writeProgress(map)
    }

    /** Une carte par série ou film : la vidéo la plus récente, ou l'épisode suivant si elle est finie. */
    fun continueList(map: Map<Int, Progress> = _progress.value): List<Progress> =
        map.values
            .groupBy { it.groupKey }
            .mapNotNull { (_, list) -> list.maxByOrNull { it.updated } }
            .filter { !it.finished || it.next != null }
            .sortedByDescending { it.updated }
            .take(24)

    /** Dernière vidéo regardée d'une série. */
    fun lastOfShow(showSlug: String, map: Map<Int, Progress> = _progress.value): Progress? =
        map.values.filter { it.showSlug == showSlug }.maxByOrNull { it.updated }

    // ---------------------------------------------------------------- Ma liste

    fun isFavorite(slug: String, list: List<Favorite> = _favorites.value) = list.any { it.slug == slug }

    /** Ajoute ou retire de « Ma liste » ; renvoie true si le titre y est maintenant. */
    fun toggleFavorite(item: Item): Boolean {
        val list = _favorites.value
        val now = if (list.any { it.slug == item.slug }) {
            list.filter { it.slug != item.slug }
        } else {
            listOf(
                Favorite(
                    item.id, item.slug, item.isSeries, item.title, item.poster, item.wide,
                    item.description, System.currentTimeMillis(),
                )
            ) + list
        }
        _favorites.value = now
        writeFavorites(now)
        return now.any { it.slug == item.slug }
    }

    // ---------------------------------------------------------------- JSON

    private fun JSONObject.s(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null

    private fun playableToJson(p: Playable) = JSONObject()
        .put("id", p.id).put("slug", p.slug).put("title", p.title)
        .put("subtitle", p.subtitle).put("image", p.image).put("dur", p.durationSecs)

    private fun playableFromJson(o: JSONObject) = Playable(
        o.optInt("id"), o.optString("slug"), o.optString("title"),
        o.s("subtitle"), o.s("image"), o.optInt("dur"),
    )

    private fun writeProgress(map: Map<Int, Progress>) {
        val arr = JSONArray()
        map.values.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id).put("slug", p.slug).put("show", p.showSlug)
                    .put("showTitle", p.showTitle).put("ep", p.episodeTitle).put("image", p.image)
                    .put("pos", p.position).put("dur", p.duration).put("upd", p.updated)
                    .put("next", p.next?.let { playableToJson(it) })
            )
        }
        prefs.edit().putString(KEY_PROGRESS, arr.toString()).apply()
    }

    private fun readProgress(): Map<Int, Progress> {
        val arr = JSONArray(prefs.getString(KEY_PROGRESS, null) ?: return emptyMap())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Progress(
                id = o.optInt("id"),
                slug = o.optString("slug"),
                showSlug = o.s("show"),
                showTitle = o.optString("showTitle"),
                episodeTitle = o.s("ep"),
                image = o.s("image"),
                position = o.optLong("pos"),
                duration = o.optLong("dur"),
                updated = o.optLong("upd"),
                next = o.optJSONObject("next")?.let { playableFromJson(it) },
            )
        }.associateBy { it.id }
    }

    private fun writeFavorites(list: List<Favorite>) {
        val arr = JSONArray()
        list.forEach { f ->
            arr.put(
                JSONObject()
                    .put("id", f.id).put("slug", f.slug).put("series", f.isSeries).put("title", f.title)
                    .put("poster", f.poster).put("wide", f.wide).put("desc", f.description).put("added", f.added)
            )
        }
        prefs.edit().putString(KEY_FAVORITES, arr.toString()).apply()
    }

    private fun readFavorites(): List<Favorite> {
        val arr = JSONArray(prefs.getString(KEY_FAVORITES, null) ?: return emptyList())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Favorite(
                o.optInt("id"), o.optString("slug"), o.optBoolean("series"), o.optString("title"),
                o.s("poster"), o.s("wide"), o.s("desc"), o.optLong("added"),
            )
        }
    }

    // Sérialisation de la file de lecture transmise au lecteur.
    fun queueToJson(queue: List<Playable>): String = JSONArray().apply { queue.forEach { put(playableToJson(it)) } }.toString()

    fun queueFromJson(s: String?): List<Playable> {
        if (s.isNullOrEmpty()) return emptyList()
        val arr = JSONArray(s)
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> playableFromJson(o) } }
    }
}
