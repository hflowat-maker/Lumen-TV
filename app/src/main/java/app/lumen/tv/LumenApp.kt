package app.lumen.tv

import android.app.Application
import app.lumen.tv.data.Db
import app.lumen.tv.data.Prefs
import app.lumen.tv.data.Repo
import app.lumen.tv.data.XtreamApi
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class LumenApp : Application(), ImageLoaderFactory {

    lateinit var prefs: Prefs
        private set
    lateinit var http: OkHttpClient
        private set
    lateinit var repo: Repo
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
        prefs = Prefs(this)
        http = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 12 })
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", prefs.userAgent).build())
            }
            .build()
        repo = Repo(prefs, XtreamApi(http), Db(this), http)
        applyConfigFile()
    }

    /**
     * Réglage à distance par ADB : un fichier lumen-config.txt déposé dans le dossier de l'app
     * (seuls l'app et ADB peuvent y écrire) permet de corriger l'adresse du serveur.
     * Ligne attendue : server=http://hote:port
     */
    private fun applyConfigFile() {
        val dir = getExternalFilesDir(null) ?: return
        val file = java.io.File(dir, "lumen-config.txt")
        if (!file.exists()) return
        try {
            val values = file.readLines().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
            }.toMap()
            val server = values["server"]?.trimEnd('/')
            val acc = prefs.account
            if (!server.isNullOrBlank() && acc != null) {
                val normalized = if (server.startsWith("http", ignoreCase = true)) server else "http://$server"
                prefs.account = acc.copy(server = normalized)
                prefs.streamBase = null
                prefs.discoveredStreamBase = null
                prefs.preferStreamBase = false
                android.util.Log.i(CrashReport.TAG, "Adresse du serveur modifiée par ADB : $normalized")
            }
        } catch (e: Exception) {
            android.util.Log.e(CrashReport.TAG, "Fichier de réglage illisible", e)
        } finally {
            file.delete()
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(
            http.newBuilder()
                .readTimeout(20, TimeUnit.SECONDS)
                .dispatcher(Dispatcher().apply { maxRequestsPerHost = 8 })
                .build()
        )
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.22).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("images"))
                .maxSizeBytes(350L * 1024 * 1024)
                .build()
        }
        .respectCacheHeaders(false)
        .crossfade(180)
        .build()
}
