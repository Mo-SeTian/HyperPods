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
    internal var homeRequested by mutableStateOf(false)
        private set

    internal fun consumeHomeRequest() {
        homeRequested = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        homeRequested = savedInstanceState?.getBoolean("home_requested")
            ?: (intent.action == HyperPodsAction.ACTION_SHOW_PODS_HOME)
        keepOpenOnDisconnect = savedInstanceState?.getBoolean("desktop_entry") == true ||
            LauncherIcon.isLauncherIntent(this, intent) || homeRequested

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
        val fromLauncher = LauncherIcon.isLauncherIntent(this, intent)
        val fromQuickControls = intent.action == HyperPodsAction.ACTION_SHOW_PODS_HOME
        if (fromLauncher || fromQuickControls) {
            // An explicit launch can reuse a page originally opened from the device card.
            keepOpenOnDisconnect = true
        }
        if (fromQuickControls) homeRequested = true
        if (fromLauncher || fromQuickControls || intent.action == HyperPodsAction.ACTION_SHOW_PODS_UI) {
            HyperPodsBroadcasts.send(this, Intent(HyperPodsAction.ACTION_PODS_UI_INIT), HyperPodsBroadcasts.BLUETOOTH)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("desktop_entry", keepOpenOnDisconnect)
        outState.putBoolean("home_requested", homeRequested)
        super.onSaveInstanceState(outState)
    }

    fun onPodsDisconnected(initializationFailed: Boolean) {
        if (!keepOpenOnDisconnect && !initializationFailed) finish()
    }
}
