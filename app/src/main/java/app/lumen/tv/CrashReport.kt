package app.lumen.tv

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Enregistre le dernier plantage pour l'afficher au lancement suivant (photo possible depuis la TV). */
object CrashReport {
    private const val FILE = "last_crash.txt"
    const val TAG = "Lumen"

    fun install(context: Context) {
        val file = File(context.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val header = "Lumen TV ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                    "Thread : ${thread.name}\n\n"
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
