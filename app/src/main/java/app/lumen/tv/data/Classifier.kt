package app.lumen.tv.data

import androidx.compose.runtime.Immutable
import java.text.Normalizer
import java.util.Locale

/**
 * Langues proposées en haut de l'écran. On peut en cocher plusieurs : seuls les contenus
 * des langues cochées apparaissent dans l'accueil, les films, les séries et les chaînes.
 */
enum class Lang(val key: String, val label: String, val chip: String) {
    FR("fr", "Français", "Français"),
    EN("en", "English", "English"),
    /** Films et séries turcs sous-titrés en arabe (catégories « تركي مترجم »). */
    TR("tr", "Turc, sous-titré en arabe", "Turc VOST arabe"),
    OTHER("other", "Autres langues", "Autres");

    companion object {
        val DEFAULT: Set<Lang> = setOf(FR, EN)
        val KIDS: Set<Lang> = setOf(FR)

        fun decode(value: String?): Set<Lang>? {
            if (value.isNullOrBlank()) return null
            // Anciennes valeurs (bascule unique) : « both », « fr », « en », « other ».
            if (value == "both") return DEFAULT
            val set = value.split(',').mapNotNull { k -> entries.firstOrNull { it.key == k.trim() } }.toSet()
            return set.ifEmpty { null }
        }

        fun encode(set: Set<Lang>): String = entries.filter { it in set }.joinToString(",") { it.key }
    }
}

/** Catégorie du fournisseur enrichie : langue détectée et regroupements. */
@Immutable
data class CatInfo(
    val id: String,
    val name: String,
    val count: Int,
    val lang: Lang,
    /** Libellé de la langue pour « Autres langues » (Arabe, Allemand…). */
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

    private val prefix = Regex("^\\s*[|\\[(]?\\s*([A-Za-z]{2,5}(?:/[A-Za-z]{2,3})?)\\s*[|\\-:•\\])]")

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

    // Turc sous-titré en arabe : « مسلسلات تركية مترجمة », « يعرض الآن تركي مترجم »…
    // Les versions doublées (« مدبلج ») restent dans Arabe.
    private val turkishAr = Regex("(?<!\\p{IsArabic})(ال)?ترك(ي|ية|يه|يا)?(?!\\p{IsArabic})")
    private val subtitledAr = Regex("مترجم")
    private val dubbedAr = Regex("مدبلج")
    private val turkishLatin = Regex("\\b(TURK|TURKISH|TURKCE|TURC|TURQUE|TURQUES|TURCS|TR)\\b")
    private val arabicSubLatin = Regex("\\b(VOSTAR|ARSUB|SUBAR|ARABIC SUB|AR SUB|SUB AR|ST AR|VOST AR|VOST ARABE|SOUS TITRE ARABE|ARABIC SUBTITLES?)\\b")

    fun isTurkishArabicSub(name: String): Boolean {
        if (dubbedAr.containsMatchIn(name)) return false
        if (turkishAr.containsMatchIn(name) && subtitledAr.containsMatchIn(name)) return true
        val up = plain(name)
        return turkishLatin.containsMatchIn(up) && arabicSubLatin.containsMatchIn(up)
    }

    fun language(name: String): Pair<Lang, String> {
        if (isTurkishArabicSub(name)) return Lang.TR to "Turc VOST arabe"
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

    /**
     * Étagères de genre (films, séries) ou de thème (chaînes) pour les langues cochées.
     * Les autres langues donnent une étagère par langue ([otherLanguageGroups]).
     */
    fun genreGroups(type: ContentType, infos: List<CatInfo>, langs: Set<Lang>): List<Group> {
        val inLang = infos.filter { it.lang in langs && it.lang != Lang.OTHER && it.lang != Lang.TR }
        val order = if (type == ContentType.LIVE) liveOrder else vodOrder
        val labels = if (type == ContentType.LIVE) liveLabels else vodLabels
        val out = ArrayList<Group>()
        for (key in order) {
            if (key == "new" || key == "all") continue
            val list = inLang.filter { key in it.groups }
            val itemWords = if (type == ContentType.SERIES) seriesGenreWords[key].orEmpty() else emptyList()
            if (list.isNotEmpty() || itemWords.isNotEmpty()) {
                out += Group(key, labels[key] ?: key, list.map { it.id }, list.sumOf { it.count }, itemWords)
            }
        }
        return out
    }

    /** Une étagère par langue « autre » (Arabe, Allemand…), la plus fournie d'abord. */
    fun otherLanguageGroups(infos: List<CatInfo>): List<Group> =
        infos.filter { it.lang == Lang.OTHER }
            .groupBy { it.otherLabel }
            .entries.sortedByDescending { e -> e.value.sumOf { it.count } }
            .map { (label, list) -> Group("lang:$label", label, list.map { it.id }, list.sumOf { it.count }) }

    /** Catégories d'une langue. */
    fun idsFor(infos: List<CatInfo>, lang: Lang): List<String> = infos.filter { it.lang == lang }.map { it.id }

    fun idsFor(infos: List<CatInfo>, langs: Set<Lang>): List<String> = infos.filter { it.lang in langs }.map { it.id }
}
