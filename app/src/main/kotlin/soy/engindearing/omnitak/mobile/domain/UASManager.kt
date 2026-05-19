package soy.engindearing.omnitak.mobile.domain

import android.util.Log
import io.dronefleet.mavlink.common.MavCmd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.data.uas.AltFrame
import soy.engindearing.omnitak.mobile.data.uas.CruiseAltitude
import soy.engindearing.omnitak.mobile.data.uas.DroneState
import soy.engindearing.omnitak.mobile.data.uas.MavlinkConnection
import soy.engindearing.omnitak.mobile.data.uas.MissionPhase
import soy.engindearing.omnitak.mobile.data.uas.MissionStore
import soy.engindearing.omnitak.mobile.data.uas.TerrainSampler
import soy.engindearing.omnitak.mobile.data.uas.WaypointMission

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
    private val terrain: TerrainSampler = TerrainSampler(),
) {
    private val mavlink = MavlinkConnection()
    val state: StateFlow<DroneState> = mavlink.state

    /** Mission the operator is drawing / has uploaded. Single store —
     *  multi-mission queueing is a fast-follow if it ever becomes a thing. */
    val missionStore = MissionStore()
    val mission: StateFlow<WaypointMission> = missionStore.state

    /** Operator's cruise altitude — what fly-here and new waypoints use.
     *  Default 80 m AGL; updated to drone's current relative_alt on
     *  connect so the first reposition matches the current flight. */
    private val _cruiseAlt = MutableStateFlow(CruiseAltitude())
    val cruiseAlt: StateFlow<CruiseAltitude> = _cruiseAlt.asStateFlow()

    fun setCruiseAltitude(meters: Double, frame: AltFrame) {
        _cruiseAlt.value = CruiseAltitude(meters.coerceIn(0.0, 10_000.0), frame)
    }

    /** Result of a flyTo attempt. Sealed so the caller can render the
     *  right toast / banner without sprinkling string matching around. */
    sealed interface FlyHereResult {
        /** Command went out to the autopilot. */
        data class Sent(val targetMsl: Double, val terrainMsl: Double?, val clearance: Double?) : FlyHereResult
        /** Blocked because the target altitude would clip the local terrain. */
        data class WouldHitTerrain(val targetMsl: Double, val terrainMsl: Double, val clearance: Double) : FlyHereResult
        /** Connected but no GPS fix yet — can't compute MSL from AGL. */
        object NoGpsFix : FlyHereResult
        object NotConnected : FlyHereResult
    }

    private var scope: CoroutineScope? = null
    private var cotPumpJob: Job? = null
    private var missionExecWatcherJob: Job? = null

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
        transport: MavlinkConnection.Transport = MavlinkConnection.Transport.UDP,
    ) {
        disconnect()
        droneCallsign = callsign.ifBlank { "OmniTAK-UAS" }
        this.operatorUid = operatorUid
        // Fresh UID per connect — different drone session, new marker.
        droneUid = "UAS-${CotBuilders.newUid().take(8)}"

        mavlink.connect(transport, host, port)

        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        cotPumpJob = s.launch { cotPumpLoop() }
        missionExecWatcherJob = s.launch { missionExecWatcher() }
        Log.i(TAG, "UAS connect uid=$droneUid callsign=$droneCallsign transport=$transport target=$host:$port")
    }

    fun disconnect() {
        cotPumpJob?.cancel()
        missionExecWatcherJob?.cancel()
        scope?.cancel()
        cotPumpJob = null
        missionExecWatcherJob = null
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
     * "Fly to here." Send MAV_CMD_DO_REPOSITION using the operator's
     * [cruiseAlt], with a pre-flight terrain safety check against TAK
     * Terrain DEM at the target coordinate.
     *
     * Returns a [FlyHereResult] the caller renders as toast/banner:
     *  - [FlyHereResult.Sent] — command went out
     *  - [FlyHereResult.WouldHitTerrain] — blocked, target below local
     *    terrain + [terrainBufferMeters] safety buffer
     *  - [FlyHereResult.NoGpsFix] — drone has no MSL ref yet
     *  - [FlyHereResult.NotConnected] — link down
     *
     * If the terrain fetch fails (offline, CDN down), we send anyway —
     * absence of data is not evidence the path is unsafe, and the
     * operator already has eyes-on (it's a manual long-press). We
     * surface this in [FlyHereResult.Sent.terrainMsl == null].
     *
     * Param mapping per RAS-A IOP §Command Protocol:
     *  - p1: ground speed (m/s, -1 = use default)
     *  - p2: bitmask (0 = position only)
     *  - p4: yaw (NaN = keep current)
     *  - p5: latitude (deg) / p6: longitude (deg) / p7: altitude (MSL)
     */
    suspend fun flyTo(
        latDeg: Double,
        lonDeg: Double,
        groundSpeed: Float = -1f,
        terrainBufferMeters: Double = 10.0,
    ): FlyHereResult {
        val st = state.value
        if (!st.isConnected()) return FlyHereResult.NotConnected
        val homeMsl = st.altMslMeters ?: return FlyHereResult.NoGpsFix

        // Operator's chosen altitude → an MSL value the autopilot accepts.
        val cruise = _cruiseAlt.value
        val targetMsl = cruise.toMsl(homeMsl)

        // Terrain safety check.
        val terrainMsl = runCatching { terrain.sampleMeters(latDeg, lonDeg) }.getOrNull()
        val clearance = terrainMsl?.let { targetMsl - it }
        if (terrainMsl != null && clearance != null && clearance < terrainBufferMeters) {
            Log.w(TAG, "flyTo BLOCKED: target=${targetMsl}m terrain=${terrainMsl}m clearance=${clearance}m")
            return FlyHereResult.WouldHitTerrain(targetMsl, terrainMsl, clearance)
        }

        mavlink.sendCommand(
            MavCmd.MAV_CMD_DO_REPOSITION,
            p1 = groundSpeed,
            p2 = 0f,
            p4 = Float.NaN,
            p5 = latDeg.toFloat(),
            p6 = lonDeg.toFloat(),
            p7 = targetMsl.toFloat(),
        )
        return FlyHereResult.Sent(targetMsl, terrainMsl, clearance)
    }

    // ------------------------------------------------------- mission upload

    /**
     * Push the current [MissionStore] waypoints to the drone using the
     * MAVLink mission upload handshake, then optionally start execution.
     *
     * The [MissionStore.state] drives the UI (banner phase, current leg
     * highlight), so we update the store at each protocol stage rather
     * than returning a status.
     */
    suspend fun uploadAndStartMission(autoStart: Boolean = true) {
        val draft = missionStore.state.value
        if (draft.waypoints.isEmpty()) return
        missionStore.setPhase(MissionPhase.UPLOADING)
        // Default mission altitude: operator's cruise altitude (the
        // chip + sheet up top). Per-waypoint altitude override is a
        // fast-follow (#54 in the backlog).
        val homeMsl = state.value.altMslMeters ?: 0.0
        val targetMsl = _cruiseAlt.value.toMsl(homeMsl)
        val waypoints = draft.waypoints.map {
            if (it.altMslMeters == 0.0) it.copy(altMslMeters = targetMsl) else it
        }
        val accepted = mavlink.uploadMission(waypoints)
        if (!accepted) {
            missionStore.setPhase(MissionPhase.FAILED, "drone rejected mission upload")
            return
        }
        missionStore.setPhase(MissionPhase.UPLOADED)
        if (autoStart) {
            mavlink.sendCommand(MavCmd.MAV_CMD_MISSION_START)
            missionStore.setPhase(MissionPhase.STARTED)
        }
    }

    /** Cancel a draft / uploaded mission. Doesn't tell the drone — RTL
     *  is the operator's separate decision and that wire takes a
     *  different MAVLink path. */
    fun cancelMission() {
        missionStore.clear()
    }

    private suspend fun missionExecWatcher() {
        mavlink.missionEvents.collect { ev ->
            if (ev is MavlinkConnection.MissionEvent.ItemReached) {
                missionStore.setCurrentSeq(ev.seq)
            }
        }
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
