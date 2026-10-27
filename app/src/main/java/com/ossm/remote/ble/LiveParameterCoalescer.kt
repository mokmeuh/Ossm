package com.ossm.remote.ble

/**
 * Latest-wins mailbox for real-time slider parameters (`set:speed`, `set:sensation`,
 * `set:depth`, `set:stroke`, ...).
 *
 * 2.3.17 — cause of the "frozen while dragging" report: every drag tick (up to ~33/s) was
 * appended to the UNLIMITED normal GATT queue, each write waiting for its acknowledgement
 * (one or two connection intervals). When the link is slower than the drag cadence the queue
 * grows, the machine answers seconds behind the finger, and Live targets/other writes wait
 * behind the backlog. A stale `set:speed:N` still queued after an urgent STOP could even
 * restart motion. Only the newest value per parameter matters, so values are coalesced here
 * and a single writer drains them one acknowledged write at a time.
 */
internal class LiveParameterCoalescer {
    private val pending = LinkedHashMap<String, Int>()

    /** Record the newest value of [param]; an older pending value is replaced in place. */
    @Synchronized
    fun offer(param: String, value: Int) {
        pending[param] = value
    }

    /** Oldest pending parameter with its newest value, removed from the mailbox. */
    @Synchronized
    fun poll(): Pair<String, Int>? {
        val iterator = pending.entries.iterator()
        if (!iterator.hasNext()) return null
        val entry = iterator.next()
        iterator.remove()
        return entry.key to entry.value
    }

    /** A direct (committed) write of [param] supersedes any pending live value. */
    @Synchronized
    fun remove(param: String) {
        pending.remove(param)
    }

    /** STOP / disconnect: nothing pending may be written afterwards. */
    @Synchronized
    fun clear() {
        pending.clear()
    }

    @Synchronized
    fun size(): Int = pending.size
}

/** `set:<param>:<value>` → `<param>`, or null for any other command. */
internal fun liveParameterOf(command: String): String? {
    if (!command.startsWith("set:")) return null
    val end = command.indexOf(':', startIndex = 4)
    if (end <= 4) return null
    return command.substring(4, end)
}
