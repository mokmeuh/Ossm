package com.ossm.remote.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.ossm.remote.R
import com.ossm.remote.ui.theme.OssmDimens

/** Zone de silence blanche autour du code : nécessaire à la lecture, ne pas réduire. */
private val QrQuietZone = 20.dp

/**
 * QR code dessiné localement à partir de [content] (ZXing core, sans réseau ni services Google).
 * Toujours noir sur blanc avec une zone de silence, quel que soit le thème, pour que
 * n'importe quelle caméra le lise. Si le contenu ne peut pas être encodé, un texte le dit.
 */
@Composable
fun QrCodeImage(content: String, contentDescription: String, modifier: Modifier = Modifier) {
    val matrix = remember(content) {
        runCatching {
            Encoder.encode(content, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")).matrix
        }.getOrNull()
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(Color.White, RoundedCornerShape(OssmDimens.ControlRadius))
            .padding(QrQuietZone)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Image
            },
        contentAlignment = Alignment.Center,
    ) {
        if (matrix == null) {
            Text(
                text = stringResource(R.string.conn_qr_unavailable),
                color = Color.Black,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            return@Box
        }
        Canvas(Modifier.fillMaxSize()) {
            val cells = matrix.width
            val cell = size.minDimension / cells
            for (y in 0 until matrix.height) {
                for (x in 0 until cells) {
                    if (matrix.get(x, y).toInt() == 1) {
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(x * cell, y * cell),
                            // Léger recouvrement : évite les fines lignes entre modules.
                            size = Size(cell + 0.5f, cell + 0.5f),
                        )
                    }
                }
            }
        }
    }
}
