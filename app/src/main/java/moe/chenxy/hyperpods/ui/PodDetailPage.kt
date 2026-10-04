package moe.chenxy.hyperpods.ui

import android.os.Bundle

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.NoiseControlMode
import moe.chenxy.hyperpods.ui.components.DashboardBattery
import moe.chenxy.hyperpods.ui.components.DashboardDivider
import moe.chenxy.hyperpods.ui.components.DashboardLink
import moe.chenxy.hyperpods.ui.components.DashboardNoise
import moe.chenxy.hyperpods.ui.components.DashboardSection
import moe.chenxy.hyperpods.ui.components.DashboardToggle
import moe.chenxy.hyperpods.ui.components.PodsInfoPage
import moe.chenxy.hyperpods.ui.components.PressAndHoldSettingPage
import moe.chenxy.hyperpods.ui.components.RenamePod
import moe.chenxy.hyperpods.ui.components.ConnectionDiagnostics
import moe.chenxy.hyperpods.utils.AACPManager
import moe.chenxy.hyperpods.utils.AirPodsModels.getModelByModelNumber
import moe.chenxy.hyperpods.utils.AirPods
import moe.chenxy.hyperpods.utils.Capability
import moe.chenxy.hyperpods.utils.PodsSettings
import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey as Key
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.EarDetectionParams
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderColors
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.useful.Back
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
    onAllowOffChange: (Boolean) -> Unit = {},
    onSettingChange: (String, Int) -> Unit = { _, _ -> },
    diagnostics: Bundle? = null,
) {
    val model = podsInfo?.let { getModelByModelNumber(it.modelNumber) }
    fun ready(key: String) = PodsSettings.supports(key, model) && key in settings && key !in pendingSettings
    val allowedModes = PodsSettings.allowedNoiseModes(model, settings)
    var adaptiveDraft by remember(settings[Key.ADAPTIVE_AUDIO_LEVEL]) { mutableFloatStateOf(adaptiveAudioLevel) }
    var chimeDraft by remember(settings[Key.CHIME_VOLUME], Key.CHIME_VOLUME in pendingSettings) { mutableFloatStateOf((settings[Key.CHIME_VOLUME] ?: 0).toFloat()) }
    var detailPage by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = isActive && detailPage != 0) { detailPage = 0 }
    val listState = key(detailPage) { rememberLazyListState() }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
    ) {
        item {
            if (detailPage == 0) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(podsInfo?.name?.takeIf { it.isNotBlank() }
                            ?: deviceName.takeIf { it.isNotBlank() } ?: "AirPods",
                            fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        RenamePod(Modifier.size(48.dp), onNameChange,
                            podsInfo?.name ?: deviceName, compact = true, pending = PodsSettings.RENAME in pendingSettings)
                    }
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(6.dp).background(MiuixTheme.colorScheme.primary, CircleShape))
                        Text(stringResource(if (pendingSettings.isNotEmpty()) R.string.setting_pending else R.string.dashboard_connected), fontSize = 13.sp, lineHeight = 18.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    DashboardBattery(batteryParams, earDetectionParams, model ?: AirPods())
                    if (model == null) Text(stringResource(R.string.settings_reading), fontSize = 13.sp)
                    if (PodsSettings.supports(PodsSettings.NOISE_MODE, model)) {
                        DashboardSection(stringResource(R.string.dashboard_noise))
                        DashboardNoise(ancMode, onAncModeChange,
                            if (ready(PodsSettings.NOISE_MODE) && Key.ALLOW_OFF_OPTION !in pendingSettings) allowedModes + 1 else emptySet())
                        if (1 !in allowedModes) Text(stringResource(R.string.off_mode_enable_on_select),
                            fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        if (ancMode == null) Text(stringResource(R.string.settings_reading), fontSize = 13.sp)
                        Box(Modifier.padding(top = 12.dp)) { DashboardDivider() }
                    }
                    if (PodsSettings.supports(Key.PERSONLIZED_VOLUME, model) || PodsSettings.supports(Key.CONVERSATION_AWARENESS, model)) {
                        DashboardSection(stringResource(R.string.dashboard_listening))
                    }
                    if (PodsSettings.supports(Key.PERSONLIZED_VOLUME, model)) {
                        DashboardToggle(stringResource(R.string.personlized_volume_title),
                            stringResource(if (Key.PERSONLIZED_VOLUME in settings) R.string.personlized_volume_summary else R.string.settings_reading),
                            personlizedVolume, onPersonlizedVolumeChange, enabled = ready(Key.PERSONLIZED_VOLUME))
                        DashboardDivider()
                    }
                    if (PodsSettings.supports(Key.CONVERSATION_AWARENESS, model)) {
                        DashboardToggle(stringResource(R.string.dashboard_conversation_title),
                            stringResource(if (Key.CONVERSATION_AWARENESS in settings) R.string.dashboard_conversation_summary else R.string.settings_reading),
                            conversationAwareness, onConversationAwarenessChange, enabled = ready(Key.CONVERSATION_AWARENESS))
                        DashboardDivider()
                    }
                    DashboardSection(stringResource(R.string.dashboard_operation))
                    DashboardToggle(stringResource(R.string.dashboard_detection_title),
                        stringResource(R.string.dashboard_detection_summary), earDetectionEnable, onEarDetectionChanged)
                    DashboardDivider()
                    DashboardLink(stringResource(R.string.dashboard_more),
                        stringResource(R.string.dashboard_more_summary)) { detailPage = 1 }
                    DashboardDivider()
                    DashboardLink(stringResource(R.string.dashboard_device_info),
                        if (podsInfo == null) stringResource(R.string.dashboard_info_pending) else null) { detailPage = 2 }
                    DashboardDivider()
                    DashboardLink(stringResource(R.string.diagnostics_title), null) { detailPage = 3 }
                }
                return@item
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { detailPage = 0 }, modifier = Modifier.size(48.dp)) {
                    Icon(MiuixIcons.Useful.Back, stringResource(R.string.dashboard_back))
                }
                Text(stringResource(when (detailPage) { 1 -> R.string.dashboard_more; 3 -> R.string.diagnostics_title; else -> R.string.dashboard_device_info }),
                    fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            if (detailPage == 3) {
                Card(modifier = Modifier.padding(12.dp)) { ConnectionDiagnostics(diagnostics) }
                return@item
            }
            if (detailPage == 2) {
                podsInfo?.let { PodsInfoPage(Modifier, Modifier.padding(12.dp), it) }
                    ?: Text(stringResource(R.string.dashboard_info_pending), modifier = Modifier.padding(24.dp))
                return@item
            }
            val cardModifier = Modifier
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            val titleModifier = Modifier

            // Press & Hold Settings
            if (PodsSettings.supports(Key.LISTENING_MODE_BYTE, model)) PressAndHoldSettingPage(
                onListeningModeChange = onListeningModeChange, titleModifier = titleModifier, cardModifier = cardModifier,
                confirmedMask = settings[Key.LISTENING_MODE_BYTE], allowedModes = allowedModes,
                enabled = ready(Key.LISTENING_MODE_BYTE),
            )

            SmallTitle(stringResource(R.string.controls_timing), modifier = titleModifier)
            Card(modifier = cardModifier) {
                listOf(Key.PRESS_SPEED to R.string.control_press_speed,
                    Key.HOLD_DURATION to R.string.control_hold_duration,
                    Key.SWIPE_SPEED to R.string.control_swipe_speed).forEach { (key, title) ->
                    if (PodsSettings.supports(key, model)) {
                        if (ready(key)) SuperDropdown(
                            title = stringResource(title),
                            items = listOf(stringResource(R.string.control_default),
                                stringResource(if (key == Key.PRESS_SPEED) R.string.control_slower else R.string.control_longer),
                                stringResource(if (key == Key.PRESS_SPEED) R.string.control_slowest else R.string.control_longest)),
                            selectedIndex = settings.getValue(key),
                            onSelectedIndexChange = { onSettingChange(key, it) },
                        ) else top.yukonga.miuix.kmp.basic.BasicComponent(
                            title = stringResource(title),
                            summary = stringResource(if (key in pendingSettings) R.string.setting_pending else R.string.settings_reading),
                            enabled = false,
                        )
                    }
                }
            }

            // Audio
            SmallTitle(stringResource(R.string.audio_title), modifier = titleModifier)
            Card(
                modifier = cardModifier
            ) {
                if (PodsSettings.supports(Key.CHIME_VOLUME, model)) Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.control_chime_volume), fontWeight = FontWeight.Medium)
                    Text(if (ready(Key.CHIME_VOLUME)) "${chimeDraft.toInt()}%" else
                        stringResource(if (Key.CHIME_VOLUME in pendingSettings) R.string.setting_pending else R.string.settings_reading),
                        fontSize = 13.sp)
                    Slider(value = chimeDraft, valueRange = 0f..100f,
                        onValueChange = { chimeDraft = it },
                        onValueChangeFinished = { onSettingChange(Key.CHIME_VOLUME, chimeDraft.toInt()) },
                        enabled = ready(Key.CHIME_VOLUME), modifier = Modifier.fillMaxWidth())
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
                                modifier = Modifier.padding(top = 8.dp),
                                effect = true,
                                showKeyPoints = true,
                                keyPoints = listOf(0f, 0.5f, 1f),
                                colors = SliderColors(
                                    foregroundColor = MiuixTheme.colorScheme.secondaryVariant,
                                    disabledForegroundColor = MiuixTheme.colorScheme.disabledPrimarySlider,
                                    backgroundColor = MiuixTheme.colorScheme.secondaryVariant,
                                    keyPointColor = Color(0x4DA3B3CD),
                                    keyPointForegroundColor = Color(0x4DA3B3CD)
                                )
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
                    summary = stringResource(R.string.adjust_volume_by_swiper_summary),
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

            // Auto Ear-Detection
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

            // Mic Phone
            Card(
                modifier = cardModifier
            ) {
                if (ready(Key.MICROPHONE_MODE)) SuperDropdown(
                    title = stringResource(R.string.microphone),
                    items = listOf(stringResource(R.string.microphone_auto),
                        stringResource(R.string.microphone_left),
                        stringResource(R.string.microphone_right)
                    ),
                    selectedIndex = microphoneMode,
                    onSelectedIndexChange = onMicrophoneModeChange,
                ) else top.yukonga.miuix.kmp.basic.BasicComponent(
                    title = stringResource(R.string.microphone),
                    summary = stringResource(if (Key.MICROPHONE_MODE in pendingSettings) R.string.setting_pending else R.string.settings_reading),
                    enabled = false,
                )
            }

            // Single Pod ANC Mode
            if (PodsSettings.supports(Key.SINGLE_POD_ANC, model)) Card(
                modifier = cardModifier
            ) {
                SuperSwitch(
                    title = stringResource(R.string.noise_cancellation_single_airpod),
                    summary = stringResource(R.string.noise_cancellation_single_airpod_description),
                    checked = noiseCancellationSingleAirPod,
                    enabled = ready(Key.SINGLE_POD_ANC),
                    onCheckedChange = onNoiseCancellationSingleAirPodChange,
                )
            }

        }
    }
}
