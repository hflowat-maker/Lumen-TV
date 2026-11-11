package app.lumen.forja.ui.screens

import android.widget.Toast
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.lumen.forja.data.ForjaApi
import app.lumen.forja.data.Item
import app.lumen.forja.data.Store
import app.lumen.forja.data.Txt
import app.lumen.forja.safely
import app.lumen.forja.ui.AppController
import app.lumen.forja.ui.components.CardSize
import app.lumen.forja.ui.components.PosterCard
import app.lumen.forja.ui.components.Safe
import app.lumen.forja.ui.components.Spinner
import app.lumen.forja.ui.components.rememberFocusMemory
import app.lumen.forja.ui.components.remembered
import app.lumen.forja.ui.theme.C
import app.lumen.forja.ui.theme.T

/** Tous les titres d'une rubrique ou d'un genre (« Tout voir »), chargés par pages de 40. */
@Composable
fun GridScreen(app: AppController, slug: String, title: String) {
    val memory = rememberFocusMemory()
    val context = LocalContext.current
    val favorites by Store.favorites.collectAsState()
    var items by remember { mutableStateOf<List<Item>?>(null) }
    var total by remember { mutableIntStateOf(0) }
    var nextPage by remember { mutableIntStateOf(1) }
    var lastPage by remember { mutableIntStateOf(1) }
    var wantMore by remember { mutableIntStateOf(0) }
    val gridState = rememberLazyGridState()

    // Première page, puis les suivantes quand le focus approche de la fin de la grille.
    // Les demandes sont traitées l'une après l'autre (snapshotFlow), sans en annuler une en cours.
    LaunchedEffect(slug) {
        snapshotFlow { wantMore }.collect {
            val current = items
            if (current != null && nextPage > lastPage) return@collect
            val page = safely("grille $slug") { ForjaApi.page(slug, nextPage) }
            if (page != null && page.items.isNotEmpty()) {
                val merged = (current.orEmpty() + page.items).distinctBy { it.id }
                items = merged
                total = maxOf(page.total, merged.size)
                lastPage = page.lastPage
                nextPage = page.page + 1
            } else if (current == null) {
                // Recherche par rubrique indisponible : on se contente des premiers titres de la rubrique.
                val first = safely("rubrique $slug") { ForjaApi.category(slug, 50) }
                items = first?.first.orEmpty()
                total = first?.second ?: 0
                nextPage = lastPage + 1
            } else {
                nextPage = lastPage + 1
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = Safe.h, top = Safe.v, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
            Text(title, style = T.shelf.copy(fontSize = 46.sp, lineHeight = 48.sp), color = C.Text1, maxLines = 1)
            if (items != null) {
                Spacer(Modifier.width(14.dp))
                Text(Txt.count(total, "titre", "titres"), style = T.meta, color = C.Text2, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        val list = items
        when {
            list == null -> Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) { Spinner() }
            list.isEmpty() -> Text(
                "Aucun titre pour le moment.", style = T.body, color = C.Text2,
                modifier = Modifier.padding(start = Safe.h, top = 20.dp),
            )
            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(CardSize.posterW),
                contentPadding = PaddingValues(start = Safe.h, end = Safe.h, top = 10.dp, bottom = 60.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(list, key = { _, x -> x.key }) { i, item ->
                    PosterCard(
                        title = item.title,
                        image = item.poster ?: item.tile,
                        favorite = Store.isFavorite(item.slug, favorites),
                        onClick = { app.open(item) },
                        onLongClick = {
                            val added = Store.toggleFavorite(item)
                            Toast.makeText(context, if (added) "Ajouté à Ma liste" else "Retiré de Ma liste", Toast.LENGTH_SHORT).show()
                        },
                        onFocus = { if (i >= list.size - 14) wantMore++ },
                        modifier = Modifier.remembered(memory, item.key, first = i == 0),
                    )
                }
            }
        }
    }
}
