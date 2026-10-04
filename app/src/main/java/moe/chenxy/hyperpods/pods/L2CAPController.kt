package moe.chenxy.hyperpods.pods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.MediaRouter2.ScanToken
import android.media.RouteDiscoveryPreference
import android.os.ParcelUuid
import android.os.Bundle
import android.util.Log
import de.robv.android.xposed.XposedHelpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.utils.AACPManager
import moe.chenxy.hyperpods.utils.AppPreferences
import moe.chenxy.hyperpods.utils.AirPodsInstance
import moe.chenxy.hyperpods.utils.MediaControl
import moe.chenxy.hyperpods.utils.PodsSettings
import moe.chenxy.hyperpods.utils.AirPodsModels
import moe.chenxy.hyperpods.utils.ConversationVolume
import moe.chenxy.hyperpods.utils.DiagnosticsHistory
import moe.chenxy.hyperpods.utils.LowBatterySettings
import moe.chenxy.hyperpods.utils.HyperPodsBroadcasts
import moe.chenxy.hyperpods.utils.SystemApisUtils
import moe.chenxy.hyperpods.utils.miuiStrongToast.MiuiStrongToastUtil
import moe.chenxy.hyperpods.utils.miuiStrongToast.MiuiStrongToastUtil.cancelPodsNotificationByMiuiBt
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.EarDetectionParams
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey
import moe.chenxy.hyperpods.utils.data.PodBatteryParams
import java.util.concurrent.Executor
import kotlin.collections.get
import kotlin.experimental.or

@SuppressLint("MissingPermission", "StaticFieldLeak")
object L2CAPController {
    private const val TAG = "HyperPods-L2CAPController"

    // Basic Object
    private var socket: BluetoothSocket? = null
    private var mContext: Context? = null
    lateinit var mDevice: BluetoothDevice
    private val audioManager: AudioManager?
        get() = mContext?.getSystemService(AudioManager::class.java)
    private var appSettingsRevision = 0L

    private var aacpManager: AACPManager? = null
    private var connectionJob: Job? = null
    private var sessionId = 0L
    private var connectionState = "disconnected"
    private var initialStatusRetries = 0
    private var lastStatusFailure = ""
    private var lastRecordedFailure = ""
    private var lastSettingKey = ""
    private var lastSettingStatus = ""
    private var lastDiagnostics: Bundle? = null
    private var lastSettingsState: Triple<Map<String, Int>, Set<String>, Set<String>>? = null

//    private lateinit var mAirPodsInstance: AirPodsInstance

    private var scanToken: ScanToken? = null
    @Volatile var routes: List<MediaRoute2Info> = listOf()

    private lateinit var mediaRouter: MediaRouter2

    // Status
    private var mShowedConnectedToast = false
    private var lastCaseConnected = false
    @Volatile private var batteryStateValid = false
    private var earDetectionStateValid = false
    private var speakerSwitchJob: Job? = null
    private var switchedToSpeaker = false
    private var pendingAudioRoute: MediaRoute2Info? = null
    private var routeRetryJob: Job? = null
    private var routeTransferTimeout: Job? = null
    private var routeRetryCount = 0
    private var pausedAudio = false
    private var lastTempBatt = 0
    lateinit var currentEarDetectionParams: EarDetectionParams
    lateinit var currentBatteryParams: BatteryParams
    private var currentAnc: Int = 1
    var currentPodsInfo: AACPManager.Companion.AirPodsInformation? = null
    private data class PendingSetting(val value: Int, val timeout: Job)
    private val pendingSettings = mutableMapOf<String, PendingSetting>()
    private val failedSettings = mutableMapOf<String, Int>()
    private var pendingOffSelection = false
    private var pendingRename: String? = null
    private var renameTimeout: Job? = null
    private var conversationTimeout: Job? = null
    private var conversationRestoreJob: Job? = null
    private var headphoneRouteId: String? = null
    private val conversationVolume = ConversationVolume(
        { audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0 },
        { audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) },
        { audioManager?.isMusicActive == true },
    )

    // Function toggle
    private var earDetection = true
    private var autoSwitchToSpeaker = true
    private var conversationPhoneVolume = true
    private var lowBatterySettings = LowBatterySettings()

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            val context = p0 ?: return
            val intent = p1 ?: return
            val sender = if (intent.action == HyperPodsAction.ACTION_GET_PODS_MAC)
                HyperPodsBroadcasts.SYSTEM_UI else BuildConfig.APPLICATION_ID
            if (!HyperPodsBroadcasts.isTrusted(context, this, sender)) return
            if (intent.action == HyperPodsAction.ACTION_GET_PODS_MAC) {
                if (mContext == null || aacpManager == null) return
                Intent(HyperPodsAction.ACTION_PODS_MAC_RECEIVED).apply {
                    this.putExtra("mac", mDevice.address)
                    this.putExtra("request_id", intent.getStringExtra("request_id"))
                    this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    HyperPodsBroadcasts.send(context, this, HyperPodsBroadcasts.SYSTEM_UI)
                    return
                }
            }
            handleUIEvent(intent)
        }
    }

    private fun changeUIAncStatus(status: Int) {
        if (status !in 1..4) {
            // ignore invalid param
            return
        }
        Intent(HyperPodsAction.ACTION_PODS_ANC_CHANGED).apply {
            this.putExtra("status", status)
            this.`package` = BuildConfig.APPLICATION_ID
            this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            HyperPodsBroadcasts.send(mContext, this)
        }
    }

    private fun changeUIInEarStatus(status: EarDetectionParams) {
        Intent(HyperPodsAction.ACTION_EAR_DETECTION_STATUS_CHANGED).apply {
            this.putExtra("status", status)
            this.`package` = BuildConfig.APPLICATION_ID
            this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            HyperPodsBroadcasts.send(mContext, this)
        }
    }

    private fun changeUIBatteryStatus(status: BatteryParams) {
        Intent(HyperPodsAction.ACTION_PODS_BATTERY_CHANGED).apply {
            this.putExtra("status", status)
            this.`package` = BuildConfig.APPLICATION_ID
            this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            HyperPodsBroadcasts.send(mContext, this)
        }
    }

    @Synchronized
    fun handleUIEvent(intent: Intent) {
        if (mContext == null) return
        when (intent.action) {
            HyperPodsAction.ACTION_APP_SETTINGS_CHANGED -> {
                AppPreferences.decode(intent.getStringExtra(AppPreferences.EXTRA))?.let {
                    appSettingsRevision++
                    updateFeatureToggle(it)
                }
            }
            HyperPodsAction.ACTION_PODS_DIAGNOSTICS_REQUEST -> sendDiagnostics(force = true)
            HyperPodsAction.ACTION_PODS_UI_INIT -> {
                Log.i(TAG, "UI Init")

                if (earDetectionStateValid && ::currentEarDetectionParams.isInitialized)
                    changeUIInEarStatus(currentEarDetectionParams)

                if (batteryStateValid && ::currentBatteryParams.isInitialized)
                    changeUIBatteryStatus(currentBatteryParams)

                changeUIAncStatus(currentAnc)
                sendSettingsState(force = true)
                // Reopening the page also recovers settings missed during initial connection.
                aacpManager?.requestInitialStatus()
                // A2DP may still be connected after control-channel exhaustion.
                // Keep the detail page and manual-recovery action accessible.
                sendConnectedStatus()
                sendDiagnostics(force = true)
            }
            HyperPodsAction.ACTION_PODS_STATUS_RETRY -> {
                if (aacpManager != null) {
                    initialStatusRetries++
                    lastStatusFailure = if (aacpManager?.requestInitialStatus() == true) "" else "write_failed"
                } else if (connectionJob?.isActive != true) {
                    connectPod(mContext!!, mDevice)
                }
                sendDiagnostics()
            }
            HyperPodsAction.ACTION_ANC_SELECT -> {
                val status = intent.getIntExtra("status", 0)
                setANCMode(status)
            }
            HyperPodsAction.ACTION_PODS_RENAME -> {
                val name = intent.getStringExtra("name")?.trim() ?: return
                if (name.isEmpty() || '\u0000' in name || name.toByteArray().size > 255 || pendingRename != null) return
                findHeadphoneRoute(routes) // Capture stable identity before the display name changes.
                if (aacpManager?.sendRename(name) != true) {
                    sendSettingResult(PodsSettings.RENAME, false, status = "write_failed")
                    return
                }
                pendingRename = name
                sendSettingResult(PodsSettings.RENAME, false, pending = true)
                val generation = sessionId
                renameTimeout = CoroutineScope(Dispatchers.Default).launch {
                    delay(6000)
                    synchronized(this@L2CAPController) {
                        ensureActive()
                        if (sessionId == generation && pendingRename == name) {
                            pendingRename = null
                            sendSettingResult(PodsSettings.RENAME, false)
                        }
                    }
                }
                aacpManager?.sendNotificationRequest()
            }

            HyperPodsAction.ACTION_EAR_DETECTION_SWITCH_CHANGED -> {
                updateEarDetectionSettings(
                    intent.getBooleanExtra("ear_detection", true),
                    intent.getBooleanExtra("disconnect_audio", true)
                )
            }
            HyperPodsAction.ACTION_PODS_SETTINGS_CHANGED -> {
                intent.getStringExtra("key")?.let {
                    if (it == HyperPodsPrefsKey.EAR_DETECTION || it == HyperPodsPrefsKey.EAR_DETECTION_SWITCH_SPEAKER) {
                        if (intent.hasExtra("ear_detection") && intent.hasExtra("switch_speaker")) {
                            updateEarDetectionSettings(intent.getBooleanExtra("ear_detection", true),
                                intent.getBooleanExtra("switch_speaker", true))
                        }
                    } else if (it == HyperPodsPrefsKey.CONVERSATION_PHONE_VOLUME && intent.hasExtra("enabled")) {
                        updateConversationPhoneVolume(intent.getBooleanExtra("enabled", true))
                    } else if (it == HyperPodsPrefsKey.LOW_BATTERY_EARS) {
                        intent.getParcelableExtra("lowBatterySettings", LowBatterySettings::class.java)?.takeIf { it.valid }?.let { settings ->
                            lowBatterySettings = settings
                            if (::currentBatteryParams.isInitialized)
                                MiuiStrongToastUtil.showPodsNotificationByMiuiBt(mContext, currentBatteryParams, mDevice, lowBatterySettings)
                        }
                    } else if (intent.hasExtra("value")) {
                        sendSetting(it, intent.getIntExtra("value", -1))
                    }
                }
            }
        }
    }

    private fun sendConnectedStatus() {
        Intent(HyperPodsAction.ACTION_PODS_CONNECTED).apply {
            putExtra("device_info", currentPodsInfo)
            putExtra("device_name", mDevice.alias ?: mDevice.name)
            HyperPodsBroadcasts.send(mContext, this)
        }
        sendSettingsState()
    }

    // Listening toggles also include optimistic local writes; all other values require a report.
    private fun confirmedSettings(): Map<String, Int> = aacpManager?.controlCommandStatusList
        ?.mapNotNull { status -> PodsSettings.keyFor(status.identifier.value)?.let { key ->
            status.value.firstOrNull()?.let { it.toInt() and 0xff }
                ?.takeIf { PodsSettings.knownValue(key, it) }?.let { key to it }
        } }?.toMap().orEmpty()

    private fun sendSettingsState(force: Boolean = false) {
        val values = confirmedSettings()
        val pending = (pendingSettings.keys + listOfNotNull(pendingRename?.let { PodsSettings.RENAME },
            PodsSettings.NOISE_MODE.takeIf { pendingOffSelection })).toSet()
        val unconfirmed = PodsSettings.identifiers.filterValues { aacpManager?.isAwaitingReport(it) == true }.keys.toSet()
        val state = Triple(values, pending, unconfirmed)
        if (!force && lastSettingsState == state) {
            sendDiagnostics()
            return
        }
        lastSettingsState = state
        Intent(HyperPodsAction.ACTION_PODS_SETTINGS_STATE).apply {
            putExtra("settings", Bundle().apply { values.forEach { (key, value) -> putInt(key, value) } })
            putStringArrayListExtra("pending", ArrayList(pending))
            putStringArrayListExtra("unconfirmed", ArrayList(unconfirmed))
            HyperPodsBroadcasts.send(mContext, this)
        }
        sendDiagnostics()
    }

    private fun diagnosticsSnapshot(): Bundle = Bundle().apply {
        putString("connection", connectionState)
        putString("stage", aacpManager?.initializationStage?.name ?: "NONE")
        putInt("received", aacpManager?.receivedPacketCount ?: 0)
        putInt("retries", initialStatusRetries)
        putBoolean("battery", batteryStateValid && !AirPodsNotifications.BatteryNotification.needsRefresh)
        putBoolean("wear", earDetectionStateValid)
        putBoolean("information", currentPodsInfo != null)
        putBoolean("ready", connectionState == "connected" && initialStatusReady())
        putInt("missing_settings", missingSettings().size)
        putString("failure", lastStatusFailure)
        putLong("last_packet_at", aacpManager?.lastPacketAt ?: 0)
        putLong("battery_at", aacpManager?.batteryReceivedAt ?: 0)
        putLong("wear_at", aacpManager?.wearReceivedAt ?: 0)
        putLong("information_at", aacpManager?.informationReceivedAt ?: 0)
        putLong("settings_at", aacpManager?.settingsReceivedAt ?: 0)
        putString("setting_key", lastSettingKey)
        putString("setting_status", lastSettingStatus)
    }

    private fun sendDiagnostics(force: Boolean = false) {
        val snapshot = diagnosticsSnapshot()
        val previous = lastDiagnostics
        @Suppress("DEPRECATION")
        val unchanged = previous != null && snapshot.keySet().orEmpty().all { snapshot.get(it) == previous.get(it) }
        if (!force && unchanged) return
        lastDiagnostics = snapshot
        Intent(HyperPodsAction.ACTION_PODS_DIAGNOSTICS).apply {
            putExtra("diagnostics", snapshot)
            HyperPodsBroadcasts.send(mContext, this)
        }
        if (lastStatusFailure.isEmpty()) lastRecordedFailure = ""
        else if (lastStatusFailure != lastRecordedFailure) {
            lastRecordedFailure = lastStatusFailure
            recordFailure(snapshot, lastStatusFailure)
        }
    }

    private fun recordFailure(snapshot: Bundle, failure: String) {
        if (failure !in DiagnosticsHistory.failureCodes) return
        val record = Bundle(snapshot).apply {
            putString("failure", failure)
            putLong("recorded_at", System.currentTimeMillis())
        }
        Intent(HyperPodsAction.ACTION_PODS_DIAGNOSTICS_RECORD).apply {
            setClassName(BuildConfig.APPLICATION_ID, DiagnosticsHistory::class.java.name)
            putExtra("diagnostics", record)
            HyperPodsBroadcasts.send(mContext, this)
        }
    }

    private fun sendSettingResult(key: String, success: Boolean, pending: Boolean = false,
        status: String = if (pending) "sent" else if (success) "confirmed" else "timeout") {
        val continueOff = key == HyperPodsPrefsKey.ALLOW_OFF_OPTION && pendingOffSelection && !pending
        lastSettingKey = key
        lastSettingStatus = status
        Intent(HyperPodsAction.ACTION_PODS_SETTING_RESULT).apply {
            putExtra("key", key)
            putExtra("success", success)
            putExtra("pending", pending)
            putExtra("status", status)
            HyperPodsBroadcasts.send(mContext, this)
        }
        sendSettingsState()
        if (status == "write_failed" || status == "timeout")
            recordFailure(diagnosticsSnapshot(), if (status == "write_failed") "setting_write_failed" else "setting_timeout")
        // Publish the prerequisite first, so the following mode command remains the latest operation.
        if (continueOff) {
            pendingOffSelection = false
            if (success) sendSetting(PodsSettings.NOISE_MODE, 1)
            else sendSettingResult(PodsSettings.NOISE_MODE, false, status = status)
        }
    }

    @Synchronized
    private fun sendSetting(key: String, value: Int) {
        val model = currentPodsInfo?.modelNumber?.let(AirPodsModels::getModelByModelNumber)
        if (!PodsSettings.validValue(key, value, model, confirmedSettings()) || pendingSettings.containsKey(key)) {
            failedSettings[key] = value
            sendSettingResult(key, false, status = "invalid")
            return
        }
        val identifier = PodsSettings.identifiers[key] ?: return
        val manager = aacpManager
        val written = if (key == HyperPodsPrefsKey.CHIME_VOLUME && manager != null) {
            // Change only the volume; retain the second configuration byte from the headset.
            val second = manager.getControlCommandStatus(identifier)?.value?.getOrNull(1) ?: run {
                failedSettings[key] = value
                sendSettingResult(key, false, status = "invalid")
                return
            }
            manager.sendControlCommand(identifier.value, byteArrayOf(value.toByte(), second))
        } else manager?.sendControlCommand(identifier.value, value) == true
        if (!written) {
            failedSettings[key] = value
            sendSettingResult(key, false, status = "write_failed")
            return
        }
        failedSettings.remove(key)
        if (key == HyperPodsPrefsKey.PERSONLIZED_VOLUME || key == HyperPodsPrefsKey.CONVERSATION_AWARENESS) {
            // These two toggles follow LibrePods: publish the local value without awaiting an echo.
            if (key == HyperPodsPrefsKey.CONVERSATION_AWARENESS && value != 1) resetConversationVolume()
            sendSettingResult(key, true, status = "sent")
            return
        }
        // Other settings still require a matching headset report.
        val generation = sessionId
        val timeout = CoroutineScope(Dispatchers.Default).launch {
            delay(3000)
            if (key == HyperPodsPrefsKey.ALLOW_OFF_OPTION) {
                synchronized(this@L2CAPController) {
                    ensureActive()
                    if (sessionId == generation && pendingSettings.containsKey(key)) aacpManager?.sendNotificationRequest()
                }
                delay(3000) // One bounded status refresh before abandoning the Off prerequisite.
            }
            synchronized(this@L2CAPController) {
                ensureActive()
                if (sessionId == generation) pendingSettings.remove(key)?.let {
                    failedSettings[key] = it.value
                    sendSettingResult(key, false)
                }
            }
        }
        pendingSettings[key] = PendingSetting(value, timeout)
        sendSettingResult(key, false, pending = true)
    }

    @Synchronized
    private fun handleInEarStatusChanged(status: List<Byte>) {
        if (status.size != 2 || status.any { it.toInt() !in 0..3 }) return
        if (earDetectionStateValid && ::currentEarDetectionParams.isInitialized) {
            if (currentEarDetectionParams.left == status[0] && currentEarDetectionParams.right == status[1]) {
                Log.d(TAG, "receive same in ear status, ignored")
                return
            }
        }
        currentEarDetectionParams = EarDetectionParams(status[0], status[1])
        earDetectionStateValid = true
        if (!batteryStateValid || AirPodsNotifications.BatteryNotification.needsRefresh)
            aacpManager?.sendNotificationRequest()
        routeRetryCount = 0
        changeUIInEarStatus(currentEarDetectionParams)

        if (batteryStateValid && ::currentBatteryParams.isInitialized) {
            val leftInCase = isInCaseStatus(status[0])
            val rightInCase = isInCaseStatus(status[1])
            val caseStateChanged = currentBatteryParams.left?.isInCase != leftInCase ||
                    currentBatteryParams.right?.isInCase != rightInCase
            currentBatteryParams.left?.isInCase = leftInCase
            currentBatteryParams.right?.isInCase = rightInCase
            if (caseStateChanged) {
                MiuiStrongToastUtil.showPodsNotificationByMiuiBt(mContext, currentBatteryParams, mDevice, lowBatterySettings)
                changeUIBatteryStatus(currentBatteryParams)
            }
        }

        updateAudioForEarState()
    }

    // Called under the controller lock, also used when either setting changes.
    private fun updateAudioForEarState() {
        if (!earDetection) {
            pausedAudio = false
            restoreHeadphoneRoute()
            return
        }
        if (!autoSwitchToSpeaker) restoreHeadphoneRoute()
        if (!earDetectionStateValid || mContext == null) return

        val status = listOf(currentEarDetectionParams.left, currentEarDetectionParams.right)
        val leftInEar = status[0] == EarDetectionStatus.IN_EAR
        val rightInEar = status[1] == EarDetectionStatus.IN_EAR
        val inEar = isReadyToPlay()

        Log.d(TAG, "handleInEarStatusChanged left $leftInEar right $rightInEar res $inEar")

        val audioIsPlaying = audioManager?.isMusicActive == true
        // Remember pauses made by us even when both earbuds are removed.
        if (!inEar && audioIsPlaying && !pausedAudio) {
            MediaControl.sendPause()
            pausedAudio = true
        }

        if (autoSwitchToSpeaker && !leftInEar && !rightInEar) {
            scheduleSpeakerRoute()
        } else {
            restoreHeadphoneRoute()
        }

        if (inEar) resumePausedAudio()
    }

    private fun isReadyToPlay(): Boolean {
        val left = currentEarDetectionParams.left
        val right = currentEarDetectionParams.right
        return if (isInCaseStatus(left) || isInCaseStatus(right)) {
            left == EarDetectionStatus.IN_EAR || right == EarDetectionStatus.IN_EAR
        } else {
            left == EarDetectionStatus.IN_EAR && right == EarDetectionStatus.IN_EAR
        }
    }

    @Synchronized
    private fun resumePausedAudio() {
        if (!pausedAudio || !earDetection || !earDetectionStateValid || mContext == null) return
        if (!isReadyToPlay()) return
        // transferTo is asynchronous: do not resume onto the phone speaker while
        // waiting for the selected route to become this headset again.
        val selected = mediaRouter.systemController.selectedRoutes
        val headset = findHeadphoneRoute(selected + routes) ?: return
        if (selected.none { it.id == headset.id }) return
        if (audioManager?.isMusicActive != true) MediaControl.sendPlay()
        pausedAudio = false
    }

    private fun wantsHeadphoneRoute(): Boolean = !earDetection || !autoSwitchToSpeaker ||
        (earDetectionStateValid && (currentEarDetectionParams.left == EarDetectionStatus.IN_EAR ||
            currentEarDetectionParams.right == EarDetectionStatus.IN_EAR))

    @Synchronized
    private fun handleAudioRouteChanged() {
        if (mContext == null) return
        val selected = mediaRouter.systemController.selectedRoutes
        pendingAudioRoute?.let { pending ->
            if (selected.any { it.id == pending.id }) {
                routeTransferTimeout?.cancel()
                routeTransferTimeout = null
                pendingAudioRoute = null
                routeRetryCount = 0
                routeRetryJob?.cancel()
                routeRetryJob = null
                if (pending.type == MediaRoute2Info.TYPE_BLUETOOTH_A2DP) switchedToSpeaker = false
            }
        }
        if (wantsHeadphoneRoute()) restoreHeadphoneRoute()
        else if (earDetectionStateValid && pendingAudioRoute == null) scheduleSpeakerRoute()
        resumePausedAudio()
    }

    @Synchronized
    private fun handleAudioRouteFailure(route: MediaRoute2Info) {
        if (mContext == null || pendingAudioRoute?.id != route.id) return
        routeTransferTimeout?.cancel()
        routeTransferTimeout = null
        pendingAudioRoute = null
        Log.w(TAG, "Audio route transfer failed (type ${route.type})")
        if (route.type == MediaRoute2Info.TYPE_BUILTIN_SPEAKER) {
            switchedToSpeaker = false
            resumePausedAudio()
        } else if (wantsHeadphoneRoute() && routeRetryCount < 2) {
            val thisSession = sessionId
            val waitMs = 500L * ++routeRetryCount
            routeRetryJob?.cancel()
            routeRetryJob = CoroutineScope(Dispatchers.Default).launch {
                delay(waitMs)
                synchronized(this@L2CAPController) {
                    ensureActive()
                    if (sessionId == thisSession && wantsHeadphoneRoute()) restoreHeadphoneRoute()
                }
            }
        } else {
            routeRetryCount = 3
        }
    }

    private fun requestAudioRoute(route: MediaRoute2Info) {
        if (pendingAudioRoute != null) return
        pendingAudioRoute = route
        val generation = sessionId
        routeTransferTimeout = CoroutineScope(Dispatchers.Default).launch {
            delay(3000)
            synchronized(this@L2CAPController) {
                ensureActive()
                if (sessionId == generation && pendingAudioRoute?.id == route.id) handleAudioRouteFailure(route)
            }
        }
        try {
            mediaRouter.transferTo(route)
        } catch (_: RuntimeException) {
            handleAudioRouteFailure(route)
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Synchronized
    fun handleBatteryChanged(packet: ByteArray) {
        val batteries = AirPodsNotifications.BatteryNotification.getBattery()
        val leftInCase = earDetectionStateValid && ::currentEarDetectionParams.isInitialized &&
                isInCaseStatus(currentEarDetectionParams.left)
        val rightInCase = earDetectionStateValid && ::currentEarDetectionParams.isInitialized &&
                isInCaseStatus(currentEarDetectionParams.right)
        val left = PodBatteryParams(
            batteries[0].level,
            batteries[0].status == BatteryStatus.CHARGING,
            batteries[0].isAvailable,
            batteries[0].status,
            leftInCase
        )
        val right = PodBatteryParams(
            batteries[1].level,
            batteries[1].status == BatteryStatus.CHARGING,
            batteries[1].isAvailable,
            batteries[1].status,
            rightInCase
        )
        val case = PodBatteryParams(
            batteries[2].level,
            batteries[2].status == BatteryStatus.CHARGING,
            batteries[2].isAvailable,
            batteries[2].status
        )
        if (BuildConfig.DEBUG) {
            Log.v(
                TAG,
                "batt left ${left.battery} right ${right.battery} case ${case.battery} packet ${
                    packet.toHexString(
                        HexFormat.UpperCase
                    )
                }"
            )
        }

        val shouldShowToast = !mShowedConnectedToast || (lastCaseConnected != case.isConnected && !lastCaseConnected)
        val batteryParams = BatteryParams(left, right, case)
        currentBatteryParams = batteryParams
        batteryStateValid = left.isConnected || right.isConnected

        // allow show toast again when case status from disconnected to active, it means pods put in the case again
        // The transient animation must never block the persistent settings entry
        // or usable battery data. A genuine 0% is also a valid reading.
        if (shouldShowToast && batteryStateValid) {
            MiuiStrongToastUtil.showPodsBatteryToastByMiuiBt(mContext, batteryParams)
            mShowedConnectedToast = true
        }
        lastCaseConnected = case.isConnected
        // Only records from this packet may trigger/rearm a reminder, never other cached components.
        val fresh = if (AirPodsNotifications.BatteryNotification.isBatteryData(packet))
            (7 until packet.size step 5).map { packet[it].toInt() and 0xff }.toIntArray() else intArrayOf()
        MiuiStrongToastUtil.showPodsNotificationByMiuiBt(mContext, batteryParams, mDevice, lowBatterySettings, fresh)
        changeUIBatteryStatus(batteryParams)

        lastTempBatt = if (left.isConnected && right.isConnected)
                minOf(left.battery, right.battery)
            else if (left.isConnected)
                left.battery
            else if (right.isConnected)
                right.battery
            else SystemApisUtils.BATTERY_LEVEL_UNKNOWN

        setRegularBatteryLevel(lastTempBatt)
    }

    private fun isInCaseStatus(status: Byte): Boolean {
        return status == EarDetectionStatus.IN_CASE || status == 0x3.toByte()
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun handleAirPodsPacket(packet: ByteArray) {
        if (AirPodsNotifications.EarDetection.isEarDetectionData(packet)) {
            AirPodsNotifications.EarDetection.setStatus(packet)
            handleInEarStatusChanged(AirPodsNotifications.EarDetection.status)
        } else if (AirPodsNotifications.ANC.isANCData(packet)) {
            AirPodsNotifications.ANC.setStatus(packet)
            currentAnc = AirPodsNotifications.ANC.status
            changeUIAncStatus(currentAnc)
        } else if (AirPodsNotifications.BatteryNotification.isBatteryData(packet)) {
            AirPodsNotifications.BatteryNotification.setBattery(packet)
            handleBatteryChanged(packet)
        } else if (AirPodsNotifications.ConversationalAwarenessNotification.isConversationalAwarenessData(packet)) {
            AirPodsNotifications.ConversationalAwarenessNotification.setData(packet)
        } else {
            if (BuildConfig.DEBUG) {
                Log.v(
                    TAG,
                    "Unknown AirPods Packet Received: ${packet.toHexString(HexFormat.UpperCase)}"
                )
            }
        }

    }

    private fun applyInitialPolicy(expectedSession: Long, expectedRevision: Long, settings: AppPreferences.Snapshot?) {
        if (sessionId == expectedSession && appSettingsRevision == expectedRevision && settings != null)
            updateFeatureToggle(settings)
    }

    private fun updateFeatureToggle(settings: AppPreferences.Snapshot) {
        // A full snapshot must not replay unrelated audio or notification side effects.
        if (earDetection != settings.earDetection || autoSwitchToSpeaker != settings.switchSpeaker)
            updateEarDetectionSettings(settings.earDetection, settings.switchSpeaker)
        if (conversationPhoneVolume != settings.conversationVolume)
            updateConversationPhoneVolume(settings.conversationVolume)
        val remindersChanged = lowBatterySettings != settings.lowBattery
        lowBatterySettings = settings.lowBattery
        if (remindersChanged && batteryStateValid && ::currentBatteryParams.isInitialized)
            MiuiStrongToastUtil.showPodsNotificationByMiuiBt(mContext, currentBatteryParams, mDevice, lowBatterySettings)
    }

    private fun updateConversationPhoneVolume(enabled: Boolean) {
        conversationPhoneVolume = enabled
        if (!enabled) resetConversationVolume()
    }

    @Synchronized
    private fun updateEarDetectionSettings(enabled: Boolean, switchToSpeaker: Boolean) {
        earDetection = enabled
        autoSwitchToSpeaker = switchToSpeaker
        routeRetryCount = 0
        updateAudioForEarState()
    }

    private val routeCallback = object : MediaRouter2.RouteCallback() {
        override fun onRoutesUpdated(routes: List<MediaRoute2Info>) {
            if (BuildConfig.DEBUG) Log.v(TAG, "routes updated (${routes.size})")
            this@L2CAPController.routes = routes
            synchronized(this@L2CAPController) {
                if (mContext != null && wantsHeadphoneRoute()) restoreHeadphoneRoute()
            }
        }
    }
    private val controllerCallback = object : MediaRouter2.ControllerCallback() {
        override fun onControllerUpdated(controller: MediaRouter2.RoutingController) {
            handleAudioRouteChanged()
        }
    }
    private val transferCallback = object : MediaRouter2.TransferCallback() {
        override fun onTransfer(oldController: MediaRouter2.RoutingController, newController: MediaRouter2.RoutingController) {
            handleAudioRouteChanged()
        }
        override fun onTransferFailure(route: MediaRoute2Info) {
            handleAudioRouteFailure(route)
        }
    }
    private fun startRoutesScan() {
        val executor = Executor { p0 ->
            CoroutineScope(Dispatchers.IO).launch {
                p0?.run()
            }
        }

        val preferredFeature = listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO, MediaRoute2Info.FEATURE_LIVE_VIDEO)
        mediaRouter.registerRouteCallback(executor, routeCallback, RouteDiscoveryPreference.Builder(preferredFeature, true).build())
        mediaRouter.registerControllerCallback(executor, controllerCallback)
        mediaRouter.registerTransferCallback(executor, transferCallback)
        scanToken = mediaRouter.requestScan(MediaRouter2.ScanRequest.Builder().build())
    }

    private fun stopRoutesScan() {
        scanToken?.let { mediaRouter.cancelScanRequest(it) }
        mediaRouter.unregisterRouteCallback(routeCallback)
        mediaRouter.unregisterControllerCallback(controllerCallback)
        mediaRouter.unregisterTransferCallback(transferCallback)
        scanToken = null
    }

    private val packetCallback = object : AACPManager.PacketCallback {
        override fun onBatteryInfoReceived(batteryInfo: ByteArray) {
            if (!AirPodsNotifications.BatteryNotification.setBattery(batteryInfo)) {
                Log.w(TAG, "Ignoring incomplete battery packet (${batteryInfo.size} bytes)")
                return
            }
            handleBatteryChanged(batteryInfo)
        }

        override fun onEarDetectionReceived(earDetection: ByteArray) {
            AirPodsNotifications.EarDetection.setStatus(earDetection)
            handleInEarStatusChanged(AirPodsNotifications.EarDetection.status)
        }

        override fun onConversationAwarenessReceived(conversationAwareness: ByteArray) {
            AirPodsNotifications.ConversationalAwarenessNotification.setData(conversationAwareness)
            val status = AirPodsNotifications.ConversationalAwarenessNotification.status.toInt()
            if (!conversationPhoneVolume || confirmedSettings()[HyperPodsPrefsKey.CONVERSATION_AWARENESS] != 1) return
            if (status !in 1..3 && status !in 6..9) return
            if (status in 1..3) {
                conversationRestoreJob?.cancel()
                conversationRestoreJob = null
            }
            runCatching { conversationVolume.onStatus(status, smoothRestore = true) }
                .onFailure { Log.w(TAG, "Unable to adjust conversation volume") }
            conversationTimeout?.cancel()
            if (status in 1..3) {
                val generation = sessionId
                conversationTimeout = CoroutineScope(Dispatchers.Default).launch {
                    delay(30000)
                    synchronized(this@L2CAPController) {
                        ensureActive()
                        if (sessionId == generation) resetConversationVolume()
                    }
                }
            } else smoothlyRestoreConversationVolume()
        }

        override fun onControlCommandReceived(controlCommand: ByteArray) {
            val command = AACPManager.ControlCommand.fromByteArray(controlCommand)
            val key = PodsSettings.keyFor(command.identifier)
            val value = command.value.firstOrNull()?.toInt()?.and(0xff)
            if (key != null && value != null) {
                if (key == lastSettingKey && lastSettingStatus == "sent" && !pendingSettings.containsKey(key))
                    lastSettingStatus = "reported"
                pendingSettings[key]?.takeIf { it.value == value }?.let {
                    pendingSettings.remove(key)
                    failedSettings.remove(key)
                    it.timeout.cancel()
                    sendSettingResult(key, true)
                }
                if (key !in pendingSettings && failedSettings[key] == value && PodsSettings.knownValue(key, value)) {
                    failedSettings.remove(key)
                    sendSettingResult(key, true, status = "reported")
                }
                if (key == HyperPodsPrefsKey.CONVERSATION_AWARENESS && value != 1) resetConversationVolume()
                sendSettingsState()
            }
            if (command.identifier == AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value) {
                AirPodsNotifications.ANC.setStatus(byteArrayOf(command.value.takeIf { it.isNotEmpty() }?.get(0) ?: 0x00.toByte()))
                currentAnc = AirPodsNotifications.ANC.status
                changeUIAncStatus(currentAnc)
            }
        }

        override fun onDeviceInformationReceived(deviceInformation: AACPManager.Companion.AirPodsInformation) {
            currentPodsInfo = deviceInformation
            if (pendingRename == deviceInformation.name) {
                pendingRename = null
                renameTimeout?.cancel()
                renameTimeout = null
                runCatching {
                    if (mDevice.setAlias(deviceInformation.name) != BluetoothStatusCodes.SUCCESS) Log.w(TAG, "Bluetooth alias update was rejected")
                }
                    .onFailure { Log.w(TAG, "Unable to update Bluetooth alias") }
                sendSettingResult(PodsSettings.RENAME, true)
                MiuiStrongToastUtil.showPodsNotificationByMiuiBt(mContext, currentBatteryParams, mDevice, lowBatterySettings)
            }
            sendConnectedStatus()
        }

        override fun onHeadTrackingReceived(headTracking: ByteArray) {
//            TODO("Not yet implemented")
        }

        override fun onUnknownPacketReceived(packet: ByteArray) {

        }

        override fun onProximityKeysReceived(proximityKeys: ByteArray) {
//            TODO("Not yet implemented")
        }

        override fun onStemPressReceived(stemPress: ByteArray) {

        }

        override fun onAudioSourceReceived(audioSource: ByteArray) {
//            TODO("Not yet implemented")
        }

        override fun onOwnershipChangeReceived(owns: Boolean) {
//            TODO("Not yet implemented")
        }

        override fun onConnectedDevicesReceived(connectedDevices: List<AACPManager.Companion.ConnectedDevice>) {
//            TODO("Not yet implemented")
        }

        override fun onOwnershipToFalseRequest(
            sender: String,
            reasonReverseTapped: Boolean
        ) {
//            TODO("Not yet implemented")
        }

        override fun onShowNearbyUI(sender: String) {

        }
    }

    @Synchronized
    fun connectPod(context: Context, device: BluetoothDevice) {
        if (connectionJob?.isActive == true && mContext != null && mDevice == device) return
        if (mContext != null && mDevice != device) disconnectedPod(context, mDevice)
        val newDeviceSession = mContext == null
        val thisSession = ++sessionId
        mContext = context
        mDevice = device
        resetControlState()
        connectionState = "connecting"
        initialStatusRetries = 0
        lastStatusFailure = ""
        lastRecordedFailure = ""
        lastSettingKey = ""
        lastSettingStatus = ""
        if (newDeviceSession) context.registerReceiver(broadcastReceiver, IntentFilter().apply {
            this.addAction(HyperPodsAction.ACTION_APP_SETTINGS_CHANGED)
            this.addAction(HyperPodsAction.ACTION_ANC_SELECT)
            this.addAction(HyperPodsAction.ACTION_PODS_UI_INIT)
            this.addAction(HyperPodsAction.ACTION_EAR_DETECTION_SWITCH_CHANGED)
            this.addAction(HyperPodsAction.ACTION_GET_PODS_MAC)
            this.addAction(HyperPodsAction.ACTION_PODS_SETTINGS_CHANGED)
            this.addAction(HyperPodsAction.ACTION_PODS_RENAME)
            this.addAction(HyperPodsAction.ACTION_PODS_STATUS_RETRY)
            this.addAction(HyperPodsAction.ACTION_PODS_DIAGNOSTICS_REQUEST)
        }, Context.RECEIVER_EXPORTED)
        sendDiagnostics()

        MediaControl.mContext = mContext
        if (newDeviceSession) {
            mediaRouter = MediaRouter2.getInstance(context)
            startRoutesScan()
        }

        val uuid = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")

        fun getBtSocket(): BluetoothSocket {
            // Android 17 removed the six-argument convenience constructor on this
            // build and added BluetoothAdapter before BluetoothDevice. Find its
            // extended equivalent while keeping TYPE_L2CAP (3):
            // BluetoothDevice.createL2capChannel() now creates TYPE_LE (4).
            val prefix = arrayOf(
                BluetoothAdapter::class.java,
                BluetoothDevice::class.java,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                ParcelUuid::class.java,
            )
            val constructor = BluetoothSocket::class.java.declaredConstructors
                .filter { it.parameterTypes.size >= prefix.size }
                .filter { candidate ->
                    prefix.indices.all { candidate.parameterTypes[it] == prefix[it] }
                }
                .minByOrNull { it.parameterCount }
                ?: throw NoSuchMethodException(
                    "No compatible classic L2CAP BluetoothSocket constructor: " +
                        BluetoothSocket::class.java.declaredConstructors.joinToString { it.toString() }
                )

            val args = constructor.parameterTypes.mapIndexed { index, type ->
                when (index) {
                    0 -> BluetoothAdapter.getDefaultAdapter()
                    1 -> device
                    2 -> 3 // BluetoothSocket.TYPE_L2CAP (classic BR/EDR)
                    3, 4 -> true // authenticated and encrypted
                    5 -> 0x1001 // AirPods AAP PSM
                    6 -> uuid
                    else -> when (type) {
                        Boolean::class.javaPrimitiveType -> false
                        Int::class.javaPrimitiveType -> 0
                        Long::class.javaPrimitiveType -> 0L
                        String::class.java -> "HyperPods"
                        android.content.AttributionSource::class.java -> context.attributionSource
                        else -> null
                    }
                }
            }.toTypedArray()
            constructor.isAccessible = true
            Log.i(TAG, "using BluetoothSocket constructor: $constructor")
            return constructor.newInstance(*args) as BluetoothSocket
        }

        val requestRevision = appSettingsRevision
        connectionJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // Overlap IPC with the existing connection delay; never block the Bluetooth main thread.
                val settingsRequest = async { AppPreferences.readRemote(context) }
                delay(500)
                val policy = settingsRequest.await()
                synchronized(this@L2CAPController) {
                    ensureActive()
                    if (sessionId != thisSession) throw CancellationException("Obsolete AirPods session")
                    // A newer UI edit wins over a cold-start provider response.
                    applyInitialPolicy(thisSession, requestRevision, policy)
                }
                recoverControlSession(context, device, thisSession, ::getBtSocket)
            } finally {
                synchronized(this@L2CAPController) {
                    if (sessionId == thisSession) {
                        connectionJob = null
                        // Keep the receiver available for manual recovery after
                        // the bounded automatic attempts have been exhausted.
                        sendDiagnostics(force = true)
                    }
                }
            }
        }
    }

    internal suspend fun CoroutineScope.recoverControlSession(context: Context, device: BluetoothDevice,
        thisSession: Long, getBtSocket: () -> BluetoothSocket) {
        // AACP can fail while A2DP remains connected. Retry only this socket,
        // retaining the UI receiver and physical audio route.
        for (attempt in 0..3) {
            if (attempt > 0) {
                delay(1000L shl (attempt - 1))
                synchronized(this@L2CAPController) {
                    ensureActive()
                    if (sessionId != thisSession) throw CancellationException("Obsolete AirPods session")
                    resetControlState()
                    connectionState = "connecting"
                    sendConnectedStatus()
                    changeUIBatteryStatus(currentBatteryParams)
                    MiuiStrongToastUtil.showPodsNotificationByMiuiBt(context, currentBatteryParams, device, lowBatterySettings)
                }
            }
            runControlAttempt(context, device, thisSession, getBtSocket)
        }
    }

    internal suspend fun CoroutineScope.runControlAttempt(context: Context, device: BluetoothDevice,
        thisSession: Long, getBtSocket: () -> BluetoothSocket) {
        val connectingSocket = try {
            getBtSocket()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "failed to create AirPods socket", e)
            synchronized(this@L2CAPController) {
                if (sessionId == thisSession) {
                    connectionState = "failed"
                    lastStatusFailure = "socket_create_failed"
                    sendDiagnostics()
                }
            }
            return
        }
        try {
            synchronized(this@L2CAPController) {
                ensureActive()
                if (sessionId != thisSession) throw CancellationException("Obsolete AirPods session")
                socket = connectingSocket
            }
            Log.d(TAG, "connecting AirPods!")
            connectingSocket.connect()
            ensureActive()
        } catch (e: CancellationException) {
            runCatching { connectingSocket.close() }
            synchronized(this@L2CAPController) {
                if (socket === connectingSocket) socket = null
            }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "failed to connect to AirPods control socket", e)
            runCatching { connectingSocket.close() }
            synchronized(this@L2CAPController) {
                if (sessionId == thisSession) {
                    if (socket === connectingSocket) socket = null
                    connectionState = "failed"
                    lastStatusFailure = "socket_connect_failed"
                    sendDiagnostics()
                }
            }
            return
        }


        Log.d(TAG, "connected!")
        val connectedSocket = connectingSocket
        val manager = AACPManager(connectedSocket)
        var statusRequestJob: Job? = null
        try {
            synchronized(this@L2CAPController) {
                ensureActive()
                if (sessionId != thisSession) throw CancellationException("Obsolete AirPods session")
                aacpManager = manager
                connectionState = "connected"
                manager.setPacketCallback(packetCallback)
                MiuiStrongToastUtil.showPodsNotificationByMiuiBt(context, currentBatteryParams, device, lowBatterySettings)
                sendConnectedStatus()
            }
            if (!manager.requestInitialStatus()) throw java.io.IOException("Initial handshake write failed")
            statusRequestJob = launch {
                // Retry the missing protocol step on this socket. The reader is already active.
                for (waitMs in listOf(1000L, 2000L, 4000L, 8000L, 15000L)) {
                    delay(waitMs)
                    val stop = synchronized(this@L2CAPController) {
                        ensureActive()
                        if (sessionId != thisSession || initialStatusReady() || !connectedSocket.isConnected) true
                        else {
                            initialStatusRetries++
                            Log.i(TAG, "Initial headset status incomplete; retrying ${manager.initializationStage}")
                            lastStatusFailure = if (manager.requestInitialStatus()) "" else "write_failed"
                            sendDiagnostics()
                            false
                        }
                    }
                    if (stop) return@launch
                }
                delay(3000)
                synchronized(this@L2CAPController) {
                    ensureActive()
                    if (sessionId == thisSession && !initialStatusReady()) {
                        lastStatusFailure = "status_timeout"
                        sendDiagnostics()
                    }
                }
            }
            // Read handshake and feature acknowledgements immediately, without fixed sleeps.
            while (connectedSocket.isConnected) {
                val buffer = ByteArray(1024)
                val bytesRead = connectedSocket.inputStream.read(buffer)
                ensureActive()
                if (bytesRead == -1) throw java.io.IOException("AirPods control stream closed")
                if (bytesRead > 0) synchronized(this@L2CAPController) {
                    ensureActive()
                    if (sessionId != thisSession) throw CancellationException("Obsolete AirPods session")
                    manager.receivePacket(buffer.copyOfRange(0, bytesRead))
                    if (initialStatusReady()) lastStatusFailure = ""
                    sendDiagnostics()
                }
            }
            throw java.io.IOException("AirPods control socket disconnected")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AirPods status session ended", e)
            synchronized(this@L2CAPController) {
                if (sessionId == thisSession) {
                    connectionState = "failed"
                    lastStatusFailure = "status_session_ended"
                    sendDiagnostics()
                }
            }
        } finally {
            statusRequestJob?.cancel()
            runCatching { connectedSocket.close() }
            synchronized(this@L2CAPController) {
                if (aacpManager === manager) aacpManager = null
                if (socket === connectedSocket) socket = null
                if (sessionId == thisSession) {
                    clearPendingSettings()
                    sendSettingsState()
                }
            }
        }
    }

    private fun clearPendingSettings() {
        pendingSettings.values.forEach { it.timeout.cancel() }
        pendingSettings.clear()
        failedSettings.clear()
        pendingOffSelection = false
        pendingRename = null
        renameTimeout?.cancel()
        renameTimeout = null
    }

    private fun resetControlState() {
        clearPendingSettings()
        resetConversationVolume()
        batteryStateValid = false
        earDetectionStateValid = false
        mShowedConnectedToast = false
        lastCaseConnected = false
        currentPodsInfo = null
        currentAnc = 0
        aacpManager = null
        lastSettingsState = null
        lastDiagnostics = null
        AirPodsNotifications.BatteryNotification.reset()
        currentBatteryParams = BatteryParams(PodBatteryParams(), PodBatteryParams(), PodBatteryParams())
    }

    @Synchronized
    fun disconnectedPod(context: Context, device: BluetoothDevice) {
        if (::mDevice.isInitialized && device != mDevice) return
        ++sessionId
        clearPendingSettings()
        resetConversationVolume()
        headphoneRouteId = null
        speakerSwitchJob?.cancel()
        speakerSwitchJob = null
        switchedToSpeaker = false
        pendingAudioRoute = null
        routeRetryJob?.cancel()
        routeRetryJob = null
        routeTransferTimeout?.cancel()
        routeTransferTimeout = null
        routeRetryCount = 0
        connectionJob?.cancel()
        connectionJob = null
        socket?.let { runCatching { it.close() } }
        socket = null

        mContext?.let {
            runCatching { stopRoutesScan() }
            cancelPodsNotificationByMiuiBt(it, device)
            Intent(HyperPodsAction.ACTION_PODS_DISCONNECTED).apply {
                putExtra("initialization_failed", lastStatusFailure.isNotEmpty())
                HyperPodsBroadcasts.send(it, this)
            }
            runCatching { it.unregisterReceiver(broadcastReceiver) }
        }

        mShowedConnectedToast = false
        lastCaseConnected = false
        batteryStateValid = false
        earDetectionStateValid = false
        pausedAudio = false
        currentAnc = 0
        currentPodsInfo = null
        routes = emptyList()
        mContext = null
        MediaControl.mContext = null
        aacpManager = null
        connectionState = "disconnected"
        lastSettingsState = null
        lastDiagnostics = null
    }

    fun sendPacket(packet: String) {
        val fromHex = packet.split(" ").map { it.toInt(16).toByte() }
        sendPacket(fromHex.toByteArray())
    }

    @Synchronized
    fun sendPacket(packet: ByteArray) {
        aacpManager?.sendPacket(packet)
    }

    @Synchronized
    fun setANCMode(mode: Int) {
        Log.d(TAG, "setANCMode: $mode")
        if (pendingOffSelection) return
        val model = currentPodsInfo?.modelNumber?.let(AirPodsModels::getModelByModelNumber)
        if (mode == 1 && PodsSettings.supports(HyperPodsPrefsKey.ALLOW_OFF_OPTION, model) &&
            confirmedSettings()[HyperPodsPrefsKey.ALLOW_OFF_OPTION] != 1) {
            if (pendingSettings.containsKey(PodsSettings.NOISE_MODE) ||
                pendingSettings.containsKey(HyperPodsPrefsKey.ALLOW_OFF_OPTION)) return
            // Only an explicit Off click enables this firmware option. Wait for
            // its acknowledgement before issuing the listening-mode command.
            pendingOffSelection = true
            sendSetting(HyperPodsPrefsKey.ALLOW_OFF_OPTION, 1)
            return
        }
        sendSetting(PodsSettings.NOISE_MODE, mode)
    }

    private fun initialStatusReady(): Boolean {
        val model = currentPodsInfo?.modelNumber?.let(AirPodsModels::getModelByModelNumber)
        return batteryStateValid && !AirPodsNotifications.BatteryNotification.needsRefresh &&
                earDetectionStateValid && currentPodsInfo != null &&
                (!PodsSettings.supports(PodsSettings.NOISE_MODE, model) || PodsSettings.NOISE_MODE in confirmedSettings()) &&
                missingSettings().isEmpty()
    }

    private fun missingSettings(): Set<String> {
        val model = currentPodsInfo?.modelNumber?.let(AirPodsModels::getModelByModelNumber) ?: return emptySet()
        val reported = confirmedSettings()
        return PodsSettings.identifiers.keys.filterTo(mutableSetOf()) {
            PodsSettings.supports(it, model) && it !in reported
        }
    }

    private fun scheduleSpeakerRoute() {
        if (switchedToSpeaker || speakerSwitchJob?.isActive == true) return
        speakerSwitchJob = CoroutineScope(Dispatchers.Default).launch {
            // Keep the existing pause delay, but never let it outlive a wear
            // change, a disabled setting, or the current Bluetooth session.
            delay(500)
            synchronized(this@L2CAPController) {
                ensureActive()
                speakerSwitchJob = null
                if (mContext == null || !earDetection || !autoSwitchToSpeaker) return@synchronized
                if (currentEarDetectionParams.left == EarDetectionStatus.IN_EAR ||
                    currentEarDetectionParams.right == EarDetectionStatus.IN_EAR) return@synchronized
                routes.firstOrNull { it.type == MediaRoute2Info.TYPE_BUILTIN_SPEAKER }?.let {
                    // A wear event must not take over a route selected by the user.
                    if (findHeadphoneRoute(mediaRouter.systemController.selectedRoutes) == null) return@synchronized
                    // Only change the media route. HFP/A2DP must stay connected.
                    switchedToSpeaker = true
                    requestAudioRoute(it)
                }
            }
        }
    }

    private fun restoreHeadphoneRoute() {
        speakerSwitchJob?.cancel()
        speakerSwitchJob = null
        // Initial wear reports must not reconnect profiles or take over a route
        // selected by the user. Only undo a speaker switch made by this module.
        if (!switchedToSpeaker || mContext == null || pendingAudioRoute != null || routeRetryCount >= 3) return
        val selected = mediaRouter.systemController.selectedRoutes
        if (selected.none { it.type == MediaRoute2Info.TYPE_BUILTIN_SPEAKER } && findHeadphoneRoute(selected) == null) {
            // The user selected another output after our speaker transfer.
            switchedToSpeaker = false
            pausedAudio = false
            routeRetryJob?.cancel()
            routeRetryJob = null
            return
        }
        findHeadphoneRoute(routes)?.let {
            requestAudioRoute(it)
        }
    }

    private fun findHeadphoneRoute(candidates: List<MediaRoute2Info>): MediaRoute2Info? {
        val bluetooth = candidates.filter { it.type == MediaRoute2Info.TYPE_BLUETOOTH_A2DP }.distinctBy { it.id }
        fun address(route: MediaRoute2Info): String? = runCatching {
            MediaRoute2Info::class.java.getMethod("getAddress").invoke(route) as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val byAddress = bluetooth.singleOrNull { address(it)?.equals(mDevice.address, ignoreCase = true) == true }
        val cached = bluetooth.find { it.id == headphoneRouteId &&
            (address(it) == null || address(it)?.equals(mDevice.address, ignoreCase = true) == true) }
        // On ROMs without address metadata, use only an unambiguous name match once,
        // then retain the route ID so renaming cannot break restore/playback.
        val names = listOfNotNull(mDevice.alias, mDevice.name)
        val match = byAddress ?: cached ?: bluetooth.singleOrNull { address(it) == null && it.name.toString() in names }
        if (match != null) headphoneRouteId = match.id
        return match
    }

    private fun resetConversationVolume() {
        conversationTimeout?.cancel()
        conversationTimeout = null
        conversationRestoreJob?.cancel()
        conversationRestoreJob = null
        runCatching { conversationVolume.reset() }
            .onFailure { Log.w(TAG, "Unable to restore conversation volume") }
    }

    private fun smoothlyRestoreConversationVolume() {
        if (conversationRestoreJob?.isActive == true) return
        val generation = sessionId
        conversationRestoreJob = CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                delay(50)
                val more = synchronized(this@L2CAPController) {
                    ensureActive()
                    sessionId == generation && runCatching { conversationVolume.restoreStep() }
                        .onFailure { Log.w(TAG, "Unable to restore conversation volume") }.getOrDefault(false)
                }
                if (!more) return@launch
            }
        }
    }

    fun setRegularBatteryLevel(level: Int) {
        try {
            // A2dpService no longer owns mAdapterService on Android 17; it is kept
            // by the ConnectableProfile superclass and exposed through this method.
            val service = try {
                XposedHelpers.callMethod(mContext, "getAdapterService")
            } catch (_: Throwable) {
                XposedHelpers.getObjectField(mContext, "mAdapterService")
            }
            XposedHelpers.callMethod(service, "setBatteryLevel", mDevice, level, false)
        } catch (error: Throwable) {
            // Battery mirroring is cosmetic and must never take down Bluetooth.
            Log.e(TAG, "unable to mirror battery level to AdapterService", error)
        }
    }
}
