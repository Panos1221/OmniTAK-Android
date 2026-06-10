package soy.engindearing.omnitak.mobile.domain

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.gyb.GybBleClient
import soy.engindearing.omnitak.mobile.data.gyb.GybDetectionParser
import soy.engindearing.omnitak.mobile.data.gyb.GybMessage
import soy.engindearing.omnitak.mobile.data.remoteid.RemoteIdToCoTConverter
import soy.engindearing.omnitak.mobile.data.remoteid.RemoteIdTrackStore

/**
 * Lifecycle + glue for the external gyb_detect drone detector. Owns the BLE
 * client, parses its JSON stream, and funnels drone detections through the
 * existing [RemoteIdTrackStore] + [RemoteIdToCoTConverter] into [ContactStore]
 * — the same sink the on-device FAA Remote ID scanner uses. Because both share
 * the `RID-{uasId}` UID, a drone seen over WiFi (gyb) and BLE (phone) merges
 * into one contact.
 *
 * Mirrors the iOS GybManager. Constructed lazily in OmniTAKApp and driven on/
 * off by the `gybDetectorEnabled` user pref, exactly like [MeshtasticCoTBridge]
 * and the Remote ID scanner.
 */
class GybManager(
    context: Context,
    private val cotSink: (CoTEvent) -> Unit,
    private val cotRemove: (String) -> Unit,
    private val scope: CoroutineScope,
    // Shared with RemoteIdScanner (provided by OmniTAKApp) so a drone heard
    // by BOTH sensors lives on one track: either source refreshes lastSeen,
    // and the stale purge only fires when both have gone silent. Two private
    // stores used to run competing purge loops that deleted each other's
    // still-live markers.
    private val trackStore: RemoteIdTrackStore,
    // Last-connected sensor address, persisted in UserPrefs by OmniTAKApp.
    // Read on start() for auto-reconnect; written on every UI connect().
    // Lambdas (not the store itself) keep this class free of DataStore.
    private val lastDeviceAddress: () -> String = { "" },
    private val persistLastDevice: (String) -> Unit = {},
) {
    val client = GybBleClient(context)
    val connectionState: StateFlow<ConnectionState> get() = client.state

    private val _scanResults = MutableStateFlow<List<GybBleClient.GybScanResult>>(emptyList())
    val scanResults: StateFlow<List<GybBleClient.GybScanResult>> = _scanResults.asStateFlow()

    private val _batteryLevel = MutableStateFlow<Double?>(null)
    val batteryLevel: StateFlow<Double?> = _batteryLevel.asStateFlow()

    private val _droneCount = MutableStateFlow(0)
    val droneCount: StateFlow<Int> = _droneCount.asStateFlow()

    private val _deviceModel = MutableStateFlow<String?>(null)
    val deviceModel: StateFlow<String?> = _deviceModel.asStateFlow()

    private val lastRecvMethod = mutableMapOf<String, Int>()

    private var collectJob: Job? = null
    private var scanCollectJob: Job? = null
    private var purgeJob: Job? = null
    private var reconnectJob: Job? = null
    private var running = false

    // Address the reconnect loop targets. Seeded from the persisted
    // last-device pref on start(); replaced by every UI connect().
    @Volatile private var targetAddress: String? = null

    // True while the operator wants a live link (set by start()-with-saved-
    // address and by connect(); cleared by disconnect() and stop()). The
    // reconnect loop only acts while this is true, so a deliberate
    // "Disconnect" tap doesn't fight an instant auto-reconnect.
    @Volatile private var wantConnection = false

    /** Start the stream collectors + purge ticker, and auto-reconnect to
     *  the last-connected sensor if one is persisted. Idempotent. */
    fun start() {
        if (running) return
        running = true
        collectJob = scope.launch {
            client.lines.collect { line -> handleLine(line) }
        }
        scanCollectJob = scope.launch {
            client.scanResults.collect { r ->
                val current = _scanResults.value
                if (current.none { it.address == r.address }) {
                    _scanResults.value = current + r
                } else {
                    _scanResults.value = current.map { if (it.address == r.address) r else it }
                }
            }
        }
        purgeJob = scope.launch {
            while (isActive) {
                delay(5_000)
                purge()
            }
        }
        // Auto-reconnect: only when the operator paired a sensor before.
        // Runs strictly inside start()/stop(), i.e. gated on the Settings
        // toggle — iOS used to reconnect even when the feature was off,
        // which is the bug class we're deliberately not porting.
        val saved = lastDeviceAddress().takeIf { it.isNotBlank() }
        targetAddress = saved
        wantConnection = saved != null
        launchReconnectLoop()
    }

    /** Stop everything and drop all gyb-sourced markers. */
    fun stop() {
        // Guard matters now that trackStore is shared: a redundant stop()
        // (initial pref emission at app start) must not clearAll() tracks
        // the phone's Remote ID scanner is still feeding.
        if (!running) return
        running = false
        wantConnection = false
        collectJob?.cancel(); collectJob = null
        scanCollectJob?.cancel(); scanCollectJob = null
        purgeJob?.cancel(); purgeJob = null
        reconnectJob?.cancel(); reconnectJob = null
        scope.launch { client.disconnectClean() }
        clearAll()
    }

    // region UI actions

    fun scan() {
        _scanResults.value = emptyList()
        client.startScan()
    }

    fun stopScan() {
        client.stopScan()
    }

    fun connect(address: String) {
        targetAddress = address
        wantConnection = true
        persistLastDevice(address)
        scope.launch { client.connectToAddress(address) }
    }

    fun disconnect() {
        // Deliberate operator action — park the reconnect loop until the
        // next connect() (or app restart with the toggle still on).
        wantConnection = false
        scope.launch { client.disconnectClean() }
    }

    // endregion

    /**
     * Reconnect supervisor. Watches the BLE connection state and, while a
     * link is wanted, re-issues connect() after an unexpected drop with
     * exponential backoff (first retry immediate, then 2s → 4s → … → 30s
     * cap, reset on success). The firmware restarts advertising after a
     * disconnect, so the sensor is always re-discoverable — the phone just
     * has to keep trying. Cancelled by stop() (Settings toggle off).
     */
    private fun launchReconnectLoop() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var backoffMs = 0L
            client.state.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> backoffMs = 0L
                    is ConnectionState.Connecting -> { /* attempt in flight */ }
                    is ConnectionState.Disconnected, is ConnectionState.Failed -> {
                        val addr = targetAddress
                        if (wantConnection && addr != null) {
                            if (backoffMs > 0) delay(backoffMs)
                            backoffMs = (backoffMs * 2).coerceIn(2_000L, 30_000L)
                            // Re-check: a Disconnect tap, toggle-off, or a
                            // UI-initiated connect (already Connecting) may
                            // have landed during the backoff sleep.
                            val idle = client.state.value is ConnectionState.Disconnected ||
                                client.state.value is ConnectionState.Failed
                            if (wantConnection && running && idle) {
                                client.connectToAddress(targetAddress ?: addr)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleLine(line: String) {
        when (val msg = GybDetectionParser.parse(line) ?: return) {
            is GybMessage.DeviceInfo -> _deviceModel.value =
                if (msg.serialNumber.isNotEmpty()) "${msg.model} · ${msg.serialNumber}" else msg.model
            is GybMessage.Battery -> _batteryLevel.value = msg.level
            is GybMessage.Drone -> {
                lastRecvMethod[msg.uasId] = msg.recvMethod
                val changed = trackStore.ingest(msg.messages, fallbackId = msg.uasId)
                for (id in changed) {
                    val track = trackStore.get(id) ?: continue
                    val note = " / via gyb (${GybDetectionParser.recvMethodLabel(msg.recvMethod)})"
                    // Emit the drone and/or the operator (pilot) marker.
                    for (ev in RemoteIdToCoTConverter.toCoTs(track)) {
                        cotSink(ev.copy(remarks = ev.remarks + note))
                    }
                }
                _droneCount.value = trackStore.snapshot().size
            }
        }
    }

    private fun purge() {
        val removed = trackStore.purgeStale()
        for (id in removed) {
            cotRemove("RID-$id")
            cotRemove("RID-OP-$id")
            lastRecvMethod.remove(id)
        }
        _droneCount.value = trackStore.snapshot().size
    }

    private fun clearAll() {
        val ids = trackStore.snapshot().map { it.uasId }
        trackStore.clear()
        ids.forEach { cotRemove("RID-$it"); cotRemove("RID-OP-$it") }
        _droneCount.value = 0
        _batteryLevel.value = null
        _deviceModel.value = null
        lastRecvMethod.clear()
    }
}
