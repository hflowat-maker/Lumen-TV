package app.lumen.tv.data

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("lumen", Context.MODE_PRIVATE)

    var account: Account?
        get() {
            val server = sp.getString("server", null) ?: return null
            val user = sp.getString("user", null) ?: return null
            val pass = sp.getString("pass", null) ?: return null
            return Account(server, user, pass)
        }
        set(value) {
            sp.edit().apply {
                if (value == null) {
                    remove("server"); remove("user"); remove("pass")
                } else {
                    putString("server", value.server)
                    putString("user", value.username)
                    putString("pass", value.password)
                }
            }.apply()
        }

    fun langFor(profileId: Long): String? = sp.getString("lang_$profileId", null)
    fun setLangFor(profileId: Long, key: String) = sp.edit().putString("lang_$profileId", key).apply()

    var activeProfileId: Long
        get() = sp.getLong("profile", -1)
        set(v) = sp.edit().putLong("profile", v).apply()

    /** "ts" (MPEG-TS, plus compatible) ou "m3u8" (HLS). */
    var liveFormat: String
        get() = sp.getString("live_format", "ts") ?: "ts"
        set(v) = sp.edit().putString("live_format", v).apply()

    var userAgent: String
        get() = sp.getString("ua", DEFAULT_UA)?.ifBlank { DEFAULT_UA } ?: DEFAULT_UA
        set(v) = sp.edit().putString("ua", v).apply()

    var hideAdult: Boolean
        get() = sp.getBoolean("hide_adult", true)
        set(v) = sp.edit().putBoolean("hide_adult", v).apply()

    var parentalPin: String?
        get() = sp.getString("parental_pin", null)?.takeIf { it.length == 4 }
        set(v) = sp.edit().putString("parental_pin", v).apply()

    var autoNextEpisode: Boolean
        get() = sp.getBoolean("auto_next", true)
        set(v) = sp.edit().putBoolean("auto_next", v).apply()

    /** Son court pendant l'animation d'ouverture. */
    var introSound: Boolean
        get() = sp.getBoolean("intro_sound", true)
        set(v) = sp.edit().putBoolean("intro_sound", v).apply()

    /** 0 = adapté, 1 = remplir (zoom), 2 = étiré */
    var resizeMode: Int
        get() = sp.getInt("resize", 0)
        set(v) = sp.edit().putInt("resize", v).apply()

    var lastSync: Long
        get() = sp.getLong("last_sync", 0)
        set(v) = sp.edit().putLong("last_sync", v).apply()

    var accountExp: Long
        get() = sp.getLong("acc_exp", 0)
        set(v) = sp.edit().putLong("acc_exp", v).apply()

    var accountMaxCons: Int
        get() = sp.getInt("acc_max", 0)
        set(v) = sp.edit().putInt("acc_max", v).apply()

    var accountActiveCons: Int
        get() = sp.getInt("acc_active", 0)
        set(v) = sp.edit().putInt("acc_active", v).apply()

    /** Adresse de diffusion annoncée par le serveur (peut différer de l'adresse de connexion). */
    var streamBase: String?
        get() = sp.getString("stream_base", null)
        set(v) = sp.edit().putString("stream_base", v).apply()

    /** Adresse de diffusion trouvée automatiquement (port qui accepte les vidéos). */
    var discoveredStreamBase: String?
        get() = sp.getString("found_stream_base", null)
        set(v) = sp.edit().putString("found_stream_base", v).apply()

    /** Forme des adresses de chaînes acceptée par le serveur : standard, short, shortts. */
    var liveUrlStyle: String
        get() = sp.getString("live_style", "standard") ?: "standard"
        set(v) = sp.edit().putString("live_style", v).apply()

    /** Forme des adresses de films/épisodes : standard, noext, mp4. */
    var vodUrlStyle: String
        get() = sp.getString("vod_style", "standard") ?: "standard"
        set(v) = sp.edit().putString("vod_style", v).apply()

    /** Pile réseau vidéo qui fonctionne avec ce serveur (voir PlayerEngine.STACKS). */
    var httpStack: String
        get() = sp.getString("http_stack", "java") ?: "java"
        set(v) = sp.edit().putString("http_stack", v).apply()

    var preferStreamBase: Boolean
        get() = sp.getBoolean("prefer_stream_base", false)
        set(v) = sp.edit().putBoolean("prefer_stream_base", v).apply()

    var accountStatus: String
        get() = sp.getString("acc_status", "") ?: ""
        set(v) = sp.edit().putString("acc_status", v).apply()

    fun clearAccount() {
        sp.edit().remove("server").remove("user").remove("pass").remove("last_sync")
            .remove("acc_exp").remove("acc_max").remove("acc_status").remove("acc_active").remove("stream_base")
            .remove("found_stream_base").remove("prefer_stream_base")
            .remove("live_style").remove("vod_style").remove("http_stack").apply()
    }

    companion object {
        const val DEFAULT_UA = "LumenTV/1.0 (Linux; Android TV)"
    }
}
