package com.ossm.remote.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ossm.remote.R
import com.ossm.remote.data.repository.DailyStat
import com.ossm.remote.data.repository.PatternStat
import com.ossm.remote.data.repository.StatsSnapshot
import com.ossm.remote.ui.components.OssmButton
import com.ossm.remote.ui.components.OssmButtonKind
import com.ossm.remote.ui.components.OssmCard
import com.ossm.remote.ui.components.OssmStatusLine
import com.ossm.remote.ui.components.OssmTone
import com.ossm.remote.ui.theme.OssmCaption
import com.ossm.remote.ui.theme.OssmDimens
import com.ossm.remote.ui.theme.OssmDisplayValue
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmGlassBorder
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmOnSurfaceMuted
import com.ossm.remote.ui.theme.OssmOutline
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmSurfaceHigh
import com.ossm.remote.ui.theme.OssmSurfaceRaised
import com.ossm.remote.ui.theme.OssmValue
import com.ossm.remote.viewmodel.StatisticsViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

// Repères propres à cette section (la grille générale vient de OssmDimens).
private val StatsChartHeight = 96.dp
private val StatsMinBarHeight = 2.dp
private val StatsBarCorner = 2.dp
private const val STATS_BAR_WIDTH_FRACTION = 0.55f
private val StatsIconSmall = 20.dp
private val StatsSpinnerStroke = 2.dp

/** Section Statistiques de l'écran Profils : chiffres clés, graphiques et actions. */
@Composable
fun StatisticsSection(modifier: Modifier = Modifier) {
    val viewModel: StatisticsViewModel = hiltViewModel()
    val snapshot by viewModel.snapshot.collectAsState()
    val exporting by viewModel.exporting.collectAsState()
    val importing by viewModel.importing.collectAsState()
    val importError by viewModel.importError.collectAsState()
    val pendingExportJson by viewModel.pendingExportJson.collectAsState()

    val context = LocalContext.current
    var showResetDialog by remember { mutableStateOf(false) }
    var showResetConfirmStep2 by remember { mutableStateOf(false) }
    var exportPayload by remember { mutableStateOf<String?>(null) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = exportPayload
        exportPayload = null
        if (uri != null && json != null) writeExport(context, uri, json)
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) readJson(context, uri)?.let(viewModel::importJson)
    }

    LaunchedEffect(pendingExportJson) {
        val json = pendingExportJson ?: return@LaunchedEffect
        exportPayload = json
        viewModel.consumePendingExport()
        createDocumentLauncher.launch(exportFileName())
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(OssmDimens.Space12)) {
        Text(
            stringResource(R.string.stat_title),
            style = MaterialTheme.typography.titleLarge,
            color = OssmOnSurface,
            modifier = Modifier.semantics { heading() }
        )
        OverviewCard(snapshot)
        SessionsCard(snapshot)
        HistoryCard(snapshot.dailyHistory)
        PatternsCard(snapshot.perPattern)
        ActionsCard(
            exporting = exporting,
            importing = importing,
            importError = importError,
            onReset = { showResetDialog = true },
            onExport = viewModel::prepareExport,
            onImport = { openDocumentLauncher.launch(arrayOf("*/*")) }
        )
    }

    // Effacement en deux étapes : première confirmation.
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            containerColor = OssmSurfaceHigh,
            title = { Text(stringResource(R.string.stat_reset_title), color = OssmOnSurface) },
            text = { Text(stringResource(R.string.stat_reset_msg), color = OssmOnSurfaceMuted) },
            confirmButton = {
                OssmButton(
                    text = stringResource(R.string.stat_continue),
                    onClick = {
                        showResetDialog = false
                        showResetConfirmStep2 = true
                    },
                    kind = OssmButtonKind.Danger
                )
            },
            dismissButton = {
                OssmButton(
                    text = stringResource(R.string.ctl_cancel),
                    onClick = { showResetDialog = false },
                    kind = OssmButtonKind.Text
                )
            }
        )
    }

    // Seconde confirmation : seule celle-ci efface réellement les données.
    if (showResetConfirmStep2) {
        AlertDialog(
            onDismissRequest = { showResetConfirmStep2 = false },
            containerColor = OssmSurfaceHigh,
            title = { Text(stringResource(R.string.stat_reset_title), color = OssmOnSurface) },
            text = { Text(stringResource(R.string.stat_reset_final_msg), color = OssmOnSurfaceMuted) },
            confirmButton = {
                OssmButton(
                    text = stringResource(R.string.stat_delete_permanently),
                    onClick = {
                        viewModel.resetAll()
                        showResetConfirmStep2 = false
                    },
                    kind = OssmButtonKind.Danger
                )
            },
            dismissButton = {
                OssmButton(
                    text = stringResource(R.string.ctl_cancel),
                    onClick = { showResetConfirmStep2 = false },
                    kind = OssmButtonKind.Text
                )
            }
        )
    }
}

/** Une ligne « libellé ... valeur » : robuste à toutes les tailles de police. */
private data class StatsEntry(val label: String, val value: String, val valueColor: Color = OssmOnSurface)

/** Les libellés existants sont en majuscules : on les passe en casse de phrase pour la lecture. */
private fun String.statsSentenceCase(): String = lowercase().replaceFirstChar { it.uppercase() }

@Composable
private fun OverviewCard(snapshot: StatsSnapshot) {
    OssmCard(
        modifier = Modifier.testTag("stats_overview"),
        title = stringResource(R.string.conn_stat_usage_title)
    ) {
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(OssmDimens.Space4)
        ) {
            Text(
                stringResource(R.string.stat_total).statsSentenceCase(),
                color = OssmOnSurfaceMuted,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                formatDuration(snapshot.totalDurationMillis),
                color = OssmOnSurface,
                style = OssmDisplayValue
            )
        }
        StatsTable(
            listOf(
                StatsEntry(stringResource(R.string.stat_today).statsSentenceCase(), formatDuration(snapshot.todayDurationMillis)),
                StatsEntry(stringResource(R.string.stat_last7).statsSentenceCase(), formatDuration(snapshot.last7DaysDurationMillis)),
                StatsEntry(stringResource(R.string.stat_last30).statsSentenceCase(), formatDuration(snapshot.last30DaysDurationMillis))
            )
        )
    }
}

@Composable
private fun SessionsCard(snapshot: StatsSnapshot) {
    OssmCard(
        modifier = Modifier.testTag("stats_sessions"),
        title = stringResource(R.string.conn_stat_detail_title)
    ) {
        StatsTable(
            listOf(
                StatsEntry(stringResource(R.string.stat_sessions).statsSentenceCase(), "${snapshot.sessionCount}"),
                StatsEntry(stringResource(R.string.stat_avg_duration).statsSentenceCase(), formatDuration(snapshot.avgSessionDurationMillis)),
                StatsEntry(stringResource(R.string.stat_max_duration).statsSentenceCase(), formatDuration(snapshot.maxSessionDurationMillis)),
                // La vitesse est le canal turquoise : ses valeurs gardent la couleur de l'action.
                StatsEntry(stringResource(R.string.stat_avg_speed).statsSentenceCase(), "${snapshot.avgSpeedPercent.roundToInt()}%", OssmPrimary),
                StatsEntry(stringResource(R.string.stat_max_speed).statsSentenceCase(), "${snapshot.maxSpeedPercent.roundToInt()}%", OssmPrimary)
            )
        )
    }
}

@Composable
private fun StatsTable(entries: List<StatsEntry>) {
    Column(verticalArrangement = Arrangement.spacedBy(OssmDimens.Space8)) {
        entries.forEachIndexed { index, entry ->
            if (index > 0) HorizontalDivider(color = OssmGlassBorder)
            StatsRow(entry)
        }
    }
}

@Composable
private fun StatsRow(entry: StatsEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OssmDimens.Space12)
    ) {
        Text(
            entry.label,
            color = OssmOnSurfaceMuted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(entry.value, color = entry.valueColor, style = OssmValue)
    }
}

@Composable
private fun HistoryCard(history: List<DailyStat>) {
    val activeDays = history.count { it.durationMillis > 0L }
    val peak = history.maxByOrNull { it.durationMillis }?.takeIf { it.durationMillis > 0L }
    // Résumé textuel : lu par les lecteurs d'écran et affiché sous le graphe.
    val summary = if (peak == null) {
        stringResource(R.string.conn_stat_chart_empty)
    } else {
        stringResource(
            R.string.conn_stat_chart_summary,
            activeDays,
            history.size,
            formatDuration(peak.durationMillis),
            dayLabel(peak.dateEpochDay)
        )
    }
    val chartDescription = if (history.isEmpty()) {
        summary
    } else {
        stringResource(
            R.string.conn_stat_chart_cd,
            dayLabel(history.first().dateEpochDay),
            dayLabel(history.last().dateEpochDay),
            summary
        )
    }
    OssmCard(
        modifier = Modifier.testTag("stats_history"),
        title = stringResource(R.string.stat_history)
    ) {
        Text(
            stringResource(R.string.stat_cumulated, formatDuration(history.sumOf { it.durationMillis })),
            color = OssmOnSurface,
            style = MaterialTheme.typography.bodyMedium
        )
        DailyHistoryChart(history, chartDescription)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            history.firstOrNull()?.let { Text(dayLabel(it.dateEpochDay), color = OssmOnSurfaceMuted, style = OssmCaption) }
            history.lastOrNull()?.let { Text(dayLabel(it.dateEpochDay), color = OssmOnSurfaceMuted, style = OssmCaption) }
        }
        Text(summary, color = OssmOnSurfaceMuted, style = OssmCaption)
    }
}

@Composable
private fun DailyHistoryChart(history: List<DailyStat>, description: String) {
    val maxDuration = history.maxOfOrNull { it.durationMillis } ?: 0L
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(StatsChartHeight)
            .semantics {
                contentDescription = description
                role = Role.Image
            }
    ) {
        if (history.isEmpty()) return@Canvas
        val barSlot = size.width / history.size
        val barWidth = barSlot * STATS_BAR_WIDTH_FRACTION
        val minBar = StatsMinBarHeight.toPx()
        val corner = StatsBarCorner.toPx()
        history.forEachIndexed { index, day ->
            if (day.durationMillis > 0L && maxDuration > 0L) {
                val fraction = day.durationMillis.toFloat() / maxDuration
                val barHeight = (size.height * fraction).coerceAtLeast(minBar)
                // Barre unie : la hauteur porte la valeur, pas de dégradé décoratif.
                drawRoundRect(
                    color = OssmPrimary,
                    topLeft = Offset(index * barSlot + (barSlot - barWidth) / 2f, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(corner, corner)
                )
            }
        }
        drawLine(
            color = OssmOutline,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = OssmDimens.BorderWidth.toPx()
        )
    }
}

@Composable
private fun PatternsCard(stats: List<PatternStat>) {
    OssmCard(
        modifier = Modifier.testTag("stats_patterns"),
        title = stringResource(R.string.stat_patterns)
    ) {
        if (stats.isEmpty()) {
            OssmStatusLine(text = stringResource(R.string.stat_no_sessions), tone = OssmTone.Neutral)
        } else {
            val total = stats.sumOf { it.totalDurationMillis }
            stats.forEach { stat ->
                PatternBar(
                    stat = stat,
                    fraction = if (total > 0L) stat.totalDurationMillis.toFloat() / total else 0f
                )
            }
        }
    }
}

@Composable
private fun PatternBar(stat: PatternStat, fraction: Float) {
    val barShape = RoundedCornerShape(OssmDimens.FieldRadius)
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(OssmDimens.Space4)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OssmDimens.Space12)
        ) {
            Text(
                stat.patternName,
                color = OssmOnSurface,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text("${(fraction * 100).roundToInt()}%", color = OssmPrimary, style = OssmValue)
        }
        Text(
            "${formatDuration(stat.totalDurationMillis)} · " +
                stringResource(R.string.stat_sessions_count, stat.sessionCount),
            color = OssmOnSurfaceMuted,
            style = OssmCaption
        )
        // Une seule couleur pour toutes les barres : le nom et le pourcentage disent de quel pattern il s'agit.
        Box(
            Modifier
                .fillMaxWidth()
                .height(OssmDimens.SliderTrack)
                .clip(barShape)
                .background(OssmSurfaceHigh)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(OssmDimens.SliderTrack)
                    .clip(barShape)
                    .background(OssmPrimary)
            )
        }
    }
}

@Composable
private fun ActionsCard(
    exporting: Boolean,
    importing: Boolean,
    importError: String?,
    onReset: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    OssmCard(modifier = Modifier.testTag("stats_actions")) {
        Row(horizontalArrangement = Arrangement.spacedBy(OssmDimens.Space8)) {
            StatsActionButton(
                label = stringResource(if (exporting) R.string.stat_exporting else R.string.stat_export),
                icon = Icons.Filled.FileDownload,
                onClick = onExport,
                modifier = Modifier
                    .weight(1f)
                    .testTag("stats_export"),
                enabled = !exporting,
                busy = exporting
            )
            StatsActionButton(
                label = stringResource(if (importing) R.string.stat_importing else R.string.stat_import),
                icon = Icons.Filled.FileUpload,
                onClick = onImport,
                modifier = Modifier
                    .weight(1f)
                    .testTag("stats_import"),
                enabled = !importing,
                busy = importing
            )
        }
        importError?.let { error ->
            OssmStatusLine(text = stringResource(R.string.stat_import_error, error), tone = OssmTone.Error)
        }
        // L'action destructive est isolée des deux autres : séparateur et bouton pleine largeur.
        HorizontalDivider(color = OssmGlassBorder)
        StatsActionButton(
            label = stringResource(R.string.stat_reset),
            icon = Icons.Filled.Delete,
            onClick = onReset,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("stats_reset"),
            destructive = true
        )
    }
}

/** Bouton d'action de 48 dp minimum : contour, icône + mot, rouge pour l'action destructive. */
@Composable
private fun StatsActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    destructive: Boolean = false
) {
    val accent = if (destructive) OssmError else OssmOnSurface
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = OssmDimens.TouchTarget),
        shape = RoundedCornerShape(OssmDimens.ControlRadius),
        border = BorderStroke(
            OssmDimens.BorderWidth,
            if (!enabled) OssmGlassBorder else if (destructive) OssmError else OssmOutline
        ),
        contentPadding = PaddingValues(horizontal = OssmDimens.Space12, vertical = OssmDimens.Space8),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = OssmSurfaceRaised,
            contentColor = accent,
            disabledContainerColor = OssmSurfaceRaised,
            disabledContentColor = OssmOnSurfaceMuted
        )
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(StatsIconSmall),
                color = OssmPrimary,
                strokeWidth = StatsSpinnerStroke
            )
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.size(StatsIconSmall))
        }
        Spacer(Modifier.width(OssmDimens.Space8))
        Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

private fun writeExport(context: Context, uri: Uri, json: String) {
    try {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(json.toByteArray(Charsets.UTF_8))
        }
    } catch (_: Exception) {
        // Échec d'écriture silencieux : l'utilisateur garde la main sur la destination choisie.
    }
}

private fun readJson(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
}.getOrNull()

private fun exportFileName(): String =
    "ossm_sessions_${LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)}.json"

private val dayLabelFormatter = DateTimeFormatter.ofPattern("d MMM")

private fun dayLabel(epochDay: Long): String =
    LocalDate.ofEpochDay(epochDay).format(dayLabelFormatter)

/** Formate une durée en millisecondes : "45s", "12min", "2h 15min". */
fun formatDuration(millis: Long): String {
    val seconds = millis / 1000
    if (seconds < 60) return "${seconds}s"
    val minutes = (seconds + 30) / 60
    if (minutes < 60) return "${minutes}min"
    val hours = minutes / 60
    val remMinutes = minutes % 60
    return if (remMinutes == 0L) "${hours}h" else "${hours}h ${remMinutes}min"
}
