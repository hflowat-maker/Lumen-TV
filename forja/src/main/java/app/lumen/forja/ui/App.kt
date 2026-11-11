package app.lumen.forja.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.lumen.forja.CrashReport
import app.lumen.forja.PlayerActivity
import app.lumen.forja.data.Category
import app.lumen.forja.data.Detail
import app.lumen.forja.data.Episode
import app.lumen.forja.data.ForjaApi
import app.lumen.forja.data.Item
import app.lumen.forja.data.Playable
import app.lumen.forja.data.Progress
import app.lumen.forja.data.Txt
import app.lumen.forja.safely
import app.lumen.forja.ui.components.Modal
import app.lumen.forja.ui.components.PillButton
import app.lumen.forja.ui.components.safeRequest
import app.lumen.forja.ui.screens.DetailScreen
import app.lumen.forja.ui.screens.GridScreen
import app.lumen.forja.ui.screens.HomeScreen
import app.lumen.forja.ui.screens.SearchScreen
import app.lumen.forja.ui.screens.Shelf
import app.lumen.forja.ui.theme.C
import app.lumen.forja.ui.theme.T
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data class Details(val item: Item) : Screen
    data class Grid(val slug: String, val title: String) : Screen
}

@Stable
class AppController(val activity: Activity) {
    val stack = mutableStateListOf<Screen>(Screen.Home)

    /** Étagères déjà chargées, affichées tout de suite au retour sur l'accueil. */
    val shelfCache = HashMap<String, List<Shelf>>()

    /** Rubriques du site (Séries, Films…), chargées une fois. */
    var menu by mutableStateOf<List<Category>?>(null)

    fun push(s: Screen) {
        stack.add(s)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun open(item: Item) = push(Screen.Details(item))

    // ---------------------------------------------------------------- lecture

    fun playEpisodes(show: Item, episodes: List<Episode>, index: Int, startSecs: Long, adTag: String? = null) {
        val queue = episodes.map { ep ->
            Playable(ep.id, ep.slug, Txt.episodeLabel(ep.number, ep.title), show.title, ep.image ?: show.tile, ep.durationSecs)
        }
        PlayerActivity.start(activity, queue, index, startSecs, show.slug, show.title, show.wide ?: show.tile, adTag)
    }

    fun playFilm(item: Item, startSecs: Long, adTag: String? = null) {
        val queue = listOf(Playable(item.id, item.slug, item.title, null, item.wide ?: item.tile, item.durationSecs))
        PlayerActivity.start(activity, queue, 0, startSecs, null, item.title, item.wide ?: item.tile, adTag)
    }

    /** Reprend depuis « Continuer à regarder » : la vidéo en cours, ou l'épisode suivant. */
    fun resume(p: Progress, scope: CoroutineScope) {
        val showSlug = p.showSlug
        if (showSlug == null) {
            val start = if (p.finished) 0L else p.position
            val queue = listOf(Playable(p.id, p.slug, p.showTitle, null, p.image, p.duration.toInt()))
            PlayerActivity.start(activity, queue, 0, start, null, p.showTitle, p.image, null)
            return
        }
        scope.launch {
            val detail: Detail? = safely("fiche de la série") { ForjaApi.detail(showSlug) }
            if (detail == null) {
                Toast.makeText(activity, "Forja ne répond pas, réessayez dans un instant", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val episodes = detail.allEpisodes
            val targetId = if (p.finished) p.next?.id ?: p.id else p.id
            val index = episodes.indexOfFirst { it.id == targetId }
            if (index < 0) {
                open(detail.item)
                return@launch
            }
            val start = if (!p.finished && targetId == p.id) p.position else 0L
            playEpisodes(detail.item, episodes, index, start, detail.adTag)
        }
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

    Box(Modifier.fillMaxSize().background(C.backdrop)) {
        holder.SaveableStateProvider(stateKey) {
            when (screen) {
                Screen.Home -> HomeScreen(app)
                Screen.Search -> SearchScreen(app)
                is Screen.Details -> DetailScreen(app, screen.item)
                is Screen.Grid -> GridScreen(app, screen.slug, screen.title)
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
            Text("Forja TV s'est fermé de façon inattendue", style = T.title, color = C.Text1)
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
