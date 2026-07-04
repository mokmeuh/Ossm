package com.ossm.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ossm.remote.remote.RemoteConnectionState
import com.ossm.remote.remote.RemoteControlOwner
import com.ossm.remote.ui.components.GlassCard
import com.ossm.remote.ui.theme.*
import com.ossm.remote.viewmodel.RemoteUiState

/**
 * Onglet « Remote » — contrôle à distance (WIP, branche claude/remote-control).
 * L'UI est en place ; la couche réseau (appairage réel) arrive à l'incrément 2.
 * Voir REMOTE_CONTROL_DESIGN.md.
 */
@Composable
fun RemoteScreen(
    uiState: RemoteUiState,
    onStartHosting: () -> Unit,
    onRegenerateCode: () -> Unit,
    onCodeInputChange: (String) -> Unit,
    onConnect: () -> Unit,
    onExclusiveControl: (Boolean) -> Unit,
    onEndSession: () -> Unit
) {
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
            Text(
                "REMOTE",
                color = OssmPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            // Bandeau expérimental clair.
            GlassCard(modifier = Modifier.fillMaxWidth(), tint = OssmWarning, padding = 12.dp) {
                Text(
                    "⚠ Fonction expérimentale (en développement) — l'appairage réseau n'est pas encore actif.",
                    color = OssmWarning,
                    fontSize = 12.sp
                )
            }

            when (val conn = uiState.connection) {
                is RemoteConnectionState.Connected -> ConnectedCard(uiState, onExclusiveControl, onEndSession)
                is RemoteConnectionState.Hosting -> HostingCard(conn.code, onRegenerateCode, onEndSession)
                else -> {
                    HostSection(onStartHosting)
                    JoinSection(uiState, onCodeInputChange, onConnect)
                    if (conn is RemoteConnectionState.Error) {
                        Text(conn.message, color = OssmError, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun HostSection(onStartHosting: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text("Partager ma machine", color = OssmOnSurface, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Génère un code à donner à la personne qui te contrôlera à distance.",
            color = OssmOnSurface.copy(alpha = 0.6f),
            fontSize = 12.sp
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onStartHosting,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = OssmPrimary)
        ) { Text("Générer mon code") }
    }
}

@Composable
private fun HostingCard(code: String, onRegenerate: () -> Unit, onEnd: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth(), tint = OssmConnected) {
        Text("Ton code", color = OssmConnected, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            code.chunked(3).joinToString(" "),
            color = OssmOnSurface,
            fontSize = 34.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "En attente d'un pair… donne ce code à la personne distante.",
            color = OssmOnSurface.copy(alpha = 0.7f),
            fontSize = 12.sp
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onRegenerate, modifier = Modifier.weight(1f)) {
                Text("Nouveau code")
            }
            Button(
                onClick = onEnd,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = OssmError)
            ) { Text("Arrêter") }
        }
    }
}

@Composable
private fun JoinSection(
    uiState: RemoteUiState,
    onCodeInputChange: (String) -> Unit,
    onConnect: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text("Contrôler une machine à distance", color = OssmOnSurface, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = uiState.codeInput,
            onValueChange = onCodeInputChange,
            label = { Text("Code à 9 chiffres") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = OssmPrimary,
                unfocusedBorderColor = OssmGlassBorder,
                focusedTextColor = OssmOnSurface,
                unfocusedTextColor = OssmOnSurface
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onConnect,
            enabled = uiState.codeInput.length == 9,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = OssmPrimary)
        ) { Text("Se connecter") }
    }
}

@Composable
private fun ConnectedCard(
    uiState: RemoteUiState,
    onExclusiveControl: (Boolean) -> Unit,
    onEndSession: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), tint = OssmConnected) {
        Text("Session active", color = OssmConnected, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = uiState.controlOwner == RemoteControlOwner.REMOTE,
                onCheckedChange = onExclusiveControl,
                colors = CheckboxDefaults.colors(checkedColor = OssmAccent)
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Prendre le contrôle exclusif", color = OssmOnSurface, fontSize = 14.sp)
                Text(
                    "L'hôte garde toujours STOP et la fin de session.",
                    color = OssmOnSurface.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onEndSession,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = OssmError)
        ) { Text("Terminer la session") }
    }
}
