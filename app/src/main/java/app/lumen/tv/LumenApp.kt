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
        repo = Repo(prefs, XtreamApi(http), Db(this))
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
