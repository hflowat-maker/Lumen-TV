package app.lumen.tv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lumen.tv.data.Lang
import app.lumen.tv.ui.theme.C
import kotlin.math.cos
import kotlin.math.sin

private val FrBlue = Color(0xFF2350A8)
private val FrRed = Color(0xFFE1343F)
private val EnRed = Color(0xFFC8102E)
private val TrRed = Color(0xFFE30A17)

/**
 * Petit drapeau dessiné (pas d'émoji : rendu identique sur toutes les TV).
 * Français, anglais (croix rouge sur blanc), turc (croissant et étoile), autres (cadre pointillé).
 */
@Composable
fun LangFlag(lang: Lang, modifier: Modifier = Modifier, width: Dp = 16.dp, dim: Boolean = false) {
    val shape = RoundedCornerShape(width * 0.12f)
    Canvas(
        modifier
            .size(width, width * 2f / 3f)
            .graphicsLayer { alpha = if (dim) 0.45f else 1f }
            .clip(shape)
    ) {
        val w = size.width
        val h = size.height
        when (lang) {
            Lang.FR -> {
                drawRect(FrBlue, size = Size(w / 3f, h))
                drawRect(Color.White, topLeft = Offset(w / 3f, 0f), size = Size(w / 3f, h))
                drawRect(FrRed, topLeft = Offset(2f * w / 3f, 0f), size = Size(w / 3f + 1f, h))
            }
            Lang.EN -> {
                drawRect(Color.White)
                val band = h * 0.3f
                drawRect(EnRed, topLeft = Offset(0f, (h - band) / 2f), size = Size(w, band))
                drawRect(EnRed, topLeft = Offset((w - band) / 2f, 0f), size = Size(band, h))
            }
            Lang.TR -> {
                drawRect(TrRed)
                val u = w / 30f
                drawCircle(Color.White, radius = 5.2f * u, center = Offset(11f * u, h / 2f))
                drawCircle(TrRed, radius = 4.2f * u, center = Offset(12.4f * u, h / 2f))
                val star = Path()
                val cx = 18.6f * u
                val cy = h / 2f
                val outer = 2.6f * u
                val inner = 1.05f * u
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) outer else inner
                    val a = Math.toRadians((i * 36.0) - 180.0)
                    val x = cx + (r * cos(a)).toFloat()
                    val y = cy + (r * sin(a)).toFloat()
                    if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
                }
                star.close()
                drawPath(star, Color.White)
            }
            Lang.OTHER -> {
                val stroke = 1.2.dp.toPx()
                drawRoundRect(
                    color = C.Text2,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(w - stroke, h - stroke),
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 2.5f))),
                )
            }
        }
    }
}
