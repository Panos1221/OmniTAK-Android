package soy.engindearing.omnitak.mobile.data.uas

import android.util.Log
import io.dronefleet.mavlink.MavlinkConnection as DroneFleetConnection
import io.dronefleet.mavlink.MavlinkMessage
import io.dronefleet.mavlink.common.BatteryStatus
import io.dronefleet.mavlink.common.CommandLong
import io.dronefleet.mavlink.common.GlobalPositionInt
import io.dronefleet.mavlink.common.GpsRawInt
import io.dronefleet.mavlink.common.HomePosition
import io.dronefleet.mavlink.common.VfrHud
import io.dronefleet.mavlink.common.WindCov
import io.dronefleet.mavlink.ardupilotmega.Wind as ArduWind
import io.dronefleet.mavlink.common.MavCmd
import io.dronefleet.mavlink.common.MavFrame
import io.dronefleet.mavlink.common.MavMissionResult
import io.dronefleet.mavlink.common.MavMissionType
import io.dronefleet.mavlink.common.MissionAck
import io.dronefleet.mavlink.common.MissionClearAll
import io.dronefleet.mavlink.common.MissionCount
import io.dronefleet.mavlink.common.MissionItemInt
import io.dronefleet.mavlink.common.MissionItemReached
import io.dronefleet.mavlink.common.MissionRequestInt
import io.dronefleet.mavlink.common.ParamValue
import io.dronefleet.mavlink.common.Statustext
import io.dronefleet.mavlink.common.SysStatus
import io.dronefleet.mavlink.minimal.Heartbeat
import io.dronefleet.mavlink.minimal.MavAutopilot
import io.dronefleet.mavlink.minimal.MavModeFlag
import io.dronefleet.mavlink.minimal.MavState
import io.dronefleet.mavlink.minimal.MavType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * MAVLink 2 client for a single connected UAS, per the RAS-A MAVLink
 * Control Link Interoperability Profile v1.2 (the DoD spec the official
 * ATAK UAS Tool 13.0 implements).
 *
 * Transports:
 *  - **UDP** (default, RAS-A IOP §Networking) — vehicle Wi-Fi / SITL.
 *  - **TCP** — useful for SSH-tunneled SITL on a lab server or any
 *    network where UDP is filtered (consumer routers often block
 *    inter-VLAN UDP; we hit this dev-side talking to the Ubuntu
 *    trainer's PX4 SITL).
 *
 * Framing: MAVLink 2 (0xFD magic, CRC-MCRF4XX) — io.dronefleet.mavlink
 * handles the parsing/serialization; we own the socket and the loop.
 *
 * Lifecycle:
 *  - [connect] opens the socket, fires off our own HEARTBEAT on a 1 Hz
 *    timer so the drone sees us as a valid GCS, and reads incoming
 *    packets in a coroutine — each parsed message updates [state].
 *  - [sendCommand] wraps COMMAND_LONG (arm / takeoff / RTL).
 *  - [disconnect] cancels the loops and closes the socket.
 *
 * Threading: socket reads block; we keep them on Dispatchers.IO. State
 * updates flow into the [MutableStateFlow] which UI collects on Main.
 */
class MavlinkConnection {

    enum class Transport { UDP, TCP }

    private val _state = MutableStateFlow(DroneState())
    val state: StateFlow<DroneState> = _state.asStateFlow()

    private val _missionEvents = MutableSharedFlow<MissionEvent>(extraBufferCapacity = 16)
    val missionEvents: SharedFlow<MissionEvent> = _missionEvents.asSharedFlow()

    sealed interface MissionEvent {
        data class RequestInt(val seq: Int) : MissionEvent
        data class Ack(val result: MavMissionResult?) : MissionEvent
        data class ItemReached(val seq: Int) : MissionEvent
    }

    /** PARAM_VALUE responses — UASManager listens to populate the
     *  failsafe params map. */
    private val _paramValues = MutableSharedFlow<Pair<String, Float>>(extraBufferCapacity = 32)
    val paramValues: SharedFlow<Pair<String, Float>> = _paramValues.asSharedFlow()

    // Active transport state — exactly one of the two is non-null while
    // connected. We branch on transport in readLoop / sendRaw rather than
    // unifying behind a stream wrapper because Datagram and Socket have
    // fundamentally different semantics (packet vs stream).
    private var transport: Transport? = null
    private var udpSocket: DatagramSocket? = null
    private var udpAddress: InetAddress? = null
    private var udpPort: Int = DEFAULT_DRONE_PORT
    private var tcpSocket: Socket? = null
    private var tcpIn: InputStream? = null
    private var tcpOut: OutputStream? = null
    /** Single long-lived dronefleet Connection for TCP — handles framing
     *  across stream boundaries (TCP doesn't preserve message edges). */
    private var tcpConn: DroneFleetConnection? = null

    private var scope: CoroutineScope? = null
    private var readJob: Job? = null
    private var heartbeatJob: Job? = null

    /** Our GCS system/component id — RAS-A IOP says ground stations use sysid 255. */
    private val gcsSystemId = 255
    private val gcsComponentId = 190 // MAV_COMP_ID_MISSIONPLANNER

    /** UDP convenience overload (backwards-compatible). */
    fun connect(host: String, port: Int = DEFAULT_DRONE_PORT) =
        connect(Transport.UDP, host, port)

    /**
     * Open the link and start the read + heartbeat loops.
     * [host]/[port] is where the drone (or SITL) is listening.
     */
    fun connect(transport: Transport, host: String, port: Int = DEFAULT_DRONE_PORT) {
        disconnect() // idempotent
        this.transport = transport

        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s

        when (transport) {
            Transport.UDP -> {
                udpAddress = InetAddress.getByName(host)
                udpPort = port
                udpSocket = DatagramSocket().apply { soTimeout = 1_000 }
                Log.i(TAG, "MAVLink UDP open → $host:$port (GCS sysid=$gcsSystemId)")
            }
            Transport.TCP -> {
                // Run the connect in the IO scope so we don't block the
                // caller (Compose Main) — the read loop waits for the
                // socket to come up.
                s.launch { openTcp(host, port) }
            }
        }

        readJob = s.launch { readLoop() }
        heartbeatJob = s.launch { heartbeatLoop() }
    }

    private suspend fun openTcp(host: String, port: Int) = withContext(Dispatchers.IO) {
        try {
            val sock = Socket()
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(host, port), 5_000)
            tcpSocket = sock
            tcpIn = sock.getInputStream()
            tcpOut = sock.getOutputStream()
            tcpConn = DroneFleetConnection.create(tcpIn!!, tcpOut!!)
            Log.i(TAG, "MAVLink TCP open → $host:$port (GCS sysid=$gcsSystemId)")
        } catch (t: Throwable) {
            Log.w(TAG, "TCP connect failed: ${t.message}")
            // Surface to UI as a status line so the operator knows.
            _state.update { st ->
                st.copy(
                    recentStatusText = (st.recentStatusText + "TCP connect failed: ${t.message}")
                        .takeLast(8)
                )
            }
        }
    }

    fun disconnect() {
        readJob?.cancel()
        heartbeatJob?.cancel()
        scope?.cancel()
        readJob = null
        heartbeatJob = null
        scope = null

        try { udpSocket?.close() } catch (_: Throwable) {}
        try { tcpSocket?.close() } catch (_: Throwable) {}
        udpSocket = null
        tcpSocket = null
        tcpIn = null
        tcpOut = null
        tcpConn = null
        transport = null

        // Reset the StateFlow so the UI's isConnected() flips to false
        // immediately. Without this, lastHeartbeat is still recent and
        // the UI's CONNECTED banner stays green for ~5s after disconnect.
        _state.value = DroneState()
        Log.i(TAG, "MAVLink disconnected")
    }

    /**
     * Send a COMMAND_LONG. Returns immediately; the COMMAND_ACK arrives
     * asynchronously via the read loop (we surface it in
     * [DroneState.recentStatusText] for now — fast-follow: dedicated
     * ack StateFlow).
     */
    suspend fun sendCommand(
        command: MavCmd,
        p1: Float = 0f, p2: Float = 0f, p3: Float = 0f, p4: Float = 0f,
        p5: Float = 0f, p6: Float = 0f, p7: Float = 0f,
    ) {
        val targetSys = _state.value.systemId ?: 1
        val targetComp = _state.value.componentId ?: 1
        val msg = CommandLong.builder()
            .targetSystem(targetSys).targetComponent(targetComp)
            .command(command).confirmation(0)
            .param1(p1).param2(p2).param3(p3).param4(p4)
            .param5(p5).param6(p6).param7(p7).build()
        sendRaw(msg)
    }

    // ---------------------------------------------------------------- loops

    private suspend fun readLoop() {
        while (scope?.isActive == true) {
            try {
                when (transport) {
                    Transport.UDP -> readOneUdp()
                    Transport.TCP -> readOneTcp()
                    null -> { delay(50) /* not connected yet */ }
                }
            } catch (_: java.net.SocketTimeoutException) {
                // expected — we set SO_TIMEOUT on UDP so we can check cancellation
            } catch (t: Throwable) {
                if (scope?.isActive == true) {
                    Log.w(TAG, "read error: ${t.javaClass.simpleName}: ${t.message}")
                    if (transport == Transport.TCP) {
                        // TCP errors are usually fatal (socket closed).
                        // Surface and back off to avoid hot-loop on EOF.
                        delay(500)
                    }
                }
            }
        }
    }

    private fun readOneUdp() {
        val sock = udpSocket ?: return
        val buf = ByteArray(280) // MAVLink 2 max frame size
        val packet = DatagramPacket(buf, buf.size)
        sock.receive(packet)
        // First receive also tells us the drone's source port if the
        // caller passed the wrong destination port (SITL is chatty about
        // this — most ground stations rely on it).
        if (udpAddress == null || packet.address != udpAddress) {
            udpAddress = packet.address
            udpPort = packet.port
        }
        val inStream = ByteArrayInputStream(packet.data, 0, packet.length)
        val conn = DroneFleetConnection.create(inStream, ByteArrayOutputStream())
        var msg: MavlinkMessage<*>? = try { conn.next() } catch (_: Throwable) { return }
        while (msg != null) {
            apply(msg)
            msg = try { conn.next() } catch (_: Throwable) { null }
        }
    }

    private fun readOneTcp() {
        val conn = tcpConn ?: return
        // dronefleet's conn.next() blocks on the InputStream and returns
        // exactly one frame; null/EOFException means the stream is closed.
        val msg = conn.next() ?: throw java.io.EOFException("TCP stream closed")
        apply(msg)
    }

    private suspend fun heartbeatLoop() {
        val hb = Heartbeat.builder()
            .type(MavType.MAV_TYPE_GCS)
            .autopilot(MavAutopilot.MAV_AUTOPILOT_INVALID)
            .systemStatus(MavState.MAV_STATE_ACTIVE)
            .mavlinkVersion(3) // MAVLink 2
            .build()
        while (scope?.isActive == true) {
            try {
                sendRaw(hb)
            } catch (t: Throwable) {
                if (scope?.isActive == true) {
                    Log.w(TAG, "heartbeat send error: ${t.message}")
                }
            }
            delay(1_000) // 1 Hz per RAS-A IOP
        }
    }

    private fun apply(msg: MavlinkMessage<*>) {
        val sys = msg.originSystemId
        val comp = msg.originComponentId
        when (val body = msg.payload) {
            is Heartbeat -> _state.update { st ->
                val nowArmed = body.baseMode().flagsEnabled(MavModeFlag.MAV_MODE_FLAG_SAFETY_ARMED)
                val now = System.currentTimeMillis()
                st.copy(
                    systemId = sys,
                    componentId = comp,
                    autopilot = body.autopilot().entry()?.name,
                    vehicleType = body.type().entry()?.name,
                    flightMode = decodeMode(body),
                    armed = nowArmed,
                    // First time armed flips true: stamp armedAtMs so the
                    // HUD's flight-time counter starts. Cleared on disarm
                    // so the next takeoff starts a fresh count.
                    armedAtMs = when {
                        nowArmed && st.armed != true -> now
                        !nowArmed -> null
                        else -> st.armedAtMs
                    },
                    lastHeartbeat = now,
                )
            }
            is GlobalPositionInt -> _state.update { st ->
                val now = System.currentTimeMillis()
                val lat = body.lat() / 1e7
                val lon = body.lon() / 1e7
                // Append to trail iff the position has actually moved
                // (avoids burying the buffer in identical fixes when
                // the drone is on the ground). Cap at TRAIL_MAX.
                val newTrail = run {
                    val last = st.trail.lastOrNull()
                    val moved = last == null ||
                        kotlin.math.abs(last.latDeg - lat) > 1e-6 ||
                        kotlin.math.abs(last.lonDeg - lon) > 1e-6
                    if (moved) (st.trail + TrailPoint(lat, lon, now)).takeLast(TRAIL_MAX)
                    else st.trail
                }
                st.copy(
                    latDeg = lat,
                    lonDeg = lon,
                    altMslMeters = body.alt() / 1000.0,
                    altAglMeters = body.relativeAlt() / 1000.0,
                    headingDeg = body.hdg() / 100.0,
                    groundSpeedMps = kotlin.math.hypot(body.vx() / 100.0, body.vy() / 100.0),
                    verticalSpeedMps = -body.vz() / 100.0,
                    lastPosition = now,
                    trail = newTrail,
                )
            }
            is SysStatus -> _state.update { st ->
                st.copy(
                    batteryPct = body.batteryRemaining().takeIf { it in 0..100 },
                    batteryVoltage = body.voltageBattery() / 1000.0,
                )
            }
            is GpsRawInt -> _state.update { st ->
                // eph is HDOP * 100 (UINT16_MAX = unknown).
                val hdop = body.eph().takeIf { it in 1..50_000 }?.let { it / 100.0 }
                st.copy(
                    gpsFix = body.fixType().entry()?.name?.removePrefix("GPS_FIX_TYPE_"),
                    gpsSatellites = body.satellitesVisible().takeIf { it in 0..255 },
                    gpsHdop = hdop,
                )
            }
            is HomePosition -> _state.update { st ->
                st.copy(
                    homeLatDeg = body.latitude() / 1e7,
                    homeLonDeg = body.longitude() / 1e7,
                    homeAltMsl = body.altitude() / 1000.0,
                )
            }
            is VfrHud -> _state.update { st ->
                st.copy(
                    airspeedMps = body.airspeed().toDouble().takeIf { it >= 0 },
                    throttlePct = body.throttle().takeIf { it in 0..100 },
                )
            }
            is WindCov -> _state.update { st ->
                // PX4 NED frame: x = north, y = east, z = down (m/s).
                // wind FROM bearing = atan2(y, x) + 180° (mod 360).
                val x = body.windX().toDouble()
                val y = body.windY().toDouble()
                val speed = kotlin.math.hypot(x, y)
                val fromDeg = (Math.toDegrees(kotlin.math.atan2(y, x)) + 180.0 + 360.0) % 360.0
                st.copy(
                    windFromDeg = fromDeg.takeIf { speed > 0.1 },
                    windSpeedMps = speed,
                )
            }
            is ArduWind -> _state.update { st ->
                // ArduPilot's WIND already gives direction as "wind from"
                // bearing in degrees + speed in m/s. No conversion needed.
                st.copy(
                    windFromDeg = body.direction().toDouble().takeIf { body.speed() > 0.1f },
                    windSpeedMps = body.speed().toDouble().takeIf { it >= 0 },
                )
            }
            is BatteryStatus -> _state.update { st ->
                // currentBattery is cA (10mA units). Negative = invalid.
                val amps = body.currentBattery().takeIf { it >= 0 }?.let { it / 100.0 }
                // timeRemaining is seconds. 0 = autopilot has no estimate
                // (typical for SITL); positive = real estimate.
                val sec = body.timeRemaining().takeIf { it > 0 }
                st.copy(
                    batteryCurrentAmps = amps,
                    batteryTimeRemainingSec = sec,
                )
            }
            is Statustext -> _state.update { st ->
                val line = body.text().trim(' ', ' ')
                // MAV_SEVERITY: 0=EMERGENCY, 1=ALERT, 2=CRITICAL, 3=ERROR,
                // 4=WARNING, 5=NOTICE, 6=INFO, 7=DEBUG. Anything ≤4 is a
                // real alert worth surfacing as a banner.
                val sev = body.severity().entry()?.ordinal ?: 7
                val alert = if (sev <= 4 && line.isNotBlank()) {
                    Triple(sev, line, System.currentTimeMillis())
                } else st.latestAlert
                st.copy(
                    recentStatusText = (st.recentStatusText + line).takeLast(8),
                    latestAlert = alert,
                )
            }
            is ParamValue -> _paramValues.tryEmit(body.paramId().trim() to body.paramValue())
            is MissionRequestInt -> _missionEvents.tryEmit(MissionEvent.RequestInt(body.seq()))
            is MissionAck -> _missionEvents.tryEmit(MissionEvent.Ack(body.type().entry()))
            is MissionItemReached -> _missionEvents.tryEmit(MissionEvent.ItemReached(body.seq()))
            else -> { /* fast-follow: GPS_RAW_INT, BATTERY_STATUS, EXTENDED_SYS_STATE */ }
        }
    }

    // ---------------------------------------------------------- mission protocol

    /** Send any MAVLink payload to the connected drone (caller built
     *  the struct). Used by mission upload, Follow-Me streaming, and
     *  any other path that pushes non-COMMAND_LONG messages. Always
     *  runs on Dispatchers.IO so callers can invoke from Main /
     *  Compose without NetworkOnMainThreadException. */
    suspend fun send(payload: Any) = sendRaw(payload)

    /** Mutate the public [state] from outside the read loop. Used by
     *  manager-level watchers (e.g. battery threshold synthesizer) to
     *  inject derived data the wire wouldn't otherwise produce. Keeps
     *  the UI's StateFlow as the single source of truth. */
    fun injectStateUpdate(transform: (DroneState) -> DroneState) {
        _state.update(transform)
    }

    private suspend fun sendRaw(payload: Any) = withContext(Dispatchers.IO) {
        when (transport) {
            Transport.UDP -> {
                val sock = udpSocket ?: return@withContext
                val addr = udpAddress ?: return@withContext
                val buf = serialize(payload)
                try {
                    sock.send(DatagramPacket(buf, buf.size, addr, udpPort))
                } catch (t: Throwable) {
                    Log.w(TAG, "UDP sendRaw failed: ${t.message}")
                }
            }
            Transport.TCP -> {
                val conn = tcpConn ?: return@withContext
                try {
                    // dronefleet handles framing + sequencing.
                    conn.send2(gcsSystemId, gcsComponentId, payload)
                    tcpOut?.flush()
                } catch (t: Throwable) {
                    Log.w(TAG, "TCP sendRaw failed: ${t.message}")
                }
            }
            null -> { /* not connected; drop silently — caller may race connect */ }
        }
    }

    /**
     * Upload [waypoints] using the MAVLink mission protocol (RAS-A IOP
     * §Mission Protocol). Returns true on ACCEPTED, false on any
     * NACK / timeout. Caller (UASManager) is responsible for issuing
     * MAV_CMD_MISSION_START separately to begin execution.
     *
     * Protocol:
     *  1. MISSION_CLEAR_ALL — wipe whatever was on the drone before.
     *  2. MISSION_COUNT(n) — announce upcoming items.
     *  3. Drone replies MISSION_REQUEST_INT(seq); we reply MISSION_ITEM_INT.
     *  4. Drone sends MISSION_ACK(MAV_MISSION_ACCEPTED) on success.
     */
    suspend fun uploadMission(
        waypoints: List<Waypoint>,
        perItemTimeoutMs: Long = 3_000,
        ackTimeoutMs: Long = 5_000,
    ): Boolean {
        if (waypoints.isEmpty()) return false
        val targetSys = _state.value.systemId ?: 1
        val targetComp = _state.value.componentId ?: 1
        val missionType = MavMissionType.MAV_MISSION_TYPE_MISSION

        // 1. Clear prior mission.
        sendRaw(
            MissionClearAll.builder()
                .targetSystem(targetSys).targetComponent(targetComp)
                .missionType(missionType).build()
        )
        // 2. Announce count.
        sendRaw(
            MissionCount.builder()
                .targetSystem(targetSys).targetComponent(targetComp)
                .count(waypoints.size).missionType(missionType).build()
        )
        Log.i(TAG, "mission upload: ${waypoints.size} waypoint(s) → sys=$targetSys")

        // 3. Serve REQUEST_INT until ACCEPTED / NACK / timeout.
        return withTimeoutOrNull(ackTimeoutMs) {
            var accepted = false
            try {
                _missionEvents.collect { ev ->
                    when (ev) {
                        is MissionEvent.RequestInt -> {
                            val seq = ev.seq
                            if (seq !in waypoints.indices) {
                                Log.w(TAG, "drone requested out-of-range seq=$seq, abort")
                                throw kotlinx.coroutines.CancellationException("seq-out-of-range")
                            }
                            val wp = waypoints[seq]
                            val item = MissionItemInt.builder()
                                .targetSystem(targetSys).targetComponent(targetComp)
                                .seq(seq)
                                .frame(MavFrame.MAV_FRAME_GLOBAL)
                                .command(MavCmd.MAV_CMD_NAV_WAYPOINT)
                                .current(if (seq == 0) 1 else 0)
                                .autocontinue(1)
                                .param1(0f).param2(2f).param3(0f).param4(Float.NaN)
                                .x((wp.latDeg * 1e7).toInt())
                                .y((wp.lonDeg * 1e7).toInt())
                                .z(wp.altMslMeters.toFloat())
                                .missionType(missionType).build()
                            sendRaw(item)
                        }
                        is MissionEvent.Ack -> {
                            accepted = ev.result == MavMissionResult.MAV_MISSION_ACCEPTED
                            throw kotlinx.coroutines.CancellationException("ack")
                        }
                        else -> { /* ignore execution events during upload */ }
                    }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // expected stop signal
            }
            accepted
        } ?: false
    }

    private fun decodeMode(hb: Heartbeat): String {
        // custom_mode encoding is autopilot-specific. PX4 packs
        // main_mode + sub_mode into the upper bytes; ArduPilot uses a
        // single flat enum. We branch on autopilot type per RAS-A IOP
        // §HEARTBEAT custom_mode interpretation guidance.
        val custom = hb.customMode()
        val autopilot = hb.autopilot().entry()
        return when (autopilot) {
            MavAutopilot.MAV_AUTOPILOT_PX4 -> decodePx4Mode(custom)
            MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA -> decodeArduPilotMode(custom, hb.type().entry())
            else -> "mode=0x" + java.lang.Long.toHexString(custom)
        }
    }

    /**
     * PX4 packs custom_mode as bytes: [reserved][sub_mode][main_mode][reserved].
     * From PX4's `commander/px4_custom_mode.h`. Sub-modes only apply when
     * main_mode is AUTO (4); other main_modes ignore sub.
     */
    private fun decodePx4Mode(custom: Long): String {
        val mainMode = ((custom ushr 16) and 0xFF).toInt()
        val subMode = ((custom ushr 24) and 0xFF).toInt()
        val main = when (mainMode) {
            1 -> "MANUAL"
            2 -> "ALTCTL"
            3 -> "POSCTL"
            4 -> when (subMode) {
                1 -> "AUTO.READY"
                2 -> "AUTO.TAKEOFF"
                3 -> "AUTO.LOITER"
                4 -> "AUTO.MISSION"
                5 -> "AUTO.RTL"
                6 -> "AUTO.LAND"
                8 -> "AUTO.FOLLOW_TARGET"
                9 -> "AUTO.PRECLAND"
                else -> "AUTO(sub=$subMode)"
            }
            5 -> "ACRO"
            6 -> "OFFBOARD"
            7 -> "STABILIZED"
            8 -> "RATTITUDE"
            else -> "PX4(main=$mainMode)"
        }
        return main
    }

    /**
     * ArduPilot packs custom_mode as a single flat enum. Different
     * vehicle types have overlapping numeric values, so the type matters.
     * Numbers from ardupilot/libraries/AP_Vehicle/AP_Vehicle_Type.h and
     * each vehicle's mode_*.h. Copter is the dominant case (~80% of
     * MAVLink platforms in the field).
     */
    private fun decodeArduPilotMode(custom: Long, vehicleType: MavType?): String {
        val mode = custom.toInt()
        return when (vehicleType) {
            MavType.MAV_TYPE_FIXED_WING -> when (mode) {
                0 -> "MANUAL"; 1 -> "CIRCLE"; 2 -> "STABILIZE"; 3 -> "TRAINING"
                5 -> "FBWA"; 6 -> "FBWB"; 7 -> "CRUISE"; 8 -> "AUTOTUNE"
                10 -> "AUTO"; 11 -> "RTL"; 12 -> "LOITER"; 15 -> "GUIDED"
                17 -> "QSTABILIZE"; 18 -> "QHOVER"; 19 -> "QLOITER"; 20 -> "QLAND"; 21 -> "QRTL"
                else -> "PLANE($mode)"
            }
            // ArduPilot Rover / Boat — modes from rover/mode.h. UGV/USV
            // share the same mode enum.
            MavType.MAV_TYPE_GROUND_ROVER, MavType.MAV_TYPE_SURFACE_BOAT -> when (mode) {
                0 -> "MANUAL"; 1 -> "ACRO"; 3 -> "STEERING"; 4 -> "HOLD"
                5 -> "LOITER"; 6 -> "FOLLOW"; 7 -> "SIMPLE"; 10 -> "AUTO"
                11 -> "RTL"; 12 -> "SMART_RTL"; 15 -> "GUIDED"; 16 -> "INITIALISING"
                else -> "ROVER($mode)"
            }
            else -> when (mode) { // Copter (dominant default)
                0 -> "STABILIZE"; 1 -> "ACRO"; 2 -> "ALT_HOLD"; 3 -> "AUTO"
                4 -> "GUIDED"; 5 -> "LOITER"; 6 -> "RTL"; 7 -> "CIRCLE"
                9 -> "LAND"; 11 -> "DRIFT"; 13 -> "SPORT"; 14 -> "FLIP"; 15 -> "AUTOTUNE"
                16 -> "POSHOLD"; 17 -> "BRAKE"; 18 -> "THROW"; 19 -> "AVOID_ADSB"
                20 -> "GUIDED_NOGPS"; 21 -> "SMART_RTL"; 22 -> "FLOWHOLD"; 23 -> "FOLLOW"
                24 -> "ZIGZAG"; 25 -> "SYSTEMID"; 26 -> "AUTOROTATE"; 27 -> "AUTO_RTL"
                else -> "COPTER($mode)"
            }
        }
    }

    private fun serialize(payload: Any): ByteArray {
        val out = ByteArrayOutputStream()
        val conn = DroneFleetConnection.create(ByteArrayInputStream(ByteArray(0)), out)
        conn.send2(gcsSystemId, gcsComponentId, payload)
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "Mavlink"
        const val DEFAULT_DRONE_PORT = 14550
        /** Trail buffer cap — ~30 s at typical 1 Hz CoT cadence. Long
         *  enough to give visual context of the drone's recent flight,
         *  short enough not to clutter the map on long missions. */
        private const val TRAIL_MAX = 30
    }
}
