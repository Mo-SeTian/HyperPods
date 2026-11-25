package moe.chenxy.hyperpods.ui

import android.os.Bundle
import android.content.Intent
import androidx.compose.foundation.layout.*
import moe.chenxy.hyperpods.ui.components.*
import top.yukonga.miuix.kmp.icon.icons.useful.*
import androidx.compose.ui.platform.LocalContext
import moe.chenxy.hyperpods.utils.HyperPodsBroadcasts
import moe.chenxy.hyperpods.utils.data.HyperPodsAction

import androidx.compose.animation.AnimatedContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable

import androidx.compose.foundation.background
import androidx.compose.foundation.progressSemantics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.NoiseControlMode
import androidx.compose.runtime.key

import moe.chenxy.hyperpods.ui.components.DashboardLink
import moe.chenxy.hyperpods.ui.components.DashboardToggle
import moe.chenxy.hyperpods.ui.components.DashboardGroup as Card
import moe.chenxy.hyperpods.ui.components.PressAndHoldSettingPage
import moe.chenxy.hyperpods.ui.components.headGestureStatus
import moe.chenxy.hyperpods.ui.components.HeadTrackingPage
import moe.chenxy.hyperpods.ui.components.EqualizerPage
import moe.chenxy.hyperpods.utils.AACPManager
import moe.chenxy.hyperpods.utils.CustomEq
import moe.chenxy.hyperpods.utils.CustomEqState
import moe.chenxy.hyperpods.utils.AirPodsModels.getModelByModelNumber
import moe.chenxy.hyperpods.utils.Capability
import moe.chenxy.hyperpods.utils.PodsSettings
import moe.chenxy.hyperpods.utils.LowBatterySettings
import moe.chenxy.hyperpods.utils.LowBatteryReminder
import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey as Key
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.EarDetectionParams
import top.yukonga.miuix.kmp.basic.Slider
import moe.chenxy.hyperpods.ui.components.MinimalDropdown
import moe.chenxy.hyperpods.ui.components.MinimalToggle

@Composable
fun PodDetailPage(
    padding: PaddingValues,
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
    onListeningModeChange: (Byte) -> Unit,
    ancMode: NoiseControlMode?,
    onAncModeChange: (NoiseControlMode) -> Unit,
    podsInfo: AACPManager.Companion.AirPodsInformation?,
    onNameChange: (String) -> Unit,
    microphoneMode: Int,
    onMicrophoneModeChange: (Int) -> Unit,
    noiseCancellationSingleAirPod: Boolean,
    onNoiseCancellationSingleAirPodChange: (Boolean) -> Unit,
    deviceName: String = "",
    isActive: Boolean = true,
    settings: Map<String, Int> = emptyMap(),
    pendingSettings: Set<String> = emptySet(),
    settingFeedback: Map<String, String> = emptyMap(),
    lowBatterySettings: LowBatterySettings = LowBatterySettings(),
    onLowBatterySettingsChange: (LowBatterySettings) -> Unit = {},
    conversationPhoneVolume: Boolean = true,
    onConversationPhoneVolumeChange: (Boolean) -> Unit = {},
    conversationPauseMedia: Boolean = false,
    onConversationPauseMediaChange: (Boolean) -> Unit = {},
    headGestures: Boolean = false,
    onHeadGesturesChange: (Boolean) -> Unit = {},
    onAllowOffChange: (Boolean) -> Unit = {},
    onSettingChange: (String, Int) -> Unit = { _, _ -> },
    diagnostics: Bundle? = null,
    customEqState: CustomEqState = CustomEqState(),
    onCustomEqChange: (CustomEq, Long) -> Unit = { _, _ -> },
    homeNotice: (@Composable () -> Unit)? = null,
    homeRequested: Boolean = false,
    onHomeOpened: () -> Unit = {},
) {
    val model = podsInfo?.let { getModelByModelNumber(it.modelNumber) }
    val context = LocalContext.current
    val foreground = rememberUiForeground {}
    val connection = diagnostics?.getString("connection")
    val controlsBlocked = diagnostics?.getString("recovery").orEmpty().isNotEmpty()
    // Explicit native strength writes do not require an earlier firmware echo.
    // The controller still checks the model, range, socket and recovery state.
    fun ready(key: String) = foreground && isActive && connection != "disconnected" && connection != "failed" && !controlsBlocked && PodsSettings.supports(key, model) && key !in pendingSettings &&
        (key in settings || (key == Key.MICROPHONE_MODE || key == Key.LISTENING_MODE_BYTE || key == Key.ADAPTIVE_AUDIO_LEVEL) &&
            diagnostics?.getString("connection") == "connected")
    @Composable
    fun statusText(key: String): String? {
        val status = when (settingFeedback[key]) {
            // Sent-only controls already settled locally. Keep the unverified
            // state in diagnostics, not as an endless waiting label on each row.
            "sent" -> if (key in pendingSettings) R.string.setting_pending else return null
            "interrupted" -> R.string.control_recovery_interrupted
            "write_failed" -> R.string.setting_write_failed
            "timeout" -> R.string.setting_failed
            "invalid" -> R.string.setting_invalid
            else -> return null
        }
        return stringResource(status)
    }
    @Composable
    fun feedback(key: String, summary: Int): String {
        if (key !in settings) return stringResource(R.string.settings_reading)
        return stringResource(summary) + (statusText(key)?.let { "\n$it" } ?: "")
    }
    val allowedModes = PodsSettings.allowedNoiseModes(model, settings)
    val eqAvailable = CustomEq.available(model, podsInfo?.version3.orEmpty(), customEqState.value != null)
    var chimeDraft by remember(settings[Key.CHIME_VOLUME], settingFeedback[Key.CHIME_VOLUME]) {
        mutableFloatStateOf((settings[Key.CHIME_VOLUME] ?: 0).toFloat())
    }
    var detailPage by rememberSaveable { mutableIntStateOf(0) }
    var equalizerParent by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(homeRequested, isActive) {
        if (homeRequested && isActive) {
            detailPage = 0
            onHomeOpened()
        }
    }
    fun back() { detailPage = when (detailPage) { 4 -> 9; 7, 8, 9, 10 -> 1; 5 -> equalizerParent; else -> 0 } }
    BackHandler(enabled = isActive && detailPage != 0) { back() }
    // Keep every page's saveable state outside the animated content. Switching the
    // content key must not discard the previous page's scroll position.
    val listStates = listOf(rememberLazyListState(), rememberLazyListState(),
        rememberLazyListState(), rememberLazyListState(), rememberLazyListState(), rememberLazyListState(),
        rememberLazyListState(), rememberLazyListState(), rememberLazyListState(), rememberLazyListState(), rememberLazyListState())
    val listeningAvailable = foreground && isActive && !controlsBlocked && connection != "disconnected" && connection != "failed"
    val listening = rememberListeningCommit(customEqState.session, ancMode, PodsSettings.supports(Key.ADAPTIVE_AUDIO_LEVEL, model),
        listeningAvailable, ready(PodsSettings.NOISE_MODE), ready(Key.ADAPTIVE_AUDIO_LEVEL),
        PodsSettings.NOISE_MODE in pendingSettings && settingFeedback[PodsSettings.NOISE_MODE] == "sent",
        settingFeedback[PodsSettings.NOISE_MODE], onAncModeChange, onAdaptiveAudioLevelChange)
    val travel = with(LocalDensity.current) { 28.dp.roundToPx() }
    AnimatedContent(
        targetState = detailPage,
        modifier = Modifier.fillMaxSize().clipToBounds(),
        transitionSpec = {
            fun depth(page: Int) = when (page) {
                0 -> 0
                1 -> 1
                4 -> 3 // More → Head gestures → Pose test
                5 -> if (equalizerParent == 8) 3 else 1
                7, 8, 9, 10 -> 2
                else -> 1
            }
            settingsPageTransition(depth(targetState) > depth(initialState), travel).using(null)
        },
        label = "SettingsPage",
        contentAlignment = Alignment.TopCenter,
    ) { page ->
        val pageTitle = stringResource(when (page) {
            1 -> R.string.fidelity_more
            2 -> R.string.dashboard_device_info
            3 -> R.string.diagnostics_title
            4 -> R.string.head_test_title
            5 -> R.string.eq_title
            6 -> R.string.diagnostics_logs
            7 -> R.string.redesign_press
            8 -> R.string.redesign_audio
            9 -> R.string.head_gestures_title
            10 -> R.string.battery_reminder_title
            else -> R.string.dashboard_headphones
        })
        val interaction = if (!isActive) Modifier.clearAndSetSemantics {}
            else if (page == detailPage) Modifier.semantics { paneTitle = pageTitle }
            else Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                // The outgoing page remains visible briefly, but must not trigger
                // stale setting writes or duplicate accessibility actions.
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        Box(Modifier.fillMaxSize().background(Fidelity.surface()), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxSize().padding(padding)
            .consumeWindowInsets(padding).clipToBounds().then(interaction),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (page != 0) FidelityTopBar(pageTitle) { back() }
            if (controlsBlocked) top.yukonga.miuix.kmp.basic.BasicComponent(
                title = stringResource(if (diagnostics?.getString("recovery") == "recovering")
                    R.string.control_recovering else R.string.control_recovery_failed),
                summary = stringResource(R.string.control_recovery_retry_hint),
                onClick = {
                    HyperPodsBroadcasts.send(context, Intent(HyperPodsAction.ACTION_PODS_STATUS_RETRY),
                        HyperPodsBroadcasts.BLUETOOTH)
                },
            )
            LazyColumn(
                state = listStates[page],
                // Keep the scroll/focus viewport between the fixed bars. Item-only
                // padding lets bring-into-view place a clickable row under a bar.
                modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds()
                    .background(if (page == 0) Fidelity.surface() else androidx.compose.ui.graphics.Color.Transparent)
                    .focusProperties { canFocus = page == detailPage && isActive },
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (page == 0) {
                    item(key = "home_header") {
                        homeNotice?.invoke()
                        DeviceHero(podsInfo?.name ?: deviceName.ifBlank { "AirPods" }, model, batteryParams,
                            earDetectionParams, diagnostics, controlsBlocked || PodsSettings.RENAME in pendingSettings, onNameChange)
                    }
                    if (PodsSettings.supports(PodsSettings.NOISE_MODE, model)) item(key = "home_noise") {
                        key(customEqState.session) {
                        ListeningSlider(ancMode, adaptiveAudioLevel, PodsSettings.supports(Key.ADAPTIVE_AUDIO_LEVEL, model),
                            isActive && page == detailPage && ready(PodsSettings.NOISE_MODE) && !listening.waitingForAdaptive && Key.ADAPTIVE_AUDIO_LEVEL !in pendingSettings,
                            settingFeedback[PodsSettings.NOISE_MODE],
                            listening.apply,
                            available = page == detailPage && listeningAvailable,
                            strengthFeedback = settingFeedback[Key.ADAPTIVE_AUDIO_LEVEL])
                        }
                    }
                    item(key = "home_quick") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                            if (PodsSettings.supports(Key.PERSONLIZED_VOLUME, model)) {
                                MinimalToggle(stringResource(R.string.personlized_volume_title),
                                    feedback(Key.PERSONLIZED_VOLUME, R.string.minimal_personalized_summary),
                                    personlizedVolume, ready(Key.PERSONLIZED_VOLUME), onPersonlizedVolumeChange)
                                FidelityDivider()
                            }
                            if (PodsSettings.supports(Key.CONVERSATION_AWARENESS, model)) {
                                MinimalToggle(stringResource(R.string.dashboard_conversation_title),
                                    feedback(Key.CONVERSATION_AWARENESS, R.string.minimal_conversation_summary),
                                    conversationAwareness, ready(Key.CONVERSATION_AWARENESS), onConversationAwarenessChange)
                                FidelityDivider()
                            }
                            MinimalToggle(stringResource(R.string.fidelity_detection), stringResource(R.string.minimal_detection_summary),
                                earDetectionEnable, onCheckedChange = onEarDetectionChanged)
                        }
                    }
                    if (CustomEq.supportsModel(model) || customEqState.value != null) item(key = "home_eq") {
                        Column(Modifier.padding(horizontal = 24.dp)) {
                            FidelityDivider()
                            MinimalLink(stringResource(R.string.eq_title), stringResource(R.string.minimal_eq_summary), value = stringResource(when {
                                !eqAvailable && !podsInfo?.version3.isNullOrBlank() -> R.string.eq_update_required
                                customEqState.value?.state == CustomEq.RECOMMENDED -> R.string.eq_recommended
                                customEqState.value?.state == CustomEq.CUSTOM -> R.string.eq_custom
                                else -> R.string.settings_reading
                            })) { equalizerParent = 0; detailPage = 5 }
                            FidelityDivider()
                        }
                    }
                    item(key = "home_more") {
                        Column(Modifier.padding(horizontal = 24.dp)) {
                            if (!CustomEq.supportsModel(model) && customEqState.value == null) FidelityDivider()
                            MinimalLink(stringResource(R.string.fidelity_more), stringResource(R.string.minimal_more_summary)) { detailPage = 1 }
                        }
                    }
                    return@LazyColumn
                }
                if (page == 5) {
                    item(key = "equalizer") {
                        EqualizerPage(customEqState,
                            firmware = podsInfo?.version3.orEmpty(), available = eqAvailable, model = model,
                            active = foreground && !controlsBlocked && isActive && page == detailPage && customEqState.session > 0 && diagnostics?.getString("connection") == "connected",
                            feedback = settingFeedback[PodsSettings.CUSTOM_EQ], onChange = onCustomEqChange)
                    }
                    return@LazyColumn
                }
                if (page == 4) {
                    item(key = "head_tracking") {
                        HeadTrackingPage(isActive && page == detailPage)
                    }
                    return@LazyColumn
                }
                val cardModifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
                val titleModifier = Modifier.padding(top = 12.dp)

                if (page == 1 && PodsSettings.supports(Key.CONVERSATION_AWARENESS, model)) item(key = "more_conversation") {
                    MinimalSection(stringResource(R.string.dashboard_conversation_title)) {
                        MinimalToggle(stringResource(R.string.conversation_pause_media_title), stringResource(R.string.minimal_pause_summary),
                            conversationPauseMedia, conversationAwareness, onConversationPauseMediaChange)
                        FidelityDivider()
                        MinimalToggle(stringResource(R.string.conversation_phone_volume_title),
                            stringResource(if (conversationPauseMedia) R.string.minimal_phone_paused else R.string.minimal_phone_summary),
                            conversationPhoneVolume, conversationAwareness && !conversationPauseMedia, onConversationPhoneVolumeChange)
                    }
                }
                if (page == 1) item(key = "more_primary_settings") {
                    MinimalSection(stringResource(R.string.minimal_wear_controls)) {
                        if (PodsSettings.supports(Key.SINGLE_POD_ANC, model)) {
                            MinimalToggle(stringResource(R.string.fidelity_single_anc), feedback(Key.SINGLE_POD_ANC, R.string.fidelity_single_anc_summary),
                                noiseCancellationSingleAirPod, ready(Key.SINGLE_POD_ANC), onNoiseCancellationSingleAirPodChange)
                            FidelityDivider()
                        }
                        if (PodsSettings.supports(Key.ADJUST_VOLUME_BY_SWIPER, model)) {
                            MinimalToggle(stringResource(R.string.adjust_volume_by_swiper_title), feedback(Key.ADJUST_VOLUME_BY_SWIPER, R.string.minimal_swipe_summary),
                                adjustVolumeBySwiper, ready(Key.ADJUST_VOLUME_BY_SWIPER), onAdjustVolumeBySwiperChange)
                            FidelityDivider()
                        }
                        listOf(Key.PRESS_SPEED to R.string.control_press_speed, Key.HOLD_DURATION to R.string.control_hold_duration).forEach { (key, title) ->
                            if (PodsSettings.supports(key, model)) {
                                MinimalDropdown(stringResource(title), listOf(stringResource(R.string.control_default),
                                    stringResource(if (key == Key.PRESS_SPEED) R.string.control_slower else R.string.control_longer),
                                    stringResource(if (key == Key.PRESS_SPEED) R.string.control_slowest else R.string.control_longest)),
                                    settings[key] ?: -1, ready(key), summary = statusText(key),
                                    onSelectedIndexChange = { onSettingChange(key, it) })
                                FidelityDivider()
                            }
                        }
                        FidelityMicrophoneRow(stringResource(R.string.microphone), listOf(stringResource(R.string.microphone_auto),
                            stringResource(R.string.microphone_left), stringResource(R.string.microphone_right)),
                            if (Key.MICROPHONE_MODE in settings) microphoneMode else -1, ready(Key.MICROPHONE_MODE), onMicrophoneModeChange)
                        if (earDetectionEnable) {
                            FidelityDivider()
                            MinimalToggle(stringResource(R.string.fidelity_speaker), stringResource(R.string.fidelity_speaker_summary),
                                autoSwitchToSpeaker, onCheckedChange = onAutoSwitchToSpeakerChange)
                        }
                    }
                }
                if (page == 1) item(key = "more_automation") {
                    MinimalSection(stringResource(R.string.minimal_automation)) {
                        if (PodsSettings.supports(Key.SLEEP_DETECTION, model)) {
                            MinimalToggle(stringResource(R.string.fidelity_sleep), feedback(Key.SLEEP_DETECTION, R.string.fidelity_sleep_summary),
                                settings[Key.SLEEP_DETECTION] == 1,
                                !controlsBlocked && Key.SLEEP_DETECTION !in pendingSettings &&
                                    (Key.SLEEP_DETECTION in settings || diagnostics?.getString("connection") == "connected"),
                                { onSettingChange(Key.SLEEP_DETECTION, if (it) 1 else 2) })
                            FidelityDivider()
                        }
                        MinimalLink(stringResource(R.string.battery_reminder_title), stringResource(R.string.redesign_battery_summary)) { detailPage = 10 }
                    }
                }
                if (page == 1) item(key = "more_shortcuts") {
                    MinimalSection(stringResource(R.string.fidelity_function_settings)) {
                        if (listOf(Key.LISTENING_MODE_BYTE, Key.PRESS_SPEED, Key.HOLD_DURATION, Key.SWIPE_SPEED)
                            .any { PodsSettings.supports(it, model) }) {
                            MinimalLink(stringResource(R.string.redesign_press), stringResource(R.string.redesign_press_summary)) { detailPage = 7 }
                            FidelityDivider()
                        }
                        MinimalLink(stringResource(R.string.redesign_audio), stringResource(R.string.redesign_audio_summary)) { detailPage = 8 }
                        if (Capability.HEAD_GESTURES in model?.capabilities.orEmpty()) {
                            FidelityDivider()
                            MinimalLink(stringResource(R.string.head_gestures_title), stringResource(R.string.fidelity_head_summary)) { detailPage = 9 }
                        }
                    }
                }
                if (page == 9) {
                    item(key = "more_gestures_sleep") {
                        FidelitySection(stringResource(R.string.dashboard_operation), modifier = titleModifier)
                        Card(modifier = cardModifier) {
                            if (page == 9 && model?.capabilities?.contains(Capability.HEAD_GESTURES) == true) {
                                DashboardToggle(stringResource(R.string.head_gestures_title),
                                    stringResource(R.string.head_gestures_summary) + "\n" +
                                        stringResource(headGestureStatus(diagnostics?.getString("head_gestures"))),
                                    headGestures, onHeadGesturesChange)
                                FidelityDivider()
                                DashboardLink(stringResource(R.string.head_test_title),
                                    stringResource(R.string.head_test_summary)) { detailPage = 4 }
                            }

                        }
                    }
                }

                if (page == 10) item(key = "more_battery_reminders") {
                    FidelitySection(stringResource(R.string.battery_reminder_title), modifier = titleModifier)
                    Card(modifier = cardModifier) {
                        MinimalToggle(title = stringResource(R.string.battery_reminder_ears),
                            summary = stringResource(R.string.battery_reminder_ears_summary),
                            checked = lowBatterySettings.earsEnabled,
                            onCheckedChange = { onLowBatterySettingsChange(lowBatterySettings.copy(earsEnabled = it)) })
                        MinimalDropdown(title = stringResource(R.string.battery_reminder_ears_threshold),
                            items = LowBatteryReminder.thresholds.map { "$it%" },
                            selectedIndex = LowBatteryReminder.thresholds.indexOf(lowBatterySettings.earsThreshold),
                            enabled = lowBatterySettings.earsEnabled,
                            onSelectedIndexChange = { onLowBatterySettingsChange(lowBatterySettings.copy(earsThreshold = LowBatteryReminder.thresholds[it])) })
                        MinimalToggle(title = stringResource(R.string.battery_reminder_case),
                            summary = stringResource(R.string.battery_reminder_case_summary),
                            checked = lowBatterySettings.caseEnabled,
                            onCheckedChange = { onLowBatterySettingsChange(lowBatterySettings.copy(caseEnabled = it)) })
                        MinimalDropdown(title = stringResource(R.string.battery_reminder_case_threshold),
                            items = LowBatteryReminder.thresholds.map { "$it%" },
                            selectedIndex = LowBatteryReminder.thresholds.indexOf(lowBatterySettings.caseThreshold),
                            enabled = lowBatterySettings.caseEnabled,
                            onSelectedIndexChange = { onLowBatterySettingsChange(lowBatterySettings.copy(caseThreshold = LowBatteryReminder.thresholds[it])) })
                        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            FidelityText(stringResource(R.string.battery_reminder_rules), size = 16, weight = FontWeight.SemiBold)
                            FidelityText(stringResource(R.string.battery_reminder_rules_summary), size = 12, color = Fidelity.muted())
                        }
                    }
                }

                    // Press & Hold Settings
                if (page == 7 && PodsSettings.supports(Key.LISTENING_MODE_BYTE, model)) item(key = "more_press_hold") {
                    PressAndHoldSettingPage(
                        onListeningModeChange = onListeningModeChange, titleModifier = titleModifier, cardModifier = cardModifier,
                        confirmedMask = settings[Key.LISTENING_MODE_BYTE], allowedModes = allowedModes,
                        enabled = ready(Key.LISTENING_MODE_BYTE),
                    )
                }

                if (page == 7) item(key = "more_timing") {
                    FidelitySection(stringResource(R.string.controls_timing), modifier = titleModifier)
                    Card(modifier = cardModifier) {
                        listOf(Key.PRESS_SPEED to R.string.control_press_speed,
                            Key.HOLD_DURATION to R.string.control_hold_duration,
                            Key.SWIPE_SPEED to R.string.control_swipe_speed).forEach { (key, title) ->
                            if (PodsSettings.supports(key, model)) {
                                val offset = if (key == Key.SWIPE_SPEED) 1 else 0
                                MinimalDropdown(
                                    title = stringResource(title),
                                    items = listOf(stringResource(R.string.control_default),
                                        stringResource(if (key == Key.PRESS_SPEED) R.string.control_slower else R.string.control_longer),
                                        stringResource(if (key == Key.PRESS_SPEED) R.string.control_slowest else R.string.control_longest)),
                                    selectedIndex = settings[key]?.minus(offset) ?: -1,
                                    showValue = key in settings,
                                    enabled = ready(key),
                                    summary = if (!ready(key)) stringResource(if (key in pendingSettings) R.string.setting_pending else R.string.settings_reading) else statusText(key),
                                    onSelectedIndexChange = { onSettingChange(key, it + offset) },
                                )
                            }
                        }
                    }
                }

                    // Audio
                if (page == 8) item(key = "more_audio") {
                    FidelitySection(stringResource(R.string.audio_title), modifier = titleModifier)
                    Card(
                        modifier = cardModifier
                    ) {
                        if (CustomEq.supportsModel(model) || customEqState.value != null) {
                            DashboardLink(stringResource(R.string.eq_title), stringResource(R.string.eq_summary)) { equalizerParent = 8; detailPage = 5 }
                            FidelityDivider()
                        }
                        if (PodsSettings.supports(Key.CONVERSATION_AWARENESS, model)) {
                            MinimalToggle(
                                title = stringResource(R.string.conversation_pause_media_title),
                                summary = stringResource(R.string.conversation_pause_media_summary),
                                checked = conversationPauseMedia,
                                enabled = conversationAwareness,
                                onCheckedChange = onConversationPauseMediaChange,
                            )
                            MinimalToggle(
                                title = stringResource(R.string.conversation_phone_volume_title),
                                summary = stringResource(if (conversationPauseMedia) R.string.conversation_phone_volume_paused
                                    else R.string.conversation_phone_volume_summary),
                                checked = conversationPhoneVolume,
                                enabled = conversationAwareness && !conversationPauseMedia,
                                onCheckedChange = onConversationPhoneVolumeChange,
                            )
                        }
                        if (PodsSettings.supports(Key.CHIME_VOLUME, model)) Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                FidelityText(stringResource(R.string.control_chime_volume), Modifier.weight(1f), size = 16, weight = FontWeight.SemiBold)
                                FidelityText(if (ready(Key.CHIME_VOLUME)) "${chimeDraft.toInt()}%" else
                                    stringResource(if (Key.CHIME_VOLUME in pendingSettings) R.string.setting_pending else R.string.settings_reading),
                                    size = 14, color = Fidelity.muted())
                            }
                            statusText(Key.CHIME_VOLUME)?.let { FidelityText(it, size = 12, color = Fidelity.muted()) }
                            Slider(value = chimeDraft, valueRange = 0f..100f,
                                onValueChange = { chimeDraft = it },
                                onValueChangeFinished = { onSettingChange(Key.CHIME_VOLUME, chimeDraft.toInt()) },
                                enabled = ready(Key.CHIME_VOLUME), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .progressSemantics(chimeDraft, 0f..100f))
                        }
                        if (Capability.LOUD_SOUND_REDUCTION in model?.capabilities.orEmpty()) MinimalToggle(
                            title = stringResource(R.string.loud_sound_reduction_title),
                            summary = stringResource(R.string.loud_sound_unavailable),
                            checked = false,
                            enabled = false,
                            onCheckedChange = {}
                        )

                        if (PodsSettings.supports(Key.ADJUST_VOLUME_BY_SWIPER, model)) MinimalToggle(
                            title = stringResource(R.string.adjust_volume_by_swiper_title),
                            summary = feedback(Key.ADJUST_VOLUME_BY_SWIPER, R.string.adjust_volume_by_swiper_summary),
                            checked = adjustVolumeBySwiper,
                            enabled = ready(Key.ADJUST_VOLUME_BY_SWIPER),
                            onCheckedChange = onAdjustVolumeBySwiperChange
                        )
                        if (PodsSettings.supports(Key.ALLOW_OFF_OPTION, model)) MinimalToggle(
                            title = stringResource(R.string.allow_off_title),
                            summary = stringResource(R.string.allow_off_summary),
                            checked = settings[Key.ALLOW_OFF_OPTION] == 1,
                            enabled = ready(Key.ALLOW_OFF_OPTION),
                            onCheckedChange = onAllowOffChange,
                        )
                    }
                }

            }
        }
        }
    }
}
