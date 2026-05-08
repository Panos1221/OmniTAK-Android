package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.data.CertVault
import soy.engindearing.omnitak.mobile.data.ChatXml
import soy.engindearing.omnitak.mobile.data.CoTParser
import soy.engindearing.omnitak.mobile.data.LocationProvider
import soy.engindearing.omnitak.mobile.data.TAKConnection
import soy.engindearing.omnitak.mobile.data.TAKServer
import soy.engindearing.omnitak.mobile.data.TAKServerStore
import soy.engindearing.omnitak.mobile.data.UserPrefsStore

/**
 * Application-scoped TAK server registry. Exposes [servers] and
 * [activeServer] as StateFlows so Compose screens can observe reactively.
 *
 * Mirrors iOS ServerManager behavior:
 *  - addServer is idempotent on host + port + protocol (iOS #42)
 *  - toggling/disabling the active server hands off to the next enabled
 *    server so the UI doesn't keep pointing at a disabled one (iOS #41)
 *  - newly added enabled server becomes active when no enabled active exists
 */
class ServerManager(
    private val store: TAKServerStore,
    private val contactStore: ContactStore? = null,
    private val chatStore: ChatStore? = null,
    private val certVault: CertVault? = null,
    private val userPrefsStore: UserPrefsStore? = null,
    private val locationProvider: LocationProvider? = null,
    // Issue #11 — wired from OmniTAKApp to read BatteryManager. Lambda
    // (not Context) keeps this class headless for unit tests.
    private val batteryProvider: () -> Int? = { null },
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _servers = MutableStateFlow<List<TAKServer>>(emptyList())
    val servers: StateFlow<List<TAKServer>> = _servers.asStateFlow()

    private val _activeServer = MutableStateFlow<TAKServer?>(null)
    val activeServer: StateFlow<TAKServer?> = _activeServer.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // GAP-030c — real ATAK-style status-bar counters. The status bar
    // chips ↓ / ↑ on MapScreen previously read hardcoded zeros, which
    // made the app look silent even when CoT was flowing both ways.
    private val _messagesReceived = MutableStateFlow(0)
    val messagesReceived: StateFlow<Int> = _messagesReceived.asStateFlow()

    private val _messagesSent = MutableStateFlow(0)
    val messagesSent: StateFlow<Int> = _messagesSent.asStateFlow()

    private var currentConnection: TAKConnection? = null
    private var connectionCollectorJob: kotlinx.coroutines.Job? = null
    private var receivedCollectorJob: kotlinx.coroutines.Job? = null
    private var pliBroadcaster: SelfPositionBroadcaster? = null

    init { scope.launch { hydrate() } }

    /** Tear down any existing connection and open a new one to [server]. */
    fun connect(server: TAKServer) {
        disconnect()
        val conn = TAKConnection(server, certVault)
        currentConnection = conn
        // Reset per-session counters so the status bar reflects this
        // connection only, not the cumulative app lifetime.
        _messagesReceived.value = 0
        _messagesSent.value = 0
        connectionCollectorJob = scope.launch {
            conn.state.collect { state ->
                _connectionState.value = state
                if (state is ConnectionState.Connected) startPliBroadcast() else stopPliBroadcast()
            }
        }
        receivedCollectorJob = scope.launch {
            conn.received.collect { xml ->
                _messagesReceived.value = _messagesReceived.value + 1
                // A frame is either a chat event or a contact/marker event,
                // depending on the CoT type. Parsing chat first is cheap
                // (string probe) and avoids double-ingesting as a contact.
                val chat = ChatXml.parse(xml)
                if (chat != null) {
                    chatStore?.ingest(chat)
                } else {
                    CoTParser.parse(xml)?.let { contactStore?.ingest(it) }
                }
            }
        }
        conn.connect()
    }

    /**
     * Re-open a connection if there's an enabled active server but no
     * live socket. Called from MainActivity's ON_RESUME hook so that
     * coming back from the background (where Android may have killed
     * the read loop after Doze / app-standby) recovers without the
     * operator needing to tap play. Issue #6.
     */
    fun reconnectIfNeeded() {
        val state = _connectionState.value
        if (state is ConnectionState.Connected || state is ConnectionState.Connecting) return
        val target = _activeServer.value ?: return
        if (!target.enabled) return
        connect(target)
    }

    /** Send a CoT XML payload on the active connection. */
    suspend fun sendCoT(xml: String): Boolean {
        val ok = currentConnection?.send(xml) ?: false
        if (ok) _messagesSent.value = _messagesSent.value + 1
        return ok
    }

    /** Disconnect the current connection if any. */
    fun disconnect() {
        stopPliBroadcast()
        currentConnection?.disconnect()
        currentConnection = null
        connectionCollectorJob?.cancel()
        connectionCollectorJob = null
        receivedCollectorJob?.cancel()
        receivedCollectorJob = null
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun startPliBroadcast() {
        if (pliBroadcaster != null || userPrefsStore == null) return
        // GAP-030b — thread the live FusedLocationProviderClient fix
        // through so PPLI carries real GPS instead of a SF default.
        // Empty StateFlow when no provider is wired (older callers/tests).
        val fixFlow = locationProvider?.fix
            ?: kotlinx.coroutines.flow.MutableStateFlow(null)
        pliBroadcaster = SelfPositionBroadcaster(
            scope = scope,
            prefsStore = userPrefsStore,
            sendCoT = { xml -> sendCoT(xml) },
            locationFix = fixFlow,
            batteryProvider = batteryProvider,
        ).also { it.start() }
    }

    private fun stopPliBroadcast() {
        pliBroadcaster?.stop()
        pliBroadcaster = null
    }

    private suspend fun hydrate() {
        var initial: List<TAKServer> = emptyList()
        store.servers.collect { loaded ->
            _servers.value = loaded
            if (initial.isEmpty() && loaded.isNotEmpty()) {
                initial = loaded
                val activeId = peekActiveId()
                val active = loaded.firstOrNull { it.id == activeId }
                    ?: loaded.firstOrNull { it.enabled }
                    ?: loaded.firstOrNull()
                _activeServer.value = active
                // Resume the previously-active enabled server on cold
                // launch. Without this the operator has to hit play after
                // every app restart even though the server entry is still
                // saved. The `currentConnection == null` guard means
                // DataPackageBootstrap can race ahead and we won't
                // double-connect on a fresh-import launch.
                if (active != null && active.enabled && currentConnection == null) {
                    connect(active)
                }
            }
        }
    }

    private suspend fun peekActiveId(): String? = store.activeServerId.firstOrNull()

    fun addServer(server: TAKServer): TAKServer {
        val existing = _servers.value.firstOrNull { it.matchesEndpoint(server) }
        if (existing != null) return existing

        val updated = _servers.value + server
        _servers.value = updated
        val becomingActive = server.enabled &&
            (_activeServer.value == null || _activeServer.value?.enabled != true)
        if (becomingActive) {
            _activeServer.value = server
            persistActive(server.id)
        }
        persist(updated)
        // Auto-connect when this server becomes active and nothing is on the wire.
        // Users expect "I added a server, it works" — the explicit play button is
        // for switching between configured servers, not for first-use bootstrap.
        if (becomingActive && currentConnection == null) {
            connect(server)
        }
        return server
    }

    fun updateServer(server: TAKServer) {
        val idx = _servers.value.indexOfFirst { it.id == server.id }
        if (idx < 0) return
        val updated = _servers.value.toMutableList().apply { this[idx] = server }
        _servers.value = updated
        if (_activeServer.value?.id == server.id) _activeServer.value = server
        persist(updated)
    }

    fun deleteServer(id: String) {
        val updated = _servers.value.filterNot { it.id == id }
        _servers.value = updated
        if (_activeServer.value?.id == id) {
            val next = updated.firstOrNull { it.enabled }
            _activeServer.value = next
            persistActive(next?.id)
        }
        persist(updated)
    }

    fun toggleEnabled(id: String) {
        val updated = _servers.value.map {
            if (it.id == id) it.copy(enabled = !it.enabled) else it
        }
        _servers.value = updated

        val toggled = updated.firstOrNull { it.id == id }
        val wasActive = _activeServer.value?.id == id
        if (wasActive) {
            if (toggled?.enabled == true) {
                _activeServer.value = toggled
            } else {
                disconnect()
                val next = updated.firstOrNull { it.enabled }
                _activeServer.value = next
                persistActive(next?.id)
            }
        }
        persist(updated)
        // Switch ON should put the server on the wire, not just permit it.
        if (toggled?.enabled == true && currentConnection == null) {
            if (!wasActive) {
                _activeServer.value = toggled
                persistActive(toggled.id)
            }
            connect(toggled)
        }
    }

    fun setActive(id: String) {
        val target = _servers.value.firstOrNull { it.id == id } ?: return
        _activeServer.value = target
        persistActive(target.id)
    }

    private fun persist(list: List<TAKServer>) {
        scope.launch { store.saveServers(list) }
    }

    private fun persistActive(id: String?) {
        scope.launch { store.saveActiveServerId(id) }
    }
}
