package com.ossm.remote.updater

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lets at most one PC check run at a time, and none without a saved server address
 * (the on-open check, the periodic poll and the button all go through it).
 */
internal class PcCheckGate {
    private val running = AtomicBoolean(false)

    /** True when the caller may run a check; it must then call [end] when done. */
    fun tryBegin(serverUrl: String): Boolean =
        serverUrl.isNotBlank() && running.compareAndSet(false, true)

    fun end() = running.set(false)
}
