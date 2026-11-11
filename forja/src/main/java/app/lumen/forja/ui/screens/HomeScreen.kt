package app.lumen.forja.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.lumen.forja.data.Category
import app.lumen.forja.data.Favorite
import app.lumen.forja.data.ForjaApi
import app.lumen.forja.data.Progress
import app.lumen.forja.data.Store
import app.lumen.forja.safely
import app.lumen.forja.ui.AppController
import app.lumen.forja.ui.Screen
import app.lumen.forja.ui.components.Clickable
import app.lumen.forja.ui.components.PillButton
import app.lumen.forja.ui.components.Safe
import app.lumen.forja.ui.components.Spinner
import app.lumen.forja.ui.components.safeRequest
import app.lumen.forja.ui.theme.C
import app.lumen.forja.ui.theme.Sans
import app.lumen.forja.ui.theme.T
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Onglet de la barre du haut : Accueil, une rubrique du site, ou Ma liste. */
data class TabDef(val key: String, val label: String, val category: Category?)

private const val HOME = "home"
private const val MINE = "mine"

/** Rubriques de secours si le menu du site ne répond pas. */
private val FALLBACK_MENU = listOf(
    Category("series", "Séries", emptyList()),
    Category("films", "Films", emptyList()),
    Category("theatre", "Théâtre", emptyList()),
    Category("divertissement", "Divertissement", emptyList()),
)

/** Liens entre la barre du haut et les étagères de l'onglet affiché. */
@Stable
private class ContentHandle {
    var focus: () -> Boolean = { false }
    var atTop: () -> Boolean = { true }
    var toTop: () -> Unit = {}
}

@Composable
fun HomeScreen(app: AppController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (app.menu == null) app.menu = safely("menu") { ForjaApi.menu() }?.takeIf { it.isNotEmpty() } ?: FALLBACK_MENU
    }
    val menu = app.menu ?: FALLBACK_MENU
    val tabs = remember(menu) {
        listOf(TabDef(HOME, "Accueil", null)) +
            menu.map { TabDef(it.slug, it.name, it) } +
            TabDef(MINE, "Ma liste", null)
    }

    var tabKey by rememberSaveable { mutableStateOf(HOME) }
    val tab = tabs.firstOrNull { it.key == tabKey } ?: tabs.first()
    var focusedTab by remember { mutableStateOf<String?>(null) }
    var contentHasFocus by remember { mutableStateOf(false) }
    var contentWasFocused by rememberSaveable { mutableStateOf(true) }
    var wantContentFocus by remember { mutableStateOf(contentWasFocused) }
    var lastBack by remember { mutableLongStateOf(0L) }
    val tabReqs = remember(tabs) { tabs.associate { it.key to FocusRequester() } }
    val handle = remember { ContentHandle() }
    val holder = rememberSaveableStateHolder()

    // Survoler un onglet l'affiche après une courte pause.
    LaunchedEffect(focusedTab) {
        val t = focusedTab ?: return@LaunchedEffect
        delay(320)
        if (focusedTab == t) tabKey = t
    }
    LaunchedEffect(Unit) {
        if (!contentWasFocused) tabReqs[tab.key]?.safeRequest(120)
    }

    fun focusTab() {
        scope.launch { tabReqs[tab.key]?.safeRequest() }
    }

    BackHandler {
        when {
            contentHasFocus && !handle.atTop() -> handle.toTop()
            contentHasFocus -> focusTab()
            tab.key != HOME -> {
                tabKey = HOME
                focusedTab = null
                scope.launch { tabReqs[HOME]?.safeRequest() }
            }
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBack < 2500) {
                    (context as? Activity)?.finish()
                } else {
                    lastBack = now
                    Toast.makeText(context, "Appuie à nouveau sur Retour pour quitter", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(C.backdrop)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                tabs = tabs,
                current = tab.key,
                tabReqs = tabReqs,
                onTabFocus = { focusedTab = it },
                onTabBlur = { focusedTab = null },
                onTabClick = { k ->
                    tabKey = k
                    focusedTab = null
                    wantContentFocus = true
                },
                onSearch = {
                    contentWasFocused = false
                    app.push(Screen.Search)
                },
                onDown = {
                    val ft = focusedTab
                    if (ft != null && ft != tab.key) {
                        tabKey = ft
                        focusedTab = null
                        wantContentFocus = true
                        true
                    } else {
                        handle.focus()
                    }
                },
                modifier = Modifier.onFocusChanged { if (it.hasFocus) contentWasFocused = false },
            )
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onFocusChanged {
                        contentHasFocus = it.hasFocus
                        if (it.hasFocus) contentWasFocused = true
                    }
            ) {
                holder.SaveableStateProvider(tab.key) {
                    TabContent(
                        app = app,
                        tab = tab,
                        handle = handle,
                        wantFocus = wantContentFocus,
                        onFocusDone = { wantContentFocus = false },
                        onUpFromTop = { focusTab() },
                    )
                }
            }
        }
    }
}

// ---------------- Barre du haut ----------------

@Composable
fun ForjaWordmark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(
            "forja",
            style = T.title.copy(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 25.sp, lineHeight = 26.sp, letterSpacing = (-0.6).sp),
            color = C.Text1,
        )
        Box(Modifier.padding(start = 2.dp, bottom = 6.dp).size(6.dp).clip(CircleShape).background(C.Gold))
    }
}

@Composable
private fun TopBar(
    tabs: List<TabDef>,
    current: String,
    tabReqs: Map<String, FocusRequester>,
    onTabFocus: (String) -> Unit,
    onTabBlur: () -> Unit,
    onTabClick: (String) -> Unit,
    onSearch: () -> Unit,
    onDown: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = Safe.h, end = Safe.h, top = 22.dp)
            .height(36.dp)
            .onPreviewKeyEvent { ev ->
                ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown && onDown()
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ForjaWordmark()
        Spacer(Modifier.width(20.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.onFocusChanged { if (!it.hasFocus) onTabBlur() },
        ) {
            tabs.forEach { t ->
                val selected = t.key == current
                val req = tabReqs[t.key]
                Clickable(
                    onClick = { onTabClick(t.key) },
                    modifier = Modifier
                        .height(32.dp)
                        .then(if (req != null) Modifier.focusRequester(req) else Modifier)
                        .onFocusChanged { if (it.isFocused) onTabFocus(t.key) },
                    shape = RoundedCornerShape(16.dp),
                    container = Color.Transparent,
                    focusedContainer = C.Focus,
                    contentColor = if (selected) C.Text1 else C.Text2,
                    focusedContentColor = C.Bg,
                    focusedScale = 1.04f,
                ) {
                    Column(Modifier.align(Alignment.Center).padding(horizontal = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(t.label, style = T.nav, maxLines = 1)
                        Box(
                            Modifier.padding(top = 2.dp).size(width = 16.dp, height = 2.dp).clip(RoundedCornerShape(1.dp))
                                .background(if (selected) C.Gold else Color.Transparent)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.onFocusChanged { if (it.hasFocus) onTabBlur() }) {
            BarIcon(Icons.Filled.Search, onSearch)
        }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, onClick: () -> Unit) {
    Clickable(
        onClick = onClick,
        modifier = Modifier.size(30.dp),
        shape = CircleShape,
        container = Color.Transparent,
        focusedContainer = C.Focus,
        contentColor = C.Text1,
        focusedContentColor = C.Bg,
        focusedScale = 1.1f,
    ) {
        Box(Modifier.matchParentSize().border(1.5.dp, C.Line, CircleShape))
        Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp).align(Alignment.Center))
    }
}

// ---------------- Chargement des étagères ----------------

private suspend fun shelfFor(slug: String, title: String, subtitle: String?, seeAll: Boolean): Shelf? {
    val (items, total) = safely("rubrique $slug") { ForjaApi.category(slug, 20) } ?: return null
    if (items.isEmpty()) return null
    val list: List<ShelfItem> = items.map<app.lumen.forja.data.Item, ShelfItem> { TileItem(it) } +
        if (seeAll && total > items.size) listOf<ShelfItem>(SeeAllItem(slug, title, total)) else emptyList()
    return Shelf("c:$slug", title, subtitle, list)
}

/** Étagères venant du site pour un onglet (sans les étagères locales : reprise, Ma liste). */
private suspend fun remoteShelves(tab: TabDef, menu: List<Category>): List<Shelf> = coroutineScope {
    when {
        tab.key == HOME -> {
            val tops = listOf(
                async { shelfFor("top-forja", "À la une", "La sélection de Forja", false) },
                async { shelfFor("top-populaire", "Les plus regardés", null, false) },
            )
            val cats = menu.map { c -> async { shelfFor(c.slug, c.name, null, true) } }
            (tops + cats).awaitAll().filterNotNull()
        }
        tab.category != null -> {
            val c = tab.category
            val all = async { shelfFor(c.slug, c.name, "Tous les titres, les plus récents d'abord", true) }
            val genres = c.genres.map { g -> async { shelfFor(g.slug, g.name, c.name, true) } }
            (listOf(all) + genres).awaitAll().filterNotNull()
        }
        else -> emptyList()
    }
}

private fun localShelves(tab: TabDef, progress: Map<Int, Progress>, favorites: List<Favorite>): Pair<List<Shelf>, List<Shelf>> {
    val resume = Store.continueList(progress).map { ResumeItem(it) }
    val resumeShelf = if (resume.isNotEmpty()) Shelf("resume", "Continuer à regarder", null, resume) else null
    val favShelf = if (favorites.isNotEmpty()) {
        Shelf("mine", "Ma liste", "Maintenir OK sur un titre pour l'ajouter ou le retirer", favorites.map { TileItem(it.toItem()) })
    } else null
    return when (tab.key) {
        HOME -> listOfNotNull(resumeShelf) to emptyList()
        MINE -> listOfNotNull(favShelf, resumeShelf) to emptyList()
        else -> emptyList<Shelf>() to emptyList()
    }
}

// ---------------- Étagères d'un onglet ----------------

@Composable
private fun TabContent(
    app: AppController,
    tab: TabDef,
    handle: ContentHandle,
    wantFocus: Boolean,
    onFocusDone: () -> Unit,
    onUpFromTop: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val progress by Store.progress.collectAsState()
    val favorites by Store.favorites.collectAsState()
    val menu = app.menu ?: FALLBACK_MENU
    var reload by remember { mutableIntStateOf(0) }
    var remote by remember(tab.key) { mutableStateOf(app.shelfCache[tab.key]) }
    var failed by remember(tab.key) { mutableStateOf(false) }

    LaunchedEffect(tab.key, menu, reload) {
        if (tab.key == MINE) {
            remote = emptyList()
            return@LaunchedEffect
        }
        failed = false
        val s = remoteShelves(tab, menu)
        if (s.isEmpty()) {
            failed = remote.isNullOrEmpty()
            if (remote == null) remote = emptyList()
        } else {
            app.shelfCache[tab.key] = s
            remote = s
        }
    }

    val shelves: List<Shelf>? = remote?.let { r ->
        val (before, after) = localShelves(tab, progress, favorites)
        before + r + after
    }
    val favSlugs = remember(favorites) { favorites.map { it.slug }.toSet() }

    val listState = rememberLazyListState()
    var current by rememberSaveable { mutableIntStateOf(0) }
    val focuses = remember { HashMap<String, ShelfFocus>() }
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    val shelfPx = with(LocalDensity.current) { ShelfSize.height.toPx() }

    fun focusOf(key: String) = focuses.getOrPut(key) { ShelfFocus() }

    fun scrollToShelf(j: Int): Job {
        scrollJob?.cancel()
        val job = scope.launch {
            val now = listState.firstVisibleItemIndex * shelfPx + listState.firstVisibleItemScrollOffset
            val delta = j * shelfPx - now
            if (kotlin.math.abs(delta) > shelfPx * 4) listState.scrollToItem(j)
            else if (delta != 0f) listState.animateScrollBy(delta, tween(320, easing = FastOutSlowInEasing))
        }
        scrollJob = job
        return job
    }

    fun goTo(j: Int) {
        val l = shelves ?: return
        if (j !in l.indices) return
        current = j
        val f = focusOf(l[j].key)
        val early = f.request()
        val job = scrollToShelf(j)
        if (!early) {
            scope.launch {
                job.join()
                if (current == j) f.request()
            }
        }
    }

    LaunchedEffect(shelves?.size) {
        val l = shelves ?: return@LaunchedEffect
        if (current > l.lastIndex) current = l.lastIndex.coerceAtLeast(0)
    }

    SideEffect {
        handle.focus = {
            val l = shelves
            if (l.isNullOrEmpty()) false else focusOf(l[current.coerceIn(0, l.lastIndex)].key).request()
        }
        handle.atTop = { current == 0 }
        handle.toTop = { goTo(0) }
    }

    // Focus initial (ouverture de l'appli, clic sur un onglet, retour d'une fiche).
    LaunchedEffect(wantFocus, shelves == null) {
        val list = shelves
        if (!wantFocus || list == null) return@LaunchedEffect
        if (list.isEmpty()) {
            onFocusDone()
            onUpFromTop()
            return@LaunchedEffect
        }
        val i = current.coerceIn(0, list.lastIndex)
        if (listState.firstVisibleItemIndex != i) listState.scrollToItem(i)
        repeat(8) {
            delay(40)
            if (focusOf(list[i].key).request()) {
                onFocusDone()
                return@LaunchedEffect
            }
        }
        onFocusDone()
    }

    fun click(item: ShelfItem) {
        when (item) {
            is TileItem -> app.open(item.item)
            is ResumeItem -> app.resume(item.progress, scope)
            is SeeAllItem -> app.push(Screen.Grid(item.slug, item.title))
        }
    }

    fun longClick(item: ShelfItem) {
        when (item) {
            is TileItem -> {
                val added = Store.toggleFavorite(item.item)
                Toast.makeText(context, if (added) "Ajouté à Ma liste" else "Retiré de Ma liste", Toast.LENGTH_SHORT).show()
            }
            is ResumeItem -> {
                Store.removeFromContinue(item.progress.groupKey)
                Toast.makeText(context, "Retiré de Continuer à regarder", Toast.LENGTH_SHORT).show()
            }
            is SeeAllItem -> Unit
        }
    }

    when {
        shelves == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
        shelves.isEmpty() && failed -> ErrorTab { reload++ }
        shelves.isEmpty() -> EmptyTab(tab.key == MINE)
        else -> CompositionLocalProvider(LocalBringIntoViewSpec provides NoAutoScroll) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 110.dp),
                userScrollEnabled = false,
            ) {
                itemsIndexed(shelves, key = { _, s -> s.key }) { i, shelf ->
                    ShelfView(
                        shelf = shelf,
                        isCurrent = i == current,
                        focus = focusOf(shelf.key),
                        favorites = favSlugs,
                        onFocusShelf = {
                            if (current != i) {
                                current = i
                                scrollToShelf(i)
                            }
                        },
                        onVertical = { up ->
                            val c = current
                            if (up) {
                                if (c == 0) onUpFromTop() else goTo(c - 1)
                            } else if (c < shelves.lastIndex) {
                                goTo(c + 1)
                            }
                            true
                        },
                        onClick = { click(it) },
                        onLongClick = { longClick(it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyTab(mine: Boolean) {
    Column(Modifier.padding(start = Safe.h, top = 30.dp).width(560.dp)) {
        Text(if (mine) "Ta liste est vide" else "Rien à afficher pour l'instant", style = T.hero, color = C.Text1)
        Spacer(Modifier.height(10.dp))
        Text(
            if (mine) "Maintiens OK sur une affiche pour l'ajouter ici. Les séries et films commencés apparaissent aussi ici."
            else "Forja n'a renvoyé aucun titre dans cette rubrique.",
            style = T.body, color = C.Text2,
        )
    }
}

@Composable
private fun ErrorTab(onRetry: () -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.safeRequest(150) }
    Column(Modifier.padding(start = Safe.h, top = 30.dp).width(560.dp)) {
        Text("Forja ne répond pas", style = T.hero, color = C.Text1)
        Spacer(Modifier.height(10.dp))
        Text("Vérifie la connexion Internet de la TV, puis réessaie.", style = T.body, color = C.Text2)
        Spacer(Modifier.height(20.dp))
        PillButton("Réessayer", onClick = onRetry, icon = Icons.Filled.Refresh, primary = true, modifier = Modifier.focusRequester(fr))
    }
}
