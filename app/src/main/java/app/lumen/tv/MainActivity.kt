package app.lumen.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import app.lumen.tv.player.LivePreview
import app.lumen.tv.ui.AppController
import app.lumen.tv.ui.AppRoot
import app.lumen.tv.ui.theme.LumenTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lumen = application as LumenApp
        if (savedInstanceState == null) SalonLink.offer(intent)
        setContent {
            LumenTheme {
                val app = remember {
                    AppController(lumen.repo, lumen.prefs, LivePreview(lumen, lumen.repo, lumen.prefs, lumen.http)).also {
                        it.exitToSalon = { moveTaskToBack(true) }
                    }
                }
                AppRoot(app)
            }
        }
    }

    /** Lumen déjà ouvert : Salon demande un autre titre ou une autre chaîne. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        SalonLink.offer(intent)
    }
}
