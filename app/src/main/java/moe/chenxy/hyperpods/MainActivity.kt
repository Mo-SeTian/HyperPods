package moe.chenxy.hyperpods

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import moe.chenxy.hyperpods.ui.App
import moe.chenxy.hyperpods.utils.HyperPodsBroadcasts
import moe.chenxy.hyperpods.utils.LauncherIcon
import moe.chenxy.hyperpods.utils.data.HyperPodsAction

class MainActivity : ComponentActivity() {
    var keepOpenOnDisconnect by mutableStateOf(false)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepOpenOnDisconnect = savedInstanceState?.getBoolean("desktop_entry") == true ||
            LauncherIcon.isLauncherIntent(this, intent)

        // Fix Dialog + TextField has weird bottom ime padding
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        setContent {
            val darkMode = isSystemInDarkTheme()

            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                )

                window.isNavigationBarContrastEnforced = false  // Xiaomi moment, this code must be here

                onDispose {}
            }

            App()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (LauncherIcon.isLauncherIntent(this, intent)) {
            // singleTask can reuse a page originally opened from a notification.
            keepOpenOnDisconnect = true
            HyperPodsBroadcasts.send(this, Intent(HyperPodsAction.ACTION_PODS_UI_INIT), HyperPodsBroadcasts.BLUETOOTH)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("desktop_entry", keepOpenOnDisconnect)
        super.onSaveInstanceState(outState)
    }

    fun onPodsDisconnected(initializationFailed: Boolean) {
        if (!keepOpenOnDisconnect && !initializationFailed) finish()
    }
}
