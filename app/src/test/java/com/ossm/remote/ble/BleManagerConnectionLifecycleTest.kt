package com.ossm.remote.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.usb.UsbSerialTransport
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BleManagerConnectionLifecycleTest {
    private val context = mockk<Context>()
    private val bluetoothManager = mockk<BluetoothManager>()
    private val bluetoothAdapter = mockk<BluetoothAdapter>()
    private val bleScanner = mockk<BluetoothLeScanner>(relaxed = true)
    private val device = mockk<BluetoothDevice>()
    private val firstGatt = mockk<BluetoothGatt>(relaxed = true)
    private val secondGatt = mockk<BluetoothGatt>(relaxed = true)
    private val callback = slot<BluetoothGattCallback>()
    private val scanCallback = slot<ScanCallback>()
    private val startedScanCallbacks = mutableListOf<ScanCallback>()

    private lateinit var manager: BleManager

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        mockkStatic(ContextCompat::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { ContextCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_GRANTED

        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED
        every { bluetoothManager.adapter } returns bluetoothAdapter
        every { bluetoothAdapter.bluetoothLeScanner } returns bleScanner
        every { bluetoothAdapter.isEnabled } returns true
        every { bluetoothAdapter.getRemoteDevice(ADDRESS) } returns device
        every {
            bleScanner.startScan(
                any<List<ScanFilter>>(),
                any<ScanSettings>(),
                capture(startedScanCallbacks),
            )
        } returns Unit
        every { device.name } returns "OSSM"
        every { device.address } returns ADDRESS
        every { firstGatt.device } returns device
        every { secondGatt.device } returns device
        every {
            device.connectGatt(
                any(),
                false,
                capture(callback),
                BluetoothDevice.TRANSPORT_LE
            )
        } returnsMany listOf(firstGatt, secondGatt)

        val usbTransport = mockk<UsbSerialTransport>(relaxed = true)
        every { usbTransport.ready } returns MutableStateFlow(false)
        every { usbTransport.frames } returns MutableSharedFlow()
        manager = BleManager(context, usbTransport)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        unmockkStatic(ContextCompat::class)
    }

    @Test
    fun `disconnect closes the active gatt and publishes disconnected before a platform callback`() {
        manager.connect(ADDRESS)

        manager.disconnect()

        assertEquals(BleConnectionState.Disconnected, manager.connectionState.value)
        verify { firstGatt.disconnect() }
        verify { firstGatt.close() }
    }

    @Test
    fun `late disconnect callback from a replaced gatt cannot close the current gatt`() {
        manager.connect(ADDRESS)
        manager.connect(ADDRESS)

        callback.captured.onConnectionStateChange(
            firstGatt,
            BluetoothGatt.GATT_SUCCESS,
            BluetoothProfile.STATE_DISCONNECTED
        )

        assertNotEquals(BleConnectionState.Disconnected, manager.connectionState.value)
        verify(exactly = 0) { secondGatt.close() }
    }

    @Test
    fun `known OSSM reconnect is permission safe and never starts motion`() {
        assertEquals(
            KnownDeviceReconnectAction.NONE,
            chooseKnownDeviceReconnectAction(
                hasConnectPermission = false,
                bluetoothEnabled = true,
                hasKnownAddress = true,
                connectionState = BleConnectionState.Disconnected,
            ),
        )
        assertEquals(
            KnownDeviceReconnectAction.NONE,
            chooseKnownDeviceReconnectAction(
                hasConnectPermission = true,
                bluetoothEnabled = false,
                hasKnownAddress = true,
                connectionState = BleConnectionState.Disconnected,
            ),
        )
        assertEquals(
            KnownDeviceReconnectAction.CONNECT_KNOWN_DEVICE,
            chooseKnownDeviceReconnectAction(
                hasConnectPermission = true,
                bluetoothEnabled = true,
                hasKnownAddress = true,
                connectionState = BleConnectionState.EmergencyStop,
            ),
        )
    }

    @Test
    fun `timed out earlier scan cannot stop a newer scan`() {
        assertEquals(false, isCurrentScanGeneration(expected = 4L, active = 5L))
        assertEquals(true, isCurrentScanGeneration(expected = 5L, active = 5L))
    }

    @Test
    fun `delayed failure from canceled scan cannot replace connecting or stop newer scan`() {
        manager.startScan()
        verify(timeout = SCAN_START_TIMEOUT_MS, exactly = 1) {
            bleScanner.startScan(
                any<List<ScanFilter>>(),
                any<ScanSettings>(),
                any<ScanCallback>(),
            )
        }
        val canceledCallback = startedScanCallbacks.single()

        manager.connect(ADDRESS)
        canceledCallback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)

        assertEquals(BleConnectionState.Connecting("OSSM"), manager.connectionState.value)
        verify(exactly = 2) { bleScanner.stopScan(canceledCallback) }

        manager.startScan()
        verify(timeout = SCAN_START_TIMEOUT_MS, exactly = 2) {
            bleScanner.startScan(
                any<List<ScanFilter>>(),
                any<ScanSettings>(),
                any<ScanCallback>(),
            )
        }
        val newerCallback = startedScanCallbacks.last()

        canceledCallback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)

        assertEquals(BleConnectionState.Scanning, manager.connectionState.value)
        verify(exactly = 2) { bleScanner.stopScan(canceledCallback) }
        verify(exactly = 1) { bleScanner.stopScan(newerCallback) }
    }

    @Test
    fun `successful CCCD bootstraps readable machine state before post setup`() {
        assertEquals(
            PostCccdAction.READ_STATE,
            choosePostCccdAction(
                status = BluetoothGatt.GATT_SUCCESS,
                hasStateCharacteristic = true,
                stateCharacteristicReadable = true,
            ),
        )
        assertEquals(
            PostCccdAction.CONTINUE_SETUP,
            choosePostCccdAction(
                status = BluetoothGatt.GATT_SUCCESS,
                hasStateCharacteristic = false,
                stateCharacteristicReadable = false,
            ),
        )
        assertEquals(
            PostCccdAction.FAIL,
            choosePostCccdAction(
                status = BluetoothGatt.GATT_FAILURE,
                hasStateCharacteristic = true,
                stateCharacteristicReadable = true,
            ),
        )
    }

    @Test
    fun `failed scan stops scanner resources and allows a fresh scan`() {
        manager.startScan()

        verify(timeout = SCAN_START_TIMEOUT_MS) {
            bleScanner.startScan(
                any<List<ScanFilter>>(),
                any<ScanSettings>(),
                capture(scanCallback),
            )
        }

        scanCallback.captured.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)

        assertEquals(
            BleConnectionState.Error("Scan échoué (${ScanCallback.SCAN_FAILED_INTERNAL_ERROR})"),
            manager.connectionState.value,
        )
        verify(exactly = 2) { bleScanner.stopScan(scanCallback.captured) }

        manager.startScan()

        verify(timeout = SCAN_START_TIMEOUT_MS, exactly = 2) {
            bleScanner.startScan(
                any<List<ScanFilter>>(),
                any<ScanSettings>(),
                any<ScanCallback>(),
            )
        }
    }

    @Test
    fun `setup commands prefer acknowledged writes while live targets stay low latency`() {
        val bothWriteModes = BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE

        assertEquals(
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            chooseGattWriteType(bothWriteModes, preferResponse = true),
        )
        assertEquals(
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            chooseGattWriteType(bothWriteModes, preferResponse = false),
        )
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
        const val SCAN_START_TIMEOUT_MS = 1_000L
    }
}
