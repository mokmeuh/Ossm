package com.ossm.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ossm.remote.R
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.model.DiagnosticsLog
import com.ossm.remote.model.LogLevel
import com.ossm.remote.ui.components.OssmCard
import com.ossm.remote.ui.components.OssmStatusLine
import com.ossm.remote.ui.components.OssmTone
import com.ossm.remote.ui.theme.OssmBackground
import com.ossm.remote.ui.theme.OssmCaption
import com.ossm.remote.ui.theme.OssmDimens
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmGlassBorder
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmOnSurfaceMuted
import com.ossm.remote.ui.theme.OssmOutline
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmSurface
import com.ossm.remote.ui.theme.OssmSurfaceRaised
import com.ossm.remote.ui.theme.OssmWarning
import kotlinx.coroutines.delay

/** Durée d'affichage de la confirmation « Journal copié ». */
private const val DIAG_COPY_FEEDBACK_MS = 2_500L

private val DiagLevelIconSize = 16.dp

@Composable
fun DiagnosticsScreen(
    logs: List<DiagnosticsLog>,
    connectionState: BleConnectionState,
    lastCommand: String,
    onClear: () -> Unit
) {
    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    // Défilement automatique vers la dernière entrée.
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    // La confirmation de copie disparaît seule.
    LaunchedEffect(copied) {
        if (copied) {
            delay(DIAG_COPY_FEEDBACK_MS)
            copied = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OssmBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(OssmDimens.ScreenGutter),
            verticalArrangement = Arrangement.spacedBy(OssmDimens.Space12)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(OssmDimens.Space8),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.diag_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = OssmOnSurface,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() }
                )
                DiagActionButton(
                    icon = Icons.Filled.ContentCopy,
                    description = stringResource(R.string.diag_copy),
                    tint = OssmPrimary,
                    onClick = {
                        clipboardManager.setText(
                            AnnotatedString(
                                logs.joinToString(separator = "\n") { log ->
                                    "${log.formattedTime()} [${log.tag}] ${log.message}"
                                }
                            )
                        )
                        copied = true
                    },
                    modifier = Modifier.testTag("diag_copy")
                )
                DiagActionButton(
                    icon = Icons.Filled.Delete,
                    description = stringResource(R.string.diag_clear),
                    tint = OssmError,
                    onClick = onClear,
                    modifier = Modifier.testTag("diag_clear")
                )
            }

            // État de la liaison et dernière commande.
            OssmCard(modifier = Modifier.testTag("diag_state_card")) {
                DiagField(label = stringResource(R.string.diag_ble_state)) {
                    ConnectionLinkBadge(state = connectionState)
                }
                DiagField(label = stringResource(R.string.diag_last_cmd)) {
                    Text(
                        lastCommand.ifBlank { "—" },
                        color = OssmOnSurface,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    )
                }
                if (connectionState is BleConnectionState.Connected) {
                    HorizontalDivider(color = OssmGlassBorder)
                    DiagField(label = stringResource(R.string.diag_mac)) {
                        Text(
                            connectionState.address,
                            color = OssmOnSurface,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                        )
                    }
                }
            }

            // Compteur d'entrées et confirmation de copie.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OssmDimens.Space12)
            ) {
                Text(
                    stringResource(R.string.diag_entries, logs.size),
                    color = OssmOnSurfaceMuted,
                    style = OssmCaption
                )
                if (copied) {
                    OssmStatusLine(
                        text = stringResource(R.string.conn_diag_copied),
                        modifier = Modifier
                            .weight(1f)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        tone = OssmTone.Ok
                    )
                }
            }

            // Journal : chaque niveau porte une icône et un mot, pas seulement une couleur.
            val listShape = RoundedCornerShape(OssmDimens.CardRadius)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(listShape)
                    .background(OssmSurface)
                    .border(OssmDimens.BorderWidth, OssmGlassBorder, listShape)
            ) {
                if (logs.isEmpty()) {
                    OssmStatusLine(
                        text = stringResource(R.string.conn_diag_empty),
                        modifier = Modifier.padding(OssmDimens.Space16)
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("diag_log_list"),
                    contentPadding = PaddingValues(OssmDimens.Space12),
                    verticalArrangement = Arrangement.spacedBy(OssmDimens.Space12)
                ) {
                    items(logs, key = { it.id }) { log ->
                        LogRow(log)
                    }
                }
            }
        }
    }
}

/** Bouton-icône de l'en-tête : cible de 48 dp, fond relevé et contour, description lue par TalkBack. */
@Composable
private fun DiagActionButton(
    icon: ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(OssmDimens.ControlRadius)
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(OssmDimens.TouchTarget)
            .clip(shape)
            .background(OssmSurfaceRaised)
            .border(OssmDimens.BorderWidth, OssmOutline, shape)
    ) {
        Icon(icon, contentDescription = description, tint = tint)
    }
}

/** Libellé discret au-dessus d'une valeur. */
@Composable
private fun DiagField(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(OssmDimens.Space4)) {
        Text(label, color = OssmOnSurfaceMuted, style = OssmCaption)
        content()
    }
}

/** Présentation d'un niveau de journal : icône, mot et couleur (jamais la couleur seule). */
internal data class LogLevelStyle(val icon: ImageVector, val labelRes: Int, val color: Color)

internal fun logLevelStyle(level: LogLevel): LogLevelStyle = when (level) {
    LogLevel.DEBUG -> LogLevelStyle(Icons.Filled.BugReport, R.string.conn_log_level_debug, OssmOnSurfaceMuted)
    LogLevel.INFO -> LogLevelStyle(Icons.Filled.Info, R.string.conn_log_level_info, OssmOnSurface)
    LogLevel.WARNING -> LogLevelStyle(Icons.Filled.WarningAmber, R.string.conn_log_level_warning, OssmWarning)
    LogLevel.ERROR -> LogLevelStyle(Icons.Filled.ErrorOutline, R.string.conn_log_level_error, OssmError)
}

@Composable
private fun LogRow(log: DiagnosticsLog) {
    val style = logLevelStyle(log.level)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(OssmDimens.Space4)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OssmDimens.Space8)
        ) {
            Icon(
                style.icon,
                contentDescription = null,
                tint = style.color,
                modifier = Modifier.size(DiagLevelIconSize)
            )
            Text(
                stringResource(style.labelRes),
                color = style.color,
                style = OssmCaption,
                fontWeight = FontWeight.Bold
            )
            Text(
                "${log.formattedTime()}  [${log.tag}]",
                color = OssmOnSurfaceMuted,
                style = OssmCaption.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            log.message,
            color = style.color,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
