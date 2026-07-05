package com.ossm.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ossm.remote.ui.components.GlassCard
import com.ossm.remote.ui.theme.*

@Composable
fun WarningScreen(onAccept: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(OssmBackground, OssmSecondary))
            ),
        contentAlignment = Alignment.Center
    ) {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .wrapContentHeight(),
            padding = 28.dp
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = OssmWarning,
                    modifier = Modifier.size(56.dp)
                )

                Text(
                    text = "⚠ Avertissement",
                    style = MaterialTheme.typography.headlineMedium,
                    color = OssmWarning,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Cette application contrôle un appareil OSSM/OFFM via Bluetooth Low Energy.",
                    color = OssmOnSurface,
                    textAlign = TextAlign.Center,
                    fontSize = 15.sp
                )

                Divider(color = OssmGlassBorder, thickness = 1.dp)

                Text(
                    text = "⚡ L'API Bluetooth OSSM est expérimentale.",
                    color = OssmWarning,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp
                )

                Text(
                    text = "Assurez-vous que le bouton STOP physique est accessible à tout moment.\n\nN'utilisez jamais cet appareil sans contrôle approprié.",
                    color = OssmOnSurface.copy(0.8f),
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = onAccept,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OssmPrimary)
                ) {
                    Text(
                        text = "J'ai compris, continuer",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}
