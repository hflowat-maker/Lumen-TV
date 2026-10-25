package app.lumen.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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

object C {
    val Bg = Color(0xFF0A0A0E)
    val Surface1 = Color(0xFF14141A)
    val Surface2 = Color(0xFF1D1D25)
    val Surface3 = Color(0xFF2A2A34)
    val Line = Color(0x1FFFFFFF)
    val Text1 = Color(0xFFF4F4F6)
    val Text2 = Color(0xFFA6A6B2)
    val Text3 = Color(0xFF6F6F7C)
    val Gold = Color(0xFFF2B544)
    val GoldSoft = Color(0x33F2B544)
    val Live = Color(0xFFE5484D)
    val Green = Color(0xFF3DD68C)
    val Glass = Color(0x1FFFFFFF)
    val Scrim = Color(0xE60A0A0E)

    val avatars = listOf(
        Color(0xFFF2B544), Color(0xFFE5484D), Color(0xFF3E7BFA), Color(0xFF3DD68C),
        Color(0xFFA970FF), Color(0xFFFF8A3D), Color(0xFF2BC4C4), Color(0xFFFF6FB5),
    )

    fun avatar(i: Int): Color = avatars[((i % avatars.size) + avatars.size) % avatars.size]
}

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

val InterDisplay = FontFamily(
    Font(R.font.interdisplay_bold, FontWeight.Bold),
    Font(R.font.interdisplay_extrabold, FontWeight.ExtraBold),
)

object T {
    val hero = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.ExtraBold, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-0.6).sp)
    val display = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.4).sp)
    val title = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp)
    val row = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp)
    val body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp)
    val bodyStrong = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp)
    val label = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp)
    val meta = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp)
    val small = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp)
    val overline = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.4.sp)
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
