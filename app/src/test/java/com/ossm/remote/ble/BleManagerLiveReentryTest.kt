package com.ossm.remote.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.model.LiveTargetProducer
import com.ossm.remote.model.MachineState
import com.ossm.remote.model.OssmCommand
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import java.util.Collections
import java.util.UUID

class BleManagerLiveReentryTest {
    private val context = mockk<Context>()
    private val bluetoothManager = mockk<BluetoothManager>()
    private val bluetoothAdapter = mockk<BluetoothAdapter>()
    private val nowMs = AtomicLong(0)

    private lateinit var manager: BleManager

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        mockkStatic(ContextCompat::class)
        mockkStatic(SystemClock::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { ContextCompat.checkSelfPermission(any(), any()) } returns android.content.pm.PackageManager.PERMISSION_GRANTED
        every { SystemClock.elapsedRealtime() } answers { nowMs.get() }
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager
        every { bluetoothManager.adapter } returns bluetoothAdapter
        every { bluetoothAdapter.bluetoothLeScanner } returns null

        manager = BleManager(context)
        streamingReadyFlow().value = true
        manager.resumeStreamingTargets()
    }

    @After
    fun tearDown() {
        scope().cancel()
        unmockkStatic(SystemClock::class)
        unmockkStatic(Log::class)
        unmockkStatic(ContextCompat::class)
    }

    @Test
    fun `new ready touch stream resumes after stop without dispatching retained target`() {
        manager.sendCommand(touchStream(positionPercent = 20, receivedAtMs = 0))
        awaitDispatchedPosition(mapLiveStreamPositionPercent(20, liveInvert = false))

        scheduler().offer(
            LiveTargetInput(
                positionPercent = mapLiveStreamPositionPercent(0, liveInvert = false),
                requestedDurationMs = 60,
                producer = LiveTargetProducer.TOUCH,
                receivedAtMs = 5,
            )
        )

        manager.sendCommand(OssmCommand.Stop)

        assertEquals("set:speed:0", manager.lastCommand.value)
        assertNull(dispatchedPosition())
        assertNull(scheduler().poll(Long.MAX_VALUE))

        nowMs.set(10)
        manager.sendCommand(touchStream(positionPercent = 70, receivedAtMs = 10))

        assertEquals(
            "First Live touch after stop must re-arm streaming speed before its target",
            "set:speed:100",
            manager.lastCommand.value,
        )
        awaitDispatchedPosition(mapLiveStreamPositionPercent(70, liveInvert = false))
    }

    @Test
    fun `initial streaming entry at zero speed rearms before first touch target`() {
        streamingReadyFlow().value = false
        machineStateFlow().value = MachineState(
            state = "menu",
            speed = 0,
            stroke = 100,
            depth = 100,
        )

        manager.sendCommand(OssmCommand.EnterStreaming)
        Thread.sleep(100)
        machineStateFlow().value = MachineState(
            state = "streaming.idle",
            speed = 0,
            stroke = 100,
            depth = 100,
        )
        awaitStreamingReady()

        manager.sendCommand(touchStream(positionPercent = 60, receivedAtMs = 200))

        assertEquals(
            "First target after an initial zero-speed Live entry must re-arm speed",
            "set:speed:100",
            manager.lastCommand.value,
        )
    }

    @Test
    fun `touch move hold dispatches documented targets before release stop`() {
        val writes = Collections.synchronizedList(mutableListOf<WireWrite>())
        installConnectedGattHarness(writes)

        manager.sendCommand(touchStream(positionPercent = 20, receivedAtMs = 0))
        awaitWrite(writes) { it.text == "stream:80:120" }

        nowMs.set(80)
        manager.sendCommand(touchStream(positionPercent = 70, receivedAtMs = 80))
        nowMs.set(200)
        awaitWrite(writes) { it.text == "stream:75:60" }

        Thread.sleep(80)
        manager.sendCommand(OssmCommand.Stop)
        awaitWrite(writes) { it.text == "set:speed:0" }

        val stopIndex = writes.indexOfFirst { it.text == "set:speed:0" }
        assertTrue("Release Stop must be delivered", stopIndex >= 0)
        assertTrue(
            "At least one real-time position target must reach GATT before release Stop: $writes",
            writes.take(stopIndex).any { it.text?.startsWith("stream:") == true },
        )
        assertFalse(
            "No position target may be delivered after release Stop: $writes",
            writes.drop(stopIndex + 1).any { it.isLiveTarget },
        )
    }

    @Test
    fun `emergency stop disarms ready streaming and cannot auto resume`() {
        manager.emergencyStop()

        assertFalse(manager.streamingReady.value)
        manager.sendCommand(touchStream(positionPercent = 70, receivedAtMs = 10))
        Thread.sleep(100)
        assertNull(dispatchedPosition())
    }

    @Test
    fun `disconnect and not ready streams cannot auto resume`() {
        manager.disconnect()
        assertFalse(manager.streamingReady.value)

        manager.sendCommand(touchStream(positionPercent = 70, receivedAtMs = 10))

        Thread.sleep(100)
        assertNull(dispatchedPosition())
    }

    private fun touchStream(positionPercent: Int, receivedAtMs: Long) = OssmCommand.Stream(
        positionPercent = positionPercent,
        timeMs = 60,
        producer = LiveTargetProducer.TOUCH,
        receivedAtMs = receivedAtMs,
    )

    private fun scheduler(): LiveTargetScheduler = privateField("liveTargetScheduler")

    private fun streamingReadyFlow(): MutableStateFlow<Boolean> = privateField("_streamingReady")

    private fun machineStateFlow(): MutableStateFlow<MachineState> = privateField("_machineState")

    private fun scope(): CoroutineScope = privateField("scope")

    private fun dispatchedPosition(): Int? {
        val field = LiveTargetScheduler::class.java.getDeclaredField("lastDispatchedPosition")
        field.isAccessible = true
        return field.get(scheduler()) as Int?
    }

    private fun awaitDispatchedPosition(expected: Int) {
        val deadlineNanos = System.nanoTime() + 2_000_000_000L
        var actual: Int?
        do {
            actual = dispatchedPosition()
            if (actual == expected) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadlineNanos)
        fail("Expected latest Live target $expected to dispatch, but scheduler position was $actual")
    }

    private fun awaitStreamingReady() {
        val deadlineNanos = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadlineNanos) {
            if (manager.streamingReady.value) return
            Thread.sleep(10)
        }
        fail("Expected initial Live entry to become streamingReady")
    }

    private fun installConnectedGattHarness(writes: MutableList<WireWrite>) {
        val fakeGatt = mockk<BluetoothGatt>(relaxed = true)
        val command = mockWritableCharacteristic(UUID.fromString("522b443a-4f53-534d-1000-420badbabe69"))
        val live = mockWritableCharacteristic(UUID.fromString(LiveSmoothProtocol.CHARACTERISTIC_UUID))
        every { fakeGatt.writeCharacteristic(any()) } returns true
        every { fakeGatt.writeCharacteristic(any(), any(), any()) } returns
            android.bluetooth.BluetoothStatusCodes.SUCCESS
        manager.gattWriteAttemptObserver = { characteristic, payload, writeType ->
            writes += WireWrite(characteristic.uuid, payload)
            if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                gattCallback().onCharacteristicWrite(fakeGatt, characteristic, BluetoothGatt.GATT_SUCCESS)
            }
        }

        setPrivateField("gatt", fakeGatt)
        setPrivateField("commandCharacteristic", command)
        setPrivateField("liveSmoothCharacteristic", live)
        setPrivateField("liveSmoothCapabilityPayload", validCapabilityPayload())
        setPrivateField("postSetupDone", true)
        connectionStateFlow().value = BleConnectionState.Connected("OSSM", "test")
    }

    private fun mockWritableCharacteristic(uuid: UUID): BluetoothGattCharacteristic {
        val characteristic = mockk<BluetoothGattCharacteristic>(relaxed = true)
        var value = byteArrayOf()
        every { characteristic.uuid } returns uuid
        every { characteristic.properties } returns (
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            )
        every { characteristic.value } answers { value }
        every { characteristic.value = any() } answers { value = firstArg<ByteArray>().copyOf() }
        return characteristic
    }

    private fun awaitWrite(writes: List<WireWrite>, predicate: (WireWrite) -> Boolean) {
        val deadlineNanos = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadlineNanos) {
            if (writes.toList().any(predicate)) return
            Thread.sleep(10)
        }
        fail("Expected GATT write was not observed. Writes=$writes")
    }

    private fun validCapabilityPayload() = ResistanceProtocol.encodeCapability(
        ResistanceProtocol.Capability(
            capabilityBits = ResistanceProtocol.ALL_CAPABILITY_BITS,
            telemetryProperties = ResistanceProtocol.TELEMETRY_PROPERTIES,
            configProperties = ResistanceProtocol.CONFIG_PROPERTIES,
        )
    )

    private fun connectionStateFlow(): MutableStateFlow<BleConnectionState> = privateField("_connectionState")

    private fun gattCallback(): android.bluetooth.BluetoothGattCallback = privateField("gattCallback")

    private fun setPrivateField(name: String, value: Any?) {
        val field = BleManager::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(manager, value)
    }

    private data class WireWrite(val characteristicUuid: UUID, val payload: ByteArray) {
        val text: String?
            get() = payload.toString(Charsets.UTF_8).takeIf { value ->
                value.startsWith("set:") || value.startsWith("stream:")
            }
        val isLiveTarget: Boolean
            get() = characteristicUuid.toString() == LiveSmoothProtocol.CHARACTERISTIC_UUID ||
                text?.startsWith("stream:") == true

        override fun toString(): String = text ?: "binary(${payload.size} bytes)"
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(name: String): T {
        val field = BleManager::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(manager) as T
    }
}
