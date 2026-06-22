package com.ossm.remote.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ossm.remote.model.Preset
import com.ossm.remote.ui.components.GlassCard
import com.ossm.remote.ui.theme.*

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
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.BookmarkBorder, null, tint = OssmPrimaryLight, modifier = Modifier.size(48.dp))
                        Text(
                            "Aucun profil sauvegardé\n\nUtilisez l'icône 💾 dans l'onglet Contrôle\npour sauvegarder vos réglages",
                            color = OssmOnSurface.copy(0.4f),
                            fontSize = 14.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(presets, key = { it.id }) { preset ->
                        PresetCard(preset = preset, onApply = { onApply(preset) }, onDelete = { onDelete(preset) })
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
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(preset.name, fontWeight = FontWeight.Bold, color = OssmOnSurface, fontSize = 16.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatChip("Vitesse", "${(preset.speed * 100).toInt()}%", OssmPrimary)
                    StatChip("Prof.", "${(preset.depth * 100).toInt()}%", OssmAccent)
                    StatChip("Stroke", "${(preset.strokeLength * 100).toInt()}%", OssmConnected)
                }
                StatChip("Sensation", "${(preset.sensation * 100).toInt()}%", OssmWarning)
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
            text = { Text("\"${preset.name}\" sera supprimé définitivement.", color = OssmOnSurface.copy(0.7f)) },
            confirmButton = {
                Button(
                    onClick = { onDelete(); confirmDelete = false },
                    colors = ButtonDefaults.buttonColors(containerColor = OssmError)
                ) { Text("Supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Annuler", color = OssmPrimaryLight) }
            }
        )
    }
}

@Composable
private fun StatChip(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
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
