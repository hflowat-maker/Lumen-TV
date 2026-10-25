package app.lumen.tv.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.lumen.tv.CrashReport
import app.lumen.tv.data.ContentType
import app.lumen.tv.ui.components.Modal
import app.lumen.tv.ui.components.PillButton
import app.lumen.tv.ui.components.safeRequest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import app.lumen.tv.data.PlayRequest
import app.lumen.tv.data.Prefs
import app.lumen.tv.data.Repo
import app.lumen.tv.data.Tile
import app.lumen.tv.player.PlayerScreen
import app.lumen.tv.ui.components.Avatar
import app.lumen.tv.ui.components.Clickable
import app.lumen.tv.ui.components.PinDialog
import app.lumen.tv.ui.components.Safe
import app.lumen.tv.ui.components.restoreFocusGroup
import app.lumen.tv.ui.screens.GridScreen
import app.lumen.tv.ui.screens.HomeScreen
import app.lumen.tv.ui.screens.KidCategoriesScreen
import app.lumen.tv.ui.screens.LiveScreen
import app.lumen.tv.ui.screens.LoginScreen
import app.lumen.tv.ui.screens.MovieDetailScreen
import app.lumen.tv.ui.screens.MoviesScreen
import app.lumen.tv.ui.screens.MyListScreen
import app.lumen.tv.ui.screens.ProfileEditScreen
import app.lumen.tv.ui.screens.ProfilesScreen
import app.lumen.tv.ui.screens.SearchScreen
import app.lumen.tv.ui.screens.SeriesDetailScreen
import app.lumen.tv.ui.screens.SeriesScreen
import app.lumen.tv.ui.screens.SettingsScreen
import app.lumen.tv.ui.screens.SyncScreen
import app.lumen.tv.ui.theme.C
import app.lumen.tv.ui.theme.T

sealed interface Screen {
    data object Splash : Screen
    data object Login : Screen
    data object Sync : Screen
    data class Profiles(val manage: Boolean = false) : Screen
    data class ProfileEdit(val id: Long) : Screen
    data object Main : Screen
    data class Movie(val tile: Tile) : Screen
    data class Series(val tile: Tile) : Screen
    data class Grid(val type: ContentType, val categoryId: String, val title: String) : Screen
    data class Player(val request: PlayRequest) : Screen
    data object Settings : Screen
    data object KidCategories : Screen
}

enum class Tab(val label: String, val icon: ImageVector) {
    SEARCH("Recherche", Icons.Filled.Search),
    HOME("Accueil", Icons.Filled.Home),
    LIVE("TV en direct", Icons.Filled.LiveTv),
    MOVIES("Films", Icons.Filled.Movie),
    SERIES("Séries", Icons.Filled.VideoLibrary),
    MYLIST("Ma liste", Icons.Filled.FavoriteBorder),
}

@Stable
class AppController(val repo: Repo, val prefs: Prefs) {
    val stack = mutableStateListOf<Screen>(Screen.Splash)
    var tab by mutableStateOf(Tab.HOME)

    fun push(s: Screen) {
        stack.add(s)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun reset(s: Screen) {
        stack.clear()
        stack.add(s)
    }

    fun open(tile: Tile) = push(if (tile.type == ContentType.SERIES) Screen.Series(tile) else Screen.Movie(tile))

    fun play(request: PlayRequest) = push(Screen.Player(request))

    /** Remplace le lecteur courant (épisode suivant) sans empiler. */
    fun replaceTop(s: Screen) {
        if (stack.isNotEmpty()) stack[stack.lastIndex] = s else stack.add(s)
    }

    fun goHome() {
        tab = Tab.HOME
        reset(Screen.Main)
    }
}

@Composable
fun AppRoot(app: AppController) {
    val holder = rememberSaveableStateHolder()
    val knownKeys = remember { mutableStateListOf<String>() }
    val index = app.stack.lastIndex
    val screen = app.stack.last()
    val stateKey = "$index:${screen::class.java.name}"

    // Oublie l'état des écrans dépilés pour qu'un nouvel écran ne récupère pas un vieux défilement.
    LaunchedEffect(app.stack.size, stateKey) {
        val live = app.stack.mapIndexed { i, s -> "$i:${s::class.java.name}" }.toSet()
        knownKeys.filter { it !in live }.forEach { holder.removeState(it) }
        knownKeys.removeAll { it !in live }
        if (stateKey !in knownKeys) knownKeys.add(stateKey)
    }

    BackHandler(enabled = app.stack.size > 1) { app.back() }

    val context = LocalContext.current
    var crash by remember { mutableStateOf(CrashReport.read(context)) }

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        holder.SaveableStateProvider(stateKey) {
            when (screen) {
                Screen.Splash -> Splash(app)
                Screen.Login -> LoginScreen(app)
                Screen.Sync -> SyncScreen(app)
                is Screen.Profiles -> ProfilesScreen(app, screen.manage)
                is Screen.ProfileEdit -> ProfileEditScreen(app, screen.id)
                Screen.Main -> MainShell(app)
                is Screen.Movie -> MovieDetailScreen(app, screen.tile)
                is Screen.Series -> SeriesDetailScreen(app, screen.tile)
                is Screen.Grid -> GridScreen(app, screen.type, screen.categoryId, screen.title)
                is Screen.Player -> key(screen.request) { PlayerScreen(app, screen.request) }
                Screen.Settings -> SettingsScreen(app)
                Screen.KidCategories -> KidCategoriesScreen(app)
            }
        }
    }

    crash?.let { report ->
        CrashDialog(report) {
            CrashReport.clear(context)
            crash = null
        }
    }
}

@Composable
private fun CrashDialog(report: String, onClose: () -> Unit) {
    val fr = remember { FocusRequester() }
    Modal(onClose) {
        Column(
            Modifier.width(880.dp).clip(RoundedCornerShape(16.dp)).background(C.Surface1).padding(24.dp)
        ) {
            Text("Lumen TV s'est fermé de façon inattendue", style = T.title, color = C.Text1)
            Spacer(Modifier.height(6.dp))
            Text("Prenez cet écran en photo et envoyez-la à Claude pour corriger le problème.", style = T.body, color = C.Text2)
            Spacer(Modifier.height(12.dp))
            Text(
                report.lines().filter { it.isNotBlank() }.take(26).joinToString("\n"),
                style = T.small.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp, lineHeight = 11.sp),
                color = C.Text1,
            )
            Spacer(Modifier.height(14.dp))
            PillButton("Fermer", onClick = onClose, modifier = Modifier.focusRequester(fr))
        }
    }
    LaunchedEffect(Unit) { fr.safeRequest(100) }
}

@Composable
private fun Splash(app: AppController) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(C.Gold))
            Spacer(Modifier.width(14.dp))
            Text("Lumen", style = T.hero, color = C.Text1)
        }
    }
    LaunchedEffect(Unit) {
        val repo = app.repo
        when {
            repo.account == null -> app.reset(Screen.Login)
            !repo.hasCatalog() -> app.reset(Screen.Sync)
            else -> {
                app.reset(Screen.Profiles())
                repo.refreshAccountInBackground()
                if (repo.catalogIsStale()) repo.syncInBackground()
            }
        }
    }
}

private val RailCollapsed = 72.dp
private val RailExpanded = 228.dp

@Composable
fun MainShell(app: AppController) {
    val profile by app.repo.profile.collectAsState()
    val context = LocalContext.current
    val tabHolder = rememberSaveableStateHolder()
    val railFallback = remember { FocusRequester() }
    val contentFr = remember { FocusRequester() }
    var railFocused by remember { mutableStateOf(false) }
    var lastBack by remember { mutableLongStateOf(0L) }
    var askPin by remember { mutableStateOf(false) }

    BackHandler {
        when {
            !railFocused -> try { railFallback.requestFocus() } catch (_: IllegalStateException) {}
            app.tab != Tab.HOME -> app.tab = Tab.HOME
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBack < 2500) {
                    (context as? Activity)?.finish()
                } else {
                    lastBack = now
                    Toast.makeText(context, "Appuyez à nouveau sur Retour pour quitter", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun openSettings() {
        val pin = app.prefs.parentalPin
        if (profile?.isKid == true && pin != null) askPin = true else app.push(Screen.Settings)
    }

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = RailCollapsed)
                .focusRequester(contentFr)
                .restoreFocusGroup()
        ) {
            val tab = app.tab
            tabHolder.SaveableStateProvider(tab.name) {
                key(tab) {
                    when (tab) {
                        Tab.SEARCH -> SearchScreen(app)
                        Tab.HOME -> HomeScreen(app)
                        Tab.LIVE -> LiveScreen(app)
                        Tab.MOVIES -> MoviesScreen(app)
                        Tab.SERIES -> SeriesScreen(app)
                        Tab.MYLIST -> MyListScreen(app)
                    }
                }
            }
        }

        if (railFocused) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0f to Color(0xF20A0A0E), 0.35f to Color(0xB30A0A0E), 0.7f to Color.Transparent)
                )
            )
        }

        val width by animateDpAsState(if (railFocused) RailExpanded else RailCollapsed, tween(180), label = "rail")
        Column(
            Modifier
                .fillMaxHeight()
                .width(width)
                .padding(vertical = Safe.v, horizontal = 12.dp)
                .onFocusChanged { railFocused = it.hasFocus }
                .restoreFocusGroup(railFallback),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val p = profile
            if (p != null) {
                Clickable(
                    onClick = { app.push(Screen.Profiles()) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    focusedContainer = Color.White,
                    focusedContentColor = C.Bg,
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(p.name, p.color, size = 36.dp)
                        if (railFocused) {
                            Spacer(Modifier.width(12.dp))
                            Text(p.name, style = T.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Tab.entries.forEach { t ->
                RailItem(
                    icon = t.icon,
                    label = t.label,
                    selected = app.tab == t,
                    expanded = railFocused,
                    modifier = if (app.tab == t) Modifier.focusRequester(railFallback) else Modifier,
                    onClick = {
                        if (app.tab == t) {
                            try { contentFr.requestFocus() } catch (_: IllegalStateException) {}
                        } else {
                            app.tab = t
                        }
                    },
                )
            }
            Spacer(Modifier.weight(1f))
            RailItem(
                icon = Icons.Filled.Settings,
                label = "Réglages",
                selected = false,
                expanded = railFocused,
                onClick = { openSettings() },
            )
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

@Composable
private fun RailItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Clickable(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(22.dp),
        container = Color.Transparent,
        focusedContainer = Color.White,
        contentColor = if (selected) C.Text1 else C.Text2,
        focusedContentColor = C.Bg,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
            if (expanded) {
                Spacer(Modifier.width(14.dp))
                Text(label, style = T.label, maxLines = 1)
            }
        }
        if (selected && !expanded) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(width = 14.dp, height = 3.dp)
                    .clip(RoundedCornerShape(2.dp)).background(C.Gold)
            )
        }
    }
}
