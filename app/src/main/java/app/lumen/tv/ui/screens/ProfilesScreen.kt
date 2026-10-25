package app.lumen.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.lumen.tv.data.Profile
import app.lumen.tv.ui.AppController
import app.lumen.tv.ui.Screen
import app.lumen.tv.ui.components.Avatar
import app.lumen.tv.ui.components.Clickable
import app.lumen.tv.ui.components.ConfirmDialog
import app.lumen.tv.ui.components.PillButton
import app.lumen.tv.ui.components.PinDialog
import app.lumen.tv.ui.components.Safe
import app.lumen.tv.ui.components.SettingRow
import app.lumen.tv.ui.components.TvTextField
import app.lumen.tv.ui.components.safeRequest
import app.lumen.tv.ui.theme.C
import app.lumen.tv.ui.theme.T
import kotlinx.coroutines.launch

@Composable
fun ProfilesScreen(app: AppController, manage: Boolean) {
    var profiles by remember { mutableStateOf<List<Profile>?>(null) }
    var pinFor by remember { mutableStateOf<Profile?>(null) }
    var parentalFor by remember { mutableStateOf<Profile?>(null) }
    val scope = rememberCoroutineScope()
    val activeId = app.prefs.activeProfileId
    val activeIsKid = app.repo.profile.value?.isKid == true
    val focusFirst = remember { FocusRequester() }

    LaunchedEffect(Unit) { profiles = app.repo.profiles() }

    fun enter(p: Profile) {
        scope.launch {
            app.repo.selectProfile(p)
            app.goHome()
        }
    }

    fun choose(p: Profile) {
        when {
            manage -> app.push(Screen.ProfileEdit(p.id))
            p.pin != null -> pinFor = p
            activeIsKid && !p.isKid && app.prefs.parentalPin != null -> parentalFor = p
            else -> enter(p)
        }
    }

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(C.Surface1, C.Bg))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (manage) "Gérer les profils" else "Qui regarde ?", style = T.hero, color = C.Text1)
            Spacer(Modifier.height(40.dp))
            val list = profiles
            if (list != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    list.forEach { p ->
                        val isFirst = if (activeId > 0) p.id == activeId else p == list.first()
                        ProfileTile(
                            profile = p,
                            manage = manage,
                            modifier = if (isFirst) Modifier.focusRequester(focusFirst) else Modifier,
                            onClick = { choose(p) },
                        )
                    }
                    if (list.size < 8) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Clickable(
                                onClick = { app.push(Screen.ProfileEdit(0)) },
                                modifier = Modifier.size(112.dp),
                                shape = RoundedCornerShape(18.dp),
                                container = C.Surface2,
                                focusedContainer = Color.White,
                                contentColor = C.Text2,
                                focusedContentColor = C.Bg,
                                focusedScale = 1.1f,
                            ) {
                                Icon(Icons.Filled.Add, null, modifier = Modifier.align(Alignment.Center).size(44.dp))
                            }
                            Spacer(Modifier.height(14.dp))
                            Text("Ajouter", style = T.label, color = C.Text2)
                        }
                    }
                }
                LaunchedEffect(list) { focusFirst.safeRequest(60) }
            }
            Spacer(Modifier.height(48.dp))
            if (manage) {
                PillButton("Terminé", icon = Icons.Filled.Check, onClick = { app.back() })
            } else {
                PillButton("Gérer les profils", icon = Icons.Filled.Edit, onClick = { app.push(Screen.Profiles(manage = true)) })
            }
        }
    }

    pinFor?.let { p ->
        PinDialog(
            title = p.name,
            subtitle = "Saisissez le code de ce profil",
            onSubmit = { code -> (code == p.pin).also { if (it) { pinFor = null; enter(p) } } },
            onDismiss = { pinFor = null },
        )
    }
    parentalFor?.let { p ->
        PinDialog(
            title = "Code parental",
            subtitle = "Requis pour quitter le profil enfant",
            onSubmit = { code -> (code == app.prefs.parentalPin).also { if (it) { parentalFor = null; enter(p) } } },
            onDismiss = { parentalFor = null },
        )
    }
}

@Composable
private fun ProfileTile(profile: Profile, manage: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Clickable(
            onClick = onClick,
            modifier = modifier.size(112.dp),
            shape = RoundedCornerShape(18.dp),
            focusedScale = 1.1f,
            borderWidth = 3.dp,
        ) {
            Avatar(profile.name, profile.color, size = 112.dp, kid = profile.isKid)
            if (manage) {
                Box(Modifier.fillMaxSize().background(Color(0x80000000)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Edit, null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
            } else if (profile.pin != null) {
                Icon(
                    Icons.Filled.Lock, null, tint = C.Bg,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(16.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(profile.name, style = T.label, color = C.Text1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(120.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun ProfileEditScreen(app: AppController, id: Long) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(0) }
    var kid by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(id == 0L) }
    var count by remember { mutableStateOf(1) }
    var askPin by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val nameFr = remember { FocusRequester() }

    LaunchedEffect(id) {
        val all = app.repo.profiles()
        count = all.size
        if (id == 0L) {
            color = all.size % C.avatars.size
        } else {
            all.firstOrNull { it.id == id }?.let {
                name = it.name; color = it.color; kid = it.isKid; pin = it.pin
            }
            loaded = true
        }
        nameFr.safeRequest(80)
    }

    fun save() {
        val n = name.trim().ifEmpty { "Profil" }
        scope.launch {
            app.repo.saveProfile(Profile(id, n.take(20), color, kid, pin))
            app.back()
        }
    }

    Row(
        Modifier.fillMaxSize().background(C.Bg).padding(horizontal = Safe.h * 2, vertical = Safe.v * 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(260.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(name.ifBlank { "?" }, color, size = 168.dp, kid = kid)
        }
        Spacer(Modifier.width(56.dp))
        if (loaded) {
            Column(Modifier.width(460.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(if (id == 0L) "Nouveau profil" else "Modifier le profil", style = T.display, color = C.Text1)
                TvTextField(value = name, onValueChange = { name = it.take(20) }, label = "Nom", placeholder = "Prénom", focusRequester = nameFr)
                Text("Couleur", style = T.meta, color = C.Text2)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    C.avatars.forEachIndexed { i, c ->
                        Clickable(
                            onClick = { color = i },
                            modifier = Modifier.size(36.dp),
                            shape = CircleShape,
                            focusedScale = 1.15f,
                            borderWidth = 3.dp,
                        ) {
                            Box(
                                Modifier.fillMaxSize().clip(CircleShape).background(c)
                                    .then(if (i == color) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                            )
                        }
                    }
                }
                SettingRow("Profil enfant", onClick = { kid = !kid }, toggle = kid)
                SettingRow(
                    "Code PIN du profil",
                    value = if (pin != null) "Activé" else "Aucun",
                    onClick = { if (pin != null) pin = null else askPin = true },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                    PillButton("Enregistrer", icon = Icons.Filled.Check, primary = true, onClick = { save() })
                    PillButton("Annuler", onClick = { app.back() })
                    if (id != 0L && count > 1) PillButton("Supprimer", onClick = { confirmDelete = true })
                }
            }
        }
    }

    if (askPin) {
        PinDialog(
            title = "Nouveau code",
            subtitle = "4 chiffres pour protéger ce profil",
            onSubmit = { code -> pin = code; askPin = false; true },
            onDismiss = { askPin = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Supprimer ce profil ?",
            message = "Ses favoris et son historique de lecture seront effacés.",
            confirm = "Supprimer",
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    app.repo.deleteProfile(id)
                    if (app.prefs.activeProfileId == id) app.prefs.activeProfileId = -1
                    app.back()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
