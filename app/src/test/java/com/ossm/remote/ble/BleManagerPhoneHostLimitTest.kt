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
import com.ossm.remote.model.OssmCommand
import com.ossm.remote.remote.PhoneHostMotionLimits
import com.ossm.remote.usb.UsbSerialTransport
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Regression at the BLE boundary: host limits must bound encoded device output. */
class BleManagerPhoneHostLimitTest {
    private val context = mockk<Context>()
    private val bluetoothManager = mockk<BluetoothManager>()
    private val bluetoothAdapter = mockk<BluetoothAdapter>()
    private val nowMs = AtomicLong(0)
    private lateinit var usbReady: MutableStateFlow<Boolean>
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

        val usbTransport = mockk<UsbSerialTransport>(relaxed = true)
        usbReady = MutableStateFlow(false)
        every { usbTransport.ready } returns usbReady
        every { usbTransport.frames } returns MutableSharedFlow()
        manager = BleManager(context, usbTransport)
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
    fun `physical live endpoints and speed rearm remain inside host ceiling`() {
        val writes = Collections.synchronizedList(mutableListOf<WireWrite>())
        installConnectedGattHarness(writes)
        val limits = PhoneHostMotionLimits(0.4f, 0.2f, 0.4f)
        manager.setPhoneHostSpeedCeiling(limits.speedMaximum)

        // In the existing non-inverted BLE mapping, raw 60 emits physical 40.
        manager.sendCommand(
            OssmCommand.Stream(
                positionPercent = limits.mapLiveUiPositionForBle(0, liveInvert = false),
                timeMs = 60,
                producer = LiveTargetProducer.TOUCH,
                receivedAtMs = 0,
            )
        )
        awaitWrite(writes) { it.livePacket?.positionPermille == 400 }
        assertTrue(writes.any { it.text == "set:speed:40" })

        Thread.sleep(BleConstants.DEBOUNCE_MIN_MS + 10L)
        nowMs.set(80)
        manager.sendCommand(
            OssmCommand.Stream(
                positionPercent = limits.mapLiveUiPositionForBle(100, liveInvert = false),
                timeMs = 60,
                producer = LiveTargetProducer.TOUCH,
                receivedAtMs = 1,
            )
        )
        awaitLiveEndpointWrite(writes) { it.livePacket?.positionPermille == 200 }
        assertTrue(writes.filter { it.livePacket != null }.all { it.livePacket!!.positionPermille in 200..400 })
    }

    @Test
    fun `zero host speed blocks target and null restores ordinary hundred percent rearm`() {
        val writes = Collections.synchronizedList(mutableListOf<WireWrite>())
        installConnectedGattHarness(writes)
        manager.setPhoneHostSpeedCeiling(0f)
        manager.sendCommand(
            OssmCommand.Stream(
                positionPercent = 50,
                timeMs = 60,
                producer = LiveTargetProducer.TOUCH,
                receivedAtMs = 0,
            )
        )
        Thread.sleep(100)
        assertFalse(writes.any { it.isLiveTarget })

        manager.setPhoneHostSpeedCeiling(null)
        manager.sendCommand(OssmCommand.Stop)
        Thread.sleep(BleConstants.DEBOUNCE_MIN_MS + 10L)
        nowMs.set(200)
        manager.sendCommand(
            OssmCommand.Stream(
                positionPercent = 50,
                timeMs = 60,
                producer = LiveTargetProducer.TOUCH,
                receivedAtMs = 200,
            )
        )
        awaitWrite(writes) { it.text == "set:speed:100" }
    }

    @Test
    fun `lowering an armed ceiling is written before later targets and fractional caps floor`() {
        val writes = Collections.synchronizedList(mutableListOf<WireWrite>())
        installConnectedGattHarness(writes)
        manager.setPhoneHostSpeedCeiling(0.8f)
        manager.sendCommand(
            OssmCommand.Stream(
                positionPercent = 50,
                timeMs = 60,
                producer = LiveTargetProducer.TOUCH,
                receivedAtMs = 0,
            )
        )
        awaitWrite(writes) { it.text == "set:speed:80" }

        manager.setPhoneHostSpeedCeiling(0.205f)
        awaitWrite(writes) { it.text == "set:speed:20" }
        val writesBeforeZero = writes.size
        manager.setPhoneHostSpeedCeiling(0f)
        Thread.sleep(100)
        nowMs.set(200)
        manager.sendCommand(
            OssmCommand.Stream(
                positionPercent = 50,
                timeMs = 60,
                producer = LiveTargetProducer.TOUCH,
                receivedAtMs = 200,
            )
        )
        Thread.sleep(100)
        assertFalse(writes.drop(writesBeforeZero).any { it.isLiveTarget })
    }

    @Test
    fun `partial ceiling rejects legacy text while full and null preserve it`() {
        val writes = Collections.synchronizedList(mutableListOf<WireWrite>())
        installConnectedGattHarness(writes, binaryLive = false)
        manager.setPhoneHostSpeedCeiling(0.4f)
        manager.sendCommand(
            OssmCommand.Stream(
                positionPercent = 50,
                timeMs = 60,
                producer = LiveTargetProducer.TOUCH,
                receivedAtMs = 0,
            )
        )
        Thread.sleep(150)
        assertFalse(writes.any { it.text?.startsWith("stream:") == true })

        manager.setPhoneHostSpeedCeiling(1f)
        manager.sendCommand(OssmCommand.Stop)
        nowMs.set(200)
        manager.sendCommand(
            OssmCommand.Stream(
                positionPercent = 60,
                timeMs = 60,
                producer = LiveTargetProducer.TOUCH,
                receivedAtMs = 200,
            )
        )
        awaitWrite(writes) { it.text?.startsWith("stream:") == true }
        val fullLegacyCount = writes.count { it.text?.startsWith("stream:") == true }

        manager.setPhoneHostSpeedCeiling(null)
        manager.sendCommand(OssmCommand.Stop)
        nowMs.set(400)
        manager.sendCommand(
            OssmCommand.Stream(
                positionPercent = 30,
                timeMs = 60,
                producer = LiveTargetProducer.TOUCH,
                receivedAtMs = 400,
            )
        )
        awaitWrite(writes) {
            it.text?.startsWith("stream:") == true &&
                writes.count { candidate -> candidate.text?.startsWith("stream:") == true } > fullLegacyCount
        }
    }

    @Test
    fun `lowering armed legacy stream to partial ceiling stops and blocks later text`() {
        val writes = Collections.synchronizedList(mutableListOf<WireWrite>())
        installConnectedGattHarness(writes, binaryLive = false)
        manager.setPhoneHostSpeedCeiling(null)
        manager.sendCommand(
            OssmCommand.Stream(
                positionPercent = 50,
                timeMs = 60,
                producer = LiveTargetProducer.TOUCH,
                receivedAtMs = 0,
            )
        )
        awaitWrite(writes) { it.text?.startsWith("stream:") == true }
        val legacyBeforeLowering = writes.count { it.text?.startsWith("stream:") == true }

        manager.setPhoneHostSpeedCeiling(0.4f)
        awaitWrite(writes) { it.text == "set:speed:0" }
        nowMs.set(200)
        manager.sendCommand(
            OssmCommand.Stream(
                positionPercent = 70,
                timeMs = 60,
                producer = LiveTargetProducer.TOUCH,
                receivedAtMs = 200,
            )
        )
        Thread.sleep(150)
        assertEquals(legacyBeforeLowering, writes.count { it.text?.startsWith("stream:") == true })
    }

    @Test
    fun `failed USB with ready flag still blocks legacy GATT fallback for partial ceiling`() {
        val writes = Collections.synchronizedList(mutableListOf<WireWrite>())
        installConnectedGattHarness(writes, binaryLive = false)
        usbReady.value = true
        manager.setPhoneHostSpeedCeiling(0.4f)
        manager.sendCommand(
            OssmCommand.Stream(
                positionPercent = 50,
                timeMs = 60,
                producer = LiveTargetProducer.TOUCH,
                receivedAtMs = 0,
            )
        )
        Thread.sleep(150)
        assertFalse(writes.any { it.text?.startsWith("stream:") == true })
    }

    @Test
    fun `writable GATT without capability marker blocks partial ceiling`() {
        val writes = Collections.synchronizedList(mutableListOf<WireWrite>())
        installConnectedGattHarness(writes, binaryLive = true, capabilityPresent = false)
        manager.setPhoneHostSpeedCeiling(0.4f)
        manager.sendCommand(
            OssmCommand.Stream(
                positionPercent = 50,
                timeMs = 60,
                producer = LiveTargetProducer.TOUCH,
                receivedAtMs = 0,
            )
        )
        Thread.sleep(150)
        assertFalse(writes.any { it.text?.startsWith("stream:") == true })
    }

    @Test
    fun `partial ceiling without armed or streaming Live logs no Live refusal`() {
        val writes = Collections.synchronizedList(mutableListOf<WireWrite>())
        installConnectedGattHarness(writes, binaryLive = false)
        streamingReadyFlow().value = false

        manager.setPhoneHostSpeedCeiling(1f)
        manager.setPhoneHostSpeedCeiling(0.4f)
        manager.setPhoneHostSpeedCeiling(0.2f)
        Thread.sleep(50)

        verify(exactly = 0) { Log.w("OSSM", match<String> { it.contains("Live refusé") }) }
        assertFalse(writes.any { it.text == "set:speed:0" })
    }

    @Test
    fun `partial ceiling still refuses and logs a Live target on the text transport`() {
        val writes = Collections.synchronizedList(mutableListOf<WireWrite>())
        installConnectedGattHarness(writes, binaryLive = false)
        streamingReadyFlow().value = false
        manager.setPhoneHostSpeedCeiling(0.4f)
        verify(exactly = 0) { Log.w("OSSM", match<String> { it.contains("Live refusé") }) }

        // Live entered (streaming ready) and a target is sent: the real refusal remains.
        streamingReadyFlow().value = true
        manager.sendCommand(
            OssmCommand.Stream(
                positionPercent = 50,
                timeMs = 60,
                producer = LiveTargetProducer.TOUCH,
                receivedAtMs = 0,
            )
        )
        Thread.sleep(100)
        verify(atLeast = 1) { Log.w("OSSM", match<String> { it.contains("Live refusé") }) }
        assertFalse(writes.any { it.text?.startsWith("stream:") == true })
    }

    @Test
    fun `lowering the ceiling while Live streaming is entered still logs the refusal`() {
        val writes = Collections.synchronizedList(mutableListOf<WireWrite>())
        installConnectedGattHarness(writes, binaryLive = false)
        // setUp leaves streaming ready = true (Live mode entered).
        manager.setPhoneHostSpeedCeiling(0.4f)
        verify(atLeast = 1) { Log.w("OSSM", match<String> { it.contains("Live refusé") }) }
    }

    @Test
    fun `urgent STOP invalidates queued Live target behind a stalled GATT callback`() {
        val writes = Collections.synchronizedList(mutableListOf<WireWrite>())
        installConnectedGattHarness(writes)
        val fakeGatt: BluetoothGatt = privateField("gatt")
        var stalledArm = true
        manager.gattWriteAttemptObserver = { characteristic, payload, writeType ->
            writes += WireWrite(characteristic.uuid, payload)
            val isArmSpeed = payload.toString(Charsets.UTF_8) == "set:speed:100"
            if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT && stalledArm && isArmSpeed) {
                // Leave the serialized GATT worker waiting for its callback. STOP still fences
                // queued arm/target work; Android cannot safely issue a concurrent GATT write.
                stalledArm = false
            } else if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                gattCallback().onCharacteristicWrite(fakeGatt, characteristic, BluetoothGatt.GATT_SUCCESS)
            }
        }

        manager.sendCommand(
            OssmCommand.Stream(
                positionPercent = 50,
                timeMs = 60,
                producer = LiveTargetProducer.TOUCH,
                receivedAtMs = 0,
            )
        )
        Thread.sleep(100)
        manager.sendCommand(OssmCommand.Stop)

        awaitWrite(writes) { it.text == "set:speed:0" }
        assertFalse(writes.any { it.isLiveTarget })
    }

    private fun streamingReadyFlow(): MutableStateFlow<Boolean> = privateField("_streamingReady")
    private fun connectionStateFlow(): MutableStateFlow<BleConnectionState> = privateField("_connectionState")
    private fun scope(): CoroutineScope = privateField("scope")

    private fun installConnectedGattHarness(
        writes: MutableList<WireWrite>,
        binaryLive: Boolean = true,
        capabilityPresent: Boolean = binaryLive,
    ) {
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
        setPrivateField("liveSmoothCharacteristic", live.takeIf { binaryLive })
        setPrivateField("liveSmoothCapabilityPayload", validCapabilityPayload().takeIf { capabilityPresent })
        setPrivateField("postSetupDone", true)
        connectionStateFlow().value = BleConnectionState.Connected("OSSM", "test")
    }

    private fun mockWritableCharacteristic(uuid: UUID): BluetoothGattCharacteristic {
        val characteristic = mockk<BluetoothGattCharacteristic>(relaxed = true)
        every { characteristic.uuid } returns uuid
        every { characteristic.properties } returns (
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            )
        return characteristic
    }

    private fun awaitWrite(writes: List<WireWrite>, predicate: (WireWrite) -> Boolean) {
        val deadlineNanos = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadlineNanos) {
            if (writes.toList().any(predicate)) return
            Thread.sleep(10)
        }
        throw AssertionError("Expected GATT write was not observed. Writes=$writes")
    }

    private fun awaitLiveEndpointWrite(writes: List<WireWrite>, predicate: (WireWrite) -> Boolean) {
        val deadlineNanos = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadlineNanos) {
            if (writes.toList().any(predicate)) return
            // The production scheduler waits in 20 ms minimum intervals. Keep
            // the mocked monotonic clock moving while the interpolated path is
            // drained so the endpoint assertion observes the actual 200‰ wire
            // target instead of a frozen intermediate sample.
            nowMs.addAndGet(LiveSmoothProtocol.MINIMUM_INPUT_INTERVAL_MS)
            Thread.sleep(10)
        }
        throw AssertionError("Expected endpoint GATT write was not observed. Writes=$writes")
    }

    private fun validCapabilityPayload() = ResistanceProtocol.encodeCapability(
        ResistanceProtocol.Capability(
            capabilityBits = ResistanceProtocol.ALL_CAPABILITY_BITS,
            telemetryProperties = ResistanceProtocol.TELEMETRY_PROPERTIES,
            configProperties = ResistanceProtocol.CONFIG_PROPERTIES,
        )
    )

    private fun setPrivateField(name: String, value: Any?) {
        val field = BleManager::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(manager, value)
    }

    private fun gattCallback(): android.bluetooth.BluetoothGattCallback = privateField("gattCallback")

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(name: String): T {
        val field = BleManager::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(manager) as T
    }

    private data class WireWrite(val characteristicUuid: UUID, val payload: ByteArray) {
        val livePacket: LiveSmoothPacket?
            get() = payload.takeIf {
                characteristicUuid.toString() == LiveSmoothProtocol.CHARACTERISTIC_UUID
            }?.let(LiveSmoothProtocol::decode)
        val text: String?
            get() = payload.toString(Charsets.UTF_8).takeIf { value ->
                value.startsWith("set:") || value.startsWith("stream:")
            }
        val isLiveTarget: Boolean
            get() = characteristicUuid.toString() == LiveSmoothProtocol.CHARACTERISTIC_UUID ||
                text?.startsWith("stream:") == true
    }
}

