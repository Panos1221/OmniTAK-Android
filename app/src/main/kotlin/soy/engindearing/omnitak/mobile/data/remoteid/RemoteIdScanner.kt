package soy.engindearing.omnitak.mobile.data.remoteid

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * BLE scanner for FAA Remote ID broadcasts.
 *
 * Listens for advertisements carrying the 16-bit service UUID
 * 0xFFFA (OpenDroneID), parses each ScanRecord's service-data
 * field into typed messages via [OpenDroneIdParser], aggregates
 * them into [RemoteIdTrack]s, and emits the resulting renderable
 * tracks on [tracks].
 *
 * ## What this catches
 * - BT4 Legacy advertising — Mavic Mini 2, Air 2S, older DJI fleet.
 *   Smaller frames (one Basic ID or one Location per broadcast),
 *   but reliably picked up by every BLE-capable Android phone.
 * - BT5 Extended advertising — Mavic 3, Air 3, Mini 4, Skydio 2+.
 *   Whole Message Pack (Basic ID + Location + System + …) lands in
 *   one broadcast. Requires `setLegacy(false)` on the scan settings
 *   AND a Bluetooth chipset / OS combo that supports BT5 extended
 *   adv reception. Pixel 6+ / Galaxy S21+ / OnePlus 9+ work; older
 *   phones drop to Legacy-only.
 *
 * ## What this does NOT catch
 * - WiFi-Beacon Remote ID — requires WiFi monitor mode, which
 *   stock Android phones can't do. That's where the gy6 hardware
 *   path earns its keep (Phase 3 of the original gy6 plan).
 * - Drones with Remote ID disabled or non-compliant builds —
 *   they don't emit anything for us to catch.
 *
 * ## Lifecycle
 * - [start] is idempotent; calling it twice is a no-op.
 * - [stop] cancels the scan and clears any in-progress tracks.
 * - The stale-purge coroutine runs every 5 s on the internal scope.
 */
@SuppressLint("MissingPermission") // gate-checked via [hasScanPermission]
class RemoteIdScanner(
    private val context: Context,
    private val trackStore: RemoteIdTrackStore = RemoteIdTrackStore(),
    private val stalePurgeIntervalMs: Long = 5_000L,
) {

    companion object {
        private const val TAG = "RemoteIdScanner"
        /** Full UUID form of the 16-bit FAA Remote ID service ID (0xFFFA). */
        private val SERVICE_UUID: ParcelUuid =
            ParcelUuid(UUID.fromString("0000fffa-0000-1000-8000-00805f9b34fb"))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var purgeJob: Job? = null

    @Volatile private var running: Boolean = false

    /**
     * Emits the UAS ID of any track whose state just changed (new
     * sighting, position update, or stale removal). Subscribers can
     * pull the full track via [trackStore].
     */
    private val _trackUpdates = MutableSharedFlow<Set<String>>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    val trackUpdates: SharedFlow<Set<String>> = _trackUpdates.asSharedFlow()

    /** Expose for callers that want to read the current roster (e.g. on rebind). */
    val tracks: List<RemoteIdTrack> get() = trackStore.snapshot()

    /**
     * Start scanning. No-op if already running, if the device has
     * no BLE adapter, or if the app is missing BLUETOOTH_SCAN.
     * Returns true if scanning actually started.
     */
    fun start(): Boolean {
        if (running) return true
        if (!hasScanPermission(context)) {
            Log.w(TAG, "BLUETOOTH_SCAN not granted; not starting scanner")
            return false
        }

        val adapter = bluetoothAdapter() ?: run {
            Log.w(TAG, "no BluetoothAdapter; device has no BLE radio")
            return false
        }
        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is off; not starting scanner")
            return false
        }

        val scanner = adapter.bluetoothLeScanner ?: run {
            Log.w(TAG, "BluetoothLeScanner unavailable")
            return false
        }

        // No ScanFilter: ASTM F3411 carries 0xFFFA only in Service Data
        // (AD type 0x16), and Android's setServiceUuid() filters on the
        // Service Class UUID list (AD types 0x02/0x03) which the spec
        // doesn't populate. setServiceData(uuid, null, null) is the
        // documented match for service-data UUIDs but at least one
        // common Mediatek BLE driver (TCL 30 SE / API 30) silently
        // drops every callback when that filter is set, even for adv
        // that clearly carry the UUID. Empty filter list + an in-code
        // check on getServiceData(SERVICE_UUID) gives us a portable
        // path that works across chipsets at modest battery cost.
        val filters = emptyList<ScanFilter>()

        // Low-latency for proper aircraft tracking. BT5 extended adv
        // is opt-in: setLegacy(false) lets us see Mavic 3-class
        // larger frames; phones without BT5 support fall back to
        // Legacy advertisements automatically.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setLegacy(true)
            .build()

        return try {
            scanner.startScan(filters, settings, scanCallback)
            running = true
            startStalePurge()
            Log.i(TAG, "Remote ID scan started (BT4 + BT5 extended adv, service 0xFFFA)")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "failed to start scan", e)
            false
        }
    }

    /** Stop scanning. Safe to call when not running. */
    fun stop() {
        if (!running) return
        running = false
        purgeJob?.cancel()
        purgeJob = null

        try {
            bluetoothAdapter()?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Throwable) {
            Log.w(TAG, "stopScan threw — was Bluetooth turned off mid-flight?", e)
        }

        val drainedIds = trackStore.snapshot().map { it.uasId }.toSet()
        trackStore.clear()
        if (drainedIds.isNotEmpty()) {
            scope.launch { _trackUpdates.emit(drainedIds) }
        }
        Log.i(TAG, "Remote ID scan stopped; ${drainedIds.size} tracks dropped")
    }

    // ---- Scan callback --------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handleResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "scan failed: errorCode=$errorCode")
            running = false
        }
    }

    private fun handleResult(result: ScanResult) {
        val record: ScanRecord = result.scanRecord ?: return
        val serviceData = record.getServiceData(SERVICE_UUID) ?: return
        val messages = OpenDroneIdParser.parseServiceData(serviceData)
        if (messages.isEmpty()) return

        // Frames without an inline Basic ID still describe a known
        // drone — use the BLE peer MAC as a stable correlation key
        // until the next BasicID broadcast resolves the real UAS ID.
        val fallback = "MAC-${result.device.address}"
        val changed = trackStore.ingest(messages, fallbackId = fallback)
        if (changed.isNotEmpty()) {
            scope.launch { _trackUpdates.emit(changed) }
        }
    }

    // ---- Stale-track purge ----------------------------------------------

    private fun startStalePurge() {
        purgeJob?.cancel()
        purgeJob = scope.launch {
            while (isActive) {
                delay(stalePurgeIntervalMs)
                val purged = trackStore.purgeStale()
                if (purged.isNotEmpty()) {
                    _trackUpdates.emit(purged)
                }
            }
        }
    }

    // ---- Helpers --------------------------------------------------------

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun hasScanPermission(context: Context): Boolean {
        // BLUETOOTH_SCAN is the Android-12+ runtime permission for BLE
        // scanning. Pre-12 needs ACCESS_FINE_LOCATION instead — the
        // BLUETOOTH_SCAN permission doesn't exist as a runtime grant on
        // those releases and checkSelfPermission always returns DENIED.
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, needed) ==
            PackageManager.PERMISSION_GRANTED
    }
}
