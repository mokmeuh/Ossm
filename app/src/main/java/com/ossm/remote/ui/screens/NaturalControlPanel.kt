package com.ossm.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import com.ossm.remote.ui.components.OssmButton
import com.ossm.remote.ui.components.OssmButtonKind
import com.ossm.remote.ui.components.OssmStatusLine
import com.ossm.remote.ui.components.OssmSwitchRow
import com.ossm.remote.ui.components.OssmTone
import com.ossm.remote.ui.theme.OssmBackground
import com.ossm.remote.ui.theme.OssmCaption
import com.ossm.remote.ui.theme.OssmConnected
import com.ossm.remote.ui.theme.OssmDimens
import com.ossm.remote.ui.theme.OssmDisplayValue
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmGlassBorder
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmOnSurfaceMuted
import com.ossm.remote.ui.theme.OssmOutline
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmSectionTitle
import com.ossm.remote.ui.theme.OssmSensation
import com.ossm.remote.ui.theme.OssmSurfaceHigh
import com.ossm.remote.ui.theme.OssmSurfaceRaised
import com.ossm.remote.ui.theme.OssmValue
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

/** Hauteur des boutons Démarrer / Arrêter : 56 dp, au-dessus du minimum tactile. */
private val NaturalActionHeight = OssmDimens.TouchTarget + OssmDimens.Space8

/** Icône de 20 dp des boutons et des puces d'état. */
private val NaturalIconSize = OssmDimens.Space16 + OssmDimens.Space4

/**
 * Étiquette de test + sémantique fusionnée : le nœud étiqueté porte le texte de ses enfants,
 * donc les tests et les lecteurs d'écran voient la ligne d'état comme un seul élément.
 */
internal fun Modifier.modesStatusTag(tag: String): Modifier =
    this.semantics(mergeDescendants = true) {}.testTag(tag)

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
    Column(
        modifier = Modifier.fillMaxWidth().testTag("natural_panel"),
        verticalArrangement = Arrangement.spacedBy(OssmDimens.Space12)
    ) {
        Text(
            stringResource(R.string.natural_intro),
            color = OssmOnSurfaceMuted,
            style = OssmCaption
        )
        // Session distante : le site pilote la vitesse, bornée par l'enveloppe locale.
        if (remoteSession.active) {
            OssmStatusLine(
                text = stringResource(R.string.natural_remote_hint),
                tone = OssmTone.Warn
            )
        }
        // Vitesse = turquoise ; énergie et variété règlent le ressenti du geste = lilas.
        ControlSlider(
            label = stringResource(R.string.natural_max_speed),
            value = if (remoteSession.active) remoteSession.speedMax else uiState.naturalMaxSpeed,
            onValueChange = actions.onMaxSpeedChange,
            onValueCommit = actions.onMaxSpeedChange,
            enabled = slidersEnabled,
            activeColor = OssmPrimary,
            currentMarker = uiState.naturalSpeedCurrent,
            gestureCancellationKey = uiState.liveGestureEpoch,
            // En session, le curseur affiche et règle le plafond de l'enveloppe.
            limited = remoteSession.active
        )
        ControlSlider(
            label = stringResource(R.string.natural_energy),
            value = uiState.naturalEnergy,
            onValueChange = actions.onEnergyChange,
            onValueCommit = actions.onEnergyChange,
            enabled = slidersEnabled,
            activeColor = OssmSensation,
            gestureCancellationKey = uiState.liveGestureEpoch
        )
        ControlSlider(
            label = stringResource(R.string.natural_variety),
            value = uiState.naturalVariety,
            onValueChange = actions.onVarietyChange,
            onValueCommit = actions.onVarietyChange,
            enabled = slidersEnabled,
            activeColor = OssmSensation,
            gestureCancellationKey = uiState.liveGestureEpoch
        )
        depthEditor()

        NaturalMotionStatus(uiState)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OssmDimens.Space12)
        ) {
            OssmButton(
                text = stringResource(R.string.natural_start),
                onClick = actions.onStart,
                icon = Icons.Filled.PlayArrow,
                enabled = slidersEnabled && !remoteSession.active &&
                    !uiState.naturalRunning && !uiState.naturalStartPending &&
                    uiState.naturalMaxSpeed > 0f,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = NaturalActionHeight)
                    .testTag("natural_start")
            )
            // Second arrêt, jaune pour le distinguer du STOP rouge global (qui reste en bas).
            NaturalStopButton(
                onClick = actions.onStop,
                enabled = connected,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = NaturalActionHeight)
                    .testTag("natural_stop")
            )
        }

        NaturalAiSection(uiState = uiState, actions = actions)
    }
}

/** État du mouvement, trois variantes exclusives : attente du premier plan, en cours, préparation. */
@Composable
private fun NaturalMotionStatus(uiState: ControlUiState) {
    when {
        uiState.naturalRunning && uiState.naturalAiWaitingFirstPlan -> OssmStatusLine(
            text = stringResource(R.string.natural_ai_waiting_first_plan, uiState.naturalAiWaitSeconds),
            tone = OssmTone.Warn,
            modifier = Modifier.modesStatusTag("natural_ai_waiting")
        )
        uiState.naturalRunning -> NaturalRunningTile(uiState)
        uiState.naturalStartPending -> OssmStatusLine(
            text = stringResource(R.string.natural_preparing),
            tone = OssmTone.Warn
        )
    }
}

/** Mouvement en cours : motif, vitesse courante en grand et plage de profondeur. */
@Composable
private fun NaturalRunningTile(uiState: ControlUiState) {
    val motifRes = naturalMotifLabelRes(uiState.naturalMotif)
    val speed = ((uiState.naturalSpeedCurrent ?: 0f) * 100).toInt()
    val low = ((uiState.naturalDepthMinCurrent ?: uiState.depthMin) * 100).toInt()
    val high = ((uiState.naturalDepthMaxCurrent ?: uiState.depthMax) * 100).toInt()
    val spoken = stringResource(R.string.natural_values, speed, low, high)
    val shape = RoundedCornerShape(OssmDimens.ControlRadius)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(OssmSurfaceRaised)
            .border(OssmDimens.BorderWidth, OssmConnected, shape)
            .padding(OssmDimens.Space12)
            .testTag("natural_running"),
        verticalArrangement = Arrangement.spacedBy(OssmDimens.Space8)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = OssmConnected,
                modifier = Modifier.size(OssmDimens.Space24)
            )
            Spacer(Modifier.width(OssmDimens.Space8))
            Text(
                stringResource(R.string.natural_running, motifRes?.let { stringResource(it) } ?: "..."),
                color = OssmConnected,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
        // Les valeurs sont lues d'une seule phrase par les lecteurs d'écran.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { contentDescription = spoken },
            horizontalArrangement = Arrangement.spacedBy(OssmDimens.Space16)
        ) {
            NaturalValueCell(
                label = stringResource(R.string.modes_natural_speed_label),
                value = stringResource(R.string.modes_percent, speed),
                valueStyle = OssmDisplayValue,
                modifier = Modifier.weight(1f)
            )
            NaturalValueCell(
                label = stringResource(R.string.modes_natural_range_label),
                value = stringResource(R.string.modes_percent_range, low, high),
                valueStyle = OssmValue,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun NaturalValueCell(
    label: String,
    value: String,
    valueStyle: TextStyle,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(label, color = OssmOnSurfaceMuted, style = OssmCaption)
        Text(value, color = OssmOnSurface, style = valueStyle)
    }
}

/** Bouton « Arrêter » du mode : jaune, texte sombre, actif dès que la machine est connectée. */
@Composable
private fun NaturalStopButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(OssmDimens.ControlRadius),
        contentPadding = PaddingValues(horizontal = OssmDimens.Space16, vertical = OssmDimens.Space8),
        colors = ButtonDefaults.buttonColors(
            containerColor = OssmWarning,
            contentColor = OssmBackground,
            disabledContainerColor = OssmSurfaceHigh,
            disabledContentColor = OssmOnSurfaceMuted
        ),
        modifier = modifier
    ) {
        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(NaturalIconSize))
        Spacer(Modifier.width(OssmDimens.Space8))
        Text(stringResource(R.string.natural_stop))
    }
}

/** État affiché d'une étape : icône + mot + couleur (jamais la couleur seule). */
private data class NaturalStepState(val icon: ImageVector, val color: Color, val label: String)

@Composable
private fun naturalStepTodo() = NaturalStepState(
    Icons.Filled.RadioButtonUnchecked, OssmOnSurfaceMuted, stringResource(R.string.modes_state_todo)
)

@Composable
private fun naturalStepRunning() = NaturalStepState(
    Icons.Filled.HourglassEmpty, OssmWarning, stringResource(R.string.modes_state_running)
)

@Composable
private fun naturalStepWaiting() = NaturalStepState(
    Icons.Filled.Schedule, OssmWarning, stringResource(R.string.modes_state_waiting)
)

@Composable
private fun naturalStepDone() = NaturalStepState(
    Icons.Filled.CheckCircle, OssmConnected, stringResource(R.string.modes_state_done)
)

@Composable
private fun naturalStepActive() = NaturalStepState(
    Icons.Filled.CheckCircle, OssmConnected, stringResource(R.string.modes_state_active)
)

@Composable
private fun naturalStepFailed() = NaturalStepState(
    Icons.Filled.ErrorOutline, OssmError, stringResource(R.string.modes_state_failed)
)

/**
 * Bloc d'une étape numérotée : pastille, titre « Étape N sur 2 », puce d'état, puis le contenu.
 * Les deux blocs sont reliés par un trait pour montrer l'ordre.
 */
@Composable
private fun NaturalStepBlock(
    number: Int,
    title: String,
    state: NaturalStepState,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(OssmDimens.ControlRadius)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(OssmSurfaceRaised)
            .border(OssmDimens.BorderWidth, OssmGlassBorder, shape)
            .padding(OssmDimens.Space12),
        horizontalArrangement = Arrangement.spacedBy(OssmDimens.Space12)
    ) {
        // Le numéro est déjà dans le titre : la pastille est décorative pour TalkBack.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(OssmDimens.Space32)
                .clip(CircleShape)
                .background(OssmSurfaceHigh)
                .border(OssmDimens.BorderWidth, state.color, CircleShape)
                .clearAndSetSemantics {}
        ) {
            Text(number.toString(), color = state.color, style = OssmValue)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(OssmDimens.Space8)
        ) {
            Text(
                title,
                color = OssmOnSurface,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() }
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics(mergeDescendants = true) {}
            ) {
                Icon(
                    state.icon,
                    contentDescription = null,
                    tint = state.color,
                    modifier = Modifier.size(NaturalIconSize)
                )
                Spacer(Modifier.width(OssmDimens.Space8))
                Text(
                    state.label,
                    color = state.color,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
            content()
        }
    }
}

/** Trait vertical entre l'étape 1 et l'étape 2, aligné sur le centre des pastilles. */
@Composable
private fun NaturalStepConnector() {
    Box(
        modifier = Modifier
            .padding(start = OssmDimens.Space12 + OssmDimens.Space16 - OssmDimens.BorderWidth)
            .width(OssmDimens.BorderWidth * 2)
            .height(OssmDimens.Space12)
            .background(OssmOutline)
            .clearAndSetSemantics {}
    )
}

/**
 * IA facultative en deux étapes explicites : « Charger Qwen3 » (pré-chargement sur le PC, jamais
 * annulé par STOP), puis « Utiliser l'IA » une fois le modèle chargé. Choisir « Utiliser l'IA »
 * pendant le chargement connecte automatiquement à la fin. Le mouvement n'attend jamais l'IA.
 */
@Composable
private fun NaturalAiSection(uiState: ControlUiState, actions: NaturalControlActions) {
    val modelState = uiState.naturalAiModelState
    val problem = uiState.naturalAiProblem
    var addressDraft by remember { mutableStateOf(uiState.naturalAiAddress) }
    LaunchedEffect(uiState.naturalAiAddress) {
        if (addressDraft.isBlank()) addressDraft = uiState.naturalAiAddress
    }
    val invalid = addressDraft.isNotBlank() && NaturalAiEndpoint.parse(addressDraft) == null
    val draftChanged = addressDraft.trim() != uiState.naturalAiAddress
    val planRejected = uiState.naturalPlannerStatus == NaturalPlannerStatus.AI_UNAVAILABLE &&
        modelState != NaturalAiModelState.FAILED
    val loadOrRetry: () -> Unit = {
        if (draftChanged && !invalid) actions.onAiAddressChange(addressDraft)
        actions.onAiLoadModel()
    }

    Column(
        modifier = Modifier.fillMaxWidth().testTag("natural_ai_section"),
        verticalArrangement = Arrangement.spacedBy(OssmDimens.Space12)
    ) {
        HorizontalDivider(color = OssmGlassBorder)
        Column(verticalArrangement = Arrangement.spacedBy(OssmDimens.Space4)) {
            Text(
                stringResource(R.string.natural_ai_toggle).uppercase(),
                color = OssmOnSurfaceMuted,
                style = OssmSectionTitle,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                stringResource(R.string.natural_ai_hint),
                color = OssmOnSurfaceMuted,
                style = OssmCaption
            )
        }

        // Adresse du PC : commune aux deux étapes, validée localement (réseau local ou Tailscale).
        Column(verticalArrangement = Arrangement.spacedBy(OssmDimens.Space8)) {
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
                textStyle = OssmValue,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = OssmSurfaceRaised,
                    unfocusedContainerColor = OssmSurfaceRaised,
                    errorContainerColor = OssmSurfaceRaised,
                    unfocusedBorderColor = OssmOutline
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { actions.onAiAddressChange(addressDraft) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("natural_ai_address")
            )
            if (draftChanged) {
                OssmButton(
                    text = stringResource(android.R.string.ok),
                    onClick = { actions.onAiAddressChange(addressDraft) },
                    kind = OssmButtonKind.Secondary,
                    enabled = !invalid
                )
            }
        }

        Column {
            // Étape 1 : modèle sur le PC.
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
            val modelColor = when (modelState) {
                NaturalAiModelState.LOADED -> OssmConnected
                NaturalAiModelState.FAILED -> OssmError
                NaturalAiModelState.LOADING -> OssmWarning
                NaturalAiModelState.IDLE ->
                    if (uiState.naturalAiModelUnloaded) OssmWarning else OssmOnSurfaceMuted
            }
            val step1State = when (modelState) {
                NaturalAiModelState.IDLE -> naturalStepTodo()
                NaturalAiModelState.LOADING -> naturalStepRunning()
                NaturalAiModelState.LOADED -> naturalStepDone()
                NaturalAiModelState.FAILED -> naturalStepFailed()
            }
            NaturalStepBlock(
                number = 1,
                title = stringResource(R.string.modes_natural_step1),
                state = step1State,
                modifier = Modifier.testTag("natural_ai_step1")
            ) {
                Text(
                    modelText,
                    color = modelColor,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("natural_ai_model_state")
                )
                if (modelState == NaturalAiModelState.LOADING && uiState.naturalAiModelInstalled == true) {
                    OssmStatusLine(
                        text = stringResource(
                            R.string.natural_ai_model_present,
                            OllamaNaturalPlannerConfig.DEFAULT_MODEL
                        )
                    )
                }
                when {
                    modelState == NaturalAiModelState.IDLE -> OssmButton(
                        text = stringResource(R.string.natural_ai_load_button),
                        onClick = loadOrRetry,
                        icon = Icons.Filled.CloudDownload,
                        modifier = Modifier.fillMaxWidth().testTag("natural_ai_load")
                    )
                    modelState == NaturalAiModelState.FAILED || planRejected -> OssmButton(
                        text = stringResource(R.string.natural_ai_retry),
                        onClick = loadOrRetry,
                        icon = Icons.Filled.Refresh,
                        modifier = Modifier.fillMaxWidth().testTag("natural_ai_retry")
                    )
                }
            }

            NaturalStepConnector()

            // Étape 2 : choisir l'IA comme planificateur.
            val status = uiState.naturalPlannerStatus
            val step2State = if (!uiState.naturalAiEnabled) {
                naturalStepTodo()
            } else {
                when (status) {
                    NaturalPlannerStatus.AI_CONNECTED, NaturalPlannerStatus.AI_READY -> naturalStepActive()
                    NaturalPlannerStatus.AI_UNAVAILABLE -> naturalStepFailed()
                    NaturalPlannerStatus.AI_WAITING_MODEL, NaturalPlannerStatus.LOCAL -> naturalStepWaiting()
                }
            }
            val statusText = stringResource(naturalStatusLabelRes(status)) +
                if (status == NaturalPlannerStatus.AI_UNAVAILABLE && problem != null) {
                    " - " + naturalAiProblemText(problem)
                } else {
                    ""
                }
            NaturalStepBlock(
                number = 2,
                title = stringResource(R.string.modes_natural_step2),
                state = step2State,
                modifier = Modifier.testTag("natural_ai_step2")
            ) {
                OssmStatusLine(
                    text = statusText,
                    tone = when (status) {
                        NaturalPlannerStatus.AI_CONNECTED, NaturalPlannerStatus.AI_READY -> OssmTone.Ok
                        NaturalPlannerStatus.AI_UNAVAILABLE -> OssmTone.Error
                        else -> OssmTone.Neutral
                    },
                    modifier = Modifier.modesStatusTag("natural_ai_status")
                )
                if (!uiState.naturalAiEnabled) {
                    when (modelState) {
                        NaturalAiModelState.LOADING -> Text(
                            stringResource(R.string.modes_natural_step2_auto),
                            color = OssmOnSurfaceMuted,
                            style = OssmCaption
                        )
                        NaturalAiModelState.IDLE, NaturalAiModelState.FAILED -> Text(
                            stringResource(R.string.modes_natural_step2_locked),
                            color = OssmOnSurfaceMuted,
                            style = OssmCaption
                        )
                        NaturalAiModelState.LOADED -> Unit
                    }
                }
                OssmSwitchRow(
                    label = stringResource(R.string.natural_ai_reasoning_relay),
                    checked = uiState.naturalAiReasoningRelay,
                    onCheckedChange = actions.onAiReasoningRelayChange,
                    supporting = stringResource(R.string.natural_ai_reasoning_relay_hint),
                    modifier = Modifier.testTag("natural_ai_reasoning_relay")
                )
                if (!uiState.naturalAiEnabled &&
                    (modelState == NaturalAiModelState.LOADED || modelState == NaturalAiModelState.LOADING)
                ) {
                    OssmButton(
                        text = stringResource(R.string.natural_ai_use),
                        onClick = { actions.onAiEnabledChange(true) },
                        icon = Icons.Filled.Check,
                        modifier = Modifier.fillMaxWidth().testTag("natural_ai_use")
                    )
                }
                if (uiState.naturalAiEnabled) {
                    OssmButton(
                        text = stringResource(R.string.natural_ai_stop_use),
                        onClick = { actions.onAiEnabledChange(false) },
                        kind = OssmButtonKind.Secondary,
                        modifier = Modifier.fillMaxWidth().testTag("natural_ai_stop_use")
                    )
                }
                uiState.naturalAiFallback?.takeIf { uiState.naturalRunning }?.let { fallback ->
                    OssmStatusLine(
                        text = naturalAiFallbackText(fallback),
                        tone = OssmTone.Error,
                        modifier = Modifier.modesStatusTag("natural_ai_fallback")
                    )
                }
                val reserve = uiState.naturalAiReserveSeconds
                if (uiState.naturalAiEnabled && uiState.naturalRunning && reserve != null) {
                    val latency = uiState.naturalAiLastLatencySeconds
                    OssmStatusLine(
                        text = if (latency != null) {
                            stringResource(R.string.natural_ai_reserve_latency, reserve, latency)
                        } else {
                            stringResource(R.string.natural_ai_reserve, reserve)
                        },
                        modifier = Modifier.modesStatusTag("natural_ai_reserve")
                    )
                }
            }
        }
    }
}
