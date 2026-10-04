package moe.chenxy.hyperpods.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import moe.chenxy.hyperpods.utils.data.HyperPodsAction

/** Fixed-field diagnostics survive closing the settings activity. */
class DiagnosticsHistory : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(HyperPodsAction.ACTION_PODS_DIAGNOSTICS_RECORD, HyperPodsAction.ACTION_PODS_DIAGNOSTICS_EVENT) ||
            !HyperPodsBroadcasts.isTrusted(context, this, HyperPodsBroadcasts.BLUETOOTH)) return
        val snapshot = intent.getBundleExtra("diagnostics") ?: return
        if (intent.action == HyperPodsAction.ACTION_PODS_DIAGNOSTICS_EVENT)
            DiagnosticEvents.fromBundle(snapshot)?.let { append(preferences(context), it) }
        else save(preferences(context), snapshot)
    }

    companion object {
        val failureCodes = setOf("socket_create_failed", "socket_connect_failed", "write_failed",
            "status_timeout", "status_session_ended", "setting_write_failed", "setting_timeout")
        private val stages = setOf("NONE", "HANDSHAKE", "FEATURES", "STATUS")

        fun preferences(context: Context): SharedPreferences =
            context.getSharedPreferences("diagnostics_history", Context.MODE_PRIVATE)

        // Explicit whitelist: never persist a Bundle wholesale or accept arbitrary failure text.
        fun save(preferences: SharedPreferences, snapshot: Bundle) {
            val failure = snapshot.getString("failure")?.takeIf { it in failureCodes } ?: return
            preferences.edit()
                .putString("failure", failure)
                .putString("stage", snapshot.getString("stage").takeIf { it in stages } ?: "NONE")
                .putLong("recorded_at", snapshot.getLong("recorded_at").coerceAtLeast(0))
                .putLong("last_packet_at", snapshot.getLong("last_packet_at").coerceAtLeast(0))
                .putInt("received", snapshot.getInt("received").coerceAtLeast(0))
                .putInt("retries", snapshot.getInt("retries").coerceAtLeast(0))
                .putString("setting_key", snapshot.getString("setting_key")
                    ?.takeIf { it in PodsSettings.identifiers || it == PodsSettings.RENAME }.orEmpty())
                .apply()
        }

        @Synchronized
        fun append(preferences: SharedPreferences, event: DiagnosticEvents.Event) {
            val safe = DiagnosticEvents.create(event.at, event.kind, event.detail, event.setting) ?: return
            preferences.edit().putString("events", DiagnosticEvents.encode(events(preferences) + safe)).apply()
        }

        fun events(preferences: SharedPreferences): List<DiagnosticEvents.Event> =
            DiagnosticEvents.decode(preferences.getString("events", null))

        fun read(preferences: SharedPreferences): Bundle? {
            val failure = preferences.getString("failure", "")?.takeIf { it in failureCodes } ?: return null
            return Bundle().apply {
                putString("failure", failure)
                putString("stage", preferences.getString("stage", "NONE"))
                putLong("recorded_at", preferences.getLong("recorded_at", 0))
                putLong("last_packet_at", preferences.getLong("last_packet_at", 0))
                putInt("received", preferences.getInt("received", 0))
                putInt("retries", preferences.getInt("retries", 0))
                putString("setting_key", preferences.getString("setting_key", ""))
            }
        }
    }
}
