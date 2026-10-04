package moe.chenxy.hyperpods.hook

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.ParcelUuid
import android.util.Log
import android.widget.Toast
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.method
import de.robv.android.xposed.XposedHelpers
import moe.chenxy.hyperpods.pods.L2CAPController
import moe.chenxy.hyperpods.utils.SystemApisUtils.setIconVisibility
import moe.chenxy.hyperpods.utils.miuiStrongToast.MiuiStrongToastUtil.cancelPodsNotificationByMiuiBt
import moe.chenxy.hyperpods.utils.miuiStrongToast.MiuiStrongToastUtil.showPodConnectingByMiuiBt

object HeadsetStateDispatcher : YukiBaseHooker() {
    private var isShowedToast = false
    private val airPodsUUIDs = hashSetOf(
        ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a"),
        ParcelUuid.fromString("2a72e02b-7b99-778f-014d-ad0b7221ec74")
    )
    private var waitingForUuids: BluetoothDevice? = null
    private var uuidReceiverRegistered = false

    @SuppressLint("PrivateApi")
    private fun getBooleanProp(prop: String, def: Boolean): Boolean {
        return XposedHelpers.callStaticMethod(Class.forName("android.os.SystemProperties"), "getBoolean", prop, def) as Boolean
    }

    external fun nativeGetHookResult(): Boolean

    @SuppressLint("MissingPermission")
    override fun onHook() {
        // Load Native hook
        System.loadLibrary("hyperpods_hook")

        "com.android.bluetooth.a2dp.A2dpService".toClass().apply {
            method {
                name = "handleConnectionStateChanged"
                paramCount = 3
            }.hook {
                after {
                    val currState = this.args[2] as Int
                    val fromState = this.args[1] as Int
                    val device = this.args[0] as BluetoothDevice?
                    val handler = XposedHelpers.getObjectField(this.instance, "mHandler") as Handler
                    if (device == null || currState == fromState) {
                        return@after
                    }
                    handler.post {
                        try {
                            Log.d(
                                "Art_Chen",
                                "A2DP Connection State: $currState, isAirPod ${isPods(device)}"
                            )
                            val context = this.instance as ContextWrapper
                            if (currState == BluetoothProfile.STATE_DISCONNECTING || currState == BluetoothProfile.STATE_DISCONNECTED) {
                                if (waitingForUuids == device) waitingForUuids = null
                                // UUIDs may be absent again during teardown.
                                L2CAPController.disconnectedPod(context, device)
                            }
                            if (!isPods(device)) {
                                // UUID discovery can finish after the A2DP event.
                                // Inspect its result once instead of dropping this connection.
                                if (currState == BluetoothProfile.STATE_CONNECTED && device.uuids == null) {
                                    if (!uuidReceiverRegistered) {
                                        context.registerReceiver(object : android.content.BroadcastReceiver() {
                                            override fun onReceive(receiverContext: Context?, intent: android.content.Intent?) {
                                                if (intent?.action != BluetoothDevice.ACTION_UUID) return
                                                val resolved = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                                                if (resolved != waitingForUuids) return
                                                try {
                                                    if (!isPods(resolved)) {
                                                        waitingForUuids = null
                                                        return
                                                    }
                                                    val state = XposedHelpers.callMethod(context, "getConnectionState", resolved) as Int
                                                    if (state == BluetoothProfile.STATE_CONNECTED && nativeGetHookResult()) {
                                                        waitingForUuids = null
                                                        showPodConnectingByMiuiBt(context, resolved)
                                                        L2CAPController.connectPod(context, resolved)
                                                    }
                                                } catch (error: Exception) {
                                                    Log.e("Art_Chen", "Unable to complete headset UUID discovery", error)
                                                }
                                            }
                                        }, android.content.IntentFilter(BluetoothDevice.ACTION_UUID), Context.RECEIVER_EXPORTED)
                                        uuidReceiverRegistered = true
                                    }
                                    waitingForUuids = device
                                    device.fetchUuidsWithSdp()
                                }
                                return@post
                            }

                            val statusBarManager =
                                context.getSystemService("statusbar") as StatusBarManager
                            if (currState == BluetoothHeadset.STATE_CONNECTED) {
                                // Show Wireless Pods icon
                                statusBarManager.setIconVisibility("wireless_headset", true)

                                val hookRes = nativeGetHookResult()
                                if (!hookRes) {
                                    Toast.makeText(
                                        appContext,
                                        "HyperPods: hook failed, this version of HyperPods will not work!",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    return@post
                                }
                                showPodConnectingByMiuiBt(context, device)
                                L2CAPController.connectPod(context, device)
                            } else if (currState == BluetoothHeadset.STATE_DISCONNECTING || currState == BluetoothHeadset.STATE_DISCONNECTED) {
                                statusBarManager.setIconVisibility("wireless_headset", false)
                            }
                        } catch (error: Exception) {
                            Log.e("Art_Chen", "Unable to handle headset connection state", error)
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun isPods(device: BluetoothDevice): Boolean {
        for (uuid in device.uuids ?: return false) {
            if (airPodsUUIDs.contains(uuid)) {
                return true
            }
        }
        return false
    }

}
