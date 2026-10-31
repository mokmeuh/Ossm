package com.ossm.remote.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.ossm.remote.R
import com.ossm.remote.ui.components.OssmButton
import com.ossm.remote.ui.components.OssmButtonKind
import com.ossm.remote.ui.components.OssmStatusLine
import com.ossm.remote.ui.components.OssmTone
import com.ossm.remote.ui.components.QrCodeImage
import com.ossm.remote.ui.theme.OssmCaption
import com.ossm.remote.ui.theme.OssmDimens
import com.ossm.remote.ui.theme.OssmDisplayValue
import com.ossm.remote.ui.theme.OssmOnPrimary
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmOnSurfaceMuted
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmSurfaceRaised
import com.ossm.remote.ui.theme.OssmValue
import com.ossm.remote.ui.theme.OssmWarning
import com.ossm.remote.updater.LocalOtaMessageTone
import com.ossm.remote.updater.LocalOtaViewModel

/** Largeur maximale du QR de jumelage (lisible à la caméra sans occuper tout l'écran). */
private val QrMaxWidth = 360.dp

/** Nombre de vérification des deux téléphones : la grande valeur de l'appli, agrandie pour être comparée d'un coup d'œil. */
private val VerificationNumberStyle = OssmDisplayValue.copy(
    fontSize = OssmDisplayValue.fontSize * 1.75f,
    lineHeight = OssmDisplayValue.lineHeight * 1.75f
)

/**
 * Local OTA (2.3.32). Receive: big pairing QR + addresses as text + state.
 * Send: "Scanner le QR", "Envoyer à une adresse" (no code), automatic discovery.
 * Both phones then show the same 2-digit number; the receiver answers Oui/Non.
 *
 * Section repliable (fermée par défaut) : les dialogues d'appairage restent hors du corps repliable,
 * donc s'affichent même section repliée.
 */
@Composable
internal fun LocalOtaSection() {
    val otaVm: LocalOtaViewModel = hiltViewModel()
    val otaState by otaVm.state.collectAsState()
    val scanPrompt = stringResource(R.string.update_local_ota_scan_prompt)
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> otaVm.onQrScanned(result.contents) }
    var open by rememberSaveable { mutableStateOf(false) }
    // L'adresse saisie et l'ouverture du champ vivent hors du corps repliable : rien n'est perdu au repli.
    var manualOpen by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }

    // Un nouvel état à montrer (message, champ d'adresse suggéré, écoute activée) ouvre la section.
    OpenOnChange(
        signal = Triple(otaState.message, otaState.suggestManual, otaState.receiverActive),
        enabled = otaState.message.isNotBlank() || otaState.suggestManual || otaState.receiverActive
    ) { open = true }

    SettingsFoldSection(
        title = stringResource(R.string.update_local_ota_title),
        expanded = open,
        onExpandedChange = { open = it },
        collapsedContent = {
            if (otaState.receiverActive) {
                OssmStatusLine(text = stringResource(R.string.rg_ota_receiving), tone = OssmTone.Ok)
            }
            if (otaState.message.isNotBlank()) {
                OtaMessageLine(message = otaState.message, tone = otaState.tone)
            }
        }
    ) {
        Text(stringResource(R.string.update_local_ota_hint), style = OssmCaption, color = OssmOnSurfaceMuted)

        // Recevoir
        OtaGroupLabel(stringResource(R.string.rg_ota_group_receive))
        ReceiverButton(active = otaState.receiverActive, onClick = otaVm::toggleReceiver)
        otaState.receiverInfo?.let { info ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(OssmDimens.Space8)
            ) {
                QrCodeImage(
                    content = info.qrUri,
                    contentDescription = stringResource(R.string.update_local_ota_qr_desc, info.addresses.joinToString(", ")),
                    modifier = Modifier.fillMaxWidth(0.85f).widthIn(max = QrMaxWidth),
                )
                Text(
                    stringResource(R.string.update_local_ota_qr_hint),
                    style = OssmCaption,
                    color = OssmOnSurfaceMuted,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                stringResource(R.string.update_local_ota_addresses),
                style = MaterialTheme.typography.labelMedium,
                color = OssmOnSurface
            )
            if (info.addresses.isEmpty()) {
                OssmStatusLine(text = stringResource(R.string.update_local_ota_no_address), tone = OssmTone.Warn)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(OssmDimens.Space4)) {
                    info.addresses.forEach { receiverAddress ->
                        Text(receiverAddress, style = OssmValue, color = OssmOnSurface)
                    }
                }
            }
        }

        // Envoyer
        SettingsDivider()
        OtaGroupLabel(stringResource(R.string.rg_ota_group_send))
        OssmButton(
            text = stringResource(R.string.update_local_ota_scan),
            onClick = {
                scanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt(scanPrompt)
                        .setBeepEnabled(false)
                        .setOrientationLocked(false),
                )
            },
            icon = Icons.Default.QrCodeScanner,
            enabled = !otaState.senderActive,
            modifier = Modifier.fillMaxWidth(),
        )
        OssmButton(
            text = stringResource(R.string.update_local_ota_send),
            onClick = otaVm::sendInstalledVersion,
            kind = OssmButtonKind.Secondary,
            icon = Icons.Default.Share,
            enabled = !otaState.senderActive,
            modifier = Modifier.fillMaxWidth(),
        )
        OssmButton(
            text = stringResource(R.string.update_local_ota_manual),
            onClick = { manualOpen = !manualOpen },
            kind = OssmButtonKind.Text,
            modifier = Modifier.fillMaxWidth(),
        )
        if (manualOpen || otaState.suggestManual) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it.trim().take(80) },
                label = { Text(stringResource(R.string.update_local_ota_address_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Send),
                modifier = Modifier.fillMaxWidth(),
            )
            OssmButton(
                text = stringResource(R.string.update_local_ota_manual_send),
                onClick = { otaVm.sendToAddress(address) },
                kind = OssmButtonKind.Secondary,
                enabled = !otaState.senderActive && address.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (otaState.message.isNotBlank()) {
            OtaMessageLine(message = otaState.message, tone = otaState.tone)
        }
    }

    // Émetteur : nombre à comparer avec l'autre téléphone. Non annulable en touchant à côté.
    otaState.pairing?.let { pairing ->
        val numberDescription = stringResource(R.string.update_local_ota_number_desc, pairing.number)
        AlertDialog(
            onDismissRequest = {},
            containerColor = OssmSurfaceRaised,
            title = { Text(stringResource(R.string.update_local_ota_pairing_title), color = OssmOnSurface) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(OssmDimens.Space12)
                ) {
                    Text(
                        pairing.number,
                        style = VerificationNumberStyle,
                        color = OssmPrimary,
                        modifier = Modifier.clearAndSetSemantics { contentDescription = numberDescription },
                    )
                    Text(
                        stringResource(
                            R.string.update_local_ota_pairing_body,
                            pairing.localVersionName,
                            pairing.peerLabel,
                            pairing.remoteVersionName ?: stringResource(R.string.update_local_ota_unknown_version),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = OssmOnSurface,
                    )
                    OssmStatusLine(
                        text = stringResource(R.string.update_local_ota_waiting),
                        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                OssmButton(
                    text = stringResource(R.string.update_local_ota_cancel),
                    onClick = otaVm::cancelPairing,
                    kind = OssmButtonKind.Secondary
                )
            },
        )
    }

    // Récepteur : le refus (Non, toucher à côté, retour) est la valeur sûre par défaut.
    otaState.incoming?.let { incoming ->
        val numberDescription = stringResource(R.string.update_local_ota_number_desc, incoming.number)
        AlertDialog(
            onDismissRequest = { otaVm.answerIncoming(false) },
            containerColor = OssmSurfaceRaised,
            title = { Text(stringResource(R.string.update_local_ota_incoming_title), color = OssmOnSurface) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(OssmDimens.Space12)
                ) {
                    Text(
                        incoming.number,
                        style = VerificationNumberStyle,
                        color = OssmPrimary,
                        modifier = Modifier.clearAndSetSemantics { contentDescription = numberDescription },
                    )
                    Text(
                        stringResource(
                            R.string.update_local_ota_incoming_body,
                            incoming.peerName,
                            incoming.peerVersionName ?: stringResource(R.string.update_local_ota_unknown_version),
                            incoming.number,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = OssmOnSurface,
                    )
                }
            },
            confirmButton = {
                OssmButton(
                    text = stringResource(R.string.update_local_ota_yes),
                    onClick = { otaVm.answerIncoming(true) }
                )
            },
            dismissButton = {
                OssmButton(
                    text = stringResource(R.string.update_local_ota_no),
                    onClick = { otaVm.answerIncoming(false) },
                    kind = OssmButtonKind.Secondary
                )
            },
        )
    }
}

/** Sous-titre d'un groupe d'actions (recevoir / envoyer). */
@Composable
private fun OtaGroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = OssmOnSurface,
        modifier = Modifier.semantics { heading() }
    )
}

/** Bouton d'écoute : turquoise au repos, ambre tant que l'écoute est active (le libellé change aussi). */
@Composable
private fun ReceiverButton(active: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = OssmDimens.TouchTarget),
        shape = RoundedCornerShape(OssmDimens.ControlRadius),
        contentPadding = PaddingValues(horizontal = OssmDimens.Space16, vertical = OssmDimens.Space8),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) OssmWarning else OssmPrimary,
            contentColor = OssmOnPrimary,
        ),
    ) {
        Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(SettingsIconSize))
        Spacer(Modifier.width(OssmDimens.Space8))
        Text(stringResource(if (active) R.string.update_local_ota_stop else R.string.update_local_ota_receive))
    }
}

/** Message d'état de la mise à jour locale : icône + texte, annoncé poliment par les lecteurs d'écran. */
@Composable
private fun OtaMessageLine(message: String, tone: LocalOtaMessageTone) {
    OssmStatusLine(
        text = message,
        tone = when (tone) {
            LocalOtaMessageTone.Success -> OssmTone.Ok
            LocalOtaMessageTone.Error -> OssmTone.Error
            LocalOtaMessageTone.Info -> OssmTone.Neutral
        },
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    )
}
