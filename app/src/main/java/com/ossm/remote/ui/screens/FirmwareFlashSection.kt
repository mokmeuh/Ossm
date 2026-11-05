package com.ossm.remote.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ossm.remote.R
import com.ossm.remote.ui.components.OssmButton
import com.ossm.remote.ui.components.OssmButtonKind
import com.ossm.remote.ui.components.OssmStatusLine
import com.ossm.remote.ui.components.OssmTone
import com.ossm.remote.ui.theme.OssmBackground
import com.ossm.remote.ui.theme.OssmCaption
import com.ossm.remote.ui.theme.OssmConnected
import com.ossm.remote.ui.theme.OssmDimens
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmOnSurfaceMuted
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmSurfaceHigh
import com.ossm.remote.ui.theme.OssmSurfaceRaised
import com.ossm.remote.ui.theme.OssmValue
import com.ossm.remote.usb.FirmwareFlashState
import com.ossm.remote.usb.FirmwareFlashViewModel
import com.ossm.remote.usb.FlashFailure
import com.ossm.remote.usb.PcFirmwareState
import java.text.DateFormat
import java.util.Date

/** Hauteur maximale du journal du flash avant défilement interne. */
private val LogMaxHeight = 200.dp

/**
 * Réglages card: writes the machine firmware over USB (OTG cable), from the phone.
 *
 * Carte repliable, fermée par défaut. Elle reste ouverte tant qu'un flash est en cours ou que son
 * résultat n'est pas acquitté (OK) : progression, erreur et journal ne sont jamais cachés.
 */
@Composable
internal fun FirmwareFlashCard() {
    val viewModel: FirmwareFlashViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    val pending by viewModel.pending.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val pcFirmware by viewModel.pcFirmware.collectAsState()
    val clipboard = LocalClipboardManager.current
    val logScroll = rememberScrollState()
    LaunchedEffect(logs.size) { logScroll.scrollTo(logScroll.maxValue) }
    val busy = state is FirmwareFlashState.Preparing ||
        state is FirmwareFlashState.Erasing ||
        state is FirmwareFlashState.Writing
    var open by rememberSaveable { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.prepareFile(uri)
    }

    // One quiet look at the PC server when the card opens; silent when it is off.
    LaunchedEffect(Unit) { viewModel.checkPcFirmware(manual = false) }

    // The phone must not sleep halfway through a transfer.
    val view = LocalView.current
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    SettingsFoldCard(
        title = stringResource(R.string.fw_title),
        expanded = open,
        onExpandedChange = { open = it },
        icon = Icons.Default.Usb,
        forceExpanded = state !is FirmwareFlashState.Idle
    ) {
        Text(stringResource(R.string.fw_hint), style = OssmCaption, color = OssmOnSurfaceMuted)

        when (val current = state) {
            FirmwareFlashState.Idle -> Unit
            FirmwareFlashState.Preparing -> {
                OssmStatusLine(text = stringResource(R.string.fw_state_preparing))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = OssmPrimary,
                    trackColor = OssmSurfaceHigh
                )
            }
            FirmwareFlashState.Erasing -> {
                OssmStatusLine(text = stringResource(R.string.fw_state_erasing))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = OssmPrimary,
                    trackColor = OssmSurfaceHigh
                )
            }
            is FirmwareFlashState.Writing -> {
                Text(
                    stringResource(R.string.fw_state_writing, current.percent),
                    style = OssmValue,
                    color = OssmOnSurface
                )
                LinearProgressIndicator(
                    progress = { current.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = OssmPrimary,
                    trackColor = OssmSurfaceHigh
                )
            }
            FirmwareFlashState.Complete -> {
                OssmStatusLine(text = stringResource(R.string.fw_state_complete), tone = OssmTone.Ok)
                AcknowledgeRow(onClick = viewModel::reset)
            }
            is FirmwareFlashState.Failed -> {
                OssmStatusLine(text = failureMessage(current), tone = OssmTone.Error)
                AcknowledgeRow(onClick = viewModel::reset)
            }
        }

        if (logs.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.fw_logs_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = OssmOnSurface,
                    modifier = Modifier.weight(1f)
                )
                OssmButton(
                    text = stringResource(R.string.fw_logs_copy),
                    onClick = { clipboard.setText(AnnotatedString(logs.joinToString("\n"))) },
                    kind = OssmButtonKind.Text
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = LogMaxHeight)
                    .background(OssmBackground, RoundedCornerShape(OssmDimens.FieldRadius))
                    .padding(OssmDimens.Space8)
                    .verticalScroll(logScroll)
            ) {
                Text(
                    logs.joinToString("\n"),
                    color = OssmOnSurfaceMuted,
                    style = OssmCaption.copy(fontFamily = FontFamily.Monospace)
                )
            }
        }

        if (!busy) PcFirmwareBlock(
            state = pcFirmware,
            onFetch = viewModel::fetchPcFirmware,
            onCheck = { viewModel.checkPcFirmware(manual = true) },
            onDismiss = viewModel::dismissPcFirmwareMessage,
        )

        if (!busy) {
            OssmButton(
                text = stringResource(R.string.fw_flash_bundled),
                onClick = viewModel::prepareBundled,
                modifier = Modifier.fillMaxWidth()
            )
            OssmButton(
                text = stringResource(R.string.fw_flash_file),
                onClick = { picker.launch(arrayOf("*/*")) },
                kind = OssmButtonKind.Secondary,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // Confirmation obligatoire avant tout flash ; toucher à côté annule.
    pending?.let { firmware ->
        AlertDialog(
            onDismissRequest = viewModel::cancel,
            containerColor = OssmSurfaceRaised,
            title = { Text(stringResource(R.string.fw_confirm_title), color = OssmOnSurface) },
            text = {
                Text(
                    stringResource(R.string.fw_confirm_body, firmware.label, firmware.sizeKb),
                    color = OssmOnSurfaceMuted
                )
            },
            confirmButton = {
                OssmButton(text = stringResource(R.string.fw_confirm_ok), onClick = viewModel::confirm)
            },
            dismissButton = {
                OssmButton(
                    text = stringResource(R.string.fw_cancel),
                    onClick = viewModel::cancel,
                    kind = OssmButtonKind.Text
                )
            }
        )
    }
}

/** Firmware announced by the PC server: nothing is shown unless there is news or the user asked. */
@Composable
private fun PcFirmwareBlock(
    state: PcFirmwareState,
    onFetch: (com.ossm.remote.updater.PcFirmwareManifest) -> Unit,
    onCheck: () -> Unit,
    onDismiss: () -> Unit,
) {
    val buttonModifier = Modifier.fillMaxWidth()
    when (state) {
        is PcFirmwareState.Available -> {
            val manifest = state.manifest
            val date = remember(manifest.modifiedMs) {
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(manifest.modifiedMs))
            }
            Text(
                stringResource(R.string.fw_pc_available),
                color = OssmConnected,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            Text(
                stringResource(R.string.fw_pc_details, manifest.label, (manifest.size + 1023) / 1024, date),
                color = OssmOnSurfaceMuted,
                fontSize = 12.sp
            )
            OssmButton(
                text = stringResource(R.string.fw_pc_fetch),
                onClick = { onFetch(manifest) },
                modifier = buttonModifier
            )
        }
        PcFirmwareState.Checking -> {
            Text(stringResource(R.string.fw_pc_checking), color = OssmOnSurfaceMuted, fontSize = 12.sp)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = OssmPrimary)
        }
        PcFirmwareState.Downloading -> {
            Text(stringResource(R.string.fw_pc_downloading), color = OssmOnSurfaceMuted, fontSize = 12.sp)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = OssmPrimary)
        }
        PcFirmwareState.UpToDate -> PcFirmwareMessage(R.string.fw_pc_none, OssmOnSurfaceMuted, onDismiss)
        PcFirmwareState.Unreachable -> PcFirmwareMessage(R.string.fw_pc_unreachable, OssmError, onDismiss)
        PcFirmwareState.NoAddress -> PcFirmwareMessage(R.string.fw_pc_no_address, OssmError, onDismiss)
        PcFirmwareState.Failed -> PcFirmwareMessage(R.string.fw_pc_error, OssmError, onDismiss)
        PcFirmwareState.Idle -> Unit
    }
    val checking = state is PcFirmwareState.Checking || state is PcFirmwareState.Downloading
    OssmButton(
        text = stringResource(R.string.fw_pc_check),
        onClick = onCheck,
        modifier = buttonModifier,
        kind = OssmButtonKind.Text,
        enabled = !checking
    )
}

@Composable
private fun PcFirmwareMessage(textRes: Int, color: androidx.compose.ui.graphics.Color, onDismiss: () -> Unit) {
    Text(stringResource(textRes), color = color, fontSize = 12.sp)
    OssmButton(
        text = stringResource(R.string.fw_ok),
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth(),
        kind = OssmButtonKind.Text
    )
}

/** Bouton « OK » aligné à droite qui acquitte le résultat du flash. */
@Composable
private fun AcknowledgeRow(onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        OssmButton(
            text = stringResource(R.string.fw_ok),
            onClick = onClick,
            kind = OssmButtonKind.Secondary
        )
    }
}

@Composable
private fun failureMessage(failed: FirmwareFlashState.Failed): String = when (failed.failure) {
    FlashFailure.NO_CABLE -> stringResource(R.string.fw_err_no_cable)
    FlashFailure.PERMISSION_DENIED -> stringResource(R.string.fw_err_permission)
    FlashFailure.OPEN_FAILED -> stringResource(R.string.fw_err_open)
    FlashFailure.NO_BOOTLOADER -> stringResource(R.string.fw_err_no_bootloader)
    FlashFailure.INVALID_IMAGE -> stringResource(R.string.fw_err_invalid)
    FlashFailure.REJECTED -> stringResource(R.string.fw_err_rejected)
    FlashFailure.WRITE_FAILED ->
        if (failed.block > 0) stringResource(R.string.fw_err_write, failed.block)
        else stringResource(R.string.fw_err_generic)
}
