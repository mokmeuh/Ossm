package com.ossm.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalClipboardManager
import com.ossm.remote.R
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.model.DiagnosticsLog
import com.ossm.remote.model.LogLevel
import com.ossm.remote.model.label
import com.ossm.remote.ui.components.BleStatusIndicator
import com.ossm.remote.ui.components.GlassCard
import com.ossm.remote.ui.theme.*

@Composable
fun DiagnosticsScreen(
    logs: List<DiagnosticsLog>,
    connectionState: BleConnectionState,
    lastCommand: String,
    onClear: () -> Unit
) {
    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(OssmBackground, OssmSecondary)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.diag_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = OssmAccent,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = {
                            clipboardManager.setText(
                                AnnotatedString(
                                    logs.joinToString(separator = "\n") { log ->
                                        "${log.formattedTime()} [${log.tag}] ${log.message}"
                                    }
                                )
                            )
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, "Copier les logs", tint = OssmPrimary)
                    }
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Delete, stringResource(R.string.diag_clear), tint = OssmError)
                    }
                }
            }

            // Connection state card
            GlassCard(modifier = Modifier.fillMaxWidth(), padding = 12.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(stringResource(R.string.diag_ble_state), color = OssmPrimaryLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        BleStatusIndicator(state = connectionState)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(stringResource(R.string.diag_last_cmd), color = OssmPrimaryLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(
                            lastCommand.ifBlank { "—" },
                            color = OssmOnSurface,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                if (connectionState is BleConnectionState.Connected) {
                    Spacer(Modifier.height(8.dp))
                    Divider(color = OssmGlassBorder)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.diag_mac), color = OssmPrimaryLight, fontSize = 11.sp)
                    Text(connectionState.address, color = OssmOnSurface, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }

            // Logs
            Text(
                stringResource(R.string.diag_entries, logs.size),
                color = OssmPrimaryLight,
                fontSize = 11.sp
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF06060A), RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(logs, key = { it.id }) { log ->
                    LogRow(log)
                }
            }
        }
    }
}

@Composable
private fun LogRow(log: DiagnosticsLog) {
    val color = when (log.level) {
        LogLevel.DEBUG   -> Color(0xFF9E9E9E)
        LogLevel.INFO    -> OssmAccent
        LogLevel.WARNING -> OssmWarning
        LogLevel.ERROR   -> OssmError
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            log.formattedTime(),
            color = Color(0xFF616161),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(72.dp)
        )
        Text(
            "[${log.tag}]",
            color = color.copy(0.7f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(60.dp)
        )
        Text(
            log.message,
            color = color,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}
