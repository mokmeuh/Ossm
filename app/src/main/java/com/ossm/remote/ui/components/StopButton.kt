package com.ossm.remote.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ossm.remote.R
import com.ossm.remote.ui.theme.OssmDimens
import com.ossm.remote.ui.theme.OssmOnStop
import com.ossm.remote.ui.theme.OssmStop

/** Diamètre du STOP rond : bien au-dessus du minimum de 64 dp. */
private val StopButtonDiameter = OssmDimens.StopHeight + OssmDimens.Space32

/**
 * STOP rond. [stackedLetters] écrit S, T, O, P les uns sous les autres, sans marge intérieure,
 * pour que le mot ne passe jamais à la ligne dans un rail étroit ; la taille ne change pas.
 * Même rendu que [StopBar] : fond `OssmStop`, contenu blanc, description « STOP, arrêt immédiat ».
 */
@Composable
fun StopButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    stackedLetters: Boolean = false
) {
    val description = stringResource(R.string.control_stop_bar_cd)
    val word = stringResource(R.string.shell_stop_word)
    Button(
        onClick = onClick,
        modifier = modifier
            .size(StopButtonDiameter)
            .semantics { contentDescription = description },
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = OssmStop,
            contentColor = OssmOnStop
        )
    ) {
        Column(
            modifier = Modifier.clearAndSetSemantics {},
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (stackedLetters) {
                Text(
                    text = word.toCharArray().joinToString("\n"),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
                    textAlign = TextAlign.Center
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.StopCircle,
                    contentDescription = null,
                    modifier = Modifier.size(OssmDimens.Space24)
                )
                Text(
                    text = word,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
