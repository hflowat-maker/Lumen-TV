package app.lumen.forja.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.lumen.forja.data.Item
import app.lumen.forja.data.Progress
import app.lumen.forja.data.Txt
import app.lumen.forja.ui.components.Clickable
import app.lumen.forja.ui.components.ProgressLine
import app.lumen.forja.ui.components.Safe
import app.lumen.forja.ui.theme.C
import app.lumen.forja.ui.theme.Sans
import app.lumen.forja.ui.theme.T
import coil.compose.AsyncImage
import coil.request.ImageRequest

// ---------------- Modèle des étagères ----------------

sealed interface ShelfItem {
    val key: String
}

data class TileItem(val item: Item) : ShelfItem {
    override val key: String get() = item.key
}

data class ResumeItem(val progress: Progress) : ShelfItem {
    override val key: String get() = "r:${progress.groupKey}"
}

data class SeeAllItem(val slug: String, val title: String, val total: Int) : ShelfItem {
    override val key: String get() = "all:$slug"
}

data class Shelf(val key: String, val title: String, val subtitle: String?, val items: List<ShelfItem>)

// ---------------- Mesures (en dp, écran TV de 960 × 540) ----------------

object ShelfSize {
    val big = DpSize(400.dp, 225.dp)
    val poster = DpSize(104.dp, 156.dp)
    val wide = DpSize(166.dp, 93.dp)
    val gap = 14.dp
    val stripTop = 100.dp
    val height = 372.dp
    val titleMax = 440.dp
    val infoStart = 512.dp
    val infoWidth = 400.dp
}

/** Aucun défilement automatique : les étagères et les cartes sont placées à la main. */
val NoAutoScroll: BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/** Accès à la grande carte d'une étagère, pour y ramener le focus. */
@Stable
class ShelfFocus {
    val requester = FocusRequester()
    var attached = 0

    fun request(): Boolean {
        if (attached <= 0) return false
        return try {
            requester.requestFocus()
            true
        } catch (_: IllegalStateException) {
            false
        }
    }
}

// ---------------- Étagère ----------------

/**
 * Une étagère façon Lumen TV : la grande carte à gauche garde le focus, gauche/droite change
 * son contenu et les cartes suivantes glissent à côté ; résumé du titre à droite.
 */
@Composable
fun ShelfView(
    shelf: Shelf,
    isCurrent: Boolean,
    focus: ShelfFocus,
    favorites: Set<String>,
    onFocusShelf: () -> Unit,
    onVertical: (up: Boolean) -> Boolean,
    onClick: (ShelfItem) -> Unit,
    onLongClick: (ShelfItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var selected by rememberSaveable(shelf.key) { mutableIntStateOf(0) }
    val rowState = rememberLazyListState(initialFirstVisibleItemIndex = selected + 1)
    val last = (shelf.items.size - 1).coerceAtLeast(0)
    val sel = selected.coerceIn(0, last)
    val selItem = shelf.items.getOrNull(sel)
    val dim by animateFloatAsState(if (isCurrent) 1f else 0.28f, tween(250), label = "dim")
    val small = shelf.items.firstOrNull()?.let { smallSize(it) } ?: ShelfSize.poster
    val stepPx = with(density) { (small.width + ShelfSize.gap).toPx() }

    var rowReady by remember { mutableStateOf(false) }
    LaunchedEffect(sel, stepPx) {
        val target = sel + 1
        val now = rowState.firstVisibleItemIndex * stepPx + rowState.firstVisibleItemScrollOffset
        val delta = target * stepPx - now
        if (kotlin.math.abs(delta) > stepPx * 6 || !rowReady) rowState.scrollToItem(target)
        else if (delta != 0f) rowState.animateScrollBy(delta, tween(240, easing = FastOutSlowInEasing))
        rowReady = true
    }

    DisposableEffect(Unit) {
        focus.attached++
        onDispose { focus.attached-- }
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(ShelfSize.height)
            .onFocusChanged { if (it.hasFocus) onFocusShelf() }
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.DirectionDown -> onVertical(false)
                    Key.DirectionUp -> onVertical(true)
                    Key.DirectionRight -> {
                        if (sel < last) selected = sel + 1
                        true
                    }
                    Key.DirectionLeft -> {
                        if (sel > 0) selected = sel - 1
                        true
                    }
                    else -> false
                }
            }
    ) {
        Column(Modifier.padding(start = Safe.h).width(ShelfSize.titleMax).graphicsLayer { alpha = dim }) {
            ShelfTitle(shelf.title)
            if (isCurrent && shelf.subtitle != null) {
                Text(shelf.subtitle, style = T.meta, color = C.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (isCurrent && selItem != null) {
            InfoPanel(
                item = selItem,
                modifier = Modifier.padding(start = ShelfSize.infoStart, top = 6.dp).width(ShelfSize.infoWidth),
            )
        }
        val stripAlpha = 0.55f + 0.45f * ((dim - 0.28f) / 0.72f)
        if (selItem != null) {
            HeroCard(
                item = selItem,
                favorite = isFavorite(selItem, favorites),
                onClick = { onClick(selItem) },
                onLongClick = { onLongClick(selItem) },
                modifier = Modifier
                    .padding(start = Safe.h, top = ShelfSize.stripTop)
                    .graphicsLayer { alpha = stripAlpha }
                    .focusRequester(focus.requester),
            )
        }
        LazyRow(
            state = rowState,
            modifier = Modifier
                .padding(start = Safe.h + ShelfSize.big.width + ShelfSize.gap, top = ShelfSize.stripTop)
                .fillMaxWidth()
                .height(ShelfSize.big.height)
                .graphicsLayer { alpha = stripAlpha },
            horizontalArrangement = Arrangement.spacedBy(ShelfSize.gap),
            verticalAlignment = Alignment.Bottom,
            userScrollEnabled = false,
        ) {
            items(shelf.items, key = { it.key }) { item ->
                SmallCard(item = item, size = smallSize(item), favorite = isFavorite(item, favorites))
            }
            // Espace final : permet de faire défiler jusqu'après la dernière carte.
            item(key = "end") { Spacer(Modifier.width(ShelfSize.infoWidth + ShelfSize.gap)) }
        }
    }
}

private fun isFavorite(item: ShelfItem, favorites: Set<String>): Boolean =
    item is TileItem && item.item.slug in favorites

/** Grand titre d'étagère en Instrument Serif ; rapetisse s'il ne tient pas sur une ligne. */
@Composable
private fun ShelfTitle(title: String) {
    val measurer = rememberTextMeasurer()
    val maxPx = with(LocalDensity.current) { ShelfSize.titleMax.roundToPx() }
    val style = remember(title, maxPx) {
        listOf(58, 46, 38, 32).map { s -> T.shelf.copy(fontSize = s.sp, lineHeight = (s + 2).sp) }
            .firstOrNull { measurer.measure(title, it, maxLines = 1, softWrap = false).size.width <= maxPx }
            ?: T.shelf.copy(fontSize = 32.sp, lineHeight = 34.sp)
    }
    Box(Modifier.height(66.dp), contentAlignment = Alignment.BottomStart) {
        Text(title, style = style, color = C.Text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------- Cartes ----------------

private fun smallSize(item: ShelfItem): DpSize = when (item) {
    is TileItem, is SeeAllItem -> ShelfSize.poster
    is ResumeItem -> ShelfSize.wide
}

/** Grande carte de l'étagère : seule carte focalisable, son contenu suit la sélection. */
@Composable
private fun HeroCard(
    item: ShelfItem,
    favorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
) {
    Clickable(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.size(ShelfSize.big),
        shape = RoundedCornerShape(16.dp),
        container = C.Surface2,
        focusedContainer = C.Surface2,
        focusedScale = 1f,
        borderWidth = 3.dp,
        borderColor = C.Gold,
    ) {
        // Fondu bref quand le contenu change, pour adoucir le passage d'une carte à l'autre.
        val fade = remember(item.key) { Animatable(0.35f) }
        LaunchedEffect(item.key) { fade.animateTo(1f, tween(170)) }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) {
            when (item) {
                is TileItem -> BigTile(item.item)
                is ResumeItem -> ResumeCard(item.progress, big = true)
                is SeeAllItem -> SeeAllCard(item, big = true)
            }
        }
        if (favorite) FavoriteMark()
        if (item is ResumeItem && !item.progress.finished && item.progress.fraction > 0f) {
            ProgressLine(
                item.progress.fraction,
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

/** Petite carte d'aperçu (non focalisable) à droite de la grande carte. */
@Composable
private fun SmallCard(item: ShelfItem, size: DpSize, favorite: Boolean) {
    Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(C.Surface2)) {
        when (item) {
            is TileItem -> SmallTile(item.item)
            is ResumeItem -> ResumeCard(item.progress, big = false)
            is SeeAllItem -> SeeAllCard(item, big = false)
        }
        if (favorite) FavoriteMark()
        if (item is ResumeItem && !item.progress.finished && item.progress.fraction > 0f) {
            ProgressLine(
                item.progress.fraction,
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun BoxScope.FavoriteMark() {
    Box(
        Modifier.align(Alignment.TopStart).padding(8.dp).size(22.dp).clip(CircleShape).background(Color(0xB31A0B12)),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Filled.Favorite, null, tint = C.Gold, modifier = Modifier.size(12.dp)) }
}

// Affiches : même requête (même taille) pour la petite carte et la grande, l'image est donc
// déjà en mémoire quand une carte devient la grande. Le fond flou est une version minuscule agrandie.
@Composable
private fun posterRequest(url: String): ImageRequest {
    val ctx = LocalContext.current
    return remember(url) { ImageRequest.Builder(ctx).data(url).size(274, 407).build() }
}

@Composable
private fun tinyRequest(url: String): ImageRequest {
    val ctx = LocalContext.current
    return remember(url) { ImageRequest.Builder(ctx).data(url).size(30, 45).memoryCacheKey("$url#flou").build() }
}

@Composable
private fun wideRequest(url: String): ImageRequest {
    val ctx = LocalContext.current
    return remember(url) { ImageRequest.Builder(ctx).data(url).size(960, 540).build() }
}

fun fallback(seed: String): Brush {
    val base = C.avatar(seed.hashCode())
    return Brush.linearGradient(listOf(base.copy(alpha = 0.45f), C.Surface3, C.Surface1))
}

@Composable
private fun BoxScope.SmallTile(item: Item) {
    Box(Modifier.fillMaxSize().background(fallback(item.title)), contentAlignment = Alignment.Center) {
        Text(
            item.title, style = T.cardTitle, color = C.Text1, maxLines = 4, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(10.dp),
        )
    }
    val img = item.poster
    if (img != null) {
        AsyncImage(model = posterRequest(img), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else if (item.tile != null) {
        AsyncImage(model = item.tile, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun BoxScope.BigTile(item: Item) {
    val wide = item.wide
    if (wide != null) {
        Box(Modifier.fillMaxSize().background(fallback(item.title)))
        AsyncImage(model = wideRequest(wide), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xD91A0B12))))
        Text(
            item.title, style = T.cardTitle.copy(fontSize = 30.sp, lineHeight = 32.sp), color = C.Text1,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 22.dp),
        )
    } else {
        PosterComposite(item.poster ?: item.tile, item.title)
    }
    if (!item.free) Badge("Abonnés", Modifier.align(Alignment.TopEnd).padding(14.dp))
}

/** Affiche verticale dans une carte horizontale : fond flou, affiche nette à droite, titre à gauche. */
@Composable
private fun BoxScope.PosterComposite(image: String?, title: String) {
    Box(Modifier.fillMaxSize().background(fallback(title)))
    if (image != null) {
        AsyncImage(
            model = tinyRequest(image), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.75f },
        )
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(0f to Color(0xE61A0B12), 0.55f to Color(0x8C1A0B12), 1f to Color(0x331A0B12))
        )
    )
    if (image != null) {
        AsyncImage(
            model = posterRequest(image), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(12.dp)
                .fillMaxHeight()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(9.dp)),
        )
    }
    Text(
        title, style = T.cardTitle.copy(fontSize = 28.sp, lineHeight = 30.sp), color = C.Text1,
        maxLines = 3, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 22.dp).width(222.dp),
    )
}

@Composable
private fun Badge(text: String, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(7.dp)).background(C.Text1).padding(horizontal = 9.dp, vertical = 3.dp)) {
        Text(text, style = T.small.copy(fontWeight = FontWeight.Bold), color = C.Bg)
    }
}

@Composable
private fun BoxScope.ResumeCard(p: Progress, big: Boolean) {
    Box(Modifier.fillMaxSize().background(fallback(p.showTitle)))
    if (p.image != null) {
        AsyncImage(model = p.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xE01A0B12))))
    if (big) {
        val label = if (p.finished) p.next?.title else p.episodeTitle
        label?.let { Badge(it, Modifier.align(Alignment.TopStart).padding(14.dp)) }
        Text(
            p.showTitle, style = T.cardTitle.copy(fontSize = 26.sp, lineHeight = 28.sp), color = C.Text1,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 18.dp, end = 18.dp, bottom = 66.dp),
        )
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 22.dp)
                .clip(RoundedCornerShape(22.dp)).background(C.Text1).padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(C.Bg), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, null, tint = C.Text1, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(9.dp))
            Text(
                if (p.finished) "Épisode suivant" else "Reprendre à ${Txt.minutes(p.position)}",
                style = T.label, color = C.Bg,
            )
        }
    } else {
        Text(
            p.showTitle, style = T.small, color = C.Text1, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = 16.dp),
        )
    }
}

@Composable
private fun BoxScope.SeeAllCard(item: SeeAllItem, big: Boolean) {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(C.Surface3, C.Surface1))))
    Column(Modifier.align(Alignment.CenterStart).padding(horizontal = if (big) 26.dp else 12.dp)) {
        Text("Tout voir", style = if (big) T.display else T.cardTitle.copy(fontSize = 22.sp), color = C.Text1)
        Spacer(Modifier.height(4.dp))
        Text(Txt.count(item.total, "titre", "titres"), style = T.meta, color = C.Text2)
        Spacer(Modifier.height(10.dp))
        Icon(Icons.Filled.ChevronRight, null, tint = C.Gold, modifier = Modifier.size(if (big) 30.dp else 22.dp))
    }
}

// ---------------- Panneau d'informations ----------------

@Composable
private fun InfoPanel(item: ShelfItem, modifier: Modifier) {
    Column(modifier) {
        when (item) {
            is TileItem -> {
                val t = item.item
                InfoTitle(t.title, if (t.description.isNullOrBlank()) 2 else 1)
                Spacer(Modifier.height(6.dp))
                InfoMeta(listOf(if (t.isSeries) "Série" else null, Txt.duration(t.durationSecs), if (!t.free) "Réservé aux abonnés" else null))
                val d = t.description?.takeIf { it.isNotBlank() && !it.equals(t.title, ignoreCase = true) }
                if (d != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(d, style = T.body, color = C.Text2, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            is ResumeItem -> {
                val p = item.progress
                InfoTitle(p.showTitle, 2)
                Spacer(Modifier.height(6.dp))
                InfoMeta(listOf(if (p.finished) p.next?.title else p.episodeTitle))
                Spacer(Modifier.height(8.dp))
                Text(
                    if (p.finished) "Prêt à démarrer. OK pour lancer, maintenir OK pour retirer de la liste."
                    else "${Txt.remaining(p.duration - p.position)}. OK pour reprendre, maintenir OK pour retirer de la liste.",
                    style = T.body, color = C.Text2, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
            is SeeAllItem -> {
                InfoTitle("Tout voir", 1)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Les ${Txt.count(item.total, "titre", "titres")} de « ${item.title} ».",
                    style = T.body, color = C.Text2, maxLines = 3,
                )
            }
        }
    }
}

@Composable
private fun InfoTitle(text: String, maxLines: Int) {
    Text(
        text,
        style = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 23.sp, lineHeight = 27.sp, letterSpacing = (-0.3).sp),
        color = C.Text1, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun InfoMeta(parts: List<String?>) {
    val items = parts.filterNotNull().filter { it.isNotBlank() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        items.forEachIndexed { i, s ->
            if (i > 0) Spacer(Modifier.width(14.dp))
            Text(s, style = T.meta, color = if (i == 0) C.Gold else C.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
