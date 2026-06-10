package soy.engindearing.omnitak.mobile.data.gyb

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.observer.ConnectionObserver
import soy.engindearing.omnitak.mobile.domain.ConnectionState
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.coroutines.resume

/**
 * BLE transport for the gyb_detect external drone detector. Mirrors
 * [MeshtasticBleClient] (Nordic Android BLE library) but the gyb stream is
 * plain newline-delimited JSON, not protobuf.
 *
 * Wire protocol:
 *   - Connect to [SERVICE_UUID].
 *   - Subscribe to the [DETECTION_UUID] notify characteristic.
 *   - The firmware (ghostnet-fw/src/gatt_link.cpp) chunks each JSON object
 *     into 20-byte notifications terminated by '\n'. We buffer inbound bytes
 *     and emit one [lines] item per complete object.
 *
 * iOS/Android both use this BLE GATT path — the legacy Bluetooth Classic SPP
 * link the ATAK plugin used can't work on iOS, so BLE is the common transport.
 */
@SuppressLint("MissingPermission")
class GybBleClient(context: Context) : BleManager(context) {

    data class GybScanResult(val name: String?, val address: String, val rssi: Int)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** One emission per reassembled JSON line off the GATT stream. */
    private val _lines = MutableSharedFlow<String>(extraBufferCapacity = 128)
    val lines: SharedFlow<String> = _lines.asSharedFlow()

    private val _scanResults = MutableSharedFlow<GybScanResult>(extraBufferCapacity = 64)
    val scanResults: SharedFlow<GybScanResult> = _scanResults.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val bluetoothAdapter: BluetoothAdapter? = run {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        mgr?.adapter
    }

    private var detectionChar: BluetoothGattCharacteristic? = null
    private val rxBuffer = ByteArrayOutputStream()
    private var scanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = runCatching { result.device.name }.getOrNull() ?: result.scanRecord?.deviceName
            _scanResults.tryEmit(GybScanResult(name, result.device.address, result.rssi))
        }
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "gyb BLE scan failed: $errorCode")
        }
    }

    init {
        setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {
                _state.value = ConnectionState.Connecting(device.address)
            }
            override fun onDeviceConnected(device: BluetoothDevice) { /* wait for ready */ }
            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                _state.value = ConnectionState.Failed("connect failed: $reason")
            }
            override fun onDeviceReady(device: BluetoothDevice) {
                _state.value = ConnectionState.Connected(device.address, useTLS = false)
            }
            override fun onDeviceDisconnecting(device: BluetoothDevice) {}
            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                synchronized(rxBuffer) { rxBuffer.reset() }
                if (_state.value !is ConnectionState.Failed) {
                    _state.value = ConnectionState.Disconnected
                }
            }
        })
    }

    // region Scan

    fun startScan(timeoutMs: Long = 12_000) {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            Log.w(TAG, "no BluetoothLeScanner — Bluetooth disabled?")
            return
        }
        if (scanning) return
        scanning = true
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }
            .onFailure { Log.w(TAG, "startScan threw: ${it.message}"); scanning = false; return }
        scope.launch { delay(timeoutMs); stopScan() }
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    // endregion

    // region Connect

    suspend fun connectToAddress(address: String): Boolean {
        val adapter = bluetoothAdapter ?: return false
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return false
        stopScan()
        _state.value = ConnectionState.Connecting(address)
        return suspendCancellableCoroutine { cont ->
            connect(device)
                .timeout(CONNECT_TIMEOUT_MS)
                .retry(2, 200)
                .useAutoConnect(false)
                .done { if (cont.isActive) cont.resume(true) }
                .fail { _, status ->
                    Log.w(TAG, "gyb BLE connect failed: status=$status")
                    _state.value = ConnectionState.Failed("status=$status")
                    if (cont.isActive) cont.resume(false)
                }
                .enqueue()
        }
    }

    suspend fun disconnectClean() {
        suspendCancellableCoroutine<Unit> { cont ->
            disconnect()
                .done { if (cont.isActive) cont.resume(Unit) }
                .fail { _, _ -> if (cont.isActive) cont.resume(Unit) }
                .enqueue()
        }
        _state.value = ConnectionState.Disconnected
    }

    // endregion

    // region BleManager hooks

    override fun getMinLogPriority(): Int = Log.VERBOSE
    override fun log(priority: Int, message: String) {
        if (priority >= Log.INFO) Log.println(priority, TAG, message)
    }

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service: BluetoothGattService = gatt.getService(SERVICE_UUID) ?: return false
        detectionChar = service.getCharacteristic(DETECTION_UUID)
        return detectionChar != null
    }

    override fun initialize() {
        requestMtu(REQUESTED_MTU)
            .with { _, mtu -> Log.i(TAG, "gyb MTU negotiated: $mtu") }
            .enqueue()

        detectionChar?.let { ch ->
            setNotificationCallback(ch).with { _, data ->
                data.value?.let { ingest(it) }
            }
            enableNotifications(ch)
                .fail { _, status -> Log.w(TAG, "gyb notify enable failed: $status") }
                .enqueue()
        }
    }

    override fun onServicesInvalidated() {
        detectionChar = null
        synchronized(rxBuffer) { rxBuffer.reset() }
    }

    // endregion

    /** Accumulate notify chunks; emit each complete newline-delimited line. */
    private fun ingest(bytes: ByteArray) {
        val completed = mutableListOf<String>()
        synchronized(rxBuffer) {
            for (b in bytes) {
                if (b == '\n'.code.toByte()) {
                    val line = rxBuffer.toString(Charsets.UTF_8.name()).trim()
                    rxBuffer.reset()
                    if (line.isNotEmpty()) completed.add(line)
                } else {
                    rxBuffer.write(b.toInt())
                }
            }
            // Guard against a runaway buffer if a '\n' never arrives.
            if (rxBuffer.size() > 8192) rxBuffer.reset()
        }
        completed.forEach { _lines.tryEmit(it) }
    }

    companion object {
        private const val TAG = "GybBle"
        /** gyb drone-detector GATT service — keep in lockstep with the firmware. */
        val SERVICE_UUID: UUID = UUID.fromString("e3f1b8a0-9c1d-4a2e-9b00-67796236d701")
        val DETECTION_UUID: UUID = UUID.fromString("e3f1b8a0-9c1d-4a2e-9b00-67796236d702")
        const val REQUESTED_MTU: Int = 247
        private const val CONNECT_TIMEOUT_MS: Long = 15_000
    }
}
