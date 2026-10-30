package moe.chenxy.hyperpods.utils.data

import moe.chenxy.hyperpods.BuildConfig

object HyperPodsAction {
    private const val PREFIX = BuildConfig.APPLICATION_ID + ".action."
    const val ACTION_SHOW_PODS_UI = PREFIX + "show_airpods_ui"
    const val ACTION_STRONG_TOAST = PREFIX + "sendstrongtoast"
    const val ACTION_UPDATE_NOTIFICATION = PREFIX + "updatepodsnotification"
    const val ACTION_CANCEL_NOTIFICATION = PREFIX + "cancelpodsnotification"
    const val ACTION_PODS_CONNECTING = PREFIX + "podconnecting"
    const val ACTION_PODS_UI_INIT = PREFIX + "ui_init"
    const val ACTION_PODS_CONNECTED = PREFIX + "pods_connected"
    const val ACTION_PODS_DISCONNECTED = PREFIX + "pods_disconnected"
    const val ACTION_PODS_BATTERY_CHANGED = PREFIX + "pods_battery_changed"

    const val ACTION_ANC_SELECT = PREFIX + "anc_select"
    const val ACTION_PODS_RENAME = PREFIX + "rename"
    const val ACTION_PODS_ANC_CHANGED = PREFIX + "pods_anc_select"
    const val ACTION_EAR_DETECTION_STATUS_CHANGED = PREFIX + "ear_detection_status_changed"
    @Deprecated("Use ACTION_PODS_SETTINGS_CHANGED instead")
    const val ACTION_EAR_DETECTION_SWITCH_CHANGED = PREFIX + "ear_detection_switch_changed"
    const val ACTION_GET_PODS_MAC = PREFIX + "get_pods_mac"
    const val ACTION_PODS_MAC_RECEIVED = PREFIX + "got_pods_mac"
    const val ACTION_PODS_SETTINGS_CHANGED = PREFIX + "preference_changed"
    const val ACTION_APP_SETTINGS_CHANGED = PREFIX + "app_settings_changed"
    const val ACTION_PODS_SETTINGS_STATE = PREFIX + "settings_state"
    const val ACTION_PODS_SETTING_RESULT = PREFIX + "setting_result"
    const val ACTION_PODS_DIAGNOSTICS = PREFIX + "diagnostics"
    const val ACTION_PODS_DIAGNOSTICS_RECORD = PREFIX + "diagnostics_record"
    const val ACTION_PODS_DIAGNOSTICS_EVENT = PREFIX + "diagnostics_event"
    const val ACTION_MODULE_STATUS_REQUEST = PREFIX + "module_status_request"
    const val ACTION_MODULE_STATUS = PREFIX + "module_status"
    const val ACTION_PODS_DIAGNOSTICS_REQUEST = PREFIX + "diagnostics_request"
    const val ACTION_PODS_STATUS_RETRY = PREFIX + "status_retry"
    const val ACTION_HEAD_TRACKING_COMMAND = PREFIX + "head_tracking_command"
    const val ACTION_HEAD_TRACKING_SAMPLE = PREFIX + "head_tracking_sample"
    const val ACTION_PODS_STATUS_CHANGED = PREFIX + "pods_status_changed"
}
