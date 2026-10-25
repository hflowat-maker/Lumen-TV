package app.lumen.tv.ui.screens

import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.lumen.tv.data.Channel
import app.lumen.tv.data.ContentType
import app.lumen.tv.data.Group
import app.lumen.tv.data.LangMode
import app.lumen.tv.data.ProgressKind
import app.lumen.tv.data.Text as Txt
import app.lumen.tv.data.Tile
import app.lumen.tv.safely
import app.lumen.tv.ui.AppController
import app.lumen.tv.ui.components.Art
import app.lumen.tv.ui.components.CardSize
import app.lumen.tv.ui.components.ChannelTile
import app.lumen.tv.ui.components.Chip
import app.lumen.tv.ui.components.FocusInfo
import app.lumen.tv.ui.components.PosterCard
import app.lumen.tv.ui.components.Safe
import app.lumen.tv.ui.components.SectionHeader
import app.lumen.tv.ui.components.Spinner
import app.lumen.tv.ui.components.Stage
import app.lumen.tv.ui.components.rememberFocusMemory
import app.lumen.tv.ui.components.rememberStartPivot
import app.lumen.tv.ui.components.remembered
import app.lumen.tv.ui.theme.C
import app.lumen.tv.ui.theme.T
import kotlinx.coroutines.delay

private val PosterW = 108.dp

/** Données affichées pour l'élément focalisé (fond plein écran + fiche). */
private data class Focus(
    val title: String,
    val art: Art?,
    val meta: List<String?>,
    val rating: String?,
    val plot: String?,
)

private fun Tile.basicFocus() = Focus(
    title = title,
    art = (backdrop ?: image)?.let { Art(it, isPoster = backdrop == null) },
    meta = listOf(year, genre),
    rating = Txt.rating(rating),
    plot = plot,
)

/** Films ou séries : puces de genres en haut, grille d'affiches, fond plein écran. */
@Composable
fun SectionScreen(app: AppController, type: ContentType) {
    val repo = app.repo
    val context = LocalContext.current
    val memory = rememberFocusMemory()
    val mode by repo.langMode.collectAsState()
    val version by repo.catalogVersion.collectAsState()
    val filterVersion by repo.filterVersion.collectAsState()
    val favorites by repo.favorites.collectAsState()
    val progress by repo.progress.collectAsState()

    var groups by remember { mutableStateOf<List<Group>?>(null) }
    var selectedKey by rememberSaveable { mutableStateOf("new") }
    var focusedChip by remember { mutableStateOf<String?>(null) }
    var tiles by remember { mutableStateOf<List<Tile>?>(null) }
    var focusedTile by remember { mutableStateOf<Tile?>(null) }
    var focus by remember { mutableStateOf<Focus?>(null) }

    LaunchedEffect(mode, version, filterVersion) {
        val list = safely("genres") { repo.groups(type) } ?: emptyList()
        groups = list
        if (list.none { it.key == selectedKey }) selectedKey = list.firstOrNull()?.key ?: "new"
    }
    // Survoler une puce suffit à changer de genre (pas besoin d'appuyer sur OK).
    LaunchedEffect(focusedChip) {
        val k = focusedChip ?: return@LaunchedEffect
        delay(350)
        selectedKey = k
    }
    val selected = groups?.firstOrNull { it.key == selectedKey }
    LaunchedEffect(selected, mode) {
        val g = selected ?: return@LaunchedEffect
        tiles = null
        tiles = safely("grille") { repo.groupTiles(type, g) } ?: emptyList()
    }
    LaunchedEffect(focusedTile) {
        val t = focusedTile ?: return@LaunchedEffect
        delay(120)
        focus = t.basicFocus()
        if (t.type == ContentType.MOVIE) {
            delay(350)
            val info = repo.cachedMovieInfo(t.id) ?: safely("fiche") { repo.movieInfo(t.id) }
            if (info != null) {
                focus = Focus(
                    title = t.title,
                    art = info.backdrop?.let { Art(it, false) } ?: t.image?.let { Art(it, true) },
                    meta = listOf(info.year ?: t.year, Txt.durationLabel(info.durationSecs), info.genre),
                    rating = Txt.rating(if (info.rating > 0) info.rating else t.rating),
                    plot = info.plot,
                )
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Stage(focus?.art)
        Column(Modifier.fillMaxSize()) {
            SectionHeader(if (type == ContentType.MOVIE) "Films" else "Séries", mode, { repo.setLangMode(it) })
            Box(Modifier.padding(start = Safe.h, top = 14.dp).height(138.dp)) {
                FocusInfo(
                    overline = selected?.label,
                    title = focus?.title,
                    meta = focus?.meta.orEmpty(),
                    rating = focus?.rating,
                    plot = focus?.plot,
                )
            }
            val gs = groups
            if (gs != null && gs.isNotEmpty()) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides rememberStartPivot(Safe.h)) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Safe.h, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(gs, key = { it.key }) { g ->
                            Chip(
                                text = "${g.label}  ${g.count}",
                                selected = g.key == selectedKey,
                                onClick = { selectedKey = g.key },
                                onFocus = { focusedChip = g.key },
                                modifier = Modifier
                                    .onFocusChanged { if (!it.isFocused && focusedChip == g.key) focusedChip = null }
                                    .remembered(memory, "chip/${g.key}"),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            val list = tiles
            when {
                gs != null && gs.isEmpty() -> EmptyLanguage(mode)
                list == null -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Spinner() }
                list.isEmpty() -> Text(
                    "Rien dans ce genre pour cette langue.", style = T.body, color = C.Text2,
                    modifier = Modifier.padding(start = Safe.h, top = 30.dp),
                )
                else -> key(selectedKey) {
                    val gridState = rememberLazyGridState()
                    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberStartPivot(10.dp)) {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(PosterW),
                            contentPadding = PaddingValues(start = Safe.h, end = Safe.h, top = 10.dp, bottom = 60.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(list, key = { it.key }) { t ->
                                val p = if (t.type == ContentType.MOVIE) progress["${ProgressKind.MOVIE}:${t.id}"] else null
                                PosterCard(
                                    title = t.title,
                                    image = t.image,
                                    width = PosterW,
                                    favorite = t.key in favorites,
                                    progress = p?.takeIf { !it.finished }?.fraction,
                                    onClick = { app.open(t) },
                                    onLongClick = { app.toggleFavorite(context, t.type, t.id) },
                                    onFocus = { focusedTile = t },
                                    modifier = Modifier.remembered(memory, "t/${t.key}", first = t == list.first()),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLanguage(mode: LangMode) {
    Column(Modifier.padding(start = Safe.h, top = 30.dp).width(560.dp)) {
        Text("Rien en ${mode.label.lowercase()} ici", style = T.title, color = C.Text1)
        Spacer(Modifier.height(8.dp))
        Text(
            "Votre abonnement n'a pas de contenu dans cette langue pour cette rubrique. " +
                "Essayez « Les deux » ou « Autres langues » en haut à droite.",
            style = T.body, color = C.Text2,
        )
    }
}

/** Ma liste : favoris en grille, triés par type. */
@Composable
fun MyListScreen(app: AppController) {
    val repo = app.repo
    val context = LocalContext.current
    val memory = rememberFocusMemory()
    val mode by repo.langMode.collectAsState()
    val favorites by repo.favorites.collectAsState()
    var filter by rememberSaveable { mutableStateOf("all") }
    var tiles by remember { mutableStateOf<List<Tile>?>(null) }
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var focus by remember { mutableStateOf<Focus?>(null) }

    LaunchedEffect(favorites) {
        tiles = safely("ma liste") { repo.myList() } ?: emptyList()
        channels = safely("chaînes favorites") { repo.favoriteChannels() } ?: emptyList()
    }

    val chips = buildList {
        add("all" to "Films & séries")
        add("movie" to "Films")
        add("series" to "Séries")
        add("live" to "Chaînes")
    }

    Box(Modifier.fillMaxSize()) {
        Stage(focus?.art)
        Column(Modifier.fillMaxSize()) {
            SectionHeader("Ma liste", mode, { repo.setLangMode(it) }, showLang = false)
            Box(Modifier.padding(start = Safe.h, top = 14.dp).height(138.dp)) {
                FocusInfo(overline = "Ma liste", title = focus?.title, meta = focus?.meta.orEmpty(), rating = focus?.rating, plot = focus?.plot)
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = Safe.h, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(chips, key = { it.first }) { (k, label) ->
                    val count = when (k) {
                        "movie" -> tiles?.count { it.type == ContentType.MOVIE }
                        "series" -> tiles?.count { it.type == ContentType.SERIES }
                        "live" -> channels.size
                        else -> tiles?.size
                    } ?: 0
                    Chip(
                        "$label  $count", selected = k == filter, onClick = { filter = k }, onFocus = { filter = k },
                        modifier = Modifier.remembered(memory, "chip/$k"),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            val all = tiles
            if (filter == "live") {
                if (channels.isEmpty()) {
                    EmptyList()
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(CardSize.channelW),
                        contentPadding = PaddingValues(start = Safe.h, end = Safe.h, top = 10.dp, bottom = 60.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(channels, key = { it.id }) { ch ->
                            ChannelTile(
                                channel = ch, favorite = true,
                                onClick = { app.playChannel(channels, ch) },
                                onLongClick = { app.toggleFavorite(context, ContentType.LIVE, ch.id) },
                                onFocus = { focus = Focus(ch.name, null, listOf("Chaîne"), null, null) },
                                modifier = Modifier.remembered(memory, "c/${ch.id}", first = ch == channels.first()),
                            )
                        }
                    }
                }
            } else {
                val list = all?.filter {
                    when (filter) {
                        "movie" -> it.type == ContentType.MOVIE
                        "series" -> it.type == ContentType.SERIES
                        else -> true
                    }
                }
                when {
                    list == null -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Spinner() }
                    list.isEmpty() -> EmptyList()
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(PosterW),
                        contentPadding = PaddingValues(start = Safe.h, end = Safe.h, top = 10.dp, bottom = 60.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(list, key = { it.key }) { t ->
                            PosterCard(
                                title = t.title, image = t.image, width = PosterW, favorite = true,
                                onClick = { app.open(t) },
                                onLongClick = { app.toggleFavorite(context, t.type, t.id) },
                                onFocus = { focus = t.basicFocus() },
                                modifier = Modifier.remembered(memory, "t/${t.key}", first = t == list.first()),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyList() {
    Column(Modifier.padding(start = Safe.h, top = 30.dp).width(560.dp)) {
        Text("Rien ici pour l'instant", style = T.title, color = C.Text1)
        Spacer(Modifier.height(8.dp))
        Text(
            "Maintenez OK sur une affiche ou une chaîne pour l'ajouter à Ma liste.",
            style = T.body, color = C.Text2,
        )
    }
}
