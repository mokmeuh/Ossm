package com.ossm.remote.remote

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ossm.remote.BuildConfig
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class RemoteSessionState(
    val active: Boolean = false,
    val connected: Boolean = false,
    val message: String = "Session inactive",
    val mediaStatus: String = "Média téléphone indisponible: le téléphone relaie les commandes seulement",
    val readiness: PhoneHostReadiness = PhoneHostReadiness(false, false, false),
    val shareUrl: String? = null,
    /** Camera-only invitation of the current session; null when the broker did not provide one. */
    val cameraUrl: String? = null,
    /** Fixed code of the last refused browser command (see [RemoteRefusalReason]); never a payload. */
    val lastRefusal: String? = null,
    /** 2.3.31: transport lost, automatic reconnection to the same link in progress (STOP already sent). */
    val reconnecting: Boolean = false,
    /** 2.3.31: current reconnection attempt (1..[RemoteReconnectPolicy.MAX_ATTEMPTS]), 0 when none. */
    val reconnectAttempt: Int = 0,
) {
    /** Redacted: capability URLs carry `#k=` secrets and must never be printed. */
    override fun toString(): String =
        "RemoteSessionState(active=$active, connected=$connected, reconnecting=$reconnecting, message=$message, readiness=$readiness, " +
            "shareUrl=${if (shareUrl != null) "<redacted>" else "null"}, cameraUrl=${if (cameraUrl != null) "<redacted>" else "null"}, lastRefusal=$lastRefusal)"
}

/**
 * Codes the broker accepts in a host `diagnostic` message. The broker schema
 * (`remote-cloud/src/protocol.ts`, `DIAGNOSTIC_CODES`) is a strict allowlist: an unknown code
 * is answered with `invalid_message`, which this client treats as a fatal broker error. These
 * are the `remote_*` codes of [RemoteRefusalReason]; keep them in sync with the broker.
 */
internal val BROKER_ACCEPTED_REFUSAL_CODES: Set<String> = setOf(
    "remote_lease_invalid",
    "remote_session_not_started",
    "remote_machine_not_ready",
    "remote_sequence_stale",
    "remote_live_position_rejected",
)

/** Strict three-key broker diagnostic for [reason], or null if the broker would reject its code. */
internal fun refusalDiagnosticPayload(
    reason: RemoteRefusalReason,
    brokerAcceptedCodes: Set<String> = BROKER_ACCEPTED_REFUSAL_CODES,
): String? = reason.code.takeIf { it in brokerAcceptedCodes }?.let { code ->
    JsonObject().apply {
        addProperty("type", "diagnostic")
        addProperty("severity", "warning")
        addProperty("code", code)
    }.toString()
}

/**
 * Host->controller message types the broker accepts beyond the existing ones. The broker schema
 * is a strict allowlist and answers an unknown type with `invalid_message` (fatal for this
 * client). The envelope message
 * `{"type":"limits","speedMax":0..1,"depthMin":0..1,"depthMax":0..1,"sensationMin":0..1,"sensationMax":0..1}`
 * (exactly these six keys; the broker accepts `sensationMax` since web v2.0.7 and
 * `sensationMin` since web v2.0.8, which must be deployed before this APK is installed)
 * is built by [limitsPayload] and is sent on every envelope change and when the controller
 * arrives. The sensation range is symmetric around the neutral 0.5.
 */
internal val BROKER_ACCEPTED_HOST_MESSAGE_TYPES: Set<String> = setOf("limits")

/** Strict six-key envelope message for [view], or null when the broker would reject its type. */
internal fun limitsPayload(
    view: RemoteSessionView,
    acceptedTypes: Set<String> = BROKER_ACCEPTED_HOST_MESSAGE_TYPES,
): String? {
    if ("limits" !in acceptedTypes || !view.active) return null
    return JsonObject().apply {
        addProperty("type", "limits")
        addProperty("speedMax", view.speedMax.coerceIn(0f, 1f))
        addProperty("depthMin", view.depthMin.coerceIn(0f, 1f))
        addProperty("depthMax", view.depthMax.coerceIn(0f, 1f))
        val sensationMin = view.sensationMin.coerceIn(0f, 1f)
        addProperty("sensationMin", sensationMin)
        addProperty("sensationMax", view.sensationMax.coerceIn(sensationMin, 1f))
    }.toString()
}

/**
 * 2.3.31: bounded backoff of the host WebSocket reconnection (1, 2, 4, 8 then 15 s, at most
 * [MAX_ATTEMPTS] attempts). Only transient transport losses are retried; revocation, expiry and
 * broker refusals end the session as before.
 */
object RemoteReconnectPolicy {
    const val MAX_ATTEMPTS = 20
    const val MAX_DELAY_MS = 15_000L

    /** Delay before attempt [attempt] (1-based), or null once the attempts are exhausted. */
    fun delayMs(attempt: Int): Long? {
        if (attempt < 1 || attempt > MAX_ATTEMPTS) return null
        if (attempt > 5) return MAX_DELAY_MS
        return minOf(1_000L shl (attempt - 1), MAX_DELAY_MS)
    }

    /** WebSocket closes that must NOT be retried (revoked or expired link, role refused). */
    fun isFatalClose(code: Int, reason: String): Boolean =
        reason == "revoked" || reason == "expired" || reason == "role unavailable" ||
            (code == 1008 && reason != "lease_lost")
}

@Singleton
class RemoteSessionClient internal constructor(
    private val safety: RemoteSessionSafetyAdapter,
    credentialStore: RemoteSessionCredentialStore,
    private val reconnectDelayMs: (Int) -> Long?,
) {
    @Inject constructor(
        safety: RemoteSessionSafetyAdapter,
        credentialStore: RemoteSessionCredentialStore,
    ) : this(safety, credentialStore, RemoteReconnectPolicy::delayMs)

    private val client = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
    private val heartbeatExecutor = ScheduledThreadPoolExecutor(1)
    private val _state = MutableStateFlow(RemoteSessionState())
    val state: StateFlow<RemoteSessionState> = _state.asStateFlow()
    private var socket: WebSocket? = null
    private var heartbeat: ScheduledFuture<*>? = null
    private var pendingLifecycleCall: Call? = null
    private var lifecycleOperation = 0L
    private var session: RemoteBrokerSession? = null
    private var sessionExpiresAt = 0L
    private val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var hostReadinessJob: Job? = null
    private var limitsJob: Job? = null
    private val commandGate = Any()
    private var commandGeneration = 0L
    private data class PendingCommand(val controllerId: String, val sequence: Long)
    private val pendingCommands = mutableSetOf<PendingCommand>()
    private val lifecycle = RemoteSessionLifecycle(credentialStore)
    private val refusalGate = Any()
    private var lastRefusalReason: RemoteRefusalReason? = null
    private var lastRefusalAtMillis = 0L
    // 2.3.31 reconnection state (guarded by the client lock).
    private var reconnectTarget: RemoteBrokerSession? = null
    private var reconnectExpiresAt = 0L
    private var reconnectAttempt = 0
    private var reconnectGeneration = 0L
    private var reconnectFuture: ScheduledFuture<*>? = null

    init {
        safety.refusalListener = ::onCommandRefused
    }

    /** Called on the command path (holds `commandGate`): must not take the client lock. */
    internal fun onCommandRefused(reason: RemoteRefusalReason) {
        val now = System.currentTimeMillis()
        val repeated = synchronized(refusalGate) {
            val same = reason == lastRefusalReason && now - lastRefusalAtMillis < REFUSAL_REPEAT_WINDOW_MS
            lastRefusalReason = reason
            lastRefusalAtMillis = now
            same
        }
        if (repeated) return
        _state.value = _state.value.copy(lastRefusal = reason.code)
        refusalDiagnosticPayload(reason)?.let { payload -> socket?.send(payload) }
    }

    @Synchronized fun start() {
        if (_state.value.active) return
        when (val stored = lifecycle.restore()) {
            StoredRemoteSession.Missing -> createSession()
            StoredRemoteSession.Unreadable -> {
                _state.value = RemoteSessionState(message = "Session existante illisible; régénérez le lien pour continuer")
            }
            is StoredRemoteSession.Available -> renewSession(stored.session)
        }
    }

    private fun createSession() {
        val brokerBaseUrl = BuildConfig.REMOTE_BROKER_BASE_URL.toHttpUrlOrNull()
        if (brokerBaseUrl == null) {
            _state.value = RemoteSessionState(message = "Création automatique indisponible: broker non configuré")
            return
        }
        _state.value = RemoteSessionState(active = true, message = "Création de la session distante…")
        val request = Request.Builder()
            .url(brokerBaseUrl.newBuilder().addPathSegments("api/sessions").build())
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val call = client.newCall(request)
        val operation = registerLifecycleCall(call)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                synchronized(this@RemoteSessionClient) {
                    if (!finishLifecycleCall(call, operation)) return
                    lost("Création de session impossible")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val created = response.use { current ->
                    current.takeIf(Response::isSuccessful)
                        ?.body
                        ?.string()
                        ?.let(RemoteBrokerSession::parseCreationResponse)
                }
                synchronized(this@RemoteSessionClient) {
                    if (!finishLifecycleCall(call, operation)) return
                    if (created == null) {
                        lost("Création de session refusée")
                        return
                    }
                    lifecycle.persist(created)
                    connect(created)
                }
            }
        })
    }

    private fun renewSession(existing: RemoteBrokerSession) {
        _state.value = RemoteSessionState(active = true, message = "Renouvellement de la session Internet…", shareUrl = existing.shareUrl, cameraUrl = existing.cameraUrl)
        val request = Request.Builder()
            .url(existing.sessionUrl)
            .header("Authorization", "Bearer ${existing.secret}")
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val call = client.newCall(request)
        val operation = registerLifecycleCall(call)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                synchronized(this@RemoteSessionClient) {
                    if (!finishLifecycleCall(call, operation)) return
                    lost("Renouvellement de session impossible")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.use { it.code }
                val successful = code in 200..299
                synchronized(this@RemoteSessionClient) {
                    if (!finishLifecycleCall(call, operation)) return
                    if (code in GONE_STATUS_CODES) {
                        lifecycle.clear()
                        createSession()
                        return
                    }
                    if (!successful) {
                        lost("Session distante refusée; régénérez le lien")
                        return
                    }
                    connect(existing)
                }
            }
        })
    }

    @Synchronized fun regenerate() {
        invalidateLifecycleCall()
        when (val stored = lifecycle.restore()) {
            StoredRemoteSession.Missing, StoredRemoteSession.Unreadable -> {
                lifecycle.clear()
                createSession()
            }
            is StoredRemoteSession.Available -> revokeThenCreate(stored.session)
        }
    }

    private fun revokeThenCreate(existing: RemoteBrokerSession) {
        stopSocket()
        // The old camera invitation is forgotten as soon as regeneration starts (cameraUrl = null):
        // an open camera share closes, and only the new session's camera link is ever offered.
        _state.value = RemoteSessionState(active = true, message = "Révocation du lien Internet actuel…", shareUrl = existing.shareUrl)
        val request = Request.Builder()
            .url(existing.sessionUrl)
            .header("Authorization", "Bearer ${existing.secret}")
            .delete()
            .build()
        val call = client.newCall(request)
        val operation = registerLifecycleCall(call)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                synchronized(this@RemoteSessionClient) {
                    if (!finishLifecycleCall(call, operation)) return
                    lost("Révocation impossible; le lien actuel est conservé")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.use { it.code }
                val successful = code in 200..299 || code in GONE_STATUS_CODES
                synchronized(this@RemoteSessionClient) {
                    if (!finishLifecycleCall(call, operation)) return
                    if (!successful) {
                        lost("Révocation refusée; le lien actuel est conservé")
                        return
                    }
                    lifecycle.clear()
                    createSession()
                }
            }
        })
    }

    private fun connect(parsed: RemoteBrokerSession) {
        stopSocket()
        safety.beginSession()
        session = parsed
        _state.value = RemoteSessionState(active = true, message = "Connexion de l'hôte au broker…", shareUrl = parsed.shareUrl, cameraUrl = parsed.cameraUrl)
        startHostReadinessUpdates()
        socket = client.newWebSocket(Request.Builder().url(parsed.websocketUrl).build(), listener)
    }

    /**
     * 2.3.31: transient transport loss (network cut, heartbeat timeout 4000...). SAFETY: the
     * STOP is emitted NOW (lease dropped, no motion resumes), the remote envelope is KEPT (the
     * Store session is not ended), and the same link is re-opened with a bounded backoff. The
     * browser must send a new command after the reconnection; nothing is replayed.
     */
    @Synchronized private fun transientLoss(message: String) {
        val target = session ?: reconnectTarget
        val expiresAt = if (sessionExpiresAt > 0L) sessionExpiresAt else reconnectExpiresAt
        if (target == null) {
            lost(message)
            return
        }
        if (expiresAt > 0L && System.currentTimeMillis() >= expiresAt) {
            lost("Session expirée")
            return
        }
        hostReadinessJob?.cancel()
        hostReadinessJob = null
        limitsJob?.cancel()
        limitsJob = null
        heartbeat?.cancel(false)
        heartbeat = null
        val staleSocket = socket
        socket = null
        session = null
        sessionExpiresAt = 0L
        invalidatePendingCommands()
        staleSocket?.close(1000, "transport_lost")
        safety.onTransportLost()
        reconnectTarget = target
        reconnectExpiresAt = expiresAt
        scheduleReconnect()
    }

    @Synchronized private fun scheduleReconnect() {
        val target = reconnectTarget ?: return
        val attempt = reconnectAttempt + 1
        val delay = reconnectDelayMs(attempt)
        if (delay == null) {
            lost("Reconnexion impossible; session arrêtée")
            return
        }
        reconnectAttempt = attempt
        reconnectGeneration += 1
        val generation = reconnectGeneration
        _state.value = _state.value.copy(
            active = true,
            connected = false,
            reconnecting = true,
            reconnectAttempt = attempt,
            message = "Reconnexion… (essai $attempt/${RemoteReconnectPolicy.MAX_ATTEMPTS})",
            shareUrl = target.shareUrl,
            cameraUrl = target.cameraUrl,
        )
        reconnectFuture?.cancel(false)
        reconnectFuture = heartbeatExecutor.schedule(Runnable { reconnectNow(generation) }, delay, TimeUnit.MILLISECONDS)
    }

    private fun reconnectNow(generation: Long) {
        synchronized(this) {
            if (generation != reconnectGeneration) return
            val target = reconnectTarget ?: return
            if (reconnectExpiresAt > 0L && System.currentTimeMillis() >= reconnectExpiresAt) {
                lost("Session expirée")
                return
            }
            reconnectFuture = null
            // Same link, same Store session (envelope kept): no beginSession, no motion replay.
            session = target
            startHostReadinessUpdates()
            socket = client.newWebSocket(Request.Builder().url(target.websocketUrl).build(), listener)
        }
    }

    @Synchronized private fun cancelReconnect() {
        reconnectGeneration += 1
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        reconnectTarget = null
        reconnectExpiresAt = 0L
        reconnectAttempt = 0
    }

    /** 404/410 while reconnecting: the session record is gone; same path as renewal (new link). */
    @Synchronized private fun sessionGoneWhileReconnecting() {
        stopSocket()
        lifecycle.clear()
        createSession()
    }

    @Synchronized fun stop() {
        invalidateLifecycleCall()
        stopSocket()
        safety.onSessionTimeout()
        _state.value = _state.value.copy(active = false, connected = false, reconnecting = false, reconnectAttempt = 0, message = "Session arrêtée")
    }

    @Synchronized private fun registerLifecycleCall(call: Call): Long {
        pendingLifecycleCall?.cancel()
        pendingLifecycleCall = call
        lifecycleOperation += 1
        return lifecycleOperation
    }

    private fun finishLifecycleCall(call: Call, operation: Long): Boolean {
        if (operation != lifecycleOperation || pendingLifecycleCall !== call) return false
        pendingLifecycleCall = null
        return true
    }

    private fun invalidateLifecycleCall() {
        lifecycleOperation += 1
        pendingLifecycleCall?.cancel()
        pendingLifecycleCall = null
    }

    fun onServiceDestroyed() {
        invalidateLifecycleCall()
        stopSocket()
        safety.onServiceDestroyed()
        _state.value = _state.value.copy(active = false, connected = false, reconnecting = false, reconnectAttempt = 0, message = "Session arrêtée")
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) {
                webSocket.close(1000, "superseded")
                return
            }
            val activeSession = session ?: run {
                webSocket.close(1008, "missing_session")
                return
            }
            webSocket.send(JsonObject().apply {
                addProperty("type", "hello")
                addProperty("role", "host")
                addProperty("secret", activeSession.secret)
            }.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) { if (webSocket === socket) handle(text) }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { closed(webSocket, code, reason) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed(webSocket, code, reason) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(this@RemoteSessionClient) {
                if (webSocket !== socket) return
                val code = response?.code
                when {
                    code != null && code in GONE_STATUS_CODES && reconnectTarget != null -> sessionGoneWhileReconnecting()
                    code != null && code in GONE_STATUS_CODES -> lost("Session expirée")
                    code == 401 || code == 403 -> lost("Session distante refusée; régénérez le lien")
                    else -> transientLoss("Transport perdu")
                }
            }
        }

        private fun closed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(this@RemoteSessionClient) {
                if (webSocket !== socket) return
                if (RemoteReconnectPolicy.isFatalClose(code, reason)) lost("Transport fermé") else transientLoss("Transport fermé")
            }
        }
    }

    @Synchronized private fun stopSocket() {
        cancelReconnect()
        invalidatePendingCommands()
        hostReadinessJob?.cancel()
        hostReadinessJob = null
        limitsJob?.cancel()
        limitsJob = null
        heartbeat?.cancel(false)
        heartbeat = null
        val staleSocket = socket
        socket = null
        session = null
        sessionExpiresAt = 0L
        staleSocket?.close(1000, "user_stop")
        safety.onTransportLost()
        safety.endSession()
    }

    @Synchronized private fun lost(message: String) {
        cancelReconnect()
        hostReadinessJob?.cancel()
        hostReadinessJob = null
        limitsJob?.cancel()
        limitsJob = null
        heartbeat?.cancel(false)
        heartbeat = null
        val staleSocket = socket
        socket = null
        session = null
        sessionExpiresAt = 0L
        invalidatePendingCommands()
        staleSocket?.close(1000, "transport_lost")
        safety.onTransportLost()
        safety.endSession()
        _state.value = _state.value.copy(active = false, connected = false, reconnecting = false, reconnectAttempt = 0, message = message)
    }

    private fun invalidatePendingCommands() {
        synchronized(commandGate) {
            commandGeneration += 1
            pendingCommands.clear()
        }
    }

    private fun handleLeaseLost(participantId: String) {
        synchronized(commandGate) {
            val activeControllerLost = safety.loseLease(participantId)
            val pendingControllerLost = pendingCommands.any { it.controllerId == participantId }
            if (activeControllerLost || pendingControllerLost) {
                commandGeneration += 1
                pendingCommands.clear()
            }
        }
    }

    private fun handle(text: String) = runCatching {
        val json = JsonParser.parseString(text).asJsonObject
        when (json.string("type")) {
            "ready" -> ready(json)
            "presence" -> presence(json)
            "lease_lost" -> handleLeaseLost(json.string("participantId"))
            "command" -> command(json)
            "emergency_stop" -> acceptEmergencyStop()
            "signal" -> Unit
            "error" -> brokerError(json.string("code"))
        }
    }

    /**
     * Broker refusals stay fatal (revoked/expired link, bad secret, protocol error). Only
     * "capacity" during a reconnection is transient: the broker has not dropped our old host
     * socket yet, so the attempt is retried with the backoff.
     */
    @Synchronized private fun brokerError(code: String) {
        if (code == "capacity" && reconnectTarget != null) transientLoss("Broker: $code") else lost("Broker: $code")
    }

    /** Sends the current envelope when the broker accepts `limits` messages. */
    private fun sendLimits() {
        if ("limits" !in BROKER_ACCEPTED_HOST_MESSAGE_TYPES) return
        limitsPayload(safety.sessionView.value)?.let { socket?.send(it) }
    }

    private fun ready(json: JsonObject) {
        if (json.string("role") != "host") return lost("Rôle broker invalide")
        sessionExpiresAt = json.long("expiresAt")
        if (sessionExpiresAt <= System.currentTimeMillis()) return lost("Session expirée")
        // Reconnected: the backoff restarts from 1 s; the lease was dropped, nothing resumes.
        val reconnected = synchronized(this) {
            val wasReconnecting = reconnectTarget != null
            reconnectTarget = null
            reconnectExpiresAt = 0L
            reconnectAttempt = 0
            wasReconnecting
        }
        _state.value = _state.value.copy(
            connected = true,
            reconnecting = false,
            reconnectAttempt = 0,
            message = if (reconnected) "Reconnecté; attente d'une nouvelle commande du contrôleur" else "Hôte authentifié; attente du contrôleur",
        )
        refreshHostReadiness()
        sendLimits()
        heartbeat?.cancel(false)
        heartbeat = heartbeatExecutor.scheduleAtFixedRate(
            { sendBrokerHeartbeat() },
            HEARTBEAT_INTERVAL_MS,
            HEARTBEAT_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun sendBrokerHeartbeat() {
        synchronized(this) {
            if (sessionExpiresAt > 0L && System.currentTimeMillis() >= sessionExpiresAt) {
                lost("Session expirée")
                return
            }
            socket?.send("{\"type\":\"heartbeat\"}")
        }
    }

    private fun presence(json: JsonObject) {
        val controllerOnline = json.bool("controllerOnline")
        if (!controllerOnline) safety.expireIfNeeded()
        refreshHostReadiness()
        if (controllerOnline) sendLimits()
        _state.value = _state.value.copy(
            message = if (controllerOnline) {
                "Hôte authentifié; contrôleur connecté"
            } else {
                "Hôte authentifié; attente du contrôleur"
            },
        )
    }

    private fun command(json: JsonObject) {
        val action = RemoteControlProtocol.parse(json.string("command")) ?: return
        val controllerId = json.string("from")
        val sequence = json.long("seq")
        refreshHostReadiness()
        val pendingCommand = PendingCommand(controllerId, sequence)
        val generation = synchronized(commandGate) {
            pendingCommands += pendingCommand
            commandGeneration
        }
        if (action.interruptsRemoteMotion()) {
            try {
                synchronized(commandGate) {
                    if (generation == commandGeneration) {
                        safety.grantLease(controllerId, sessionExpiresAt)
                        safety.acceptAction(controllerId, sequence, action) { commandGeneration += 1 }
                    }
                }
            } finally {
                synchronized(commandGate) { pendingCommands -= pendingCommand }
            }
            return
        }
        commandScope.launch {
            try {
                synchronized(commandGate) {
                    if (generation == commandGeneration) {
                        safety.grantLease(controllerId, sessionExpiresAt)
                        safety.acceptAction(controllerId, sequence, action)
                    }
                }
            } finally {
                synchronized(commandGate) { pendingCommands -= pendingCommand }
            }
        }
    }

    private fun acceptEmergencyStop() = synchronized(commandGate) {
        commandGeneration += 1
        safety.accept(RemoteSessionCommand.EmergencyStop())
    }

    private fun startHostReadinessUpdates() {
        hostReadinessJob?.cancel()
        hostReadinessJob = commandScope.launch {
            while (isActive) {
                refreshHostReadiness()
                kotlinx.coroutines.delay(HOST_READINESS_POLL_MS)
            }
        }
        if ("limits" in BROKER_ACCEPTED_HOST_MESSAGE_TYPES) {
            limitsJob?.cancel()
            // Emit on every envelope change (distinct strict envelope), not on remote* updates.
            limitsJob = commandScope.launch {
                safety.sessionView
                    .map { listOf(it.speedMax, it.depthMin, it.depthMax, it.sensationMax) to it.active }
                    .distinctUntilChanged()
                    .collect { sendLimits() }
            }
        }
    }

    private fun refreshHostReadiness() {
        val readiness = safety.hostReadiness()
        safety.updateReadiness(readiness)
        _state.value = _state.value.copy(readiness = readiness)
    }

    private fun JsonObject.string(name: String) = get(name).asString
    private fun JsonObject.long(name: String) = get(name).asLong
    private fun JsonObject.bool(name: String) = get(name).asBoolean

    private companion object {
        const val HEARTBEAT_INTERVAL_MS = 500L
        const val HOST_READINESS_POLL_MS = 250L
        /** The same refusal reason is reported at most once per window to avoid flooding. */
        const val REFUSAL_REPEAT_WINDOW_MS = 2_000L
        /** Broker answers 404/410 once an expired session record is gone: nothing is left to renew or revoke. */
        val GONE_STATUS_CODES = setOf(404, 410)
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private fun RemoteControlAction.interruptsRemoteMotion(): Boolean = when (this) {
    RemoteControlAction.Stop,
    RemoteControlAction.EndLiveTouch,
    RemoteControlAction.EmergencyStop -> true
    is RemoteControlAction.SetSpeed,
    is RemoteControlAction.SetDepth,
    is RemoteControlAction.SetSensation,
    is RemoteControlAction.SelectPattern,
    is RemoteControlAction.SetLivePosition,
    RemoteControlAction.Home,
    RemoteControlAction.Pause,
    RemoteControlAction.Resume -> false
}

internal data class RemoteBrokerSession(
    val websocketUrl: String,
    val sessionUrl: String,
    val secret: String,
    val shareUrl: String,
    /**
     * Camera-only invitation (`/s/{id}?role=camera#k=...`) from broker v2.0.7+, or null when the
     * broker predates it or the session was stored before 2.3.15. Its key only admits the camera
     * role; it is a secret like [shareUrl] and must never reach a log or a diagnostic.
     */
    val cameraUrl: String? = null,
) {
    /** Redacted: the default data-class form would print both capability URLs. */
    override fun toString(): String = "RemoteBrokerSession(sessionUrl=$sessionUrl, camera=${cameraUrl != null})"

    /**
     * Value encrypted by [RemoteSessionCredentialPersistence]. Without a camera link it stays the
     * bare share URL (pre-2.3.15 format); with one it is the broker creation JSON shape.
     */
    fun toStoredValue(): String {
        val camera = cameraUrl ?: return shareUrl
        return JsonObject().apply {
            addProperty("url", shareUrl)
            addProperty("cameraUrl", camera)
        }.toString()
    }

    companion object {
        private val sessionIdPattern = Regex("[A-Za-z0-9_-]{8,64}")
        private val secretPattern = Regex("[A-Za-z0-9_-]{24,256}")

        fun parse(value: String): RemoteBrokerSession? {
            val url = value.toHttpUrlOrNull() ?: return null
            if (url.pathSegments.size != 2 || url.pathSegments[0] != "s") return null
            val sessionId = url.pathSegments[1].takeIf(sessionIdPattern::matches) ?: return null
            val secret = url.fragment?.takeIf { it.startsWith("k=") }?.substringAfter("k=")?.substringBefore('&')?.takeIf(secretPattern::matches) ?: return null
            val httpSessionUrl = url.newBuilder().encodedPath("/api/session/$sessionId").query(null).fragment(null).build().toString()
            val httpWebsocketUrl = url.newBuilder().encodedPath("/api/session/$sessionId/ws").query(null).fragment(null).build().toString()
            val websocketUrl = when (url.scheme) {
                "https" -> httpWebsocketUrl.replaceFirst("https://", "wss://")
                "http" -> httpWebsocketUrl.replaceFirst("http://", "ws://")
                else -> return null
            }
            return RemoteBrokerSession(websocketUrl, httpSessionUrl, secret, value)
        }

        fun parseCreationResponse(value: String): RemoteBrokerSession? = runCatching {
            val json = JsonParser.parseString(value).asJsonObject
            val main = json.get("url")?.asString?.let(::parse) ?: return@runCatching null
            val camera = json.get("cameraUrl")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
            main.copy(cameraUrl = camera?.takeIf { cameraUrlMatches(main, it) })
        }.getOrNull()

        /** Restores either the bare pre-2.3.15 share URL or the JSON form of [toStoredValue]. */
        fun parseStored(value: String): RemoteBrokerSession? =
            parse(value) ?: value.takeIf { it.trimStart().startsWith("{") }?.let(::parseCreationResponse)

        /**
         * A camera invitation is accepted only for the same broker origin and session id as the
         * host link, with exactly `?role=camera` and its own key (never the host key).
         */
        internal fun cameraUrlMatches(main: RemoteBrokerSession, candidate: String): Boolean {
            val host = main.shareUrl.toHttpUrlOrNull() ?: return false
            val camera = candidate.toHttpUrlOrNull() ?: return false
            if (camera.scheme != host.scheme || camera.host != host.host || camera.port != host.port) return false
            if (camera.pathSegments != host.pathSegments) return false
            if (camera.queryParameterNames != setOf("role") || camera.queryParameter("role") != "camera") return false
            val key = camera.fragment?.takeIf { it.startsWith("k=") }?.substringAfter("k=") ?: return false
            return secretPattern.matches(key) && key != main.secret
        }
    }
}
