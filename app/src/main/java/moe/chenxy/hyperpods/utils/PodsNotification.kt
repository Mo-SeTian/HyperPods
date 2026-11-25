package moe.chenxy.hyperpods.utils

import android.app.Notification

internal const val ISLAND_QUICK_CONTROLS = "hyperpods.island.quick_controls"

// Only the connected-state card is persistent. Explicit cancellation on Bluetooth
// disconnect still works; transient connection/low-battery toasts do not use this.
internal fun Notification.Builder.buildConnectedPodsNotification(): Notification =
    setOngoing(true).build().apply {
        flags = flags or Notification.FLAG_NO_CLEAR
    }
