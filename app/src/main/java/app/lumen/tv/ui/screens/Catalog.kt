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
