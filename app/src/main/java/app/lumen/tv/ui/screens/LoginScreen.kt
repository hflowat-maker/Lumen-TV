package app.lumen.tv.ui.screens

import android.net.Uri
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.lumen.tv.data.Account
import app.lumen.tv.ui.AppController
import app.lumen.tv.ui.Screen
import app.lumen.tv.ui.components.PillButton
import app.lumen.tv.ui.components.LumenLogo
import app.lumen.tv.ui.components.Safe
import app.lumen.tv.ui.components.Spinner
import app.lumen.tv.ui.components.TvTextField
import app.lumen.tv.ui.components.safeRequest
import app.lumen.tv.ui.theme.C
import app.lumen.tv.ui.theme.T
import kotlinx.coroutines.launch

/** Accepte « host:port », « http://host:port », ou un lien M3U complet contenant les identifiants. */
internal fun parseAccount(serverIn: String, userIn: String, passIn: String): Account? {
    var raw = serverIn.trim()
    var user = userIn.trim()
    var pass = passIn.trim()
    if (raw.isEmpty()) return null
    if (!raw.startsWith("http://", true) && !raw.startsWith("https://", true)) raw = "http://$raw"
    val uri = Uri.parse(raw)
    uri.getQueryParameter("username")?.let { user = it }
    uri.getQueryParameter("password")?.let { pass = it }
    val host = uri.host ?: return null
    val port = if (uri.port > 0) ":${uri.port}" else ""
    val path = uri.path.orEmpty().trimEnd('/')
    val keepPath = path.isNotEmpty() && !path.endsWith(".php") && !path.contains("/get") && !path.contains("/player_api")
    val server = "${uri.scheme ?: "http"}://$host$port${if (keepPath) path else ""}"
    if (user.isEmpty() || pass.isEmpty()) return null
    return Account(server, user, pass)
}

@Composable
fun LoginScreen(app: AppController) {
    val existing = app.prefs.account
    var server by rememberSaveable { mutableStateOf(existing?.server ?: "") }
    var user by rememberSaveable { mutableStateOf(existing?.username ?: "") }
    var pass by rememberSaveable { mutableStateOf(existing?.password ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val firstField = remember { FocusRequester() }

    fun submit() {
        if (loading) return
        val acc = parseAccount(server, user, pass)
        if (acc == null) {
            error = "Renseignez l'adresse du serveur, l'identifiant et le mot de passe."
            return
        }
        server = acc.server
        user = acc.username
        pass = acc.password
        error = null
        loading = true
        scope.launch {
            try {
                app.repo.login(acc)
                app.reset(Screen.Splash)
            } catch (e: Exception) {
                error = e.message ?: "Connexion impossible"
            } finally {
                loading = false
            }
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(listOf(C.Bg, C.Surface1)))
            .padding(horizontal = Safe.h * 1.5f, vertical = Safe.v * 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 56.dp)) {
            LumenLogo(markSize = 30.dp, textSize = 24.sp)
            Spacer(Modifier.height(28.dp))
            Text("Connectez votre service", style = T.hero, color = C.Text1)
            Spacer(Modifier.height(14.dp))
            Text(
                "Saisissez les informations Xtream Codes fournies par votre fournisseur. " +
                    "Astuce : vous pouvez coller votre lien M3U complet dans le champ Serveur, " +
                    "l'identifiant et le mot de passe seront remplis automatiquement.",
                style = T.body, color = C.Text2,
            )
        }
        Column(Modifier.width(400.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            TvTextField(
                value = server, onValueChange = { server = it }, label = "Serveur",
                placeholder = "http://exemple.com:8080", keyboardType = KeyboardType.Uri,
                focusRequester = firstField,
            )
            TvTextField(value = user, onValueChange = { user = it }, label = "Identifiant", placeholder = "Identifiant")
            TvTextField(
                value = pass, onValueChange = { pass = it }, label = "Mot de passe",
                placeholder = "Mot de passe", password = true, keyboardType = KeyboardType.Password,
                onDone = { submit() },
            )
            Box(Modifier.height(22.dp)) {
                error?.let { Text(it, style = T.meta, color = C.Live, maxLines = 2) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton(
                    text = if (loading) "Connexion…" else "Se connecter",
                    icon = Icons.Filled.Login,
                    primary = true,
                    onClick = { submit() },
                )
                if (loading) {
                    Spacer(Modifier.width(16.dp))
                    Spinner(size = 26.dp)
                }
            }
        }
    }
    LaunchedEffect(Unit) { firstField.safeRequest(120) }
}

@Composable
fun SyncScreen(app: AppController) {
    val state by app.repo.sync.collectAsState()
    var attempt by remember { mutableStateOf(0) }
    val retryFr = remember { FocusRequester() }

    LaunchedEffect(attempt) {
        try {
            app.repo.sync()
            app.reset(Screen.Profiles())
        } catch (_: Exception) {
        }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(C.Surface1, C.Bg))), contentAlignment = Alignment.Center) {
        Column(Modifier.width(520.dp)) {
            Text("Préparation de votre catalogue", style = T.display, color = C.Text1)
            Spacer(Modifier.height(8.dp))
            Text(
                "Lumen télécharge et indexe vos chaînes, films et séries une fois pour toutes. " +
                    "Les prochains démarrages seront instantanés.",
                style = T.body, color = C.Text2,
            )
            Spacer(Modifier.height(28.dp))
            SyncLine("Chaînes", state.live, state.running)
            SyncLine("Films", state.movies, state.running)
            SyncLine("Séries", state.series, state.running)
            val err = state.error
            if (err != null && !state.running) {
                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ErrorOutline, null, tint = C.Live, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(err, style = T.bodyStrong, color = C.Live)
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton(
                        "Réessayer", icon = Icons.Filled.Refresh, primary = true,
                        onClick = { attempt++ }, modifier = Modifier.focusRequester(retryFr),
                    )
                    PillButton("Modifier le compte", onClick = { app.reset(Screen.Login) })
                }
                LaunchedEffect(err) { retryFr.safeRequest(60) }
            }
        }
    }
}

@Composable
private fun SyncLine(label: String, count: Int, running: Boolean) {
    Row(
        Modifier.fillMaxWidth().height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            when {
                count >= 0 -> Icon(Icons.Filled.Check, null, tint = C.Green, modifier = Modifier.size(22.dp))
                running -> Spinner(size = 20.dp)
                else -> Box(Modifier.size(8.dp).clip(CircleShape).background(C.Text3))
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(label, style = T.label, color = C.Text1, modifier = Modifier.weight(1f))
        Text(
            if (count >= 0) "%,d".format(count).replace(',', ' ') else if (running) "en cours…" else "",
            style = T.meta, color = C.Text2,
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
}
