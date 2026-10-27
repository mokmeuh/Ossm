package com.ossm.remote.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.ossm.remote.R
import com.ossm.remote.ui.components.QrCodeImage
import com.ossm.remote.ui.theme.OssmConnected
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmOnSurfaceMuted
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmPrimaryLight
import com.ossm.remote.ui.theme.OssmWarning
import com.ossm.remote.updater.LocalOtaMessageTone
import com.ossm.remote.updater.LocalOtaViewModel

/**
 * Local OTA (2.3.32). Receive: big pairing QR + addresses as text + state.
 * Send: "Scanner le QR", "Envoyer à une adresse" (no code), automatic discovery.
 * Both phones then show the same 2-digit number; the receiver answers Oui/Non.
 */
@Composable
internal fun LocalOtaSection() {
    val otaVm: LocalOtaViewModel = hiltViewModel()
    val otaState by otaVm.state.collectAsState()
    val scanPrompt = stringResource(R.string.update_local_ota_scan_prompt)
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> otaVm.onQrScanned(result.contents) }
    val buttonModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)

    Text(
        stringResource(R.string.update_local_ota_title),
        color = OssmOnSurface,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
    )
    Button(
        onClick = otaVm::toggleReceiver,
        modifier = buttonModifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (otaState.receiverActive) OssmWarning else OssmPrimary,
        ),
    ) {
        Icon(Icons.Default.SystemUpdate, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (otaState.receiverActive) R.string.update_local_ota_stop else R.string.update_local_ota_receive))
    }
    otaState.receiverInfo?.let { info ->
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            QrCodeImage(
                content = info.qrUri,
                contentDescription = stringResource(R.string.update_local_ota_qr_desc, info.addresses.joinToString(", ")),
                modifier = Modifier.fillMaxWidth(0.85f).widthIn(max = 360.dp),
            )
            Text(
                stringResource(R.string.update_local_ota_qr_hint),
                color = OssmOnSurfaceMuted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
        Text(stringResource(R.string.update_local_ota_addresses), color = OssmOnSurface, fontSize = 12.sp)
        if (info.addresses.isEmpty()) {
            Text(stringResource(R.string.update_local_ota_no_address), color = OssmWarning, fontSize = 12.sp)
        } else {
            info.addresses.forEach { address ->
                Text(address, color = OssmOnSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
    Text(stringResource(R.string.update_local_ota_hint), color = OssmOnSurfaceMuted, fontSize = 12.sp)
    Button(
        onClick = {
            scanner.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(scanPrompt)
                    .setBeepEnabled(false)
                    .setOrientationLocked(false),
            )
        },
        enabled = !otaState.senderActive,
        modifier = buttonModifier,
        colors = ButtonDefaults.buttonColors(containerColor = OssmPrimary),
    ) {
        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.update_local_ota_scan))
    }
    var manualOpen by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    TextButton(onClick = { manualOpen = !manualOpen }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.update_local_ota_manual), color = OssmPrimaryLight)
    }
    if (manualOpen || otaState.suggestManual) {
        OutlinedTextField(
            value = address,
            onValueChange = { address = it.trim().take(80) },
            label = { Text(stringResource(R.string.update_local_ota_address_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Send),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { otaVm.sendToAddress(address) },
            enabled = !otaState.senderActive && address.isNotBlank(),
            modifier = buttonModifier,
            colors = ButtonDefaults.buttonColors(containerColor = OssmPrimary),
        ) {
            Text(stringResource(R.string.update_local_ota_manual_send))
        }
    }
    Button(
        onClick = otaVm::sendInstalledVersion,
        enabled = !otaState.senderActive,
        modifier = buttonModifier,
        colors = ButtonDefaults.buttonColors(containerColor = OssmPrimary),
    ) {
        Icon(Icons.Default.Share, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.update_local_ota_send))
    }

    otaState.pairing?.let { pairing ->
        val numberDescription = stringResource(R.string.update_local_ota_number_desc, pairing.number)
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.update_local_ota_pairing_title)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        pairing.number,
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Bold,
                        color = OssmPrimaryLight,
                        modifier = Modifier.clearAndSetSemantics { contentDescription = numberDescription },
                    )
                    Text(
                        stringResource(
                            R.string.update_local_ota_pairing_body,
                            pairing.localVersionName,
                            pairing.peerLabel,
                            pairing.remoteVersionName ?: stringResource(R.string.update_local_ota_unknown_version),
                        ),
                    )
                    Text(
                        stringResource(R.string.update_local_ota_waiting),
                        color = OssmOnSurfaceMuted,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = otaVm::cancelPairing, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.update_local_ota_cancel))
                }
            },
        )
    }

    otaState.incoming?.let { incoming ->
        val numberDescription = stringResource(R.string.update_local_ota_number_desc, incoming.number)
        AlertDialog(
            onDismissRequest = { otaVm.answerIncoming(false) },
            title = { Text(stringResource(R.string.update_local_ota_incoming_title)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        incoming.number,
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Bold,
                        color = OssmPrimaryLight,
                        modifier = Modifier.clearAndSetSemantics { contentDescription = numberDescription },
                    )
                    Text(
                        stringResource(
                            R.string.update_local_ota_incoming_body,
                            incoming.peerName,
                            incoming.peerVersionName ?: stringResource(R.string.update_local_ota_unknown_version),
                            incoming.number,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { otaVm.answerIncoming(true) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.update_local_ota_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { otaVm.answerIncoming(false) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.update_local_ota_no))
                }
            },
        )
    }

    if (otaState.message.isNotBlank()) {
        Text(
            otaState.message,
            color = when (otaState.tone) {
                LocalOtaMessageTone.Success -> OssmConnected
                LocalOtaMessageTone.Error -> OssmError
                LocalOtaMessageTone.Info -> OssmOnSurfaceMuted
            },
            fontSize = 12.sp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
