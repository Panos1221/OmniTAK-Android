package soy.engindearing.omnitak.mobile.domain

import android.util.Log
import io.dronefleet.mavlink.common.MavCmd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.data.uas.DroneState
import soy.engindearing.omnitak.mobile.data.uas.MavlinkConnection

/**
 * Glue between the raw [MavlinkConnection] (UDP + framing) and the rest
 * of the app (CoT bus, UI). One [UASManager] per active drone.
 *
 *  - Owns the connection.
 *  - On every position update, builds a CoT PLI event and ships it via
 *    the same [ServerManager.sendCoT] path that markers and chat use,
 *    so the drone shows up on the local map AND federates to every
 *    other operator on the TAK server.
 *  - Surfaces commands (arm/takeoff/RTL/disarm) as suspending functions
 *    the UI calls.
 *
 * Lifecycle is explicit: caller calls [connect] to start, [disconnect]
 * to stop. The internal scope is cancelled on disconnect.
 */
class UASManager(
    private val sendCoT: suspend (String) -> Boolean,
) {
    private val mavlink = MavlinkConnection()
    val state: StateFlow<DroneState> = mavlink.state

    private var scope: CoroutineScope? = null
    private var cotPumpJob: Job? = null

    private var droneUid: String = CotBuilders.newUid()
    private var droneCallsign: String = "OmniTAK-UAS"
    private var operatorUid: String? = null

    /**
     * Open the MAVLink connection and start pumping position telemetry
     * out as CoT.
     *
     * [host]/[port] is where the drone's MAVLink endpoint is listening.
     * SITL convention is `127.0.0.1:14550`; from an Android emulator
     * pointing at the host machine, use `10.0.2.2:14550`.
     */
    fun connect(
        host: String,
        port: Int = MavlinkConnection.DEFAULT_DRONE_PORT,
        callsign: String = "OmniTAK-UAS",
        operatorUid: String? = null,
    ) {
        disconnect()
        droneCallsign = callsign.ifBlank { "OmniTAK-UAS" }
        this.operatorUid = operatorUid
        // Fresh UID per connect — different drone session, new marker.
        droneUid = "UAS-${CotBuilders.newUid().take(8)}"

        mavlink.connect(host, port)

        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        cotPumpJob = s.launch { cotPumpLoop() }
        Log.i(TAG, "UAS connect uid=$droneUid callsign=$droneCallsign target=$host:$port")
    }

    fun disconnect() {
        cotPumpJob?.cancel()
        scope?.cancel()
        cotPumpJob = null
        scope = null
        mavlink.disconnect()
    }

    // ----------------------------------------------------------- commands

    /** ARM. Per RAS-A IOP §Arming, MAV_CMD_COMPONENT_ARM_DISARM with param1=1. */
    suspend fun arm() {
        mavlink.sendCommand(MavCmd.MAV_CMD_COMPONENT_ARM_DISARM, p1 = 1f)
    }

    /** DISARM. param1=0. param2=21196 is the "force" magic per ArduPilot convention; we omit it (graceful disarm only). */
    suspend fun disarm() {
        mavlink.sendCommand(MavCmd.MAV_CMD_COMPONENT_ARM_DISARM, p1 = 0f)
    }

    /**
     * Takeoff to [altitudeMetersAgl] above the current home position.
     * MAV_CMD_NAV_TAKEOFF, param7 is target altitude.
     *
     * Caller should ensure the drone is armed and in a takeoff-capable
     * mode (PX4: AUTO.TAKEOFF, ArduPilot: GUIDED). For day-1 we send
     * the command and rely on the autopilot's default behaviour;
     * fast-follow: explicit SET_MODE before TAKEOFF.
     */
    suspend fun takeoff(altitudeMetersAgl: Float = 10f) {
        mavlink.sendCommand(MavCmd.MAV_CMD_NAV_TAKEOFF, p7 = altitudeMetersAgl)
    }

    /** Return To Launch. Drone climbs to RTL altitude, returns to home, lands. */
    suspend fun returnToLaunch() {
        mavlink.sendCommand(MavCmd.MAV_CMD_NAV_RETURN_TO_LAUNCH)
    }

    /**
     * "Fly to here." Send MAV_CMD_DO_REPOSITION with the operator-tapped
     * coordinate at the drone's current altitude.
     *
     * Works on both PX4 (any AUTO sub-mode honors DO_REPOSITION) and
     * ArduPilot (Copter must be in GUIDED). Caller is responsible for
     * making sure the drone is in a mode where DO_REPOSITION makes
     * sense; if not, the drone responds with COMMAND_ACK = DENIED and
     * we surface that via the STATUSTEXT path.
     *
     * Param mapping per the RAS-A IOP §Command Protocol / Mavlink common:
     *  - p1: ground speed (m/s, -1 = use default)
     *  - p2: bitmask (0 = position only)
     *  - p3: reserved
     *  - p4: yaw (NaN = keep current)
     *  - p5: latitude (deg)
     *  - p6: longitude (deg)
     *  - p7: altitude (m, MSL — we re-use the drone's current MSL alt
     *        to keep it at the same level it's flying at now)
     */
    suspend fun flyTo(latDeg: Double, lonDeg: Double, groundSpeed: Float = -1f) {
        val alt = state.value.altMslMeters?.toFloat() ?: 0f
        mavlink.sendCommand(
            MavCmd.MAV_CMD_DO_REPOSITION,
            p1 = groundSpeed,
            p2 = 0f,
            p4 = Float.NaN,
            p5 = latDeg.toFloat(),
            p6 = lonDeg.toFloat(),
            p7 = alt,
        )
    }

    // ---------------------------------------------------------- internals

    private suspend fun cotPumpLoop() {
        // Pump on a fixed cadence rather than per-message so we don't
        // flood the server with 4 Hz CoT (most TAK clients PLI at 1–2 Hz).
        // We use the latest known state on each tick; if no fix yet, skip.
        var lastSent: Long = 0
        while (scope?.isActive == true) {
            val st = state.value
            val now = System.currentTimeMillis()
            if (st.hasFix() && now - lastSent >= 1_000) {
                runCatching {
                    sendCoT(
                        CotBuilders.buildUasPliEvent(
                            uid = droneUid,
                            callsign = droneCallsign,
                            latDeg = st.latDeg!!,
                            lonDeg = st.lonDeg!!,
                            haeMeters = st.altMslMeters ?: 0.0,
                            headingDeg = st.headingDeg,
                            groundSpeedMps = st.groundSpeedMps,
                            operatorUid = operatorUid,
                        )
                    )
                }.onFailure { Log.w(TAG, "CoT pump send failed: ${it.message}") }
                lastSent = now
            }
            delay(250)
        }
    }

    companion object {
        private const val TAG = "UASManager"
    }
}
