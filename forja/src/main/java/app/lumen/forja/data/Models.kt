package app.lumen.forja.data

/** Un titre du catalogue Forja : série (« playlist ») ou vidéo seule (film, pièce, documentaire). */
data class Item(
    val id: Int,
    val slug: String,
    val isSeries: Boolean,
    val title: String,
    val description: String?,
    /** Affiche verticale (274 × 407). */
    val poster: String?,
    /** Grande image horizontale 16:9 (1920 × 1080). */
    val wide: String?,
    /** Vignette horizontale (330 × 200). */
    val tile: String?,
    val durationSecs: Int,
    val free: Boolean,
) {
    val key: String get() = "i$id"
}

data class Episode(
    val id: Int,
    val slug: String,
    val title: String,
    val number: Int,
    val durationSecs: Int,
    val image: String?,
)

data class Season(val title: String, val episodes: List<Episode>)

data class Detail(
    val item: Item,
    val seasons: List<Season>,
    val genres: List<String>,
    val cast: List<String>,
    val similar: List<Item>,
) {
    val allEpisodes: List<Episode> get() = seasons.flatMap { it.episodes }
}

data class Genre(val slug: String, val name: String)

/** Rubrique du menu (Séries, Films, Théâtre…) et ses genres. */
data class Category(val slug: String, val name: String, val genres: List<Genre>)

data class Page(val items: List<Item>, val page: Int, val lastPage: Int, val total: Int)

/** Une vidéo à lire dans le lecteur officiel (épisode ou film). */
data class Playable(
    val id: Int,
    val slug: String,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val durationSecs: Int,
)

/** Où l'on en est dans une vidéo, pour « Continuer à regarder ». Positions en secondes. */
data class Progress(
    val id: Int,
    val slug: String,
    /** Série d'origine (null pour un film). */
    val showSlug: String?,
    val showTitle: String,
    val episodeTitle: String?,
    val image: String?,
    val position: Long,
    val duration: Long,
    val updated: Long,
    /** Épisode suivant, s'il existe. */
    val next: Playable?,
) {
    val fraction: Float get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val finished: Boolean get() = duration > 0 && (position >= duration * 0.94 || duration - position < 90)
    val groupKey: String get() = showSlug ?: slug
}

/** Titre ajouté à « Ma liste ». */
data class Favorite(
    val id: Int,
    val slug: String,
    val isSeries: Boolean,
    val title: String,
    val poster: String?,
    val wide: String?,
    val description: String?,
    val added: Long,
) {
    fun toItem() = Item(id, slug, isSeries, title, description, poster, wide, null, 0, true)
}
