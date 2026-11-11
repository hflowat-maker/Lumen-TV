package app.lumen.forja.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Catalogue public de Forja (SNRT) : les mêmes requêtes que le site forja.ma
 * (rubriques, fiches, saisons et épisodes, recherche). Aucune donnée de compte.
 */
object ForjaApi {
    const val SITE = "https://forja.ma"
    private const val BASE = "https://forja.ma/pages"
    private const val TTL = 15 * 60_000L

    lateinit var http: OkHttpClient

    private val cache = ConcurrentHashMap<String, Pair<Long, Any>>()

    /** Adresse du lecteur officiel d'une vidéo. */
    fun playerUrl(slug: String) = "$SITE/fr/player/$slug"

    private suspend fun getJson(path: String, params: Map<String, String> = emptyMap(), ttl: Long = TTL): Any =
        withContext(Dispatchers.IO) {
            val url = (BASE + path).toHttpUrl().newBuilder().apply {
                addQueryParameter("lang", "fr")
                params.forEach { (k, v) -> addQueryParameter(k, v) }
            }.build()
            val key = url.toString()
            val hit = cache[key]
            if (hit != null && System.currentTimeMillis() - hit.first < ttl) return@withContext hit.second
            val request = Request.Builder().url(url).header("Accept", "application/json").build()
            http.newCall(request).execute().use { res ->
                if (!res.isSuccessful) throw IOException("Forja ne répond pas (erreur ${res.code})")
                val body = res.body?.string() ?: throw IOException("Réponse vide de Forja")
                val value = JSONTokener(body).nextValue()
                cache[key] = System.currentTimeMillis() to value
                value
            }
        }

    fun clearCache() = cache.clear()

    // ---------------------------------------------------------------- requêtes

    /** Rubriques du menu du site : Séries, Films, Théâtre, Documentaires… avec leurs genres. */
    suspend fun menu(): List<Category> {
        val arr = getJson("/menu") as? JSONArray ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("type") != "category") return@mapNotNull null
            val g = o.optJSONArray("genres") ?: JSONArray()
            val genres = (0 until g.length()).mapNotNull { j ->
                val go = g.optJSONObject(j) ?: return@mapNotNull null
                val slug = go.str("slug") ?: return@mapNotNull null
                Genre(slug, clean(go.str("name") ?: slug))
            }
            val slug = o.str("slug") ?: return@mapNotNull null
            Category(slug, clean(o.str("name") ?: slug), genres)
        }
    }

    /** Premiers titres d'une rubrique ou d'un genre, et le nombre total de titres. */
    suspend fun category(slug: String, limit: Int = 20): Pair<List<Item>, Int> {
        val o = getJson("/category/$slug", mapOf("limit" to "$limit")) as? JSONObject ?: return emptyList<Item>() to 0
        val items = parseItems(o.optJSONArray("contents"))
        val total = o.optString("contents_count").toIntOrNull() ?: items.size
        return items to total
    }

    /** Une page de 40 titres d'une rubrique (« Tout voir »). */
    suspend fun page(category: String, page: Int): Page {
        val o = getJson("/search/", mapOf("categories" to category, "page" to "$page", "page_size" to "40")) as JSONObject
        return parsePage(o)
    }

    suspend fun search(query: String): List<Item> {
        val o = getJson("/search/", mapOf("filter" to query, "page" to "1", "page_size" to "40"), ttl = 120_000L) as JSONObject
        return parsePage(o).items
    }

    /** Fiche complète : saisons et épisodes, genres, distribution, titres similaires. */
    suspend fun detail(slug: String): Detail {
        val o = getJson("/content/$slug") as JSONObject
        val item = parseItem(o)
        val children = o.optJSONArray("contents") ?: JSONArray()
        val seasons = mutableListOf<Season>()
        val loose = mutableListOf<Episode>()
        for (i in 0 until children.length()) {
            val c = children.optJSONObject(i) ?: continue
            when (c.optString("type")) {
                "playlist" -> {
                    val eps = c.optJSONArray("contents") ?: JSONArray()
                    val list = (0 until eps.length()).mapNotNull { j ->
                        eps.optJSONObject(j)?.takeIf { it.optString("type") == "video" }?.let { parseEpisode(it, j + 1) }
                    }
                    if (list.isNotEmpty()) seasons += Season(clean(c.str("name") ?: "Saison ${seasons.size + 1}"), orderEpisodes(list))
                }
                "video" -> loose += parseEpisode(c, loose.size + 1)
            }
        }
        if (loose.isNotEmpty()) seasons += Season(if (seasons.isEmpty()) "Épisodes" else "Autres vidéos", orderEpisodes(loose))

        val genres = mutableListOf<String>()
        val cast = mutableListOf<String>()
        val cats = o.optJSONArray("categories") ?: JSONArray()
        for (i in 0 until cats.length()) {
            val c = cats.optJSONObject(i) ?: continue
            val name = clean(c.str("name") ?: continue)
            val parents = c.optJSONArray("parents") ?: JSONArray()
            val parentSlugs = (0 until parents.length()).mapNotNull { parents.optJSONObject(it)?.str("slug") }
            when {
                "actors" in parentSlugs -> cast += name
                parentSlugs.any { it == "series" || it == "films" } -> genres += name
            }
        }
        return Detail(
            item = item,
            seasons = orderSeasons(seasons, item.title),
            genres = genres.distinct(),
            cast = cast.distinct(),
            similar = parseItems(o.optJSONArray("similars")).filter { it.slug != item.slug },
            adTag = parseAdTag(o.optJSONArray("ads"), item.slug),
        )
    }

    /** Adresse de la vidéo (HLS), telle que le site la demande avant de lancer son lecteur. */
    suspend fun streamUrl(id: Int): String? = withContext(Dispatchers.IO) {
        val url = "$BASE/proxy/content/$id/stream_url".toHttpUrl().newBuilder()
            .addQueryParameter("lang", "fr")
            .addQueryParameter("mode", "link")
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { res ->
            if (!res.isSuccessful) return@withContext null
            val body = res.body?.string()?.trim().orEmpty().trim('"')
            body.takeIf { it.startsWith("http") }
        }
    }

    /** Publicité VAST de Forja : on complète les champs à remplir comme le fait le site. */
    private fun parseAdTag(ads: JSONArray?, slug: String): String? {
        if (ads == null) return null
        for (i in 0 until ads.length()) {
            val a = ads.optJSONObject(i) ?: continue
            if (a.optString("type") != "external_vast") continue
            val raw = a.str("protocol_link") ?: a.str("vast_xml_url") ?: continue
            val page = java.net.URLEncoder.encode("$SITE/content/$slug", "UTF-8")
            return raw
                .replace("[placeholder]", page)
                .replace("[timestamp]", System.currentTimeMillis().toString())
                .replace(Regex("\\[[a-zA-Z_]+\\]"), "")
        }
        return null
    }

    // ---------------------------------------------------------------- lecture du JSON

    private fun parsePage(o: JSONObject): Page {
        val c = o.optJSONObject("contents") ?: return Page(emptyList(), 1, 1, 0)
        val items = parseItems(c.optJSONArray("data"))
        return Page(
            items = items,
            page = c.optInt("page", 1),
            lastPage = c.optInt("lastPage", 1),
            total = c.optInt("total", items.size),
        )
    }

    private fun parseItems(arr: JSONArray?): List<Item> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val t = o.optString("type")
            if (t != "playlist" && t != "video") return@mapNotNull null
            if (!o.optBoolean("is_active", true)) return@mapNotNull null
            parseItem(o).takeIf { it.slug.isNotEmpty() }
        }.distinctBy { it.id }
    }

    private fun parseItem(o: JSONObject) = Item(
        id = o.optInt("id"),
        slug = o.optString("slug"),
        isSeries = o.optString("type") == "playlist",
        title = clean(o.str("name") ?: o.str("name_short") ?: o.str("name_long") ?: ""),
        description = (o.str("description_short") ?: o.str("description") ?: o.str("description_long"))?.let { clean(it) },
        poster = o.str("vertical_image") ?: o.str("top_image"),
        wide = o.str("poster_image") ?: o.str("miniature_paysage_image") ?: o.str("banner_image"),
        tile = o.str("tile_image") ?: o.str("miniature_portrait_image"),
        durationSecs = o.optInt("duration", 0),
        free = o.optString("access", "public") == "public",
    )

    private val episodeNumber = Regex("""(?i)(?:é|e)pisode\s*(\d+)""")
    private val seasonNumber = Regex("""(?i)(?:saison|season|\bs)\s*(\d+)""")

    private fun parseEpisode(o: JSONObject, fallbackNumber: Int): Episode {
        val name = clean(o.str("name") ?: o.str("name_short") ?: "")
        val number = episodeNumber.find(name)?.groupValues?.get(1)?.toIntOrNull()
            ?: o.optInt("pivot_index", 0).takeIf { it > 0 }
            ?: fallbackNumber
        return Episode(
            id = o.optInt("id"),
            slug = o.optString("slug"),
            title = name,
            number = number,
            durationSecs = o.optInt("duration", 0),
            image = o.str("tile_image") ?: o.str("poster_image"),
        )
    }

    /** Trie par numéro d'épisode quand les numéros sont tous différents, sinon garde l'ordre du site. */
    private fun orderEpisodes(list: List<Episode>): List<Episode> =
        if (list.map { it.number }.toSet().size == list.size) list.sortedBy { it.number } else list

    /** Saison 1, 2, 3… dans l'ordre quand les noms portent un numéro ; libellés « Saison N ». */
    private fun orderSeasons(seasons: List<Season>, showTitle: String): List<Season> {
        if (seasons.size == 1) {
            val s = seasons[0]
            val n = seasonNumber.find(s.title)?.groupValues?.get(1)?.toIntOrNull()
            return listOf(s.copy(title = if (n != null) "Saison $n" else "Épisodes"))
        }
        val numbers = seasons.map { seasonNumber.find(it.title)?.groupValues?.get(1)?.toIntOrNull() }
        if (numbers.all { it != null } && numbers.toSet().size == numbers.size) {
            return seasons.zip(numbers).sortedBy { it.second }.map { (s, n) -> s.copy(title = "Saison $n") }
        }
        return seasons.map { s ->
            if (s.title.equals(showTitle, ignoreCase = true)) s.copy(title = "Épisodes") else s
        }
    }

    private fun JSONObject.str(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun clean(s: String): String = s.replace(Regex("\\s+"), " ").trim()
}
