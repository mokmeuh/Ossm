package com.ossm.remote.usb

/**
 * When to give up on the USB-C serial link after failed writes.
 *
 * A heartbeat frame is written every 100 ms with a 40 ms write timeout, so a single slow
 * write (busy CDC chip, phone under load) is not proof that the cable is gone. Closing too
 * early drops a healthy link; closing too late leaves the app believing it still controls
 * a machine it can no longer reach. The firmware stops on its own when heartbeats stop.
 */
internal object UsbLinkPolicy {
    /** Number of consecutive failed writes after which the link is closed and reopened. */
    const val DROP_AFTER_FAILURES = 5

    fun shouldDropLink(consecutiveFailures: Int): Boolean = consecutiveFailures >= DROP_AFTER_FAILURES

    /** Readable state of the link, derived from what the transport knows. */
    fun linkState(
        cablePresent: Boolean,
        ready: Boolean,
        reopening: Boolean,
        failed: Boolean,
    ): UsbLinkState = when {
        ready -> UsbLinkState.Ready
        !cablePresent -> UsbLinkState.Absent
        reopening -> UsbLinkState.Recovering
        failed -> UsbLinkState.Failed
        else -> UsbLinkState.Connecting
    }
}

enum class UsbLinkState { Absent, Connecting, Ready, Recovering, Failed }

/**
 * 2.3.43: the Bluetooth link never takes over by itself while the OSSM USB cable is still
 * plugged in. Falling back to BLE while USB was recovering made both radios fight and the
 * BLE link misbehave.
 */
internal object UsbFallbackPolicy {
    /**
     * @param cablePresent the OSSM is physically on the USB bus
     * @param reopening a USB reopen attempt is in progress
     * @param reopenFailed every reopen attempt failed (or USB permission was refused)
     * @param userRequested the user explicitly asked for BLE (scan/connect button)
     */
    fun allowBle(
        cablePresent: Boolean,
        reopening: Boolean,
        reopenFailed: Boolean,
        userRequested: Boolean,
    ): Boolean = when {
        !cablePresent -> true
        reopening -> false
        else -> reopenFailed && userRequested
    }
}

/**
 * Opens the app on Live once per cable plug-in, and only when the USB link is ready and the
 * machine is ready. Never fires for an app launched by hand with the cable already in, never
 * twice for the same plug (the attach is signalled both by the system intent and by the
 * transport receiver), and a stale arming expires.
 */
internal class UsbLiveAutoEntry(private val ttlMs: Long = DEFAULT_TTL_MS) {
    private var pluggedSeen = false
    private var armedAtMs: Long? = null

    /** An attach signal; returns true when it armed a new auto-entry. */
    @Synchronized
    fun onAttach(nowMs: Long): Boolean {
        if (pluggedSeen) return false
        pluggedSeen = true
        armedAtMs = nowMs
        return true
    }

    @Synchronized
    fun onDetach() {
        pluggedSeen = false
        armedAtMs = null
    }

    /** True exactly once per armed plug-in, when everything is ready. */
    @Synchronized
    fun tryConsume(nowMs: Long, linkReady: Boolean, machineReady: Boolean): Boolean {
        val armed = armedAtMs ?: return false
        if (nowMs - armed > ttlMs) {
            armedAtMs = null
            return false
        }
        if (!linkReady || !machineReady) return false
        armedAtMs = null
        return true
    }

    companion object {
        const val DEFAULT_TTL_MS = 120_000L
    }
}
