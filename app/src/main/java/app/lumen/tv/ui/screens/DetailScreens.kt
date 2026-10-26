package app.lumen.tv.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import app.lumen.tv.data.ContentType
import app.lumen.tv.data.Episode
import app.lumen.tv.data.MovieInfo
import app.lumen.tv.data.PlayRequest
import app.lumen.tv.data.ProgressKind
import app.lumen.tv.data.SeriesInfo
import app.lumen.tv.data.Text as Txt
import app.lumen.tv.data.Tile
import app.lumen.tv.data.WatchProgress
import app.lumen.tv.ui.AppController
import app.lumen.tv.ui.Screen
import app.lumen.tv.ui.components.CardSize
import app.lumen.tv.ui.components.Chip
import app.lumen.tv.ui.components.MetaLine
import app.lumen.tv.ui.components.PillButton
import app.lumen.tv.ui.components.PosterCard
import app.lumen.tv.ui.components.RoundIconButton
import app.lumen.tv.ui.components.RowHeader
import app.lumen.tv.ui.components.Safe
import app.lumen.tv.ui.components.Skeleton
import app.lumen.tv.ui.components.SkeletonRow
import app.lumen.tv.ui.components.WideCard
import app.lumen.tv.ui.components.rememberFocusMemory
import app.lumen.tv.ui.components.rememberStartPivot
import app.lumen.tv.ui.components.remembered
import app.lumen.tv.ui.components.restoreFocus
import app.lumen.tv.ui.theme.C
import app.lumen.tv.ui.theme.T
import coil.compose.AsyncImage

private fun openTrailer(context: Context, id: String) {
    val url = if (id.startsWith("http")) id else "https://www.youtube.com/watch?v=$id"
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "YouTube n'est pas installé sur cette TV", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun DetailBackdrop(url: String?) {
    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = url, animationSpec = tween(400), label = "detailBackdrop") { u ->
            if (u != null) {
                AsyncImage(
                    model = u, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to C.Bg, 0.38f to C.Bg.copy(alpha = 0.92f), 0.7f to C.Bg.copy(alpha = 0.4f), 1f to C.Bg.copy(alpha = 0.15f))
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Transparent, 1f to C.Bg)
            )
        )
    }
}

@Composable
fun MovieDetailScreen(app: AppController, tile: Tile) {
    val context = LocalContext.current
    val memory = rememberFocusMemory()
    val favorites by app.repo.favorites.collectAsState()
    val progressMap by app.repo.progress.collectAsState()
    val info by produceState<MovieInfo?>(app.repo.cachedMovieInfo(tile.id), tile.id) {
        if (value == null) value = runCatching { app.repo.movieInfo(tile.id) }.getOrNull()
    }
    val similar by produceState<List<Tile>?>(null, tile.id) {
        value = if (tile.categoryId.isBlank()) emptyList()
        else app.repo.tilesIn(ContentType.MOVIE, tile.categoryId, 24).filter { it.id != tile.id }
    }
    val progress = progressMap["${ProgressKind.MOVIE}:${tile.id}"]?.takeIf { !it.finished && it.position > 30_000 }
    val fav = tile.key in favorites
    val i = info
    val movie = tile.copy(
        ext = i?.ext ?: tile.ext,
        backdrop = i?.backdrop ?: tile.backdrop,
        image = tile.image ?: i?.poster,
    )

    Box(Modifier.fillMaxSize()) {
        DetailBackdrop(i?.backdrop ?: tile.backdrop ?: i?.poster ?: tile.image)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(start = Safe.h, top = Safe.v * 2.2f).width(600.dp)) {
                Text(tile.title, style = T.hero.copy(fontSize = 46.sp, lineHeight = 48.sp), color = C.Text1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                MetaLine(
                    listOf(i?.year ?: tile.year, i?.durationSecs?.let { Txt.durationLabel(it) }, i?.genre),
                    rating = Txt.rating(if ((i?.rating ?: 0.0) > 0) i!!.rating else tile.rating),
                )
                Spacer(Modifier.height(14.dp))
                if (i == null) {
                    Skeleton(Modifier.width(520.dp).height(60.dp))
                } else {
                    i.plot?.let { Text(it, style = T.body.copy(fontSize = T.body.fontSize * 1.05f), color = C.Text1.copy(alpha = 0.88f), maxLines = 5, overflow = TextOverflow.Ellipsis) }
                    Spacer(Modifier.height(10.dp))
                    i.director?.let { Text("Réalisation : $it", style = T.meta, color = C.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    i.cast?.let { Text("Avec : $it", style = T.meta, color = C.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (progress != null) {
                        PillButton(
                            "Reprendre · ${Txt.remaining(progress.duration - progress.position)}",
                            icon = Icons.Filled.PlayArrow, primary = true,
                            onClick = { app.play(PlayRequest.Movie(movie, progress.position)) },
                            modifier = Modifier.remembered(memory, "play", first = true),
                        )
                        PillButton(
                            "Depuis le début", icon = Icons.Filled.Replay,
                            onClick = { app.play(PlayRequest.Movie(movie, 0)) },
                            modifier = Modifier.remembered(memory, "restart"),
                        )
                    } else {
                        PillButton(
                            "Lecture", icon = Icons.Filled.PlayArrow, primary = true,
                            onClick = { app.play(PlayRequest.Movie(movie, 0)) },
                            modifier = Modifier.remembered(memory, "play", first = true),
                        )
                    }
                    RoundIconButton(
                        if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        onClick = { app.toggleFavorite(context, ContentType.MOVIE, tile.id) },
                        active = fav,
                        modifier = Modifier.remembered(memory, "fav"),
                    )
                    i?.trailer?.let { t ->
                        PillButton("Bande-annonce", icon = Icons.Filled.Theaters, onClick = { openTrailer(context, t) }, modifier = Modifier.remembered(memory, "trailer"))
                    }
                }
            }
            Spacer(Modifier.height(36.dp))
            val sim = similar
            if (sim == null) {
                SkeletonRow(CardSize.posterW, 2f / 3f)
            } else if (sim.isNotEmpty()) {
                RowHeader("Dans la même catégorie")
                CompositionLocalProvider(LocalBringIntoViewSpec provides rememberStartPivot(Safe.h)) {
                    LazyRow(
                        modifier = Modifier.restoreFocus(),
                        contentPadding = PaddingValues(start = Safe.h, end = Safe.h, top = 8.dp, bottom = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(sim, key = { it.key }) { t ->
                            PosterCard(
                                title = t.title, image = t.image,
                                favorite = t.key in favorites,
                                onClick = { app.push(Screen.Movie(t)) },
                                onLongClick = { app.toggleFavorite(context, t.type, t.id) },
                                modifier = Modifier.remembered(memory, "sim/${t.key}"),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Épisode à proposer en premier : celui en cours, ou le suivant du dernier terminé. */
private fun resumeTarget(info: SeriesInfo, progress: Map<String, WatchProgress>, seriesId: Int): Pair<Int, Long>? {
    val last = progress.values
        .filter { it.kind == ProgressKind.EPISODE && it.seriesId == seriesId }
        .maxByOrNull { it.updated } ?: return null
    val idx = info.episodes.indexOfFirst { it.id == last.itemId }
    if (idx < 0) return null
    return when {
        last.isNext -> idx to 0L
        last.finished -> if (idx + 1 < info.episodes.size) (idx + 1) to 0L else null
        else -> idx to last.position
    }
}

@Composable
fun SeriesDetailScreen(app: AppController, tile: Tile) {
    val context = LocalContext.current
    val memory = rememberFocusMemory()
    val favorites by app.repo.favorites.collectAsState()
    val progressMap by app.repo.progress.collectAsState()
    var info by remember { mutableStateOf(app.repo.cachedSeriesInfo(tile.id)) }
    var error by remember { mutableStateOf<String?>(null) }
    var season by rememberSaveable { mutableStateOf(-1) }
    var focusedEpisode by remember { mutableStateOf<Episode?>(null) }

    LaunchedEffect(tile.id) {
        try {
            info = app.repo.seriesInfo(tile.id, tile.title)
        } catch (e: Exception) {
            error = e.message ?: "Série indisponible"
        }
    }

    val i = info
    val target = i?.let { resumeTarget(it, progressMap, tile.id) }
    if (i != null && season == -1) {
        season = target?.let { i.episodes[it.first].season } ?: i.seasons.firstOrNull() ?: 1
    }
    val series = tile.copy(backdrop = i?.backdrop ?: tile.backdrop, image = tile.image ?: i?.cover)
    val fav = tile.key in favorites

    Box(Modifier.fillMaxSize()) {
        DetailBackdrop(i?.backdrop ?: tile.backdrop ?: tile.image ?: i?.cover)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(start = Safe.h, top = Safe.v * 2.2f).width(600.dp)) {
                Text(tile.title, style = T.hero.copy(fontSize = 46.sp, lineHeight = 48.sp), color = C.Text1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                val seasonsLabel = i?.seasons?.size?.let { if (it > 1) "$it saisons" else "1 saison" }
                MetaLine(listOf(i?.year ?: tile.year, seasonsLabel, i?.genre ?: tile.genre), rating = Txt.rating(if ((i?.rating ?: 0.0) > 0) i!!.rating else tile.rating))
                Spacer(Modifier.height(14.dp))
                val plot = i?.plot ?: tile.plot
                if (plot != null) Text(plot, style = T.body, color = C.Text1.copy(alpha = 0.88f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                i?.cast?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("Avec : $it", style = T.meta, color = C.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (i != null && i.episodes.isNotEmpty()) {
                        val (idx, start) = target ?: (0 to 0L)
                        val ep = i.episodes[idx]
                        PillButton(
                            (if (target != null && start > 0) "Reprendre " else "Lecture ") + ep.label,
                            icon = Icons.Filled.PlayArrow, primary = true,
                            onClick = { app.play(PlayRequest.Episodes(series, i.episodes, idx, start)) },
                            modifier = Modifier.remembered(memory, "play", first = true),
                        )
                    } else if (error != null) {
                        Text(error ?: "", style = T.bodyStrong, color = C.Live)
                    } else {
                        Skeleton(Modifier.width(180.dp).height(44.dp))
                    }
                    RoundIconButton(
                        if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        onClick = { app.toggleFavorite(context, ContentType.SERIES, tile.id) },
                        active = fav,
                        modifier = Modifier.remembered(memory, "fav", first = error != null),
                    )
                    i?.trailer?.let { t ->
                        PillButton("Bande-annonce", icon = Icons.Filled.Theaters, onClick = { openTrailer(context, t) }, modifier = Modifier.remembered(memory, "trailer"))
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            if (i != null && i.episodes.isNotEmpty()) {
                if (i.seasons.size > 1) {
                    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberStartPivot(Safe.h)) {
                        LazyRow(
                            modifier = Modifier.restoreFocus(),
                            contentPadding = PaddingValues(horizontal = Safe.h, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(i.seasons, key = { it }) { s ->
                                Chip(
                                    "Saison $s", selected = s == season,
                                    onClick = { season = s },
                                    onFocus = { season = s },
                                    modifier = Modifier.remembered(memory, "season/$s"),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                } else {
                    RowHeader("Épisodes")
                }
                val episodes = i.episodes.filter { it.season == season }
                CompositionLocalProvider(LocalBringIntoViewSpec provides rememberStartPivot(Safe.h)) {
                    LazyRow(
                        modifier = Modifier.restoreFocus(),
                        contentPadding = PaddingValues(start = Safe.h, end = Safe.h, top = 8.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(episodes, key = { it.id }) { ep ->
                            val p = progressMap["${ProgressKind.EPISODE}:${ep.id}"]
                            val index = i.episodes.indexOf(ep)
                            WideCard(
                                title = "${ep.number}. ${ep.title}",
                                subtitle = Txt.durationLabel(ep.durationSecs),
                                image = ep.image ?: series.backdrop ?: series.image,
                                progress = p?.takeIf { !it.isNext }?.let { if (it.finished) 1f else it.fraction },
                                badge = if (p?.finished == true) "VU" else null,
                                onClick = {
                                    val start = if (p != null && !p.finished && !p.isNext) p.position else 0L
                                    app.play(PlayRequest.Episodes(series, i.episodes, index, start))
                                },
                                onFocus = { focusedEpisode = ep },
                                modifier = Modifier.remembered(memory, "ep/${ep.id}"),
                            )
                        }
                    }
                }
                val fe = focusedEpisode?.takeIf { it.season == season }
                Box(Modifier.padding(start = Safe.h, end = Safe.h, bottom = 40.dp).height(46.dp).width(720.dp)) {
                    fe?.plot?.let { Text(it, style = T.body, color = C.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            } else if (i == null && error == null) {
                SkeletonRow(CardSize.wideW, 16f / 9f, count = 4)
            } else if (i != null) {
                Text("Aucun épisode disponible pour cette série.", style = T.body, color = C.Text2, modifier = Modifier.padding(start = Safe.h))
            }
        }
    }
}
