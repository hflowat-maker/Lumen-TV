package app.lumen.forja.ui.screens

import android.widget.Toast
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
import app.lumen.forja.data.Favorite
import app.lumen.forja.data.ForjaApi
import app.lumen.forja.data.Item
import app.lumen.forja.data.Store
import app.lumen.forja.safely
import app.lumen.forja.ui.AppController
import app.lumen.forja.ui.components.FocusMemory
import app.lumen.forja.ui.components.PosterCard
import app.lumen.forja.ui.components.RowHeader
import app.lumen.forja.ui.components.Safe
import app.lumen.forja.ui.components.SearchKeyboard
import app.lumen.forja.ui.components.Spinner
import app.lumen.forja.ui.components.rememberFocusMemory
import app.lumen.forja.ui.components.rememberStartPivot
import app.lumen.forja.ui.components.remembered
import app.lumen.forja.ui.components.restoreFocus
import app.lumen.forja.ui.components.safeRequest
import app.lumen.forja.ui.theme.C
import app.lumen.forja.ui.theme.T
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(app: AppController) {
    val memory = rememberFocusMemory()
    val favorites by Store.favorites.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Item>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val firstKey = remember { FocusRequester() }

    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            results = null
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(350)
        results = safely("recherche") { ForjaApi.search(query.trim()) } ?: emptyList()
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
                    query.ifEmpty { "Série, film, acteur…" } + if (query.isNotEmpty()) "▏" else "",
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
                        "Tapez au moins deux lettres, comme sur le site : « bnat », « lhaj », « caftan »…",
                        style = T.body, color = C.Text2, modifier = Modifier.width(440.dp),
                    )
                }
                r.isEmpty() -> Column(Modifier.padding(start = Safe.h, top = 70.dp)) {
                    Text("Aucun résultat pour « ${query.trim()} »", style = T.title, color = C.Text1)
                    Spacer(Modifier.height(8.dp))
                    Text("Essayez avec un autre mot ou une autre orthographe.", style = T.body, color = C.Text2)
                }
                else -> {
                    val series = r.filter { it.isSeries }
                    val videos = r.filter { !it.isSeries }
                    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberStartPivot(0.dp)) {
                        LazyColumn(
                            contentPadding = PaddingValues(top = 62.dp, bottom = 80.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            if (series.isNotEmpty()) item(key = "s") { ResultRow(app, "Séries", series, memory, favorites) }
                            if (videos.isNotEmpty()) item(key = "v") { ResultRow(app, "Films, théâtre et documentaires", videos, memory, favorites) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(app: AppController, title: String, items: List<Item>, memory: FocusMemory, favorites: List<Favorite>) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        RowHeader(title, count = items.size)
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberStartPivot(Safe.h)) {
            LazyRow(
                modifier = Modifier.restoreFocus(),
                contentPadding = PaddingValues(start = Safe.h, end = Safe.h, top = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(items, key = { it.key }) { t ->
                    PosterCard(
                        title = t.title, image = t.poster ?: t.tile,
                        favorite = Store.isFavorite(t.slug, favorites),
                        onClick = { app.open(t) },
                        onLongClick = {
                            val added = Store.toggleFavorite(t)
                            Toast.makeText(context, if (added) "Ajouté à Ma liste" else "Retiré de Ma liste", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.remembered(memory, "r/${t.key}"),
                    )
                }
            }
        }
    }
}
