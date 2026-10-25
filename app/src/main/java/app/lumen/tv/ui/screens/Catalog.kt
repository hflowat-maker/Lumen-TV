package app.lumen.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.lumen.tv.data.Channel
import app.lumen.tv.safely
import app.lumen.tv.data.ContentType
import app.lumen.tv.data.ProgressKind
import app.lumen.tv.data.Tile
import app.lumen.tv.ui.AppController
import app.lumen.tv.ui.components.CardSize
import app.lumen.tv.ui.components.ChannelTile
import app.lumen.tv.ui.components.PosterCard
import app.lumen.tv.ui.components.Safe
import app.lumen.tv.ui.components.Skeleton
import app.lumen.tv.ui.components.SkeletonRow
import app.lumen.tv.ui.components.Spinner
import app.lumen.tv.ui.components.rememberFocusMemory
import app.lumen.tv.ui.components.remembered
import app.lumen.tv.ui.theme.C
import app.lumen.tv.ui.theme.T

private suspend fun homeRows(app: AppController): List<BrowseRow> {
    val repo = app.repo
    val out = ArrayList<BrowseRow>()
    val cont = repo.continueWatching()
    if (cont.isNotEmpty()) out += BrowseRow.Continue("continue", "Continuer à regarder", cont)
    val mine = repo.myList()
    if (mine.isNotEmpty()) out += BrowseRow.Tiles("mylist", "Ma liste", mine)
    val favCh = repo.favoriteChannels()
    if (favCh.isNotEmpty()) out += BrowseRow.Channels("favch", "Mes chaînes", favCh)
    val recentCh = repo.recentChannels().filter { c -> favCh.none { it.id == c.id } }
    if (recentCh.isNotEmpty()) out += BrowseRow.Channels("recentch", "Regardées récemment", recentCh)
    val newMovies = repo.recent(ContentType.MOVIE, 30)
    if (newMovies.isNotEmpty()) out += BrowseRow.Tiles("newm", "Nouveaux films", newMovies)
    val newSeries = repo.recent(ContentType.SERIES, 30)
    if (newSeries.isNotEmpty()) out += BrowseRow.Tiles("news", "Séries mises à jour", newSeries)
    val mc = repo.categories(ContentType.MOVIE).take(4)
    val sc = repo.categories(ContentType.SERIES).take(4)
    for (i in 0 until maxOf(mc.size, sc.size)) {
        mc.getOrNull(i)?.let { out += BrowseRow.CategoryRow("cm-${it.id}", "Films · ${it.name}", it) }
        sc.getOrNull(i)?.let { out += BrowseRow.CategoryRow("cs-${it.id}", "Séries · ${it.name}", it) }
    }
    return out
}

private suspend fun typeRows(app: AppController, type: ContentType): List<BrowseRow> {
    val repo = app.repo
    val out = ArrayList<BrowseRow>()
    if (type == ContentType.SERIES) {
        val cont = repo.continueWatching().filter { it.kind == ProgressKind.EPISODE }
        if (cont.isNotEmpty()) out += BrowseRow.Continue("continue", "Reprendre", cont)
    } else {
        val cont = repo.continueWatching().filter { it.kind == ProgressKind.MOVIE }
        if (cont.isNotEmpty()) out += BrowseRow.Continue("continue", "Reprendre", cont)
    }
    val fav = repo.favoriteTiles(type)
    if (fav.isNotEmpty()) out += BrowseRow.Tiles("fav", "Ma liste", fav)
    val recent = repo.recent(type, 30)
    if (recent.isNotEmpty()) {
        out += BrowseRow.Tiles("recent", if (type == ContentType.MOVIE) "Ajoutés récemment" else "Mises à jour récentes", recent)
    }
    repo.categories(type).forEach { out += BrowseRow.CategoryRow("c-${it.id}", it.name, it) }
    return out
}

@Composable
private fun BrowseHost(app: AppController, emptyText: String, load: suspend () -> List<BrowseRow>) {
    val memory = rememberFocusMemory()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val version by app.repo.catalogVersion.collectAsState()
    val filterVersion by app.repo.filterVersion.collectAsState()
    val profile by app.repo.profile.collectAsState()
    val favorites by app.repo.favorites.collectAsState()
    val progress by app.repo.progress.collectAsState()
    var rows by remember { mutableStateOf<List<BrowseRow>?>(null) }

    LaunchedEffect(version, filterVersion, profile?.id, favorites, progress) {
        rows = safely("rangées") { load() } ?: emptyList()
    }

    val r = rows
    if (r == null) {
        LoadingBrowse()
    } else {
        ImmersiveBrowse(app, r, memory, listState, scope, context, empty = {
            Text(emptyText, style = T.body, color = C.Text2, modifier = Modifier.width(520.dp))
        })
    }
}

@Composable
fun HomeScreen(app: AppController) =
    BrowseHost(app, "Votre catalogue est vide pour ce profil. Vérifiez les catégories autorisées dans les réglages.") { homeRows(app) }

@Composable
fun MoviesScreen(app: AppController) =
    BrowseHost(app, "Aucun film disponible.") { typeRows(app, ContentType.MOVIE) }

@Composable
fun SeriesScreen(app: AppController) =
    BrowseHost(app, "Aucune série disponible.") { typeRows(app, ContentType.SERIES) }

@Composable
fun MyListScreen(app: AppController) = BrowseHost(
    app,
    "Votre liste est vide. Maintenez OK sur une affiche ou une chaîne pour l'ajouter à Ma liste.",
) {
    val repo = app.repo
    val out = ArrayList<BrowseRow>()
    val movies = repo.favoriteTiles(ContentType.MOVIE)
    val series = repo.favoriteTiles(ContentType.SERIES)
    val channels = repo.favoriteChannels()
    if (series.isNotEmpty()) out += BrowseRow.Tiles("fs", "Séries", series)
    if (movies.isNotEmpty()) out += BrowseRow.Tiles("fm", "Films", movies)
    if (channels.isNotEmpty()) out += BrowseRow.Channels("fc", "Chaînes", channels)
    out
}

@Composable
fun LoadingBrowse() {
    Column(Modifier.fillMaxSize().padding(top = Safe.v + 6.dp)) {
        Column(Modifier.padding(start = Safe.h)) {
            Skeleton(Modifier.width(360.dp).height(38.dp))
            Spacer(Modifier.height(14.dp))
            Skeleton(Modifier.width(220.dp).height(16.dp))
            Spacer(Modifier.height(14.dp))
            Skeleton(Modifier.width(500.dp).height(52.dp))
        }
        Spacer(Modifier.height(64.dp))
        Skeleton(Modifier.padding(start = Safe.h).width(160.dp).height(18.dp))
        Spacer(Modifier.height(14.dp))
        SkeletonRow(CardSize.posterW, 2f / 3f)
    }
}

/** Grille complète d'une catégorie. */
@Composable
fun GridScreen(app: AppController, type: ContentType, categoryId: String, title: String) {
    val memory = rememberFocusMemory()
    val context = LocalContext.current
    val favorites by app.repo.favorites.collectAsState()
    val progress by app.repo.progress.collectAsState()
    var tiles by remember { mutableStateOf<List<Tile>?>(null) }
    var channels by remember { mutableStateOf<List<Channel>?>(null) }
    val gridState = rememberLazyGridState()

    LaunchedEffect(type, categoryId) {
        if (type == ContentType.LIVE) channels = safely("grille") { app.repo.channelsIn(categoryId) } ?: emptyList()
        else tiles = safely("grille") { app.repo.tilesIn(type, categoryId) } ?: emptyList()
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = Safe.h, top = Safe.v, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
            Text(title, style = T.display, color = C.Text1)
            val n = tiles?.size ?: channels?.size
            if (n != null) {
                Spacer(Modifier.width(12.dp))
                Text("$n titres", style = T.meta, color = C.Text3, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
        val t = tiles
        val ch = channels
        when {
            t != null -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(CardSize.posterW),
                contentPadding = PaddingValues(start = Safe.h, end = Safe.h, top = 10.dp, bottom = 60.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(t, key = { it.key }) { tile ->
                    val p = if (tile.type == ContentType.MOVIE) progress["${ProgressKind.MOVIE}:${tile.id}"] else null
                    PosterCard(
                        title = tile.title,
                        image = tile.image,
                        favorite = tile.key in favorites,
                        progress = p?.takeIf { !it.finished }?.fraction,
                        onClick = { app.open(tile) },
                        onLongClick = { app.toggleFavorite(context, tile.type, tile.id) },
                        modifier = Modifier.remembered(memory, tile.key, first = tile == t.first()),
                    )
                }
            }
            ch != null -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(CardSize.channelW),
                contentPadding = PaddingValues(start = Safe.h, end = Safe.h, top = 10.dp, bottom = 60.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(ch, key = { it.id }) { c ->
                    ChannelTile(
                        channel = c,
                        favorite = "live:${c.id}" in favorites,
                        onClick = { app.playChannel(ch, c) },
                        onLongClick = { app.toggleFavorite(context, ContentType.LIVE, c.id) },
                        modifier = Modifier.remembered(memory, "ch${c.id}", first = c == ch.first()),
                    )
                }
            }
            else -> Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) { Spinner() }
        }
    }
}
