package com.ossm.remote.ble

internal fun isSpeedKnobIndependentValue(value: ByteArray?): Boolean =
    value?.toString(Charsets.UTF_8)?.trim()?.equals("false", ignoreCase = true) == true
