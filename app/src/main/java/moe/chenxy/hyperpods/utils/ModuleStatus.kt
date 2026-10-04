package moe.chenxy.hyperpods.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.utils.data.HyperPodsAction

/** Version comes from the code running in the host, not the newly installed APK. */
object ModuleStatus {
    val packages = listOf(HyperPodsBroadcasts.BLUETOOTH, HyperPodsBroadcasts.XIAOMI_BLUETOOTH,
        HyperPodsBroadcasts.SYSTEM_UI)
    data class Report(val packageName: String, val version: Int, val revision: String, val detail: String) {
        val matchesInstalled: Boolean
            get() = version == BuildConfig.VERSION_CODE && revision == BuildConfig.BUILD_REVISION
    }

    fun validRequest(request: String?): Boolean = request != null &&
        request.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))

    fun read(context: Context, receiver: BroadcastReceiver, intent: Intent, request: String): Report? {
        if (intent.action != HyperPodsAction.ACTION_MODULE_STATUS || !validRequest(request) ||
            intent.getStringExtra("request") != request) return null
        val pkg = intent.getStringExtra("host")?.takeIf { it in packages } ?: return null
        if (receiver.sentFromPackage != pkg || !HyperPodsBroadcasts.isTrusted(context, receiver, pkg)) return null
        val revision = intent.getStringExtra("revision")?.takeIf {
            it == "local" || it.matches(Regex("[0-9a-f]{8}"))
        } ?: return null
        val version = intent.getIntExtra("version", 0).takeIf { it in 1..1_000_000 } ?: return null
        val allowed = when (pkg) {
            HyperPodsBroadcasts.BLUETOOTH -> setOf("native_ok", "native_failed", "native_unknown")
            HyperPodsBroadcasts.SYSTEM_UI -> setOf("plugin_observed", "plugin_waiting")
            else -> setOf("receiver_ready", "receiver_waiting")
        }
        val detail = intent.getStringExtra("detail")?.takeIf { it in allowed } ?: return null
        return Report(pkg, version, revision, detail)
    }
}
