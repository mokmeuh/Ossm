package com.ossm.remote.session

import com.ossm.remote.ble.DepthByLoadProtocol

/** Réglages mémorisés du mode « profondeur selon la résistance ». L'activation n'en fait pas partie. */
data class DepthByLoadSettings(
    val sensitivityPercent: Int = DepthByLoadProtocol.DEFAULT_SENSITIVITY_PERCENT,
    val floorPercent: Int = DepthByLoadProtocol.DEFAULT_FLOOR_PERCENT,
)
