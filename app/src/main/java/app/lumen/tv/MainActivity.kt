package app.lumen.tv

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
        setContent {
            LumenTheme {
                val app = remember { AppController(lumen.repo, lumen.prefs, LivePreview(lumen, lumen.repo, lumen.prefs, lumen.http)) }
                AppRoot(app)
            }
        }
    }
}
