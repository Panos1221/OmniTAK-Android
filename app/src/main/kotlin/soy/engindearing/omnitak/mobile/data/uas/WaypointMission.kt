package soy.engindearing.omnitak.mobile.data.uas

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mission = ordered list of waypoints to be flown by the drone in
 * sequence. Day-one supports lat/lon + optional altitude override +
 * optional speed override. Gimbal pointing, loiter time, and image-
 * capture actions are all in the MAVLink mission spec but deferred —
 * each adds a `<sensor>`-style UI surface out of scope for the MVP.
 *
 * Altitudes are MSL meters because that's what GLOBAL_POSITION_INT
 * reports and what PX4 / ArduPilot expect on the wire when frame =
 * MAV_FRAME_GLOBAL.
 *
 * [altMslMeters] = 0.0 means "use cruise altitude" — the uploader fills
 * the actual MSL based on the operator's cruise pill. Operator can
 * override per-waypoint via the waypoint edit sheet (tap a pin on the
 * map). [speedMps] null = autopilot default.
 */
data class Waypoint(
    val latDeg: Double,
    val lonDeg: Double,
    val altMslMeters: Double = 0.0,
    val speedMps: Float? = null,
)

/** Phase of a mission as it travels from drawn → uploaded → executing. */
enum class MissionPhase {
    DRAFT,        // user adding waypoints, not yet sent
    UPLOADING,    // MISSION_COUNT / MISSION_REQUEST_INT / MISSION_ITEM_INT in flight
    UPLOADED,     // drone ACK'd MISSION_ACCEPTED
    STARTED,      // MAV_CMD_MISSION_START sent
    FAILED,       // drone rejected or upload error
}

data class WaypointMission(
    val waypoints: List<Waypoint> = emptyList(),
    val phase: MissionPhase = MissionPhase.DRAFT,
    val currentSeq: Int = -1,        // last MISSION_ITEM_REACHED seq, -1 if none yet
    val errorMessage: String? = null,
) {
    val isEmpty: Boolean get() = waypoints.isEmpty()
    val canUpload: Boolean get() = phase == MissionPhase.DRAFT && waypoints.size >= 1
}

/**
 * Single in-memory mission store. Replace the whole mission on each
 * edit — waypoint lists are short (rarely >50) so we don't bother with
 * incremental diffs.
 */
class MissionStore {
    private val _state = MutableStateFlow(WaypointMission())
    val state: StateFlow<WaypointMission> = _state.asStateFlow()

    fun addWaypoint(lat: Double, lon: Double, altMsl: Double = 0.0) {
        _state.value = _state.value.copy(
            waypoints = _state.value.waypoints + Waypoint(lat, lon, altMsl),
            phase = MissionPhase.DRAFT,
            errorMessage = null,
            currentSeq = -1,
        )
    }

    /** Replace [index] with [updated]. No-op if out of range. Used by
     *  the per-waypoint edit sheet to push altitude/speed overrides. */
    fun updateWaypoint(index: Int, updated: Waypoint) {
        val cur = _state.value
        if (index !in cur.waypoints.indices) return
        _state.value = cur.copy(
            waypoints = cur.waypoints.toMutableList().also { it[index] = updated },
            phase = MissionPhase.DRAFT,
            errorMessage = null,
            currentSeq = -1,
        )
    }

    fun removeWaypoint(index: Int) {
        val cur = _state.value
        if (index !in cur.waypoints.indices) return
        _state.value = cur.copy(
            waypoints = cur.waypoints.filterIndexed { i, _ -> i != index },
            phase = MissionPhase.DRAFT,
            errorMessage = null,
            currentSeq = -1,
        )
    }

    fun undoWaypoint() {
        val cur = _state.value
        if (cur.waypoints.isNotEmpty()) {
            _state.value = cur.copy(
                waypoints = cur.waypoints.dropLast(1),
                phase = MissionPhase.DRAFT,
                errorMessage = null,
                currentSeq = -1,
            )
        }
    }

    fun clear() {
        _state.value = WaypointMission()
    }

    /** Called by [UASManager] as it works through the upload handshake. */
    fun setPhase(phase: MissionPhase, error: String? = null) {
        _state.value = _state.value.copy(phase = phase, errorMessage = error)
    }

    fun setCurrentSeq(seq: Int) {
        _state.value = _state.value.copy(currentSeq = seq)
    }
}
