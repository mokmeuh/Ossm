package com.ossm.remote.model

data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val isOssm: Boolean = false
)
