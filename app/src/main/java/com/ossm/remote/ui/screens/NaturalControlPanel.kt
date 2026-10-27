package com.ossm.remote.ui.screens

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ossm.remote.R
import com.ossm.remote.natural.NaturalAiEndpoint
import com.ossm.remote.natural.NaturalAiFallback
import com.ossm.remote.natural.NaturalAiFallbackKind
import com.ossm.remote.natural.NaturalAiFailureKind
import com.ossm.remote.natural.NaturalAiModelState
import com.ossm.remote.natural.NaturalAiProblem
import com.ossm.remote.natural.OllamaNaturalPlannerConfig
import com.ossm.remote.natural.NaturalMotif
import com.ossm.remote.remote.RemoteSessionView
import com.ossm.remote.ui.components.ControlSlider
import com.ossm.remote.ui.theme.OssmAccent
import com.ossm.remote.ui.theme.OssmConnected
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmPrimaryLight
import com.ossm.remote.ui.theme.OssmWarning
import com.ossm.remote.viewmodel.ControlUiState
import com.ossm.remote.viewmodel.NaturalPlannerStatus

/** Callbacks of the Naturel panel. Defaults are no-ops so existing callers stay valid. */
data class NaturalControlActions(
    val onStart: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onEnergyChange: (Float) -> Unit = {},
    val onVarietyChange: (Float) -> Unit = {},
    val onMaxSpeedChange: (Float) -> Unit = {},
    val onAiEnabledChange: (Boolean) -> Unit = {},
    val onAiAddressChange: (String) -> Unit = {},
    /** "Charger Qwen3" and "Reessayer": background model pre-load. */
    val onAiLoadModel: () -> Unit = {},
    /** "Passer par le relais de raisonnement (PC)": port 11435 + think:true. */
    val onAiReasoningRelayChange: (Boolean) -> Unit = {},
)

internal fun naturalStatusLabelRes(status: NaturalPlannerStatus): Int = when (status) {
    NaturalPlannerStatus.LOCAL -> R.string.natural_status_local
    NaturalPlannerStatus.AI_WAITING_MODEL -> R.string.natural_status_waiting
    NaturalPlannerStatus.AI_READY -> R.string.natural_status_ready
    NaturalPlannerStatus.AI_CONNECTED -> R.string.natural_status_connected
    NaturalPlannerStatus.AI_UNAVAILABLE -> R.string.natural_status_unavailable
}

@Composable
internal fun naturalAiFallbackText(fallback: NaturalAiFallback): String = stringResource(
    R.string.natural_ai_fallback,
    when (fallback.kind) {
        NaturalAiFallbackKind.RESERVE_EXHAUSTED ->
            stringResource(R.string.natural_ai_fallback_reserve, fallback.latencySeconds, fallback.reserveSeconds)
        NaturalAiFallbackKind.AI_ERROR -> fallback.problem?.let { naturalAiProblemText(it) } ?: ""
        NaturalAiFallbackKind.MODEL_UNLOADED -> stringResource(R.string.natural_ai_fallback_unloaded)
    },
)

@Composable
internal fun naturalAiProblemText(problem: NaturalAiProblem): String = when (problem.kind) {
    NaturalAiFailureKind.INVALID_ADDRESS -> stringResource(R.string.natural_ai_cause_invalid_address)
    NaturalAiFailureKind.UNREACHABLE -> stringResource(R.string.natural_ai_cause_unreachable) +
        if (problem.detail.isBlank()) "" else "\n${problem.detail}"
    NaturalAiFailureKind.LOAD_TIMEOUT -> stringResource(R.string.natural_ai_cause_timeout, problem.seconds)
    NaturalAiFailureKind.MODEL_MISSING ->
        stringResource(R.string.natural_ai_cause_model_missing, OllamaNaturalPlannerConfig.DEFAULT_MODEL)
    NaturalAiFailureKind.INVALID_JSON -> stringResource(R.string.natural_ai_cause_invalid_json)
    NaturalAiFailureKind.HTTP_ERROR -> stringResource(R.string.natural_ai_cause_http, problem.httpCode)
}

internal fun naturalMotifLabelRes(wireName: String?): Int? = when (wireName?.let(NaturalMotif::fromWire)) {
    NaturalMotif.APPROACH_HOLD_RELEASE -> R.string.natural_motif_approach_hold_release
    NaturalMotif.TEASE_PAUSE -> R.string.natural_motif_tease_pause
    NaturalMotif.WAVE -> R.string.natural_motif_wave
    NaturalMotif.LADDER -> R.string.natural_motif_ladder
    NaturalMotif.REST -> R.string.natural_motif_rest
    null -> null
}

@Composable
internal fun NaturalControlPanel(
    uiState: ControlUiState,
    remoteSession: RemoteSessionView,
    connected: Boolean,
    slidersEnabled: Boolean,
    actions: NaturalControlActions,
    depthEditor: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().testTag("natural_panel")) {
        Text(
            stringResource(R.string.natural_intro),
            color = OssmOnSurface.copy(alpha = 0.7f),
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 10.dp)
        )
        if (remoteSession.active) {
            Text(
                stringResource(R.string.natural_remote_hint),
                color = OssmWarning,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        ControlSlider(
            label = stringResource(R.string.natural_max_speed),
            value = if (remoteSession.active) remoteSession.speedMax else uiState.naturalMaxSpeed,
            onValueChange = actions.onMaxSpeedChange,
            onValueCommit = actions.onMaxSpeedChange,
            enabled = slidersEnabled,
            activeColor = OssmPrimary,
            currentMarker = uiState.naturalSpeedCurrent,
            gestureCancellationKey = uiState.liveGestureEpoch
        )
        Spacer(Modifier.height(10.dp))
        ControlSlider(
            label = stringResource(R.string.natural_energy),
            value = uiState.naturalEnergy,
            onValueChange = actions.onEnergyChange,
            onValueCommit = actions.onEnergyChange,
            enabled = slidersEnabled,
            activeColor = OssmAccent,
            gestureCancellationKey = uiState.liveGestureEpoch
        )
        Spacer(Modifier.height(10.dp))
        ControlSlider(
            label = stringResource(R.string.natural_variety),
            value = uiState.naturalVariety,
            onValueChange = actions.onVarietyChange,
            onValueCommit = actions.onVarietyChange,
            enabled = slidersEnabled,
            activeColor = OssmWarning,
            gestureCancellationKey = uiState.liveGestureEpoch
        )
        Spacer(Modifier.height(10.dp))
        depthEditor()
        Spacer(Modifier.height(12.dp))

        val motifRes = naturalMotifLabelRes(uiState.naturalMotif)
        when {
            uiState.naturalRunning && uiState.naturalAiWaitingFirstPlan -> Text(
                stringResource(R.string.natural_ai_waiting_first_plan, uiState.naturalAiWaitSeconds),
                color = OssmWarning,
                fontSize = 12.sp,
                modifier = Modifier.testTag("natural_ai_waiting")
            )
            uiState.naturalRunning -> {
                Text(
                    stringResource(
                        R.string.natural_running,
                        motifRes?.let { stringResource(it) } ?: "..."
                    ),
                    color = OssmConnected,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                val speed = ((uiState.naturalSpeedCurrent ?: 0f) * 100).toInt()
                val low = ((uiState.naturalDepthMinCurrent ?: uiState.depthMin) * 100).toInt()
                val high = ((uiState.naturalDepthMaxCurrent ?: uiState.depthMax) * 100).toInt()
                Text(
                    stringResource(R.string.natural_values, speed, low, high),
                    color = OssmOnSurface.copy(alpha = 0.75f),
                    fontSize = 12.sp
                )
            }
            uiState.naturalStartPending -> Text(
                stringResource(R.string.natural_preparing),
                color = OssmWarning,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = actions.onStart,
                enabled = slidersEnabled && !remoteSession.active &&
                    !uiState.naturalRunning && !uiState.naturalStartPending &&
                    uiState.naturalMaxSpeed > 0f,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("natural_start"),
                colors = ButtonDefaults.buttonColors(containerColor = OssmConnected)
            ) {
                Text(stringResource(R.string.natural_start), textAlign = TextAlign.Center, lineHeight = 16.sp)
            }
            Button(
                onClick = actions.onStop,
                enabled = connected,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("natural_stop"),
                colors = ButtonDefaults.buttonColors(containerColor = OssmWarning)
            ) {
                Text(stringResource(R.string.natural_stop), textAlign = TextAlign.Center, lineHeight = 16.sp)
            }
        }

        Spacer(Modifier.height(16.dp))
        NaturalAiSection(uiState = uiState, actions = actions)
    }
}

/**
 * Optional AI in two explicit steps: "Charger Qwen3" (pre-load on the PC, never cancelled by
 * STOP) then "Utiliser l'IA" once the model is loaded. Choosing "Utiliser l'IA" during the load
 * connects automatically when it finishes. Motion never waits for any of this.
 */
@Composable
private fun NaturalAiSection(uiState: ControlUiState, actions: NaturalControlActions) {
    Column(modifier = Modifier.fillMaxWidth().testTag("natural_ai_section")) {
        Text(
            stringResource(R.string.natural_ai_toggle),
            color = OssmPrimaryLight,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            stringResource(R.string.natural_ai_hint),
            color = OssmOnSurface.copy(alpha = 0.6f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
        )
        var addressDraft by remember { mutableStateOf(uiState.naturalAiAddress) }
        LaunchedEffect(uiState.naturalAiAddress) {
            if (addressDraft.isBlank()) addressDraft = uiState.naturalAiAddress
        }
        val invalid = addressDraft.isNotBlank() && NaturalAiEndpoint.parse(addressDraft) == null
        val draftChanged = addressDraft.trim() != uiState.naturalAiAddress
        OutlinedTextField(
            value = addressDraft,
            onValueChange = { addressDraft = it.take(200) },
            label = { Text(stringResource(R.string.natural_ai_address)) },
            singleLine = true,
            isError = invalid,
            supportingText = if (invalid) {
                { Text(stringResource(R.string.natural_ai_address_invalid)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { actions.onAiAddressChange(addressDraft) }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("natural_ai_address")
        )
        if (draftChanged) {
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = { actions.onAiAddressChange(addressDraft) },
                enabled = !invalid,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(android.R.string.ok))
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(
                    value = uiState.naturalAiReasoningRelay,
                    role = Role.Switch,
                    onValueChange = actions.onAiReasoningRelayChange
                )
        ) {
            Text(
                stringResource(R.string.natural_ai_reasoning_relay),
                color = OssmOnSurface,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = uiState.naturalAiReasoningRelay,
                onCheckedChange = null,
                modifier = Modifier.testTag("natural_ai_reasoning_relay")
            )
        }
        Text(
            stringResource(R.string.natural_ai_reasoning_relay_hint),
            color = OssmOnSurface.copy(alpha = 0.6f),
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        Spacer(Modifier.height(8.dp))
        val modelState = uiState.naturalAiModelState
        val problem = uiState.naturalAiProblem
        val modelText = when (modelState) {
            NaturalAiModelState.IDLE -> if (uiState.naturalAiModelUnloaded) {
                stringResource(R.string.natural_ai_model_unloaded)
            } else {
                stringResource(R.string.natural_ai_model_idle)
            }
            NaturalAiModelState.LOADING ->
                stringResource(R.string.natural_ai_model_loading, uiState.naturalAiLoadSeconds)
            NaturalAiModelState.LOADED -> stringResource(R.string.natural_ai_model_loaded)
            NaturalAiModelState.FAILED -> stringResource(
                R.string.natural_ai_unavailable,
                problem?.let { naturalAiProblemText(it) } ?: ""
            )
        }
        Text(
            modelText,
            color = when (modelState) {
                NaturalAiModelState.LOADED -> OssmConnected
                NaturalAiModelState.FAILED -> OssmError
                NaturalAiModelState.LOADING -> OssmWarning
                NaturalAiModelState.IDLE ->
                    if (uiState.naturalAiModelUnloaded) OssmWarning else OssmOnSurface.copy(alpha = 0.7f)
            },
            fontSize = 12.sp,
            modifier = Modifier.testTag("natural_ai_model_state")
        )
        if (modelState == NaturalAiModelState.LOADING && uiState.naturalAiModelInstalled == true) {
            Text(
                stringResource(R.string.natural_ai_model_present, OllamaNaturalPlannerConfig.DEFAULT_MODEL),
                color = OssmOnSurface.copy(alpha = 0.7f),
                fontSize = 11.sp
            )
        }

        val planRejected = uiState.naturalPlannerStatus == NaturalPlannerStatus.AI_UNAVAILABLE &&
            modelState != NaturalAiModelState.FAILED
        val loadOrRetry: () -> Unit = {
            if (draftChanged && !invalid) actions.onAiAddressChange(addressDraft)
            actions.onAiLoadModel()
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                modelState == NaturalAiModelState.IDLE -> Button(
                    onClick = loadOrRetry,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("natural_ai_load"),
                    colors = ButtonDefaults.buttonColors(containerColor = OssmPrimary)
                ) {
                    Text(stringResource(R.string.natural_ai_load_button), textAlign = TextAlign.Center)
                }
                modelState == NaturalAiModelState.FAILED || planRejected -> Button(
                    onClick = loadOrRetry,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("natural_ai_retry"),
                    colors = ButtonDefaults.buttonColors(containerColor = OssmWarning)
                ) {
                    Text(stringResource(R.string.natural_ai_retry), textAlign = TextAlign.Center)
                }
            }
            if (!uiState.naturalAiEnabled &&
                (modelState == NaturalAiModelState.LOADED || modelState == NaturalAiModelState.LOADING)
            ) {
                Button(
                    onClick = { actions.onAiEnabledChange(true) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("natural_ai_use"),
                    colors = ButtonDefaults.buttonColors(containerColor = OssmConnected)
                ) {
                    Text(stringResource(R.string.natural_ai_use), textAlign = TextAlign.Center)
                }
            }
            if (uiState.naturalAiEnabled) {
                OutlinedButton(
                    onClick = { actions.onAiEnabledChange(false) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("natural_ai_stop_use")
                ) {
                    Text(stringResource(R.string.natural_ai_stop_use), textAlign = TextAlign.Center)
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        val status = uiState.naturalPlannerStatus
        val statusText = stringResource(naturalStatusLabelRes(status)) +
            if (status == NaturalPlannerStatus.AI_UNAVAILABLE && problem != null) {
                " - " + naturalAiProblemText(problem)
            } else {
                ""
            }
        Text(
            statusText,
            color = when (status) {
                NaturalPlannerStatus.AI_CONNECTED, NaturalPlannerStatus.AI_READY -> OssmConnected
                NaturalPlannerStatus.AI_UNAVAILABLE -> OssmError
                else -> OssmOnSurface.copy(alpha = 0.7f)
            },
            fontSize = 12.sp,
            modifier = Modifier.testTag("natural_ai_status")
        )
        uiState.naturalAiFallback?.takeIf { uiState.naturalRunning }?.let { fallback ->
            Text(
                naturalAiFallbackText(fallback),
                color = OssmError,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 2.dp).testTag("natural_ai_fallback")
            )
        }
        val reserve = uiState.naturalAiReserveSeconds
        if (uiState.naturalAiEnabled && uiState.naturalRunning && reserve != null) {
            val latency = uiState.naturalAiLastLatencySeconds
            Text(
                if (latency != null) {
                    stringResource(R.string.natural_ai_reserve_latency, reserve, latency)
                } else {
                    stringResource(R.string.natural_ai_reserve, reserve)
                },
                color = OssmOnSurface.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp).testTag("natural_ai_reserve")
            )
        }
    }
}
