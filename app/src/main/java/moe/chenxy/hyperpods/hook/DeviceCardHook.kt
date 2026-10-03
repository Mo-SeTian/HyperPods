package moe.chenxy.hyperpods.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.type.android.ContextClass
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.utils.HyperPodsBroadcasts
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import java.util.UUID

object DeviceCardHook : YukiBaseHooker() {
    override fun onHook() {
        val handler = Handler(Looper.getMainLooper())
        val pendingClicks = mutableMapOf<String, (String?) -> Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                context ?: return
                if (!HyperPodsBroadcasts.isTrusted(context, this, HyperPodsBroadcasts.BLUETOOTH)) return
                val request = intent?.getStringExtra("request_id") ?: return
                pendingClicks.remove(request)?.invoke(intent.getStringExtra("mac"))
            }
        }
        var registered = false
        var panelController: Any? = null
        "miui.systemui.controlcenter.panel.main.MainPanelController".toClass().method {
            name = "onCreate"
        }.hook {
            after { panelController = this.instance }
        }

        "miui.systemui.devicecenter.devices.DeviceInfoWrapper".toClass().method {
            name = "performClicked"
            param(ContextClass)
        }.hook {
            before {
                val context = this.args[0] as Context
                val info = XposedHelpers.callMethod(this.instance, "getDeviceInfo")
                if (XposedHelpers.callMethod(info, "getDeviceType") != "third_headset") return@before
                val id = XposedHelpers.callMethod(info, "getId") as String
                if (!registered) {
                    context.applicationContext.registerReceiver(receiver,
                        IntentFilter(HyperPodsAction.ACTION_PODS_MAC_RECEIVED), null, handler,
                        Context.RECEIVER_EXPORTED)
                    registered = true
                }
                val originalMember = this.member
                val originalInstance = this.instance
                val request = UUID.randomUUID().toString()
                lateinit var timeout: Runnable
                pendingClicks[request] = { mac ->
                    handler.removeCallbacks(timeout)
                    try {
                        if (mac == id) {
                            context.startActivity(Intent("chen.action.hyperpods.show_airpods_ui")
                                .setPackage(BuildConfig.APPLICATION_ID).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            panelController?.let { XposedHelpers.callMethod(it, "exitOrHide") }
                        } else {
                            XposedBridge.invokeOriginalMethod(originalMember, originalInstance, arrayOf(context))
                        }
                    } catch (error: Exception) {
                        Log.e("Art_Chen", "Unable to open headset controls", error)
                        if (mac == id) runCatching {
                            XposedBridge.invokeOriginalMethod(originalMember, originalInstance, arrayOf(context))
                        }.onFailure {
                            Log.e("Art_Chen", "Unable to restore the original device-card click", it)
                        }
                    }
                }
                timeout = Runnable { pendingClicks.remove(request)?.invoke(null) }
                handler.postDelayed(timeout, 500)
                HyperPodsBroadcasts.send(context, Intent(HyperPodsAction.ACTION_GET_PODS_MAC)
                    .putExtra("request_id", request), HyperPodsBroadcasts.BLUETOOTH)
                this.result = null
            }
        }
    }
}
