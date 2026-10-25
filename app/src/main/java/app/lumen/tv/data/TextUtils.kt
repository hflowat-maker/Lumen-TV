package app.lumen.tv.data

import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Text {
    private val accents = Regex("\\p{Mn}+")
    private val spaces = Regex("\\s+")

    /** Minuscules sans accents, pour la recherche. */
    fun normalize(s: String): String =
        accents.replace(Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD), "")
            .replace(spaces, " ")
            .trim()

    private val tagCodes = setOf(
        "FR", "VF", "VO", "VOST", "VOSTFR", "MULTI", "EN", "UK", "US", "AR", "MA", "DZ", "TN",
        "BE", "CH", "CA", "QC", "DE", "ES", "IT", "PT", "NL", "TR", "4K", "UHD", "FHD", "HD",
        "SD", "NF", "AMZ", "ARAB", "AFR", "EU", "LATINO", "PL", "RO", "RU", "IN",
    )
    private val bracketPrefix = Regex("^\\s*(?:\\|[^|]{1,8}\\||\\[[^\\]]{1,8}\\]|\\([A-Z0-9+]{2,6}\\))\\s*[-:|]?\\s*")
    private val codePrefix = Regex("^\\s*([A-Z0-9+]{2,7})\\s*[-:|•]\\s+")
    private val yearSuffix = Regex("\\s*[(\\[]((?:19|20)\\d{2})[)\\]]\\s*$")
    private val yearDash = Regex("\\s+-\\s+((?:19|20)\\d{2})\\s*$")

    /** Retire les préfixes techniques du fournisseur (« FR - », « |VF| ») et l'année finale. */
    fun cleanTitle(raw: String): String {
        var s = raw.trim()
        repeat(3) {
            val b = bracketPrefix.find(s)
            if (b != null) {
                s = s.substring(b.range.last + 1)
                return@repeat
            }
            val c = codePrefix.find(s)
            if (c != null && c.groupValues[1] in tagCodes) {
                s = s.substring(c.range.last + 1)
            }
        }
        s = yearSuffix.replace(s, "")
        s = yearDash.replace(s, "")
        return s.trim().ifEmpty { raw.trim() }
    }

    fun extractYear(raw: String): String? =
        yearSuffix.find(raw.trim())?.groupValues?.get(1)
            ?: yearDash.find(raw.trim())?.groupValues?.get(1)

    fun yearOf(date: String?): String? {
        if (date.isNullOrBlank()) return null
        return Regex("(19|20)\\d{2}").find(date)?.value
    }

    private val episodeMarker = Regex("(?i)\\bS\\d{1,2}\\s*[ExÉ]\\s*\\d{1,3}\\b")

    fun cleanEpisodeTitle(raw: String, seriesName: String, number: Int): String {
        var s = raw.trim()
        val cleanSeries = cleanTitle(seriesName)
        val idx = s.indexOf(cleanSeries, ignoreCase = true)
        if (idx in 0..12) s = s.substring(idx + cleanSeries.length)
        s = cleanTitle(s)
        s = episodeMarker.replace(s, "")
        s = s.trim().trim('-', ':', '|', '–', '.', ' ').trim()
        return if (s.isBlank() || s.equals(cleanSeries, ignoreCase = true)) "Épisode $number" else s
    }

    fun durationLabel(secs: Int): String? {
        if (secs <= 0) return null
        val h = secs / 3600
        val m = (secs % 3600) / 60
        return when {
            h > 0 && m > 0 -> "$h h ${m.toString().padStart(2, '0')}"
            h > 0 -> "$h h"
            else -> "$m min"
        }
    }

    fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun remaining(ms: Long): String {
        val min = (ms / 60000).coerceAtLeast(1)
        return if (min >= 60) "${min / 60} h ${(min % 60).toString().padStart(2, '0')} restantes" else "$min min restantes"
    }

    private fun fmt(pattern: String) = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat(pattern, Locale.FRANCE)
    }

    private val hourFmt = fmt("HH:mm")
    private val dateFmt = fmt("d MMMM yyyy")
    private val dateTimeFmt = fmt("d MMM 'à' HH:mm")

    fun hour(ms: Long): String = hourFmt.get()!!.format(Date(ms))
    fun date(ms: Long): String = dateFmt.get()!!.format(Date(ms))
    fun dateTime(ms: Long): String = dateTimeFmt.get()!!.format(Date(ms))

    fun rating(r: Double): String? = if (r > 0.05) String.format(Locale.FRANCE, "%.1f", r) else null

    fun initials(name: String): String = name.trim().take(1).uppercase()
}
