package app.lumen.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import app.lumen.tv.R

/**
 * Palette « Velours » : fond aubergine, laiton pour la sélection, crème pour le texte.
 * Les noms historiques (Gold, Text1…) sont conservés pour tous les écrans.
 */
object C {
    val Bg = Color(0xFF24101A)
    val BgDeep = Color(0xFF1A0B12)
    val BgGlow = Color(0xFF3A1A2A)
    val Surface1 = Color(0xFF2D1520)
    val Surface2 = Color(0xFF351826)
    val Surface3 = Color(0xFF43202F)
    val Line = Color(0x26F3E7D3)
    val Text1 = Color(0xFFF3E7D3)
    val Text2 = Color(0xFFB9A08F)
    val Text3 = Color(0xFF8A6F68)
    /** Laiton : sélection, éléments actifs. */
    val Gold = Color(0xFFC9A45C)
    val GoldSoft = Color(0x2EC9A45C)
    /** Fond d'un élément focalisé (boutons, puces). */
    val Focus = Color(0xFFF3E7D3)
    val Live = Color(0xFFE4572E)
    val Green = Color(0xFF6CC48C)
    val Glass = Color(0x1AF3E7D3)
    val Scrim = Color(0xE61A0B12)

    /** Dégradé de fond des écrans. */
    val backdrop: Brush = object : ShaderBrush() {
        override fun createShader(size: Size): Shader = RadialGradientShader(
            center = Offset(size.width * 0.75f, 0f),
            radius = size.width * 1.05f,
            colors = listOf(BgGlow, Bg, BgDeep),
            colorStops = listOf(0f, 0.48f, 1f),
        )
    }

    val avatars = listOf(
        Color(0xFFC9A45C), Color(0xFFB8546E), Color(0xFF5E7FB8), Color(0xFF5FA37E),
        Color(0xFF9A6FC2), Color(0xFFD9824A), Color(0xFF4AA3A3), Color(0xFFD6739F),
    )

    fun avatar(i: Int): Color = avatars[((i % avatars.size) + avatars.size) % avatars.size]
}

/** Instrument Sans : interface et textes. */
val Sans = FontFamily(
    Font(R.font.instrumentsans_regular, FontWeight.Normal),
    Font(R.font.instrumentsans_medium, FontWeight.Medium),
    Font(R.font.instrumentsans_semibold, FontWeight.SemiBold),
    Font(R.font.instrumentsans_bold, FontWeight.Bold),
)

/** Instrument Serif : grands titres d'étagères et logo. */
val Serif = FontFamily(Font(R.font.instrumentserif_regular, FontWeight.Normal))

object T {
    /** Titre d'étagère (taille réduite automatiquement s'il est long). */
    val shelf = TextStyle(fontFamily = Serif, fontSize = 58.sp, lineHeight = 60.sp, letterSpacing = (-0.3).sp)
    val logo = TextStyle(fontFamily = Serif, fontSize = 27.sp, lineHeight = 30.sp)
    val hero = TextStyle(fontFamily = Serif, fontSize = 42.sp, lineHeight = 44.sp, letterSpacing = (-0.2).sp)
    val display = TextStyle(fontFamily = Serif, fontSize = 36.sp, lineHeight = 38.sp, letterSpacing = (-0.2).sp)
    val title = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp)
    val row = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp)
    val body = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp)
    val bodyStrong = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp)
    val label = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp)
    val nav = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp)
    val chip = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 15.sp)
    val meta = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp)
    val small = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.2.sp)
    val overline = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp)
    val cardTitle = TextStyle(fontFamily = Serif, fontSize = 18.sp, lineHeight = 20.sp)
}

@Composable
fun LumenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Gold,
            onPrimary = C.Bg,
            background = C.Bg,
            onBackground = C.Text1,
            surface = C.Surface1,
            onSurface = C.Text1,
            surfaceVariant = C.Surface2,
            onSurfaceVariant = C.Text2,
        ),
    ) {
        CompositionLocalProvider(LocalContentColor provides C.Text1, content = content)
    }
}
