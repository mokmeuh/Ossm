package com.ossm.remote.updater

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import com.ossm.remote.R
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Phone-to-phone APK transfer, protocol 2 (2.3.32). Three ways to reach the receiver:
 * - QR code shown by the receiver (addresses, port and its ephemeral public key);
 * - manual address (IP or IP:port), no code;
 * - automatic NSD/mDNS discovery `_ossm-ota._tcp.` (TXT carries the public key, no secret).
 * All three run the same handshake ([LocalOtaSenderClient] / [LocalOtaReceiverServer]):
 * ECDH P-256, both phones show the same 2-digit number, the receiver answers Oui/Non,
 * and the upload is authenticated by an HMAC derived from the shared secret. The
 * receiver still validates package, signer and versionCode, and Android always asks
 * the user to confirm the installation. Nothing sensitive is logged.
 */
internal class LocalOtaTransfer(private val context: Context) {
    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val multicastLock = LocalOtaMulticastLock(
        context.applicationContext.getSystemService(WifiManager::class.java)
            ?.createMulticastLock("ossm-local-ota"),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()
    private val httpClient = OkHttpClient.Builder().callTimeout(0, TimeUnit.MILLISECONDS).build()
    private val _state = MutableStateFlow(LocalOtaState())
    val state: StateFlow<LocalOtaState> = _state.asStateFlow()

    private var receiverSocket: ServerSocket? = null
    private var receiverJob: Job? = null
    private var receiverServer: LocalOtaReceiverServer? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var discoveryTimeoutJob: Job? = null
    private var senderJob: Job? = null
    @Volatile private var senderClient: LocalOtaSenderClient? = null

    private fun text(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun setMessage(message: String, tone: LocalOtaMessageTone) =
        _state.update { it.copy(message = message, tone = tone) }

    // ---------------------------------------------------------------- receiver

    fun startReceiver() {
        if (receiverJob?.isActive == true) return
        receiverJob = scope.launch {
            var socket: ServerSocket? = null
            try {
                multicastLock.acquire(LocalOtaMulticastUser.RECEIVER)
                val server = LocalOtaReceiverServer(LocalOtaCrypto.newKeyPair(random), receiverHost, random)
                val bound = bindReceiverSocket()
                socket = bound
                receiverSocket = bound
                receiverServer = server
                registerReceiverService(bound.localPort, server.publicKey)
                _state.update {
                    it.copy(
                        receiverActive = true,
                        receiverInfo = receiverInfo(bound.localPort, server.publicKey),
                        incoming = null,
                        message = text(R.string.ota_msg_receiver_ready),
                        tone = LocalOtaMessageTone.Info,
                    )
                }
                launch { refreshReceiverInfoLoop(bound, server.publicKey) }
                while (isActive && !bound.isClosed) {
                    val client = bound.accept()
                    launch { runCatching { server.handle(client) } }
                }
            } catch (error: Exception) {
                // A socket closed by stopReceiver() is a normal stop, not an error.
                val stoppedByUser = socket != null && receiverSocket !== socket
                if (error !is CancellationException && !stoppedByUser) {
                    runCatching { socket?.close() }
                    receiverSocket = null
                    receiverServer?.cancelAll()
                    receiverServer = null
                    registrationListener?.let { runCatching { nsdManager.unregisterService(it) } }
                    registrationListener = null
                    _state.update {
                        it.copy(
                            receiverActive = false,
                            receiverInfo = null,
                            incoming = null,
                            message = text(R.string.ota_msg_receiver_unavailable),
                            tone = LocalOtaMessageTone.Error,
                        )
                    }
                }
            } finally {
                multicastLock.release(LocalOtaMulticastUser.RECEIVER)
            }
        }
    }

    /** Oui / Non on the receiver for the number shown. */
    fun answerIncoming(accepted: Boolean) {
        val request = _state.value.incoming ?: return
        receiverServer?.decide(request.sessionId, accepted)
    }

    private val receiverHost = object : LocalOtaReceiverHost {
        override val packageName: String get() = context.packageName
        override val deviceName: String get() = LocalOtaProtocol.safeModel()
        override fun installedVersionCode(): Long = installedLocalOtaVersion(context).first
        override fun installedVersionName(): String = installedLocalOtaVersion(context).second

        override fun onConfirmationRequested(request: LocalOtaIncomingRequest) {
            _state.update { it.copy(incoming = request, message = "") }
        }

        override fun onConfirmationClosed(sessionId: String, decision: LocalOtaDecision) {
            val (message, tone) = when (decision) {
                LocalOtaDecision.Accepted -> text(R.string.ota_msg_receiving) to LocalOtaMessageTone.Info
                LocalOtaDecision.Declined -> text(R.string.ota_msg_incoming_declined) to LocalOtaMessageTone.Info
                LocalOtaDecision.TimedOut -> text(R.string.ota_msg_incoming_timeout) to LocalOtaMessageTone.Error
                LocalOtaDecision.Disconnected -> text(R.string.ota_msg_incoming_cancelled) to LocalOtaMessageTone.Error
                LocalOtaDecision.Cancelled -> text(R.string.ota_msg_incoming_cancelled) to LocalOtaMessageTone.Info
            }
            _state.update {
                if (it.incoming?.sessionId == sessionId) it.copy(incoming = null, message = message, tone = tone) else it
            }
        }

        override fun newStagingFile(): File =
            File(context.cacheDir, "updates/received-${UUID.randomUUID()}.apk.part").also { it.parentFile?.mkdirs() }

        override fun onApkReceived(file: File): LocalOtaInstallResult {
            val fileCheck = validateLocalOtaApkFile(context, file)
            if (fileCheck != null) {
                val message = when (fileCheck) {
                    LocalOtaFileCheck.NotAnApk -> text(R.string.ota_msg_not_apk)
                    is LocalOtaFileCheck.Rejected -> when (fileCheck.reason) {
                        LocalOtaRejection.WrongPackage -> text(R.string.ota_msg_wrong_package_apk)
                        LocalOtaRejection.AlreadyUpToDate -> text(R.string.ota_msg_up_to_date)
                        LocalOtaRejection.WrongSigner -> text(R.string.ota_msg_wrong_signer)
                    }
                }
                val status = if (fileCheck == LocalOtaFileCheck.Rejected(LocalOtaRejection.AlreadyUpToDate)) 409 else 422
                setMessage(message, LocalOtaMessageTone.Error)
                return LocalOtaInstallResult(status, message)
            }
            val destination = file.resolveSibling(file.name.removeSuffix(".part"))
            check(file.renameTo(destination)) { "rename failed" }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destination)
            // Android's package installer always asks the user to confirm.
            val installer = createApkInstallIntent(uri)
            setMessage(text(R.string.ota_msg_received), LocalOtaMessageTone.Success)
            scope.launch(Dispatchers.Main) { context.startActivity(installer) }
            return LocalOtaInstallResult(200, "OK")
        }

        override fun onUploadRejected(reason: String) {
            setMessage(text(R.string.ota_msg_integrity_failed), LocalOtaMessageTone.Error)
        }
    }

    /** Binds on 0.0.0.0 (all interfaces, Wi-Fi and Tailscale tun) on the preferred port, else an ephemeral one. */
    private fun bindReceiverSocket(): ServerSocket {
        val any = InetAddress.getByName("0.0.0.0")
        return runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(any, LocalOtaProtocol.PREFERRED_PORT), LocalOtaProtocol.SOCKET_BACKLOG)
            }
        }.getOrElse {
            ServerSocket(0, LocalOtaProtocol.SOCKET_BACKLOG, any)
        }
    }

    private suspend fun refreshReceiverInfoLoop(socket: ServerSocket, publicKey: ByteArray) {
        while (!socket.isClosed) {
            delay(RECEIVER_REFRESH_MS)
            val info = receiverInfo(socket.localPort, publicKey)
            if (!socket.isClosed && _state.value.receiverInfo != info) {
                _state.update { if (it.receiverActive) it.copy(receiverInfo = info) else it }
            }
        }
    }

    private fun receiverInfo(port: Int, publicKey: ByteArray): LocalOtaReceiverInfo {
        val addresses = selectLocalOtaAddresses(networkAddresses())
        val labels = addresses.map { address ->
            val kind = text(
                when (address.kind) {
                    LocalOtaAddressKind.WiFi -> R.string.update_local_ota_kind_wifi
                    LocalOtaAddressKind.Tailscale -> R.string.update_local_ota_kind_tailscale
                },
            )
            "${address.address}:$port · $kind"
        }
        val uri = buildLocalOtaQrUri(
            LocalOtaQrPayload(
                hosts = addresses.map { it.address },
                port = port,
                receiverPublicKey = publicKey,
                versionCode = installedLocalOtaVersion(context).first,
                deviceName = LocalOtaProtocol.safeModel(),
                secret = receiverServer?.qrSecret,
            ),
        )
        return LocalOtaReceiverInfo(labels, uri)
    }

    private fun networkAddresses(): List<Pair<String, InetAddress>> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp }.getOrDefault(false) }
            .flatMap { networkInterface ->
                networkInterface.inetAddresses.toList().map { networkInterface.name to it }
            }
    }.getOrDefault(emptyList())

    fun stopReceiver() {
        runCatching { receiverSocket?.close() }
        receiverSocket = null
        receiverJob?.cancel()
        receiverJob = null
        receiverServer?.cancelAll()
        receiverServer = null
        registrationListener?.let { runCatching { nsdManager.unregisterService(it) } }
        registrationListener = null
        _state.update {
            it.copy(
                receiverActive = false,
                receiverInfo = null,
                incoming = null,
                message = text(R.string.ota_msg_receiver_stopped),
                tone = LocalOtaMessageTone.Info,
            )
        }
    }

    private fun registerReceiverService(port: Int, publicKey: ByteArray) {
        val (versionCode, versionName) = installedLocalOtaVersion(context)
        val service = NsdServiceInfo().apply {
            serviceName = "OSSM-OTA-${LocalOtaProtocol.safeModel()}-${ownServiceTag()}"
            serviceType = LocalOtaProtocol.SERVICE_TYPE
            this.port = port
            setAttribute(LocalOtaProtocol.TXT_PACKAGE, context.packageName)
            setAttribute(LocalOtaProtocol.TXT_VERSION_CODE, versionCode.toString())
            setAttribute(LocalOtaProtocol.TXT_VERSION_NAME, versionName.take(32))
            setAttribute(LocalOtaProtocol.TXT_DEVICE, LocalOtaProtocol.safeModel())
            setAttribute(LocalOtaProtocol.TXT_INSTANCE, LocalOtaProtocol.INSTANCE_ID)
            setAttribute(LocalOtaProtocol.TXT_PROTOCOL, LocalOtaProtocol.PROTOCOL_VERSION.toString())
            // Public key only (no secret): the 2-digit comparison authenticates the pairing.
            setAttribute(LocalOtaProtocol.TXT_PUBLIC_KEY, LocalOtaCrypto.b64(publicKey))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                // QR code and manual address keep working without mDNS.
                registrationListener = null
                setMessage(text(R.string.ota_msg_publish_refused, errorCode), LocalOtaMessageTone.Error)
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        nsdManager.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun ownServiceTag(): String = LocalOtaProtocol.INSTANCE_ID.take(8)

    // ------------------------------------------------------------------ sender

    /** Manual path: an address typed by the user, no code (the number comparison authenticates). */
    fun sendToAddress(address: String) {
        val target = parseLocalOtaAddress(address)
            ?: return setMessage(text(R.string.ota_msg_invalid_address), LocalOtaMessageTone.Error)
        startHandshake(listOf(target), expectedKey = null)
    }

    /** QR path: tries each address in order and pins the receiver public key. */
    fun sendFromQr(contents: String?) {
        val payload = parseLocalOtaQrUri(contents)
            ?: return setMessage(text(R.string.ota_msg_invalid_qr), LocalOtaMessageTone.Error)
        startHandshake(payload.targets, expectedKey = payload.receiverPublicKey, qrSecret = payload.secret)
    }

    private fun startHandshake(targets: List<LocalOtaTarget>, expectedKey: ByteArray?, qrSecret: ByteArray? = null) {
        if (senderJob?.isActive == true || _state.value.senderActive) return
        _state.update {
            it.copy(
                senderActive = true,
                pairing = null,
                message = text(R.string.ota_msg_checking, targets.joinToString(", ") { target -> target.label }),
                tone = LocalOtaMessageTone.Info,
            )
        }
        senderJob = scope.launch { runHandshake(targets, expectedKey, qrSecret) }
    }

    private fun runHandshake(targets: List<LocalOtaTarget>, expectedKey: ByteArray?, qrSecret: ByteArray? = null) {
        val (ownCode, ownName) = installedLocalOtaVersion(context)
        val client = LocalOtaSenderClient(
            httpClient,
            LocalOtaOwnVersion(context.packageName, ownCode, ownName, LocalOtaProtocol.safeModel()),
            random,
        )
        senderClient = client
        val session = when (val hello = client.hello(targets, expectedKey)) {
            is LocalOtaHelloOutcome.Ready -> hello.session
            is LocalOtaHelloOutcome.Legacy -> return finishSender(text(R.string.ota_msg_peer_legacy))
            is LocalOtaHelloOutcome.PeerUpToDate -> return finishSender(
                text(R.string.ota_msg_peer_up_to_date, hello.versionName ?: "?"),
                tone = LocalOtaMessageTone.Info,
            )
            LocalOtaHelloOutcome.Busy -> return finishSender(text(R.string.ota_msg_busy))
            LocalOtaHelloOutcome.WrongPackage -> return finishSender(text(R.string.ota_msg_wrong_package))
            LocalOtaHelloOutcome.KeyMismatch -> return finishSender(text(R.string.ota_msg_key_mismatch))
            is LocalOtaHelloOutcome.Http -> return finishSender(text(R.string.ota_msg_refused_http, hello.status))
            is LocalOtaHelloOutcome.Unreachable -> {
                if (senderClient !== client) return
                return finishSender(text(R.string.ota_msg_unreachable, targets.joinToString(", ") { it.label }))
            }
        }
        val label = listOfNotNull(session.peerName, session.target.label).joinToString(" · ")
        _state.update {
            it.copy(
                peerName = label,
                // Scanned QR = already authenticated: no number to compare.
                pairing = if (qrSecret != null) null
                else LocalOtaPairingPrompt(session.number, label, ownName, session.peerVersionName),
                message = text(if (qrSecret != null) R.string.ota_msg_sending else R.string.ota_msg_waiting_confirmation, ownName),
                tone = LocalOtaMessageTone.Info,
            )
        }
        when (val confirmation = client.awaitConfirmation(session, qrSecret)) {
            LocalOtaConfirmOutcome.Accepted -> Unit
            LocalOtaConfirmOutcome.Declined -> return finishSender(text(R.string.ota_msg_declined), LocalOtaMessageTone.Info)
            LocalOtaConfirmOutcome.TimedOut -> return finishSender(text(R.string.ota_msg_confirm_timeout))
            is LocalOtaConfirmOutcome.Failed -> {
                if (senderClient !== client) return
                return finishSender(text(R.string.ota_msg_confirm_failed))
            }
        }
        _state.update {
            it.copy(pairing = null, message = text(R.string.ota_msg_sending, ownName), tone = LocalOtaMessageTone.Info)
        }
        when (val upload = client.upload(session, File(context.applicationInfo.sourceDir))) {
            LocalOtaUploadOutcome.Sent -> finishSender(text(R.string.ota_msg_sent), LocalOtaMessageTone.Success)
            LocalOtaUploadOutcome.PeerUpToDate -> finishSender(text(R.string.ota_msg_peer_already_current), LocalOtaMessageTone.Info)
            LocalOtaUploadOutcome.MacRejected -> finishSender(text(R.string.ota_msg_mac_rejected))
            LocalOtaUploadOutcome.SessionRejected -> finishSender(text(R.string.ota_msg_session_rejected))
            is LocalOtaUploadOutcome.Refused -> finishSender(
                listOf(text(R.string.ota_msg_refused_http, upload.status), upload.message)
                    .filter(String::isNotBlank)
                    .joinToString(" "),
            )
            LocalOtaUploadOutcome.Unreachable -> if (senderClient === client) {
                finishSender(text(R.string.ota_msg_unreachable, session.target.label))
            }
        }
    }

    /** "Annuler" on the sender while waiting: closes the connection, which the receiver treats as Non. */
    fun cancelPairing() {
        senderClient?.cancel()
        senderClient = null
        discoveryListener?.let(::stopDiscovery)
        discoveryTimeoutJob?.cancel()
        senderJob?.cancel()
        finishSender(text(R.string.ota_msg_cancelled), tone = LocalOtaMessageTone.Info)
    }

    fun sendInstalledVersion() {
        if (senderJob?.isActive == true || _state.value.senderActive) return
        senderJob = scope.launch {
            try {
                multicastLock.acquire(LocalOtaMulticastUser.SENDER)
                _state.update {
                    it.copy(
                        senderActive = true,
                        suggestManual = false,
                        pairing = null,
                        message = text(R.string.ota_msg_searching),
                        tone = LocalOtaMessageTone.Info,
                    )
                }
                discoverReceiver()
            } catch (error: Exception) {
                if (error !is CancellationException) {
                    finishSender(text(R.string.ota_msg_discovery_unavailable, -1), suggestManual = true)
                }
            }
        }
    }

    private fun discoverReceiver() {
        val chosen = AtomicBoolean(false)
        val upToDatePeer = arrayOfNulls<String>(1)
        val legacyPeer = AtomicBoolean(false)
        val queue = ArrayDeque<NsdServiceInfo>()
        val resolving = AtomicBoolean(false)
        lateinit var listener: NsdManager.DiscoveryListener

        fun resolveNext() {
            if (chosen.get()) return
            val next = synchronized(queue) { queue.pollFirst() } ?: run {
                resolving.set(false)
                return
            }
            nsdManager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                        synchronized(queue) { queue.addLast(next) }
                        scope.launch {
                            delay(RESOLVE_RETRY_MS)
                            resolveNext()
                        }
                    } else {
                        resolveNext()
                    }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val attributes = info.attributes.mapValues { (_, value) -> value?.toString(Charsets.UTF_8).orEmpty() }
                    val peer = localOtaPeerFromTxt(attributes)
                    val host = info.host?.hostAddress
                    if (peer == null || host == null ||
                        isOwnLocalOtaReceiver(peer, LocalOtaProtocol.INSTANCE_ID) ||
                        peer.packageName != context.packageName
                    ) {
                        return resolveNext()
                    }
                    if (!peer.supportsPairing()) {
                        legacyPeer.set(true)
                        return resolveNext()
                    }
                    val (ownCode, _) = installedLocalOtaVersion(context)
                    when (localOtaSendDecision(peer, context.packageName, ownCode)) {
                        LocalOtaSendDecision.Send -> {
                            if (!chosen.compareAndSet(false, true)) return
                            stopDiscovery(listener)
                            discoveryTimeoutJob?.cancel()
                            val expectedKey = LocalOtaCrypto.unb64(peer.publicKey)
                            senderJob = scope.launch {
                                runHandshake(listOf(LocalOtaTarget(host, info.port)), expectedKey)
                            }
                        }
                        LocalOtaSendDecision.PeerUpToDate -> {
                            upToDatePeer[0] = peer.versionName ?: peer.versionCode?.toString()
                            resolveNext()
                        }
                        LocalOtaSendDecision.WrongPackage -> resolveNext()
                    }
                }
            })
        }

        listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryTimeoutJob?.cancel()
                finishSender(text(R.string.ota_msg_discovery_unavailable, errorCode), suggestManual = true)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (chosen.get()) return
                if (!serviceInfo.serviceType.trimEnd('.').equals(LocalOtaProtocol.SERVICE_TYPE.trimEnd('.'), ignoreCase = true)) return
                // Skip this phone's own receiver without spending a resolve slot.
                if (serviceInfo.serviceName?.contains(ownServiceTag()) == true) return
                synchronized(queue) { queue.addLast(serviceInfo) }
                if (resolving.compareAndSet(false, true)) resolveNext()
            }
        }
        discoveryListener = listener
        @Suppress("DEPRECATION")
        nsdManager.discoverServices(LocalOtaProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        discoveryTimeoutJob = scope.launch {
            delay(LocalOtaProtocol.DISCOVERY_TIMEOUT_MS)
            if (!chosen.get()) {
                chosen.set(true)
                stopDiscovery(listener)
                val upToDate = upToDatePeer[0]
                when {
                    upToDate != null ->
                        finishSender(text(R.string.ota_msg_peer_up_to_date, upToDate), tone = LocalOtaMessageTone.Info)
                    legacyPeer.get() -> finishSender(text(R.string.ota_msg_peer_legacy))
                    else ->
                        finishSender(text(R.string.ota_msg_none_found), tone = LocalOtaMessageTone.Info, suggestManual = true)
                }
            }
        }
    }

    private fun finishSender(
        message: String,
        tone: LocalOtaMessageTone = LocalOtaMessageTone.Error,
        suggestManual: Boolean = false,
    ) {
        senderJob = null
        senderClient = null
        multicastLock.release(LocalOtaMulticastUser.SENDER)
        _state.update {
            it.copy(
                senderActive = false,
                pairing = null,
                message = message,
                tone = tone,
                suggestManual = suggestManual || it.suggestManual,
            )
        }
    }

    fun close() {
        stopReceiver()
        stopSender()
        scope.cancel()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private fun stopSender() {
        discoveryListener?.let(::stopDiscovery)
        discoveryListener = null
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        senderClient?.cancel()
        senderClient = null
        senderJob?.cancel()
        senderJob = null
        multicastLock.release(LocalOtaMulticastUser.SENDER)
        _state.update { it.copy(senderActive = false, pairing = null) }
    }

    private fun stopDiscovery(listener: NsdManager.DiscoveryListener) {
        runCatching { nsdManager.stopServiceDiscovery(listener) }
        if (discoveryListener === listener) discoveryListener = null
    }

    private companion object {
        const val RECEIVER_REFRESH_MS = 5_000L
        const val RESOLVE_RETRY_MS = 400L
    }
}
