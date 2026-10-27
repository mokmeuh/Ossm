package com.ossm.remote.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import com.ossm.remote.ui.theme.OssmBackground
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ossm.remote.R
import com.ossm.remote.ui.components.GlassCard
import com.ossm.remote.ui.theme.OssmConnected
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmOnSurfaceMuted
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmPrimaryLight
import com.ossm.remote.usb.FirmwareFlashState
import com.ossm.remote.usb.FirmwareFlashViewModel
import com.ossm.remote.usb.FlashFailure

/** Réglages card: writes the machine firmware over USB (OTG cable), from the phone. */
@Composable
internal fun FirmwareFlashCard() {
    val viewModel: FirmwareFlashViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    val pending by viewModel.pending.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val clipboard = LocalClipboardManager.current
    val logScroll = rememberScrollState()
    LaunchedEffect(logs.size) { logScroll.scrollTo(logScroll.maxValue) }
    val busy = state is FirmwareFlashState.Preparing ||
        state is FirmwareFlashState.Erasing ||
        state is FirmwareFlashState.Writing
    val buttonModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.prepareFile(uri)
    }

    // The phone must not sleep halfway through a transfer.
    val view = LocalView.current
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    GlassCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Usb, null, tint = OssmPrimaryLight, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.fw_title),
                color = OssmOnSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
        Text(stringResource(R.string.fw_hint), color = OssmOnSurfaceMuted, fontSize = 12.sp)

        when (val current = state) {
            FirmwareFlashState.Idle -> Unit
            FirmwareFlashState.Preparing -> {
                Text(stringResource(R.string.fw_state_preparing), color = OssmOnSurfaceMuted, fontSize = 12.sp)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = OssmPrimary)
            }
            FirmwareFlashState.Erasing -> {
                Text(stringResource(R.string.fw_state_erasing), color = OssmOnSurfaceMuted, fontSize = 12.sp)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = OssmPrimary)
            }
            is FirmwareFlashState.Writing -> {
                Text(
                    stringResource(R.string.fw_state_writing, current.percent),
                    color = OssmOnSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                LinearProgressIndicator(
                    progress = { current.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = OssmPrimary
                )
            }
            FirmwareFlashState.Complete -> {
                Text(stringResource(R.string.fw_state_complete), color = OssmConnected, fontSize = 13.sp)
                TextButton(onClick = viewModel::reset, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.fw_ok), color = OssmPrimaryLight)
                }
            }
            is FirmwareFlashState.Failed -> {
                Text(failureMessage(current), color = OssmError, fontSize = 13.sp)
                TextButton(onClick = viewModel::reset, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.fw_ok), color = OssmPrimaryLight)
                }
            }
        }

        if (logs.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.fw_logs_title),
                    color = OssmOnSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { clipboard.setText(AnnotatedString(logs.joinToString("\n"))) }) {
                    Text(stringResource(R.string.fw_logs_copy), color = OssmPrimaryLight)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .background(OssmBackground)
                    .padding(8.dp)
                    .verticalScroll(logScroll)
            ) {
                Text(
                    logs.joinToString("\n"),
                    color = OssmOnSurfaceMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 14.sp
                )
            }
        }

        if (!busy) {
            Button(onClick = viewModel::prepareBundled, modifier = buttonModifier) {
                Text(stringResource(R.string.fw_flash_bundled))
            }
            TextButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = buttonModifier) {
                Text(stringResource(R.string.fw_flash_file), color = OssmPrimaryLight)
            }
        }
    }

    pending?.let { firmware ->
        AlertDialog(
            onDismissRequest = viewModel::cancel,
            title = { Text(stringResource(R.string.fw_confirm_title)) },
            text = { Text(stringResource(R.string.fw_confirm_body, firmware.label, firmware.sizeKb)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirm) { Text(stringResource(R.string.fw_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.fw_cancel)) }
            }
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
