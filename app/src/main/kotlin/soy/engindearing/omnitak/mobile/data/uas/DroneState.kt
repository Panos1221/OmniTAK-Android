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
    /** From BATTERY_STATUS — seconds until the autopilot expects the
     *  battery to be empty at current draw. null when no estimate. */
    val batteryTimeRemainingSec: Int? = null,
    /** Current draw, amps. null until BATTERY_STATUS arrives. */
    val batteryCurrentAmps: Double? = null,

    // From GPS_RAW_INT
    val gpsFix: String? = null,           // NO_FIX / FIX_2D / FIX_3D / DGPS / RTK_FLOAT / RTK_FIXED
    val gpsSatellites: Int? = null,
    /** Horizontal dilution of precision — lower is better. PX4 / ArduPilot
     *  pre-arm typically wants HDOP &lt; 1.5; tactical fixed-wing flight
     *  &lt; 2.0 is acceptable. */
    val gpsHdop: Double? = null,

    // From STATUSTEXT — last N messages for the UI log strip
    val recentStatusText: List<String> = emptyList(),
    /** Latest WARNING-or-worse STATUSTEXT, surfaced as a banner. Cleared
     *  by the UI after display. (severity, text, timeMs) */
    val latestAlert: Triple<Int, String, Long>? = null,

    /** Ring buffer of recent positions, oldest → newest. Used to render
     *  the drone's trail on the map. Capped at ~30 entries. */
    val trail: List<TrailPoint> = emptyList(),

    // From HOME_POSITION
    val homeLatDeg: Double? = null,
    val homeLonDeg: Double? = null,
    val homeAltMsl: Double? = null,

    // From VFR_HUD
    val airspeedMps: Double? = null,
    val throttlePct: Int? = null,

    // From WIND (ArduPilot) or WIND_COV (PX4) — direction is degrees
    // where wind is FROM (compass), speed is m/s horizontal.
    val windFromDeg: Double? = null,
    val windSpeedMps: Double? = null,

    /** Wall-clock ms when [armed] first flipped to true on this session.
     *  Used for the "FLT 03:24" elapsed timer in the HUD. */
    val armedAtMs: Long? = null,
) {
    /** True if we've had a HEARTBEAT in the last 5 s — RAS-A IOP §HEARTBEAT timeout. */
    fun isConnected(nowMs: Long = System.currentTimeMillis()): Boolean =
        lastHeartbeat?.let { nowMs - it < 5_000 } ?: false

    /** True if we have any usable position fix to emit as CoT. */
    fun hasFix(): Boolean = latDeg != null && lonDeg != null

    /** Air / ground / surface / sub class, derived from the MAV_TYPE.
     *  Drives the UI's vehicle-aware behaviour: ground vehicles drop the
     *  altitude semantics (no cruise, no takeoff, no AGL), use "Drive"
     *  verbs, and emit a ground CoT type. */
    val vehicleClass: VehicleClass
        get() = when (vehicleType) {
            "MAV_TYPE_GROUND_ROVER" -> VehicleClass.GROUND
            "MAV_TYPE_SURFACE_BOAT" -> VehicleClass.SURFACE
            "MAV_TYPE_SUBMARINE" -> VehicleClass.SUB
            null -> VehicleClass.UNKNOWN
            else -> VehicleClass.AIR
        }
}

/** Broad platform category for vehicle-aware UI + CoT typing. */
enum class VehicleClass { AIR, GROUND, SURFACE, SUB, UNKNOWN }

/** One sample on the drone's trail — lat/lon + when, so old samples can
 *  fade or be culled. */
data class TrailPoint(
    val latDeg: Double,
    val lonDeg: Double,
    val timeMs: Long,
)
