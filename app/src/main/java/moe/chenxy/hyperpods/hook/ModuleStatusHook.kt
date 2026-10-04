package moe.chenxy.hyperpods.hook

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.utils.HyperPodsBroadcasts
import moe.chenxy.hyperpods.utils.ModuleStatus
import moe.chenxy.hyperpods.utils.data.HyperPodsAction

/** Independent of a headset session: About can check even when no headset is connected. */
class ModuleStatusHook(private val host: String) : YukiBaseHooker() {
    private var registered = false

    override fun onHook() {
        XposedHelpers.findAndHookMethod(Application::class.java, "attach", Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    register(param.thisObject as Application)
                }
            })
        appContext?.let(::register)
    }

    @Synchronized
    private fun register(context: Context) {
        if (registered || context.packageName != host || Application.getProcessName() != host) return
        runCatching {
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != HyperPodsAction.ACTION_MODULE_STATUS_REQUEST ||
                        !HyperPodsBroadcasts.isTrusted(context, this, BuildConfig.APPLICATION_ID)) return
                    val request = intent.getStringExtra("request")
                    if (!ModuleStatus.validRequest(request)) return
                    val detail = when (host) {
                        HyperPodsBroadcasts.BLUETOOTH -> runCatching {
                            if (HeadsetStateDispatcher.nativeGetHookResult()) "native_ok" else "native_failed"
                        }.getOrDefault("native_unknown")
                        HyperPodsBroadcasts.SYSTEM_UI -> if (SystemUIPluginHook.pluginObserved) "plugin_observed" else "plugin_waiting"
                        else -> if (MiBluetoothToastHook.notificationReceiverReady) "receiver_ready" else "receiver_waiting"
                    }
                    HyperPodsBroadcasts.send(context, Intent(HyperPodsAction.ACTION_MODULE_STATUS).apply {
                        putExtra("request", request)
                        putExtra("host", host)
                        putExtra("version", BuildConfig.VERSION_CODE)
                        putExtra("revision", BuildConfig.BUILD_REVISION)
                        putExtra("detail", detail)
                    })
                }
            }, IntentFilter(HyperPodsAction.ACTION_MODULE_STATUS_REQUEST), Context.RECEIVER_EXPORTED)
            registered = true
        }.onFailure { Log.w("Art_Chen", "Unable to register module self-check receiver") }
    }
}
