package app.lumen.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.lumen.tv.data.Channel
import app.lumen.tv.data.Text as Txt
import app.lumen.tv.ui.theme.C
import app.lumen.tv.ui.theme.Sans
import androidx.compose.ui.text.font.FontWeight
import app.lumen.tv.ui.theme.T
import coil.compose.AsyncImage

object CardSize {
    val posterW = 124.dp
    val wideW = 236.dp
    val channelW = 168.dp
}

private val cardShape = RoundedCornerShape(10.dp)

private fun fallbackBrush(seed: String): Brush {
    val base = C.avatar(seed.hashCode())
    return Brush.linearGradient(listOf(base.copy(alpha = 0.35f), C.Surface2, C.Surface1))
}

/** Affiche verticale (film / série). */
@Composable
fun PosterCard(
    title: String,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = CardSize.posterW,
    progress: Float? = null,
    favorite: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
) {
    Clickable(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .width(width)
            .aspectRatio(2f / 3f)
            .onFocusChanged { if (it.isFocused) onFocus?.invoke() },
        shape = cardShape,
        container = C.Surface2,
        focusedContainer = C.Surface2,
        focusedScale = 1.08f,
        borderWidth = 2.5.dp,
    ) {
        Box(Modifier.fillMaxSize().background(fallbackBrush(title)), contentAlignment = Alignment.Center) {
            Text(
                title, style = T.label, color = C.Text1, textAlign = TextAlign.Center,
                maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp),
            )
        }
        if (image != null) {
            AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (favorite) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(Color(0xB3000000)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Favorite, null, tint = C.Gold, modifier = Modifier.size(13.dp))
            }
        }
        if (progress != null && progress > 0f) {
            ProgressLine(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp))
        }
    }
}

/** Carte horizontale 16:9 (reprise de lecture, épisodes). */
@Composable
fun WideCard(
    title: String,
    subtitle: String?,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = CardSize.wideW,
    progress: Float? = null,
    badge: String? = null,
    onLongClick: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
) {
    Clickable(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .width(width)
            .aspectRatio(16f / 9f)
            .onFocusChanged { if (it.isFocused) onFocus?.invoke() },
        shape = cardShape,
        container = C.Surface2,
        focusedContainer = C.Surface2,
        focusedScale = 1.07f,
        borderWidth = 2.5.dp,
    ) {
        Box(Modifier.fillMaxSize().background(fallbackBrush(title)))
        if (image != null) {
            AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xE6000000))
            )
        )
        if (badge != null) {
            Box(
                Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(4.dp))
                    .background(C.Gold).padding(horizontal = 6.dp, vertical = 2.dp),
            ) { Text(badge, style = T.overline, color = C.Bg) }
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(title, style = T.label, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = T.small, color = Color(0xCCFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (progress != null && progress > 0f) {
                Spacer(Modifier.height(7.dp))
                ProgressLine(progress, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Tuile de chaîne avec logo. */
@Composable
fun ChannelTile(
    channel: Channel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = CardSize.channelW,
    nowTitle: String? = null,
    favorite: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
) {
    Clickable(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .width(width)
            .aspectRatio(16f / 10f)
            .onFocusChanged { if (it.isFocused) onFocus?.invoke() },
        shape = cardShape,
        container = C.Surface2,
        focusedContainer = C.Surface3,
        focusedScale = 1.07f,
        borderWidth = 2.5.dp,
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                ChannelLogo(channel, Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(6.dp))
            Text(channel.name, style = T.small, color = C.Text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (nowTitle != null) Text(nowTitle, style = T.small, color = C.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (favorite) {
            Icon(
                Icons.Filled.Favorite, null, tint = C.Gold,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(14.dp),
            )
        }
    }
}

/** Logo de chaîne avec repli sur les initiales. */
@Composable
fun ChannelLogo(channel: Channel, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            channel.name.split(' ', '-', '|').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase(),
            style = T.title, color = C.Text3,
        )
        if (channel.icon != null) {
            AsyncImage(model = channel.icon, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Avatar de profil. */
@Composable
fun Avatar(name: String, color: Int, modifier: Modifier = Modifier, size: Dp = 112.dp, kid: Boolean = false) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.16f))
            .background(Brush.linearGradient(listOf(C.avatar(color), C.avatar(color).copy(alpha = 0.7f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            Txt.initials(name),
            style = T.hero.copy(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp, lineHeight = (size.value * 0.46f).sp),
            color = C.Bg,
        )
        if (kid) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).clip(RoundedCornerShape(6.dp))
                    .background(C.Bg.copy(alpha = 0.8f)).padding(horizontal = 6.dp, vertical = 2.dp),
            ) { Text("ENFANTS", style = T.overline, color = C.Text1) }
        }
    }
}

/** Rangée titrée de cartes, avec squelette pendant le chargement. */
@Composable
fun RowHeader(title: String, modifier: Modifier = Modifier, count: Int? = null) {
    Row(modifier.padding(start = Safe.h, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = T.row, color = C.Text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text("$count", style = T.meta, color = C.Text3)
        }
    }
}

@Composable
fun SkeletonRow(itemWidth: Dp, ratio: Float, count: Int = 7) {
    Row(Modifier.padding(start = Safe.h), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(count) { Skeleton(Modifier.width(itemWidth).aspectRatio(ratio)) }
    }
}

/** Petite vignette « Voir tout » en fin de rangée. */
@Composable
fun SeeAllCard(onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = CardSize.posterW, ratio: Float = 2f / 3f) {
    Clickable(
        onClick = onClick,
        modifier = modifier.width(width).aspectRatio(ratio),
        shape = cardShape,
        container = C.Surface1,
        focusedContainer = C.Focus,
        contentColor = C.Text2,
        focusedContentColor = C.Bg,
        focusedScale = 1.08f,
    ) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.ChevronRight, null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(6.dp))
            Text("Voir tout", style = T.label)
        }
    }
}
