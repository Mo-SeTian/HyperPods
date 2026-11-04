package moe.chenxy.hyperpods.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** The component override persists across restarts without changing Bluetooth policies. */
object LauncherIcon {
    fun component(context: Context) = ComponentName(context.packageName, "${context.packageName}.Launcher")

    fun isVisible(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(component(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setVisible(context: Context, visible: Boolean) {
        context.packageManager.setComponentEnabledSetting(component(context),
            if (visible) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
    }

    fun isLauncherIntent(context: Context, intent: Intent?): Boolean = intent?.component == component(context)
}
