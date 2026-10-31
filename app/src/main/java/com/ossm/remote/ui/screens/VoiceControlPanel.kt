package com.ossm.remote.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.ossm.remote.R
import com.ossm.remote.ui.components.ControlSlider
import com.ossm.remote.ui.components.OssmButton
import com.ossm.remote.ui.components.OssmButtonKind
import com.ossm.remote.ui.components.OssmStatusLine
import com.ossm.remote.ui.components.OssmSwitchRow
import com.ossm.remote.ui.components.OssmTone
import com.ossm.remote.ui.theme.OssmCaption
import com.ossm.remote.ui.theme.OssmConnected
import com.ossm.remote.ui.theme.OssmDimens
import com.ossm.remote.ui.theme.OssmGlass
import com.ossm.remote.ui.theme.OssmGlassBorder
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmOnSurfaceMuted
import com.ossm.remote.ui.theme.OssmOutline
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmPrimaryLight
import com.ossm.remote.ui.theme.OssmSectionTitle
import com.ossm.remote.ui.theme.OssmSurfaceHigh
import com.ossm.remote.ui.theme.OssmSurfaceRaised
import com.ossm.remote.viewmodel.ControlUiState
import com.ossm.remote.voice.AsrEngine
import com.ossm.remote.voice.VoiceAiStatus
import com.ossm.remote.voice.VoiceAiUiState
import com.ossm.remote.voice.VoiceDepthRange
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

/** Hauteur du bouton Écouter / Pause micro et de la rangée « mot court » : 56 dp. */
private val VoiceActionHeight = OssmDimens.TouchTarget + OssmDimens.Space8

/** Icône de 20 dp des lignes du plan. */
private val VoiceIconSize = OssmDimens.Space16 + OssmDimens.Space4

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
    val ready = voiceAi.status is VoiceAiStatus.Ready
    // 2.3.28: la longue explication est dans le bouton « i » de l'en-tête (VoiceInfoButton).
    // Ordre : état de l'IA, écoute, réglages, puis ce qui a été entendu et le plan en résultant.
    Column(
        modifier = Modifier.fillMaxWidth().testTag("voice_panel"),
        verticalArrangement = Arrangement.spacedBy(OssmDimens.Space12)
    ) {
        VoiceAiStatusBlock(voiceAi.status, modelRoot)
        // 2.3.30: moteur de transcription (Whisper « fr » imposé, ou repli Parakeet).
        voiceAi.asrEngine?.let { engine ->
            OssmStatusLine(
                text = stringResource(voiceAsrLabelRes(engine)),
                tone = if (engine == AsrEngine.WHISPER_SMALL_FR) OssmTone.Ok else OssmTone.Warn,
                modifier = Modifier.modesStatusTag("voice_asr_engine")
            )
        }
        VoiceListenZone(
            listening = voiceAi.listening,
            ready = ready,
            shortWordStop = uiState.voiceShortWordStop,
            onShortWordStopChange = actions.onShortWordStopChange,
            onToggleListen = {
                when {
                    voiceAi.listening -> actions.onListenChange(false)
                    actions.hasMicrophonePermission() -> actions.onListenChange(true)
                    else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        )
        if (voiceAi.microphoneDenied) {
            OssmStatusLine(
                text = stringResource(R.string.voice_mic_denied),
                tone = OssmTone.Error
            )
        }
        if (!uiState.streamingReady) {
            OssmStatusLine(
                text = stringResource(R.string.ctl_live_preparing),
                tone = OssmTone.Warn
            )
        }
        ControlSlider(
            label = stringResource(R.string.voice_max_speed),
            value = uiState.voiceMaxSpeed,
            onValueChange = actions.onMaxSpeedChange,
            onValueCommit = actions.onMaxSpeedChange,
            enabled = slidersEnabled,
            activeColor = OssmPrimary,
            gestureCancellationKey = uiState.liveGestureEpoch
        )
        VoiceDepthRangeControl(uiState = uiState, enabled = slidersEnabled, onCommit = actions.onDepthRangeChange)
        VoiceHeardBlock(voiceAi)
        VoicePlanBlock(uiState)
        // 2.3.28: pas de STOP local. La barre STOP globale reste l'unique bouton ;
        // le mot parlé « stop » reste actif (voiceStopNow).
    }
}

/**
 * Zone d'écoute : état du micro lisible (icône + mot), bouton Écouter / Pause micro,
 * puis l'interrupteur « STOP rapide par mot court ».
 */
@Composable
private fun VoiceListenZone(
    listening: Boolean,
    ready: Boolean,
    shortWordStop: Boolean,
    onShortWordStopChange: (Boolean) -> Unit,
    onToggleListen: () -> Unit,
) {
    val shape = RoundedCornerShape(OssmDimens.ControlRadius)
    val stateColor = if (listening) OssmConnected else OssmOnSurfaceMuted
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(OssmSurfaceRaised)
            .border(OssmDimens.BorderWidth, if (listening) OssmConnected else OssmGlassBorder, shape)
            .padding(OssmDimens.Space12),
        verticalArrangement = Arrangement.spacedBy(OssmDimens.Space12)
    ) {
        // L'état est annoncé par TalkBack à chaque changement (écoute / pause).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .testTag("voice_listening_state")
        ) {
            Icon(
                if (listening) Icons.Filled.Mic else Icons.Filled.MicOff,
                contentDescription = null,
                tint = stateColor,
                modifier = Modifier.size(OssmDimens.Space24)
            )
            Spacer(Modifier.width(OssmDimens.Space12))
            Text(
                stringResource(if (listening) R.string.voice_listening else R.string.modes_voice_mic_paused),
                color = stateColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        OssmButton(
            text = stringResource(if (listening) R.string.voice_listen_pause else R.string.voice_listen),
            onClick = onToggleListen,
            kind = if (listening) OssmButtonKind.Secondary else OssmButtonKind.Primary,
            icon = if (listening) Icons.Filled.Pause else Icons.Filled.Mic,
            enabled = ready,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = VoiceActionHeight)
                .testTag("voice_listen")
        )
        HorizontalDivider(color = OssmGlassBorder)
        OssmSwitchRow(
            label = stringResource(
                if (shortWordStop) R.string.voice_short_word_stop_on else R.string.voice_short_word_stop_off
            ),
            checked = shortWordStop,
            onCheckedChange = onShortWordStopChange,
            modifier = Modifier
                .heightIn(min = VoiceActionHeight)
                .testTag("voice_short_word_stop")
        )
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

/**
 * Diagnostic de la dernière écoute : ce qui a été entendu, d'où vient l'interprétation, les
 * latences, et pourquoi une transcription a été ignorée. N'affiche rien tant qu'il n'y a rien.
 */
@Composable
private fun VoiceHeardBlock(voiceAi: VoiceAiUiState) {
    val transcript = voiceAi.lastTranscript?.takeIf { it.isNotBlank() }
    val hasLatency = voiceAi.speechToTextMs != null || voiceAi.textToStepsMs != null
    val source = voiceAi.interpretSource
    val ignored = voiceAi.lastIgnoredReason
    if (transcript == null && !hasLatency && source == null && ignored == null) return

    val shape = RoundedCornerShape(OssmDimens.ControlRadius)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(OssmSurfaceRaised)
            .border(OssmDimens.BorderWidth, OssmGlassBorder, shape)
            .padding(OssmDimens.Space12),
        verticalArrangement = Arrangement.spacedBy(OssmDimens.Space8)
    ) {
        Text(
            stringResource(R.string.modes_voice_heard_title).uppercase(),
            color = OssmOnSurfaceMuted,
            style = OssmSectionTitle,
            modifier = Modifier.semantics { heading() }
        )
        if (transcript != null) {
            Text(
                stringResource(R.string.voice_last_transcript, transcript),
                color = OssmOnSurface,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("voice_transcript")
            )
        }
        if (hasLatency) {
            Text(
                stringResource(
                    R.string.voice_latency,
                    voiceAi.speechToTextMs?.toString() ?: "—",
                    voiceAi.textToStepsMs?.toString() ?: "—",
                ),
                color = OssmOnSurfaceMuted,
                style = OssmCaption,
                modifier = Modifier.testTag("voice_latency")
            )
        }
        if (source != null) {
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
                color = OssmOnSurfaceMuted,
                style = OssmCaption,
                modifier = Modifier.testTag("voice_interpret_source")
            )
        }
        if (ignored != null) {
            OssmStatusLine(
                text = stringResource(R.string.voice_transcript_ignored, stringResource(voiceIgnoreReasonLabel(ignored))),
                tone = OssmTone.Warn,
                modifier = Modifier.modesStatusTag("voice_transcript_ignored")
            )
        }
    }
}

/** Séquence planifiée : état de l'interprétation, motif continu ou liste des étapes. */
@Composable
private fun VoicePlanBlock(uiState: ControlUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(OssmDimens.Space8)) {
        Text(
            stringResource(R.string.voice_plan_title),
            color = OssmOnSurfaceMuted,
            style = OssmSectionTitle,
            modifier = Modifier.semantics { heading() }
        )
        voiceInterpretationRes(uiState.voiceInterpretation)?.let { res ->
            OssmStatusLine(
                text = stringResource(res),
                tone = when (uiState.voiceInterpretation) {
                    VoiceInterpretation.PLANNED -> OssmTone.Ok
                    VoiceInterpretation.STOPPED -> OssmTone.Neutral
                    else -> OssmTone.Error
                },
                modifier = Modifier.modesStatusTag("voice_state")
            )
        }
        val motif = uiState.voiceMotif
        if (motif != null) {
            OssmStatusLine(
                text = stringResource(
                    R.string.voice_motif_line,
                    motif.depthStart, motif.depthEnd, motif.speedStart, motif.speedEnd, motif.rampSeconds,
                ),
                tone = if (uiState.voiceRunning) OssmTone.Ok else OssmTone.Neutral,
                modifier = Modifier.modesStatusTag("voice_motif")
            )
            if (uiState.voiceRunning) {
                Text(
                    stringResource(R.string.voice_motif_running),
                    color = OssmOnSurfaceMuted,
                    style = OssmCaption
                )
            }
        } else if (uiState.voicePlan.isEmpty()) {
            Text(
                stringResource(R.string.voice_plan_empty),
                color = OssmOnSurfaceMuted,
                style = OssmCaption
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(OssmDimens.Space4)) {
                uiState.voicePlan.forEachIndexed { index, step ->
                    VoiceStepRow(
                        text = voiceStepText(step, first = index == 0),
                        current = uiState.voiceRunning && uiState.voiceStepIndex == index
                    )
                }
            }
        }
    }
}

/** Une étape du plan ; l'étape courante est encadrée, marquée d'une flèche et nommée. */
@Composable
private fun VoiceStepRow(text: String, current: Boolean) {
    val shape = RoundedCornerShape(OssmDimens.FieldRadius)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (current) OssmSurfaceHigh else Color.Transparent)
            .border(OssmDimens.BorderWidth, if (current) OssmConnected else Color.Transparent, shape)
            .padding(horizontal = OssmDimens.Space8, vertical = OssmDimens.Space4)
            .semantics(mergeDescendants = true) {}
    ) {
        Box(modifier = Modifier.size(VoiceIconSize)) {
            if (current) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = OssmConnected,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.width(OssmDimens.Space8))
        Column {
            Text(
                text,
                color = if (current) OssmConnected else OssmOnSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal
            )
            if (current) {
                Text(
                    stringResource(R.string.modes_voice_step_current),
                    color = OssmOnSurfaceMuted,
                    style = OssmCaption
                )
            }
        }
    }
}

/** Bouton « i » de l'en-tête du mode Voix : même empreinte de 48 dp que l'engrenage, ouvre une bulle courte. */
@Composable
internal fun VoiceInfoButton(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.voice_info_cd)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(OssmDimens.TouchTarget)
            .clip(CircleShape)
            .background(OssmSurfaceRaised)
            .border(OssmDimens.BorderWidth, OssmOutline, CircleShape)
            .clickable(role = Role.Button) { open = true }
            .semantics { contentDescription = description }
            .testTag("voice_info")
    ) {
        // La lettre est décorative : le bouton se lit par sa description.
        Text(
            "i",
            color = OssmPrimaryLight,
            style = MaterialTheme.typography.titleLarge,
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
            title = {
                Text(
                    stringResource(R.string.voice_info_title),
                    color = OssmOnSurface,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            // Le texte défile : il reste entièrement lisible sur petit écran et à 130 % de police.
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(R.string.voice_info_body),
                        color = OssmOnSurface,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                OssmButton(
                    text = stringResource(R.string.voice_info_close),
                    onClick = { open = false },
                    kind = OssmButtonKind.Text
                )
            },
            modifier = Modifier.testTag("voice_info_dialog")
        )
    }
}

/** État de l'IA vocale (icône + texte) et, si le modèle manque, les chemins attendus. */
@Composable
private fun VoiceAiStatusBlock(status: VoiceAiStatus, modelRoot: String?) {
    val (text, tone) = when (status) {
        VoiceAiStatus.Idle -> stringResource(R.string.voice_ai_idle) to OssmTone.Warn
        VoiceAiStatus.Loading -> stringResource(R.string.voice_ai_loading) to OssmTone.Warn
        is VoiceAiStatus.Ready -> stringResource(R.string.voice_ai_ready, status.llmBackend) to OssmTone.Ok
        is VoiceAiStatus.ModelMissing -> stringResource(R.string.voice_ai_missing) to OssmTone.Error
        is VoiceAiStatus.ModelUnreadable -> stringResource(R.string.voice_ai_unreadable) to OssmTone.Error
        is VoiceAiStatus.Error -> stringResource(R.string.voice_ai_error, status.message) to OssmTone.Error
    }
    Column(verticalArrangement = Arrangement.spacedBy(OssmDimens.Space4)) {
        OssmStatusLine(
            text = text,
            tone = tone,
            modifier = Modifier.modesStatusTag("voice_model_state")
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
                    color = OssmOnSurfaceMuted,
                    style = OssmCaption,
                    modifier = Modifier.padding(start = OssmDimens.Space24)
                )
            }
        } else if (status is VoiceAiStatus.Error && modelRoot != null) {
            Text(
                stringResource(R.string.voice_ai_expected_path, modelRoot),
                color = OssmOnSurfaceMuted,
                style = OssmCaption,
                modifier = Modifier.padding(start = OssmDimens.Space24)
            )
        }
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
