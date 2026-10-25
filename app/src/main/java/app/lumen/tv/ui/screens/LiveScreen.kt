package app.lumen.tv.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import app.lumen.tv.data.Category
import app.lumen.tv.data.Group
import app.lumen.tv.ui.components.Chip
import app.lumen.tv.ui.components.SectionHeader
import app.lumen.tv.ui.components.Stage
import app.lumen.tv.ui.components.rememberStartPivot
import androidx.compose.foundation.lazy.LazyRow
import app.lumen.tv.safely
import app.lumen.tv.data.Channel
import app.lumen.tv.data.ContentType
import app.lumen.tv.data.EpgEntry
import app.lumen.tv.data.Text as Txt
import app.lumen.tv.ui.AppController
import app.lumen.tv.ui.components.ChannelLogo
import app.lumen.tv.ui.components.Clickable
import app.lumen.tv.ui.components.MetaLine
import app.lumen.tv.ui.components.ProgressLine
import app.lumen.tv.ui.components.Safe
import app.lumen.tv.ui.components.Spinner
import app.lumen.tv.ui.components.rememberCenterPivot
import app.lumen.tv.ui.components.rememberFocusMemory
import app.lumen.tv.ui.components.remembered
import app.lumen.tv.ui.components.restoreFocus
import app.lumen.tv.ui.theme.C
import app.lumen.tv.ui.theme.T
import kotlinx.coroutines.delay

private const val FAV = "__fav"
private const val RECENT = "__recent"
private const val ALL = "__all"

@Composable
fun LiveScreen(app: AppController) {
    val repo = app.repo
    val memory = rememberFocusMemory()
    val context = LocalContext.current
    val mode by repo.langMode.collectAsState()
    val version by repo.catalogVersion.collectAsState()
    val filterVersion by repo.filterVersion.collectAsState()
    val favorites by repo.favorites.collectAsState()
    val hasFavs = favorites.any { it.startsWith("live:") }

    var groups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var selected by rememberSaveable { mutableStateOf(if (hasFavs) FAV else ALL) }
    var focusedChip by remember { mutableStateOf<String?>(null) }
    var channels by remember { mutableStateOf<List<Channel>?>(null) }
    var focusedChannel by remember { mutableStateOf<Channel?>(null) }

    LaunchedEffect(mode, version, filterVersion) {
        groups = safely("thèmes TV") { repo.groups(ContentType.LIVE) } ?: emptyList()
    }
    // Survoler une puce suffit à changer de thème.
    LaunchedEffect(focusedChip) {
        val k = focusedChip ?: return@LaunchedEffect
        delay(350)
        selected = k
    }
    val favKey = if (selected == FAV) favorites else null
    LaunchedEffect(selected, mode, version, favKey, groups) {
        channels = safely("chaînes $selected") {
            when (selected) {
                FAV -> repo.favoriteChannels()
                RECENT -> repo.recentChannels()
                ALL -> repo.languageChannels()
                else -> groups.firstOrNull { it.key == selected }?.let { repo.groupChannels(it) } ?: repo.languageChannels()
            }
        } ?: emptyList()
    }

    val chips = buildList {
        if (hasFavs) add(FAV to "Mes chaînes")
        add(RECENT to "Récentes")
        add(ALL to "Toutes")
        groups.forEach { add(it.key to "${it.label}  ${it.count}") }
    }

    Box(Modifier.fillMaxSize()) {
        Stage(null)
        Column(Modifier.fillMaxSize()) {
            SectionHeader("TV en direct", mode, { repo.setLangMode(it) })
            CompositionLocalProvider(LocalBringIntoViewSpec provides rememberStartPivot(Safe.h)) {
                LazyRow(
                    contentPadding = PaddingValues(start = Safe.h, end = Safe.h, top = 14.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(chips, key = { it.first }) { (k, label) ->
                        Chip(
                            text = label,
                            selected = k == selected,
                            onClick = { selected = k },
                            onFocus = { focusedChip = k },
                            modifier = Modifier
                                .onFocusChanged { if (!it.isFocused && focusedChip == k) focusedChip = null }
                                .remembered(memory, "chip/$k"),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxSize().padding(top = 8.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().padding(start = Safe.h - 8.dp, end = 16.dp)) {
                    val list = channels
                    when {
                        list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
                        list.isEmpty() -> Text(
                            when (selected) {
                                FAV -> "Aucune chaîne favorite. Maintenez OK sur une chaîne pour l'ajouter."
                                RECENT -> "Aucune chaîne regardée récemment."
                                else -> "Aucune chaîne dans cette catégorie."
                            },
                            style = T.body, color = C.Text2, modifier = Modifier.padding(start = 8.dp),
                        )
                        else -> CompositionLocalProvider(LocalBringIntoViewSpec provides rememberCenterPivot()) {
                            val listState = rememberLazyListState()
                            var shownFor by rememberSaveable { mutableStateOf(selected) }
                            LaunchedEffect(selected) {
                                if (selected != shownFor) {
                                    shownFor = selected
                                    listState.scrollToItem(0)
                                }
                            }
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize().restoreFocus(),
                                contentPadding = PaddingValues(top = 4.dp, bottom = 60.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                items(list, key = { it.id }) { ch ->
                                    ChannelItem(
                                        app = app,
                                        channel = ch,
                                        favorite = "live:${ch.id}" in favorites,
                                        modifier = Modifier.remembered(memory, "ch:${ch.id}", first = ch == list.first()),
                                        onFocus = { focusedChannel = ch },
                                        onClick = { app.playChannel(list, ch) },
                                        onLongClick = { app.toggleFavorite(context, ContentType.LIVE, ch.id) },
                                    )
                                }
                            }
                        }
                    }
                }
                ChannelPreview(app, focusedChannel, Modifier.width(330.dp).fillMaxHeight().padding(end = Safe.h, top = 8.dp))
            }
        }
    }
}

@Composable
private fun CategoryItem(
    name: String,
    count: Int?,
    selected: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    Clickable(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .onFocusChanged { if (it.isFocused) onFocus() },
        shape = RoundedCornerShape(10.dp),
        container = if (selected) C.GoldSoft else Color.Transparent,
        focusedContainer = Color.White,
        contentColor = if (selected) C.Gold else C.Text2,
        focusedContentColor = C.Bg,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = T.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (count != null) Text("$count", style = T.small, color = LocalContentColor.current.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun ChannelItem(
    app: AppController,
    channel: Channel,
    favorite: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val epg by produceState<List<EpgEntry>?>(app.repo.cachedEpg(channel.id), channel.id) {
        if (value == null) {
            delay(180)
            value = app.repo.epg(channel.id)
        }
    }
    val now = System.currentTimeMillis()
    val current = epg?.firstOrNull { it.start <= now && it.end > now }

    Clickable(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .fillMaxWidth()
            .height(62.dp)
            .onFocusChanged { if (it.isFocused) onFocus() },
        shape = RoundedCornerShape(12.dp),
        container = C.Surface1,
        focusedContainer = Color.White,
        contentColor = C.Text1,
        focusedContentColor = C.Bg,
        focusedScale = 1.02f,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (channel.num > 0) "${channel.num}" else "",
                style = T.meta, color = LocalContentColor.current.copy(alpha = 0.5f),
                modifier = Modifier.width(40.dp),
            )
            Box(Modifier.size(width = 64.dp, height = 40.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x14FFFFFF)).padding(4.dp)) {
                ChannelLogo(channel, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(channel.name, style = T.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (current != null) {
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            current.title, style = T.small, color = LocalContentColor.current.copy(alpha = 0.7f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(10.dp))
                        ProgressLine(current.progress(now), Modifier.width(56.dp))
                    }
                }
            }
            if (favorite) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.Favorite, null, tint = C.Gold, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun ChannelPreview(app: AppController, channel: Channel?, modifier: Modifier) {
    Column(modifier) {
        if (channel != null) PreviewContent(app, channel)
    }
}

@Composable
private fun PreviewContent(app: AppController, channel: Channel) {
    Column {
        val epg by produceState<List<EpgEntry>?>(app.repo.cachedEpg(channel.id), channel.id) {
            delay(200)
            value = app.repo.epg(channel.id)
        }
        val now = System.currentTimeMillis()
        val list = epg.orEmpty().filter { it.end > now }
        val current = list.firstOrNull { it.start <= now }
        Box(
            Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp)).background(C.Surface1).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            ChannelLogo(channel, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(18.dp))
        Text(channel.name, style = T.title, color = C.Text1, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        if (current != null) {
            MetaLine(listOf("${Txt.hour(current.start)} – ${Txt.hour(current.end)}"), badge = "DIRECT")
            Spacer(Modifier.height(10.dp))
            Text(current.title, style = T.bodyStrong, color = C.Text1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            ProgressLine(current.progress(now), Modifier.fillMaxWidth())
            current.description?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = T.body, color = C.Text2, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        } else if (epg != null) {
            MetaLine(emptyList(), badge = "DIRECT")
            Spacer(Modifier.height(10.dp))
            Text("Pas de guide des programmes pour cette chaîne.", style = T.body, color = C.Text3)
        }
        val next = list.filter { it.start > now }.take(3)
        if (next.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("À SUIVRE", style = T.overline, color = C.Text3)
            Spacer(Modifier.height(8.dp))
            next.forEach { e ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text(Txt.hour(e.start), style = T.meta, color = C.Gold, modifier = Modifier.width(52.dp))
                    Text(e.title, style = T.meta, color = C.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
