package moe.chenxy.hyperpods.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.highcapable.yukihookapi.hook.factory.prefs
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import moe.chenxy.hyperpods.MainActivity
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.NoiseControlMode
import moe.chenxy.hyperpods.utils.AACPManager
import moe.chenxy.hyperpods.utils.HyperPodsBroadcasts
import moe.chenxy.hyperpods.utils.PodsSettings
import moe.chenxy.hyperpods.utils.LowBatterySettings
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.EarDetectionParams
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey
import moe.chenxy.hyperpods.ui.components.DashboardTopBar
import moe.chenxy.hyperpods.ui.components.DashboardNavigation
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme

var currentPodsInfo by mutableStateOf<AACPManager.Companion.AirPodsInformation?>(null)

fun sendBooleanSetting(context: Context, prefKey: String, value: Boolean) {
    sendPodsSetting(context, prefKey, if (value) 1 else 2)
}

fun sendPodsSetting(context: Context, prefKey: String, value: Int) {
    HyperPodsBroadcasts.send(context, Intent(HyperPodsAction.ACTION_PODS_SETTINGS_CHANGED)
        .putExtra("key", prefKey).putExtra("value", value), HyperPodsBroadcasts.BLUETOOTH)
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(FlowPreview::class)
@Composable
fun MainUI() {
    val topAppBarScrollBehavior0 = MiuixScrollBehavior(rememberTopAppBarState())
    val topAppBarScrollBehavior1 = MiuixScrollBehavior(rememberTopAppBarState())

    val topAppBarScrollBehaviorList = listOf(
        topAppBarScrollBehavior0, topAppBarScrollBehavior1
    )

    val pagerState = rememberPagerState(pageCount = { 2 })
    var targetPage by remember { mutableIntStateOf(pagerState.currentPage) }
    val coroutineScope = rememberCoroutineScope()

    val currentScrollBehavior = when (pagerState.currentPage) {
        0 -> topAppBarScrollBehaviorList[0]
        else -> topAppBarScrollBehaviorList[1]
    }

    val mainTitle = remember { mutableStateOf("") }
    val aboutTitle = stringResource(R.string.about_hyperpods)
    val currentTitle = when (pagerState.currentPage) {
        0 -> "HyperPods"
        else -> aboutTitle
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.debounce(150).collectLatest {
            targetPage = pagerState.currentPage
        }
    }
    val context = LocalContext.current

    val earDetectionEnable = remember { mutableStateOf(context.prefs().getBoolean(HyperPodsPrefsKey.EAR_DETECTION, true)) }
    val earDetectionParams = remember { mutableStateOf(EarDetectionParams((-1).toByte(), (-1).toByte())) }
    val batteryParams = remember { mutableStateOf(BatteryParams()) }
    val autoSwitchToSpeaker = remember { mutableStateOf(context.prefs().getBoolean(HyperPodsPrefsKey.EAR_DETECTION_SWITCH_SPEAKER, true)) }
    val settings = remember { mutableStateMapOf<String, Int>() }
    var pendingSettings by remember { mutableStateOf<Set<String>>(emptySet()) }
    val settingFeedback = remember { mutableStateMapOf<String, String>() }
    var conversationPhoneVolume by remember { mutableStateOf(context.prefs().getBoolean(HyperPodsPrefsKey.CONVERSATION_PHONE_VOLUME, true)) }
    var lowBatterySettings by remember { mutableStateOf(LowBatterySettings(
        context.prefs().getBoolean(HyperPodsPrefsKey.LOW_BATTERY_EARS, true),
        context.prefs().getInt(HyperPodsPrefsKey.LOW_BATTERY_EARS_THRESHOLD, 20),
        context.prefs().getBoolean(HyperPodsPrefsKey.LOW_BATTERY_CASE, true),
        context.prefs().getInt(HyperPodsPrefsKey.LOW_BATTERY_CASE_THRESHOLD, 20),
    ).takeIf { it.valid } ?: LowBatterySettings()) }
    var diagnostics by remember { mutableStateOf<Bundle?>(null) }
    val personlizedVolume = remember { mutableStateOf(false) }
    val adaptiveAudioLevel = remember { mutableFloatStateOf(0.5f) }
    val adjustVolumeBySwiper = remember { mutableStateOf(false) }
    val conversationAwareness = remember { mutableStateOf(false) }
    val noiseCancellationSingleAirPod = remember { mutableStateOf(false) }
    val canShowDetailPage = remember { mutableStateOf(false) }
    val ancMode = remember { mutableStateOf<NoiseControlMode?>(null) }
    val microphoneMode = remember { mutableStateOf(0) }

    DisposableEffect(context) {
        val broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, p1: Intent?) {
                if (!HyperPodsBroadcasts.isTrusted(context, this, HyperPodsBroadcasts.BLUETOOTH)) return
                when (p1?.action) {
                    HyperPodsAction.ACTION_PODS_DIAGNOSTICS -> {
                        diagnostics = p1.getBundleExtra("diagnostics")
                    }
                    HyperPodsAction.ACTION_PODS_ANC_CHANGED -> {
                        val status = p1.getIntExtra("status", 0)
                        if (status !in 1..NoiseControlMode.entries.size) return
                        ancMode.value =
                            NoiseControlMode.entries[status - 1]
                    }

                    HyperPodsAction.ACTION_EAR_DETECTION_STATUS_CHANGED -> {
                        earDetectionParams.value =
                            p1.getParcelableExtra("status", EarDetectionParams::class.java) ?: return
                    }

                    HyperPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                        batteryParams.value = p1.getParcelableExtra("status", BatteryParams::class.java) ?: return
                    }

                    HyperPodsAction.ACTION_PODS_CONNECTED -> {
                        val deviceInfo = p1.getParcelableExtra("device_info", AACPManager.Companion.AirPodsInformation::class.java)
                        val deviceName = p1.getStringExtra("device_name")
                        mainTitle.value = deviceInfo?.name ?: (deviceName ?: "")
                        canShowDetailPage.value = true
                        currentPodsInfo = deviceInfo
                    }

                    HyperPodsAction.ACTION_PODS_SETTINGS_STATE -> {
                        val bundle = p1.getBundleExtra("settings") ?: return
                        settings.clear()
                        bundle.keySet().filter { it in PodsSettings.identifiers }.forEach { settings[it] = bundle.getInt(it) }
                        pendingSettings = p1.getStringArrayListExtra("pending")?.toSet().orEmpty()
                        val unconfirmed = p1.getStringArrayListExtra("unconfirmed")?.toSet().orEmpty()
                        settings.keys.forEach { key ->
                            if (settingFeedback[key] !in listOf("write_failed", "timeout", "invalid"))
                                settingFeedback[key] = if (key in unconfirmed || key in pendingSettings) "sent" else "reported"
                        }
                        personlizedVolume.value = settings[HyperPodsPrefsKey.PERSONLIZED_VOLUME] == 1
                        conversationAwareness.value = settings[HyperPodsPrefsKey.CONVERSATION_AWARENESS] == 1
                        adjustVolumeBySwiper.value = settings[HyperPodsPrefsKey.ADJUST_VOLUME_BY_SWIPER] == 1
                        noiseCancellationSingleAirPod.value = settings[HyperPodsPrefsKey.SINGLE_POD_ANC] == 1
                        adaptiveAudioLevel.floatValue = (100 - (settings[HyperPodsPrefsKey.ADAPTIVE_AUDIO_LEVEL] ?: 50)) / 100f
                        microphoneMode.value = PodsSettings.microphoneIndex(settings[HyperPodsPrefsKey.MICROPHONE_MODE]) ?: 0
                        ancMode.value = settings[PodsSettings.NOISE_MODE]?.let { NoiseControlMode.entries.getOrNull(it - 1) }
                    }

                    HyperPodsAction.ACTION_PODS_SETTING_RESULT -> {
                        val key = p1.getStringExtra("key") ?: return
                        val status = p1.getStringExtra("status") ?: return
                        if (key in PodsSettings.identifiers || key == PodsSettings.RENAME) settingFeedback[key] = status
                        if (!p1.getBooleanExtra("pending", false)) {
                            val success = p1.getBooleanExtra("success", false)
                            if (!success || status == "confirmed" && key != HyperPodsPrefsKey.ALLOW_OFF_OPTION) {
                                val message = when {
                                    status == "write_failed" -> R.string.setting_write_failed
                                    status == "invalid" -> R.string.setting_invalid
                                    !success -> R.string.setting_failed
                                    key == PodsSettings.RENAME -> R.string.setting_applied
                                    else -> R.string.setting_confirmed
                                }
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }

                    HyperPodsAction.ACTION_PODS_DISCONNECTED -> {
                        mainTitle.value = ""
                        canShowDetailPage.value = false
                        settings.clear()
                        pendingSettings = emptySet()
                        settingFeedback.clear()
                        if (p0 is MainActivity && !p1.getBooleanExtra("initialization_failed", false)) {
                            p0.finish()
                        }
                    }
                }
            }
        }

        context.registerReceiver(broadcastReceiver, IntentFilter().apply {
            this.addAction(HyperPodsAction.ACTION_PODS_ANC_CHANGED)
            this.addAction(HyperPodsAction.ACTION_EAR_DETECTION_STATUS_CHANGED)
            this.addAction(HyperPodsAction.ACTION_PODS_BATTERY_CHANGED)
            this.addAction(HyperPodsAction.ACTION_PODS_CONNECTED)
            this.addAction(HyperPodsAction.ACTION_PODS_DISCONNECTED)
            this.addAction(HyperPodsAction.ACTION_PODS_SETTINGS_STATE)
            this.addAction(HyperPodsAction.ACTION_PODS_SETTING_RESULT)
            this.addAction(HyperPodsAction.ACTION_PODS_DIAGNOSTICS)
        }, Context.RECEIVER_EXPORTED)

        HyperPodsBroadcasts.send(context, Intent(HyperPodsAction.ACTION_PODS_UI_INIT), HyperPodsBroadcasts.BLUETOOTH)
        onDispose {
            context.unregisterReceiver(broadcastReceiver)
            currentPodsInfo = null
        }
    }

    fun setAncMode(mode: NoiseControlMode) {
        Intent(HyperPodsAction.ACTION_ANC_SELECT).apply {
            this.putExtra("status", mode.ordinal + 1)
            HyperPodsBroadcasts.send(context, this, HyperPodsBroadcasts.BLUETOOTH)
        }
    }

    fun setEarDetection(main: Boolean, disconnect: Boolean) {
        context.prefs().edit {
            putBoolean(HyperPodsPrefsKey.EAR_DETECTION, main)
            putBoolean(HyperPodsPrefsKey.EAR_DETECTION_SWITCH_SPEAKER, disconnect)
        }
        // Deliver the new policy directly; preference persistence may finish after the broadcast.
        HyperPodsBroadcasts.send(context, Intent(HyperPodsAction.ACTION_PODS_SETTINGS_CHANGED)
            .putExtra("key", HyperPodsPrefsKey.EAR_DETECTION)
            .putExtra("ear_detection", main).putExtra("switch_speaker", disconnect), HyperPodsBroadcasts.BLUETOOTH)
    }

    fun renameAirPods(it: String) {
        HyperPodsBroadcasts.send(context, Intent(HyperPodsAction.ACTION_PODS_RENAME)
            .putExtra("name", it), HyperPodsBroadcasts.BLUETOOTH)
    }

    fun onMicrophoneModeChange(it: Int) {
        PodsSettings.microphoneValue(it)?.let { value -> sendPodsSetting(context, HyperPodsPrefsKey.MICROPHONE_MODE, value) }
    }

    fun onListeningModeChange(it: Byte) {
        sendPodsSetting(context, HyperPodsPrefsKey.LISTENING_MODE_BYTE, it.toInt() and 0xff)
    }

    fun onNoiseCancellationSingleAirPodChange(it: Boolean) {
        sendBooleanSetting(context, HyperPodsPrefsKey.SINGLE_POD_ANC, it)
    }

    val hazeState = remember { HazeState() }
    val hazeStyle = HazeStyle(
        backgroundColor = if (currentScrollBehavior.state.heightOffset > -1) Color.Transparent else MiuixTheme.colorScheme.background,
        tint = HazeTint(
            MiuixTheme.colorScheme.background.copy(
                if (currentScrollBehavior.state.heightOffset > -1) 1f
                else lerp(1f, 0.85f, (currentScrollBehavior.state.heightOffset + 1) / -143f)
            )
        )
    )
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            BoxWithConstraints {
                if (pagerState.currentPage == 0) {
                    DashboardTopBar { (context as? MainActivity)?.onBackPressedDispatcher?.onBackPressed() }
                } else if (maxWidth > 840.dp) {
                    SmallTopAppBar(
                        color = Color.Transparent,
                        title = currentTitle,
                        modifier = Modifier
                            .hazeEffect(
                                hazeState
                            ) {
                                style = hazeStyle
                                blurRadius = 25.dp
                                noiseFactor = 0f
                                progressive = HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0.5f)
                            },
                        scrollBehavior = currentScrollBehavior
                    )
                } else {
                    TopAppBar(
                        color = Color.Transparent,
                        title = currentTitle,
                        scrollBehavior = currentScrollBehavior,
                        modifier = Modifier
                            .hazeEffect(
                                hazeState
                            ) {
                                style = hazeStyle
                                blurRadius = 25.dp
                                noiseFactor = 0f
                                progressive = HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f)
                            }
                    )
                }
            }
        },
        bottomBar = {
            DashboardNavigation(
                selected = targetPage,
                onSelect = { index ->
                    if (index in 0..1) {
                        targetPage = index
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(index)
                        }
                    }
                }
            )
        },
    ) { padding ->
        AppHorizontalPager(
            modifier = Modifier
                .imePadding()
                .hazeSource(state = hazeState),
            pagerState = pagerState,
            topAppBarScrollBehaviorList = topAppBarScrollBehaviorList,
            padding = padding,
            canShowDetailPage = canShowDetailPage.value,
            deviceName = mainTitle.value,
            batteryParams = batteryParams.value,
            earDetectionParams = earDetectionParams.value,
            earDetectionEnable = earDetectionEnable.value,
            onEarDetectionChanged = {
                earDetectionEnable.value = it
                setEarDetection(it, autoSwitchToSpeaker.value)
            },
            autoSwitchToSpeaker = autoSwitchToSpeaker.value,
            onAutoSwitchToSpeakerChange = {
                autoSwitchToSpeaker.value = it
                setEarDetection(earDetectionEnable.value, it)
            },
            personlizedVolume = personlizedVolume.value,
            onPersonlizedVolumeChange = {
                sendBooleanSetting(context, HyperPodsPrefsKey.PERSONLIZED_VOLUME, it)
            },
            conversationAwareness = conversationAwareness.value,
            onConversationAwarenessChange = {
                sendBooleanSetting(context, HyperPodsPrefsKey.CONVERSATION_AWARENESS, it)
            },
            adjustVolumeBySwiper = adjustVolumeBySwiper.value,
            onAdjustVolumeBySwiperChange = {
                sendBooleanSetting(context, HyperPodsPrefsKey.ADJUST_VOLUME_BY_SWIPER, it)
            },
            adaptiveAudioLevel = adaptiveAudioLevel.floatValue,
            onAdaptiveAudioLevelChange = {
                sendPodsSetting(context, HyperPodsPrefsKey.ADAPTIVE_AUDIO_LEVEL, (100 - it * 100).toInt())
            },
            ancMode = ancMode.value,
            onAncModeChange = {
                setAncMode(it)
            },
            onNameChange = {
                renameAirPods(it)
            },
            microphoneMode = microphoneMode.value,
            onMicrophoneModeChange = {
                onMicrophoneModeChange(it)
            },
            onListeningModeChange = {
                onListeningModeChange(it)
            },
            onNoiseCancellationSingleAirPodChange = {
                onNoiseCancellationSingleAirPodChange(it)
            },
            noiseCancellationSingleAirPod = noiseCancellationSingleAirPod.value,
            settings = settings.toMap(), pendingSettings = pendingSettings,
            settingFeedback = settingFeedback.toMap(),
            lowBatterySettings = lowBatterySettings,
            onLowBatterySettingsChange = {
                lowBatterySettings = it
                context.prefs().edit {
                    putBoolean(HyperPodsPrefsKey.LOW_BATTERY_EARS, it.earsEnabled)
                    putInt(HyperPodsPrefsKey.LOW_BATTERY_EARS_THRESHOLD, it.earsThreshold)
                    putBoolean(HyperPodsPrefsKey.LOW_BATTERY_CASE, it.caseEnabled)
                    putInt(HyperPodsPrefsKey.LOW_BATTERY_CASE_THRESHOLD, it.caseThreshold)
                }
                HyperPodsBroadcasts.send(context, Intent(HyperPodsAction.ACTION_PODS_SETTINGS_CHANGED)
                    .putExtra("key", HyperPodsPrefsKey.LOW_BATTERY_EARS).putExtra("lowBatterySettings", it), HyperPodsBroadcasts.BLUETOOTH)
            },
            conversationPhoneVolume = conversationPhoneVolume,
            onConversationPhoneVolumeChange = {
                conversationPhoneVolume = it
                context.prefs().edit { putBoolean(HyperPodsPrefsKey.CONVERSATION_PHONE_VOLUME, it) }
                HyperPodsBroadcasts.send(context, Intent(HyperPodsAction.ACTION_PODS_SETTINGS_CHANGED)
                    .putExtra("key", HyperPodsPrefsKey.CONVERSATION_PHONE_VOLUME).putExtra("enabled", it), HyperPodsBroadcasts.BLUETOOTH)
            },
            onAllowOffChange = { sendBooleanSetting(context, HyperPodsPrefsKey.ALLOW_OFF_OPTION, it) },
            onSettingChange = { key, value -> sendPodsSetting(context, key, value) },
            diagnostics = diagnostics,
        )
    }
}

@Composable
fun AppHorizontalPager(
    modifier: Modifier = Modifier,
    pagerState: PagerState,
    topAppBarScrollBehaviorList: List<ScrollBehavior>,
    padding: PaddingValues,
    canShowDetailPage: Boolean,
    batteryParams: BatteryParams,
    earDetectionParams: EarDetectionParams,
    earDetectionEnable: Boolean,
    onEarDetectionChanged: (Boolean) -> Unit,
    autoSwitchToSpeaker: Boolean,
    onAutoSwitchToSpeakerChange: (Boolean) -> Unit,
    personlizedVolume: Boolean,
    onPersonlizedVolumeChange: (Boolean) -> Unit,
    conversationAwareness: Boolean,
    onConversationAwarenessChange: (Boolean) -> Unit,
    adjustVolumeBySwiper: Boolean,
    onAdjustVolumeBySwiperChange: (Boolean) -> Unit,
    adaptiveAudioLevel: Float,
    onAdaptiveAudioLevelChange: (Float) -> Unit,
    ancMode: NoiseControlMode?,
    onAncModeChange: (NoiseControlMode) -> Unit,
    onNameChange: (String) -> Unit,
    microphoneMode: Int,
    onMicrophoneModeChange: (Int) -> Unit,
    onListeningModeChange: (Byte) -> Unit,
    onNoiseCancellationSingleAirPodChange: (Boolean) -> Unit,
    noiseCancellationSingleAirPod: Boolean,
    deviceName: String = "",
    settings: Map<String, Int> = emptyMap(),
    pendingSettings: Set<String> = emptySet(),
    settingFeedback: Map<String, String> = emptyMap(),
    lowBatterySettings: LowBatterySettings = LowBatterySettings(),
    onLowBatterySettingsChange: (LowBatterySettings) -> Unit = {},
    conversationPhoneVolume: Boolean = true,
    onConversationPhoneVolumeChange: (Boolean) -> Unit = {},
    onAllowOffChange: (Boolean) -> Unit = {},
    onSettingChange: (String, Int) -> Unit = { _, _ -> },
    diagnostics: Bundle? = null,
) {
    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        pageContent = { page ->
            when (page) {
                0 -> Crossfade(canShowDetailPage, label = "MainUIShowDetailAnim") { value ->
                        if (value) {
                            PodDetailPage(
                                settings = settings, pendingSettings = pendingSettings, onAllowOffChange = onAllowOffChange,
                                settingFeedback = settingFeedback, conversationPhoneVolume = conversationPhoneVolume,
                                lowBatterySettings = lowBatterySettings, onLowBatterySettingsChange = onLowBatterySettingsChange,
                                onConversationPhoneVolumeChange = onConversationPhoneVolumeChange,
                                onSettingChange = onSettingChange, diagnostics = diagnostics,
                                padding = padding,
                                batteryParams = batteryParams,
                                earDetectionParams = earDetectionParams,
                                earDetectionEnable = earDetectionEnable,
                                onEarDetectionChanged = onEarDetectionChanged,
                                autoSwitchToSpeaker = autoSwitchToSpeaker,
                                onAutoSwitchToSpeakerChange = onAutoSwitchToSpeakerChange,
                                personlizedVolume = personlizedVolume,
                                onPersonlizedVolumeChange = onPersonlizedVolumeChange,
                                conversationAwareness = conversationAwareness,
                                onConversationAwarenessChange = onConversationAwarenessChange,
                                adjustVolumeBySwiper = adjustVolumeBySwiper,
                                onAdjustVolumeBySwiperChange = onAdjustVolumeBySwiperChange,
                                adaptiveAudioLevel = adaptiveAudioLevel,
                                onAdaptiveAudioLevelChange = onAdaptiveAudioLevelChange,
                                ancMode = ancMode,
                                onAncModeChange = onAncModeChange,
                                podsInfo = currentPodsInfo,
                                deviceName = deviceName,
                                isActive = pagerState.currentPage == 0,
                                onNameChange = onNameChange,
                                microphoneMode = microphoneMode,
                                onMicrophoneModeChange = onMicrophoneModeChange,
                                onListeningModeChange = onListeningModeChange,
                                onNoiseCancellationSingleAirPodChange = onNoiseCancellationSingleAirPodChange,
                                noiseCancellationSingleAirPod = noiseCancellationSingleAirPod,
                            )
                        } else {
                            WaitingPodsPage()
                        }
                    }

                1 -> AboutPage(
                    diagnostics = diagnostics,
                    topAppBarScrollBehavior = topAppBarScrollBehaviorList[1],
                    padding = padding
                )
            }
        }
    )
}

//@Composable
//@Preview
//fun PodDetailPreview() {
//    val ancMode = remember { mutableStateOf(NoiseControlMode.OFF) }
//    val earDetectionEnable = remember { mutableStateOf(true) }
//    val autoSwitchToSpeaker = remember { mutableStateOf(true) }
//    val earDetectionParams = remember { mutableStateOf(EarDetectionParams()) }
//    val batteryParams = remember { mutableStateOf(BatteryParams()) }
//    AppTheme {
//        Scaffold {
//            PodDetailPage(
//                topAppBarScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState()),
//                padding = PaddingValues(),
//                earDetectionParams = earDetectionParams.value,
//                batteryParams = batteryParams.value,
//                earDetectionEnable = earDetectionEnable.value,
//                onEarDetectionChanged = { earDetectionEnable.value = it },
//                autoSwitchToSpeaker = autoSwitchToSpeaker.value,
//                onAutoSwitchToSpeakerChange = { autoSwitchToSpeaker.value = it },
//                ancMode = ancMode.value,
//                onAncModeChange = { ancMode.value = it },
//            )
//        }
//    }
//}

@Composable
fun Dp.dpToPx() = with(LocalDensity.current) { this@dpToPx.toPx() }


@Composable
fun Int.pxToDp() = with(LocalDensity.current) { this@pxToDp.toDp() }

@Composable
fun Float.pxToDp() = with(LocalDensity.current) { this@pxToDp.toDp() }
