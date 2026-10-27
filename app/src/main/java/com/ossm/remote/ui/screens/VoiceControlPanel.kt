package com.ossm.remote.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import com.ossm.remote.ui.theme.OssmGlass
import com.ossm.remote.ui.theme.OssmOutline
import com.ossm.remote.ui.theme.OssmSurfaceRaised
import com.ossm.remote.voice.VoiceDepthRange
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ossm.remote.R
import com.ossm.remote.ui.components.ControlSlider
import com.ossm.remote.ui.theme.OssmConnected
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmPrimaryLight
import com.ossm.remote.ui.theme.OssmWarning
import com.ossm.remote.viewmodel.ControlUiState
import com.ossm.remote.voice.AsrEngine
import com.ossm.remote.voice.VoiceAiStatus
import com.ossm.remote.voice.VoiceAiUiState
import com.ossm.remote.voice.VoiceIgnoreReason
import com.ossm.remote.voice.VoiceInterpretSource
import com.ossm.remote.voice.VoiceInterpretation
import com.ossm.remote.voice.VoiceStep
import java.util.Locale

/** Callbacks of the Voix panel. Defaults are no-ops so existing callers stay valid. */
data class VoiceControlActions(
    val onStopNow: () -> Unit = {},
    val onMaxSpeedChange: (Float) -> Unit = {},
    /** 2.3.28: Voix depth range commit (fractions, intersected with the limits by the store). */
    val onDepthRangeChange: (Float, Float) -> Unit = { _, _ -> },
    /** 2.3.29: "STOP rapide par mot court" on/off (persisted, on by default). */
    val onShortWordStopChange: (Boolean) -> Unit = {},
    /** Microphone on/off (2.3.27). Only effective once the on-device AI is ready. */
    val onListenChange: (Boolean) -> Unit = {},
    val hasMicrophonePermission: () -> Boolean = { false },
    val onMicrophonePermissionResult: (Boolean) -> Unit = {},
)

/** 2.3.30: "Transcription : Whisper small (français)" / "Transcription : Parakeet (langue non forcée)". */
internal fun voiceAsrLabelRes(engine: AsrEngine): Int = when (engine) {
    AsrEngine.WHISPER_SMALL_FR -> R.string.voice_asr_whisper
    AsrEngine.PARAKEET -> R.string.voice_asr_parakeet
}

internal fun formatVoiceSeconds(ms: Int, locale: Locale = Locale.getDefault()): String =
    if (ms % 1000 == 0) (ms / 1000).toString() else String.format(locale, "%.1f", ms / 1000f)

internal fun voiceInterpretationRes(state: VoiceInterpretation): Int? = when (state) {
    VoiceInterpretation.NONE -> null
    VoiceInterpretation.PLANNED -> R.string.voice_state_planned
    VoiceInterpretation.REJECTED -> R.string.voice_state_rejected
    VoiceInterpretation.NOT_UNDERSTOOD -> R.string.voice_state_not_understood
    VoiceInterpretation.STOPPED -> R.string.voice_state_stopped
    VoiceInterpretation.NOT_READY -> R.string.voice_state_not_ready
}

@Composable
private fun voiceStepText(step: VoiceStep, first: Boolean): String {
    val seconds = formatVoiceSeconds(step.moveMs)
    val move = when {
        step.moveMs == 0 -> stringResource(R.string.voice_step_stay, step.targetPercent)
        step.targetPercent == 100 -> stringResource(R.string.voice_step_deep, step.targetPercent, seconds)
        step.targetPercent == 0 -> stringResource(R.string.voice_step_home, step.targetPercent, seconds)
        else -> stringResource(R.string.voice_step_position, step.targetPercent, seconds)
    }
    val hold = if (step.holdMs > 0) {
        " → " + stringResource(R.string.voice_step_hold, formatVoiceSeconds(step.holdMs))
    } else ""
    return (if (first) "" else "→ ") + move + hold
}

@Composable
internal fun VoiceControlPanel(
    uiState: ControlUiState,
    connected: Boolean,
    slidersEnabled: Boolean,
    actions: VoiceControlActions,
    voiceAi: VoiceAiUiState = VoiceAiUiState(),
    modelRoot: String? = null,
) {
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        actions.onMicrophonePermissionResult(granted)
    }
    // 2.3.28: the long explanation moved to the header "i" button (VoiceInfoButton).
    Column(modifier = Modifier.fillMaxWidth().testTag("voice_panel")) {
        VoiceAiStatusBlock(voiceAi.status, modelRoot)
        // 2.3.30: which engine transcribes (Whisper "fr" imposed, or the Parakeet fallback).
        voiceAi.asrEngine?.let { engine ->
            Text(
                stringResource(voiceAsrLabelRes(engine)),
                color = if (engine == AsrEngine.WHISPER_SMALL_FR) OssmConnected else OssmWarning,
                fontSize = 12.sp,
                modifier = Modifier.testTag("voice_asr_engine")
            )
        }
        val ready = voiceAi.status is VoiceAiStatus.Ready
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                when {
                    voiceAi.listening -> actions.onListenChange(false)
                    actions.hasMicrophonePermission() -> actions.onListenChange(true)
                    else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            enabled = ready,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("voice_listen")
        ) {
            Text(
                stringResource(if (voiceAi.listening) R.string.voice_listen_pause else R.string.voice_listen),
                fontWeight = FontWeight.Bold
            )
        }
        if (voiceAi.listening) {
            Text(
                stringResource(R.string.voice_listening),
                color = OssmConnected,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(
                    value = uiState.voiceShortWordStop,
                    role = Role.Switch,
                    onValueChange = actions.onShortWordStopChange,
                )
                .testTag("voice_short_word_stop"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(
                    if (uiState.voiceShortWordStop) R.string.voice_short_word_stop_on
                    else R.string.voice_short_word_stop_off
                ),
                color = if (uiState.voiceShortWordStop) OssmConnected else OssmWarning,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = uiState.voiceShortWordStop, onCheckedChange = null)
        }
        if (voiceAi.microphoneDenied) {
            Text(
                stringResource(R.string.voice_mic_denied),
                color = OssmError,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        voiceAi.lastTranscript?.takeIf { it.isNotBlank() }?.let { transcript ->
            Text(
                stringResource(R.string.voice_last_transcript, transcript),
                color = OssmOnSurface.copy(alpha = 0.85f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp).testTag("voice_transcript")
            )
        }
        if (voiceAi.speechToTextMs != null || voiceAi.textToStepsMs != null) {
            Text(
                stringResource(
                    R.string.voice_latency,
                    voiceAi.speechToTextMs?.toString() ?: "—",
                    voiceAi.textToStepsMs?.toString() ?: "—",
                ),
                color = OssmOnSurface.copy(alpha = 0.6f),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp).testTag("voice_latency")
            )
        }
        voiceAi.interpretSource?.let { source ->
            Text(
                stringResource(
                    R.string.voice_interpret_source,
                    stringResource(
                        when (source) {
                            VoiceInterpretSource.RULES -> R.string.voice_source_rules
                            VoiceInterpretSource.GEMMA -> R.string.voice_source_gemma
                        }
                    ),
                    voiceAi.textToStepsMs?.toString() ?: "—",
                ),
                color = OssmOnSurface.copy(alpha = 0.6f),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp).testTag("voice_interpret_source")
            )
        }
        voiceAi.lastIgnoredReason?.let { reason ->
            Text(
                stringResource(R.string.voice_transcript_ignored, stringResource(voiceIgnoreReasonLabel(reason))),
                color = OssmWarning.copy(alpha = 0.9f),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp).testTag("voice_transcript_ignored")
            )
        }
        if (!uiState.streamingReady) {
            Text(
                stringResource(R.string.ctl_live_preparing),
                color = OssmWarning.copy(alpha = 0.9f),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        ControlSlider(
            label = stringResource(R.string.voice_max_speed),
            value = uiState.voiceMaxSpeed,
            onValueChange = actions.onMaxSpeedChange,
            onValueCommit = actions.onMaxSpeedChange,
            enabled = slidersEnabled,
            activeColor = OssmPrimary,
            gestureCancellationKey = uiState.liveGestureEpoch
        )
        Spacer(Modifier.height(12.dp))
        VoiceDepthRangeControl(uiState = uiState, enabled = slidersEnabled, onCommit = actions.onDepthRangeChange)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.voice_plan_title),
            color = OssmPrimaryLight,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        voiceInterpretationRes(uiState.voiceInterpretation)?.let { res ->
            Text(
                stringResource(res),
                color = when (uiState.voiceInterpretation) {
                    VoiceInterpretation.PLANNED -> OssmConnected
                    VoiceInterpretation.STOPPED -> OssmOnSurface
                    else -> OssmError
                },
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp).testTag("voice_state")
            )
        }
        val motif = uiState.voiceMotif
        if (motif != null) {
            Text(
                stringResource(
                    R.string.voice_motif_line,
                    motif.depthStart, motif.depthEnd, motif.speedStart, motif.speedEnd, motif.rampSeconds,
                ),
                color = if (uiState.voiceRunning) OssmConnected else OssmOnSurface.copy(alpha = 0.85f),
                fontWeight = if (uiState.voiceRunning) FontWeight.Bold else FontWeight.Normal,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp).testTag("voice_motif")
            )
            if (uiState.voiceRunning) {
                Text(
                    stringResource(R.string.voice_motif_running),
                    color = OssmOnSurface.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        } else if (uiState.voicePlan.isEmpty()) {
            Text(
                stringResource(R.string.voice_plan_empty),
                color = OssmOnSurface.copy(alpha = 0.6f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        } else {
            uiState.voicePlan.forEachIndexed { index, step ->
                val current = uiState.voiceRunning && uiState.voiceStepIndex == index
                Text(
                    voiceStepText(step, first = index == 0),
                    color = if (current) OssmConnected else OssmOnSurface.copy(alpha = 0.85f),
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        // 2.3.28: no STOP here any more. The global STOP bar at the top stays the only STOP
        // button; the spoken STOP keyword stays active (voiceStopNow).
    }
}

/** Voix depth range: the user range shown clamped into the hard depth limits. */
@Composable
private fun VoiceDepthRangeControl(
    uiState: ControlUiState,
    enabled: Boolean,
    onCommit: (Float, Float) -> Unit,
) {
    val effective = VoiceDepthRange.effective(
        uiState.voiceDepthMin, uiState.voiceDepthMax, uiState.depthLimitMin, uiState.depthLimitMax
    )
    var draft by remember(effective) { mutableStateOf(effective) }
    Column(modifier = Modifier.fillMaxWidth().testTag("voice_depth_range")) {
        DepthRangeEditor(
            minValue = draft.first,
            maxValue = draft.second,
            enabled = enabled,
            onRangeChange = { min, max ->
                draft = VoiceDepthRange.effective(min, max, uiState.depthLimitMin, uiState.depthLimitMax)
            },
            onRangeCommit = { min, max ->
                val committed = VoiceDepthRange.effective(min, max, uiState.depthLimitMin, uiState.depthLimitMax)
                draft = committed
                onCommit(committed.first, committed.second)
            },
        )
    }
}

/** Header "i" button of the Voix mode: same 48dp footprint as the gear, opens a short bubble. */
@Composable
internal fun VoiceInfoButton(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.voice_info_cd)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(OssmSurfaceRaised)
            .border(1.dp, OssmOutline, CircleShape)
            .clickable(role = Role.Button) { open = true }
            .semantics { contentDescription = description }
            .testTag("voice_info")
    ) {
        Text(
            "i",
            color = OssmPrimaryLight,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Italic,
            fontFamily = FontFamily.Serif,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            containerColor = OssmGlass,
            title = { Text(stringResource(R.string.voice_info_title), color = OssmOnSurface, fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.voice_info_body), color = OssmOnSurface, fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = { open = false }) {
                    Text(stringResource(R.string.voice_info_close), color = OssmPrimaryLight)
                }
            },
            modifier = Modifier.testTag("voice_info_dialog")
        )
    }
}

@Composable
private fun VoiceAiStatusBlock(status: VoiceAiStatus, modelRoot: String?) {
    val (text, color) = when (status) {
        VoiceAiStatus.Idle -> stringResource(R.string.voice_ai_idle) to OssmWarning
        VoiceAiStatus.Loading -> stringResource(R.string.voice_ai_loading) to OssmWarning
        is VoiceAiStatus.Ready -> stringResource(R.string.voice_ai_ready, status.llmBackend) to OssmConnected
        is VoiceAiStatus.ModelMissing -> stringResource(R.string.voice_ai_missing) to OssmError
        is VoiceAiStatus.ModelUnreadable -> stringResource(R.string.voice_ai_unreadable) to OssmError
        is VoiceAiStatus.Error -> stringResource(R.string.voice_ai_error, status.message) to OssmError
    }
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.testTag("voice_model_state")
    )
    val paths = when (status) {
        is VoiceAiStatus.ModelMissing -> status.paths
        is VoiceAiStatus.ModelUnreadable -> status.paths
        else -> emptyList()
    }
    if (paths.isNotEmpty()) {
        paths.forEach { path ->
            Text(
                stringResource(R.string.voice_ai_expected_path, path),
                color = OssmOnSurface.copy(alpha = 0.7f),
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    } else if (status is VoiceAiStatus.Error && modelRoot != null) {
        Text(
            stringResource(R.string.voice_ai_expected_path, modelRoot),
            color = OssmOnSurface.copy(alpha = 0.7f),
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/** 2.3.31: label of a guarded (ignored) transcript. */
internal fun voiceIgnoreReasonLabel(reason: VoiceIgnoreReason): Int = when (reason) {
    VoiceIgnoreReason.EMPTY -> R.string.voice_ignored_empty
    VoiceIgnoreReason.PUNCTUATION_ONLY -> R.string.voice_ignored_punctuation
    VoiceIgnoreReason.HALLUCINATION -> R.string.voice_ignored_hallucination
    VoiceIgnoreReason.REPETITION -> R.string.voice_ignored_repetition
    VoiceIgnoreReason.TOO_LONG -> R.string.voice_ignored_too_long
    VoiceIgnoreReason.LOW_ENERGY -> R.string.voice_ignored_low_energy
}
