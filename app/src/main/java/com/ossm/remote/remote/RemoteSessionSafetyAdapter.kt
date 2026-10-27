package com.ossm.remote.remote

import com.ossm.remote.session.ControlSessionStore
import javax.inject.Inject

class RemoteSessionSafetyAdapter internal constructor(
    private val controlSessionStore: ControlSessionStore,
    private val clockMillis: () -> Long,
) {
    @Inject constructor(controlSessionStore: ControlSessionStore) : this(controlSessionStore, System::currentTimeMillis)
    private var controllerLeaseId: String? = null
    private var leaseExpiresAtMillis = 0L
    private var lastSequence = Long.MIN_VALUE
    private var lastMotionAtMillis = 0L
    private var remoteSessionStarted = false

    /**
     * Receives the short, non-sensitive reason each time a browser command is refused.
     * Set by the session client; never carries command payloads, identifiers or secrets.
     */
    @Volatile
    var refusalListener: ((RemoteRefusalReason) -> Unit)? = null

    private fun refuse(reason: RemoteRefusalReason): Boolean {
        refusalListener?.invoke(reason)
        return false
    }

    /** Begin one browser session and capture the phone-owned local envelope. */
    fun beginSession(
        localSpeedMaximum: Float? = null,
        localDepthMinimum: Float? = null,
        localDepthMaximum: Float? = null,
        localSensation: Float? = null,
    ): PhoneHostMotionLimits {
        val limits = controlSessionStore.beginRemoteSession(
            localSpeedMaximum = localSpeedMaximum,
            localDepthMinimum = localDepthMinimum,
            localDepthMaximum = localDepthMaximum,
            localSensation = localSensation,
        )
        remoteSessionStarted = true
        return limits
    }

    /**
     * End the browser session and clear its separate safety snapshot. SAFETY: the end of a
     * session is always a STOP (speed 0, no Live replay); the envelope maximum is never
     * adopted as a local command (see [ControlSessionStore.endRemoteSession]).
     */
    fun endSession() {
        val wasStarted = remoteSessionStarted
        remoteSessionStarted = false
        val storeHadSession = controlSessionStore.endRemoteSession()
        // Defensive: the adapter believed a session was running but the Store had already
        // dropped its envelope. Still end in a STOP.
        if (wasStarted && !storeHadSession) controlSessionStore.stop()
    }

    /** Live envelope + what the browser asked for (see [RemoteSessionView]). */
    val sessionView: kotlinx.coroutines.flow.StateFlow<RemoteSessionView>
        get() = controlSessionStore.remoteSessionView

    /** Readiness from ordinary phone BLE/post-setup state only. */
    fun hostReadiness(): PhoneHostReadiness = controlSessionStore.hostReadiness()

    fun updateReadiness(readiness: PhoneHostReadiness) {
        this.readiness = readiness
    }

    private var readiness = PhoneHostReadiness(false, false, false)

    fun accept(command: RemoteSessionCommand): Boolean {
        if (command is RemoteSessionCommand.EmergencyStop) {
            controlSessionStore.emergencyStop()
            return true
        }
        if (!isLeaseValid(command.leaseId)) return refuse(RemoteRefusalReason.LeaseInvalid)
        if (command.sequence <= lastSequence) return refuse(RemoteRefusalReason.StaleSequence)
        val interrupt = command is RemoteSessionCommand.Stop || command is RemoteSessionCommand.EndLiveTouch
        if ((!remoteSessionStarted || controlSessionStore.remoteSessionLimits() == null) && !interrupt) {
            return refuse(RemoteRefusalReason.SessionNotStarted)
        }
        if (command is RemoteSessionCommand.Motion && !readiness.allowsRemoteMotion) {
            return refuse(RemoteRefusalReason.MachineNotReady)
        }
        // Rate limiting is routine pacing of a fast slider, not a refusal worth reporting.
        if (command is RemoteSessionCommand.Motion && clockMillis() - lastMotionAtMillis < MIN_MOTION_INTERVAL_MS) return false
        if (command is RemoteSessionCommand.Motion &&
            !controlSessionStore.setRemoteStreamPosition(command.positionPercent)
        ) {
            return refuse(RemoteRefusalReason.LivePositionRejected)
        }
        lastSequence = command.sequence
        if (command is RemoteSessionCommand.Motion) lastMotionAtMillis = clockMillis()
        when (command) {
            is RemoteSessionCommand.Motion -> Unit
            is RemoteSessionCommand.Stop -> controlSessionStore.stop()
            // Browser release has the same phone-owned slow return as a local finger release.
            // Explicit Stop remains the immediate cancellation path below.
            is RemoteSessionCommand.EndLiveTouch -> controlSessionStore.endLiveTouch()
            is RemoteSessionCommand.EmergencyStop -> Unit
        }
        return true
    }

    internal fun acceptAction(
        leaseId: String,
        sequence: Long,
        action: RemoteControlAction,
        onAccepted: (() -> Unit)? = null,
    ): Boolean {
        if (action == RemoteControlAction.EmergencyStop) {
            onAccepted?.invoke()
            controlSessionStore.emergencyStop()
            return true
        }
        if (!isLeaseValid(leaseId)) return refuse(RemoteRefusalReason.LeaseInvalid)
        if (sequence <= lastSequence) return refuse(RemoteRefusalReason.StaleSequence)
        val interrupt = action in setOf(RemoteControlAction.Stop, RemoteControlAction.EndLiveTouch)
        if ((!remoteSessionStarted || controlSessionStore.remoteSessionLimits() == null) && !interrupt) {
            return refuse(RemoteRefusalReason.SessionNotStarted)
        }
        val requiresMotionReadiness = action !in setOf(
            RemoteControlAction.Stop,
            RemoteControlAction.EndLiveTouch,
            RemoteControlAction.Home,
        )
        val homeReady = readiness.connected && readiness.postSetupReady
        if ((requiresMotionReadiness && !readiness.allowsRemoteMotion) ||
            (action == RemoteControlAction.Home && !homeReady)
        ) {
            return refuse(RemoteRefusalReason.MachineNotReady)
        }
        if (action is RemoteControlAction.SetLivePosition &&
            !controlSessionStore.setRemoteStreamPosition(action.percent)
        ) {
            return refuse(RemoteRefusalReason.LivePositionRejected)
        }
        onAccepted?.invoke()
        lastSequence = sequence
        when (action) {
            is RemoteControlAction.SetSpeed -> controlSessionStore.requestRemoteSpeedChange(action.value)
            is RemoteControlAction.SetDepth -> controlSessionStore.requestRemoteDepthRangeChange(action.minimum, action.maximum)
            is RemoteControlAction.SetSensation -> controlSessionStore.requestRemoteSensationChange(action.value)
            is RemoteControlAction.SelectPattern -> controlSessionStore.activateRemotePattern(action.key)
            is RemoteControlAction.SetLivePosition -> Unit
            RemoteControlAction.EndLiveTouch -> controlSessionStore.endLiveTouch()
            RemoteControlAction.Stop -> controlSessionStore.stop()
            RemoteControlAction.Home -> controlSessionStore.home()
            RemoteControlAction.Pause -> controlSessionStore.pause()
            RemoteControlAction.Resume -> controlSessionStore.resume()
            RemoteControlAction.EmergencyStop -> Unit
        }
        return true
    }

    fun grantLease(leaseId: String, expiresAtMillis: Long) {
        if (expiresAtMillis <= clockMillis()) return
        if (controllerLeaseId == leaseId) {
            leaseExpiresAtMillis = maxOf(leaseExpiresAtMillis, expiresAtMillis)
            return
        }
        if (controllerLeaseId != null && controllerLeaseId != leaseId) controlSessionStore.emergencyStop()
        controllerLeaseId = leaseId
        leaseExpiresAtMillis = expiresAtMillis
        lastSequence = Long.MIN_VALUE
    }

    fun loseLease(leaseId: String): Boolean {
        if (controllerLeaseId != leaseId) return false
        loseLease()
        return true
    }

    /** Transport loss, explicit stop/timeout and service destruction all end in a STOP. */
    fun onTransportLost() = loseLeaseAndStop()
    fun onSessionTimeout() = loseLeaseAndStop()
    fun onServiceDestroyed() = loseLeaseAndStop()

    /**
     * Drop the lease (emergency stop when a controller held it) and, even when no controller
     * ever held a lease, stop any motion while a session is running.
     */
    private fun loseLeaseAndStop() {
        val hadLease = controllerLeaseId != null
        loseLease()
        if (!hadLease && remoteSessionStarted) controlSessionStore.stop()
    }

    fun expireIfNeeded() {
        if (controllerLeaseId != null && clockMillis() >= leaseExpiresAtMillis) loseLease()
    }

    private fun isLeaseValid(leaseId: String): Boolean =
        controllerLeaseId == leaseId && clockMillis() < leaseExpiresAtMillis

    private fun loseLease() {
        if (controllerLeaseId != null) controlSessionStore.emergencyStop()
        controllerLeaseId = null
        leaseExpiresAtMillis = 0L
        lastSequence = Long.MIN_VALUE
    }

    companion object { private const val MIN_MOTION_INTERVAL_MS = 35L }
}

/**
 * Why the phone refused a browser command. [code] is a fixed, non-sensitive identifier:
 * it never contains payloads, lease/participant ids, secrets or the `#k=` fragment.
 */
enum class RemoteRefusalReason(val code: String) {
    LeaseInvalid("remote_lease_invalid"),
    SessionNotStarted("remote_session_not_started"),
    MachineNotReady("remote_machine_not_ready"),
    StaleSequence("remote_sequence_stale"),
    LivePositionRejected("remote_live_position_rejected"),
}

sealed interface RemoteSessionCommand {
    val leaseId: String
    val sequence: Long
    data class Motion(override val leaseId: String, override val sequence: Long, val positionPercent: Int) : RemoteSessionCommand
    data class Stop(override val leaseId: String, override val sequence: Long) : RemoteSessionCommand
    data class EndLiveTouch(override val leaseId: String, override val sequence: Long) : RemoteSessionCommand
    data class EmergencyStop(override val leaseId: String = "", override val sequence: Long = Long.MAX_VALUE) : RemoteSessionCommand
}
