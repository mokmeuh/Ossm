package com.ossm.remote.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.model.BleDevice
import com.ossm.remote.ui.components.BleStatusIndicator
import com.ossm.remote.ui.components.GlassCard
import com.ossm.remote.ui.theme.*

@Composable
fun ScanScreen(
    connectionState: BleConnectionState,
    devices: List<BleDevice>,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    onStop: () -> Unit
) {
    val isScanning = connectionState is BleConnectionState.Scanning
    val isConnecting = connectionState is BleConnectionState.Connecting
    val isConnected = connectionState is BleConnectionState.Connected

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(OssmBackground, OssmSecondary)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "OSSM Remote",
                    style = MaterialTheme.typography.headlineLarge,
                    color = OssmPrimary
                )
                BleStatusIndicator(state = connectionState)
            }

            // Scan button
            Button(
                onClick = if (isScanning) onStop else onScan,
                enabled = !isConnecting && !isConnected,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isScanning) OssmError else OssmPrimary
                )
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = OssmOnSurface,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Arrêter le scan", fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Default.Bluetooth, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Scanner les appareils BLE", fontWeight = FontWeight.Bold)
                }
            }

            // Device list
            AnimatedVisibility(visible = devices.isNotEmpty()) {
                GlassCard(modifier = Modifier.fillMaxWidth(), padding = 12.dp) {
                    Text(
                        "${devices.size} appareil(s) trouvé(s)",
                        color = OssmPrimaryLight,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(devices, key = { it.address }) { device ->
                            DeviceRow(
                                device = device,
                                isConnecting = isConnecting,
                                onClick = { onConnect(device.address) }
                            )
                        }
                    }
                }
            }

            // Connected info
            AnimatedVisibility(visible = isConnected) {
                if (connectionState is BleConnectionState.Connected) {
                    GlassCard(modifier = Modifier.fillMaxWidth(), tint = OssmConnected) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = OssmConnected, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Connecté", color = OssmConnected, fontWeight = FontWeight.Bold)
                                Text(connectionState.deviceName, color = OssmOnSurface, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            if (isConnecting) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = OssmWarning)
                        Spacer(Modifier.width(12.dp))
                        Text("Connexion en cours...", color = OssmWarning)
                    }
                }
            }

            if (connectionState is BleConnectionState.Error) {
                GlassCard(modifier = Modifier.fillMaxWidth(), tint = OssmError) {
                    Row {
                        Icon(Icons.Default.Error, null, tint = OssmError)
                        Spacer(Modifier.width(8.dp))
                        Text(connectionState.message, color = OssmError)
                    }
                }
            }

            if (!isScanning && devices.isEmpty() && connectionState is BleConnectionState.Disconnected) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "Appuyez sur 'Scanner' pour rechercher\ndes appareils OSSM à proximité",
                        color = OssmOnSurface.copy(0.4f),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 40.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: BleDevice,
    isConnecting: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !isConnecting) { onClick() }
            .background(OssmGlass.copy(alpha = 0.15f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (device.isOssm) Icons.Default.BluetoothConnected else Icons.Default.BluetoothSearching,
            contentDescription = null,
            tint = if (device.isOssm) OssmConnected else OssmPrimaryLight,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                device.name,
                fontWeight = if (device.isOssm) FontWeight.Bold else FontWeight.Normal,
                color = if (device.isOssm) OssmOnSurface else OssmOnSurface.copy(0.7f)
            )
            Text(device.address, color = OssmPrimaryLight, fontSize = 11.sp)
        }
        Text(
            "${device.rssi} dBm",
            color = rssiColor(device.rssi),
            fontSize = 11.sp
        )
        if (device.isOssm) {
            Spacer(Modifier.width(8.dp))
            Text("OSSM", color = OssmConnected, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun rssiColor(rssi: Int) = when {
    rssi > -60  -> OssmConnected
    rssi > -75  -> OssmWarning
    else        -> OssmError
}
