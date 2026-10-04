package moe.chenxy.hyperpods.utils.data

object HyperPodsAction {
    const val ACTION_PODS_UI_INIT = "chen.action.hyperpods.ui_init"
    const val ACTION_PODS_CONNECTED = "chen.action.hyperpods.pods_connected"
    const val ACTION_PODS_DISCONNECTED = "chen.action.hyperpods.pods_disconnected"
    const val ACTION_PODS_BATTERY_CHANGED = "chen.action.hyperpods.pods_battery_changed"

    const val ACTION_ANC_SELECT = "chen.action.hyperpods.anc_select"
    const val ACTION_PODS_RENAME = "chen.action.hyperpods.rename"
    const val ACTION_PODS_ANC_CHANGED = "chen.action.hyperpods.pods_anc_select"
    const val ACTION_EAR_DETECTION_STATUS_CHANGED = "chen.action.hyperpods.ear_detection_status_changed"
    @Deprecated("Use ACTION_PODS_SETTINGS_CHANGED instead")
    const val ACTION_EAR_DETECTION_SWITCH_CHANGED = "chen.action.hyperpods.ear_detection_switch_changed"
    const val ACTION_GET_PODS_MAC = "chen.action.hyperpods.get_pods_mac"
    const val ACTION_PODS_MAC_RECEIVED = "chen.action.hyperpods.got_pods_mac"
    const val ACTION_PODS_SETTINGS_CHANGED = "chen.action.hyperpods.preference_changed"
    const val ACTION_APP_SETTINGS_CHANGED = "chen.action.hyperpods.app_settings_changed"
    const val ACTION_PODS_SETTINGS_STATE = "chen.action.hyperpods.settings_state"
    const val ACTION_PODS_SETTING_RESULT = "chen.action.hyperpods.setting_result"
    const val ACTION_PODS_DIAGNOSTICS = "chen.action.hyperpods.diagnostics"
    const val ACTION_PODS_DIAGNOSTICS_RECORD = "chen.action.hyperpods.diagnostics_record"
    const val ACTION_PODS_DIAGNOSTICS_EVENT = "chen.action.hyperpods.diagnostics_event"
    const val ACTION_MODULE_STATUS_REQUEST = "chen.action.hyperpods.module_status_request"
    const val ACTION_MODULE_STATUS = "chen.action.hyperpods.module_status"
    const val ACTION_PODS_DIAGNOSTICS_REQUEST = "chen.action.hyperpods.diagnostics_request"
    const val ACTION_PODS_STATUS_RETRY = "chen.action.hyperpods.status_retry"
    const val ACTION_PODS_STATUS_CHANGED = "chen.action.hyperpods.pods_status_changed"
}
