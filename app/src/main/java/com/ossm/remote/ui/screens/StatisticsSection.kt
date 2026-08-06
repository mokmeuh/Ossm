package com.ossm.remote.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ossm.remote.R
import com.ossm.remote.data.repository.DailyStat
import com.ossm.remote.data.repository.PatternStat
import com.ossm.remote.data.repository.StatsSnapshot
import com.ossm.remote.ui.components.GlassCard
import com.ossm.remote.ui.theme.OssmAccent
import com.ossm.remote.ui.theme.OssmConnected
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmGlassBorder
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmPrimaryLight
import com.ossm.remote.ui.theme.OssmSurface
import com.ossm.remote.ui.theme.OssmWarning
import com.ossm.remote.ui.theme.PatternColors
import com.ossm.remote.viewmodel.StatisticsViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

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

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.stat_title),
            style = MaterialTheme.typography.titleLarge,
            color = OssmPrimary,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
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

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            containerColor = OssmSurface,
            title = { Text(stringResource(R.string.stat_reset_title), color = OssmOnSurface) },
            text = { Text(stringResource(R.string.stat_reset_msg), color = OssmOnSurface.copy(alpha = 0.7f)) },
            confirmButton = {
                Button(
                    onClick = {
                        showResetDialog = false
                        showResetConfirmStep2 = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OssmError)
                ) {
                    Text(stringResource(R.string.stat_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.ctl_cancel), color = OssmPrimaryLight)
                }
            }
        )
    }

    if (showResetConfirmStep2) {
        AlertDialog(
            onDismissRequest = { showResetConfirmStep2 = false },
            containerColor = OssmSurface,
            title = { Text(stringResource(R.string.stat_reset_title), color = OssmOnSurface) },
            text = {
                Text(
                    "Êtes-vous absolument certain ? Toutes les données seront définitivement supprimées et ne pourront pas être récupérées.",
                    color = OssmOnSurface.copy(alpha = 0.7f)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.resetAll()
                        showResetConfirmStep2 = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OssmError)
                ) {
                    Text(stringResource(R.string.stat_delete_permanently))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmStep2 = false }) {
                    Text(stringResource(R.string.ctl_cancel), color = OssmPrimaryLight)
                }
            }
        )
    }
}

@Composable
private fun OverviewCard(snapshot: StatsSnapshot) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCell(
                label = stringResource(R.string.stat_total),
                value = formatDuration(snapshot.totalDurationMillis),
                color = OssmPrimary,
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.weight(1f)
            )
            StatCell(
                label = stringResource(R.string.stat_today),
                value = formatDuration(snapshot.todayDurationMillis),
                color = OssmAccent,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCell(
                label = stringResource(R.string.stat_last7),
                value = formatDuration(snapshot.last7DaysDurationMillis),
                color = OssmConnected,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f)
            )
            StatCell(
                label = stringResource(R.string.stat_last30),
                value = formatDuration(snapshot.last30DaysDurationMillis),
                color = OssmWarning,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SessionsCard(snapshot: StatsSnapshot) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCell(
                label = stringResource(R.string.stat_sessions),
                value = "${snapshot.sessionCount}",
                color = OssmOnSurface,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f)
            )
            StatCell(
                label = stringResource(R.string.stat_avg_duration),
                value = formatDuration(snapshot.avgSessionDurationMillis),
                color = OssmAccent,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f)
            )
            StatCell(
                label = stringResource(R.string.stat_max_duration),
                value = formatDuration(snapshot.maxSessionDurationMillis),
                color = OssmWarning,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCell(
                label = stringResource(R.string.stat_avg_speed),
                value = "${snapshot.avgSpeedPercent.roundToInt()}%",
                color = OssmConnected,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f)
            )
            StatCell(
                label = stringResource(R.string.stat_max_speed),
                value = "${snapshot.maxSpeedPercent.roundToInt()}%",
                color = OssmWarning,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(
            value,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}

@Composable
private fun HistoryCard(history: List<DailyStat>) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.stat_history), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.stat_cumulated, formatDuration(history.sumOf { it.durationMillis })),
                style = MaterialTheme.typography.labelSmall,
                color = OssmPrimaryLight
            )
        }
        Spacer(Modifier.height(12.dp))
        DailyHistoryChart(history)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            history.firstOrNull()?.let { Text(dayLabel(it.dateEpochDay), style = MaterialTheme.typography.labelSmall) }
            history.lastOrNull()?.let { Text(dayLabel(it.dateEpochDay), style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun DailyHistoryChart(history: List<DailyStat>) {
    val maxDuration = history.maxOfOrNull { it.durationMillis } ?: 0L
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
    ) {
        if (history.isEmpty()) return@Canvas
        val barSlot = size.width / history.size
        val barWidth = barSlot * 0.55f
        history.forEachIndexed { index, day ->
            val fraction = if (maxDuration > 0L) day.durationMillis.toFloat() / maxDuration else 0f
            if (fraction > 0f) {
                val barHeight = size.height * fraction
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(OssmPrimary, OssmAccent)),
                    topLeft = Offset(index * barSlot + (barSlot - barWidth) / 2f, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                )
            }
        }
        drawLine(
            color = OssmGlassBorder,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1.dp.toPx()
        )
    }
}

@Composable
private fun PatternsCard(stats: List<PatternStat>) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.stat_patterns), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(12.dp))
        if (stats.isEmpty()) {
            Text(
                stringResource(R.string.stat_no_sessions),
                color = OssmOnSurface.copy(alpha = 0.5f),
                fontSize = 13.sp
            )
        } else {
            val total = stats.sumOf { it.totalDurationMillis }
            stats.forEachIndexed { index, stat ->
                PatternBar(
                    stat = stat,
                    color = PatternColors[index % PatternColors.size],
                    fraction = if (total > 0L) stat.totalDurationMillis.toFloat() / total else 0f
                )
                if (index != stats.lastIndex) Spacer(Modifier.height(14.dp))
            }
        }
    }
}

@Composable
private fun PatternBar(stat: PatternStat, color: Color, fraction: Float) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(
                stat.patternName,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${formatDuration(stat.totalDurationMillis)} · ${(fraction * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyLarge,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            stringResource(R.string.stat_sessions_count, stat.sessionCount),
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(OssmPrimary.copy(alpha = 0.15f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
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
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                label = stringResource(R.string.stat_reset),
                icon = Icons.Default.Delete,
                tint = OssmError,
                enabled = true,
                busy = false,
                onClick = onReset,
                modifier = Modifier.weight(1f)
            )
            ActionButton(
                label = stringResource(if (exporting) R.string.stat_exporting else R.string.stat_export),
                icon = Icons.Default.FileDownload,
                tint = OssmPrimary,
                enabled = !exporting,
                busy = exporting,
                onClick = onExport,
                modifier = Modifier.weight(1f)
            )
            ActionButton(
                label = stringResource(if (importing) R.string.stat_importing else R.string.stat_import),
                icon = Icons.Default.FileUpload,
                tint = OssmAccent,
                enabled = !importing,
                busy = importing,
                onClick = onImport,
                modifier = Modifier.weight(1f)
            )
        }
        importError?.let { error ->
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.stat_import_error, error),
                color = OssmError,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    tint: Color,
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = tint.copy(alpha = 0.16f),
            contentColor = tint
        )
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = tint, strokeWidth = 2.dp)
        } else {
            Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
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
