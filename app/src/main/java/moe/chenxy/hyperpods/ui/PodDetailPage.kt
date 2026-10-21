package moe.chenxy.hyperpods.ui

import android.os.Bundle

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.sp
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.NoiseControlMode
import moe.chenxy.hyperpods.ui.components.DashboardBattery
import moe.chenxy.hyperpods.ui.components.dashboardConnectionStatus
import moe.chenxy.hyperpods.ui.components.DashboardDivider
import moe.chenxy.hyperpods.ui.components.DashboardLink
import moe.chenxy.hyperpods.ui.components.DashboardNoise
import moe.chenxy.hyperpods.ui.components.DashboardSection
import moe.chenxy.hyperpods.ui.components.DashboardToggle
import moe.chenxy.hyperpods.ui.components.DashboardGroup as Card
import moe.chenxy.hyperpods.ui.components.PodsInfoPage
import moe.chenxy.hyperpods.ui.components.PressAndHoldSettingPage
import moe.chenxy.hyperpods.ui.components.RenamePod
import moe.chenxy.hyperpods.ui.components.ConnectionDiagnostics
import moe.chenxy.hyperpods.ui.components.headGestureStatus
import moe.chenxy.hyperpods.ui.components.HeadTrackingPage
import moe.chenxy.hyperpods.utils.AACPManager
import moe.chenxy.hyperpods.utils.AirPodsModels.getModelByModelNumber
import moe.chenxy.hyperpods.utils.Capability
import moe.chenxy.hyperpods.utils.PodsSettings
import moe.chenxy.hyperpods.utils.LowBatterySettings
import moe.chenxy.hyperpods.utils.LowBatteryReminder
import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey as Key
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.EarDetectionParams
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperDropdown
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    headGestures: Boolean = false,
    onHeadGesturesChange: (Boolean) -> Unit = {},
    onAllowOffChange: (Boolean) -> Unit = {},
    onSettingChange: (String, Int) -> Unit = { _, _ -> },
    diagnostics: Bundle? = null,
) {
    val model = podsInfo?.let { getModelByModelNumber(it.modelNumber) }
    fun ready(key: String) = PodsSettings.supports(key, model) && key !in pendingSettings &&
        (key in settings || (key == Key.MICROPHONE_MODE || key == Key.LISTENING_MODE_BYTE) &&
            diagnostics?.getString("connection") == "connected")
    @Composable
    fun statusText(key: String): String? {
        val status = when (settingFeedback[key]) {
            // Sent-only controls already settled locally. Keep the unverified
            // state in diagnostics, not as an endless waiting label on each row.
            "sent" -> if (key in pendingSettings) R.string.setting_pending else return null
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
    var adaptiveDraft by remember(settings[Key.ADAPTIVE_AUDIO_LEVEL], Key.ADAPTIVE_AUDIO_LEVEL in pendingSettings,
        settingFeedback[Key.ADAPTIVE_AUDIO_LEVEL]) { mutableFloatStateOf(adaptiveAudioLevel) }
    var chimeDraft by remember(settings[Key.CHIME_VOLUME], settingFeedback[Key.CHIME_VOLUME]) {
        mutableFloatStateOf((settings[Key.CHIME_VOLUME] ?: 0).toFloat())
    }
    var detailPage by rememberSaveable { mutableIntStateOf(0) }
    fun back() { detailPage = if (detailPage == 4) 1 else 0 }
    BackHandler(enabled = isActive && detailPage != 0) { back() }
    // Keep every page's saveable state outside the animated content. Switching the
    // content key must not discard the previous page's scroll position.
    val listStates = listOf(rememberLazyListState(), rememberLazyListState(),
        rememberLazyListState(), rememberLazyListState(), rememberLazyListState())
    val travel = with(LocalDensity.current) { 28.dp.roundToPx() }
    AnimatedContent(
        targetState = detailPage,
        modifier = Modifier.fillMaxSize().clipToBounds(),
        transitionSpec = {
            val direction = if (targetState < initialState) -1 else 1
            ((fadeIn(tween(220)) + slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { direction * travel })
                togetherWith (fadeOut(tween(120)) + slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) { -direction * travel / 2 }))
                .using(null)
        },
        label = "SettingsPage",
    ) { page ->
        val pageTitle = stringResource(when (page) {
            1 -> R.string.dashboard_more
            2 -> R.string.dashboard_device_info
            3 -> R.string.diagnostics_title
            4 -> R.string.head_test_title
            else -> R.string.dashboard_headphones
        })
        val interaction = if (page == detailPage) Modifier.semantics { paneTitle = pageTitle }
            else Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                // The outgoing page remains visible briefly, but must not trigger
                // stale setting writes or duplicate accessibility actions.
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = listStates[page],
                // Keep the scroll/focus viewport between the fixed bars. Item-only
                // padding lets bring-into-view place a clickable row under a bar.
                modifier = Modifier.widthIn(max = 680.dp).fillMaxSize().padding(padding).clipToBounds()
                    .focusProperties { canFocus = page == detailPage && isActive }.then(interaction),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (page == 0) {
                    item(key = "home_header") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(podsInfo?.name?.takeIf { it.isNotBlank() }
                                    ?: deviceName.takeIf { it.isNotBlank() } ?: "AirPods",
                                    fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                RenamePod(Modifier.size(48.dp), onNameChange,
                                    podsInfo?.name ?: deviceName, compact = true, pending = PodsSettings.RENAME in pendingSettings)
                            }
                            Row(Modifier.padding(start = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(6.dp).background(MiuixTheme.colorScheme.primary, CircleShape))
                                Text(stringResource(if (pendingSettings.isNotEmpty()) R.string.setting_pending else dashboardConnectionStatus(diagnostics)), fontSize = 13.sp, lineHeight = 18.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                            DashboardBattery(batteryParams, earDetectionParams, model, diagnostics)
                            if (model == null) Text(stringResource(R.string.settings_reading), fontSize = 13.sp)
                        }
                    }
                    if (PodsSettings.supports(PodsSettings.NOISE_MODE, model)) {
                        item(key = "home_noise") {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                DashboardSection(stringResource(R.string.dashboard_noise))
                                DashboardNoise(ancMode, onAncModeChange,
                                    if (ready(PodsSettings.NOISE_MODE) && Key.ALLOW_OFF_OPTION !in pendingSettings) allowedModes + 1 else emptySet())
                                if (1 !in allowedModes) Text(stringResource(R.string.off_mode_enable_on_select),
                                    fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                if (ancMode == null) Text(stringResource(R.string.settings_reading), fontSize = 13.sp)
                            }
                        }
                    }
                    if (PodsSettings.supports(Key.PERSONLIZED_VOLUME, model) || PodsSettings.supports(Key.CONVERSATION_AWARENESS, model)) {
                        item(key = "home_listening") {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                DashboardSection(stringResource(R.string.dashboard_listening))
                                Card {
                                    if (PodsSettings.supports(Key.PERSONLIZED_VOLUME, model)) {
                                        DashboardToggle(stringResource(R.string.personlized_volume_title),
                                            feedback(Key.PERSONLIZED_VOLUME, R.string.personlized_volume_summary),
                                            personlizedVolume, onPersonlizedVolumeChange, enabled = ready(Key.PERSONLIZED_VOLUME))
                                    }
                                    if (PodsSettings.supports(Key.CONVERSATION_AWARENESS, model)) {
                                        if (PodsSettings.supports(Key.PERSONLIZED_VOLUME, model)) DashboardDivider(Modifier.padding(horizontal = 16.dp))
                                        DashboardToggle(stringResource(R.string.dashboard_conversation_title),
                                            feedback(Key.CONVERSATION_AWARENESS, R.string.dashboard_conversation_summary),
                                            conversationAwareness, onConversationAwarenessChange, enabled = ready(Key.CONVERSATION_AWARENESS))
                                    }
                                }
                            }
                        }
                    }
                    item(key = "home_operation") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            DashboardSection(stringResource(R.string.dashboard_operation))
                            Card {
                                DashboardToggle(stringResource(R.string.dashboard_detection_title),
                                    stringResource(R.string.dashboard_detection_summary), earDetectionEnable, onEarDetectionChanged)
                                DashboardDivider(Modifier.padding(horizontal = 16.dp))
                                DashboardLink(stringResource(R.string.dashboard_more),
                                    stringResource(if (model?.capabilities?.contains(Capability.HEAD_GESTURES) == true &&
                                        PodsSettings.supports(Key.SLEEP_DETECTION, model)) R.string.dashboard_more_summary
                                        else R.string.dashboard_more_basic_summary)) { detailPage = 1 }
                            }
                        }
                    }
                    item(key = "home_support") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            DashboardSection(stringResource(R.string.dashboard_support))
                            Card {
                                DashboardLink(stringResource(R.string.dashboard_device_info),
                                    if (podsInfo == null) stringResource(R.string.dashboard_info_pending) else null) { detailPage = 2 }
                                DashboardDivider(Modifier.padding(horizontal = 16.dp))
                                DashboardLink(stringResource(R.string.diagnostics_title), null) { detailPage = 3 }
                            }
                        }
                    }
                    return@LazyColumn
                }
                item(key = "page_title") {
                    Text(pageTitle, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
                }
                if (page == 4) {
                    item(key = "head_tracking") {
                        HeadTrackingPage(isActive && page == detailPage)
                    }
                    return@LazyColumn
                }
                if (page == 3) {
                    item(key = "diagnostics") {
                        Card(modifier = Modifier.padding(16.dp)) { ConnectionDiagnostics(diagnostics) }
                    }
                    return@LazyColumn
                }
                if (page == 2) {
                    item(key = "device_info") {
                        podsInfo?.let { PodsInfoPage(Modifier, Modifier.padding(horizontal = 16.dp), it) }
                            ?: Text(stringResource(R.string.dashboard_info_pending), modifier = Modifier.padding(24.dp))
                    }
                    return@LazyColumn
                }
                val cardModifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                val titleModifier = Modifier.padding(top = 12.dp)

                if (model?.capabilities?.contains(Capability.HEAD_GESTURES) == true || PodsSettings.supports(Key.SLEEP_DETECTION, model)) {
                    item(key = "more_gestures_sleep") {
                        SmallTitle(stringResource(R.string.dashboard_operation), modifier = titleModifier)
                        Card(modifier = cardModifier) {
                            if (model?.capabilities?.contains(Capability.HEAD_GESTURES) == true) {
                                DashboardToggle(stringResource(R.string.head_gestures_title),
                                    stringResource(R.string.head_gestures_summary) + "\n" +
                                        stringResource(headGestureStatus(diagnostics?.getString("head_gestures"))),
                                    headGestures, onHeadGesturesChange)
                                DashboardDivider(Modifier.padding(horizontal = 16.dp))
                                DashboardLink(stringResource(R.string.head_test_title),
                                    stringResource(R.string.head_test_summary)) { detailPage = 4 }
                            }
                            if (PodsSettings.supports(Key.SLEEP_DETECTION, model)) {
                                DashboardDivider(Modifier.padding(horizontal = 16.dp))
                                DashboardToggle(stringResource(R.string.sleep_detection_title),
                                    feedback(Key.SLEEP_DETECTION, R.string.sleep_detection_summary),
                                    settings[Key.SLEEP_DETECTION] == 1,
                                    { onSettingChange(Key.SLEEP_DETECTION, if (it) 1 else 2) },
                                    enabled = Key.SLEEP_DETECTION !in pendingSettings &&
                                        (Key.SLEEP_DETECTION in settings || diagnostics?.getString("connection") == "connected"))
                            }
                        }
                    }
                }

                item(key = "more_battery_reminders") {
                    SmallTitle(stringResource(R.string.battery_reminder_title), modifier = titleModifier)
                    Card(modifier = cardModifier) {
                        SuperSwitch(title = stringResource(R.string.battery_reminder_ears),
                            summary = stringResource(R.string.battery_reminder_ears_summary),
                            checked = lowBatterySettings.earsEnabled,
                            onCheckedChange = { onLowBatterySettingsChange(lowBatterySettings.copy(earsEnabled = it)) })
                        SuperDropdown(title = stringResource(R.string.battery_reminder_ears_threshold),
                            items = LowBatteryReminder.thresholds.map { "$it%" },
                            selectedIndex = LowBatteryReminder.thresholds.indexOf(lowBatterySettings.earsThreshold),
                            enabled = lowBatterySettings.earsEnabled,
                            onSelectedIndexChange = { onLowBatterySettingsChange(lowBatterySettings.copy(earsThreshold = LowBatteryReminder.thresholds[it])) })
                        SuperSwitch(title = stringResource(R.string.battery_reminder_case),
                            summary = stringResource(R.string.battery_reminder_case_summary),
                            checked = lowBatterySettings.caseEnabled,
                            onCheckedChange = { onLowBatterySettingsChange(lowBatterySettings.copy(caseEnabled = it)) })
                        SuperDropdown(title = stringResource(R.string.battery_reminder_case_threshold),
                            items = LowBatteryReminder.thresholds.map { "$it%" },
                            selectedIndex = LowBatteryReminder.thresholds.indexOf(lowBatterySettings.caseThreshold),
                            enabled = lowBatterySettings.caseEnabled,
                            onSelectedIndexChange = { onLowBatterySettingsChange(lowBatterySettings.copy(caseThreshold = LowBatteryReminder.thresholds[it])) })
                        top.yukonga.miuix.kmp.basic.BasicComponent(title = stringResource(R.string.battery_reminder_rules),
                            summary = stringResource(R.string.battery_reminder_rules_summary), enabled = false)
                    }
                }

                    // Press & Hold Settings
                if (PodsSettings.supports(Key.LISTENING_MODE_BYTE, model)) item(key = "more_press_hold") {
                    PressAndHoldSettingPage(
                        onListeningModeChange = onListeningModeChange, titleModifier = titleModifier, cardModifier = cardModifier,
                        confirmedMask = settings[Key.LISTENING_MODE_BYTE], allowedModes = allowedModes,
                        enabled = ready(Key.LISTENING_MODE_BYTE),
                    )
                }

                item(key = "more_timing") {
                    SmallTitle(stringResource(R.string.controls_timing), modifier = titleModifier)
                    Card(modifier = cardModifier) {
                        listOf(Key.PRESS_SPEED to R.string.control_press_speed,
                            Key.HOLD_DURATION to R.string.control_hold_duration,
                            Key.SWIPE_SPEED to R.string.control_swipe_speed).forEach { (key, title) ->
                            if (PodsSettings.supports(key, model)) {
                                val offset = if (key == Key.SWIPE_SPEED) 1 else 0
                                if (ready(key)) SuperDropdown(
                                    title = stringResource(title),
                                    items = listOf(stringResource(R.string.control_default),
                                        stringResource(if (key == Key.PRESS_SPEED) R.string.control_slower else R.string.control_longer),
                                        stringResource(if (key == Key.PRESS_SPEED) R.string.control_slowest else R.string.control_longest)),
                                    selectedIndex = settings.getValue(key) - offset,
                                    onSelectedIndexChange = { onSettingChange(key, it + offset) },
                                ) else top.yukonga.miuix.kmp.basic.BasicComponent(
                                    title = stringResource(title),
                                    summary = stringResource(if (key in pendingSettings) R.string.setting_pending else R.string.settings_reading),
                                    enabled = false,
                                )
                            }
                        }
                    }
                }

                    // Audio
                item(key = "more_audio") {
                    SmallTitle(stringResource(R.string.audio_title), modifier = titleModifier)
                    Card(
                        modifier = cardModifier
                    ) {
                        if (PodsSettings.supports(Key.CONVERSATION_AWARENESS, model)) SuperSwitch(
                            title = stringResource(R.string.conversation_phone_volume_title),
                            summary = stringResource(R.string.conversation_phone_volume_summary),
                            checked = conversationPhoneVolume,
                            onCheckedChange = onConversationPhoneVolumeChange,
                        )
                        if (PodsSettings.supports(Key.CHIME_VOLUME, model)) Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.control_chime_volume), fontWeight = FontWeight.Medium)
                            Text(if (ready(Key.CHIME_VOLUME)) "${chimeDraft.toInt()}%" else
                                stringResource(if (Key.CHIME_VOLUME in pendingSettings) R.string.setting_pending else R.string.settings_reading),
                                fontSize = 13.sp)
                            statusText(Key.CHIME_VOLUME)?.let { Text(it, fontSize = 12.sp) }
                            Slider(value = chimeDraft, valueRange = 0f..100f,
                                onValueChange = { chimeDraft = it },
                                onValueChangeFinished = { onSettingChange(Key.CHIME_VOLUME, chimeDraft.toInt()) },
                                enabled = ready(Key.CHIME_VOLUME), modifier = Modifier.fillMaxWidth()
                                    .progressSemantics(chimeDraft, 0f..100f))
                        }
                        if (Capability.LOUD_SOUND_REDUCTION in model?.capabilities.orEmpty()) SuperSwitch(
                            title = stringResource(R.string.loud_sound_reduction_title),
                            summary = stringResource(R.string.loud_sound_unavailable),
                            checked = false,
                            enabled = false,
                            onCheckedChange = {}
                        )

                        AnimatedVisibility(
                            visible = ancMode == NoiseControlMode.ADAPTIVE && PodsSettings.supports(Key.ADAPTIVE_AUDIO_LEVEL, model),
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Row(
                                modifier = Modifier
                                    .heightIn(min = 56.dp)
                                    .fillMaxWidth()
                                    .padding(PaddingValues(16.dp)),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = stringResource(R.string.adaptive_audio_title),
                                        fontSize = MiuixTheme.textStyles.headline1.fontSize,
                                        fontWeight = FontWeight.Medium,
                                        color = MiuixTheme.colorScheme.onSurface
                                    )
                                    Slider(
                                        value = adaptiveDraft,
                                        onValueChange = { adaptiveDraft = it },
                                        onValueChangeFinished = { onAdaptiveAudioLevelChange(adaptiveDraft) },
                                        enabled = ready(Key.ADAPTIVE_AUDIO_LEVEL),
                                        modifier = Modifier.padding(top = 8.dp).progressSemantics(adaptiveDraft),
                                        effect = true,
                                        showKeyPoints = true,
                                        keyPoints = listOf(0f, 0.5f, 1f),
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            stringResource(R.string.less_noise),
                                            fontSize = 12.sp,
                                            color = MiuixTheme.colorScheme.onBackgroundVariant
                                        )
                                        Text(
                                            stringResource(R.string.more_noise),
                                            fontSize = 12.sp,
                                            color = MiuixTheme.colorScheme.onBackgroundVariant
                                        )
                                    }
                                }
                            }
                        }

                        if (PodsSettings.supports(Key.ADJUST_VOLUME_BY_SWIPER, model)) SuperSwitch(
                            title = stringResource(R.string.adjust_volume_by_swiper_title),
                            summary = feedback(Key.ADJUST_VOLUME_BY_SWIPER, R.string.adjust_volume_by_swiper_summary),
                            checked = adjustVolumeBySwiper,
                            enabled = ready(Key.ADJUST_VOLUME_BY_SWIPER),
                            onCheckedChange = onAdjustVolumeBySwiperChange
                        )
                        if (PodsSettings.supports(Key.ALLOW_OFF_OPTION, model)) SuperSwitch(
                            title = stringResource(R.string.allow_off_title),
                            summary = stringResource(R.string.allow_off_summary),
                            checked = settings[Key.ALLOW_OFF_OPTION] == 1,
                            enabled = ready(Key.ALLOW_OFF_OPTION),
                            onCheckedChange = onAllowOffChange,
                        )
                    }
                }

                    // Auto Ear-Detection
                item(key = "more_ear_detection") {
                    SmallTitle(stringResource(R.string.ear_detection_title), modifier = titleModifier)
                    Card(
                        modifier = cardModifier
                    ) {
                        AnimatedVisibility(
                            visible = earDetectionEnable,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            SuperSwitch(
                                title = stringResource(R.string.auto_switch_to_speaker_title),
                                summary = stringResource(R.string.auto_switch_to_speaker_summary),
                                checked = autoSwitchToSpeaker,
                                onCheckedChange = onAutoSwitchToSpeakerChange,
                            )
                        }
                    }
                }

                    // Mic Phone
                item(key = "more_microphone") {
                    Card(
                        modifier = cardModifier
                    ) {
                        if (ready(Key.MICROPHONE_MODE)) SuperDropdown(
                            title = stringResource(R.string.microphone),
                            items = listOf(stringResource(R.string.microphone_auto),
                                stringResource(R.string.microphone_left),
                                stringResource(R.string.microphone_right)
                            ),
                            selectedIndex = if (Key.MICROPHONE_MODE in settings) microphoneMode else -1,
                            showValue = Key.MICROPHONE_MODE in settings,
                            summary = if (Key.MICROPHONE_MODE in settings) statusText(Key.MICROPHONE_MODE)
                                else stringResource(R.string.setting_select_to_apply),
                            onSelectedIndexChange = onMicrophoneModeChange,
                        ) else top.yukonga.miuix.kmp.basic.BasicComponent(
                            title = stringResource(R.string.microphone),
                            summary = stringResource(if (Key.MICROPHONE_MODE in pendingSettings) R.string.setting_pending else R.string.settings_reading),
                            enabled = false,
                        )
                    }
                }

                    // Single Pod ANC Mode
                if (PodsSettings.supports(Key.SINGLE_POD_ANC, model)) item(key = "more_single_anc") {
                    Card(
                        modifier = cardModifier
                    ) {
                        SuperSwitch(
                            title = stringResource(R.string.noise_cancellation_single_airpod),
                            summary = feedback(Key.SINGLE_POD_ANC, R.string.noise_cancellation_single_airpod_description),
                            checked = noiseCancellationSingleAirPod,
                            enabled = ready(Key.SINGLE_POD_ANC),
                            onCheckedChange = onNoiseCancellationSingleAirPodChange,
                        )
                    }
                }
            }
        }
    }
}
