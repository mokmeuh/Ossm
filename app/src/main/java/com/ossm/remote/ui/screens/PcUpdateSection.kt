package com.ossm.remote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.ossm.remote.R
import com.ossm.remote.ui.components.OssmButton
import com.ossm.remote.ui.components.OssmButtonKind
import com.ossm.remote.ui.components.OssmStatusLine
import com.ossm.remote.ui.components.OssmSwitchRow
import com.ossm.remote.ui.components.OssmTone
import com.ossm.remote.ui.theme.OssmCaption
import com.ossm.remote.ui.theme.OssmOnSurfaceMuted
import com.ossm.remote.updater.PcUpdateStatus
import com.ossm.remote.updater.PcUpdateViewModel

/**
 * Update without ADB: the phone pulls the newest APK the PC server publishes. The listening
 * switch makes it check the PC every 15 s, silently when the server is off.
 *
 * Section repliable, ouverte par défaut (c'est la mise à jour la plus utilisée). Un nouvel état
 * (prête, erreur, injoignable…) la rouvre si elle avait été repliée.
 */
@Composable
fun PcUpdateSection(viewModel: PcUpdateViewModel = hiltViewModel()) {
    val savedUrl by viewModel.serverUrl.collectAsState()
    val listening by viewModel.listening.collectAsState()
    val status by viewModel.status.collectAsState()
    var address by remember(savedUrl) { mutableStateOf(savedUrl) }
    var invalid by remember { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf(true) }

    OpenOnChange(signal = status, enabled = status !is PcUpdateStatus.Idle) { open = true }

    SettingsFoldSection(
        title = stringResource(R.string.pc_update_title),
        expanded = open,
        onExpandedChange = { open = it },
        collapsedContent = {
            if (listening) {
                OssmStatusLine(text = stringResource(R.string.pc_update_listening), tone = OssmTone.Ok)
            }
        }
    ) {
        Text(stringResource(R.string.pc_update_hint), style = OssmCaption, color = OssmOnSurfaceMuted)
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; invalid = false },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.pc_update_url_label)) },
            placeholder = { Text(stringResource(R.string.pc_update_url_hint)) },
            isError = invalid,
            supportingText = if (invalid) {
                { Text(stringResource(R.string.pc_update_url_invalid)) }
            } else null,
            singleLine = true,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OssmButton(
                text = stringResource(R.string.pc_update_url_save),
                onClick = { invalid = !viewModel.saveServerUrl(address) },
                kind = OssmButtonKind.Text,
            )
        }
        OssmButton(
            text = stringResource(R.string.pc_update_install),
            onClick = {
                invalid = false
                viewModel.installLatestNow(address)
            },
            enabled = status !is PcUpdateStatus.Checking && status !is PcUpdateStatus.Downloading,
            modifier = Modifier.fillMaxWidth(),
        )
        PcUpdateStatusLine(status) { viewModel.openInstaller(it) }
        // Toute la rangée bascule l'écoute (et non plus le seul interrupteur).
        OssmSwitchRow(
            label = stringResource(R.string.pc_update_listen),
            checked = listening,
            onCheckedChange = viewModel::setListening,
            supporting = stringResource(R.string.pc_update_listen_hint),
        )
        if (listening) {
            OssmStatusLine(text = stringResource(R.string.pc_update_listening), tone = OssmTone.Ok)
        }
    }
}

@Composable
private fun PcUpdateStatusLine(status: PcUpdateStatus, onOpen: (java.io.File) -> Unit) {
    when (status) {
        PcUpdateStatus.Idle -> Unit
        PcUpdateStatus.Checking -> OssmStatusLine(text = stringResource(R.string.pc_update_checking))
        PcUpdateStatus.Unreachable -> OssmStatusLine(text = stringResource(R.string.pc_update_unreachable), tone = OssmTone.Warn)
        PcUpdateStatus.UpToDate -> OssmStatusLine(text = stringResource(R.string.pc_update_current), tone = OssmTone.Ok)
        is PcUpdateStatus.Downloading -> OssmStatusLine(
            text = stringResource(R.string.pc_update_downloading, status.versionName),
        )
        PcUpdateStatus.Rejected -> OssmStatusLine(text = stringResource(R.string.pc_update_rejected), tone = OssmTone.Error)
        PcUpdateStatus.Error -> OssmStatusLine(text = stringResource(R.string.pc_update_error), tone = OssmTone.Error)
        is PcUpdateStatus.Ready -> {
            OssmStatusLine(text = stringResource(R.string.pc_update_ready, status.versionName), tone = OssmTone.Ok)
            OssmButton(
                text = stringResource(R.string.pc_update_install_again),
                onClick = { onOpen(status.apk) },
                kind = OssmButtonKind.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
