package soy.engindearing.omnitak.mobile.data.uas

/**
 * Snapshot of what we know about a connected UAS at any moment.
 *
 * All fields are nullable — we accumulate them as MAVLink messages
 * arrive, rather than blocking the UI until everything is populated.
 * The UI surfaces "—" for null fields instead of refusing to render.
 *
 * The unit of telemetry update is "one MAVLink message" — when a
 * GLOBAL_POSITION_INT arrives we replace lat/lon/alt/heading/groundSpeed;
 * when a HEARTBEAT arrives we replace flightMode/armed; etc. The
 * StateFlow consumer (UI + CoT emitter) re-reads atomically.
 */
data class DroneState(
    val systemId: Int? = null,
    val componentId: Int? = null,

    // From HEARTBEAT
    val autopilot: String? = null,        // PX4 / ArduPilot / Generic / etc.
    val vehicleType: String? = null,      // Quadrotor / Fixed-wing / Hexarotor / ...
    val flightMode: String? = null,       // STABILIZED / AUTO.MISSION / RTL / ...
    val armed: Boolean? = null,
    val lastHeartbeat: Long? = null,      // monotonic ms

    // From GLOBAL_POSITION_INT
    val latDeg: Double? = null,
    val lonDeg: Double? = null,
    val altMslMeters: Double? = null,
    val altAglMeters: Double? = null,
    val headingDeg: Double? = null,
    val groundSpeedMps: Double? = null,
    val verticalSpeedMps: Double? = null,
    val lastPosition: Long? = null,

    // From SYS_STATUS / BATTERY_STATUS
    val batteryPct: Int? = null,
    val batteryVoltage: Double? = null,

    // From GPS_RAW_INT
    val gpsFix: String? = null,           // NO_FIX / FIX_2D / FIX_3D / DGPS / RTK_FLOAT / RTK_FIXED
    val gpsSatellites: Int? = null,

    // From STATUSTEXT — last N messages for the UI log strip
    val recentStatusText: List<String> = emptyList(),
) {
    /** True if we've had a HEARTBEAT in the last 5 s — RAS-A IOP §HEARTBEAT timeout. */
    fun isConnected(nowMs: Long = System.currentTimeMillis()): Boolean =
        lastHeartbeat?.let { nowMs - it < 5_000 } ?: false

    /** True if we have any usable position fix to emit as CoT. */
    fun hasFix(): Boolean = latDeg != null && lonDeg != null
}
