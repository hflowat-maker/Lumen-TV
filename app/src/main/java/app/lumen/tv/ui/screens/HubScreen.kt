package app.lumen.tv.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.lumen.tv.data.Channel
import app.lumen.tv.data.ContentType
import app.lumen.tv.data.EpgEntry
import app.lumen.tv.data.MovieInfo
import app.lumen.tv.data.ProgressKind
import app.lumen.tv.data.Repo
import app.lumen.tv.data.Text as Txt
import app.lumen.tv.safely
import app.lumen.tv.ui.AppController
import app.lumen.tv.ui.Screen
import app.lumen.tv.ui.components.Art
import app.lumen.tv.ui.components.Avatar
import app.lumen.tv.ui.components.ChannelLogo
import app.lumen.tv.ui.components.Clickable
import app.lumen.tv.ui.components.FocusInfo
import app.lumen.tv.ui.components.LangSwitch
import app.lumen.tv.ui.components.PinDialog
import app.lumen.tv.ui.components.ProgressLine
import app.lumen.tv.ui.components.RoundIconButton
import app.lumen.tv.ui.components.Safe
import app.lumen.tv.ui.components.Stage
import app.lumen.tv.ui.components.rememberFocusMemory
import app.lumen.tv.ui.components.remembered
import app.lumen.tv.ui.theme.C
import app.lumen.tv.ui.theme.T
import coil.compose.AsyncImage

/** Contenu d'une tuile de l'accueil et du fond affiché quand elle est sélectionnée. */
private data class HubTile(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val subtitle: String?,
    val art: Art?,
    val overline: String?,
    val title: String?,
    val meta: List<String?> = emptyList(),
    val plot: String? = null,
    val progress: Float? = null,
    val channel: Channel? = null,
    val badge: String? = null,
    val onClick: () -> Unit,
)

@Composable
fun HubScreen(app: AppController) {
    val repo = app.repo
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val memory = rememberFocusMemory()
    val mode by repo.langMode.collectAsState()
    val profile by repo.profile.collectAsState()
    val version by repo.catalogVersion.collectAsState()
    val filterVersion by repo.filterVersion.collectAsState()
    val favorites by repo.favorites.collectAsState()
    val progressMap by repo.progress.collectAsState()
    var data by remember { mutableStateOf<Repo.HubData?>(null) }
    var focusedKey by rememberSaveable { mutableStateOf("movies") }
    var askPin by remember { mutableStateOf(false) }
    var lastBack by remember { mutableLongStateOf(0L) }

    LaunchedEffect(mode, version, filterVersion, profile?.id, favorites, progressMap) {
        data = safely("accueil") { repo.hubData() }
    }

    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBack < 2500) {
            (context as? Activity)?.finish()
        } else {
            lastBack = now
            Toast.makeText(context, "Appuyez à nouveau sur Retour pour quitter", Toast.LENGTH_SHORT).show()
        }
    }

    val d = data
    // Fiche complète du film mis en avant (image horizontale et résumé).
    val movieInfo by produceState<MovieInfo?>(null, d?.newMovie?.id) {
        val id = d?.newMovie?.id ?: return@produceState
        value = repo.cachedMovieInfo(id) ?: safely("film mis en avant") { repo.movieInfo(id) }
    }
    val epgNow by produceState<EpgEntry?>(null, d?.channel?.id) {
        val ch = d?.channel ?: return@produceState
        val now = System.currentTimeMillis()
        value = repo.epg(ch.id).firstOrNull { it.start <= now && it.end > now }
    }

    val tiles = buildTiles(app, d, movieInfo, epgNow, scope, context)
    val focused = tiles.firstOrNull { it.key == focusedKey } ?: tiles.firstOrNull()

    fun openSettings() {
        if (profile?.isKid == true && app.prefs.parentalPin != null) askPin = true else app.push(Screen.Settings)
    }

    Box(Modifier.fillMaxSize()) {
        Stage(focused?.art)
        Column(Modifier.fillMaxSize()) {
            // ---------- Barre du haut ----------
            Row(
                Modifier.fillMaxWidth().padding(start = Safe.h, end = Safe.h, top = Safe.v),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(C.Gold))
                Spacer(Modifier.width(10.dp))
                Text("Lumen", style = T.title, color = C.Text1)
                Spacer(Modifier.weight(1f))
                LangSwitch(mode, { repo.setLangMode(it) }, modifier = Modifier.remembered(memory, "lang"))
                Spacer(Modifier.width(16.dp))
                profile?.let { p ->
                    Clickable(
                        onClick = { app.push(Screen.Profiles()) },
                        modifier = Modifier.size(44.dp).remembered(memory, "profile"),
                        shape = CircleShape,
                        focusedScale = 1.1f,
                        borderWidth = 2.dp,
                    ) { Avatar(p.name, p.color, size = 44.dp) }
                    Spacer(Modifier.width(10.dp))
                }
                RoundIconButton(Icons.Filled.Settings, onClick = { openSettings() }, modifier = Modifier.remembered(memory, "settings"))
            }

            Spacer(Modifier.weight(1f))

            // ---------- Fiche de la tuile sélectionnée ----------
            Box(Modifier.padding(start = Safe.h).height(150.dp)) {
                FocusInfo(
                    overline = focused?.overline,
                    title = focused?.title,
                    meta = focused?.meta.orEmpty(),
                    rating = null,
                    plot = focused?.plot,
                    badge = focused?.badge,
                )
            }
            Spacer(Modifier.height(14.dp))

            // ---------- Tuiles ----------
            Column(
                Modifier.fillMaxWidth().padding(start = Safe.h, end = Safe.h, bottom = Safe.v),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                tiles.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        row.forEach { t ->
                            HubTileView(
                                tile = t,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(112.dp)
                                    .onFocusChanged { if (it.isFocused) focusedKey = t.key }
                                    .remembered(memory, "tile/${t.key}", first = t.key == (if (d?.resume != null) "resume" else "movies")),
                            )
                        }
                    }
                }
            }
        }
    }

    if (askPin) {
        PinDialog(
            title = "Code parental",
            subtitle = "Les réglages sont protégés.",
            onSubmit = { entered ->
                val ok = entered == app.prefs.parentalPin
                if (ok) {
                    askPin = false
                    app.push(Screen.Settings)
                }
                ok
            },
            onDismiss = { askPin = false },
        )
    }
}

private fun buildTiles(
    app: AppController,
    d: Repo.HubData?,
    movieInfo: MovieInfo?,
    epgNow: EpgEntry?,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
): List<HubTile> {
    val out = ArrayList<HubTile>()
    val resume = d?.resume
    val movie = d?.newMovie
    val series = d?.newSeries
    val movieArt = when {
        movieInfo?.backdrop != null -> Art(movieInfo.backdrop, isPoster = false)
        movie?.image != null -> Art(movie.image, isPoster = true)
        else -> null
    }

    if (resume != null) {
        val left = if (resume.isNext || resume.duration <= 0) null else Txt.remaining(resume.duration - resume.position)
        out += HubTile(
            key = "resume", label = "Reprendre", icon = Icons.Filled.PlayArrow,
            subtitle = resume.title,
            art = resume.image?.let { Art(it, isPoster = false) },
            overline = if (resume.isNext) "Épisode suivant" else "Reprendre",
            title = resume.title, meta = listOf(resume.subtitle, left),
            progress = if (resume.isNext) null else resume.fraction,
            onClick = { app.resume(resume, scope, context) },
        )
    } else {
        out += HubTile(
            key = "resume", label = "À découvrir", icon = Icons.Filled.PlayArrow,
            subtitle = movie?.title,
            art = movieArt, overline = "À découvrir", title = movie?.title,
            meta = listOf(movie?.year, movieInfo?.genre), plot = movieInfo?.plot,
            onClick = { movie?.let { app.open(it) } ?: app.push(Screen.Section(ContentType.MOVIE)) },
        )
    }

    val ch = d?.channel
    out += HubTile(
        key = "live", label = "TV en direct", icon = Icons.Filled.LiveTv,
        subtitle = when {
            ch != null && epgNow != null -> "${ch.name} · ${epgNow.title}"
            ch != null -> ch.name
            else -> d?.channelCount?.let { "$it chaînes" }
        },
        art = null, overline = "En ce moment",
        title = epgNow?.title ?: ch?.name ?: "TV en direct",
        meta = listOf(ch?.name?.takeIf { epgNow != null }, epgNow?.let { "${Txt.hour(it.start)} – ${Txt.hour(it.end)}" }),
        plot = epgNow?.description,
        progress = epgNow?.progress(System.currentTimeMillis()),
        channel = ch, badge = "DIRECT",
        onClick = { app.push(Screen.Live) },
    )

    out += HubTile(
        key = "movies", label = "Films", icon = Icons.Filled.Movie,
        subtitle = d?.let { "${it.movieCount} films" + (movie?.let { m -> " · ${m.title}" } ?: "") },
        art = movieArt, overline = "Nouveau dans Films", title = movie?.title,
        meta = listOf(movie?.year, Txt.durationLabel(movieInfo?.durationSecs ?: 0), movieInfo?.genre),
        plot = movieInfo?.plot,
        onClick = { app.push(Screen.Section(ContentType.MOVIE)) },
    )

    out += HubTile(
        key = "series", label = "Séries", icon = Icons.Filled.VideoLibrary,
        subtitle = d?.let { "${it.seriesCount} séries" + (series?.let { s -> " · ${s.title}" } ?: "") },
        art = series?.let { s -> (s.backdrop ?: s.image)?.let { Art(it, isPoster = s.backdrop == null) } },
        overline = "Mise à jour dans Séries", title = series?.title,
        meta = listOf(series?.year, series?.genre), plot = series?.plot,
        onClick = { app.push(Screen.Section(ContentType.SERIES)) },
    )

    val fav = d?.myList?.firstOrNull()
    out += HubTile(
        key = "mylist", label = "Ma liste", icon = Icons.Filled.FavoriteBorder,
        subtitle = d?.let { if (it.myListCount == 0) "Appui long sur OK pour ajouter" else "${it.myListCount} favoris" },
        art = fav?.let { f -> (f.backdrop ?: f.image)?.let { Art(it, isPoster = f.backdrop == null) } },
        overline = "Ma liste", title = fav?.title ?: "Ma liste",
        meta = listOf(fav?.year),
        plot = if (fav == null) "Maintenez OK sur une affiche ou une chaîne pour l'ajouter ici." else null,
        onClick = { app.push(Screen.MyList) },
    )

    out += HubTile(
        key = "search", label = "Rechercher", icon = Icons.Filled.Search,
        subtitle = "Films, séries et chaînes",
        art = null, overline = "Rechercher", title = "Que voulez-vous regarder ?",
        plot = "Une seule recherche sur toutes les chaînes, films et séries.",
        onClick = { app.push(Screen.Search) },
    )
    return out
}

@Composable
private fun HubTileView(tile: HubTile, modifier: Modifier) {
    Clickable(
        onClick = tile.onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        container = Color(0x8C14141A),
        focusedContainer = Color(0xE614141A),
        focusedScale = 1.05f,
        borderWidth = 3.dp,
    ) {
        // Vignette de fond : l'image du contenu, assombrie pour garder le texte lisible.
        val art = tile.art?.url
        if (art != null) {
            AsyncImage(
                model = art, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0f to Color(0xF20A0A0E), 0.6f to Color(0x990A0A0E), 1f to Color(0x330A0A0E))
                )
            )
        }
        val ch = tile.channel
        if (art == null && ch != null) {
            Box(
                Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).size(width = 92.dp, height = 60.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Color(0x1FFFFFFF)).padding(8.dp)
            ) { ChannelLogo(ch, Modifier.fillMaxSize()) }
        }
        Column(Modifier.align(Alignment.CenterStart).padding(start = 18.dp, end = if (ch != null && art == null) 120.dp else 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(tile.icon, null, tint = C.Gold, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(tile.label, style = T.title, color = C.Text1, maxLines = 1)
                if (tile.key == "live") {
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(C.Live))
                }
            }
            tile.subtitle?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = T.meta, color = C.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            tile.progress?.let {
                Spacer(Modifier.height(8.dp))
                ProgressLine(it, Modifier.width(160.dp))
            }
        }
    }
}
