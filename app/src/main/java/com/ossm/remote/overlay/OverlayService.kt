package com.ossm.remote.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ossm.remote.MainActivity
import com.ossm.remote.R
import com.ossm.remote.data.repository.OverlayPreferencesRepository
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.model.isConnected
import com.ossm.remote.session.ControlSessionStore
import com.ossm.remote.ui.theme.OssmRemoteTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Service au premier plan affichant l'overlay flottant (bulle → panneau de contrôle).
 * Utilise un `WindowManager.LayoutParams` de type TYPE_APPLICATION_OVERLAY avec un
 * ComposeView. L'UI est pilotée par [ControlSessionStore] : tout ce qui s'affiche
 * (vitesse, profondeur, état) vient de son `uiState`, et chaque écriture passe par
 * ses fonctions pour que l'écran de contrôle reste synchronisé.
 */
@AndroidEntryPoint
class OverlayService : Service() {

    @Inject lateinit var controlSessionStore: ControlSessionStore
    @Inject lateinit var overlayPreferencesRepository: OverlayPreferencesRepository

    // Compose exige un LifecycleOwner + ViewModelStoreOwner dans l'arbre de vues ;
    // un Service n'en fournit pas et Hilt interdit de les implémenter directement
    // (base @AndroidEntryPoint = extends Service pur).
    private val composeOwner = OverlayComposeOwner()

    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private lateinit var params: WindowManager.LayoutParams

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // État de l'UI de l'overlay (lu par la composition, écrit sur le thread main).
    private var expanded by mutableStateOf(false)
    private var dragging by mutableStateOf(false)
    private var transparencyPercent by mutableStateOf(DEFAULT_TRANSPARENCY_PERCENT)

    private var viewAttached = false
    private var isStopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        composeOwner.onCreate()
        isRunning = true
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW refusée, overlay non démarré")
            stopSelf()
            return
        }
        createNotificationChannel()
        startAsForeground()
        setupOverlay()
        observePreferences()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isStopped) return START_NOT_STICKY
        composeOwner.onStart()
        composeOwner.onResume()
        // Le service est arrêté via le toggle de l'écran Profils (stopService) ou ici.
        if (intent?.action == ACTION_STOP) {
            safeStopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        safeStopSelf()
        isRunning = false
        composeOwner.onDestroy()
        serviceScope.cancel()
        if (viewAttached) {
            viewAttached = false
            try {
                windowManager.removeView(composeView)
            } catch (e: Exception) {
                Log.w(TAG, "removeView : vue déjà détachée", e)
            }
        }
        super.onDestroy()
    }

    // ---- Mise en place de la fenêtre overlay ----

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(composeOwner)
            setViewTreeViewModelStoreOwner(composeOwner)
            setViewTreeSavedStateRegistryOwner(composeOwner)
            setContent {
                OssmRemoteTheme {
                    OverlayContent()
                }
            }
        }
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = DEFAULT_BUBBLE_X
            y = DEFAULT_BUBBLE_Y
        }
        try {
            windowManager.addView(composeView, params)
            viewAttached = true
        } catch (e: Exception) {
            Log.w(TAG, "addView impossible", e)
            stopSelf()
        }
    }

    private fun observePreferences() {
        // Position persistée : appliquée dès lecture (et si l'utilisateur ne glisse pas).
        serviceScope.launch {
            combine(
                overlayPreferencesRepository.bubbleX,
                overlayPreferencesRepository.bubbleY
            ) { x, y -> x to y }.collect { (x, y) ->
                if (!dragging) {
                    params.x = x
                    params.y = y
                    updateWindow()
                }
            }
        }
        // Transparence persistée : reflétée en direct sur la bulle/le panneau.
        serviceScope.launch {
            overlayPreferencesRepository.transparencyPercent.collect { percent ->
                transparencyPercent = percent
            }
        }
    }

    @Composable
    private fun OverlayContent() {
        val uiState by controlSessionStore.uiState.collectAsState()
        val connectionState by controlSessionStore.connectionState.collectAsState()
        OverlayRoot(
            state = OverlayViewState(
                speed = uiState.speed,
                depthMin = uiState.depthMin,
                depthMax = uiState.depthMax,
                isRunning = uiState.isRunning,
                isPaused = uiState.isPaused,
                patternName = uiState.activePattern?.name.orEmpty(),
                isConnected = connectionState.isConnected(),
                hasError = connectionState is BleConnectionState.Error ||
                    connectionState is BleConnectionState.EmergencyStop,
                hasPendingManualChange = uiState.pendingManualChange != null
            ),
            transparencyPercent = transparencyPercent,
            expanded = expanded,
            dragging = dragging,
            onBubbleTap = ::expandPanel,
            onBubbleDragStart = { dragging = true; setFocusable(true) },
            onBubbleDragDelta = { delta ->
                params.x = (params.x + delta.x).roundToInt()
                params.y = (params.y + delta.y).roundToInt()
                updateWindow()
            },
            onBubbleDragEnd = ::finishDrag,
            onCollapse = ::collapsePanel,
            onOverlayClose = ::closeOverlay,
            onEmergencyStop = { controlSessionStore.emergencyStop() },
            onSpeedLive = { controlSessionStore.setSpeedLive(it) },
            onSpeedCommit = { controlSessionStore.requestSpeedChange(it) },
            onDepthLive = { min, max -> controlSessionStore.setDepthLive(min, max) },
            onDepthCommit = { min, max -> controlSessionStore.requestDepthRangeChange(min, max) }
        )
    }

    // ---- Déplacement / ancrage de la bulle ----

    /** Ferme l'overlay seul : arrête le service, la machine n'est pas arrêtée. */
    private fun closeOverlay() {
        // Fermeture manuelle dans cette session : l'auto-start à la sortie d'app ne doit
        // pas relancer l'overlay. Le drapeau est réinitialisé quand l'app revient au premier
        // plan (MainActivity.onStart). Redondant avec overlay_enabled=false ci-dessous pour
        // couvrir la course d'écriture async du DataStore.
        manuallyClosed = true
        // Garde le toggle de l'écran Profils cohérent : l'overlay n'est plus actif.
        serviceScope.launch {
            overlayPreferencesRepository.setOverlayEnabled(false)
        }
        // Émet ACTION_STOP : le service se stoppe proprement (onDestroy retire la fenêtre).
        try {
            startService(Intent(this, OverlayService::class.java).setAction(ACTION_STOP))
        } catch (e: Exception) {
            Log.w(TAG, "closeOverlay : impossible d'émettre ACTION_STOP", e)
        }
    }

    private fun safeStopSelf() {
        if (isStopped) return
        isStopped = true
        try {
            stopSelf()
        } catch (e: Exception) {
            Log.w(TAG, "stopSelf déjà en cours", e)
        }
    }

    private fun expandPanel() {
        expanded = true
        // Fenêtre pleine largeur : le panneau (fillMaxWidth + marge) occupe presque tout l'écran.
        // x/y gardent la position de la bulle : à la fermeture la fenêtre repasse en WRAP_CONTENT
        // à l'endroit exact où la bulle était.
        params.width = WindowManager.LayoutParams.MATCH_PARENT
        updateWindow()
    }

    private fun collapsePanel() {
        expanded = false
        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        updateWindow()
    }

    private fun finishDrag() {
        dragging = false
        setFocusable(false)
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val marginPx = dp(MARGIN_DP)
        val bubblePx = dp(OVERLAY_BUBBLE_TOUCH_SIZE_DP.toFloat())
        val targetX = if (params.x + bubblePx / 2 < screenWidth / 2) {
            marginPx
        } else {
            (screenWidth - bubblePx - marginPx).coerceAtLeast(marginPx)
        }
        val targetY = params.y.coerceIn(
            marginPx,
            (screenHeight - bubblePx - marginPx).coerceAtLeast(marginPx)
        )
        animateWindowTo(targetX, targetY)
        serviceScope.launch {
            overlayPreferencesRepository.setBubblePosition(targetX, targetY)
        }
    }

    /** Animation courte vers le bord (accrochage) puis persistance. */
    private fun animateWindowTo(targetX: Int, targetY: Int) {
        serviceScope.launch {
            val startX = params.x
            val startY = params.y
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(durationMillis = SNAP_ANIMATION_MS)
            ) { value, _ ->
                params.x = (startX + (targetX - startX) * value).roundToInt()
                params.y = (startY + (targetY - startY) * value).roundToInt()
                updateWindow()
            }
            params.x = targetX
            params.y = targetY
            updateWindow()
        }
    }

    /** Pendant le glisser on enlève FLAG_NOT_FOCUSABLE pour garder les événements tactiles. */
    private fun setFocusable(focusable: Boolean) {
        params.flags = if (focusable) {
            0
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        updateWindow()
    }

    private fun updateWindow() {
        if (!viewAttached) return
        try {
            windowManager.updateViewLayout(composeView, params)
        } catch (e: Exception) {
            Log.w(TAG, "updateViewLayout : fenêtre absente", e)
        }
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    // ---- Notification de service au premier plan ----

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.overlay_notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_STOP = "com.ossm.remote.overlay.STOP"

        /** Vrai tant que le service tourne (drapeau processus pour l'auto-start). */
        @Volatile var isRunning = false

        /**
         * Vrai quand l'utilisateur a fermé l'overlay manuellement dans cette session :
         * l'auto-start à la sortie d'app ne doit pas le relancer. Réinitialisé quand
         * l'app revient au premier plan (MainActivity.onStart).
         */
        @Volatile var manuallyClosed = false

        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 70
        private const val DEFAULT_BUBBLE_X = 24
        private const val DEFAULT_BUBBLE_Y = 160
        private const val DEFAULT_TRANSPARENCY_PERCENT = 100
        private const val MARGIN_DP = 8f
        private const val SNAP_ANIMATION_MS = 260
    }
}

private class OverlayComposeOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    fun onCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onStart() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    fun onResume() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }
}
