package com.ossm.remote.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmStop

/**
 * Round STOP. [stackedLetters] writes S, T, O, P one under the other (top to bottom) with no
 * inner padding, so the word never wraps in the narrow landscape rail; the size is unchanged.
 */
@Composable
fun StopButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    stackedLetters: Boolean = false
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .size(96.dp)
            .then(if (stackedLetters) Modifier.semantics { contentDescription = "STOP" } else Modifier),
        shape = CircleShape,
        border = BorderStroke(2.dp, OssmError),
        contentPadding = if (stackedLetters) PaddingValues(0.dp) else ButtonDefaults.ContentPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = OssmStop,
            contentColor = OssmOnSurface
        )
    ) {
        if (stackedLetters) {
            Text(
                "S\nT\nO\nP",
                fontSize = 18.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.clearAndSetSemantics {}
            )
        } else {
            Text("STOP", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
        }
    }
}
