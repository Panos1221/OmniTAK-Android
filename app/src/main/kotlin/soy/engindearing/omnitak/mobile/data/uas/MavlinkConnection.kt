package soy.engindearing.omnitak.mobile.data.uas

import android.util.Log
import io.dronefleet.mavlink.MavlinkConnection as DroneFleetConnection
import io.dronefleet.mavlink.MavlinkMessage
import io.dronefleet.mavlink.common.CommandLong
import io.dronefleet.mavlink.common.GlobalPositionInt
import io.dronefleet.mavlink.common.MavCmd
import io.dronefleet.mavlink.common.Statustext
import io.dronefleet.mavlink.common.SysStatus
import io.dronefleet.mavlink.minimal.Heartbeat
import io.dronefleet.mavlink.minimal.MavAutopilot
import io.dronefleet.mavlink.minimal.MavModeFlag
import io.dronefleet.mavlink.minimal.MavState
import io.dronefleet.mavlink.minimal.MavType
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * UDP MAVLink 2 client for a single connected UAS, per the RAS-A
 * MAVLink Control Link Interoperability Profile v1.2 (the DoD spec the
 * official ATAK UAS Tool 13.0 implements).
 *
 * Transport: UDP unicast, well-known port 14550 (RAS-A IOP §Networking).
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

    private val _state = MutableStateFlow(DroneState())
    val state: StateFlow<DroneState> = _state.asStateFlow()

    private var socket: DatagramSocket? = null
    private var droneAddress: InetAddress? = null
    private var dronePort: Int = DEFAULT_DRONE_PORT
    private var scope: CoroutineScope? = null
    private var readJob: Job? = null
    private var heartbeatJob: Job? = null

    /** Our GCS system/component id — RAS-A IOP says ground stations use sysid 255. */
    private val gcsSystemId = 255
    private val gcsComponentId = 190 // MAV_COMP_ID_MISSIONPLANNER

    /**
     * Open a UDP socket and start the read + heartbeat loops.
     * [host]/[port] is where the drone (or SITL) is listening. Default is
     * the SITL convention `127.0.0.1:14550`.
     */
    fun connect(host: String, port: Int = DEFAULT_DRONE_PORT) {
        disconnect() // idempotent
        droneAddress = InetAddress.getByName(host)
        dronePort = port

        val sock = DatagramSocket().apply {
            soTimeout = 1_000 // so the read loop can check cancellation
        }
        socket = sock

        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s

        readJob = s.launch { readLoop(sock) }
        heartbeatJob = s.launch { heartbeatLoop(sock) }
        Log.i(TAG, "MAVLink UDP open → $host:$port (GCS sysid=$gcsSystemId)")
    }

    fun disconnect() {
        readJob?.cancel()
        heartbeatJob?.cancel()
        scope?.cancel()
        readJob = null
        heartbeatJob = null
        scope = null
        socket?.close()
        socket = null
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
        val sock = socket ?: error("not connected")
        val addr = droneAddress ?: error("no drone address")
        val targetSys = _state.value.systemId ?: 1
        val targetComp = _state.value.componentId ?: 1

        val msg = CommandLong.builder()
            .targetSystem(targetSys)
            .targetComponent(targetComp)
            .command(command)
            .confirmation(0)
            .param1(p1).param2(p2).param3(p3).param4(p4)
            .param5(p5).param6(p6).param7(p7)
            .build()

        withContext(Dispatchers.IO) {
            val buf = serialize(msg)
            sock.send(DatagramPacket(buf, buf.size, addr, dronePort))
        }
    }

    // ---------------------------------------------------------------- loops

    private suspend fun readLoop(sock: DatagramSocket) {
        val buf = ByteArray(280) // MAVLink 2 max frame size
        while (scope?.isActive == true) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                sock.receive(packet)
                // First receive also tells us the drone's source port if
                // the caller passed the wrong destination port (SITL is
                // chatty about this — most ground stations rely on it).
                if (droneAddress == null || packet.address != droneAddress) {
                    droneAddress = packet.address
                    dronePort = packet.port
                }
                handleIncoming(packet)
            } catch (_: java.net.SocketTimeoutException) {
                // expected — we set SO_TIMEOUT so we can check cancellation
            } catch (t: Throwable) {
                if (scope?.isActive == true) {
                    Log.w(TAG, "read error: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
    }

    private suspend fun heartbeatLoop(sock: DatagramSocket) {
        val hb = Heartbeat.builder()
            .type(MavType.MAV_TYPE_GCS)
            .autopilot(MavAutopilot.MAV_AUTOPILOT_INVALID)
            .systemStatus(MavState.MAV_STATE_ACTIVE)
            .mavlinkVersion(3) // MAVLink 2
            .build()
        while (scope?.isActive == true) {
            try {
                val addr = droneAddress
                if (addr != null) {
                    val buf = serialize(hb)
                    sock.send(DatagramPacket(buf, buf.size, addr, dronePort))
                }
            } catch (t: Throwable) {
                if (scope?.isActive == true) {
                    Log.w(TAG, "heartbeat send error: ${t.message}")
                }
            }
            delay(1_000) // 1 Hz per RAS-A IOP
        }
    }

    private fun handleIncoming(packet: DatagramPacket) {
        val inStream = ByteArrayInputStream(packet.data, 0, packet.length)
        // io.dronefleet's MavlinkConnection wraps a stream — re-create
        // per packet rather than maintain a long-lived parser, because
        // we're packet-oriented over UDP.
        val conn = DroneFleetConnection.create(inStream, ByteArrayOutputStream())
        var msg: MavlinkMessage<*>?
        try {
            msg = conn.next()
        } catch (t: Throwable) {
            return
        }
        while (msg != null) {
            apply(msg)
            try { msg = conn.next() } catch (_: Throwable) { msg = null }
        }
    }

    private fun apply(msg: MavlinkMessage<*>) {
        val sys = msg.originSystemId
        val comp = msg.originComponentId
        when (val body = msg.payload) {
            is Heartbeat -> _state.update { st ->
                st.copy(
                    systemId = sys,
                    componentId = comp,
                    autopilot = body.autopilot().entry()?.name,
                    vehicleType = body.type().entry()?.name,
                    flightMode = decodeMode(body),
                    armed = body.baseMode().flagsEnabled(MavModeFlag.MAV_MODE_FLAG_SAFETY_ARMED),
                    lastHeartbeat = System.currentTimeMillis(),
                )
            }
            is GlobalPositionInt -> _state.update { st ->
                st.copy(
                    latDeg = body.lat() / 1e7,
                    lonDeg = body.lon() / 1e7,
                    altMslMeters = body.alt() / 1000.0,
                    altAglMeters = body.relativeAlt() / 1000.0,
                    headingDeg = body.hdg() / 100.0,
                    groundSpeedMps = kotlin.math.hypot(body.vx() / 100.0, body.vy() / 100.0),
                    verticalSpeedMps = -body.vz() / 100.0,
                    lastPosition = System.currentTimeMillis(),
                )
            }
            is SysStatus -> _state.update { st ->
                st.copy(
                    batteryPct = body.batteryRemaining().takeIf { it in 0..100 },
                    batteryVoltage = body.voltageBattery() / 1000.0,
                )
            }
            is Statustext -> _state.update { st ->
                val line = body.text().trim(' ', ' ')
                st.copy(recentStatusText = (st.recentStatusText + line).takeLast(8))
            }
            else -> { /* fast-follow: GPS_RAW_INT, BATTERY_STATUS, EXTENDED_SYS_STATE */ }
        }
    }

    private fun decodeMode(hb: Heartbeat): String {
        // Per RAS-A IOP §HEARTBEAT, custom_mode encoding is autopilot-
        // specific. PX4 packs main_mode + sub_mode in upper bytes;
        // ArduPilot uses a single flat enum. For now surface the raw
        // value — pretty names land in the fast-follow once we wire
        // per-autopilot decoders.
        return "mode=0x" + Integer.toHexString(hb.customMode().toInt())
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
    }
}
