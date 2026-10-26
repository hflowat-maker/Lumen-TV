package app.lumen.tv.ui.screens

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.lumen.tv.CrashReport
import app.lumen.tv.R
import app.lumen.tv.data.SyncProgress
import app.lumen.tv.data.Text as Txt
import app.lumen.tv.ui.AppController
import app.lumen.tv.ui.Screen
import app.lumen.tv.ui.components.PillButton
import app.lumen.tv.ui.components.safeRequest
import app.lumen.tv.ui.theme.C
import app.lumen.tv.ui.theme.Sans
import app.lumen.tv.ui.theme.T
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Tracés du logo « lm » (même dessin que res/drawable/logo_lm.xml).
private const val WHITE_PATH = "M22,10 V84 A14,14 0 0 0 50,84 V62 A13,13 0 0 1 76,62 V102"
private const val YELLOW_PATH = "M76,62 A13,13 0 0 1 102,62 V102"

private val VioletLight = Color(0xFF6A3BFF)
private val Violet = Color(0xFF5426EE)
private val VioletDeep = Color(0xFF3E17C4)
private val Yellow = Color(0xFFFFD23F)

/**
 * Ouverture de l'appli : le « lm » se trace, l'arche jaune s'allume, « lumen » apparaît.
 * Pendant ce temps la playlist se met à jour ; une fois à jour, le logo s'envole
 * vers l'écran suivant (choix du profil, puis accueil).
 */
@Composable
fun IntroScreen(app: AppController) {
    val repo = app.repo
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val sync by repo.sync.collectAsState()

    val draw = remember { Animatable(0f) }
    val arch = remember { Animatable(0f) }
    val glow = remember { Animatable(0f) }
    val word = remember { Animatable(0f) }
    val outro = remember { Animatable(0f) }

    var showStatus by remember { mutableStateOf(false) }
    var canSkip by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var leaving by remember { mutableStateOf(false) }
    var rowW by remember { mutableIntStateOf(0) }
    var wordW by remember { mutableIntStateOf(0) }
    val hasAccount = repo.account != null
    val rootFr = remember { FocusRequester() }
    val retryFr = remember { FocusRequester() }

    suspend fun leave() {
        if (leaving) return
        leaving = true
        glow.animateTo(1f, tween(160))
        outro.animateTo(1f, tween(720, easing = FastOutLinearInEasing))
        app.reset(if (hasAccount) Screen.Profiles() else Screen.Login)
    }

    // Animation d'ouverture et son.
    LaunchedEffect(Unit) {
        rootFr.safeRequest(30)
        if (app.prefs.introSound) playIntroSound(context)
        launch { draw.animateTo(1f, tween(1000, easing = FastOutSlowInEasing)) }
        launch {
            delay(850)
            arch.animateTo(1f, tween(330, easing = LinearOutSlowInEasing))
            glow.animateTo(1f, tween(170))
            glow.animateTo(0.45f, tween(560))
        }
        launch {
            delay(1000)
            word.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
        }
    }

    // Mise à jour de la playlist pendant l'animation.
    LaunchedEffect(attempt) {
        failure = null
        if (!hasAccount) {
            delay(1800)
            leave()
            return@LaunchedEffect
        }
        if (attempt == 0) repo.refreshAccountInBackground()
        val job = repo.startSync()
        delay(if (attempt == 0) 1700 else 0)
        if (!job.isCompleted) showStatus = true
        val skip = launch {
            delay(3000)
            if (repo.hasCatalog()) canSkip = true
        }
        val result = job.await()
        skip.cancel()
        if (result.isSuccess) {
            leave()
        } else {
            val msg = result.exceptionOrNull()?.message ?: "erreur inconnue"
            android.util.Log.w(CrashReport.TAG, "Mise à jour au démarrage impossible : $msg")
            if (repo.hasCatalog()) {
                notice = "La playlist n'a pas pu être mise à jour ($msg). Ton catalogue précédent reste disponible."
                delay(2600)
                leave()
            } else {
                showStatus = true
                failure = msg
            }
        }
    }
    LaunchedEffect(failure) { if (failure != null) retryFr.safeRequest(80) }

    val statusAlpha by animateFloatAsState(if (showStatus || notice != null || failure != null) 1f else 0f, tween(350), label = "status")
    val spacerPx = with(density) { 30.dp.toPx() }
    val markPx = with(density) { 132.dp.toPx() }

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val o = outro.value
                drawRect(
                    Brush.linearGradient(
                        listOf(lerp(VioletLight, C.BgGlow, o), lerp(Violet, C.Bg, o), lerp(VioletDeep, C.BgDeep, o)),
                        start = Offset.Zero, end = Offset(size.width, size.height),
                    )
                )
            }
            .focusRequester(rootFr)
            .onPreviewKeyEvent { ev ->
                val ok = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
                if (ev.type == KeyEventType.KeyUp && ok && canSkip && failure == null) {
                    scope.launch { leave() }
                    true
                } else {
                    false
                }
            }
            .focusable()
    ) {
        Row(
            Modifier
                .align(Alignment.Center)
                .onSizeChanged { rowW = it.width }
                .graphicsLayer {
                    // Le symbole se trace au centre, puis glisse à gauche quand le nom apparaît.
                    translationX = (1f - word.value) * (wordW + spacerPx) / 2f
                    val o = outro.value
                    val s = 1f + o * o * 9f
                    scaleX = s
                    scaleY = s
                    alpha = 1f - ((o - 0.35f) / 0.65f).coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(if (rowW > 0) (markPx * 0.62f) / rowW else 0.2f, 0.6f)
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogoStroke(draw.value, arch.value, glow.value, Modifier.size(132.dp))
            Spacer(Modifier.width(30.dp))
            Text(
                "lumen",
                style = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 96.sp, letterSpacing = (-0.04).em),
                color = Color.White,
                modifier = Modifier
                    .onSizeChanged { wordW = it.width }
                    .graphicsLayer {
                        alpha = word.value
                        translationX = (1f - word.value) * -28.dp.toPx()
                    },
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 56.dp)
                .width(560.dp)
                .graphicsLayer { alpha = statusAlpha * (1f - outro.value) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val err = failure
            val note = notice
            when {
                err != null -> {
                    Text("Impossible de charger la playlist", style = T.title, color = Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(err, style = T.body, color = Color(0xD9FFFFFF), textAlign = TextAlign.Center, maxLines = 3)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton(
                            "Réessayer", icon = Icons.Filled.Refresh, primary = true,
                            onClick = { attempt++ }, modifier = Modifier.focusRequester(retryFr),
                        )
                        PillButton("Modifier le compte", onClick = { app.reset(Screen.Login) })
                    }
                }
                note != null -> Text(note, style = T.body, color = Color.White, textAlign = TextAlign.Center)
                else -> {
                    Text("Mise à jour de la playlist", style = T.label, color = Color.White)
                    Spacer(Modifier.height(10.dp))
                    LoadingBar()
                    Spacer(Modifier.height(10.dp))
                    Text(syncLine(sync), style = T.meta, color = Color(0xCCFFFFFF), maxLines = 1)
                    if (canSkip) {
                        Spacer(Modifier.height(8.dp))
                        Text("OK pour continuer sans attendre", style = T.meta, color = Yellow)
                    }
                }
            }
        }
    }
}

private fun syncLine(s: SyncProgress): String {
    val parts = listOfNotNull(
        s.live.takeIf { it >= 0 }?.let { Txt.count(it, "chaîne", "chaînes") },
        s.movies.takeIf { it >= 0 }?.let { Txt.count(it, "film", "films") },
        s.series.takeIf { it >= 0 }?.let { Txt.count(it, "série", "séries") },
    )
    return if (parts.isEmpty()) "Chaînes, films et séries…" else parts.joinToString(", ")
}

/** Barre de chargement : un reflet jaune qui glisse sur un rail fin. */
@Composable
private fun LoadingBar() {
    val t = rememberInfiniteTransition(label = "bar")
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "x")
    Box(Modifier.width(220.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x40FFFFFF))) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(70.dp)
                .graphicsLayer { translationX = (x * 290f - 70f).dp.toPx() }
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Yellow, Color.Transparent)))
        )
    }
}

/** Le « lm » tracé progressivement : [draw] pour le trait blanc, [arch] pour l'arche jaune, [glow] pour son halo. */
@Composable
private fun LogoStroke(draw: Float, arch: Float, glow: Float, modifier: Modifier) {
    val white = remember { PathParser().parsePathString(WHITE_PATH).toPath() }
    val yellow = remember { PathParser().parsePathString(YELLOW_PATH).toPath() }
    val mw = remember { PathMeasure().apply { setPath(white, false) } }
    val my = remember { PathMeasure().apply { setPath(yellow, false) } }
    val segW = remember { Path() }
    val segY = remember { Path() }
    Canvas(modifier) {
        val k = size.minDimension / 120f
        scale(k, k, pivot = Offset.Zero) {
            if (arch > 0f) {
                segY.reset()
                my.getSegment(0f, my.length * arch, segY, true)
                if (glow > 0f) {
                    drawPath(segY, Yellow.copy(alpha = 0.10f * glow), style = Stroke(15f + 34f * glow, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(segY, Yellow.copy(alpha = 0.22f * glow), style = Stroke(15f + 16f * glow, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                drawPath(segY, Yellow, style = Stroke(15f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            if (draw > 0f) {
                segW.reset()
                mw.getSegment(0f, mw.length * draw, segW, true)
                drawPath(segW, Color.White, style = Stroke(15f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

private fun playIntroSound(context: Context) {
    runCatching {
        MediaPlayer.create(context, R.raw.intro)?.apply {
            setOnCompletionListener { it.release() }
            start()
        }
    }
}
