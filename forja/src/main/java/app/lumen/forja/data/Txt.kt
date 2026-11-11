package app.lumen.forja.data

/** Petits formats d'affichage en français. */
object Txt {
    fun duration(secs: Int): String? {
        if (secs <= 0) return null
        val h = secs / 3600
        val m = (secs % 3600) / 60
        return when {
            h > 0 && m > 0 -> "$h h ${m.toString().padStart(2, '0')}"
            h > 0 -> "$h h"
            m > 0 -> "$m min"
            else -> "1 min"
        }
    }

    fun remaining(secs: Long): String {
        val min = (secs / 60).coerceAtLeast(1)
        return if (min >= 60) "${min / 60} h ${(min % 60).toString().padStart(2, '0')} restantes" else "$min min restantes"
    }

    fun minutes(secs: Long): String {
        val min = (secs / 60).coerceAtLeast(1)
        return if (min >= 60) "${min / 60} h ${(min % 60).toString().padStart(2, '0')}" else "$min min"
    }

    fun count(n: Int, one: String, many: String): String = "$n ${if (n <= 1) one else many}"

    private val episodeWord = Regex("""(?i)(?:é|e)pisode\s*\d+""")

    /** « Épisode 12 » quand le nom du site ne fait que répéter le numéro et le titre de la série. */
    fun episodeLabel(number: Int, name: String): String =
        if (name.isBlank() || episodeWord.containsMatchIn(name)) "Épisode $number" else name
}
