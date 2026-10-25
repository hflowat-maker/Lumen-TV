package app.lumen.tv.data

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStreamReader
import java.net.URLEncoder

// ---------- Lecture JSON tolérante (les panels Xtream mélangent chaînes, nombres et null) ----------

internal fun JsonReader.str(): String? = when (peek()) {
    JsonToken.NULL -> { nextNull(); null }
    JsonToken.STRING, JsonToken.NUMBER -> nextString()
    JsonToken.BOOLEAN -> nextBoolean().toString()
    else -> { skipValue(); null }
}

internal fun JsonReader.int(): Int {
    val s = str()?.trim() ?: return 0
    return s.toIntOrNull() ?: s.toDoubleOrNull()?.toInt() ?: 0
}

internal fun JsonReader.long(): Long {
    val s = str()?.trim() ?: return 0
    return s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong() ?: 0
}

internal fun JsonReader.double(): Double = str()?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: 0.0

internal fun JsonReader.strList(): List<String> = when (peek()) {
    JsonToken.BEGIN_ARRAY -> {
        val out = ArrayList<String>()
        beginArray()
        while (hasNext()) str()?.takeIf { it.isNotBlank() }?.let(out::add)
        endArray()
        out
    }
    JsonToken.STRING -> listOfNotNull(nextString().takeIf { it.isNotBlank() })
    else -> { skipValue(); emptyList() }
}

internal inline fun JsonReader.obj(block: (String) -> Unit) {
    if (peek() != JsonToken.BEGIN_OBJECT) { skipValue(); return }
    beginObject()
    while (hasNext()) block(nextName())
    endObject()
}

internal inline fun JsonReader.arr(block: () -> Unit) {
    if (peek() != JsonToken.BEGIN_ARRAY) { skipValue(); return }
    beginArray()
    while (hasNext()) block()
    endArray()
}

private fun String?.orNullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

private fun image(url: String?): String? {
    val u = url.orNullIfBlank() ?: return null
    return if (u.startsWith("http", ignoreCase = true)) u else null
}

// ---------- Client Xtream Codes ----------

class XtreamApi(private val http: OkHttpClient) {

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun api(acc: Account, action: String? = null, extra: String = ""): String {
        val base = "${acc.server}/player_api.php?username=${enc(acc.username)}&password=${enc(acc.password)}"
        return if (action == null) base else "$base&action=$action$extra"
    }

    fun liveUrl(acc: Account, id: Int, format: String): String =
        "${acc.server}/live/${enc(acc.username)}/${enc(acc.password)}/$id.$format"

    fun movieUrl(acc: Account, id: Int, ext: String): String =
        "${acc.server}/movie/${enc(acc.username)}/${enc(acc.password)}/$id.${ext.ifBlank { "mp4" }}"

    fun episodeUrl(acc: Account, id: String, ext: String): String =
        "${acc.server}/series/${enc(acc.username)}/${enc(acc.password)}/$id.${ext.ifBlank { "mp4" }}"

    private suspend fun <T> get(url: String, parse: (JsonReader) -> T): T = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        try {
            http.newCall(request).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) throw XtreamException("Accès refusé par le serveur (${resp.code})")
                if (!resp.isSuccessful) throw XtreamException("Le serveur a répondu ${resp.code}")
                val body = resp.body ?: throw XtreamException("Réponse vide du serveur")
                JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                    reader.isLenient = true
                    if (reader.peek() == JsonToken.END_DOCUMENT) throw XtreamException("Réponse vide du serveur")
                    parse(reader)
                }
            }
        } catch (e: XtreamException) {
            throw e
        } catch (e: android.util.MalformedJsonException) {
            throw XtreamException("Réponse illisible du serveur")
        } catch (e: IOException) {
            throw XtreamException("Serveur injoignable : ${e.message ?: e.javaClass.simpleName}")
        } catch (e: IllegalStateException) {
            throw XtreamException("Réponse illisible du serveur")
        } catch (e: IllegalArgumentException) {
            throw XtreamException("Adresse du serveur invalide")
        }
    }

    suspend fun login(acc: Account): AccountInfo = get(api(acc)) { r ->
        var auth = 0
        var status = ""
        var exp: Long? = null
        var max = 0
        var active = 0
        var trial = false
        var formats = emptyList<String>()
        var message: String? = null
        var siUrl: String? = null
        var siPort: String? = null
        var siHttpsPort: String? = null
        var siProto: String? = null
        if (r.peek() != JsonToken.BEGIN_OBJECT) throw XtreamException("Identifiants refusés")
        r.obj { name ->
            when (name) {
                "user_info" -> r.obj { n ->
                    when (n) {
                        "auth" -> auth = r.int()
                        "status" -> status = r.str().orEmpty()
                        "exp_date" -> exp = r.long().takeIf { it > 0 }?.times(1000)
                        "max_connections" -> max = r.int()
                        "active_cons" -> active = r.int()
                        "is_trial" -> trial = r.int() == 1
                        "allowed_output_formats" -> formats = r.strList()
                        "message" -> message = r.str()
                        else -> r.skipValue()
                    }
                }
                "server_info" -> r.obj { n ->
                    when (n) {
                        "url" -> siUrl = r.str().orNullIfBlank()
                        "port" -> siPort = r.str().orNullIfBlank()
                        "https_port" -> siHttpsPort = r.str().orNullIfBlank()
                        "server_protocol" -> siProto = r.str().orNullIfBlank()
                        else -> r.skipValue()
                    }
                }
                else -> r.skipValue()
            }
        }
        if (auth != 1) throw XtreamException(message.orNullIfBlank() ?: "Identifiant ou mot de passe incorrect")
        if (status.isNotBlank() && !status.equals("Active", ignoreCase = true)) {
            throw XtreamException("Abonnement inactif ($status)")
        }
        val streamBase = siUrl?.let { host ->
            val proto = if (siProto.equals("https", ignoreCase = true)) "https" else "http"
            val port = (if (proto == "https") siHttpsPort else siPort)?.takeIf { it != "0" }
            val h = host.removePrefix("http://").removePrefix("https://").trimEnd('/')
            "$proto://$h" + (port?.let { ":$it" } ?: "")
        }
        AccountInfo(status.ifBlank { "Active" }, exp, max, active, trial, formats, streamBase)
    }

    suspend fun categories(acc: Account, type: ContentType): List<Category> {
        val action = when (type) {
            ContentType.LIVE -> "get_live_categories"
            ContentType.MOVIE -> "get_vod_categories"
            ContentType.SERIES -> "get_series_categories"
        }
        return get(api(acc, action)) { r ->
            val out = ArrayList<Category>()
            r.arr {
                var id: String? = null
                var name: String? = null
                r.obj { n ->
                    when (n) {
                        "category_id" -> id = r.str()
                        "category_name" -> name = r.str()
                        else -> r.skipValue()
                    }
                }
                val cid = id.orNullIfBlank()
                if (cid != null) out.add(Category(type, cid, name.orNullIfBlank() ?: "Sans nom"))
            }
            out
        }
    }

    suspend fun liveStreams(acc: Account): List<Channel> = get(api(acc, "get_live_streams")) { r ->
        val out = ArrayList<Channel>(4096)
        r.arr {
            var id = 0
            var num = 0
            var name = ""
            var icon: String? = null
            var cat = ""
            var epg: String? = null
            var archive = false
            var adult = false
            r.obj { n ->
                when (n) {
                    "stream_id" -> id = r.int()
                    "num" -> num = r.int()
                    "name" -> name = r.str().orEmpty()
                    "stream_icon" -> icon = image(r.str())
                    "category_id" -> cat = r.str().orEmpty()
                    "epg_channel_id" -> epg = r.str().orNullIfBlank()
                    "tv_archive" -> archive = r.int() == 1
                    "is_adult" -> adult = r.int() == 1
                    else -> r.skipValue()
                }
            }
            if (id > 0) out.add(Channel(id, num, name.trim(), icon, cat, epg, archive, adult))
        }
        out
    }

    suspend fun vodStreams(acc: Account): List<Tile> = get(api(acc, "get_vod_streams")) { r ->
        val out = ArrayList<Tile>(8192)
        r.arr {
            var id = 0
            var name = ""
            var icon: String? = null
            var cat = ""
            var rating = 0.0
            var rating5 = 0.0
            var added = 0L
            var ext = "mp4"
            var year: String? = null
            r.obj { n ->
                when (n) {
                    "stream_id" -> id = r.int()
                    "name" -> name = r.str().orEmpty()
                    "stream_icon" -> icon = image(r.str())
                    "category_id" -> cat = r.str().orEmpty()
                    "rating" -> rating = r.double()
                    "rating_5based" -> rating5 = r.double()
                    "added" -> added = r.long()
                    "container_extension" -> ext = r.str().orNullIfBlank() ?: "mp4"
                    "year" -> year = Text.yearOf(r.str())
                    else -> r.skipValue()
                }
            }
            if (id > 0) {
                val score = if (rating > 0) rating else rating5 * 2
                out.add(
                    Tile(
                        type = ContentType.MOVIE, id = id, title = name.trim(), image = icon,
                        rating = score, year = year, ext = ext, categoryId = cat, added = added,
                    )
                )
            }
        }
        out
    }

    suspend fun series(acc: Account): List<Tile> = get(api(acc, "get_series")) { r ->
        val out = ArrayList<Tile>(4096)
        r.arr {
            var id = 0
            var name = ""
            var cover: String? = null
            var backdrop: String? = null
            var cat = ""
            var rating = 0.0
            var rating5 = 0.0
            var plot: String? = null
            var genre: String? = null
            var year: String? = null
            var modified = 0L
            r.obj { n ->
                when (n) {
                    "series_id" -> id = r.int()
                    "name" -> name = r.str().orEmpty()
                    "cover" -> cover = image(r.str())
                    "backdrop_path" -> backdrop = r.strList().firstNotNullOfOrNull { image(it) }
                    "category_id" -> cat = r.str().orEmpty()
                    "rating" -> rating = r.double()
                    "rating_5based" -> rating5 = r.double()
                    "plot" -> plot = r.str().orNullIfBlank()
                    "genre" -> genre = r.str().orNullIfBlank()
                    "releaseDate", "release_date" -> { val v = Text.yearOf(r.str()); if (year == null) year = v }
                    "last_modified" -> modified = r.long()
                    else -> r.skipValue()
                }
            }
            if (id > 0) {
                out.add(
                    Tile(
                        type = ContentType.SERIES, id = id, title = name.trim(), image = cover,
                        backdrop = backdrop, rating = if (rating > 0) rating else rating5 * 2,
                        year = year, plot = plot, genre = genre, categoryId = cat, added = modified,
                    )
                )
            }
        }
        out
    }

    suspend fun movieInfo(acc: Account, id: Int): MovieInfo = get(api(acc, "get_vod_info", "&vod_id=$id")) { r ->
        var name = ""
        var poster: String? = null
        var backdrop: String? = null
        var plot: String? = null
        var genre: String? = null
        var director: String? = null
        var cast: String? = null
        var year: String? = null
        var duration = 0
        var durationText: String? = null
        var rating = 0.0
        var trailer: String? = null
        var ext = "mp4"
        r.obj { n ->
            when (n) {
                "info" -> r.obj { k ->
                    when (k) {
                        "name", "o_name", "title" -> { val v = r.str().orNullIfBlank(); if (name.isBlank() && v != null) name = v }
                        "movie_image", "cover_big" -> { val v = image(r.str()); if (poster == null) poster = v }
                        "backdrop_path" -> backdrop = r.strList().firstNotNullOfOrNull { image(it) }
                        "plot", "description" -> { val v = r.str().orNullIfBlank(); if (plot == null) plot = v }
                        "genre" -> genre = r.str().orNullIfBlank()
                        "director" -> director = r.str().orNullIfBlank()
                        "cast", "actors" -> { val v = r.str().orNullIfBlank(); if (cast == null) cast = v }
                        "releasedate", "release_date", "year" -> { val v = Text.yearOf(r.str()); if (year == null) year = v }
                        "duration_secs" -> duration = r.int()
                        "duration" -> durationText = r.str()
                        "rating" -> rating = r.double()
                        "youtube_trailer" -> trailer = r.str().orNullIfBlank()
                        else -> r.skipValue()
                    }
                }
                "movie_data" -> r.obj { k ->
                    when (k) {
                        "name" -> { val v = r.str().orNullIfBlank(); if (v != null) name = v }
                        "container_extension" -> ext = r.str().orNullIfBlank() ?: ext
                        else -> r.skipValue()
                    }
                }
                else -> r.skipValue()
            }
        }
        if (duration <= 0) duration = parseHms(durationText)
        MovieInfo(id, name, poster, backdrop, plot, genre, director, cast, year, duration, rating, trailer, ext)
    }

    suspend fun seriesInfo(acc: Account, id: Int): SeriesInfo = get(api(acc, "get_series_info", "&series_id=$id")) { r ->
        var name = ""
        var cover: String? = null
        var backdrop: String? = null
        var plot: String? = null
        var genre: String? = null
        var cast: String? = null
        var director: String? = null
        var year: String? = null
        var rating = 0.0
        var trailer: String? = null
        val episodes = ArrayList<Episode>()
        val seasonNumbers = sortedSetOf<Int>()

        fun readEpisode(defaultSeason: Int) {
            var eid: String? = null
            var num = 0
            var title = ""
            var ext = "mp4"
            var season = defaultSeason
            var eplot: String? = null
            var img: String? = null
            var dur = 0
            var durText: String? = null
            r.obj { k ->
                when (k) {
                    "id" -> eid = r.str()
                    "episode_num" -> num = r.int()
                    "title" -> title = r.str().orEmpty()
                    "container_extension" -> ext = r.str().orNullIfBlank() ?: "mp4"
                    "season" -> r.int().takeIf { it > 0 }?.let { season = it }
                    "info" -> r.obj { i ->
                        when (i) {
                            "movie_image", "cover_big" -> { val v = image(r.str()); if (img == null) img = v }
                            "plot" -> eplot = r.str().orNullIfBlank()
                            "duration_secs" -> dur = r.int()
                            "duration" -> durText = r.str()
                            else -> r.skipValue()
                        }
                    }
                    else -> r.skipValue()
                }
            }
            val episodeId = eid.orNullIfBlank() ?: return
            if (dur <= 0) dur = parseHms(durText)
            seasonNumbers.add(season)
            episodes.add(Episode(episodeId, season, num, title, eplot, img, dur, ext))
        }

        r.obj { n ->
            when (n) {
                "info" -> r.obj { k ->
                    when (k) {
                        "name" -> name = r.str().orEmpty()
                        "cover" -> cover = image(r.str())
                        "backdrop_path" -> backdrop = r.strList().firstNotNullOfOrNull { image(it) }
                        "plot" -> plot = r.str().orNullIfBlank()
                        "genre" -> genre = r.str().orNullIfBlank()
                        "cast" -> cast = r.str().orNullIfBlank()
                        "director" -> director = r.str().orNullIfBlank()
                        "releaseDate", "release_date" -> { val v = Text.yearOf(r.str()); if (year == null) year = v }
                        "rating" -> rating = r.double()
                        "youtube_trailer" -> trailer = r.str().orNullIfBlank()
                        else -> r.skipValue()
                    }
                }
                "episodes" -> when (r.peek()) {
                    JsonToken.BEGIN_OBJECT -> r.obj { seasonKey ->
                        val s = seasonKey.trim().toIntOrNull() ?: 1
                        when (r.peek()) {
                            JsonToken.BEGIN_ARRAY -> r.arr { readEpisode(s) }
                            JsonToken.BEGIN_OBJECT -> r.obj { _ -> readEpisode(s) }
                            else -> r.skipValue()
                        }
                    }
                    JsonToken.BEGIN_ARRAY -> r.arr {
                        when (r.peek()) {
                            JsonToken.BEGIN_ARRAY -> r.arr { readEpisode(1) }
                            JsonToken.BEGIN_OBJECT -> readEpisode(1)
                            else -> r.skipValue()
                        }
                    }
                    else -> r.skipValue()
                }
                else -> r.skipValue()
            }
        }
        val sorted = episodes.distinctBy { it.id }.sortedWith(compareBy({ it.season }, { it.number }))
        SeriesInfo(
            id, name, cover, backdrop, plot, genre, cast, director, year, rating, trailer,
            seasonNumbers.toList(), sorted,
        )
    }

    suspend fun shortEpg(acc: Account, streamId: Int, limit: Int = 6): List<EpgEntry> =
        get(api(acc, "get_short_epg", "&stream_id=$streamId&limit=$limit")) { r ->
            val out = ArrayList<EpgEntry>()
            r.obj { n ->
                if (n == "epg_listings") {
                    r.arr {
                        var title: String? = null
                        var desc: String? = null
                        var start = 0L
                        var stop = 0L
                        r.obj { k ->
                            when (k) {
                                "title" -> title = r.str()
                                "description" -> desc = r.str()
                                "start_timestamp" -> start = r.long() * 1000
                                "stop_timestamp", "end_timestamp" -> stop = r.long() * 1000
                                else -> r.skipValue()
                            }
                        }
                        val t = decode(title)
                        if (!t.isNullOrBlank() && start > 0 && stop > start) {
                            out.add(EpgEntry(t, decode(desc)?.takeIf { it.isNotBlank() }, start, stop))
                        }
                    }
                } else {
                    r.skipValue()
                }
            }
            out.sortedBy { it.start }
        }

    private val base64Like = Regex("^[A-Za-z0-9+/=\\r\\n]+$")

    private fun decode(s: String?): String? {
        if (s.isNullOrBlank()) return null
        if (!base64Like.matches(s)) return s
        return try {
            String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (e: IllegalArgumentException) {
            s
        }
    }

    private fun parseHms(s: String?): Int {
        if (s.isNullOrBlank()) return 0
        val parts = s.trim().split(":").mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0] * 60
            else -> 0
        }
    }
}
