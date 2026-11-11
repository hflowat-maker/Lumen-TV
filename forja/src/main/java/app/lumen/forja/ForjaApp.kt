package app.lumen.forja

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import app.lumen.forja.data.ForjaApi
import app.lumen.forja.data.Store
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.TimeUnit

class ForjaApp : Application(), ImageLoaderFactory {

    lateinit var http: OkHttpClient
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
        Store.init(this)
        http = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(6, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .build()
        ForjaApi.http = http
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
                .maxSizeBytes(250L * 1024 * 1024)
                .build()
        }
        .respectCacheHeaders(false)
        .crossfade(180)
        .build()

    companion object {
        /** Navigateur de bureau : le site et son lecteur s'affichent comme sur un ordinateur. */
        const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
}

/** Enregistre le dernier plantage pour l'afficher au lancement suivant (photo possible depuis la TV). */
object CrashReport {
    private const val FILE = "last_crash.txt"
    const val TAG = "ForjaTV"

    fun install(context: Context) {
        val file = File(context.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val header = "Forja TV ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · " +
                    "${Build.MANUFACTURER} ${Build.MODEL}\nThread : ${thread.name}\n\n"
                file.writeText(header + sw.toString().take(12_000))
                Log.e(TAG, "Plantage", error)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? {
        val file = File(context.filesDir, FILE)
        return if (file.exists()) runCatching { file.readText() }.getOrNull() else null
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}

/** Exécute un chargement sans faire planter l'écran : l'erreur est journalisée et null est renvoyé. */
suspend fun <T> safely(what: String, block: suspend () -> T): T? = try {
    block()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Throwable) {
    Log.e(CrashReport.TAG, "Échec : $what", e)
    null
}
