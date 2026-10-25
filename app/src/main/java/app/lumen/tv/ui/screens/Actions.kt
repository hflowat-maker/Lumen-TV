package app.lumen.tv.ui.screens

import android.content.Context
import android.widget.Toast
import app.lumen.tv.data.Channel
import app.lumen.tv.data.ContentType
import app.lumen.tv.data.PlayRequest
import app.lumen.tv.data.ProgressKind
import app.lumen.tv.data.Tile
import app.lumen.tv.data.WatchProgress
import app.lumen.tv.ui.AppController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun AppController.toggleFavorite(context: Context, type: ContentType, id: Int) {
    val added = repo.toggleFavorite(type, id)
    Toast.makeText(context, if (added) "Ajouté à Ma liste" else "Retiré de Ma liste", Toast.LENGTH_SHORT).show()
}

/** Reprend un film ou un épisode là où il s'était arrêté. */
fun AppController.resume(p: WatchProgress, scope: CoroutineScope, context: Context) {
    scope.launch {
        if (p.kind == ProgressKind.MOVIE) {
            val id = p.itemId.toIntOrNull() ?: return@launch
            val tile = repo.tile(ContentType.MOVIE, id) ?: Tile(ContentType.MOVIE, id, p.title, p.image, ext = p.ext)
            play(PlayRequest.Movie(tile, if (p.finished) 0 else p.position))
        } else {
            val series = repo.tile(ContentType.SERIES, p.seriesId) ?: Tile(ContentType.SERIES, p.seriesId, p.title, p.image)
            val info = try {
                repo.seriesInfo(p.seriesId, series.title)
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Série indisponible", Toast.LENGTH_LONG).show()
                return@launch
            }
            val idx = info.episodes.indexOfFirst { it.id == p.itemId }
            if (idx < 0) {
                open(series)
                return@launch
            }
            play(PlayRequest.Episodes(series, info.episodes, idx, if (p.isNext || p.finished) 0 else p.position))
        }
    }
}

fun AppController.playChannel(channels: List<Channel>, channel: Channel) {
    val idx = channels.indexOfFirst { it.id == channel.id }.coerceAtLeast(0)
    play(PlayRequest.Live(channels, idx))
}
