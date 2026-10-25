package app.lumen.tv.data

import androidx.compose.runtime.Immutable
import java.text.Normalizer
import java.util.Locale

/** Choix de langue affiché en haut de l'écran. */
enum class LangMode(val key: String, val label: String) {
    FR("fr", "Français"),
    EN("en", "English"),
    BOTH("both", "Les deux"),
    OTHER("other", "Autres langues");

    fun accepts(lang: Lang): Boolean = when (this) {
        FR -> lang == Lang.FR
        EN -> lang == Lang.EN
        BOTH -> lang == Lang.FR || lang == Lang.EN
        OTHER -> lang == Lang.OTHER
    }

    companion object {
        fun of(key: String?): LangMode = entries.firstOrNull { it.key == key } ?: BOTH
    }
}

enum class Lang { FR, EN, OTHER }

/** Catégorie du fournisseur enrichie : langue détectée et regroupements. */
@Immutable
data class CatInfo(
    val id: String,
    val name: String,
    val count: Int,
    val lang: Lang,
    /** Libellé de langue pour le mode « Autres langues » (Arabe, Allemand…). */
    val otherLabel: String,
    val groups: Set<String>,
)

/** Regroupement affiché sous forme de puce (genre, thème, plateforme ou langue). */
@Immutable
data class Group(
    val key: String,
    val label: String,
    val catIds: List<String>,
    val count: Int,
    /** Mots-clés recherchés dans le genre des séries (en plus des catégories). */
    val itemGenreKeywords: List<String> = emptyList(),
)

/**
 * Classe les catégories du fournisseur : langue (préfixe « FR| », « EN - », nom du pays…)
 * et genre (mots-clés français et anglais), pour proposer quelques regroupements clairs
 * au lieu de centaines de catégories.
 */
object Classifier {

    // ---------------- Langue ----------------

    private val FR_CODES = setOf("FR", "FRA", "FRE", "VF", "VFF", "VOSTFR", "BE", "CH", "QC", "LU", "MC")
    private val EN_CODES = setOf("EN", "ENG", "UK", "US", "USA", "GB", "IE", "AU", "AUS", "CA", "NZ")

    private val prefix = Regex("^\\s*([A-Za-z]{2,5}(?:/[A-Za-z]{2,3})?)\\s*[|\\-:•]")

    private val OTHER_LABELS = linkedMapOf(
        "AR" to "Arabe", "KU" to "Kurde", "TR" to "Turc", "DE" to "Allemand", "AT" to "Allemand",
        "ES" to "Espagnol", "LA" to "Espagnol", "CL" to "Espagnol", "IT" to "Italien",
        "PT" to "Portugais", "BR" to "Portugais", "PT/BR" to "Portugais", "NL" to "Néerlandais",
        "IN" to "Indien & Asie", "ASIA" to "Indien & Asie", "PK" to "Indien & Asie", "BN" to "Indien & Asie",
        "VN" to "Indien & Asie", "TH" to "Indien & Asie", "MYS" to "Indien & Asie", "PH" to "Indien & Asie",
        "RU" to "Russe", "UA" to "Russe", "PL" to "Polonais", "GR" to "Grec", "IL" to "Hébreu",
        "IR" to "Persan", "AF" to "Persan", "AL" to "Albanais", "BG" to "Bulgare", "RO" to "Roumain",
        "HU" to "Europe de l'Est", "CZ" to "Europe de l'Est", "SR" to "Europe de l'Est", "BH" to "Europe de l'Est",
        "HR" to "Europe de l'Est", "MK" to "Europe de l'Est", "SI" to "Europe de l'Est", "LT" to "Europe de l'Est",
        "GE" to "Europe de l'Est", "AM" to "Europe de l'Est", "AZ" to "Europe de l'Est", "KZ" to "Europe de l'Est",
        "EX" to "Europe de l'Est", "SE" to "Nordique", "DK" to "Nordique", "NO" to "Nordique", "FI" to "Nordique",
        "AFR" to "Afrique", "SO" to "Afrique", "MT" to "Autres",
    )

    private val otherWords = listOf(
        "TURKISH" to "Turc", "INDIA" to "Indien & Asie", "PAKISTAN" to "Indien & Asie", "HINDI" to "Indien & Asie",
        "GERMANY" to "Allemand", "DEUTSCH" to "Allemand", "ESPANA" to "Espagnol", "ITALY" to "Italien",
        "KURDISH" to "Kurde", "HEBREW" to "Hébreu", "RUSSIAN" to "Russe", "RUSSAIN" to "Russe",
        "BULGARIYA" to "Bulgare", "NETHERLANDS" to "Néerlandais", "MALTA" to "Autres", "DANSK" to "Nordique",
        "SVENSKA" to "Nordique", "NORDIC" to "Nordique", "ARAB" to "Arabe", "PERSIAN" to "Persan",
        "GREEK" to "Grec", "POLSKA" to "Polonais", "LATINO" to "Espagnol",
    )

    private val frWords = listOf("FRANCE", "FRENCH", "FRANCAIS", "QUEBEC", "VOSTFR", "TRUEFRENCH")
    private val enWords = listOf("ENGLISH")

    private val nonLatin = Regex("[\\p{IsArabic}\\p{IsHebrew}\\p{IsGreek}\\p{IsCyrillic}\\p{IsDevanagari}\\p{IsThai}\\p{IsHan}]")

    private fun plain(s: String): String =
        // NFKD : décompose aussi les exposants (« ⁴ᴷ ᴴᴰᴿ » devient « 4K HDR »).
        Normalizer.normalize(s, Normalizer.Form.NFKD)
            .replace(Regex("\\p{Mn}+"), "")
            .uppercase(Locale.ROOT)

    private fun words(upper: String): Set<String> =
        upper.split(Regex("[^A-Z0-9+]+")).filter { it.isNotEmpty() }.toSet()

    fun language(name: String): Pair<Lang, String> {
        val code = prefix.find(name)?.groupValues?.get(1)?.uppercase(Locale.ROOT)
        if (code != null) {
            if (code in FR_CODES) return Lang.FR to "Français"
            if (code in EN_CODES) return Lang.EN to "English"
            val label = OTHER_LABELS[code] ?: OTHER_LABELS[code.substringBefore('/')]
            if (label != null) return Lang.OTHER to label
        }
        if (nonLatin.containsMatchIn(name)) {
            return Lang.OTHER to when {
                name.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.HEBREW } -> "Hébreu"
                name.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.GREEK } -> "Grec"
                name.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.CYRILLIC } -> "Russe"
                else -> "Arabe"
            }
        }
        val up = plain(name)
        val w = words(up)
        if (frWords.any { it in w || up.contains(it) }) return Lang.FR to "Français"
        otherWords.firstOrNull { (k, _) -> k in w || up.contains(k) }?.let { return Lang.OTHER to it.second }
        if (enWords.any { it in w }) return Lang.EN to "English"
        if (code != null) return Lang.OTHER to "Autres"
        // Catégorie internationale sans langue indiquée (« NETFLIX MOVIES ») : version originale anglaise.
        return Lang.EN to "English"
    }

    // ---------------- Genres (films et séries) ----------------

    data class Def(val key: String, val label: String, val words: List<String>, val itemWords: List<String> = emptyList())

    private val VOD_DEFS = listOf(
        Def("4k", "4K & Dolby", listOf("4K", "UHD", "3840", "DOLBY", "HDR")),
        Def("action", "Action", listOf("ACTION"), listOf("Action")),
        Def("comedy", "Comédie", listOf("COMEDY", "COMEDIE", "HUMOUR", "HUMOR"), listOf("Comédie", "Comedy", "Comedie")),
        Def("drama", "Drame", listOf("DRAMA", "DRAME"), listOf("Drame", "Drama")),
        Def("thriller", "Thriller & policier", listOf("THRILLER", "CRIME", "POLICIER", "SUSPENSE", "POLAR"),
            listOf("Thriller", "Crime", "Policier", "Mystery", "Mystère")),
        Def("horror", "Horreur", listOf("HORROR", "HORREUR", "EPOUVANTE"), listOf("Horreur", "Horror", "Épouvante")),
        Def("scifi", "Science-fiction & fantastique", listOf("SCIENCE", "SCIFI", "FANTASTIQUE", "FANTASY"),
            listOf("Science-Fiction", "Science Fiction", "Sci-Fi", "Fantastique", "Fantasy")),
        Def("adventure", "Aventure", listOf("AVENTURE", "ADVENTURE"), listOf("Aventure", "Adventure")),
        Def("kids", "Enfants & famille", listOf("KIDS", "ENFANT", "ENFANTS", "FAMILLE", "FAMILY", "JEUNESSE"),
            listOf("Familial", "Family", "Kids", "Enfants")),
        Def("animation", "Animation", listOf("ANIMATION", "CARTOON", "DESSIN"), listOf("Animation")),
        Def("anime", "Anime & manga", listOf("ANIME", "MANGA")),
        Def("docs", "Documentaires", listOf("DOCUMENTAIRE", "DOCUMENTAIRES", "DOCUMENTAIRS", "DOCUMENTARY", "DOCUMENTARIES", "DOCU"),
            listOf("Documentaire", "Documentary")),
        Def("history", "Histoire & biopics", listOf("HISTORIQUE", "HISTOIRE", "HISTORY", "BIOPIC", "GUERRE", "WAR"),
            listOf("Histoire", "History", "Guerre", "War")),
        Def("romance", "Romance", listOf("ROMANCE", "ROMANTIQUE", "ROMANTIC"), listOf("Romance")),
        Def("western", "Western", listOf("WESTERN", "WESTREN")),
        Def("reality", "Téléréalité", listOf("REALITY", "TELEREALITE")),
        Def("music", "Musique & concerts", listOf("MUSIQUE", "MUSIC", "MUSICAL", "CONCERT", "CONCERTS")),
        Def("sport", "Sport", listOf("SPORT", "WWE", "BOXING", "BOXE", "WORKOUT", "UFC")),
        Def("christmas", "Noël", listOf("CHRISTMAS", "NOEL")),
        Def("classics", "Classiques", listOf("ANCIEN", "CLASSIC", "CLASSIQUE", "OLD")),
        Def("collections", "Sagas", listOf("COLLECTION", "COLLECTIONS", "SAGA", "SAGAS")),
        Def("asia", "Asiatiques", listOf("ASIA", "ASIAN", "KOREAN", "KDRAMA")),
        Def("vostfr", "VOSTFR", listOf("VOSTFR")),
        Def("quebec", "Québec", listOf("QUEBEC")),
        Def("netflix", "Netflix", listOf("NETFLIX")),
        Def("disney", "Disney+", listOf("DISNEY", "DISNEY+")),
        Def("prime", "Prime Video", listOf("PRIME", "AMAZON")),
        Def("apple", "Apple TV+", listOf("APPLE", "APPLE+")),
        Def("hbo", "HBO & Max", listOf("HBO")),
        Def("fox", "Fox+", listOf("FOX", "FOX+")),
        Def("canal", "Canal+", listOf("CANAL", "CANAL+")),
    )

    private val NEW_WORDS = listOf("NOUVEAU", "NEW", "DERNIER", "DRENIER", "AJOUT", "EXCLUSIVIT", "RECENT", "LATEST")

    /** Ordre des puces : les genres d'abord, les plateformes ensuite. */
    val vodOrder: List<String> = listOf("new", "all") + VOD_DEFS.map { it.key }
    val vodLabels: Map<String, String> = VOD_DEFS.associate { it.key to it.label } +
        mapOf("new" to "Nouveautés", "all" to "Tout")
    val seriesGenreWords: Map<String, List<String>> = VOD_DEFS.filter { it.itemWords.isNotEmpty() }
        .associate { it.key to it.itemWords }

    fun vodGroups(name: String): Set<String> {
        val up = plain(name)
        val w = words(up)
        val out = HashSet<String>()
        for (d in VOD_DEFS) {
            if (d.words.any { word -> word in w || (word.length >= 6 && up.contains(word)) }) out += d.key
        }
        if (NEW_WORDS.any { up.contains(it) } || Regex("\\b20(2[4-9]|3\\d)\\b").containsMatchIn(up)) out += "new"
        return out
    }

    // ---------------- Thèmes (TV en direct) ----------------

    private val LIVE_DEFS = listOf(
        Def("general", "Généralistes", listOf("GENERAL", "GENERALISTE", "TNT", "ENTERTAINMENT", "FRANCE", "BELGIUM",
            "SWITZERLAND", "IRELAND", "CANADA", "AUSTRALIA", "ABC", "CBS", "FOX", "TELESAT", "CANAL+", "CANAL")),
        Def("sport", "Sport", listOf("SPORT", "SPORTS", "LIGUE1+", "LIGUE1", "DAZN", "SOCCER", "BEIN", "EQUIPE",
            "FORMULA", "F1", "NFL", "MLS", "TSN", "TENNIS", "FFF", "EUROSPORT", "FOOT", "FOOTBALL")),
        Def("cinema", "Cinéma & séries", listOf("CINEMA", "MOVIES", "MOVIE", "FILMS", "NETFLIX", "PRIME", "LUXPLAY",
            "PREMIUM", "24/7", "SKY")),
        Def("news", "Info", listOf("NEWS", "INFO", "INFOS")),
        Def("kids", "Enfants", listOf("KIDS", "ENFANTS", "ENFANT", "JEUNESSE", "CARTOON")),
        Def("docs", "Découverte", listOf("DOCUMENTAIRE", "DOCUMENTARY", "DISCOVERY", "DISCOVERY+", "NATURE")),
        Def("music", "Musique", listOf("MUSIQUE", "MUSIC")),
        Def("events", "Événements", listOf("PPV", "EVENT", "EVENTS")),
        Def("outremer", "Outre-mer & Afrique", listOf("CARIBBEAN", "AFRICA", "AFRIQUE", "ANTILLES", "REUNION")),
    )

    val liveOrder: List<String> = LIVE_DEFS.map { it.key } + "other"
    val liveLabels: Map<String, String> = LIVE_DEFS.associate { it.key to it.label } + ("other" to "Autres")

    fun liveGroups(name: String): Set<String> {
        val up = plain(name)
        val w = words(up)
        val out = LinkedHashSet<String>()
        // Le cinéma prime sur le sport pour « BEIN CINEMA », le sport sur les PPV sportifs.
        for (d in LIVE_DEFS) {
            if (d.words.any { word -> word in w || (word.contains('/') && up.contains(word)) }) out += d.key
        }
        if ("cinema" in out) out -= "sport"
        if ("sport" in out || "cinema" in out || "kids" in out || "docs" in out || "news" in out || "music" in out) {
            out -= "general"
        }
        if (out.isEmpty()) out += "other"
        return out
    }

    // ---------------- Assemblage ----------------

    fun classify(type: ContentType, cats: List<Category>): List<CatInfo> = cats.map { c ->
        val (lang, label) = language(c.name)
        val groups = if (type == ContentType.LIVE) liveGroups(c.name) else vodGroups(c.name)
        CatInfo(c.id, c.name, c.count, lang, label, groups)
    }

    /** Puces pour un type de contenu et une langue. */
    fun groupsFor(type: ContentType, infos: List<CatInfo>, mode: LangMode): List<Group> {
        val inLang = infos.filter { mode.accepts(it.lang) }
        if (inLang.isEmpty()) return emptyList()
        val allIds = inLang.map { it.id }
        val total = inLang.sumOf { it.count }

        if (mode == LangMode.OTHER) {
            // Autres langues : une puce par langue.
            val byLabel = inLang.groupBy { it.otherLabel }
                .entries.sortedByDescending { e -> e.value.sumOf { it.count } }
            val langGroups = byLabel.map { (label, list) ->
                Group("lang:$label", label, list.map { it.id }, list.sumOf { it.count })
            }
            return listOf(Group("all", "Tout", allIds, total)) + langGroups
        }

        val order = if (type == ContentType.LIVE) liveOrder else vodOrder
        val labels = if (type == ContentType.LIVE) liveLabels else vodLabels
        val out = ArrayList<Group>()
        for (key in order) {
            when (key) {
                "new" -> out += Group("new", labels.getValue("new"), allIds, total)
                "all" -> out += Group("all", if (type == ContentType.LIVE) "Toutes" else "Tout", allIds, total)
                else -> {
                    val list = inLang.filter { key in it.groups }
                    val itemWords = if (type == ContentType.SERIES) seriesGenreWords[key].orEmpty() else emptyList()
                    if (list.isNotEmpty() || itemWords.isNotEmpty()) {
                        out += Group(key, labels[key] ?: key, list.map { it.id }, list.sumOf { it.count }, itemWords)
                    }
                }
            }
        }
        return out
    }
}
