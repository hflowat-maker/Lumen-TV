package app.lumen.tv.data

import androidx.compose.runtime.Immutable

enum class ContentType(val key: String) {
    LIVE("live"), MOVIE("movie"), SERIES("series");

    companion object {
        fun of(key: String): ContentType = entries.firstOrNull { it.key == key } ?: MOVIE
    }
}

@Immutable
data class Account(val server: String, val username: String, val password: String)

@Immutable
data class AccountInfo(
    val status: String,
    val expDate: Long?,
    val maxConnections: Int,
    val activeConnections: Int,
    val isTrial: Boolean,
    val formats: List<String>,
)

@Immutable
data class Category(
    val type: ContentType,
    val id: String,
    val name: String,
    val count: Int = 0,
    val adult: Boolean = false,
)

@Immutable
data class Channel(
    val id: Int,
    val num: Int,
    val name: String,
    val icon: String?,
    val categoryId: String,
    val epgId: String?,
    val hasArchive: Boolean,
    val adult: Boolean = false,
)

/** Élément affichable en carte (film ou série). */
@Immutable
data class Tile(
    val type: ContentType,
    val id: Int,
    val title: String,
    val image: String?,
    val backdrop: String? = null,
    val rating: Double = 0.0,
    val year: String? = null,
    val plot: String? = null,
    val genre: String? = null,
    val ext: String = "mp4",
    val categoryId: String = "",
    val added: Long = 0,
) {
    val key: String get() = "${type.key}:$id"
}

@Immutable
data class MovieInfo(
    val id: Int,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val plot: String?,
    val genre: String?,
    val director: String?,
    val cast: String?,
    val year: String?,
    val durationSecs: Int,
    val rating: Double,
    val trailer: String?,
    val ext: String,
)

@Immutable
data class Episode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val plot: String?,
    val image: String?,
    val durationSecs: Int,
    val ext: String,
) {
    val label: String get() = "S$season:É$number"
}

@Immutable
data class SeriesInfo(
    val id: Int,
    val name: String,
    val cover: String?,
    val backdrop: String?,
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val director: String?,
    val year: String?,
    val rating: Double,
    val trailer: String?,
    val seasons: List<Int>,
    val episodes: List<Episode>,
)

@Immutable
data class EpgEntry(
    val title: String,
    val description: String?,
    val start: Long,
    val end: Long,
) {
    fun progress(now: Long): Float =
        if (end <= start) 0f else ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f)
}

@Immutable
data class Profile(
    val id: Long,
    val name: String,
    val color: Int,
    val isKid: Boolean,
    val pin: String?,
)

object ProgressKind {
    const val MOVIE = "movie"
    const val EPISODE = "episode"
}

@Immutable
data class WatchProgress(
    val kind: String,
    val itemId: String,
    val seriesId: Int,
    val season: Int,
    val episode: Int,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val position: Long,
    val duration: Long,
    val ext: String,
    val updated: Long,
    val isNext: Boolean,
) {
    val key: String get() = "$kind:$itemId"
    val fraction: Float get() = if (duration <= 0) 0f else (position.toFloat() / duration).coerceIn(0f, 1f)
    val finished: Boolean get() = duration > 0 && position >= duration * 0.94
}

/** Filtre de contenu appliqué selon le profil actif. */
@Immutable
data class ContentFilter(val kid: Boolean, val hideAdult: Boolean)

sealed interface PlayRequest {
    data class Live(val channels: List<Channel>, val index: Int) : PlayRequest
    data class Movie(val tile: Tile, val startMs: Long) : PlayRequest
    data class Episodes(
        val series: Tile,
        val episodes: List<Episode>,
        val index: Int,
        val startMs: Long,
    ) : PlayRequest
}

class XtreamException(message: String) : Exception(message)
