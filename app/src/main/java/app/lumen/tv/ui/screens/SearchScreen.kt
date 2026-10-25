package app.lumen.tv.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.lumen.tv.data.Channel
import app.lumen.tv.data.ContentType
import app.lumen.tv.data.Tile
import app.lumen.tv.ui.AppController
import app.lumen.tv.ui.components.ChannelTile
import app.lumen.tv.ui.components.PosterCard
import app.lumen.tv.ui.components.RowHeader
import app.lumen.tv.ui.components.Safe
import app.lumen.tv.ui.components.SearchKeyboard
import app.lumen.tv.ui.components.Spinner
import app.lumen.tv.ui.components.rememberFocusMemory
import app.lumen.tv.ui.components.rememberStartPivot
import app.lumen.tv.ui.components.remembered
import app.lumen.tv.ui.components.restoreFocus
import app.lumen.tv.ui.components.safeRequest
import app.lumen.tv.ui.theme.C
import app.lumen.tv.ui.theme.T
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(app: AppController) {
    val memory = rememberFocusMemory()
    val context = LocalContext.current
    val favorites by app.repo.favorites.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<Triple<List<Channel>, List<Tile>, List<Tile>>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val firstKey = remember { FocusRequester() }

    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            results = null
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(260)
        results = app.repo.search(query.trim())
        searching = false
    }
    LaunchedEffect(Unit) {
        if (memory.last == null) firstKey.safeRequest(80)
    }

    Row(Modifier.fillMaxSize().padding(top = Safe.v)) {
        Column(Modifier.width(330.dp).fillMaxHeight().padding(start = 28.dp, end = 12.dp)) {
            Text("Recherche", style = T.display, color = C.Text1)
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.width(282.dp).height(46.dp).clip(RoundedCornerShape(10.dp)).background(C.Surface2).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, tint = C.Text3, modifier = Modifier.width(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    query.ifEmpty { "Titre, chaîne…" } + if (query.isNotEmpty()) "▏" else "",
                    style = T.bodyStrong,
                    color = if (query.isEmpty()) C.Text3 else C.Text1,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(16.dp))
            SearchKeyboard(
                onChar = { query += it },
                onBackspace = { query = query.dropLast(1) },
                onClear = { query = "" },
                firstKey = firstKey,
            )
        }

        Box(Modifier.weight(1f).fillMaxHeight()) {
            val r = results
            when {
                searching && r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
                r == null -> Column(Modifier.padding(start = Safe.h, top = 70.dp)) {
                    Text("Que voulez-vous regarder ?", style = T.title, color = C.Text1)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tapez au moins deux lettres : la recherche couvre vos chaînes, films et séries.",
                        style = T.body, color = C.Text2, modifier = Modifier.width(440.dp),
                    )
                }
                r.first.isEmpty() && r.second.isEmpty() && r.third.isEmpty() -> Column(Modifier.padding(start = Safe.h, top = 70.dp)) {
                    Text("Aucun résultat pour « ${query.trim()} »", style = T.title, color = C.Text1)
                    Spacer(Modifier.height(8.dp))
                    Text("Essayez avec un autre mot ou une orthographe différente.", style = T.body, color = C.Text2)
                }
                else -> CompositionLocalProvider(LocalBringIntoViewSpec provides rememberStartPivot(0.dp)) {
                    LazyColumn(
                        contentPadding = PaddingValues(top = 62.dp, bottom = 80.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (r.second.isNotEmpty()) item(key = "m") { ResultTiles(app, "Films", r.second, memory, favorites, context) }
                        if (r.third.isNotEmpty()) item(key = "s") { ResultTiles(app, "Séries", r.third, memory, favorites, context) }
                        if (r.first.isNotEmpty()) item(key = "c") { ResultChannels(app, r.first, memory, favorites, context) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultTiles(
    app: AppController,
    title: String,
    tiles: List<Tile>,
    memory: app.lumen.tv.ui.components.FocusMemory,
    favorites: Set<String>,
    context: android.content.Context,
) {
    Column(Modifier.fillMaxWidth()) {
        RowHeader(title, count = tiles.size)
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberStartPivot(Safe.h)) {
            LazyRow(
                modifier = Modifier.restoreFocus(),
                contentPadding = PaddingValues(start = Safe.h, end = Safe.h, top = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(tiles, key = { it.key }) { t ->
                    PosterCard(
                        title = t.title, image = t.image, favorite = t.key in favorites,
                        onClick = { app.open(t) },
                        onLongClick = { app.toggleFavorite(context, t.type, t.id) },
                        modifier = Modifier.remembered(memory, "r/${t.key}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultChannels(
    app: AppController,
    channels: List<Channel>,
    memory: app.lumen.tv.ui.components.FocusMemory,
    favorites: Set<String>,
    context: android.content.Context,
) {
    Column(Modifier.fillMaxWidth()) {
        RowHeader("Chaînes", count = channels.size)
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberStartPivot(Safe.h)) {
            LazyRow(
                modifier = Modifier.restoreFocus(),
                contentPadding = PaddingValues(start = Safe.h, end = Safe.h, top = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(channels, key = { it.id }) { ch ->
                    ChannelTile(
                        channel = ch, favorite = "live:${ch.id}" in favorites,
                        onClick = { app.playChannel(channels, ch) },
                        onLongClick = { app.toggleFavorite(context, ContentType.LIVE, ch.id) },
                        modifier = Modifier.remembered(memory, "c/${ch.id}"),
                    )
                }
            }
        }
    }
}
