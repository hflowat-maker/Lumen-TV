package app.lumen.tv.data

import androidx.compose.runtime.Immutable

/** Onglets de l'écran principal. */
enum class Tab(val label: String) {
    HOME("Accueil"),
    LIVE("Direct"),
    MOVIES("Films"),
    SERIES("Séries"),
    MINE("Ma liste"),
}

/** Une étagère : un titre, une ligne d'explication et des cartes à parcourir de gauche à droite. */
@Immutable
data class Shelf(
    val key: String,
    val title: String,
    val subtitle: String?,
    val items: List<ShelfItem>,
)

@Immutable
sealed interface ShelfItem {
    val key: String
}

/** Film ou série, avec la langue de sa catégorie (pour le petit drapeau). */
@Immutable
data class TileItem(val tile: Tile, val lang: Lang?) : ShelfItem {
    override val key: String get() = tile.key
}

/** Film ou épisode commencé. */
@Immutable
data class ResumeItem(val progress: WatchProgress) : ShelfItem {
    override val key: String get() = "resume:${progress.key}"
}

/** Chaîne en direct (le zapping du lecteur reprend les chaînes de son étagère). */
@Immutable
data class ChannelItem(val channel: Channel, val lang: Lang?) : ShelfItem {
    override val key: String get() = "live:${channel.id}"
}

/** Dernière carte d'une étagère tronquée : ouvre la grille complète. */
@Immutable
data class SeeAllItem(val target: GridTarget, val total: Int) : ShelfItem {
    override val key: String get() = "all:${target.key}"
}

/** Contenu d'une grille complète (étagère « Tout voir »). */
@Immutable
data class GridTarget(
    val key: String,
    val type: ContentType,
    val title: String,
    val catIds: List<String>,
    val genreLike: List<String> = emptyList(),
    val langCatIds: List<String> = emptyList(),
)
