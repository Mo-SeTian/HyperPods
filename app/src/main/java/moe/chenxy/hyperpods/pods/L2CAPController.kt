package moe.chenxy.hyperpods.pods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
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
import android.util.Log
import com.highcapable.yukihookapi.hook.xposed.prefs.YukiHookPrefsBridge
import de.robv.android.xposed.XposedHelpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.utils.AACPManager
import moe.chenxy.hyperpods.utils.AirPodsInstance
import moe.chenxy.hyperpods.utils.MediaControl
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
import kotlin.reflect.full.memberProperties

@SuppressLint("MissingPermission", "StaticFieldLeak")
object L2CAPController {
    private const val TAG = "HyperPods-L2CAPController"

    // Basic Object
    private var socket: BluetoothSocket? = null
    private var mContext: Context? = null
    lateinit var mDevice: BluetoothDevice
    private val audioManager: AudioManager?
        get() = mContext?.getSystemService(AudioManager::class.java)
    private lateinit var mPrefsBridge: YukiHookPrefsBridge

    private var aacpManager: AACPManager? = null
    private var connectionJob: Job? = null
    private var sessionId = 0L

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
    private var routeRetryCount = 0
    private var pausedAudio = false
    private var lastTempBatt = 0
    lateinit var currentEarDetectionParams: EarDetectionParams
    lateinit var currentBatteryParams: BatteryParams
    private var currentAnc: Int = 1
    var currentPodsInfo: AACPManager.Companion.AirPodsInformation? = null

    // Function toggle
    private var earDetection = true
    private var autoSwitchToSpeaker = true

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
            HyperPodsAction.ACTION_PODS_UI_INIT -> {
                Log.i(TAG, "UI Init")

                if (earDetectionStateValid && ::currentEarDetectionParams.isInitialized)
                    changeUIInEarStatus(currentEarDetectionParams)

                if (batteryStateValid && ::currentBatteryParams.isInitialized)
                    changeUIBatteryStatus(currentBatteryParams)

                changeUIAncStatus(currentAnc)
                if (aacpManager != null) sendConnectedStatus()
            }
            HyperPodsAction.ACTION_ANC_SELECT -> {
                val status = intent.getIntExtra("status", 0)
                setANCMode(status)
            }
            HyperPodsAction.ACTION_PODS_RENAME -> {
                val name = intent.getStringExtra("name")?.trim() ?: return
                if (name.isEmpty() || name.toByteArray().size > 255) return
                if (aacpManager?.sendRename(name) == true) {
                    runCatching { mDevice.setAlias(name) }
                        .onFailure { Log.w(TAG, "Unable to update Bluetooth alias") }
                    currentPodsInfo = currentPodsInfo?.copy(name = name)
                    sendConnectedStatus()
                    MiuiStrongToastUtil.showPodsNotificationByMiuiBt(mContext, currentBatteryParams, mDevice)
                }
            }

            HyperPodsAction.ACTION_EAR_DETECTION_SWITCH_CHANGED -> {
                updateEarDetectionSettings(
                    intent.getBooleanExtra("ear_detection", true),
                    intent.getBooleanExtra("disconnect_audio", true)
                )
            }
            HyperPodsAction.ACTION_PODS_SETTINGS_CHANGED -> {
                intent.getStringExtra("key")?.let { handleUISettingsChanged(it) }
            }
        }
    }

    private fun sendConnectedStatus() {
        Intent(HyperPodsAction.ACTION_PODS_CONNECTED).apply {
            putExtra("device_info", currentPodsInfo)
            putExtra("device_name", mDevice.alias ?: mDevice.name)
            HyperPodsBroadcasts.send(mContext, this)
        }
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
                MiuiStrongToastUtil.showPodsNotificationByMiuiBt(mContext, currentBatteryParams, mDevice)
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
        if (mediaRouter.systemController.selectedRoutes.none {
            it.type == MediaRoute2Info.TYPE_BLUETOOTH_A2DP && it.name == mDevice.name
        }) return
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
        MiuiStrongToastUtil.showPodsNotificationByMiuiBt(mContext, batteryParams, mDevice)
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

    fun initAllCustomSettings() {
        val values = HyperPodsPrefsKey::class.memberProperties
            .filter { it.returnType.classifier == String::class }
            .map { it.getter.call(HyperPodsPrefsKey) as String }
        for (key in values) {
            handleUISettingsChanged(key)
        }
    }

    fun getIdentifierValue(identifier: AACPManager.Companion.ControlCommandIdentifiers): Byte? {
        return aacpManager?.controlCommandStatusList?.find {
            it.identifier == identifier
        }?.value?.takeIf { it.isNotEmpty() }?.get(0)
    }

    fun handleUISettingsChanged(key: String) {
        when (key) {
            HyperPodsPrefsKey.PERSONLIZED_VOLUME -> {
                val checked = mPrefsBridge.getBoolean(key, true)
                aacpManager?.sendControlCommand(identifier = AACPManager.Companion.ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG.value, value = checked)
            }
//            HyperPodsPrefsKey.CASE_CHARGING_SOUND -> {
////                aacpManager?.sendControlCommand(identifier = AACPManager.Companion.ControlCommandIdentifiers.CH.value, value = checked)
//                setCaseChargingSounds(mPrefsBridge.getBoolean(key, true))
//            }
            HyperPodsPrefsKey.ADAPTIVE_AUDIO_LEVEL -> {
                val value = mPrefsBridge.getFloat(key, 0.5f)
                aacpManager?.sendControlCommand(identifier = AACPManager.Companion.ControlCommandIdentifiers.AUTO_ANC_STRENGTH.value, value = (100 - value * 100).toInt())
            }
            HyperPodsPrefsKey.CONVERSATION_AWARENESS -> {
                val checked = mPrefsBridge.getBoolean(key, true)
                aacpManager?.sendControlCommand(identifier = AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG.value, value = checked)
            }
            HyperPodsPrefsKey.LOUD_SOUND_REDUCTION -> {
                val checked = mPrefsBridge.getBoolean(key, true)
                // TODO: porting ATTManager from LibrePod
                setLoudSoundReduction(checked)
            }
            HyperPodsPrefsKey.ADJUST_VOLUME_BY_SWIPER -> {
                val checked = mPrefsBridge.getBoolean(key, true)
                aacpManager?.sendControlCommand(identifier = AACPManager.Companion.ControlCommandIdentifiers.VOLUME_SWIPE_MODE.value, value = checked)
            }
            HyperPodsPrefsKey.LISTENING_MODE_BYTE -> {
                val value = mPrefsBridge.getInt(key, AACPManager.Companion.ListeningMode.NC.value.toInt() or AACPManager.Companion.ListeningMode.TRANSPARENCY.value.toInt())
                aacpManager?.sendControlCommand(identifier = AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE_CONFIGS.value, value = value.toByte())
            }
            HyperPodsPrefsKey.LONG_PRESS_MODE_LEFT -> {
                // TODO: Implement launch Xiaoai & enable custom hold action
            }
            HyperPodsPrefsKey.LONG_PRESS_MODE_RIGHT -> {
                // TODO: Implement launch Xiaoai & enable custom hold action
            }

            HyperPodsPrefsKey.EAR_DETECTION,
            HyperPodsPrefsKey.EAR_DETECTION_SWITCH_SPEAKER -> {
                // The UI saves both switches together. Reload them together too.
                updateFeatureToggle()
            }

            HyperPodsPrefsKey.SINGLE_POD_ANC -> {
                val checked = mPrefsBridge.getBoolean(key, true)
                aacpManager?.sendControlCommand(identifier = AACPManager.Companion.ControlCommandIdentifiers.ONE_BUD_ANC_MODE.value, value = checked)
            }

            HyperPodsPrefsKey.MICROPHONE_MODE -> {
                val value = mPrefsBridge.getInt(key, 0)
                val byteValue = when (value) {
                    0 -> 0x00
                    2 -> 0x01
                    1 -> 0x02
                    else -> 0x00
                }
                aacpManager?.sendControlCommand(identifier = AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE.value, value = byteValue)
            }
        }
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

    private fun updateFeatureToggle() {
        updateEarDetectionSettings(
            mPrefsBridge.getBoolean(HyperPodsPrefsKey.EAR_DETECTION, true),
            mPrefsBridge.getBoolean(HyperPodsPrefsKey.EAR_DETECTION_SWITCH_SPEAKER, true)
        )
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
            Log.v(TAG, "routes updated: $routes")
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
            Log.i(TAG, "Conversation Awareness: ${AirPodsNotifications.ConversationalAwarenessNotification.status}")
        }

        override fun onControlCommandReceived(controlCommand: ByteArray) {
            val command = AACPManager.ControlCommand.fromByteArray(controlCommand)
            if (command.identifier == AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value) {
                AirPodsNotifications.ANC.setStatus(byteArrayOf(command.value.takeIf { it.isNotEmpty() }?.get(0) ?: 0x00.toByte()))
                currentAnc = AirPodsNotifications.ANC.status
                changeUIAncStatus(currentAnc)
            }
        }

        override fun onDeviceInformationReceived(deviceInformation: AACPManager.Companion.AirPodsInformation) {
            currentPodsInfo = deviceInformation
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
    fun connectPod(context: Context, device: BluetoothDevice, prefsBridge: YukiHookPrefsBridge) {
        if (connectionJob?.isActive == true && mContext != null && mDevice == device) return
        if (mContext != null) disconnectedPod(context, mDevice)
        val thisSession = ++sessionId
        mContext = context
        mDevice = device
        mPrefsBridge = prefsBridge
        batteryStateValid = false
        earDetectionStateValid = false
        mShowedConnectedToast = false
        lastCaseConnected = false
        currentPodsInfo = null
        currentAnc = 0
        AirPodsNotifications.BatteryNotification.reset()
        currentBatteryParams = BatteryParams(PodBatteryParams(), PodBatteryParams(), PodBatteryParams())

        updateFeatureToggle()

        context.registerReceiver(broadcastReceiver, IntentFilter().apply {
            this.addAction(HyperPodsAction.ACTION_ANC_SELECT)
            this.addAction(HyperPodsAction.ACTION_PODS_UI_INIT)
            this.addAction(HyperPodsAction.ACTION_EAR_DETECTION_SWITCH_CHANGED)
            this.addAction(HyperPodsAction.ACTION_GET_PODS_MAC)
            this.addAction(HyperPodsAction.ACTION_PODS_SETTINGS_CHANGED)
            this.addAction(HyperPodsAction.ACTION_PODS_RENAME)
        }, Context.RECEIVER_EXPORTED)

        MediaControl.mContext = mContext
        mediaRouter = MediaRouter2.getInstance(mContext!!)
        startRoutesScan()

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

        connectionJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                delay(500)
                val connectingSocket = try {
                    getBtSocket()
                } catch (e: Exception) {
                    Log.e(TAG, "failed to create AirPods socket", e)
                    return@launch
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
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "failed to connect to AirPods socket; stop retrying", e)
                    runCatching { connectingSocket.close() }
                    return@launch
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
                        manager.setPacketCallback(packetCallback)
                        MiuiStrongToastUtil.showPodsNotificationByMiuiBt(context, currentBatteryParams, device)
                        sendConnectedStatus()
                    }
                    manager.sendDataPacket(manager.createHandshakePacket())
                    manager.sendPacket(manager.createHandshakePacket())
                    delay(200)
                    manager.sendSetFeatureFlagsPacket()
                    delay(200)
                    manager.sendNotificationRequest()
                    statusRequestJob = launch {
                        // Retry only the status subscription, not the native socket.
                        // This job belongs to the connection and stops on disconnect.
                        for (waitMs in listOf(1000L, 2000L, 4000L)) {
                            delay(waitMs)
                            val earsReady = synchronized(this@L2CAPController) {
                                sessionId != thisSession || (batteryStateValid &&
                                currentBatteryParams.left?.rawStatus != BatteryStatus.NEED_AGAIN &&
                                currentBatteryParams.right?.rawStatus != BatteryStatus.NEED_AGAIN)
                            }
                            if (earsReady || !connectedSocket.isConnected) break
                            Log.i(TAG, "Battery not ready; requesting initial status again")
                            manager.sendNotificationRequest()
                        }
                    }
                    // Begin receiving immediately after subscribing; no extra delay.
                    while (connectedSocket.isConnected) {
                        val buffer = ByteArray(1024)
                        val bytesRead = connectedSocket.inputStream.read(buffer)
                        ensureActive()
                        if (bytesRead == -1) break
                        if (bytesRead > 0) synchronized(this@L2CAPController) {
                            ensureActive()
                            if (sessionId != thisSession) throw CancellationException("Obsolete AirPods session")
                            manager.receivePacket(buffer.copyOfRange(0, bytesRead))
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "AirPods status session ended", e)
                } finally {
                    statusRequestJob?.cancel()
                    runCatching { connectedSocket.close() }
                    synchronized(this@L2CAPController) {
                        if (aacpManager === manager) aacpManager = null
                    }
                }
            } finally {
                finishSession(thisSession, context, device)
            }
        }
    }

    @Synchronized
    private fun finishSession(expectedSession: Long, context: Context, device: BluetoothDevice) {
        // Old coroutine completion must not tear down a newer connection.
        if (sessionId == expectedSession) disconnectedPod(context, device)
    }

    @Synchronized
    fun disconnectedPod(context: Context, device: BluetoothDevice) {
        if (::mDevice.isInitialized && device != mDevice) return
        ++sessionId
        speakerSwitchJob?.cancel()
        speakerSwitchJob = null
        switchedToSpeaker = false
        pendingAudioRoute = null
        routeRetryJob?.cancel()
        routeRetryJob = null
        routeRetryCount = 0
        connectionJob?.cancel()
        connectionJob = null
        socket?.let { runCatching { it.close() } }
        socket = null

        mContext?.let {
            runCatching { stopRoutesScan() }
            cancelPodsNotificationByMiuiBt(it, device)
            Intent(HyperPodsAction.ACTION_PODS_DISCONNECTED).apply {
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
    }

    fun sendPacket(packet: String) {
        val fromHex = packet.split(" ").map { it.toInt(16).toByte() }
        sendPacket(fromHex.toByteArray())
    }

    @Synchronized
    fun sendPacket(packet: ByteArray) {
        aacpManager?.sendPacket(packet)
    }

    fun setANCMode(mode: Int) {
        Log.d(TAG, "setANCMode: $mode")

        if (mode in 1..4) {
            aacpManager?.sendControlCommand(
                AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value,
                mode
            )
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
        routes.firstOrNull {
            it.type == MediaRoute2Info.TYPE_BLUETOOTH_A2DP && it.name == mDevice.name
        }?.let {
            requestAudioRoute(it)
        }
    }

    fun setLoudSoundReduction(enabled: Boolean) {
        val hex = "52 1B 00 0${if (enabled) "1" else "0"}"
        val bytes = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
        sendPacket(bytes)
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
