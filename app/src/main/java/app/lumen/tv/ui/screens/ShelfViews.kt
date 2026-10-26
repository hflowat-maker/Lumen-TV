package app.lumen.tv.ui.screens

import android.os.Build
import android.view.TextureView
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.lumen.tv.data.Channel
import app.lumen.tv.data.ChannelItem
import app.lumen.tv.data.ContentType
import app.lumen.tv.data.EpgEntry
import app.lumen.tv.data.Lang
import app.lumen.tv.data.MovieInfo
import app.lumen.tv.data.ProgressKind
import app.lumen.tv.data.ResumeItem
import app.lumen.tv.data.SeeAllItem
import app.lumen.tv.data.Shelf
import app.lumen.tv.data.ShelfItem
import app.lumen.tv.data.Tile
import app.lumen.tv.data.TileItem
import app.lumen.tv.data.WatchProgress
import app.lumen.tv.data.Text as Txt
import app.lumen.tv.safely
import app.lumen.tv.ui.AppController
import app.lumen.tv.ui.components.ChannelLogo
import app.lumen.tv.ui.components.Clickable
import app.lumen.tv.ui.components.LangFlag
import app.lumen.tv.ui.components.ProgressLine
import app.lumen.tv.ui.components.Safe
import app.lumen.tv.ui.theme.C
import app.lumen.tv.ui.theme.Sans
import app.lumen.tv.ui.theme.Serif
import app.lumen.tv.ui.theme.T
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------- Mesures (en dp, écran TV de 960 × 540) ----------------

object ShelfSize {
    val big = DpSize(400.dp, 225.dp)
    val poster = DpSize(104.dp, 156.dp)
    val wide = DpSize(166.dp, 93.dp)
    val gap = 14.dp
    /** Hauteur réservée au titre et à sa ligne d'explication, au-dessus des cartes. */
    val stripTop = 100.dp
    /** Hauteur d'une étagère : le titre de la suivante dépasse en bas de l'écran. */
    val height = 372.dp
    val titleMax = 440.dp
    val infoStart = 512.dp
    val infoWidth = 400.dp
}

/** Aucun défilement automatique : les étagères et les cartes sont placées à la main. */
val NoAutoScroll: BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/** Accès à la carte sélectionnée d'une étagère, pour y ramener le focus. */
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

@Composable
fun ShelfView(
    app: AppController,
    shelf: Shelf,
    isCurrent: Boolean,
    focus: ShelfFocus,
    showFlags: Boolean,
    favorites: Set<String>,
    progress: Map<String, WatchProgress>,
    onFocusShelf: () -> Unit,
    onVertical: (up: Boolean) -> Boolean,
    onClick: (ShelfItem) -> Unit,
    onLongClick: (ShelfItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val rowState = rememberLazyListState()
    var selected by rememberSaveable(shelf.key) { mutableIntStateOf(0) }
    val sel = selected.coerceIn(0, (shelf.items.size - 1).coerceAtLeast(0))
    val selItem = shelf.items.getOrNull(sel)
    val dim by animateFloatAsState(if (isCurrent) 1f else 0.28f, tween(250), label = "dim")

    // Fiche du film sélectionné (résumé, image horizontale), chargée après une courte pause.
    val detail by produceState<MovieInfo?>(null, selItem?.key, isCurrent) {
        val t = (selItem as? TileItem)?.tile
        if (t == null || t.type != ContentType.MOVIE) {
            value = null
            return@produceState
        }
        value = app.repo.cachedMovieInfo(t.id)
        if (value == null && isCurrent) {
            delay(450)
            value = safely("fiche du film") { app.repo.movieInfo(t.id) }
        }
    }
    // Guide des programmes de la chaîne sélectionnée.
    val epg by produceState<List<EpgEntry>?>(null, selItem?.key, isCurrent) {
        val ch = (selItem as? ChannelItem)?.channel
        if (ch == null) {
            value = null
            return@produceState
        }
        value = app.repo.cachedEpg(ch.id)
        if (isCurrent) {
            delay(300)
            value = safely("guide des programmes") { app.repo.epg(ch.id) } ?: value
        }
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
                item = selItem, detail = detail, epg = epg, showLang = showFlags, progress = progress,
                modifier = Modifier.padding(start = ShelfSize.infoStart, top = 6.dp).width(ShelfSize.infoWidth),
            )
        }
        LazyRow(
            state = rowState,
            modifier = Modifier
                .padding(top = ShelfSize.stripTop)
                .fillMaxWidth()
                .height(ShelfSize.big.height)
                .graphicsLayer { alpha = 0.55f + 0.45f * ((dim - 0.28f) / 0.72f) },
            contentPadding = PaddingValues(start = Safe.h, end = Safe.h),
            horizontalArrangement = Arrangement.spacedBy(ShelfSize.gap),
            verticalAlignment = Alignment.Bottom,
            userScrollEnabled = false,
        ) {
            itemsIndexed(shelf.items, key = { _, it -> it.key }) { idx, item ->
                val isSel = idx == sel
                DisposableEffect(isSel) {
                    if (isSel) focus.attached++
                    onDispose { if (isSel) focus.attached-- }
                }
                ShelfCard(
                    app = app,
                    item = item,
                    big = isSel,
                    detail = if (isSel) detail else null,
                    epg = if (isSel) epg else null,
                    showFlag = showFlags,
                    favorite = isFavorite(item, favorites),
                    progress = progressOf(item, progress),
                    onClick = { onClick(item) },
                    onLongClick = { onLongClick(item) },
                    modifier = Modifier
                        .then(if (isSel) Modifier.focusRequester(focus.requester) else Modifier)
                        .onFocusChanged {
                            if (it.isFocused && selected != idx) {
                                selected = idx
                                scope.launch { rowState.animateScrollToItem(idx) }
                            }
                        },
                )
            }
        }
    }
}

private fun isFavorite(item: ShelfItem, favorites: Set<String>): Boolean = when (item) {
    is TileItem -> item.tile.key in favorites
    is ChannelItem -> "live:${item.channel.id}" in favorites
    else -> false
}

private fun progressOf(item: ShelfItem, progress: Map<String, WatchProgress>): Float? = when (item) {
    is TileItem -> if (item.tile.type == ContentType.MOVIE) {
        progress["${ProgressKind.MOVIE}:${item.tile.id}"]?.takeIf { !it.finished && it.position > 30_000 }?.fraction
    } else null
    is ResumeItem -> if (item.progress.isNext) null else item.progress.fraction
    else -> null
}

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
    is ResumeItem, is ChannelItem -> ShelfSize.wide
}

@Composable
private fun ShelfCard(
    app: AppController,
    item: ShelfItem,
    big: Boolean,
    detail: MovieInfo?,
    epg: List<EpgEntry>?,
    showFlag: Boolean,
    favorite: Boolean,
    progress: Float?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
) {
    val small = smallSize(item)
    val w by animateDpAsState(if (big) ShelfSize.big.width else small.width, tween(170), label = "w")
    val h by animateDpAsState(if (big) ShelfSize.big.height else small.height, tween(170), label = "h")
    val shape = RoundedCornerShape(if (big) 16.dp else 10.dp)
    var focused by remember { mutableStateOf(false) }
    Clickable(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.size(w, h).onFocusChanged { focused = it.isFocused },
        shape = shape,
        container = C.Surface2,
        focusedContainer = C.Surface2,
        focusedScale = if (big) 1f else 1.04f,
        borderWidth = 3.dp,
        borderColor = C.Gold,
    ) {
        when (item) {
            is TileItem -> if (big) BigTile(item, detail, showFlag) else SmallTile(item.tile, item.lang, showFlag)
            is ResumeItem -> ResumeCard(item.progress, big)
            is ChannelItem -> ChannelCard(app, item.channel, item.lang, showFlag, big, big && focused, epg)
            is SeeAllItem -> SeeAllCard(item, big)
        }
        if (favorite) {
            Box(
                Modifier.align(Alignment.TopStart).padding(8.dp).size(22.dp).clip(CircleShape).background(Color(0xB31A0B12)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Favorite, null, tint = C.Gold, modifier = Modifier.size(12.dp)) }
        }
        if (progress != null && progress > 0f) {
            ProgressLine(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp))
        }
    }
}

private fun fallback(seed: String): Brush {
    val base = C.avatar(seed.hashCode())
    return Brush.linearGradient(listOf(base.copy(alpha = 0.45f), C.Surface3, C.Surface1))
}

private fun isFresh(addedSecs: Long, days: Int): Boolean {
    if (addedSecs <= 0) return false
    val age = System.currentTimeMillis() - addedSecs * 1000
    return age in 0..days * 86_400_000L
}

@Composable
private fun BoxScope.SmallTile(tile: Tile, lang: Lang?, showFlag: Boolean) {
    Box(Modifier.fillMaxSize().background(fallback(tile.title)), contentAlignment = Alignment.Center) {
        Text(
            tile.title, style = T.cardTitle, color = C.Text1, maxLines = 4, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(10.dp),
        )
    }
    if (tile.image != null) {
        AsyncImage(model = tile.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
    if (showFlag && lang != null) {
        Box(Modifier.align(Alignment.TopEnd).padding(7.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xB31A0B12)).padding(4.dp)) {
            LangFlag(lang, width = 15.dp)
        }
    }
}

@Composable
private fun BoxScope.BigTile(item: TileItem, detail: MovieInfo?, showFlag: Boolean) {
    val tile = item.tile
    val wide = tile.backdrop ?: detail?.takeIf { it.id == tile.id }?.backdrop
    if (wide != null) {
        Box(Modifier.fillMaxSize().background(fallback(tile.title)))
        AsyncImage(model = wide, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xD91A0B12))))
        Text(
            tile.title, style = T.cardTitle.copy(fontSize = 30.sp, lineHeight = 32.sp), color = C.Text1,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 22.dp),
        )
    } else {
        PosterComposite(tile.image, tile.title)
    }
    val fresh = isFresh(tile.added, if (tile.type == ContentType.SERIES) 2 else 5)
    if (fresh) {
        Badge(
            if (tile.type == ContentType.SERIES) "Nouvel épisode" else "Nouveau",
            Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 14.dp),
        )
    }
    if (showFlag && item.lang != null) {
        LangTag(item.lang, Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 14.dp))
    }
}

/** Affiche verticale dans une carte horizontale : fond flou de l'affiche, affiche nette à droite, titre à gauche. */
@Composable
private fun BoxScope.PosterComposite(image: String?, title: String) {
    Box(Modifier.fillMaxSize().background(fallback(title)))
    if (image != null) {
        AsyncImage(
            model = image, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = 1.3f; scaleY = 1.3f; alpha = 0.7f }
                .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(26.dp) else Modifier),
        )
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(0f to Color(0xE61A0B12), 0.55f to Color(0x8C1A0B12), 1f to Color(0x331A0B12))
        )
    )
    if (image != null) {
        AsyncImage(
            model = image, contentDescription = null, contentScale = ContentScale.Crop,
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
        Text(text, style = T.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = C.Bg)
    }
}

/** Drapeau de langue sur une grande carte ; le turc affiche aussi « مترجم » (sous-titré). */
@Composable
private fun LangTag(lang: Lang, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(7.dp)).background(Color(0xB81A0B12)).padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LangFlag(lang, width = 18.dp)
        if (lang == Lang.TR) {
            Spacer(Modifier.width(6.dp))
            Text("مترجم", style = T.small.copy(fontSize = 12.sp), color = C.Gold)
        }
    }
}

@Composable
private fun BoxScope.ResumeCard(p: WatchProgress, big: Boolean) {
    Box(Modifier.fillMaxSize().background(fallback(p.title)))
    if (p.image != null) {
        AsyncImage(model = p.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xE01A0B12))))
    if (big) {
        p.subtitle?.let { Badge(it.substringBefore(" · "), Modifier.align(Alignment.TopStart).padding(14.dp)) }
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
                if (p.isNext || p.position <= 0) "Épisode suivant" else "Reprendre à ${minutesLabel(p.position)}",
                style = T.label, color = C.Bg,
            )
        }
    } else {
        Text(
            p.title, style = T.small, color = C.Text1, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = 16.dp),
        )
    }
}

private fun minutesLabel(ms: Long): String {
    val min = (ms / 60_000).coerceAtLeast(1)
    return if (min >= 60) "${min / 60} h ${(min % 60).toString().padStart(2, '0')}" else "$min min"
}

@Composable
private fun BoxScope.ChannelCard(
    app: AppController,
    channel: Channel,
    lang: Lang?,
    showFlag: Boolean,
    big: Boolean,
    preview: Boolean,
    epg: List<EpgEntry>?,
) {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(C.Surface3, C.Surface1))))
    if (!big) {
        ChannelLogo(channel, Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp))
        if (showFlag && lang != null) LangFlag(lang, Modifier.align(Alignment.TopEnd).padding(6.dp), width = 13.dp)
        return
    }
    val now = System.currentTimeMillis()
    val current = epg?.firstOrNull { it.start <= now && it.end > now }
    // Aperçu vidéo avec le son quand la tuile reste sélectionnée un instant.
    val video = if (preview) LiveVideo(app, channel) else false
    if (video) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xD91A0B12))))
    } else {
        ChannelLogo(channel, Modifier.align(Alignment.TopStart).padding(start = 22.dp, top = 22.dp).size(150.dp, 84.dp))
    }
    Row(
        Modifier.align(Alignment.TopEnd).padding(14.dp).clip(RoundedCornerShape(7.dp)).background(Color(0x991A0B12))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(C.Live))
        Spacer(Modifier.width(6.dp))
        Text("En direct", style = T.small, color = C.Text1)
        if (showFlag && lang != null) {
            Spacer(Modifier.width(8.dp))
            LangFlag(lang, width = 15.dp)
        }
    }
    Column(Modifier.align(Alignment.BottomStart).padding(start = 22.dp, end = 22.dp, bottom = 20.dp)) {
        Text(
            current?.title ?: channel.name, style = T.cardTitle.copy(fontSize = 24.sp, lineHeight = 27.sp),
            color = C.Text1, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (current != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressLine(current.progress(now), Modifier.width(150.dp))
                Spacer(Modifier.width(10.dp))
                Text("${Txt.hour(current.start)} – ${Txt.hour(current.end)}", style = T.small, color = C.Text2)
            }
        }
    }
}

/**
 * Vidéo de la chaîne dans la tuile, après une courte pause sur la tuile.
 * Renvoie true dès que la première image s'affiche.
 */
@Composable
private fun LiveVideo(app: AppController, channel: Channel): Boolean {
    val preview = app.preview
    val state by preview.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifeState by lifecycle.currentStateFlow.collectAsState()
    val active = lifeState.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(channel.id, active) {
        if (!active) {
            preview.release(channel.id)
            return@LaunchedEffect
        }
        delay(800)
        preview.start(channel)
    }
    DisposableEffect(channel.id) {
        onDispose { preview.release(channel.id) }
    }
    val player = if (state.channelId == channel.id) preview.player else null
    if (player != null) {
        key(player) {
            AndroidView(
                factory = { ctx -> TextureView(ctx) },
                update = { view -> player.setVideoTextureView(view) },
                onRelease = { view -> player.clearVideoTextureView(view) },
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (state.showing) 1f else 0f },
            )
        }
    }
    return player != null && state.showing
}

@Composable
private fun BoxScope.SeeAllCard(item: SeeAllItem, big: Boolean) {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(C.Surface3, C.Surface1))))
    val what = when (item.target.type) {
        ContentType.MOVIE -> Txt.count(item.total, "film", "films")
        ContentType.SERIES -> Txt.count(item.total, "série", "séries")
        ContentType.LIVE -> Txt.count(item.total, "chaîne", "chaînes")
    }
    Column(Modifier.align(Alignment.CenterStart).padding(horizontal = if (big) 26.dp else 12.dp)) {
        Text("Tout voir", style = if (big) T.display else T.cardTitle.copy(fontSize = 22.sp), color = C.Text1)
        Spacer(Modifier.height(4.dp))
        Text(what, style = T.meta, color = C.Text2)
        Spacer(Modifier.height(10.dp))
        Icon(Icons.Filled.ChevronRight, null, tint = C.Gold, modifier = Modifier.size(if (big) 30.dp else 22.dp))
    }
}

// ---------------- Panneau d'informations ----------------

private fun langLabel(lang: Lang): String = when (lang) {
    Lang.FR -> "Français"
    Lang.EN -> "Anglais"
    Lang.TR -> "Turc, sous-titres arabes"
    Lang.OTHER -> "Autre langue"
}

@Composable
private fun InfoPanel(
    item: ShelfItem,
    detail: MovieInfo?,
    epg: List<EpgEntry>?,
    showLang: Boolean,
    progress: Map<String, WatchProgress>,
    modifier: Modifier,
) {
    Column(modifier) {
        when (item) {
            is TileItem -> {
                val t = item.tile
                val info = detail?.takeIf { it.id == t.id }
                val plot = if (t.type == ContentType.SERIES) t.plot else info?.plot
                InfoTitle(t.title, if (plot.isNullOrBlank()) 2 else 1)
                Spacer(Modifier.height(6.dp))
                InfoMeta(
                    lang = item.lang.takeIf { showLang || it == Lang.TR },
                    parts = listOf(
                        t.year ?: info?.year,
                        Txt.durationLabel(info?.durationSecs ?: 0),
                        (if (t.type == ContentType.SERIES) t.genre else info?.genre)?.split(',', '/')?.take(2)?.joinToString(", ") { it.trim() },
                    ),
                    rating = Txt.rating(if (t.rating > 0) t.rating else info?.rating ?: 0.0),
                )
                if (!plot.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(plot, style = T.body, color = C.Text2, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (t.type == ContentType.MOVIE) {
                    progress["${ProgressKind.MOVIE}:${t.id}"]?.takeIf { !it.finished && it.position > 30_000 }?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(Txt.remaining(it.duration - it.position), style = T.meta, color = C.Gold)
                    }
                }
            }
            is ResumeItem -> {
                val p = item.progress
                InfoTitle(p.title, 2)
                Spacer(Modifier.height(6.dp))
                InfoMeta(null, listOf(p.subtitle), null)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (p.isNext) "Prêt à démarrer. OK pour lancer, maintenir OK pour retirer de la liste."
                    else "${Txt.remaining(p.duration - p.position)}. OK pour reprendre, maintenir OK pour retirer de la liste.",
                    style = T.body, color = C.Text2, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
            is ChannelItem -> {
                val ch = item.channel
                val now = System.currentTimeMillis()
                val list = epg.orEmpty().filter { it.end > now }
                val current = list.firstOrNull { it.start <= now }
                val next = list.firstOrNull { it.start > now }
                InfoTitle(ch.name, 1)
                Spacer(Modifier.height(6.dp))
                InfoMeta(
                    lang = item.lang.takeIf { showLang },
                    parts = listOf(current?.let { "${Txt.hour(it.start)} – ${Txt.hour(it.end)}" } ?: "En direct"),
                    rating = null,
                )
                Spacer(Modifier.height(8.dp))
                if (current != null) {
                    Text(current.title, style = T.bodyStrong, color = C.Text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    current.description?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = T.body, color = C.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                } else if (epg != null) {
                    Text("Pas de guide des programmes pour cette chaîne.", style = T.body, color = C.Text3)
                }
                if (next != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("Ensuite à ${Txt.hour(next.start)} : ${next.title}", style = T.meta, color = C.Gold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            is SeeAllItem -> {
                InfoTitle("Tout voir", 1)
                Spacer(Modifier.height(8.dp))
                val what = when (item.target.type) {
                    ContentType.MOVIE -> Txt.count(item.total, "film", "films")
                    ContentType.SERIES -> Txt.count(item.total, "série", "séries")
                    ContentType.LIVE -> Txt.count(item.total, "chaîne", "chaînes")
                }
                Text("Les $what de « ${item.target.title} », du plus récent au plus ancien.", style = T.body, color = C.Text2, maxLines = 3)
            }
        }
    }
}

@Composable
private fun InfoTitle(text: String, maxLines: Int) {
    Text(
        text, style = TextStyle(fontFamily = Sans, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 23.sp, lineHeight = 27.sp, letterSpacing = (-0.3).sp),
        color = C.Text1, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun InfoMeta(lang: Lang?, parts: List<String?>, rating: String?) {
    val items = parts.filterNotNull().filter { it.isNotBlank() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (lang != null) {
            LangFlag(lang, width = 18.dp)
            Spacer(Modifier.width(7.dp))
            Text(langLabel(lang), style = T.meta, color = C.Text1, maxLines = 1)
        }
        items.forEachIndexed { i, s ->
            if (i > 0 || lang != null) Spacer(Modifier.width(14.dp))
            Text(s, style = T.meta, color = C.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (rating != null) {
            Spacer(Modifier.width(14.dp))
            Text("★ $rating", style = T.meta, color = C.Gold, maxLines = 1)
        }
    }
}

/** Contour fin réutilisé par les puces de langue. */
fun Modifier.hairline(color: Color, shape: androidx.compose.ui.graphics.Shape, width: Dp = 1.5.dp): Modifier =
    this.border(width, color, shape)
