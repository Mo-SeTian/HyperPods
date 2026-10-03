package moe.chenxy.hyperpods.utils

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import moe.chenxy.hyperpods.BuildConfig

/** Cross-UID module messages disclose their real sender and target one package. */
object HyperPodsBroadcasts {
    const val BLUETOOTH = "com.android.bluetooth"
    const val XIAOMI_BLUETOOTH = "com.xiaomi.bluetooth"
    const val SYSTEM_UI = "com.android.systemui"

    fun send(context: Context?, intent: Intent, target: String = BuildConfig.APPLICATION_ID) {
        context ?: return
        intent.setPackage(target)
        context.sendBroadcast(intent, null,
            BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
    }

    fun isTrusted(context: Context, receiver: BroadcastReceiver, vararg packages: String): Boolean {
        val uid = receiver.sentFromUid
        if (uid < 0) return false
        return packages.any {
            try {
                context.packageManager.getPackageUid(it, 0) == uid
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }
}
