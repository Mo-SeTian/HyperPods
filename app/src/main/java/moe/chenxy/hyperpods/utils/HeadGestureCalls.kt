package moe.chenxy.hyperpods.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.telephony.SubscriptionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Runs in the scoped Bluetooth process, using its existing telephony permissions.
 * No root commands, dialer replacement, phone numbers or call records are needed.
 * The host's lock serializes receiver/timer callbacks with its socket lifecycle.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION") // Same accept/reject API as LibrePods; no default-dialer role takeover.
class HeadGestureCalls(private val context: Context, private val lock: Any, sharedStream: HeadTrackingStream? = null,
    private val onStateChanged: () -> Unit) {
    private val detector = HeadGestureDetector()
    private var manager: AACPManager? = null
    private val stream = sharedStream ?: HeadTrackingStream { start ->
        manager?.let { it.sendDataPacket(if (start) it.createAlternateStartHeadTrackingPacket()
            else it.createAlternateStopHeadTrackingPacket()) } == true
    }
    private var enabled = false
    private var supported = false
    private var worn = false
    private var registered = false
    private var tracking = false
    private var handled = false
    private var receivedPose = false
    private var timeout: Job? = null
    var state = "disabled"
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = synchronized(lock) {
            // This is a system-protected action. Read aggregate state afresh, not
            // per-SIM extras (or the phone number), and ignore duplicate broadcasts.
            if (intent?.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED && registered) refresh()
        }
    }

    private fun available(): Boolean = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
        (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.MODIFY_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) &&
        context.getSystemService(TelephonyManager::class.java) != null && context.getSystemService(TelecomManager::class.java) != null &&
        context.getSystemService(SubscriptionManager::class.java) != null

    private fun ringing(): Boolean = runCatching {
        val phone = context.getSystemService(TelephonyManager::class.java) ?: return@runCatching false
        if (phone.callState != TelephonyManager.CALL_STATE_RINGING) return@runCatching false
        // Aggregate state alone also includes some VoIP calls. Require an actual
        // ringing cellular subscription, checking both SIMs rather than the default.
        // Read only transient subscription IDs, never numbers, names or identifiers.
        context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty().any {
            phone.createForSubscriptionId(it.subscriptionId).callStateForSubscription == TelephonyManager.CALL_STATE_RINGING
        }
    }.getOrDefault(false)

    private fun state(value: String) {
        if (state == value) return
        state = value
        onStateChanged()
    }

    fun update(manager: AACPManager?, enabled: Boolean, supported: Boolean, worn: Boolean) {
        if (this.manager !== manager) {
            stop()
            handled = false
        }
        this.manager = manager
        this.enabled = enabled
        this.supported = supported
        this.worn = worn
        val unavailable = when {
            !enabled -> "disabled"
            !supported -> "unsupported"
            manager == null -> "disconnected"
            !available() -> "unavailable"
            else -> null
        }
        if (unavailable != null) {
            close()
            state(unavailable)
            return
        }
        if (!registered) {
            try {
                context.registerReceiver(receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), Context.RECEIVER_EXPORTED)
                registered = true
            } catch (_: Exception) {
                state("unavailable")
                return
            }
        }
        refresh()
    }

    private fun refresh() {
        if (!ringing()) {
            handled = false
            stop()
            state("waiting")
            return
        }
        if (!worn) {
            stop()
            state("not_worn")
            return
        }
        if (tracking || handled) return
        // LibrePods defaults to the alternate head-tracking packet pair.
        if (manager == null) return
        if (!stream.acquire(HeadTrackingStream.Owner.CALL)) {
            handled = true // Do not retry on every battery/wear packet.
            state("write_failed")
            return
        }
        detector.reset()
        tracking = true
        receivedPose = false
        timeout = CoroutineScope(Dispatchers.Default).launch {
            delay(5000)
            val noData = synchronized(lock) {
                ensureActive()
                if (!receivedPose) {
                    handled = true
                    stop()
                    state("no_data")
                    true
                } else false
            }
            if (noData) return@launch
            delay(55000)
            synchronized(lock) {
                ensureActive()
                handled = true
                stop()
                state("timeout")
            }
        }
        state("starting")
    }

    fun onPacket(packet: ByteArray) {
        if (!tracking) return
        if (!enabled || !supported || !worn) {
            refresh()
            return
        }
        val orientation = HeadGestureDetector.orientation(packet) ?: return
        if (!receivedPose) {
            receivedPose = true
            state("tracking")
        }
        val gesture = detector.process(orientation, SystemClock.elapsedRealtime()) ?: return
        handled = true // One decision at most for this ringing window.
        stop()
        if (!ringing()) {
            state("waiting") // Discard late gestures; do not act on an answered call.
            return
        }
        val result = runCatching {
            val telecom = context.getSystemService(TelecomManager::class.java) ?: return@runCatching false
            if (gesture == HeadGestureDetector.Gesture.NOD) {
                telecom.acceptRingingCall()
                true // API is void: request sent, not proof the call was answered.
            } else telecom.endCall()
        }.getOrDefault(false)
        state(if (!result) "call_failed" else if (gesture == HeadGestureDetector.Gesture.NOD) "answer_sent" else "reject_sent")
    }

    private fun stop() {
        timeout?.cancel()
        timeout = null
        if (tracking) stream.release(HeadTrackingStream.Owner.CALL)
        tracking = false
        detector.reset()
    }

    fun close() {
        stop()
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
        manager = null
        handled = false
    }
}
