package app.lumen.forja.ui.components

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
import app.lumen.forja.ui.theme.C
import app.lumen.forja.ui.theme.T

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
        focusedContainer = C.Focus,
        contentColor = C.Text1,
        focusedContentColor = C.Bg,
        focusedScale = 1.1f,
        content = content,
    )
}
