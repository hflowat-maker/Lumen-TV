package app.lumen.tv.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.lumen.tv.ui.theme.C
import app.lumen.tv.ui.theme.T
import kotlinx.coroutines.delay

/** Marges de sécurité TV (overscan). */
object Safe {
    val h = 48.dp
    val v = 27.dp
}

/** Élément cliquable à la télécommande, avec mise en valeur au focus. */
@Composable
fun Clickable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(10.dp),
    container: Color = Color.Transparent,
    focusedContainer: Color = container,
    contentColor: Color = C.Text1,
    focusedContentColor: Color = contentColor,
    focusedScale: Float = 1f,
    borderWidth: Dp = 0.dp,
    borderColor: Color = Color.White,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = contentColor,
            focusedContainerColor = focusedContainer,
            focusedContentColor = focusedContentColor,
            pressedContainerColor = focusedContainer,
            pressedContentColor = focusedContentColor,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale, pressedScale = focusedScale * 0.98f),
        border = if (borderWidth > 0.dp) {
            ClickableSurfaceDefaults.border(
                focusedBorder = Border(border = BorderStroke(borderWidth, borderColor), shape = shape),
                pressedBorder = Border(border = BorderStroke(borderWidth, borderColor), shape = shape),
            )
        } else {
            ClickableSurfaceDefaults.border()
        },
        content = content,
    )
}

// ---------------- Défilement « pivot » façon Netflix ----------------

/** Garde l'élément focalisé aligné au début du conteneur (rangées horizontales, liste de rangées). */
@Composable
fun rememberStartPivot(offset: Dp = 0.dp): BringIntoViewSpec {
    val px = with(LocalDensity.current) { offset.toPx() }
    return remember(px) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - px
        }
    }
}

/** Garde l'élément focalisé au centre (listes verticales de chaînes, catégories). */
@Composable
fun rememberCenterPivot(): BringIntoViewSpec = remember {
    object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
            val target = (containerSize - size) / 2f
            return offset - target
        }
    }
}

// ---------------- Restauration du focus ----------------

/** Pour LazyRow / LazyColumn : revient sur le dernier élément focalisé de la liste. */
fun Modifier.restoreFocus(): Modifier = this.focusRestorer()

/** Pour un conteneur classique (Row / Column). */
fun Modifier.restoreFocusGroup(fallback: FocusRequester? = null): Modifier =
    if (fallback == null) this.focusRestorer().focusGroup()
    else this.focusRestorer { fallback }.focusGroup()

// ---------------- Mémoire de focus (retour arrière) ----------------

@Stable
class FocusMemory(initial: String?) {
    var last: String? = initial
    var pending: Boolean = initial != null
}

private val FocusMemorySaver = Saver<FocusMemory, String>(
    save = { it.last ?: "" },
    restore = { FocusMemory(it.ifEmpty { null }) },
)

@Composable
fun rememberFocusMemory(): FocusMemory = rememberSaveable(saver = FocusMemorySaver) { FocusMemory(null) }

/**
 * Mémorise l'élément focalisé et le refocalise automatiquement quand l'écran revient.
 * [first] demande le focus quand aucun souvenir n'existe (premier affichage).
 */
@Composable
fun Modifier.remembered(memory: FocusMemory, key: String, first: Boolean = false): Modifier {
    val fr = remember { FocusRequester() }
    LaunchedEffect(key) {
        fun tryFocus(): Boolean = try {
            fr.requestFocus()
            true
        } catch (_: IllegalStateException) {
            false
        }
        when {
            memory.pending && memory.last == key -> {
                delay(32)
                if (tryFocus()) memory.pending = false
            }
            first && memory.last == null -> {
                delay(16)
                tryFocus()
            }
            first && memory.pending -> {
                // L'élément mémorisé n'existe plus : on retombe sur le premier.
                delay(450)
                if (memory.pending) {
                    memory.pending = false
                    tryFocus()
                }
            }
        }
    }
    return this
        .focusRequester(fr)
        .onFocusChanged { if (it.isFocused) memory.last = key }
}

suspend fun FocusRequester.safeRequest(delayMs: Long = 0) {
    if (delayMs > 0) delay(delayMs)
    try {
        requestFocus()
    } catch (_: IllegalStateException) {
    }
}

// ---------------- Petits éléments ----------------

@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 36.dp, color: Color = C.Gold) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle",
    )
    Canvas(modifier.size(size)) {
        drawArc(
            color = color, startAngle = angle, sweepAngle = 280f, useCenter = false,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
fun Skeleton(modifier: Modifier, shape: Shape = RoundedCornerShape(10.dp)) {
    val t = rememberInfiniteTransition(label = "sk")
    val a by t.animateFloat(
        initialValue = 0.45f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "alpha",
    )
    Box(modifier.graphicsLayer { alpha = a }.clip(shape).background(C.Surface2))
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, color: Color = C.Gold, track: Color = Color(0x55FFFFFF)) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}

@Composable
fun Dot() {
    Box(Modifier.padding(horizontal = 8.dp).size(3.dp).clip(CircleShape).background(C.Text3))
}

/** Ligne de métadonnées « 2021 · 1 h 52 · ★ 7,4 · Drame ». */
@Composable
fun MetaLine(items: List<String?>, modifier: Modifier = Modifier, rating: String? = null, badge: String? = null) {
    val parts = items.filterNotNull().filter { it.isNotBlank() }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (badge != null) {
            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(C.Live).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text(badge, style = T.overline, color = Color.White)
            }
            if (parts.isNotEmpty() || rating != null) Spacer(Modifier.width(10.dp))
        }
        if (rating != null) {
            Text("★", style = T.meta, color = C.Gold)
            Spacer(Modifier.width(4.dp))
            Text(rating, style = T.meta, color = C.Text1)
            if (parts.isNotEmpty()) Dot()
        }
        parts.forEachIndexed { i, s ->
            if (i > 0) Dot()
            Text(s, style = T.meta, color = C.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Bouton pilule : clair au focus, doré quand [primary]. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    Clickable(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(22.dp),
        container = if (primary) C.Gold else C.Glass,
        focusedContainer = Color.White,
        contentColor = if (primary) C.Bg else C.Text1,
        focusedContentColor = C.Bg,
        focusedScale = 1.06f,
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp).align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                if (text.isNotEmpty()) Spacer(Modifier.width(8.dp))
            }
            if (text.isNotEmpty()) Text(text, style = T.label, maxLines = 1)
        }
    }
}

/** Bouton rond icône seule. */
@Composable
fun RoundIconButton(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false) {
    Clickable(
        onClick = onClick,
        modifier = modifier.size(44.dp),
        shape = CircleShape,
        container = if (active) C.GoldSoft else C.Glass,
        focusedContainer = Color.White,
        contentColor = if (active) C.Gold else C.Text1,
        focusedContentColor = C.Bg,
        focusedScale = 1.08f,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp).align(Alignment.Center))
    }
}

/** Puce sélectionnable (saisons, onglets). */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, onFocus: (() -> Unit)? = null) {
    Clickable(
        onClick = onClick,
        modifier = modifier
            .height(36.dp)
            .onFocusChanged { if (it.isFocused) onFocus?.invoke() },
        shape = RoundedCornerShape(18.dp),
        container = if (selected) C.GoldSoft else Color.Transparent,
        focusedContainer = Color.White,
        contentColor = if (selected) C.Gold else C.Text2,
        focusedContentColor = C.Bg,
        focusedScale = 1.05f,
    ) {
        Text(text, style = T.label, modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp), maxLines = 1)
    }
}

/** Interrupteur visuel. */
@Composable
fun Toggle(on: Boolean) {
    Box(
        Modifier
            .size(width = 44.dp, height = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (on) C.Gold else Color(0x40FFFFFF))
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(Color.White))
    }
}
