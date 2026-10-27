package com.ossm.remote.ble

internal enum class CommandTransport {
    USB,
    BLE,
    NONE,
}

internal fun chooseCommandTransport(usbReady: Boolean, bleReady: Boolean): CommandTransport = when {
    usbReady -> CommandTransport.USB
    bleReady -> CommandTransport.BLE
    else -> CommandTransport.NONE
}
