package app.lumen.tv.ui.screens

import android.app.Activity
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Job
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumen.tv.ui.components.Modal
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.lumen.tv.data.ChannelItem
import app.lumen.tv.data.ContentType
import app.lumen.tv.data.Lang
import app.lumen.tv.data.Repo
import app.lumen.tv.data.ResumeItem
import app.lumen.tv.data.SeeAllItem
import app.lumen.tv.data.Shelf
import app.lumen.tv.data.ShelfItem
import app.lumen.tv.data.Tab
import app.lumen.tv.data.TileItem
import app.lumen.tv.data.Text as Txt
import app.lumen.tv.safely
import app.lumen.tv.ui.AppController
import app.lumen.tv.ui.Screen
import app.lumen.tv.ui.components.Avatar
import app.lumen.tv.ui.components.Clickable
import app.lumen.tv.ui.components.LangFlag
import app.lumen.tv.ui.components.LumenMark
import app.lumen.tv.ui.components.PinDialog
import app.lumen.tv.ui.components.Safe
import app.lumen.tv.ui.components.Spinner
import app.lumen.tv.ui.components.safeRequest
import app.lumen.tv.ui.theme.C
import app.lumen.tv.ui.theme.T
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Liens entre la barre du haut et les étagères de l'onglet affiché. */
@Stable
private class ContentHandle {
    var focus: () -> Boolean = { false }
    var atTop: () -> Boolean = { true }
    var toTop: () -> Unit = {}
}

/**
 * Écran principal : barre du haut (onglets, langues, recherche, réglages, profil)
 * et étagères de l'onglet choisi, une à la fois.
 */
@Composable
fun MainScreen(app: AppController) {
    val repo = app.repo
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val langs by repo.langs.collectAsState()
    val profile by repo.profile.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var focusedTab by remember { mutableStateOf<Tab?>(null) }
    var contentHasFocus by remember { mutableStateOf(false) }
    // Où remettre le focus en revenant sur l'écran (étagères ou barre du haut).
    var contentWasFocused by rememberSaveable { mutableStateOf(true) }
    var wantContentFocus by remember { mutableStateOf(contentWasFocused) }
    var panel by remember { mutableStateOf(false) }
    var askPin by remember { mutableStateOf(false) }
    var lastBack by remember { mutableLongStateOf(0L) }
    val tabReqs = remember { Tab.entries.associateWith { FocusRequester() } }
    val handle = remember { ContentHandle() }
    val holder = rememberSaveableStateHolder()

    // Survoler un onglet l'affiche après une courte pause.
    LaunchedEffect(focusedTab) {
        val t = focusedTab ?: return@LaunchedEffect
        delay(320)
        if (focusedTab == t) tab = t
    }
    LaunchedEffect(Unit) {
        if (!contentWasFocused) tabReqs.getValue(tab).safeRequest(120)
    }

    fun openSettings() {
        if (profile?.isKid == true && app.prefs.parentalPin != null) askPin = true else app.push(Screen.Settings)
    }

    fun focusTab() {
        scope.launch { tabReqs.getValue(tab).safeRequest() }
    }

    BackHandler(enabled = !panel && !askPin) {
        when {
            contentHasFocus && !handle.atTop() -> handle.toTop()
            contentHasFocus -> focusTab()
            tab != Tab.HOME -> {
                tab = Tab.HOME
                focusedTab = null
                scope.launch { tabReqs.getValue(Tab.HOME).safeRequest() }
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
                tab = tab,
                langs = langs,
                profileName = profile?.name,
                profileColor = profile?.color ?: 0,
                tabReqs = tabReqs,
                onTabFocus = { t -> focusedTab = t },
                onTabBlur = { focusedTab = null },
                onTabClick = { t ->
                    tab = t
                    focusedTab = null
                    wantContentFocus = true
                },
                onToggleLang = { l ->
                    if (!repo.toggleLang(l)) Toast.makeText(context, "Garde au moins une langue cochée", Toast.LENGTH_SHORT).show()
                },
                onLangPanel = { panel = true },
                onSearch = { contentWasFocused = false; app.push(Screen.Search) },
                onSettings = { contentWasFocused = false; openSettings() },
                onProfile = { contentWasFocused = false; app.push(Screen.Profiles()) },
                onDown = {
                    val ft = focusedTab
                    if (ft != null && ft != tab) {
                        tab = ft
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
                holder.SaveableStateProvider(tab.name) {
                    TabContent(
                        app = app,
                        tab = tab,
                        langs = langs,
                        handle = handle,
                        wantFocus = wantContentFocus,
                        onFocusDone = { wantContentFocus = false },
                        onUpFromTop = { focusTab() },
                    )
                }
            }
        }
        if (panel) {
            LanguagePanel(app, onClose = {
                panel = false
                scope.launch {
                    delay(60)
                    if (!handle.focus()) tabReqs.getValue(tab).safeRequest()
                }
            })
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

// ---------------- Barre du haut ----------------

@Composable
private fun TopBar(
    tab: Tab,
    langs: Set<Lang>,
    profileName: String?,
    profileColor: Int,
    tabReqs: Map<Tab, FocusRequester>,
    onTabFocus: (Tab) -> Unit,
    onTabBlur: () -> Unit,
    onTabClick: (Tab) -> Unit,
    onToggleLang: (Lang) -> Unit,
    onLangPanel: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onProfile: () -> Unit,
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
        LumenMark(28.dp)
        Spacer(Modifier.width(18.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.onFocusChanged { if (!it.hasFocus) onTabBlur() },
        ) {
            Tab.entries.forEach { t ->
                val selected = t == tab
                Clickable(
                    onClick = { onTabClick(t) },
                    modifier = Modifier
                        .height(32.dp)
                        .focusRequester(tabReqs.getValue(t))
                        .onFocusChanged { if (it.isFocused) onTabFocus(t) },
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.onFocusChanged { if (it.hasFocus) onTabBlur() },
        ) {
            // Avec « Autres langues » cochée, 4 puces : libellés courts pour que la barre tienne sur l'écran.
            val compact = Lang.OTHER in langs
            listOf(Lang.FR, Lang.EN, Lang.TR).forEach { l -> LangChip(l, l in langs, compact, { onToggleLang(l) }, onLangPanel) }
            if (compact) LangChip(Lang.OTHER, true, true, { onToggleLang(Lang.OTHER) }, onLangPanel)
        }
        Spacer(Modifier.width(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.onFocusChanged { if (it.hasFocus) onTabBlur() },
        ) {
            BarIcon(Icons.Filled.Search, onSearch)
            BarIcon(Icons.Filled.Settings, onSettings)
            if (profileName != null) {
                Clickable(
                    onClick = onProfile,
                    modifier = Modifier.size(30.dp),
                    shape = CircleShape,
                    focusedScale = 1.12f,
                    borderWidth = 2.dp,
                    borderColor = C.Focus,
                ) {
                    Box(Modifier.fillMaxSize().clip(CircleShape)) { Avatar(profileName, profileColor, size = 30.dp) }
                }
            }
        }
    }
}

/** Puce de langue : OK coche ou décoche, OK maintenu ouvre le panneau des langues. */
@Composable
private fun LangChip(lang: Lang, on: Boolean, compact: Boolean, onToggle: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(15.dp)
    Clickable(
        onClick = onToggle,
        onLongClick = onLongClick,
        modifier = Modifier.height(30.dp),
        shape = shape,
        container = if (on) C.GoldSoft else Color.Transparent,
        focusedContainer = C.Focus,
        contentColor = if (on) C.Text1 else C.Text3,
        focusedContentColor = C.Bg,
        focusedScale = 1.05f,
    ) {
        Box(Modifier.matchParentSize().border(1.5.dp, if (on) C.Gold else C.Line, shape))
        Row(Modifier.align(Alignment.Center).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            LangFlag(lang, width = 16.dp, dim = !on)
            Spacer(Modifier.width(6.dp))
            Text(
                if (compact) when (lang) { Lang.FR -> "FR"; Lang.EN -> "EN"; Lang.TR -> "Turc"; Lang.OTHER -> "Autres" } else lang.chip,
                style = T.chip, maxLines = 1,
            )
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

// ---------------- Étagères d'un onglet ----------------

@Composable
private fun TabContent(
    app: AppController,
    tab: Tab,
    langs: Set<Lang>,
    handle: ContentHandle,
    wantFocus: Boolean,
    onFocusDone: () -> Unit,
    onUpFromTop: () -> Unit,
) {
    val repo = app.repo
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by repo.catalogVersion.collectAsState()
    val filterVersion by repo.filterVersion.collectAsState()
    val profile by repo.profile.collectAsState()
    val favorites by repo.favorites.collectAsState()
    val progress by repo.progress.collectAsState()
    val cacheKey = "${profile?.id}:${tab.name}:${Lang.encode(langs)}"
    var shelves by remember(cacheKey) { mutableStateOf(app.shelfCache[cacheKey]) }
    val favKey = if (tab == Tab.HOME || tab == Tab.MINE || tab == Tab.LIVE) favorites else null
    val progKey = if (tab == Tab.HOME || tab == Tab.MINE) progress else null

    LaunchedEffect(cacheKey, version, filterVersion, favKey, progKey) {
        val s = safely("étagères ${tab.label}") { repo.shelves(tab) } ?: emptyList()
        app.shelfCache[cacheKey] = s
        shelves = s
    }

    val listState = rememberLazyListState()
    var current by rememberSaveable { mutableIntStateOf(0) }
    val focuses = remember { HashMap<String, ShelfFocus>() }
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    val shelfPx = with(LocalDensity.current) { ShelfSize.height.toPx() }
    val list = shelves

    fun focusOf(key: String) = focuses.getOrPut(key) { ShelfFocus() }

    // Défilement vertical précis et doux : chaque étagère a la même hauteur.
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

    LaunchedEffect(list) {
        val l = list ?: return@LaunchedEffect
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
    LaunchedEffect(wantFocus, list) {
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

    fun click(shelf: Shelf, item: ShelfItem) {
        when (item) {
            is TileItem -> app.open(item.tile)
            is ResumeItem -> app.resume(item.progress, scope, context)
            is ChannelItem -> app.playChannel(shelf.items.filterIsInstance<ChannelItem>().map { it.channel }, item.channel)
            is SeeAllItem -> app.push(Screen.GroupGrid(item.target))
        }
    }

    fun longClick(item: ShelfItem) {
        when (item) {
            is TileItem -> app.toggleFavorite(context, item.tile.type, item.tile.id)
            is ChannelItem -> app.toggleFavorite(context, ContentType.LIVE, item.channel.id)
            is ResumeItem -> {
                repo.removeProgress(item.progress.kind, item.progress.itemId)
                Toast.makeText(context, "Retiré de Reprendre", Toast.LENGTH_SHORT).show()
            }
            is SeeAllItem -> Unit
        }
    }

    when {
        list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
        list.isEmpty() -> EmptyTab(tab)
        else -> CompositionLocalProvider(LocalBringIntoViewSpec provides NoAutoScroll) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 110.dp),
                userScrollEnabled = false,
            ) {
                itemsIndexed(list, key = { _, s -> s.key }) { i, shelf ->
                    ShelfView(
                        app = app,
                        shelf = shelf,
                        isCurrent = i == current,
                        focus = focusOf(shelf.key),
                        showFlags = langs.size > 1,
                        favorites = favorites,
                        progress = progress,
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
                            } else if (c < list.lastIndex) {
                                goTo(c + 1)
                            }
                            true
                        },
                        onClick = { click(shelf, it) },
                        onLongClick = { longClick(it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyTab(tab: Tab) {
    Column(Modifier.padding(start = Safe.h, top = 30.dp).width(520.dp)) {
        Text(
            if (tab == Tab.MINE) "Ta liste est vide" else "Rien à afficher dans ces langues",
            style = T.hero, color = C.Text1,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (tab == Tab.MINE) "Maintiens OK sur une affiche ou une chaîne pour l'ajouter ici. Les programmes commencés apparaissent aussi ici."
            else "Coche une autre langue en haut de l'écran, ou maintiens OK sur une langue pour voir toutes les options.",
            style = T.body, color = C.Text2,
        )
    }
}

// ---------------- Panneau des langues ----------------

/** Panneau latéral : cocher les langues affichées, avec le nombre de titres de chacune. */
@Composable
fun LanguagePanel(app: AppController, onClose: () -> Unit) {
    val repo = app.repo
    val context = LocalContext.current
    val langs by repo.langs.collectAsState()
    val counts by produceState<Map<Lang, Repo.LangCount>?>(null) { value = safely("langues") { repo.langCounts() } }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.safeRequest(120) }

    Modal(onClose) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(380.dp)
                .background(C.Surface2)
                .padding(horizontal = 32.dp, vertical = 34.dp)
        ) {
            Text("Langues", style = T.hero.copy(fontSize = 36.sp, lineHeight = 38.sp), color = C.Text1)
            Spacer(Modifier.height(8.dp))
            Text(
                "Coche celles que tu regardes. L'accueil, les films, les séries et les chaînes ne montrent que ces langues.",
                style = T.body, color = C.Text2,
            )
            Spacer(Modifier.height(20.dp))
            Lang.entries.forEachIndexed { i, l ->
                val on = l in langs
                val c = counts?.get(l)
                val detail = when {
                    c == null -> null
                    else -> listOfNotNull(
                        c.movies.takeIf { it > 0 }?.let { Txt.count(it, "film", "films") },
                        c.series.takeIf { it > 0 }?.let { Txt.count(it, "série", "séries") },
                        c.channels.takeIf { it > 0 }?.let { Txt.count(it, "chaîne", "chaînes") },
                    ).joinToString(", ").ifEmpty { "Rien chez ton fournisseur" }
                }
                Clickable(
                    onClick = {
                        if (!repo.toggleLang(l)) Toast.makeText(context, "Garde au moins une langue cochée", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    shape = RoundedCornerShape(12.dp),
                    container = Color(0x0DF3E7D3),
                    focusedContainer = C.Focus,
                    contentColor = C.Text1,
                    focusedContentColor = C.Bg,
                    focusedScale = 1.02f,
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        LangFlag(l, width = 26.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(l.label, style = T.row, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (detail != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(detail, style = T.meta, color = androidx.tv.material3.LocalContentColor.current.copy(alpha = 0.7f), maxLines = 1)
                                    if (l == Lang.TR) {
                                        Spacer(Modifier.width(8.dp))
                                        Text("تركي مترجم", style = T.meta, color = C.Gold, maxLines = 1)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Box(
                            Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
                                .background(if (on) C.Gold else Color.Transparent)
                                .border(2.dp, if (on) C.Gold else C.Text3, RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (on) Icon(Icons.Filled.Check, null, tint = C.Bg, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Chaque profil garde son propre réglage.\nRetour pour fermer.",
                style = T.meta, color = C.Text2,
            )
        }
    }
    }
}

