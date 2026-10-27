package com.ossm.remote.updater

import android.net.wifi.WifiManager

internal enum class LocalOtaMulticastUser {
    RECEIVER,
    SENDER,
}

internal class LocalOtaMulticastLock(private val lock: WifiManager.MulticastLock?) {
    private val users = mutableSetOf<LocalOtaMulticastUser>()

    @Synchronized
    fun acquire(user: LocalOtaMulticastUser) {
        if (user in users) return
        if (users.isEmpty()) lock?.acquire()
        users += user
    }

    @Synchronized
    fun release(user: LocalOtaMulticastUser) {
        if (user !in users) return
        users -= user
        if (users.isEmpty()) lock?.release()
    }
}
