package app.lumen.tv.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.lumen.tv.data.Category
import app.lumen.tv.data.Channel
import app.lumen.tv.data.ContentType
import app.lumen.tv.data.PlayRequest
import app.lumen.tv.data.ProgressKind
import app.lumen.tv.data.Text as Txt
import app.lumen.tv.data.Tile
import app.lumen.tv.data.WatchProgress
import app.lumen.tv.ui.AppController
import app.lumen.tv.ui.Screen
import app.lumen.tv.ui.components.CardSize
import app.lumen.tv.ui.components.ChannelTile
import app.lumen.tv.ui.components.FocusMemory
import app.lumen.tv.ui.components.MetaLine
import app.lumen.tv.ui.components.PosterCard
import app.lumen.tv.ui.components.ProgressLine
import app.lumen.tv.ui.components.RowHeader
import app.lumen.tv.ui.components.Safe
import app.lumen.tv.ui.components.SeeAllCard
import app.lumen.tv.ui.components.SkeletonRow
import app.lumen.tv.ui.components.WideCard
import app.lumen.tv.ui.components.rememberStartPivot
import app.lumen.tv.ui.components.remembered
import app.lumen.tv.ui.components.restoreFocus
import app.lumen.tv.ui.theme.C
import app.lumen.tv.ui.theme.T
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------- Modèle des rangées ----------------

sealed interface BrowseRow {
    val id: String
    val title: String

    data class Continue(override val id: String, override val title: String, val items: List<WatchProgress>) : BrowseRow
    data class Tiles(
        override val id: String,
        override val title: String,
        val tiles: List<Tile>,
        val seeAll: Screen? = null,
    ) : BrowseRow
    data class CategoryRow(override val id: String, override val title: String, val category: Category) : BrowseRow
    data class Channels(override val id: String, override val title: String, val channels: List<Channel>) : BrowseRow
}

@Immutable
data class HeroData(
    val key: String,
    val title: String,
    val meta: List<String?> = emptyList(),
    val rating: String? = null,
    val plot: String? = null,
    val image: String? = null,
    val badge: String? = null,
    val progress: Float? = null,
)

private fun Tile.hero(extraPlot: String? = null, extraImage: String? = null, duration: String? = null, genre: String? = null) = HeroData(
    key = key,
    title = title,
    meta = listOf(year, duration, genre ?: this.genre),
    rating = Txt.rating(rating),
    plot = extraPlot ?: plot,
    image = extraImage ?: backdrop ?: image,
)

// ---------------- Actions communes ----------------

fun AppController.toggleFavorite(context: Context, type: ContentType, id: Int) {
    val added = repo.toggleFavorite(type, id)
    Toast.makeText(context, if (added) "Ajouté à Ma liste" else "Retiré de Ma liste", Toast.LENGTH_SHORT).show()
}

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

// ---------------- Écran de navigation immersif ----------------

/**
 * En haut : la fiche de l'élément focalisé sur son image de fond.
 * En bas : les rangées qui défilent avec le focus aligné à gauche, comme sur Netflix.
 */
@Composable
fun ImmersiveBrowse(
    app: AppController,
    rows: List<BrowseRow>,
    memory: FocusMemory,
    listState: LazyListState,
    scope: CoroutineScope,
    context: Context,
    empty: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf<Any?>(null) }
    var hero by remember { mutableStateOf<HeroData?>(null) }
    val favorites by app.repo.favorites.collectAsState()
    val progress by app.repo.progress.collectAsState()

    LaunchedEffect(focused) {
        when (val f = focused) {
            is Tile -> {
                delay(90)
                val cached = if (f.type == ContentType.MOVIE) app.repo.cachedMovieInfo(f.id) else null
                hero = if (cached != null) {
                    f.hero(cached.plot, cached.backdrop, Txt.durationLabel(cached.durationSecs), cached.genre)
                } else {
                    f.hero()
                }
                if (f.type == ContentType.MOVIE && cached == null) {
                    delay(380)
                    val info = runCatching { app.repo.movieInfo(f.id) }.getOrNull()
                    if (info != null) hero = f.hero(info.plot, info.backdrop, Txt.durationLabel(info.durationSecs), info.genre)
                }
            }
            is WatchProgress -> {
                delay(90)
                hero = HeroData(
                    key = f.key, title = f.title,
                    meta = listOf(f.subtitle, Txt.remaining(f.duration - f.position).takeIf { !f.isNext && f.duration > 0 }),
                    image = f.image, progress = if (f.isNext) null else f.fraction,
                    badge = if (f.isNext) "NOUVEL ÉPISODE" else null,
                )
            }
            is Channel -> {
                delay(90)
                hero = HeroData(key = "live:${f.id}", title = f.name, badge = "DIRECT", image = null)
                val now = app.repo.epg(f.id).firstOrNull { it.end > System.currentTimeMillis() }
                if (now != null) {
                    hero = HeroData(
                        key = "live:${f.id}", title = f.name, badge = "DIRECT",
                        meta = listOf("${Txt.hour(now.start)} – ${Txt.hour(now.end)}"),
                        plot = now.title + (now.description?.let { "\n$it" } ?: ""),
                        progress = now.progress(System.currentTimeMillis()),
                    )
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        HeroBackdrop(hero?.image)
        Column(Modifier.fillMaxSize()) {
            HeroText(hero, Modifier.height(216.dp))
            if (rows.isEmpty() && empty != null) {
                Box(Modifier.fillMaxSize().padding(start = Safe.h), contentAlignment = Alignment.TopStart) { empty() }
            }
            CompositionLocalProvider(LocalBringIntoViewSpec provides rememberStartPivot(0.dp)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rows.forEachIndexed { rowIndex, row ->
                        item(key = row.id, contentType = row::class.java.simpleName) {
                            val first = rowIndex == 0
                            when (row) {
                                is BrowseRow.Continue -> ContinueRow(app, row, memory, first, scope, context) { focused = it }
                                is BrowseRow.Tiles -> TileRow(app, row.id, row.title, row.tiles, memory, first, favorites, progress, row.seeAll, context) { focused = it }
                                is BrowseRow.CategoryRow -> {
                                    val version by app.repo.catalogVersion.collectAsState()
                                    val tiles by produceState<List<Tile>?>(null, row.category.id, version) {
                                        value = app.repo.tilesIn(row.category.type, row.category.id, 30)
                                    }
                                    val list = tiles
                                    if (list == null) {
                                        Column {
                                            RowHeader(row.title)
                                            SkeletonRow(CardSize.posterW, 2f / 3f)
                                            Spacer(Modifier.height(10.dp))
                                        }
                                    } else {
                                        TileRow(
                                            app, row.id, row.title, list, memory, first, favorites, progress,
                                            if (row.category.count > list.size) {
                                                Screen.Grid(row.category.type, row.category.id, row.category.name)
                                            } else null,
                                            context, count = row.category.count,
                                        ) { focused = it }
                                    }
                                }
                                is BrowseRow.Channels -> ChannelRow(app, row, memory, first, favorites, context) { focused = it }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HeroBackdrop(url: String?) {
    Box(Modifier.fillMaxWidth().height(400.dp)) {
        Crossfade(targetState = url, animationSpec = tween(450), label = "backdrop") { u ->
            Box(Modifier.fillMaxSize()) {
                if (u != null) {
                    AsyncImage(
                        model = u, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.72f).fillMaxHeight(),
                    )
                }
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to C.Bg, 0.3f to C.Bg, 0.55f to C.Bg.copy(alpha = 0.55f), 1f to Color.Transparent)
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.55f to C.Bg.copy(alpha = 0.35f), 1f to C.Bg)
            )
        )
    }
}

@Composable
fun HeroText(hero: HeroData?, modifier: Modifier = Modifier) {
    Crossfade(targetState = hero, animationSpec = tween(220), label = "hero", modifier = modifier) { h ->
        Column(Modifier.fillMaxSize().padding(start = Safe.h, top = Safe.v + 6.dp).width(540.dp)) {
            if (h != null) {
                Text(h.title, style = T.hero, color = C.Text1, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(560.dp))
                Spacer(Modifier.height(10.dp))
                MetaLine(h.meta, rating = h.rating, badge = h.badge)
                if (h.progress != null) {
                    Spacer(Modifier.height(10.dp))
                    ProgressLine(h.progress, Modifier.width(220.dp))
                }
                if (!h.plot.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(h.plot, style = T.body, color = C.Text2, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(560.dp))
                }
            }
        }
    }
}

@Composable
private fun TileRow(
    app: AppController,
    rowId: String,
    title: String,
    tiles: List<Tile>,
    memory: FocusMemory,
    firstRow: Boolean,
    favorites: Set<String>,
    progress: Map<String, WatchProgress>,
    seeAll: Screen?,
    context: Context,
    count: Int? = null,
    onFocus: (Any) -> Unit,
) {
    if (tiles.isEmpty()) return
    Column {
        RowHeader(title, count = count)
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberStartPivot(Safe.h)) {
            LazyRow(
                modifier = Modifier.restoreFocus(),
                contentPadding = PaddingValues(start = Safe.h, end = Safe.h, top = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(tiles, key = { it.key }) { t ->
                    val p = if (t.type == ContentType.MOVIE) progress["${ProgressKind.MOVIE}:${t.id}"] else null
                    PosterCard(
                        title = t.title,
                        image = t.image,
                        favorite = t.key in favorites,
                        progress = p?.takeIf { !it.finished }?.fraction,
                        onClick = { app.open(t) },
                        onLongClick = { app.toggleFavorite(context, t.type, t.id) },
                        onFocus = { onFocus(t) },
                        modifier = Modifier.remembered(memory, "$rowId/${t.key}", first = firstRow && t == tiles.first()),
                    )
                }
                if (seeAll != null) {
                    item(key = "__all") {
                        SeeAllCard(onClick = { app.push(seeAll) }, modifier = Modifier.remembered(memory, "$rowId/__all"))
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueRow(
    app: AppController,
    row: BrowseRow.Continue,
    memory: FocusMemory,
    firstRow: Boolean,
    scope: CoroutineScope,
    context: Context,
    onFocus: (Any) -> Unit,
) {
    if (row.items.isEmpty()) return
    Column {
        RowHeader(row.title)
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberStartPivot(Safe.h)) {
            LazyRow(
                modifier = Modifier.restoreFocus(),
                contentPadding = PaddingValues(start = Safe.h, end = Safe.h, top = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(row.items, key = { it.key }) { p ->
                    WideCard(
                        title = p.title,
                        subtitle = p.subtitle,
                        image = p.image,
                        progress = if (p.isNext) null else p.fraction,
                        badge = if (p.isNext) "SUIVANT" else null,
                        onClick = { app.resume(p, scope, context) },
                        onLongClick = {
                            app.repo.removeProgress(p.kind, p.itemId)
                            Toast.makeText(context, "Retiré de « Continuer à regarder »", Toast.LENGTH_SHORT).show()
                        },
                        onFocus = { onFocus(p) },
                        modifier = Modifier.remembered(memory, "${row.id}/${p.key}", first = firstRow && p == row.items.first()),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    app: AppController,
    row: BrowseRow.Channels,
    memory: FocusMemory,
    firstRow: Boolean,
    favorites: Set<String>,
    context: Context,
    onFocus: (Any) -> Unit,
) {
    if (row.channels.isEmpty()) return
    Column {
        RowHeader(row.title)
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberStartPivot(Safe.h)) {
            LazyRow(
                modifier = Modifier.restoreFocus(),
                contentPadding = PaddingValues(start = Safe.h, end = Safe.h, top = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(row.channels, key = { it.id }) { ch ->
                    ChannelTile(
                        channel = ch,
                        favorite = "live:${ch.id}" in favorites,
                        onClick = { app.playChannel(row.channels, ch) },
                        onLongClick = { app.toggleFavorite(context, ContentType.LIVE, ch.id) },
                        onFocus = { onFocus(ch) },
                        modifier = Modifier.remembered(memory, "${row.id}/${ch.id}", first = firstRow && ch == row.channels.first()),
                    )
                }
            }
        }
    }
}
