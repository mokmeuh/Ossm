package com.ossm.remote.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ossm.remote.R
import com.ossm.remote.model.Preset
import com.ossm.remote.overlay.OverlayService
import com.ossm.remote.remote.RemoteSessionViewModel
import com.ossm.remote.ui.components.GlassCard
import com.ossm.remote.ui.components.OssmButton
import com.ossm.remote.ui.components.OssmButtonKind
import com.ossm.remote.ui.components.OssmCard
import com.ossm.remote.ui.components.OssmStatusLine
import com.ossm.remote.ui.components.OssmSwitchRow
import com.ossm.remote.ui.components.OssmTone
import com.ossm.remote.ui.theme.OssmBackground
import com.ossm.remote.ui.theme.OssmCaption
import com.ossm.remote.ui.theme.OssmDepth
import com.ossm.remote.ui.theme.OssmDimens
import com.ossm.remote.ui.theme.OssmGlassBorder
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmOnSurfaceMuted
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmPrimaryLight
import com.ossm.remote.ui.theme.OssmSectionTitle
import com.ossm.remote.ui.theme.OssmSurfaceHigh
import com.ossm.remote.ui.theme.OssmSurfaceRaised
import com.ossm.remote.ui.theme.OssmValue
import com.ossm.remote.updater.UpdateManifest
import com.ossm.remote.updater.UpdaterStatus
import com.ossm.remote.usb.FirmwareFlashState
import com.ossm.remote.usb.FirmwareFlashViewModel
import com.ossm.remote.viewmodel.OverlaySettingsViewModel
import java.io.File
import kotlin.math.roundToInt

/** Taille des icônes d'en-tête (carte, section repliable). */
internal val SettingsIconSize = 24.dp

/** Durée des ouvertures / fermetures de section (MASTER §7 : 120 à 200 ms). */
private const val FoldAnimationMillis = 160

@Composable
fun ProfilesScreen(
    presets: List<Preset>,
    updaterState: UpdaterStatus,
    updaterUrl: String,
    onApply: (Preset) -> Unit,
    onDelete: (Preset) -> Unit,
    onUpdateUrlSave: (String) -> Unit,
    onUpdateCheck: () -> Unit,
    onUpdateDownload: (UpdateManifest) -> Unit,
    onUpdateInstall: (File) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OssmBackground)
    ) {
        // Garde toute la carte firmware (et son journal) à l'écran pendant un flash.
        val flashVm: FirmwareFlashViewModel = hiltViewModel()
        val flashState by flashVm.state.collectAsState()
        val flashPhase = when (flashState) {
            FirmwareFlashState.Idle -> 0
            FirmwareFlashState.Preparing, FirmwareFlashState.Erasing, is FirmwareFlashState.Writing -> 1
            else -> 2
        }
        val listState = rememberLazyListState()
        // Les deux derniers éléments doivent rester « firmware » puis « statistics » : l'index
        // totalItemsCount - 2 désigne la carte firmware.
        LaunchedEffect(flashPhase) {
            if (flashPhase != 0) listState.scrollToItem((listState.layoutInfo.totalItemsCount - 2).coerceAtLeast(0))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(OssmDimens.ScreenGutter),
            verticalArrangement = Arrangement.spacedBy(OssmDimens.Space16)
        ) {
            item(key = "title") {
                Text(
                    stringResource(R.string.nav_profiles),
                    style = MaterialTheme.typography.titleLarge,
                    color = OssmOnSurface,
                    modifier = Modifier.semantics { heading() }
                )
            }

            item(key = "profiles-header") {
                Text(
                    stringResource(R.string.prof_title).uppercase(),
                    style = OssmSectionTitle,
                    color = OssmOnSurfaceMuted,
                    modifier = Modifier.semantics { heading() }
                )
            }

            if (presets.isEmpty()) {
                item(key = "empty") { ProfilesEmptyState() }
            } else {
                items(presets, key = { it.id }) { preset ->
                    PresetCard(
                        preset = preset,
                        onApply = { onApply(preset) },
                        onDelete = { onDelete(preset) }
                    )
                }
            }
            item(key = "remote-session") { RemoteSessionCard() }
            item(key = "overlay") { OverlayCard() }
            item(key = "updater") {
                UpdaterCard(
                    state = updaterState,
                    url = updaterUrl,
                    onUrlSave = onUpdateUrlSave,
                    onCheck = onUpdateCheck,
                    onDownload = onUpdateDownload,
                    onInstall = onUpdateInstall
                )
            }
            item(key = "firmware") { FirmwareFlashCard() }
            item(key = "statistics") { StatisticsSection() }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Briques communes des Réglages : section repliable, séparateur, ouverture sur changement d'état.
// ---------------------------------------------------------------------------------------------

/**
 * Section repliable : en-tête cliquable (48 dp minimum) et corps animé. L'état ouvert / fermé est
 * porté par l'appelant (voir [rememberSaveable]) pour survivre au défilement de la liste.
 * [collapsedContent] n'est affiché que repliée (ex. un état à ne pas perdre de vue).
 * [forceExpanded] garde la section ouverte (le bouton de repli disparaît).
 */
@Composable
internal fun SettingsFoldSection(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    titleColor: Color = OssmOnSurface,
    forceExpanded: Boolean = false,
    collapsedContent: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val open = expanded || forceExpanded
    val actionLabel = stringResource(if (open) R.string.rg_fold_action_collapse else R.string.rg_fold_action_expand)
    val stateText = stringResource(if (open) R.string.rg_fold_expanded else R.string.rg_fold_collapsed)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = OssmDimens.TouchTarget)
                .clickable(enabled = !forceExpanded, onClickLabel = actionLabel, role = Role.Button) {
                    onExpandedChange(!expanded)
                }
                .semantics { stateDescription = stateText },
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = OssmPrimaryLight, modifier = Modifier.size(SettingsIconSize))
                Spacer(Modifier.width(OssmDimens.Space12))
            }
            Text(
                title.uppercase(),
                style = OssmSectionTitle,
                color = titleColor,
                modifier = Modifier.weight(1f)
            )
            if (!forceExpanded) {
                Spacer(Modifier.width(OssmDimens.Space8))
                Icon(
                    if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = OssmOnSurfaceMuted,
                    modifier = Modifier.size(SettingsIconSize)
                )
            }
        }
        if (!open && collapsedContent != null) {
            Column(
                modifier = Modifier.padding(bottom = OssmDimens.Space8),
                verticalArrangement = Arrangement.spacedBy(OssmDimens.Space8)
            ) {
                collapsedContent()
            }
        }
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(animationSpec = tween(FoldAnimationMillis, easing = FastOutSlowInEasing)) +
                expandVertically(animationSpec = tween(FoldAnimationMillis, easing = FastOutSlowInEasing)),
            exit = fadeOut(animationSpec = tween(FoldAnimationMillis, easing = FastOutSlowInEasing)) +
                shrinkVertically(animationSpec = tween(FoldAnimationMillis, easing = FastOutSlowInEasing))
        ) {
            Column(
                modifier = Modifier.padding(top = OssmDimens.Space4),
                verticalArrangement = Arrangement.spacedBy(OssmDimens.Space12)
            ) {
                content()
            }
        }
    }
}

/** Carte entière repliable (même habillage que [OssmCard], titre cliquable). */
@Composable
internal fun SettingsFoldCard(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    forceExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        SettingsFoldSection(
            title = title,
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            icon = icon,
            titleColor = OssmOnSurfaceMuted,
            forceExpanded = forceExpanded,
            content = content
        )
    }
}

/** Filet fin entre deux sous-sections d'une même carte. */
@Composable
internal fun SettingsDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(OssmDimens.BorderWidth)
            .background(OssmGlassBorder)
    )
}

/**
 * Appelle [onOpen] quand [signal] change pendant que l'écran est affiché (nouvel état à montrer),
 * jamais au simple retour sur l'écran : un repli volontaire de l'utilisateur est respecté.
 * [enabled] permet d'ignorer les changements qui n'ont rien à montrer.
 */
@Composable
internal fun OpenOnChange(signal: Any?, enabled: Boolean = true, onOpen: () -> Unit) {
    var handled by rememberSaveable { mutableStateOf(signal.hashCode()) }
    val currentOnOpen by rememberUpdatedState(onOpen)
    LaunchedEffect(signal) {
        val current = signal.hashCode()
        if (current != handled) {
            handled = current
            if (enabled) currentOnOpen()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Profils
// ---------------------------------------------------------------------------------------------

@Composable
private fun ProfilesEmptyState() {
    OssmCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(OssmDimens.Space8)
        ) {
            Icon(
                Icons.Default.BookmarkBorder,
                contentDescription = null,
                tint = OssmPrimaryLight,
                modifier = Modifier.size(OssmDimens.Space32)
            )
            Text(
                stringResource(R.string.prof_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = OssmOnSurfaceMuted,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PresetCard(preset: Preset, onApply: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }

    OssmCard {
        Column(verticalArrangement = Arrangement.spacedBy(OssmDimens.Space4)) {
            Text(preset.name, style = MaterialTheme.typography.titleMedium, color = OssmOnSurface)
            Text(preset.patternName, style = OssmCaption, color = OssmPrimaryLight)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OssmDimens.Space12)
        ) {
            PresetValue(stringResource(R.string.prof_speed), "${(preset.speed * 100).toInt()}%", OssmPrimary)
            PresetValue(stringResource(R.string.prof_min), "${(preset.depthMin * 100).toInt()}%", OssmDepth)
            PresetValue(stringResource(R.string.prof_max), "${(preset.depthMax * 100).toInt()}%", OssmDepth)
        }
        // Appliquer et Supprimer : deux cibles de 48 dp minimum, séparées d'au moins 8 dp.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OssmDimens.TouchGap)
        ) {
            OssmButton(
                text = stringResource(R.string.prof_apply),
                onClick = onApply,
                icon = Icons.Default.PlayArrow,
                modifier = Modifier.weight(1f)
            )
            OssmButton(
                text = stringResource(R.string.prof_delete),
                onClick = { confirmDelete = true },
                kind = OssmButtonKind.Secondary,
                icon = Icons.Default.Delete,
                modifier = Modifier.weight(1f)
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = OssmSurfaceRaised,
            title = { Text(stringResource(R.string.prof_delete_title), color = OssmOnSurface) },
            text = { Text(stringResource(R.string.prof_delete_msg, preset.name), color = OssmOnSurfaceMuted) },
            confirmButton = {
                OssmButton(
                    text = stringResource(R.string.prof_delete),
                    onClick = { onDelete(); confirmDelete = false },
                    kind = OssmButtonKind.Danger
                )
            },
            dismissButton = {
                OssmButton(
                    text = stringResource(R.string.ctl_cancel),
                    onClick = { confirmDelete = false },
                    kind = OssmButtonKind.Text
                )
            }
        )
    }
}

/** Valeur d'un profil : étiquette au-dessus, nombre en police à chiffres fixes (lu d'un bloc). */
@Composable
private fun RowScope.PresetValue(label: String, value: String, color: Color) {
    Column(
        modifier = Modifier
            .weight(1f)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(OssmDimens.Space4)
    ) {
        Text(label, style = OssmCaption, color = OssmOnSurfaceMuted)
        Text(value, style = OssmValue, color = color)
    }
}

// ---------------------------------------------------------------------------------------------
// Session Internet privée
// ---------------------------------------------------------------------------------------------

@Composable
private fun RemoteSessionCard() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val viewModel: RemoteSessionViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()

    OssmCard(title = stringResource(R.string.remote_session_title)) {
        Text(
            stringResource(R.string.remote_session_hint),
            style = OssmCaption,
            color = OssmOnSurfaceMuted
        )
        OssmStatusLine(
            text = state.message,
            tone = if (state.connected) OssmTone.Ok else OssmTone.Warn
        )
        OssmStatusLine(text = state.mediaStatus)
        state.shareUrl?.let { shareUrl ->
            // Le lien ne va dans le presse-papiers que sur action explicite (bouton Copier).
            var copied by remember(shareUrl) { mutableStateOf(false) }
            OutlinedTextField(
                value = shareUrl,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.remote_session_link)) },
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(OssmDimens.TouchGap),
            ) {
                OssmButton(
                    text = stringResource(R.string.ui2_remote_copy),
                    onClick = {
                        clipboardManager.setText(AnnotatedString(shareUrl))
                        copied = true
                    },
                    icon = Icons.Default.ContentCopy,
                    modifier = Modifier.weight(1f),
                )
                OssmButton(
                    text = stringResource(R.string.remote_share),
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareUrl)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.remote_share)))
                    },
                    kind = OssmButtonKind.Secondary,
                    icon = Icons.Default.Share,
                    modifier = Modifier.weight(1f),
                )
            }
            if (copied) {
                OssmStatusLine(text = stringResource(R.string.ui2_remote_copied), tone = OssmTone.Ok)
            }
        }
        OssmButton(
            text = stringResource(if (state.active) R.string.remote_stop_session else R.string.remote_session_start),
            onClick = { if (state.active) viewModel.stop() else viewModel.start() },
            modifier = Modifier.fillMaxWidth(),
        )
        // Action qui révoque le lien : séparée du bouton principal. Aucune confirmation (comportement inchangé).
        SettingsDivider()
        Column(verticalArrangement = Arrangement.spacedBy(OssmDimens.Space8)) {
            OssmButton(
                text = stringResource(R.string.remote_session_regenerate),
                onClick = viewModel::regenerate,
                kind = OssmButtonKind.Danger,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.remote_session_regenerate_hint),
                style = OssmCaption,
                color = OssmOnSurfaceMuted
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Mise à jour : état de la vérification Internet en tête, trois mécanismes en sections repliables
// ---------------------------------------------------------------------------------------------

@Composable
private fun UpdaterCard(
    state: UpdaterStatus,
    url: String,
    onUrlSave: (String) -> Unit,
    onCheck: () -> Unit,
    onDownload: (UpdateManifest) -> Unit,
    onInstall: (File) -> Unit
) {
    var manualUrl by remember(url) { mutableStateOf(url) }
    var internetOpen by rememberSaveable { mutableStateOf(false) }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = OssmPrimaryLight,
                modifier = Modifier.size(SettingsIconSize)
            )
            Spacer(Modifier.width(OssmDimens.Space12))
            Text(
                stringResource(R.string.update_title).uppercase(),
                style = OssmSectionTitle,
                color = OssmOnSurfaceMuted,
                modifier = Modifier.weight(1f)
            )
            OssmButton(
                text = stringResource(R.string.update_check_now),
                onClick = onCheck,
                kind = OssmButtonKind.Text,
                enabled = state !is UpdaterStatus.Checking && state !is UpdaterStatus.Downloading
            )
        }
        Spacer(Modifier.height(OssmDimens.Space12))
        Column(verticalArrangement = Arrangement.spacedBy(OssmDimens.Space12)) {
            // Résultat de « Vérifier » : toujours visible, même sections repliées.
            UpdaterStatusBlock(state = state, onDownload = onDownload, onInstall = onInstall)
            LocalOtaSection()
            SettingsDivider()
            PcUpdateSection()
            SettingsDivider()
            SettingsFoldSection(
                title = stringResource(R.string.update_ota_title),
                expanded = internetOpen,
                onExpandedChange = { internetOpen = it }
            ) {
                OutlinedTextField(
                    value = manualUrl,
                    onValueChange = { manualUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.update_url_label)) },
                    placeholder = { Text(stringResource(R.string.update_url_hint)) },
                    singleLine = true
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OssmButton(
                        text = stringResource(R.string.update_url_save),
                        onClick = { onUrlSave(manualUrl) },
                        kind = OssmButtonKind.Text
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdaterStatusBlock(
    state: UpdaterStatus,
    onDownload: (UpdateManifest) -> Unit,
    onInstall: (File) -> Unit
) {
    when (state) {
        UpdaterStatus.Idle -> Unit
        UpdaterStatus.Checking -> OssmStatusLine(text = stringResource(R.string.update_checking))
        UpdaterStatus.UpToDate -> OssmStatusLine(text = stringResource(R.string.update_current), tone = OssmTone.Ok)
        is UpdaterStatus.Available -> {
            OssmStatusLine(
                text = stringResource(R.string.update_available, state.manifest.versionName),
                tone = OssmTone.Ok
            )
            OssmButton(
                text = stringResource(R.string.update_download),
                onClick = { onDownload(state.manifest) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        UpdaterStatus.Downloading -> OssmStatusLine(text = stringResource(R.string.update_downloading))
        is UpdaterStatus.ReadyToInstall -> OssmButton(
            text = stringResource(R.string.update_install),
            onClick = { onInstall(state.apk) },
            modifier = Modifier.fillMaxWidth()
        )
        is UpdaterStatus.Error -> OssmStatusLine(text = stringResource(R.string.update_error), tone = OssmTone.Error)
    }
}

// ---------------------------------------------------------------------------------------------
// Overlay
// ---------------------------------------------------------------------------------------------

@Composable
private fun OverlayCard() {
    val context = LocalContext.current
    // hiltViewModel() : même instance que celle utilisée ailleurs (scopée à l'Activity).
    val overlayVm: OverlaySettingsViewModel = hiltViewModel()
    val overlayEnabled by overlayVm.overlayEnabled.collectAsState()
    val transparency by overlayVm.transparencyPercent.collectAsState()
    val canDrawOverlays = Settings.canDrawOverlays(context)
    val transparencyLabel = stringResource(R.string.overlay_transparency)

    OssmCard(title = stringResource(R.string.overlay_title)) {
        OssmSwitchRow(
            label = stringResource(R.string.rg_overlay_switch),
            checked = overlayEnabled,
            onCheckedChange = { enabled -> onOverlayToggle(context, overlayVm, enabled) },
            supporting = stringResource(R.string.overlay_hint)
        )
        if (!canDrawOverlays) {
            OssmStatusLine(text = stringResource(R.string.overlay_permission_hint), tone = OssmTone.Warn)
        }
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    transparencyLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = OssmOnSurface,
                    modifier = Modifier.weight(1f)
                )
                Text("$transparency%", style = OssmValue, color = OssmOnSurface)
            }
            Slider(
                value = transparency.toFloat(),
                onValueChange = { overlayVm.setTransparencyPercent(it.roundToInt()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = transparencyLabel
                        stateDescription = "$transparency%"
                    },
                valueRange = 20f..100f,
                steps = 79,
                colors = SliderDefaults.colors(
                    thumbColor = OssmPrimary,
                    activeTrackColor = OssmPrimary,
                    inactiveTrackColor = OssmSurfaceHigh,
                    // 80 paliers : les repères de palier sont masqués, le pas de 1 % reste.
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                )
            )
        }
    }
}

internal fun onOverlayToggle(context: Context, overlayVm: OverlaySettingsViewModel, enabled: Boolean) {
    if (enabled) {
        if (Settings.canDrawOverlays(context)) {
            OverlayService.manuallyClosed = false
            overlayVm.setOverlayEnabled(true)
        } else {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
        }
    } else {
        // Fermeture délibérée : posé AVANT l'arrêt async du service et l'écriture DataStore,
        // pour que l'auto-start de onStop (app quittée) ne relance pas l'overlay.
        OverlayService.manuallyClosed = true
        overlayVm.setOverlayEnabled(false)
        context.stopService(Intent(context, OverlayService::class.java))
    }
}
