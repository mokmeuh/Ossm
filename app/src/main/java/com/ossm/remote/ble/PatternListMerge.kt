package com.ossm.remote.ble

import com.ossm.remote.model.OssmPattern

/**
 * Builds the pattern picker from the list the machine reports.
 *
 * The app-driven modes always exist. The stroke-engine patterns the app already knows keep their
 * stable key and take the name the machine reports. A pattern announced by a newer firmware that
 * the app does not know yet (for example a new movement) is appended in the machine's order, so
 * it shows up without an app update. An older firmware reports fewer patterns and simply yields a
 * shorter list; the app never offers a pattern the machine did not announce.
 */
internal fun mergeDevicePatterns(
    devicePatterns: List<OssmPattern>,
    knownStrokeEngine: List<OssmPattern>,
    appDriven: List<OssmPattern>,
): List<OssmPattern> {
    val deviceById = devicePatterns.mapNotNull { pattern -> pattern.id?.let { it to pattern } }.toMap()
    val known = knownStrokeEngine.map { fallback ->
        val fromDevice = fallback.id?.let(deviceById::get)
        if (fromDevice != null) fallback.copy(name = fromDevice.name) else fallback
    }
    val knownIds = knownStrokeEngine.mapNotNull { it.id }.toSet()
    val announcedByMachine = deviceById.values
        .filter { it.id !in knownIds }
        .sortedBy { it.id }
    return appDriven + known + announcedByMachine
}
