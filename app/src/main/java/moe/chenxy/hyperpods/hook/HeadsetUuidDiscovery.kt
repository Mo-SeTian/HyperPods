package moe.chenxy.hyperpods.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/** Register once on the process context, but resolve against the current A2DP service. */
@SuppressLint("MissingPermission")
internal class HeadsetUuidDiscovery(
    private val isPods: (BluetoothDevice) -> Boolean,
    private val isConnected: (Context, BluetoothDevice) -> Boolean,
    private val onReady: (Context, BluetoothDevice) -> Unit,
) {
    private data class Pending(val service: Context, val device: BluetoothDevice)
    private var pending: Pending? = null
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?): Unit = synchronized(this@HeadsetUuidDiscovery) {
            if (intent?.action != BluetoothDevice.ACTION_UUID) return
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            val request = pending?.takeIf { it.device == device } ?: return
            pending = null
            try {
                if (isPods(device) && isConnected(request.service, device)) onReady(request.service, device)
            } catch (error: Exception) {
                Log.e("Art_Chen", "Unable to complete headset UUID discovery", error)
            }
        }
    }

    @Synchronized
    fun discover(service: Context, device: BluetoothDevice) {
        if (!registered) {
            service.applicationContext.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_UUID), Context.RECEIVER_EXPORTED)
            registered = true
        }
        pending = Pending(service, device)
        device.fetchUuidsWithSdp()
    }

    @Synchronized
    fun disconnected(service: Context, device: BluetoothDevice) {
        if (pending?.let { it.service === service && it.device == device } == true) pending = null
    }
}
