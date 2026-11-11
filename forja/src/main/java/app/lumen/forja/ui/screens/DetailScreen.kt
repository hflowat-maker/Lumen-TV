package app.lumen.forja.ui.screens

import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.lumen.forja.data.Detail
import app.lumen.forja.data.Episode
import app.lumen.forja.data.ForjaApi
import app.lumen.forja.data.Item
import app.lumen.forja.data.Progress
import app.lumen.forja.data.Store
import app.lumen.forja.data.Txt
import app.lumen.forja.ui.AppController
import app.lumen.forja.ui.components.CardSize
import app.lumen.forja.ui.components.Chip
import app.lumen.forja.ui.components.MetaLine
import app.lumen.forja.ui.components.PillButton
import app.lumen.forja.ui.components.PosterCard
import app.lumen.forja.ui.components.RoundIconButton
import app.lumen.forja.ui.components.RowHeader
import app.lumen.forja.ui.components.Safe
import app.lumen.forja.ui.components.Skeleton
import app.lumen.forja.ui.components.SkeletonRow
import app.lumen.forja.ui.components.WideCard
import app.lumen.forja.ui.components.rememberFocusMemory
import app.lumen.forja.ui.components.rememberStartPivot
import app.lumen.forja.ui.components.remembered
import app.lumen.forja.ui.components.restoreFocus
import app.lumen.forja.ui.theme.C
import app.lumen.forja.ui.theme.T
import coil.compose.AsyncImage

@Composable
private fun DetailBackdrop(url: String?) {
    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = url, animationSpec = tween(400), label = "detailBackdrop") { u ->
            if (u != null) {
                AsyncImage(model = u, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to C.Bg, 0.38f to C.Bg.copy(alpha = 0.92f), 0.7f to C.Bg.copy(alpha = 0.4f), 1f to C.Bg.copy(alpha = 0.15f))
            )
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Transparent, 1f to C.Bg)))
    }
}

/** Épisode à proposer en premier : celui en cours, ou le suivant du dernier terminé. */
private fun resumeTarget(episodes: List<Episode>, last: Progress?): Pair<Int, Long>? {
    if (last == null) return null
    val idx = episodes.indexOfFirst { it.id == last.id }
    if (idx < 0) return null
    return when {
        !last.finished -> idx to last.position
        idx + 1 < episodes.size -> (idx + 1) to 0L
        else -> null
    }
}

@Composable
fun DetailScreen(app: AppController, item: Item) {
    val context = LocalContext.current
    val memory = rememberFocusMemory()
    val favorites by Store.favorites.collectAsState()
    val progressMap by Store.progress.collectAsState()
    var detail by remember { mutableStateOf<Detail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var season by rememberSaveable { mutableIntStateOf(-1) }

    LaunchedEffect(item.slug, reload) {
        error = null
        try {
            detail = ForjaApi.detail(item.slug)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Forja ne répond pas. Vérifie la connexion de la TV."
        }
    }

    val d = detail
    // Infos de la fiche quand elles sont là, sinon celles de la carte.
    val show = d?.item?.let {
        it.copy(
            poster = it.poster ?: item.poster,
            wide = it.wide ?: item.wide,
            tile = it.tile ?: item.tile,
            description = it.description ?: item.description,
        )
    } ?: item
    val isSeries = show.isSeries
    val episodes = d?.allEpisodes.orEmpty()
    val last = if (isSeries) Store.lastOfShow(show.slug, progressMap) else null
    val target = if (isSeries) resumeTarget(episodes, last) else null
    val filmProgress = if (!isSeries) progressMap[show.id]?.takeIf { !it.finished && it.position > 30 } else null
    if (d != null && season == -1) {
        season = target?.let { t -> d.seasons.indexOfFirst { s -> s.episodes.any { it.id == episodes[t.first].id } } }?.takeIf { it >= 0 } ?: 0
    }
    val fav = Store.isFavorite(show.slug, favorites)

    fun toggleFav() {
        val added = Store.toggleFavorite(show)
        Toast.makeText(context, if (added) "Ajouté à Ma liste" else "Retiré de Ma liste", Toast.LENGTH_SHORT).show()
    }

    Box(Modifier.fillMaxSize()) {
        DetailBackdrop(show.wide ?: show.tile ?: show.poster)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(start = Safe.h, top = Safe.v * 2.2f).width(600.dp)) {
                Text(show.title, style = T.hero.copy(fontSize = 46.sp, lineHeight = 48.sp), color = C.Text1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                val seasonsLabel = d?.seasons?.size?.takeIf { isSeries && it > 0 }?.let { if (it > 1) "$it saisons" else Txt.count(episodes.size, "épisode", "épisodes") }
                MetaLine(
                    listOf(
                        if (isSeries) "Série" else null,
                        seasonsLabel,
                        if (!isSeries) Txt.duration(show.durationSecs) else null,
                        d?.genres?.take(2)?.joinToString(", "),
                    ),
                    badge = if (!show.free) "ABONNÉS" else null,
                )
                Spacer(Modifier.height(14.dp))
                val plot = show.description?.takeIf { it.isNotBlank() && !it.equals(show.title, ignoreCase = true) }
                if (plot != null) {
                    Text(plot, style = T.body.copy(fontSize = T.body.fontSize * 1.05f), color = C.Text1.copy(alpha = 0.88f), maxLines = 4, overflow = TextOverflow.Ellipsis)
                } else if (d == null && error == null) {
                    Skeleton(Modifier.width(520.dp).height(60.dp))
                }
                d?.cast?.takeIf { it.isNotEmpty() }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("Avec : ${it.take(6).joinToString(", ")}", style = T.meta, color = C.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        error != null && d == null -> PillButton(
                            "Réessayer", icon = Icons.Filled.Refresh, primary = true,
                            onClick = { reload++ },
                            modifier = Modifier.remembered(memory, "retry", first = true),
                        )
                        d == null -> Skeleton(Modifier.width(180.dp).height(44.dp))
                        isSeries && episodes.isNotEmpty() -> {
                            val (idx, start) = target ?: (0 to 0L)
                            val ep = episodes[idx]
                            PillButton(
                                (if (target != null && start > 0) "Reprendre · " else "Lecture · ") + Txt.episodeLabel(ep.number, ep.title),
                                icon = Icons.Filled.PlayArrow, primary = true,
                                onClick = { app.playEpisodes(show, episodes, idx, start) },
                                modifier = Modifier.remembered(memory, "play", first = true),
                            )
                            if (target != null && (idx > 0 || start > 0)) {
                                PillButton(
                                    "Épisode 1", icon = Icons.Filled.Replay,
                                    onClick = { app.playEpisodes(show, episodes, 0, 0) },
                                    modifier = Modifier.remembered(memory, "first"),
                                )
                            }
                        }
                        isSeries -> Text("Aucun épisode disponible pour le moment.", style = T.bodyStrong, color = C.Text2)
                        filmProgress != null -> {
                            PillButton(
                                "Reprendre · ${Txt.remaining(filmProgress.duration - filmProgress.position)}",
                                icon = Icons.Filled.PlayArrow, primary = true,
                                onClick = { app.playFilm(show, filmProgress.position) },
                                modifier = Modifier.remembered(memory, "play", first = true),
                            )
                            PillButton(
                                "Depuis le début", icon = Icons.Filled.Replay,
                                onClick = { app.playFilm(show, 0) },
                                modifier = Modifier.remembered(memory, "restart"),
                            )
                        }
                        else -> PillButton(
                            "Lecture", icon = Icons.Filled.PlayArrow, primary = true,
                            onClick = { app.playFilm(show, 0) },
                            modifier = Modifier.remembered(memory, "play", first = true),
                        )
                    }
                    RoundIconButton(
                        if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        onClick = { toggleFav() },
                        active = fav,
                        modifier = Modifier.remembered(memory, "fav"),
                    )
                }
                if (error != null && d == null) {
                    Spacer(Modifier.height(10.dp))
                    Text(error ?: "", style = T.bodyStrong, color = C.Live)
                }
            }
            Spacer(Modifier.height(28.dp))

            if (d != null && isSeries && d.seasons.isNotEmpty()) {
                val s = season.coerceIn(0, d.seasons.lastIndex)
                if (d.seasons.size > 1) {
                    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberStartPivot(Safe.h)) {
                        LazyRow(
                            modifier = Modifier.restoreFocus(),
                            contentPadding = PaddingValues(horizontal = Safe.h, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(d.seasons.size) { i ->
                                Chip(
                                    d.seasons[i].title, selected = i == s,
                                    onClick = { season = i },
                                    onFocus = { season = i },
                                    modifier = Modifier.remembered(memory, "season/$i"),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                } else {
                    RowHeader(Txt.count(episodes.size, "épisode", "épisodes"))
                }
                val list = d.seasons[s].episodes
                CompositionLocalProvider(LocalBringIntoViewSpec provides rememberStartPivot(Safe.h)) {
                    LazyRow(
                        modifier = Modifier.restoreFocus(),
                        contentPadding = PaddingValues(start = Safe.h, end = Safe.h, top = 8.dp, bottom = 30.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(list, key = { it.id }) { ep ->
                            val p = progressMap[ep.id]
                            val index = episodes.indexOf(ep)
                            WideCard(
                                title = Txt.episodeLabel(ep.number, ep.title),
                                subtitle = Txt.duration(ep.durationSecs),
                                image = ep.image ?: show.tile ?: show.wide,
                                progress = p?.let { if (it.finished) 1f else it.fraction },
                                badge = if (p?.finished == true) "VU" else null,
                                onClick = {
                                    val start = if (p != null && !p.finished) p.position else 0L
                                    app.playEpisodes(show, episodes, index, start)
                                },
                                modifier = Modifier.remembered(memory, "ep/${ep.id}"),
                            )
                        }
                    }
                }
            } else if (d == null && error == null && isSeries) {
                SkeletonRow(CardSize.wideW, 16f / 9f, count = 4)
            }

            val similar = d?.similar.orEmpty()
            if (similar.isNotEmpty()) {
                RowHeader("Dans le même esprit")
                CompositionLocalProvider(LocalBringIntoViewSpec provides rememberStartPivot(Safe.h)) {
                    LazyRow(
                        modifier = Modifier.restoreFocus(),
                        contentPadding = PaddingValues(start = Safe.h, end = Safe.h, top = 8.dp, bottom = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(similar, key = { it.key }) { t ->
                            PosterCard(
                                title = t.title, image = t.poster ?: t.tile,
                                favorite = Store.isFavorite(t.slug, favorites),
                                onClick = { app.open(t) },
                                onLongClick = {
                                    val added = Store.toggleFavorite(t)
                                    Toast.makeText(context, if (added) "Ajouté à Ma liste" else "Retiré de Ma liste", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.remembered(memory, "sim/${t.key}"),
                            )
                        }
                    }
                }
            }
        }
    }
}
