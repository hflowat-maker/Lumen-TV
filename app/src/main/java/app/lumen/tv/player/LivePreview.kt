package app.lumen.tv.player

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import app.lumen.tv.CrashReport
import app.lumen.tv.data.Channel
import app.lumen.tv.data.Prefs
import app.lumen.tv.data.Repo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

@Immutable
data class PreviewState(
    val channelId: Int = -1,
    /** La première image est affichée : on peut montrer la vidéo à la place du logo. */
    val showing: Boolean = false,
    val failed: Boolean = false,
)

/**
 * Aperçu vidéo d'une chaîne dans la grande tuile du Direct.
 * Un seul lecteur à la fois (une seule connexion à l'abonnement). Quand on appuie sur OK,
 * le lecteur plein écran reprend ce même lecteur : pas de nouvelle connexion ni de temps de chargement.
 */
class LivePreview(
    private val context: Context,
    private val repo: Repo,
    private val prefs: Prefs,
    private val http: OkHttpClient,
) {
    private var engine: PlayerEngine? = null

    private val _state = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    /** Lecteur de l'aperçu en cours, pour l'afficher dans la tuile. */
    val player: Player? get() = engine?.player

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            _state.value = _state.value.copy(showing = true)
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(CrashReport.TAG, "Aperçu impossible : ${PlayerEngine.technical(error)}")
            engine?.stop()
            _state.value = _state.value.copy(showing = false, failed = true)
        }
    }

    fun start(channel: Channel) {
        if (_state.value.channelId == channel.id && engine != null) return
        release()
        val e = PlayerEngine(
            context.applicationContext, http, prefs.userAgent,
            alternateBase = repo.alternateStreamBase(), preferAlternate = prefs.preferStreamBase,
            initialStack = prefs.httpStack,
        )
        e.player.addListener(listener)
        e.player.volume = 1f
        engine = e
        _state.value = PreviewState(channelId = channel.id)
        e.playLive(repo.liveUrl(channel.id), prefs.liveFormat == "m3u8")
    }

    /** Arrête l'aperçu (seulement s'il concerne [channelId] quand il est fourni). */
    fun release(channelId: Int? = null) {
        if (channelId != null && channelId != _state.value.channelId) return
        engine?.let {
            it.player.removeListener(listener)
            it.release()
        }
        engine = null
        _state.value = PreviewState()
    }

    /**
     * Donne le lecteur au plein écran s'il lit déjà [channelId] sans erreur.
     * Sinon l'aperçu est arrêté et le plein écran ouvre sa propre connexion.
     */
    fun takeOver(channelId: Int?): PlayerEngine? {
        val e = engine
        val s = _state.value
        if (e == null || channelId == null || s.channelId != channelId || s.failed ||
            e.player.playerError != null || e.player.playbackState == Player.STATE_IDLE
        ) {
            release()
            return null
        }
        e.player.removeListener(listener)
        engine = null
        _state.value = PreviewState()
        Log.i(CrashReport.TAG, "Plein écran : reprise du lecteur de l'aperçu")
        return e
    }
}
