package com.ossm.remote.ble

import java.util.UUID

/**
 * Caractéristique « profondeur selon la résistance » (paquet de 8 octets, petit-boutiste).
 *
 * Elle est indépendante des trois caractéristiques résistance de [ResistanceProtocol] : leurs
 * décodeurs stricts gardent le transport Live fluide, donc rien n'y est ajouté. L'absence de
 * cette caractéristique veut simplement dire que le firmware ne sait pas faire ce mode.
 *
 *  [0] version (1)
 *  [1] flags : bit0 = actif (souhaité à l'écriture, état réel à la lecture),
 *              bit1 = indisponible (lecture seule : signal de courant non validé ou périmé)
 *  [2] sensibilité 0..100 (0 = la charge n'a aucun effet)
 *  [3] plancher 10..100 (fraction minimale de la profondeur max de l'utilisateur, en %)
 *  [4..5] charge normalisée en ‰ (lecture/notification seulement, ignorée à l'écriture)
 *  [6..7] facteur de profondeur appliqué en ‰, 1000 = pleine profondeur (idem)
 */
object DepthByLoadProtocol {
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("522b443a-4f53-534d-6200-420badbabe69")

    const val VERSION = 1
    const val PACKET_SIZE = 8

    const val FLAG_ENABLED = 1 shl 0
    const val FLAG_UNAVAILABLE = 1 shl 1
    const val ALL_FLAGS = FLAG_ENABLED or FLAG_UNAVAILABLE

    const val MAX_SENSITIVITY_PERCENT = 100
    const val MIN_FLOOR_PERCENT = 10
    const val MAX_FLOOR_PERCENT = 100
    const val MAX_PERMILLE = 1_000

    const val DEFAULT_SENSITIVITY_PERCENT = 50
    const val DEFAULT_FLOOR_PERCENT = 40

    enum class RejectReason { INVALID_LENGTH, UNSUPPORTED_VERSION, INVALID_FLAGS, OUT_OF_RANGE }

    data class Packet(
        val enabled: Boolean,
        val unavailable: Boolean,
        val sensitivityPercent: Int,
        val floorPercent: Int,
        val loadPermille: Int,
        val depthFactorPermille: Int,
    )

    data class DecodeResult(val value: Packet? = null, val error: RejectReason? = null)

    fun clampSensitivity(percent: Int): Int = percent.coerceIn(0, MAX_SENSITIVITY_PERCENT)

    fun clampFloor(percent: Int): Int = percent.coerceIn(MIN_FLOOR_PERCENT, MAX_FLOOR_PERCENT)

    /** Paquet d'écriture : les octets de lecture seule (indisponible, charge, facteur) restent à zéro. */
    fun encodeWrite(enabled: Boolean, sensitivityPercent: Int, floorPercent: Int): ByteArray =
        ByteArray(PACKET_SIZE).also {
            it[0] = VERSION.toByte()
            it[1] = (if (enabled) FLAG_ENABLED else 0).toByte()
            it[2] = clampSensitivity(sensitivityPercent).toByte()
            it[3] = clampFloor(floorPercent).toByte()
        }

    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.size != PACKET_SIZE) return DecodeResult(error = RejectReason.INVALID_LENGTH)
        if (bytes.u8(0) != VERSION) return DecodeResult(error = RejectReason.UNSUPPORTED_VERSION)
        val flags = bytes.u8(1)
        if (flags and ALL_FLAGS.inv() != 0) return DecodeResult(error = RejectReason.INVALID_FLAGS)
        val sensitivity = bytes.u8(2)
        val floor = bytes.u8(3)
        val load = bytes.u16(4)
        val factor = bytes.u16(6)
        if (
            sensitivity > MAX_SENSITIVITY_PERCENT ||
            floor !in MIN_FLOOR_PERCENT..MAX_FLOOR_PERCENT ||
            load > MAX_PERMILLE ||
            factor > MAX_PERMILLE
        ) {
            return DecodeResult(error = RejectReason.OUT_OF_RANGE)
        }
        return DecodeResult(
            value = Packet(
                enabled = flags and FLAG_ENABLED != 0,
                unavailable = flags and FLAG_UNAVAILABLE != 0,
                sensitivityPercent = sensitivity,
                floorPercent = floor,
                loadPermille = load,
                depthFactorPermille = factor,
            ),
        )
    }

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xff

    private fun ByteArray.u16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)
}

/**
 * État du mode vu par l'appli. [present] est faux tant que la caractéristique n'a pas été lue
 * correctement : sans elle, aucune interface n'est active.
 */
data class DepthByLoadState(
    val present: Boolean = false,
    val unavailable: Boolean = false,
    val enabled: Boolean = false,
    val sensitivityPercent: Int = DepthByLoadProtocol.DEFAULT_SENSITIVITY_PERCENT,
    val floorPercent: Int = DepthByLoadProtocol.DEFAULT_FLOOR_PERCENT,
    val loadPermille: Int = 0,
    val depthFactorPermille: Int = DepthByLoadProtocol.MAX_PERMILLE,
) {
    /** Le mode peut être activé : la machine le connaît et son signal est valide. */
    val usable: Boolean get() = present && !unavailable

    companion object {
        fun from(packet: DepthByLoadProtocol.Packet): DepthByLoadState = DepthByLoadState(
            present = true,
            unavailable = packet.unavailable,
            // Un firmware qui se déclare indisponible ne module rien, quel que soit le bit actif.
            enabled = packet.enabled && !packet.unavailable,
            sensitivityPercent = packet.sensitivityPercent,
            floorPercent = packet.floorPercent,
            loadPermille = packet.loadPermille,
            depthFactorPermille = packet.depthFactorPermille,
        )
    }
}
