package app.lumen.tv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.tv.material3.Text
import app.lumen.tv.R
import app.lumen.tv.ui.theme.Sans

/** Symbole « lm » seul (barre du haut). */
@Composable
fun LumenMark(size: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.logo_lm), contentDescription = "Lumen", modifier = modifier.size(size))
}

/** Symbole et nom « lumen » (démarrage, connexion). */
@Composable
fun LumenLogo(markSize: Dp, textSize: TextUnit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        LumenMark(markSize)
        Spacer(Modifier.width(markSize * 0.28f))
        Text(
            "lumen",
            style = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = textSize, letterSpacing = (-0.04).em),
            color = Color.White,
        )
    }
}
