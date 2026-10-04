package moe.chenxy.hyperpods.utils

import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/** A bounded, fixed vocabulary. Never store a name, setting value or raw packet. */
object DiagnosticEvents {
    const val LIMIT = 40
    data class Event(val at: Long, val kind: String, val detail: String, val setting: String = "")

    fun create(at: Long, kind: String?, detail: String?, setting: String? = ""): Event? {
        if (at <= 0 || kind == null || detail == null) return null
        val valid = when (kind) {
            "connection" -> detail in setOf("connecting", "connected", "ready", "failed", "disconnected")
            "retry" -> detail in setOf("1", "2", "3")
            "sync" -> detail in setOf("HANDSHAKE", "FEATURES", "STATUS", "request")
            "role" -> detail in setOf("left", "right")
            "wear" -> detail.matches(Regex("[0-3],[0-3]"))
            "setting" -> detail in setOf("sent", "reported", "confirmed", "write_failed", "timeout", "invalid") &&
                (setting in PodsSettings.identifiers || setting == PodsSettings.RENAME)
            "failure" -> detail in DiagnosticsHistory.failureCodes
            else -> false
        }
        return if (valid) Event(at, kind, detail, if (kind == "setting") setting.orEmpty() else "") else null
    }

    fun fromBundle(bundle: Bundle): Event? = create(bundle.getLong("at"), bundle.getString("kind"),
        bundle.getString("detail"), bundle.getString("setting"))

    fun decode(encoded: String?): List<Event> {
        if (encoded == null || encoded.length > 16_384) return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            (maxOf(0, array.length() - LIMIT) until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                create(item.optLong("at"), item.optString("kind"), item.optString("detail"), item.optString("setting"))
            }
        }.getOrDefault(emptyList())
    }

    fun encode(events: List<Event>): String = JSONArray().apply {
        events.takeLast(LIMIT).forEach { event ->
            create(event.at, event.kind, event.detail, event.setting)?.let {
                put(JSONObject().put("at", it.at).put("kind", it.kind).put("detail", it.detail).put("setting", it.setting))
            }
        }
    }.toString()
}
