package moe.chenxy.hyperpods.utils

import android.app.Notification

// Only the connected-state card is persistent. Explicit cancellation on Bluetooth
// disconnect still works; transient connection/low-battery toasts do not use this.
internal fun Notification.Builder.buildConnectedPodsNotification(): Notification =
    setOngoing(true).build().apply {
        flags = flags or Notification.FLAG_NO_CLEAR
    }
