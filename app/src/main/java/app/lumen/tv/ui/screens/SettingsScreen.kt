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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.lumen.tv.BuildConfig
import app.lumen.tv.data.Category
import app.lumen.tv.data.ContentType
import app.lumen.tv.data.Lang
import app.lumen.tv.data.Prefs
import app.lumen.tv.data.Text as Txt
import app.lumen.tv.ui.AppController
import app.lumen.tv.ui.Screen
import app.lumen.tv.ui.components.Chip
import app.lumen.tv.ui.components.ConfirmDialog
import app.lumen.tv.ui.components.Modal
import app.lumen.tv.ui.components.PillButton
import app.lumen.tv.ui.components.PinDialog
import app.lumen.tv.ui.components.Safe
import app.lumen.tv.ui.components.SettingRow
import app.lumen.tv.ui.components.Spinner
import app.lumen.tv.ui.components.TvTextField
import app.lumen.tv.ui.components.rememberCenterPivot
import app.lumen.tv.ui.components.rememberFocusMemory
import app.lumen.tv.ui.components.remembered
import app.lumen.tv.ui.components.safeRequest
import app.lumen.tv.ui.theme.C
import app.lumen.tv.ui.theme.T
import kotlinx.coroutines.launch

private sealed interface SettingItem {
    data class Header(val title: String) : SettingItem
    data class Row(
        val key: String,
        val title: String,
        val value: String? = null,
        val toggle: Boolean? = null,
        val danger: Boolean = false,
        val onClick: () -> Unit,
    ) : SettingItem
}

@Composable
fun SettingsScreen(app: AppController) {
    val prefs = app.prefs
    val repo = app.repo
    val scope = rememberCoroutineScope()
    val memory = rememberFocusMemory()
    val sync by repo.sync.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    var counts by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    var setPin by remember { mutableStateOf(false) }
    var confirmRemovePin by remember { mutableStateOf(false) }
    var editUa by remember { mutableStateOf(false) }
    var langPanel by remember { mutableStateOf(false) }
    val langs by repo.langs.collectAsState()

    LaunchedEffect(Unit) {
        repo.refreshAccountInfo()
        tick++
    }
    LaunchedEffect(sync.running) {
        counts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repo.counts() }
        tick++
    }

    fun changed() {
        tick++
    }

    val entries = remember(tick, sync.running, counts, langs) {
        val acc = prefs.account
        val exp = prefs.accountExp
        val status = prefs.accountStatus.ifBlank { "Actif" }
        buildList<SettingItem> {
            add(SettingItem.Header("Compte"))
            add(SettingItem.Row("server", "Serveur", acc?.server?.substringAfter("://") ?: "—") {})
            add(SettingItem.Row("user", "Identifiant", acc?.username ?: "—") {})
            add(
                SettingItem.Row(
                    "sub", "Abonnement",
                    if (exp > 0) "$status · jusqu'au ${Txt.date(exp)}" else "$status · sans date de fin",
                ) {}
            )
            if (prefs.accountMaxCons > 0) {
                add(SettingItem.Row("cons", "Écrans utilisés", "${prefs.accountActiveCons} sur ${prefs.accountMaxCons}") {})
            }
            add(SettingItem.Row("logout", "Changer de compte", danger = true) { confirmLogout = true })

            add(SettingItem.Header("Catalogue"))
            add(
                SettingItem.Row(
                    "sync", "Actualiser le catalogue",
                    when {
                        sync.running -> "Actualisation…"
                        prefs.lastSync > 0 -> "Mis à jour le ${Txt.dateTime(prefs.lastSync)}"
                        else -> null
                    },
                ) { if (!sync.running) repo.syncInBackground() }
            )
            counts?.let { (l, m, s) ->
                add(SettingItem.Row("counts", "Contenu", "$l chaînes · $m films · $s séries") {})
            }

            add(SettingItem.Header("Langues"))
            add(
                SettingItem.Row(
                    "langs", "Langues affichées",
                    Lang.entries.filter { it in langs }.joinToString(", ") { it.chip },
                ) { langPanel = true }
            )

            add(SettingItem.Header("Lecture"))
            add(
                SettingItem.Row(
                    "format", "Format des chaînes",
                    if (prefs.liveFormat == "ts") "MPEG-TS (recommandé)" else "HLS (m3u8)",
                ) {
                    prefs.liveFormat = if (prefs.liveFormat == "ts") "m3u8" else "ts"
                    changed()
                }
            )
            add(SettingItem.Row("introsound", "Son à l'ouverture de l'appli", toggle = prefs.introSound) {
                prefs.introSound = !prefs.introSound
                changed()
            })
            add(SettingItem.Row("autonext", "Épisode suivant automatique", toggle = prefs.autoNextEpisode) {
                prefs.autoNextEpisode = !prefs.autoNextEpisode
                changed()
            })
            add(
                SettingItem.Row(
                    "resize", "Format d'image par défaut",
                    listOf("Adapté à l'écran", "Remplir (zoom)", "Étiré")[prefs.resizeMode.coerceIn(0, 2)],
                ) {
                    prefs.resizeMode = (prefs.resizeMode + 1) % 3
                    changed()
                }
            )
            add(
                SettingItem.Row(
                    "ua", "User-Agent",
                    if (prefs.userAgent == Prefs.DEFAULT_UA) "Par défaut" else prefs.userAgent.take(28),
                ) { editUa = true }
            )

            add(SettingItem.Header("Contrôle parental"))
            add(SettingItem.Row("pin", "Code parental", if (prefs.parentalPin != null) "Activé" else "Aucun") {
                if (prefs.parentalPin != null) confirmRemovePin = true else setPin = true
            })
            add(SettingItem.Row("adult", "Masquer le contenu adulte", toggle = prefs.hideAdult) {
                prefs.hideAdult = !prefs.hideAdult
                repo.bumpFilters()
                changed()
            })
            add(SettingItem.Row("kids", "Catégories des profils enfants") { app.push(Screen.KidCategories) })
            add(SettingItem.Row("profiles", "Gérer les profils") { app.push(Screen.Profiles(manage = true)) })

            add(SettingItem.Header("À propos"))
            add(SettingItem.Row("version", "Version", "Lumen TV ${BuildConfig.VERSION_NAME}") {})
        }
    }

    Row(Modifier.fillMaxSize().padding(horizontal = Safe.h, vertical = Safe.v)) {
        Column(Modifier.width(300.dp).padding(top = 20.dp, end = 40.dp)) {
            Text("Réglages", style = T.shelf.copy(fontSize = 46.sp, lineHeight = 48.sp), color = C.Text1)
            Spacer(Modifier.height(12.dp))
            Text(
                "Appui long sur OK sur une affiche ou une chaîne pour l'ajouter à Ma liste. " +
                    "Dans le lecteur, les touches haut/bas changent de chaîne.",
                style = T.body, color = C.Text2,
            )
            if (sync.running) {
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner(size = 20.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Actualisation du catalogue…", style = T.meta, color = C.Text2)
                }
            }
            sync.error?.takeIf { !sync.running }?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = T.meta, color = C.Live)
            }
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberCenterPivot()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(top = 10.dp, bottom = 60.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(entries, key = { if (it is SettingItem.Row) it.key else "h:" + (it as SettingItem.Header).title }) { item ->
                    when (item) {
                        is SettingItem.Header -> Text(
                            item.title, style = T.overline.copy(fontSize = 12.sp), color = C.Gold,
                            modifier = Modifier.padding(start = 6.dp, top = 18.dp, bottom = 4.dp),
                        )
                        is SettingItem.Row -> SettingRow(
                            title = item.title, value = item.value, toggle = item.toggle, danger = item.danger,
                            onClick = item.onClick,
                            modifier = Modifier.remembered(memory, item.key, first = item.key == "server"),
                        )
                    }
                }
            }
        }
    }

    if (langPanel) {
        LanguagePanel(app, onClose = { langPanel = false })
    }
    if (confirmLogout) {
        ConfirmDialog(
            title = "Changer de compte ?",
            message = "Le catalogue sera effacé de cette TV. Vos profils, favoris et reprises de lecture sont conservés.",
            confirm = "Déconnecter",
            onConfirm = {
                confirmLogout = false
                scope.launch {
                    repo.logout()
                    app.reset(Screen.Login)
                }
            },
            onDismiss = { confirmLogout = false },
        )
    }
    if (setPin) {
        PinDialog(
            title = "Code parental",
            subtitle = "Choisissez 4 chiffres. Il sera demandé pour quitter un profil enfant.",
            onSubmit = { code ->
                prefs.parentalPin = code
                setPin = false
                changed()
                true
            },
            onDismiss = { setPin = false },
        )
    }
    if (confirmRemovePin) {
        PinDialog(
            title = "Supprimer le code parental",
            subtitle = "Saisissez le code actuel",
            onSubmit = { code ->
                val ok = code == prefs.parentalPin
                if (ok) {
                    prefs.parentalPin = null
                    confirmRemovePin = false
                    changed()
                }
                ok
            },
            onDismiss = { confirmRemovePin = false },
        )
    }
    if (editUa) {
        UserAgentDialog(
            initial = prefs.userAgent,
            onSave = {
                prefs.userAgent = it.trim().ifBlank { Prefs.DEFAULT_UA }
                editUa = false
                changed()
            },
            onReset = {
                prefs.userAgent = Prefs.DEFAULT_UA
                editUa = false
                changed()
            },
            onDismiss = { editUa = false },
        )
    }
}

@Composable
private fun UserAgentDialog(initial: String, onSave: (String) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    val fr = remember { FocusRequester() }
    Modal(onDismiss) {
        Column(Modifier.width(520.dp).clip(RoundedCornerShape(20.dp)).background(C.Surface1).padding(28.dp)) {
            Text("User-Agent", style = T.title, color = C.Text1)
            Spacer(Modifier.height(8.dp))
            Text(
                "À modifier uniquement si votre fournisseur l'exige (certains n'acceptent que des lecteurs précis).",
                style = T.body, color = C.Text2,
            )
            Spacer(Modifier.height(18.dp))
            TvTextField(value = value, onValueChange = { value = it }, label = "Valeur", focusRequester = fr)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Enregistrer", primary = true, onClick = { onSave(value) })
                PillButton("Par défaut", onClick = onReset)
                PillButton("Annuler", onClick = onDismiss)
            }
        }
    }
    LaunchedEffect(Unit) { fr.safeRequest(80) }
}

@Composable
fun KidCategoriesScreen(app: AppController) {
    val memory = rememberFocusMemory()
    val scope = rememberCoroutineScope()
    var type by rememberSaveable { mutableStateOf(ContentType.MOVIE) }
    var list by remember { mutableStateOf<List<Pair<Category, Boolean>>?>(null) }

    LaunchedEffect(type) {
        list = null
        list = app.repo.kidCategories(type)
    }

    Row(Modifier.fillMaxSize().padding(horizontal = Safe.h, vertical = Safe.v)) {
        Column(Modifier.width(320.dp).padding(top = 20.dp, end = 40.dp)) {
            Text("Profils enfants", style = T.hero, color = C.Text1)
            Spacer(Modifier.height(12.dp))
            Text(
                "Les profils enfants ne voient que les catégories cochées. Lumen en a présélectionné " +
                    "certaines d'après leur nom ; ajustez la liste selon vos choix.",
                style = T.body, color = C.Text2,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ContentType.LIVE to "TV", ContentType.MOVIE to "Films", ContentType.SERIES to "Séries").forEach { (t, label) ->
                    Chip(label, selected = t == type, onClick = { type = t }, modifier = Modifier.remembered(memory, "tab/${t.key}"))
                }
            }
        }
        val l = list
        if (l == null) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { Spinner() }
        } else {
            CompositionLocalProvider(LocalBringIntoViewSpec provides rememberCenterPivot()) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(top = 20.dp, bottom = 60.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(l, key = { it.first.id }) { (cat, allowed) ->
                        SettingRow(
                            title = cat.name,
                            value = "${cat.count}",
                            toggle = allowed,
                            onClick = {
                                val now = !allowed
                                list = l.map { if (it.first.id == cat.id) it.first to now else it }
                                scope.launch {
                                    app.repo.setKidCategory(type, cat.id, now)
                                    app.repo.bumpFilters()
                                }
                            },
                            modifier = Modifier.remembered(memory, "${type.key}/${cat.id}", first = cat == l.first().first),
                        )
                    }
                }
            }
        }
    }
}
