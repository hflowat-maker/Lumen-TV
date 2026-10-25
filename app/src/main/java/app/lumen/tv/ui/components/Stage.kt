package app.lumen.tv.ui.components

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.lumen.tv.data.LangMode
import app.lumen.tv.ui.theme.C
import app.lumen.tv.ui.theme.T
import coil.compose.AsyncImage

/** Image affichée en plein écran derrière l'interface. */
@Immutable
data class Art(val url: String?, val isPoster: Boolean)

/**
 * Fond « affiche plein écran » : l'image du contenu sélectionné occupe tout l'écran.
 * Une affiche verticale est floutée en fond et posée nette à droite ; une image
 * horizontale est affichée telle quelle. Des dégradés gardent le texte lisible.
 */
@Composable
fun Stage(art: Art?, modifier: Modifier = Modifier, dim: Float = 1f) {
    Box(modifier.fillMaxSize().background(C.Bg)) {
        Crossfade(targetState = art, animationSpec = tween(550), label = "stage") { a ->
            Box(Modifier.fillMaxSize()) {
                val url = a?.url
                val poster = a?.isPoster == true
                if (url != null) {
                    if (poster) {
                        AsyncImage(
                            model = url, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = 0.55f; scaleX = 1.15f; scaleY = 1.15f }
                                .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(40.dp) else Modifier),
                        )
                        AsyncImage(
                            model = url, contentDescription = null, contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .fillMaxHeight(0.92f)
                                .padding(top = 24.dp, end = 64.dp),
                        )
                    } else {
                        AsyncImage(
                            model = url, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to C.Bg.copy(alpha = 0.96f * dim),
                    0.42f to C.Bg.copy(alpha = 0.72f * dim),
                    0.75f to C.Bg.copy(alpha = 0.18f * dim),
                    1f to Color.Transparent,
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to C.Bg.copy(alpha = 0.55f * dim),
                    0.22f to Color.Transparent,
                    0.55f to C.Bg.copy(alpha = 0.35f * dim),
                    1f to C.Bg.copy(alpha = 0.97f),
                )
            )
        )
    }
}

/** Bascule de langue : Français · English · Les deux · Autres langues. */
@Composable
fun LangSwitch(mode: LangMode, onChange: (LangMode) -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0x66000000))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LangMode.entries.forEach { m ->
            val selected = m == mode
            val label = if (compact && m == LangMode.OTHER) "Autres" else if (m == LangMode.OTHER) "Autres langues" else m.label
            Clickable(
                onClick = { if (!selected) onChange(m) },
                modifier = Modifier.height(36.dp),
                shape = RoundedCornerShape(18.dp),
                container = if (selected) C.Gold else Color.Transparent,
                focusedContainer = Color.White,
                contentColor = if (selected) C.Bg else C.Text1,
                focusedContentColor = C.Bg,
                focusedScale = 1.05f,
            ) {
                Row(Modifier.align(Alignment.Center).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (m == LangMode.FR || m == LangMode.EN) {
                        Flag(m)
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(label, style = T.label, maxLines = 1)
                }
            }
        }
    }
}

/** Petit drapeau dessiné (pas d'émoji : rendu identique sur toutes les TV). */
@Composable
private fun Flag(mode: LangMode) {
    val shape = RoundedCornerShape(2.dp)
    if (mode == LangMode.FR) {
        Row(Modifier.size(width = 18.dp, height = 12.dp).clip(shape)) {
            Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF1F4BA5)))
            Box(Modifier.weight(1f).fillMaxHeight().background(Color.White))
            Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFE1343F)))
        }
    } else {
        // Rayures et canton : évocation sobre des pays anglophones.
        Box(Modifier.size(width = 18.dp, height = 12.dp).clip(shape).background(Color.White)) {
            Box(Modifier.fillMaxWidth().height(4.dp).align(Alignment.Center).background(Color(0xFFC8102E)))
            Box(Modifier.width(4.dp).fillMaxHeight().align(Alignment.Center).background(Color(0xFFC8102E)))
            Box(Modifier.size(width = 18.dp, height = 12.dp).background(Color(0x33012169)))
        }
    }
}

/** En-tête commun des rubriques : titre à gauche, bascule de langue à droite. */
@Composable
fun SectionHeader(title: String, mode: LangMode, onLang: (LangMode) -> Unit, modifier: Modifier = Modifier, showLang: Boolean = true) {
    Row(
        modifier.fillMaxWidth().padding(start = Safe.h, end = Safe.h, top = Safe.v),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(C.Gold))
        Spacer(Modifier.width(10.dp))
        Text(title, style = T.title, color = C.Text1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (showLang) LangSwitch(mode, onLang, compact = true)
    }
}

/** Bloc d'informations sur l'élément focalisé, posé sur le fond plein écran. */
@Composable
fun FocusInfo(
    overline: String?,
    title: String?,
    meta: List<String?>,
    rating: String?,
    plot: String?,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    Crossfade(targetState = title, animationSpec = tween(200), label = "info", modifier = modifier) { t ->
        androidx.compose.foundation.layout.Column(Modifier.width(600.dp)) {
            if (t != null) {
                if (overline != null) {
                    Text(overline.uppercase(), style = T.overline, color = C.Gold, maxLines = 1)
                    Spacer(Modifier.height(6.dp))
                }
                Text(t, style = T.hero, color = C.Text1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                MetaLine(meta, rating = rating, badge = badge)
                if (!plot.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(plot, style = T.body, color = C.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
