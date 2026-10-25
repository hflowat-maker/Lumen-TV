package app.lumen.tv.ui.components

import android.view.KeyEvent as AKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.lumen.tv.ui.theme.C
import app.lumen.tv.ui.theme.T

/**
 * Champ texte pensé pour la télécommande : on navigue dessus sans ouvrir le clavier,
 * OK ouvre le clavier système, « Terminé » referme.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onDone: () -> Unit = {},
) {
    var editing by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    var returnFocus by remember { mutableStateOf(false) }
    val fieldFr = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(10.dp)

    Column(modifier) {
        Text(label, style = T.meta, color = C.Text2)
        Spacer(Modifier.height(6.dp))
        if (editing) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = T.bodyStrong.copy(color = C.Text1, fontSize = 16.sp),
                cursorBrush = SolidColor(C.Gold),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    returnFocus = true
                    editing = false
                    keyboard?.hide()
                    onDone()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .focusRequester(fieldFr)
                    .onFocusChanged {
                        if (it.isFocused) hadFocus = true else if (hadFocus) editing = false
                    }
                    .clip(shape)
                    .background(C.Surface3)
                    .border(2.dp, C.Gold, shape)
                    .padding(horizontal = 16.dp),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(placeholder, style = T.bodyStrong, color = C.Text3)
                        inner()
                    }
                },
            )
            LaunchedEffect(Unit) {
                fieldFr.safeRequest(16)
                keyboard?.show()
            }
        } else {
            Clickable(
                onClick = {
                    hadFocus = false
                    editing = true
                },
                modifier = Modifier.fillMaxWidth().height(50.dp).focusRequester(focusRequester),
                shape = shape,
                container = C.Surface2,
                focusedContainer = C.Surface3,
                borderWidth = 2.dp,
            ) {
                val shown = when {
                    value.isEmpty() -> placeholder
                    password -> "•".repeat(value.length.coerceAtMost(24))
                    else -> value
                }
                Text(
                    shown,
                    style = T.bodyStrong,
                    color = if (value.isEmpty()) C.Text3 else C.Text1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp),
                )
            }
            LaunchedEffect(Unit) {
                if (returnFocus) {
                    returnFocus = false
                    focusRequester.safeRequest(16)
                }
            }
        }
    }
}

/** Fenêtre modale plein écran (le focus reste piégé dedans). */
@Composable
fun Modal(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(C.Scrim), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

/** Saisie d'un code à 4 chiffres. [onSubmit] renvoie true si le code est accepté. */
@Composable
fun PinDialog(
    title: String,
    subtitle: String? = null,
    onSubmit: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val firstKey = remember { FocusRequester() }

    fun press(d: String) {
        if (pin.length >= 4) return
        error = false
        pin += d
        if (pin.length == 4) {
            if (!onSubmit(pin)) {
                error = true
                pin = ""
            }
        }
    }

    Modal(onDismiss) {
        Column(
            Modifier
                .width(380.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(C.Surface1)
                .padding(28.dp)
                .onPreviewKeyEvent { ev ->
                    val code = ev.nativeKeyEvent.keyCode
                    if (ev.type == KeyEventType.KeyDown && code in AKeyEvent.KEYCODE_0..AKeyEvent.KEYCODE_9) {
                        press((code - AKeyEvent.KEYCODE_0).toString())
                        true
                    } else {
                        false
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = T.title, color = C.Text1)
            if (subtitle != null) {
                Spacer(Modifier.height(6.dp))
                Text(subtitle, style = T.body, color = C.Text2)
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(4) { i ->
                    Box(
                        Modifier.size(16.dp).clip(CircleShape)
                            .background(if (i < pin.length) C.Gold else if (error) C.Live.copy(alpha = 0.5f) else C.Surface3)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(if (error) "Code incorrect" else " ", style = T.meta, color = C.Live)
            Spacer(Modifier.height(10.dp))
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("x", "0", "<"))
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    row.forEach { k ->
                        val mod = if (k == "1") Modifier.focusRequester(firstKey) else Modifier
                        Clickable(
                            onClick = {
                                when (k) {
                                    "x" -> onDismiss()
                                    "<" -> { pin = pin.dropLast(1); error = false }
                                    else -> press(k)
                                }
                            },
                            modifier = mod.size(64.dp),
                            shape = CircleShape,
                            container = C.Surface2,
                            focusedContainer = Color.White,
                            focusedContentColor = C.Bg,
                            focusedScale = 1.08f,
                        ) {
                            when (k) {
                                "x" -> Icon(Icons.Filled.Close, null, modifier = Modifier.align(Alignment.Center).size(22.dp))
                                "<" -> Icon(Icons.Filled.Backspace, null, modifier = Modifier.align(Alignment.Center).size(22.dp))
                                else -> Text(k, style = T.title, modifier = Modifier.align(Alignment.Center))
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { firstKey.safeRequest(80) }
}

/** Boîte de confirmation simple. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val fr = remember { FocusRequester() }
    Modal(onDismiss) {
        Column(
            Modifier.width(460.dp).clip(RoundedCornerShape(20.dp)).background(C.Surface1).padding(28.dp),
        ) {
            Text(title, style = T.title, color = C.Text1)
            Spacer(Modifier.height(10.dp))
            Text(message, style = T.body, color = C.Text2)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Annuler", onClick = onDismiss, modifier = Modifier.focusRequester(fr))
                PillButton(confirm, onClick = onConfirm, primary = true)
            }
        }
    }
    LaunchedEffect(Unit) { fr.safeRequest(80) }
}

/** Ligne de réglage cliquable. */
@Composable
fun SettingRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    toggle: Boolean? = null,
    danger: Boolean = false,
    onFocus: (() -> Unit)? = null,
) {
    Clickable(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .onFocusChanged { if (it.isFocused) onFocus?.invoke() },
        shape = RoundedCornerShape(12.dp),
        container = C.Surface1,
        focusedContainer = Color.White,
        contentColor = if (danger) C.Live else C.Text1,
        focusedContentColor = if (danger) C.Live else C.Bg,
        focusedScale = 1.02f,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = T.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (value != null) {
                Text(value, style = T.meta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
            }
            if (toggle != null) {
                Spacer(Modifier.width(12.dp))
                Toggle(toggle)
            } else if (!danger) {
                Icon(Icons.Filled.ChevronRight, null, modifier = Modifier.padding(start = 8.dp).size(20.dp))
            }
        }
    }
}

/** Clavier à l'écran pour la recherche (plus rapide à la télécommande que le clavier système). */
@Composable
fun SearchKeyboard(
    onChar: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    firstKey: FocusRequester = remember { FocusRequester() },
) {
    val rows = "abcdefghijklmnopqrstuvwxyz1234567890".chunked(6)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEachIndexed { ri, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEachIndexed { ci, ch ->
                    val m = if (ri == 0 && ci == 0) Modifier.focusRequester(firstKey) else Modifier
                    KeyCap(m.size(42.dp), onClick = { onChar(ch) }) {
                        Text(ch.uppercase(), style = T.label, modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyCap(Modifier.width(90.dp).height(42.dp), onClick = { onChar(' ') }) {
                Icon(Icons.Filled.SpaceBar, null, modifier = Modifier.align(Alignment.Center).size(22.dp))
            }
            KeyCap(Modifier.width(90.dp).height(42.dp), onClick = onBackspace) {
                Icon(Icons.Filled.Backspace, null, modifier = Modifier.align(Alignment.Center).size(20.dp))
            }
            KeyCap(Modifier.width(90.dp).height(42.dp), onClick = onClear) {
                Text("Effacer", style = T.meta, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun KeyCap(modifier: Modifier, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Clickable(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        container = C.Surface2,
        focusedContainer = Color.White,
        contentColor = C.Text1,
        focusedContentColor = C.Bg,
        focusedScale = 1.1f,
        content = content,
    )
}
