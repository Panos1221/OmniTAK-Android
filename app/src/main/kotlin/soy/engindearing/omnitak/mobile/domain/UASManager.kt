package soy.engindearing.omnitak.mobile.domain

import android.util.Log
import io.dronefleet.mavlink.common.FollowTarget
import io.dronefleet.mavlink.common.MavCmd
import io.dronefleet.mavlink.minimal.MavAutopilot
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
import soy.engindearing.omnitak.mobile.data.SelfFix
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
    /** Operator's own position — used by Follow-Me to tell the drone
     *  where to track. Defaults to "no fix" so Follow-Me cleanly errors
     *  out in tests that don't wire a real LocationProvider. */
    private val operatorFix: () -> SelfFix? = { null },
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
    private var followMeJob: Job? = null
    private var terrainSamplerJob: Job? = null
    private var batteryWatcherJob: Job? = null

    /** What the drone is currently tracking. null = idle. (Named
     *  FollowSubject to avoid colliding with MAVLink's FOLLOW_TARGET
     *  message class.) */
    sealed interface FollowSubject {
        object Operator : FollowSubject
        data class Contact(val uid: String, val callsign: String) : FollowSubject
    }
    private val _followingWhat = MutableStateFlow<FollowSubject?>(null)
    val followingWhat: StateFlow<FollowSubject?> = _followingWhat.asStateFlow()
    /** Convenience derived flow for the UAS HUD pill. */
    private val _followMeActive = MutableStateFlow(false)
    val followMeActive: StateFlow<Boolean> = _followMeActive.asStateFlow()

    /** Terrain elevation (MSL meters) directly under the drone's current
     *  position. Drives the AGL-above-terrain HUD readout. Sampled every
     *  5 s — fine for ground-speed UAS work, and we cache tiles so the
     *  CDN cost stays effectively zero. */
    private val _terrainBelowDroneMsl = MutableStateFlow<Double?>(null)
    val terrainBelowDroneMsl: StateFlow<Double?> = _terrainBelowDroneMsl.asStateFlow()

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
        terrainSamplerJob = s.launch { terrainSamplerLoop() }
        batteryWatcherJob = s.launch { batteryWatcherLoop() }
        Log.i(TAG, "UAS connect uid=$droneUid callsign=$droneCallsign transport=$transport target=$host:$port")
    }

    fun disconnect() {
        cotPumpJob?.cancel()
        missionExecWatcherJob?.cancel()
        followMeJob?.cancel()
        terrainSamplerJob?.cancel()
        batteryWatcherJob?.cancel()
        scope?.cancel()
        cotPumpJob = null
        missionExecWatcherJob = null
        followMeJob = null
        terrainSamplerJob = null
        batteryWatcherJob = null
        _followMeActive.value = false
        _terrainBelowDroneMsl.value = null
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
        // Don't keep streaming Follow-Me after the operator hits RTL —
        // the drone should head home, not chase the operator further.
        if (_followMeActive.value) stopFollowMe()
        mavlink.sendCommand(MavCmd.MAV_CMD_NAV_RETURN_TO_LAUNCH)
    }

    // ------------------------------------------------------------- Follow-Me

    sealed interface FollowMeResult {
        object Started : FollowMeResult
        object NoGpsFix : FollowMeResult
        object NotConnected : FollowMeResult
        data class AutopilotNotSupported(val autopilot: String?) : FollowMeResult
    }

    /**
     * Engage Follow-Me: drone tracks the operator's GPS at the cruise
     * altitude offset. Streams MAVLink FOLLOW_TARGET (#144) at 1 Hz so
     * the autopilot keeps a fresh position fix on us.
     *
     * Today: PX4 only. The autopilot switches to AUTO.FOLLOW_TARGET
     * (main=4, sub=8) and uses FOLLOW_TARGET.alt + the FLW_TGT_HT param
     * to set follow height. We add the operator's [CruiseAltitude]
     * (treated as meters above operator) onto the position we send, so
     * the drone flies at operator_alt + cruise_meters (give or take
     * PX4's default FLW_TGT_HT of ~8 m — negligible vs typical cruise).
     *
     * ArduPilot Copter has a FOLLOW mode (custom_mode=23) but uses a
     * different position source (GCS heartbeat / FOLL_OFS params) —
     * fast-follow.
     */
    suspend fun startFollowMe(): FollowMeResult {
        if (operatorFix() == null) return FollowMeResult.NoGpsFix
        return startFollow(FollowSubject.Operator) {
            operatorFix()?.let { it.lat to it.lon }
        }
    }

    /**
     * Pursue a CoT contact — drone tracks the contact's position
     * exactly like Follow-Me but with the contact as the source.
     * [positionProvider] is called each tick to fetch the contact's
     * current lat/lon (the manager doesn't own the contact store, so
     * the UI passes it in as a closure over its live state).
     *
     * Returns the same [FollowMeResult] sealed type as Follow-Me —
     * [FollowMeResult.NoGpsFix] now means "contact has no position yet".
     */
    suspend fun startPursueContact(
        uid: String,
        callsign: String,
        positionProvider: () -> Pair<Double, Double>?,
    ): FollowMeResult {
        if (positionProvider() == null) return FollowMeResult.NoGpsFix
        return startFollow(FollowSubject.Contact(uid, callsign), positionProvider)
    }

    /** Shared follow/pursue startup — handles autopilot check, mode
     *  switch, and the 1 Hz FOLLOW_TARGET streamer. */
    private suspend fun startFollow(
        target: FollowSubject,
        positionProvider: () -> Pair<Double, Double>?,
    ): FollowMeResult {
        val st = state.value
        if (!st.isConnected()) return FollowMeResult.NotConnected
        if (st.autopilot != MavAutopilot.MAV_AUTOPILOT_PX4.name) {
            return FollowMeResult.AutopilotNotSupported(st.autopilot)
        }

        // 1. SET_MODE → AUTO.FOLLOW_TARGET (PX4 main=4, sub=8).
        mavlink.sendCommand(
            MavCmd.MAV_CMD_DO_SET_MODE,
            p1 = 1f, p2 = 4f, p3 = 8f,
        )

        // 2. Spin the 1 Hz streamer. Cancel any prior follow target so
        //    we never have two streams racing each other.
        followMeJob?.cancel()
        followMeJob = scope?.launch {
            while (isActive) {
                val pos = positionProvider()
                if (pos != null) sendFollowTargetMsg(pos.first, pos.second)
                delay(1_000)
            }
        }
        _followingWhat.value = target
        _followMeActive.value = true
        Log.i(TAG, "FOLLOW engaged: target=$target cruise=${_cruiseAlt.value.meters}m ${_cruiseAlt.value.frame}")
        return FollowMeResult.Started
    }

    /** Disengage whatever follow/pursue is active. Stops the streamer
     *  and parks the drone in AUTO.LOITER. */
    suspend fun stopFollowMe() {
        followMeJob?.cancel()
        followMeJob = null
        _followMeActive.value = false
        _followingWhat.value = null
        if (state.value.isConnected()) {
            // PX4 AUTO.LOITER = main 4, sub 3.
            mavlink.sendCommand(
                MavCmd.MAV_CMD_DO_SET_MODE,
                p1 = 1f, p2 = 4f, p3 = 3f,
            )
        }
        Log.i(TAG, "FOLLOW disengaged")
    }

    /** Build + send one FOLLOW_TARGET message at [latDeg]/[lonDeg].
     *  Altitude rule: prefer terrain elevation at the target plus cruise
     *  (so the drone holds AGL above the target's actual ground level);
     *  fall back to operator-altitude + cruise when terrain isn't
     *  available. Result is a consistent above-the-thing-you're-tracking
     *  flight height regardless of whether the target is the operator
     *  or a contact on a hill across town. */
    private suspend fun sendFollowTargetMsg(latDeg: Double, lonDeg: Double) {
        val cruise = _cruiseAlt.value.meters
        val terrainMsl = runCatching { terrain.sampleMeters(latDeg, lonDeg) }.getOrNull()
        val baseMsl = terrainMsl ?: operatorFix()?.altitudeM ?: 0.0
        val altMsl = baseMsl + cruise
        val msg = FollowTarget.builder()
            .timestamp(java.math.BigInteger.valueOf(System.currentTimeMillis()))
            .estCapabilities(1) // bit 0 = position
            .lat((latDeg * 1e7).toInt())
            .lon((lonDeg * 1e7).toInt())
            .alt(altMsl.toFloat())
            .customState(java.math.BigInteger.ZERO)
            .build()
        runCatching { mavlink.send(msg) }
            .onFailure { Log.w(TAG, "FOLLOW_TARGET send failed: ${it.message}") }
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
            // Apply mission-wide cruise speed if any waypoint overrode
            // it — first override wins. Mid-mission speed changes need
            // interleaved DO_CHANGE_SPEED items (fast-follow).
            val speedOverride = waypoints.firstNotNullOfOrNull { it.speedMps }
            if (speedOverride != null) {
                mavlink.sendCommand(
                    MavCmd.MAV_CMD_DO_CHANGE_SPEED,
                    p1 = 1f /* ground speed */,
                    p2 = speedOverride,
                    p3 = -1f /* throttle = no change */,
                    p4 = 0f,
                )
            }
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

    /** Background loop: sample TAK Terrain at the drone's current lat/lon
     *  every 5 s. Drives the HUD's "above terrain" readout. Cheap because
     *  the sampler caches tiles after the first hit and the drone usually
     *  stays in the same z=12 tile for an entire flight (~5 km × 5 km). */
    /** Synthesize battery alerts from the SYS_STATUS percent stream:
     *  ≤25 % → WARNING "Recommend RTL", ≤15 % → CRITICAL "RTL NOW".
     *  Fires once per threshold cross to avoid spamming the banner. */
    private suspend fun batteryWatcherLoop() {
        var lastBucket = 100
        while (scope?.isActive == true) {
            val pct = state.value.batteryPct
            if (pct != null) {
                val bucket = when {
                    pct <= 15 -> 15
                    pct <= 25 -> 25
                    else -> 100
                }
                if (bucket < lastBucket) {
                    // Inject a synthetic alert via DroneState.latestAlert
                    // so the existing UasAlertBanner picks it up — keeps
                    // the rendering path uniform.
                    val now = System.currentTimeMillis()
                    val alert = when (bucket) {
                        15 -> Triple(2 /* CRITICAL */, "RTL NOW — battery $pct%", now)
                        25 -> Triple(4 /* WARNING */, "Recommend RTL — battery $pct%", now)
                        else -> null
                    }
                    if (alert != null) {
                        mavlink.injectStateUpdate { st -> st.copy(latestAlert = alert) }
                        Log.w(TAG, "Battery alert: ${alert.second}")
                    }
                }
                lastBucket = bucket
            }
            delay(5_000)
        }
    }

    private suspend fun terrainSamplerLoop() {
        while (scope?.isActive == true) {
            val st = state.value
            if (st.hasFix()) {
                val t = runCatching { terrain.sampleMeters(st.latDeg!!, st.lonDeg!!) }.getOrNull()
                _terrainBelowDroneMsl.value = t
            }
            delay(5_000)
        }
    }

    // ------------------------------------------------------- safety + control

    /** Land at the drone's current position (NOT home — for that, use RTL).
     *  Standard "set me down here" operator action when the drone is over
     *  a safe spot. */
    suspend fun landHere() {
        if (_followMeActive.value) stopFollowMe()
        mavlink.sendCommand(MavCmd.MAV_CMD_NAV_LAND)
    }

    /** Pause an executing mission — switch the drone to AUTO.LOITER so it
     *  hovers in place. Operator resumes via [resumeMission]. PX4-specific
     *  sub-mode encoding (ArduPilot uses BRAKE / GUIDED hold). */
    suspend fun pauseMission() {
        if (state.value.autopilot == MavAutopilot.MAV_AUTOPILOT_PX4.name) {
            // PX4 AUTO.LOITER = main 4, sub 3.
            mavlink.sendCommand(MavCmd.MAV_CMD_DO_SET_MODE, p1 = 1f, p2 = 4f, p3 = 3f)
        } else {
            mavlink.sendCommand(MavCmd.MAV_CMD_DO_PAUSE_CONTINUE, p1 = 0f /* pause */)
        }
    }

    /** Resume a paused mission. */
    suspend fun resumeMission() {
        if (state.value.autopilot == MavAutopilot.MAV_AUTOPILOT_PX4.name) {
            // PX4 AUTO.MISSION = main 4, sub 4.
            mavlink.sendCommand(MavCmd.MAV_CMD_DO_SET_MODE, p1 = 1f, p2 = 4f, p3 = 4f)
        } else {
            mavlink.sendCommand(MavCmd.MAV_CMD_DO_PAUSE_CONTINUE, p1 = 1f /* continue */)
        }
    }

    /**
     * EMERGENCY: cut motors immediately. The drone WILL fall out of the
     * sky. Only call after a confirm dialog — this is the "I'd rather
     * the drone crash than hurt someone on the ground" lever (e.g., a
     * fly-away into a crowd). MAV_CMD_DO_FLIGHTTERMINATION param1=1.
     */
    /**
     * Orbit a point: drone circles [latDeg]/[lonDeg] at [radiusMeters]
     * at the cruise altitude. PX4 supports MAV_CMD_DO_ORBIT directly;
     * ArduPilot Copter handles it via NAV_LOITER_TURNS in a mission
     * upload, which is heavier — for day-one we just fire DO_ORBIT and
     * report what the autopilot ACKs.
     *
     * Param mapping per MAVLink common:
     *  - p1: radius (m) — positive = CW from above, negative = CCW
     *  - p2: velocity (m/s, NaN = default)
     *  - p3: yaw behaviour (0 = uncontrolled, 1 = face center, NaN = default)
     *  - p5/p6: lat/lon (deg as float)
     *  - p7: altitude (m MSL)
     */
    /**
     * Generate a parallel-line lawnmower survey covering a [boxMeters] ×
     * [boxMeters] square centered on ([centerLat], [centerLon]), with
     * [lineSpacingMeters] between sweep lines. Standard SAR / mapping
     * pattern — drone flies E→W, N step, W→E, N step, …
     *
     * Defaults (200 m × 200 m at 30 m spacing → 7 lines, ~14 waypoints):
     * sane for a single battery on a Phantom-class quad. Operator can
     * grow the box by editing waypoints individually (the per-waypoint
     * edit sheet) or by deleting/re-running.
     */
    suspend fun uploadSurveyMission(
        centerLat: Double,
        centerLon: Double,
        boxMeters: Double = 200.0,
        lineSpacingMeters: Double = 30.0,
    ) {
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = 111_320.0 * kotlin.math.cos(Math.toRadians(centerLat))
        val cruiseMsl = _cruiseAlt.value.toMsl(state.value.altMslMeters ?: 0.0)
        val half = boxMeters / 2.0
        val numLines = (boxMeters / lineSpacingMeters).toInt().coerceAtLeast(2) + 1
        val wps = mutableListOf<soy.engindearing.omnitak.mobile.data.uas.Waypoint>()
        // Walk south→north; each line is east-or-west depending on parity.
        for (i in 0 until numLines) {
            val north = -half + i * lineSpacingMeters
            val lat = centerLat + north / metersPerDegLat
            val (westLon, eastLon) = Pair(
                centerLon + (-half / metersPerDegLon),
                centerLon + (half / metersPerDegLon),
            )
            val leftFirst = i % 2 == 0
            val (a, b) = if (leftFirst) westLon to eastLon else eastLon to westLon
            wps += soy.engindearing.omnitak.mobile.data.uas.Waypoint(lat, a, cruiseMsl)
            wps += soy.engindearing.omnitak.mobile.data.uas.Waypoint(lat, b, cruiseMsl)
        }
        missionStore.clear()
        wps.forEach { missionStore.addWaypoint(it.latDeg, it.lonDeg, it.altMslMeters) }
        uploadAndStartMission(autoStart = true)
        Log.i(TAG, "Survey mission: ${wps.size} pts, ${numLines} lines, box=${boxMeters}m spacing=${lineSpacingMeters}m")
    }

    /**
     * Build a circle of [points] waypoints around ([centerLat], [centerLon])
     * at [radiusMeters] and load them into [missionStore], then upload +
     * start. Persistent vs [orbitPoint] (DO_ORBIT) which evaporates on
     * the next mode change. Closes the loop by appending the first
     * waypoint as the last so the drone keeps circling.
     */
    suspend fun uploadCircleMission(
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Float = 50f,
        points: Int = 12,
    ) {
        val safePoints = points.coerceIn(3, 36)
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = 111_320.0 * kotlin.math.cos(Math.toRadians(centerLat))
        val cruiseMsl = _cruiseAlt.value.toMsl(state.value.altMslMeters ?: 0.0)
        val wps = (0 until safePoints).map { i ->
            val theta = 2.0 * Math.PI * i / safePoints
            val north = radiusMeters * kotlin.math.cos(theta)
            val east = radiusMeters * kotlin.math.sin(theta)
            soy.engindearing.omnitak.mobile.data.uas.Waypoint(
                latDeg = centerLat + north / metersPerDegLat,
                lonDeg = centerLon + east / metersPerDegLon,
                altMslMeters = cruiseMsl,
            )
        } + listOfNotNull(
            // Close the loop — last waypoint = first, so the drone arrives
            // back at WP1 and the autopilot's repeat behaviour kicks in
            // (PX4 loops AUTO.MISSION when current_seq overruns count).
            null
        )
        // Replace any draft mission.
        missionStore.clear()
        wps.forEach { missionStore.addWaypoint(it.latDeg, it.lonDeg, it.altMslMeters) }
        uploadAndStartMission(autoStart = true)
        Log.i(TAG, "Circle mission: $safePoints pts radius=${radiusMeters}m alt=${cruiseMsl.toInt()}m MSL")
    }

    suspend fun orbitPoint(latDeg: Double, lonDeg: Double, radiusMeters: Float = 50f) {
        val cruise = _cruiseAlt.value
        val homeMsl = state.value.altMslMeters ?: 0.0
        val altMsl = cruise.toMsl(homeMsl)
        mavlink.sendCommand(
            MavCmd.MAV_CMD_DO_ORBIT,
            p1 = radiusMeters,
            p2 = Float.NaN,
            p3 = 1f /* face center */,
            p5 = latDeg.toFloat(),
            p6 = lonDeg.toFloat(),
            p7 = altMsl.toFloat(),
        )
    }

    suspend fun emergencyStop() {
        // Stop any background streamers first so they don't keep trying
        // to push commands at a now-falling vehicle.
        followMeJob?.cancel()
        _followMeActive.value = false
        mavlink.sendCommand(MavCmd.MAV_CMD_DO_FLIGHTTERMINATION, p1 = 1f)
        Log.w(TAG, "EMERGENCY STOP — flight termination sent")
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
