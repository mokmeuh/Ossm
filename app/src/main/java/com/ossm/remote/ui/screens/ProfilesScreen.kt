package com.ossm.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ossm.remote.model.Preset
import com.ossm.remote.ui.components.GlassCard
import com.ossm.remote.ui.theme.OssmAccent
import com.ossm.remote.ui.theme.OssmBackground
import com.ossm.remote.ui.theme.OssmConnected
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmPrimaryLight
import com.ossm.remote.ui.theme.OssmSecondary
import com.ossm.remote.ui.theme.OssmSurface

@Composable
fun ProfilesScreen(
    presets: List<Preset>,
    onApply: (Preset) -> Unit,
    onDelete: (Preset) -> Unit
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
                "PROFILS",
                style = MaterialTheme.typography.titleLarge,
                color = OssmPrimary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            if (presets.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.BookmarkBorder, null, tint = OssmPrimaryLight, modifier = Modifier.size(48.dp))
                        Text(
                            "Aucun profil sauvegarde\n\nUtilisez l'icone de sauvegarde dans l'onglet Controle pour garder vos reglages.",
                            color = OssmOnSurface.copy(alpha = 0.4f),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(presets, key = { it.id }) { preset ->
                        PresetCard(
                            preset = preset,
                            onApply = { onApply(preset) },
                            onDelete = { onDelete(preset) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetCard(preset: Preset, onApply: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }

    GlassCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(preset.name, fontWeight = FontWeight.Bold, color = OssmOnSurface, fontSize = 16.sp)
                Text(preset.patternName, color = OssmPrimaryLight, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatChip("Vitesse", "${(preset.speed * 100).toInt()}%", OssmPrimary)
                    StatChip("Min", "${(preset.depthMin * 100).toInt()}%", OssmAccent)
                    StatChip("Max", "${(preset.depthMax * 100).toInt()}%", OssmConnected)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onApply) {
                    Icon(Icons.Default.PlayArrow, "Appliquer", tint = OssmConnected)
                }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Default.Delete, "Supprimer", tint = OssmError)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = OssmSurface,
            title = { Text("Supprimer le profil ?", color = OssmOnSurface) },
            text = { Text("\"${preset.name}\" sera supprime definitivement.", color = OssmOnSurface.copy(alpha = 0.7f)) },
            confirmButton = {
                Button(
                    onClick = { onDelete(); confirmDelete = false },
                    colors = ButtonDefaults.buttonColors(containerColor = OssmError)
                ) {
                    Text("Supprimer")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Annuler", color = OssmPrimaryLight)
                }
            }
        )
    }
}

@Composable
private fun StatChip(label: String, value: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, color = OssmPrimaryLight, fontSize = 10.sp)
        Text(
            value,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
