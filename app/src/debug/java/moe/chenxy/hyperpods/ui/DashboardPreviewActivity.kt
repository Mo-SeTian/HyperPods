package moe.chenxy.hyperpods.ui

import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import moe.chenxy.hyperpods.pods.EarDetectionStatus
import moe.chenxy.hyperpods.pods.NoiseControlMode
import moe.chenxy.hyperpods.utils.AACPManager
import moe.chenxy.hyperpods.utils.PodsSettings
import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey as Key
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.EarDetectionParams
import moe.chenxy.hyperpods.utils.data.PodBatteryParams
import moe.chenxy.hyperpods.ui.components.DashboardTopBar
import moe.chenxy.hyperpods.ui.components.DashboardNavigation
import top.yukonga.miuix.kmp.basic.*

/** Renders the production page with synthetic data, without Bluetooth or preference writes. */
class DashboardPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = intent.getIntExtra("theme", 1) == 2
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark })
        window.isNavigationBarContrastEnforced = false
        val inCase = intent.getBooleanExtra("in_case", false)
        val unknown = intent.getBooleanExtra("unknown", false)
        val caseConnected = intent.getBooleanExtra("case_connected", inCase)
        val leftBattery = intent.getIntExtra("left_battery", 92)
        setContent {
            AppTheme(colorMode = intent.getIntExtra("theme", 1)) {
                var anc by remember { mutableStateOf(NoiseControlMode.NOISE_CANCELLATION) }
                var personalized by remember { mutableStateOf(false) }
                var conversation by remember { mutableStateOf(false) }
                var detection by remember { mutableStateOf(true) }
                var speaker by remember { mutableStateOf(false) }
                var swipe by remember { mutableStateOf(false) }
                var single by remember { mutableStateOf(false) }
                var level by remember { mutableFloatStateOf(0.5f) }
                var microphone by remember { mutableIntStateOf(0) }
                var name by remember { mutableStateOf("AirPods Pro") }
                val pager = rememberPagerState(pageCount = { 2 })
                val scroll = MiuixScrollBehavior(rememberTopAppBarState())
                Scaffold(
                    topBar = { DashboardTopBar { onBackPressedDispatcher.onBackPressed() } },
                    bottomBar = { DashboardNavigation(pager) }
                ) { padding ->
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                        if (page == 1) AboutPage(scroll, padding) else PodDetailPage(
                            padding = padding,
                            batteryParams = BatteryParams(
                                PodBatteryParams(leftBattery, isConnected = !unknown, isInCase = inCase),
                                PodBatteryParams(87, isConnected = !unknown, isInCase = inCase),
                                PodBatteryParams(73, isConnected = caseConnected)),
                            earDetectionParams = EarDetectionParams(
                                if (inCase) EarDetectionStatus.IN_CASE else EarDetectionStatus.IN_EAR,
                                if (inCase) EarDetectionStatus.IN_CASE else EarDetectionStatus.IN_EAR),
                            earDetectionEnable = detection, onEarDetectionChanged = { detection = it },
                            autoSwitchToSpeaker = speaker, onAutoSwitchToSpeakerChange = { speaker = it },
                            personlizedVolume = personalized, onPersonlizedVolumeChange = { personalized = it },
                            conversationAwareness = conversation, onConversationAwarenessChange = { conversation = it },
                            adjustVolumeBySwiper = swipe, onAdjustVolumeBySwiperChange = { swipe = it },
                            adaptiveAudioLevel = level, onAdaptiveAudioLevelChange = { level = it },
                            onListeningModeChange = {}, ancMode = anc, onAncModeChange = { anc = it },
                            podsInfo = if (unknown) null else AACPManager.Companion.AirPodsInformation(
                                name, "A3049", "Apple Inc.", "DEMO-NOT-REAL", "0", "0", "1.0.0",
                                "DEMO", "DEMO-LEFT", "DEMO-RIGHT", "DEMO"),
                            onNameChange = { name = it }, microphoneMode = microphone,
                            onMicrophoneModeChange = { microphone = it },
                            noiseCancellationSingleAirPod = single, onNoiseCancellationSingleAirPodChange = { single = it },
                            deviceName = name,
                            isActive = pager.currentPage == 0,
                            settings = if (unknown) emptyMap() else mapOf(
                                PodsSettings.NOISE_MODE to anc.ordinal + 1,
                                Key.PERSONLIZED_VOLUME to if (personalized) 1 else 2,
                                Key.CONVERSATION_AWARENESS to if (conversation) 1 else 2,
                                Key.ADJUST_VOLUME_BY_SWIPER to if (swipe) 1 else 2,
                                Key.SINGLE_POD_ANC to if (single) 1 else 2,
                                Key.MICROPHONE_MODE to (PodsSettings.microphoneValue(microphone) ?: 0),
                                Key.ADAPTIVE_AUDIO_LEVEL to (100 - level * 100).toInt(),
                                Key.LISTENING_MODE_BYTE to 6,
                                Key.ALLOW_OFF_OPTION to 1,
                            ))
                    }
                }
            }
        }
    }
}
