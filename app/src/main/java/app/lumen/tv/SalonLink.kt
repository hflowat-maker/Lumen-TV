package app.lumen.tv

import android.content.Intent
import app.lumen.tv.data.ContentType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ce que Salon (l'écran d'accueil, classes io.freemytv) demande d'ouvrir dans Lumen : la fiche d'un film ou d'une
 * série du catalogue, ou une chaîne en direct (↑/↓ zappent dans la rangée d'où elle vient). Salon passe par
 * MainActivity avec les extras salon_open (movie, series, live), salon_id et salon_ids.
 */
object SalonLink {
    sealed interface Request
    data class Title(val type: ContentType, val id: Int) : Request
    data class Live(val id: Int, val list: List<Int>) : Request

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    /** Vrai quand Lumen a été ouvert par Salon : Retour y revient au lieu de rester dans Lumen. */
    @Volatile
    var fromSalon = false
        private set

    /** Lit la demande de Salon dans l'intent (et l'en retire, pour qu'une rotation ne la rejoue pas). */
    fun offer(intent: Intent?) {
        val open = intent?.getStringExtra(EXTRA_OPEN) ?: return
        val id = intent.getIntExtra(EXTRA_ID, 0)
        val list = intent.getIntArrayExtra(EXTRA_IDS)?.toList().orEmpty()
        intent.removeExtra(EXTRA_OPEN)
        intent.removeExtra(EXTRA_ID)
        intent.removeExtra(EXTRA_IDS)
        if (id <= 0) return
        _pending.value = when (open) {
            "movie" -> Title(ContentType.MOVIE, id)
            "series" -> Title(ContentType.SERIES, id)
            "live" -> Live(id, list.ifEmpty { listOf(id) })
            else -> return
        }
        fromSalon = true
    }

    /** La demande est traitée (une plus récente, arrivée entre-temps, reste en attente). */
    fun done(r: Request) {
        _pending.compareAndSet(r, null)
    }

    /**
     * Vrai entre « Retour vers Salon » et le retour de Lumen au premier plan : l'écran reste sombre. Sinon l'image que
     * le système garde de Lumen (son accueil) apparaîtrait un instant à la prochaine chaîne ouverte depuis Salon.
     */
    val curtain = MutableStateFlow(false)

    fun leftSalon() {
        fromSalon = false
    }

    private const val EXTRA_OPEN = "salon_open"
    private const val EXTRA_ID = "salon_id"
    private const val EXTRA_IDS = "salon_ids"
}
