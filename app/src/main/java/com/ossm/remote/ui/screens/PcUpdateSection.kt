package com.ossm.remote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ossm.remote.R
import com.ossm.remote.ui.theme.OssmConnected
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmOnSurfaceMuted
import com.ossm.remote.ui.theme.OssmPrimaryLight
import com.ossm.remote.ui.theme.OssmWarning
import com.ossm.remote.updater.PcUpdateStatus
import com.ossm.remote.updater.PcUpdateViewModel

/**
 * Update without ADB: the phone pulls the newest APK the PC server publishes. The listening
 * switch makes it check the PC every 15 s, silently when the server is off.
 */
@Composable
fun PcUpdateSection(viewModel: PcUpdateViewModel = hiltViewModel()) {
    val savedUrl by viewModel.serverUrl.collectAsState()
    val listening by viewModel.listening.collectAsState()
    val status by viewModel.status.collectAsState()
    var address by remember(savedUrl) { mutableStateOf(savedUrl) }
    var invalid by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.pc_update_title), color = OssmOnSurface, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(stringResource(R.string.pc_update_hint), color = OssmOnSurfaceMuted, fontSize = 11.sp, lineHeight = 15.sp)
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
            TextButton(onClick = { invalid = !viewModel.saveServerUrl(address) }) {
                Text(stringResource(R.string.pc_update_url_save), color = OssmPrimaryLight)
            }
        }
        Button(
            onClick = {
                invalid = false
                viewModel.installLatestNow(address)
            },
            enabled = status !is PcUpdateStatus.Checking && status !is PcUpdateStatus.Downloading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.pc_update_install))
        }
        PcUpdateStatusLine(status) { viewModel.openInstaller(it) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.pc_update_listen), color = OssmOnSurface, fontSize = 13.sp)
                Text(stringResource(R.string.pc_update_listen_hint), color = OssmOnSurfaceMuted, fontSize = 11.sp, lineHeight = 15.sp)
            }
            Switch(checked = listening, onCheckedChange = viewModel::setListening)
        }
        if (listening) {
            Text(stringResource(R.string.pc_update_listening), color = OssmConnected, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PcUpdateStatusLine(status: PcUpdateStatus, onOpen: (java.io.File) -> Unit) {
    when (status) {
        PcUpdateStatus.Idle -> Unit
        PcUpdateStatus.Checking -> Text(stringResource(R.string.pc_update_checking), color = OssmOnSurfaceMuted, fontSize = 12.sp)
        PcUpdateStatus.Unreachable -> Text(stringResource(R.string.pc_update_unreachable), color = OssmWarning, fontSize = 12.sp)
        PcUpdateStatus.UpToDate -> Text(stringResource(R.string.pc_update_current), color = OssmConnected, fontSize = 12.sp)
        is PcUpdateStatus.Downloading -> Text(
            stringResource(R.string.pc_update_downloading, status.versionName),
            color = OssmOnSurfaceMuted,
            fontSize = 12.sp,
        )
        PcUpdateStatus.Rejected -> Text(stringResource(R.string.pc_update_rejected), color = OssmError, fontSize = 12.sp)
        PcUpdateStatus.Error -> Text(stringResource(R.string.pc_update_error), color = OssmError, fontSize = 12.sp)
        is PcUpdateStatus.Ready -> {
            Text(stringResource(R.string.pc_update_ready, status.versionName), color = OssmConnected, fontSize = 12.sp)
            TextButton(onClick = { onOpen(status.apk) }) {
                Text(stringResource(R.string.pc_update_install_again), color = OssmPrimaryLight)
            }
        }
    }
}
