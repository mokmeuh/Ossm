package com.ossm.remote.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ossm.remote.BuildConfig
import com.ossm.remote.R
import com.ossm.remote.model.LogLevel
import com.ossm.remote.remote.CameraJournalEntry
import com.ossm.remote.remote.CameraShareDiagnostics
import com.ossm.remote.remote.CameraShareJournal
import com.ossm.remote.remote.CameraSharePolicy
import com.ossm.remote.ui.components.GlassCard
import com.ossm.remote.ui.theme.OssmAccent
import com.ossm.remote.ui.theme.OssmBackground
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmOnSurfaceMuted
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmPrimaryLight
import com.ossm.remote.ui.theme.OssmStop
import com.ossm.remote.ui.theme.OssmWarning

private const val MAX_CONSOLE_LINES = 20

private val CAMERA_SHARE_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Full-screen "share my camera" page (route [com.ossm.remote.ui.navigation.Screen.CameraShare]).
 *
 * Loads the broker's camera page ([cameraUrl], camera role only) in a locked-down WebView once
 * CAMERA and RECORD_AUDIO are granted. The page itself shows the local preview and the
 * front/back switch. Leaving this screen (stop button, back, app sent to background, session
 * ended or link regenerated) destroys the WebView, which releases the camera and microphone.
 * The link is never logged; the page console is not forwarded to logcat.
 */
@Composable
fun CameraShareScreen(
    cameraUrl: String,
    onStopSharing: () -> Unit,
    onDiagnostic: (LogLevel, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val brokerBaseUrl = BuildConfig.REMOTE_BROKER_BASE_URL
    val stop by rememberUpdatedState(onStopSharing)
    val diagnosticSink by rememberUpdatedState(onDiagnostic)
    val journalEntries = remember { mutableStateListOf<CameraJournalEntry>() }
    val journal = remember { CameraShareJournal(journalEntries, sink = { level, message -> diagnosticSink(level, message) }) }
    var rendererGone by remember { mutableStateOf(false) }
    val urlAllowed = remember(cameraUrl) { CameraSharePolicy.isLoadableCameraUrl(cameraUrl, brokerBaseUrl) }
    var permissionsGranted by remember { mutableStateOf(CAMERA_SHARE_PERMISSIONS.all(context::isGranted)) }
    var permissionRefused by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val camera = context.isGranted(Manifest.permission.CAMERA)
        val microphone = context.isGranted(Manifest.permission.RECORD_AUDIO)
        permissionsGranted = camera && microphone
        permissionRefused = !permissionsGranted
        journal.record(
            if (permissionsGranted) LogLevel.INFO else LogLevel.WARNING,
            CameraShareDiagnostics.androidPermissions(camera, microphone),
        )
    }

    // Opening diagnostics: link shape (never the link), Android permissions, WebView engine.
    LaunchedEffect(Unit) {
        journal.record(
            if (urlAllowed) LogLevel.INFO else LogLevel.ERROR,
            CameraShareDiagnostics.linkCheck(cameraUrl, brokerBaseUrl),
        )
        journal.record(
            LogLevel.INFO,
            CameraShareDiagnostics.androidPermissions(
                context.isGranted(Manifest.permission.CAMERA),
                context.isGranted(Manifest.permission.RECORD_AUDIO),
            ),
        )
        val webViewPackage = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        journal.record(
            if (webViewPackage == null) LogLevel.WARNING else LogLevel.INFO,
            CameraShareDiagnostics.webViewPackage(webViewPackage?.packageName, webViewPackage?.versionName),
        )
    }

    LaunchedEffect(urlAllowed) {
        if (urlAllowed && !permissionsGranted) permissionLauncher.launch(CAMERA_SHARE_PERMISSIONS)
    }

    BackHandler { stop() }

    // The camera must not keep streaming once the app leaves the foreground. Before the WebView
    // exists (permission step, possibly via the app settings) leaving the app keeps this screen.
    val sharing by rememberUpdatedState(urlAllowed && permissionsGranted && !rendererGone)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> if (sharing) stop()
                Lifecycle.Event.ON_RESUME -> {
                    val before = permissionsGranted
                    permissionsGranted = CAMERA_SHARE_PERMISSIONS.all(context::isGranted)
                    if (permissionsGranted) permissionRefused = false
                    if (before != permissionsGranted) {
                        journal.record(
                            LogLevel.INFO,
                            CameraShareDiagnostics.androidPermissions(
                                context.isGranted(Manifest.permission.CAMERA),
                                context.isGranted(Manifest.permission.RECORD_AUDIO),
                            ),
                        )
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Opaque full-screen route: swallow touches so the tabs underneath stay inert.
            .pointerInput(Unit) {}
            .background(OssmBackground)
            .systemBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.ui2_camera_screen_title),
            color = OssmOnSurface,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.ui2_camera_screen_hint),
            color = OssmOnSurfaceMuted,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        Button(
            onClick = { stop() },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = OssmStop),
        ) {
            Icon(Icons.Default.VideocamOff, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ui2_camera_stop), fontWeight = FontWeight.Bold)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            when {
                !urlAllowed -> Text(
                    stringResource(R.string.ui2_camera_invalid_link),
                    color = OssmError,
                    textAlign = TextAlign.Center,
                )
                !permissionsGranted -> CameraPermissionCard(
                    refused = permissionRefused,
                    onRequest = { permissionLauncher.launch(CAMERA_SHARE_PERMISSIONS) },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
                // Keep the screen open with a readable error: the WebView is already dropped
                // (camera released); closing silently left the user with no explanation.
                rendererGone -> Text(
                    stringResource(R.string.ui2_camera_renderer_gone),
                    color = OssmError,
                    textAlign = TextAlign.Center,
                )
                else -> CameraShareWebView(
                    cameraUrl = cameraUrl,
                    brokerBaseUrl = brokerBaseUrl,
                    journal = journal,
                    onRendererGone = { rendererGone = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        CameraDiagnosticsPanel(entries = journalEntries)
    }
}

/** Short, non-sensitive journal (never a link or key): what happened while opening the camera. */
@Composable
private fun CameraDiagnosticsPanel(entries: List<CameraJournalEntry>) {
    val scroll = rememberScrollState()
    LaunchedEffect(entries.size) { scroll.animateScrollTo(scroll.maxValue) }
    GlassCard(modifier = Modifier.fillMaxWidth(), padding = 10.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.ui2_camera_diag_title),
                color = OssmPrimaryLight,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            entries.lastOrNull()?.let { last ->
                Text(
                    last.message,
                    color = if (last.level >= LogLevel.WARNING) OssmError else OssmOnSurface,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 120.dp)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                entries.forEach { entry ->
                    Text(
                        "• " + entry.message,
                        color = when (entry.level) {
                            LogLevel.ERROR -> OssmError
                            LogLevel.WARNING -> OssmWarning
                            else -> OssmOnSurfaceMuted
                        },
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraPermissionCard(
    refused: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Videocam, contentDescription = null, tint = OssmAccent, modifier = Modifier.size(32.dp))
            Text(
                stringResource(R.string.ui2_camera_permission_title),
                color = OssmOnSurface,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(if (refused) R.string.ui2_camera_permission_refused else R.string.ui2_camera_permission_body),
                color = if (refused) OssmError else OssmOnSurfaceMuted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Button(
                onClick = onRequest,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OssmPrimary),
            ) {
                Text(stringResource(R.string.ui2_camera_permission_grant))
            }
            if (refused) {
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = OssmPrimaryLight)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ui2_camera_permission_settings), color = OssmPrimaryLight)
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CameraShareWebView(
    cameraUrl: String,
    brokerBaseUrl: String,
    journal: CameraShareJournal,
    onRendererGone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rendererGone by rememberUpdatedState(onRendererGone)
    // Set once the renderer died: the WebView must then only be destroyed, never driven again.
    val deadRenderer = remember { booleanArrayOf(false) }
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            WebView(viewContext).apply {
                keepScreenOn = true
                settings.apply {
                    javaScriptEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    setGeolocationEnabled(false)
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
                journal.record(
                    LogLevel.INFO,
                    CameraShareDiagnostics.webViewSettings(
                        javaScript = settings.javaScriptEnabled,
                        domStorage = settings.domStorageEnabled,
                        mediaNeedsGesture = settings.mediaPlaybackRequiresUserGesture,
                    ),
                )
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val block = CameraSharePolicy.shouldBlockNavigation(request.url?.toString(), brokerBaseUrl)
                        if (block) journal.record(LogLevel.WARNING, CameraShareDiagnostics.navigationBlocked())
                        return block
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        if (CameraSharePolicy.isBrokerOrigin(url, brokerBaseUrl)) {
                            journal.record(LogLevel.INFO, CameraShareDiagnostics.pageStarted())
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        // Only the camera page is reported (not about:blank on release). The link
                        // itself is only inspected for its fragment, never written anywhere.
                        if (!CameraSharePolicy.isBrokerOrigin(url, brokerBaseUrl)) return
                        val fragmentKept = url?.substringAfter('#', "")?.startsWith("k=") == true
                        journal.record(LogLevel.INFO, CameraShareDiagnostics.pageFinished(fragmentKept))
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        journal.record(
                            if (request.isForMainFrame) LogLevel.ERROR else LogLevel.WARNING,
                            CameraShareDiagnostics.loadError(error.errorCode, error.description, request.isForMainFrame),
                        )
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse,
                    ) {
                        if (!request.isForMainFrame) return
                        journal.record(LogLevel.ERROR, CameraShareDiagnostics.httpError(errorResponse.statusCode, mainFrame = true))
                    }

                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                        journal.record(LogLevel.ERROR, CameraShareDiagnostics.sslError(error.primaryError))
                        handler.cancel()
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        deadRenderer[0] = true
                        journal.record(LogLevel.ERROR, CameraShareDiagnostics.renderProcessGone(detail.didCrash()))
                        rendererGone()
                        return true
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) {
                        val origin = request.origin?.toString()
                        val requested = request.resources
                        val grant = CameraSharePolicy.grantableResources(
                            origin = origin,
                            requested = requested,
                            brokerBaseUrl = brokerBaseUrl,
                            cameraGranted = viewContext.isGranted(Manifest.permission.CAMERA),
                            microphoneGranted = viewContext.isGranted(Manifest.permission.RECORD_AUDIO),
                        )
                        if (grant.isEmpty()) request.deny() else request.grant(grant)
                        journal.record(
                            if (grant.size == requested.size && grant.isNotEmpty()) LogLevel.INFO else LogLevel.WARNING,
                            CameraShareDiagnostics.webPermission(
                                originAllowed = CameraSharePolicy.isBrokerOrigin(origin, brokerBaseUrl),
                                requested = requested,
                                granted = grant,
                            ),
                        )
                    }

                    override fun onPermissionRequestCanceled(request: PermissionRequest) {
                        journal.record(LogLevel.WARNING, CameraShareDiagnostics.webPermissionCanceled())
                    }

                    // Never forward the page console to logcat. Only errors/warnings reach the
                    // short journal, sanitized (no link, no key, no opaque token), and capped.
                    private var consoleLines = 0

                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        val level = consoleMessage.messageLevel()?.name ?: return true
                        if (level != "ERROR" && level != "WARNING") return true
                        if (consoleLines >= MAX_CONSOLE_LINES) return true
                        consoleLines++
                        journal.record(
                            CameraShareDiagnostics.consoleLevel(level),
                            CameraShareDiagnostics.console(level.lowercase(), consoleMessage.message().orEmpty()),
                        )
                        return true
                    }

                    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                        callback.invoke(origin, false, false)
                    }

                    override fun onShowFileChooser(
                        webView: WebView,
                        filePathCallback: ValueCallback<Array<android.net.Uri>>,
                        fileChooserParams: FileChooserParams,
                    ): Boolean = false
                }
                // The exact, complete link (query ?role=camera and fragment #k=) is handed to
                // loadUrl unchanged; the fragment stays client-side and is never sent or logged.
                if (CameraSharePolicy.isLoadableCameraUrl(cameraUrl, brokerBaseUrl)) loadUrl(cameraUrl)
            }
        },
        onRelease = { webView ->
            if (!deadRenderer[0]) {
                // Unload the page first so getUserMedia tracks stop, then free the renderer.
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.onPause()
            }
            webView.removeAllViews()
            webView.destroy()
        },
    )
}
