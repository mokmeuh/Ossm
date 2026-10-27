package com.ossm.remote.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ossm.remote.R
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmStop

/** Height of the compact portrait STOP bar (2.3.28); the pause button beside it matches it. */
val StopBarHeight = 56.dp

/**
 * 2.3.28 compact STOP of the Control screen (portrait): a wide red rounded bar that takes the
 * available width, [StopBarHeight] high (>= 48dp target), "STOP" in bold white on `OssmStop`
 * (contrast ~6:1). Kept separate from [StopButton], which other screens still use as a round.
 */
@Composable
fun StopBar(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Standing bar (landscape): S, T, O, P written one under the other. */
    vertical: Boolean = false
) {
    val description = stringResource(R.string.control_stop_bar_cd)
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = StopBarHeight)
            .semantics { contentDescription = description },
        shape = RoundedCornerShape(14.dp),
        contentPadding = if (vertical) PaddingValues(0.dp) else ButtonDefaults.ContentPadding,
        border = BorderStroke(2.dp, OssmError),
        colors = ButtonDefaults.buttonColors(
            containerColor = OssmStop,
            contentColor = OssmOnSurface
        )
    ) {
        Text(
            if (vertical) "S\nT\nO\nP" else "STOP",
            fontSize = 22.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = if (vertical) 0.sp else 3.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}
