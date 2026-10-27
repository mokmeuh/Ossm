package com.ossm.remote.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * QR code drawn locally from [content] (ZXing core, no network, no Google services).
 * Always black on white with a quiet zone, whatever the theme, so any camera reads it.
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
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(20.dp)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Image
            },
    ) {
        if (matrix == null) return@Box
        Canvas(Modifier.fillMaxSize()) {
            val cells = matrix.width
            val cell = size.minDimension / cells
            for (y in 0 until matrix.height) {
                for (x in 0 until cells) {
                    if (matrix.get(x, y).toInt() == 1) {
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(x * cell, y * cell),
                            // Slight overlap avoids hairlines between modules.
                            size = Size(cell + 0.5f, cell + 0.5f),
                        )
                    }
                }
            }
        }
    }
}
