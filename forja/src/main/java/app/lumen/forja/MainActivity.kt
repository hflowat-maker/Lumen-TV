package app.lumen.forja

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import app.lumen.forja.ui.AppController
import app.lumen.forja.ui.AppRoot
import app.lumen.forja.ui.theme.ForjaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ForjaTheme {
                val app = remember { AppController(this@MainActivity) }
                AppRoot(app)
            }
        }
    }
}
