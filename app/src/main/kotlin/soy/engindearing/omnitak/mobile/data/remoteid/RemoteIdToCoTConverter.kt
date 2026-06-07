package soy.engindearing.omnitak.mobile.data.remoteid

import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.remoteid.OpenDroneIdMessage.UaType

/**
 * Convert a [RemoteIdTrack] (aggregated stream of OpenDroneID
 * messages from one drone) into a [CoTEvent] the rest of the app
 * already knows how to render.
 *
 * Pure function, no I/O. The CoT type picks an `a-u-A-…` (unknown
 * air) family so the operator can manually promote to hostile if
 * the situation warrants — TAK-conservative default for unsolicited
 * traffic detected over RF.
 */
object RemoteIdToCoTConverter {

    /** Prefix every UID so they're recognisable in the contacts list. */
    private const val UID_PREFIX = "RID-"

    /** Operator/pilot marker UID prefix (one per tracked drone). */
    private const val OP_UID_PREFIX = "RID-OP-"

    /** Operator (pilot) is on the ground: unknown-ground so the operator
     *  can promote affiliation. Renders the SUGP / ground frame. */
    private const val OPERATOR_COT_TYPE = "a-u-G"

    /**
     * Map UA Type → CoT type. Drone class (multirotor) goes to
     * `a-u-A-M-H-Q` so the catalogue's SUAPMHQ---- rotor symbol
     * actually lights up; fixed-wing UAS use `a-u-A-M-F-Q`
     * (SUAPMFQ----); everything else falls back to plain `a-u-A`
     * (SUA---------).
     */
    private fun cotTypeFor(uaType: UaType): String = when (uaType) {
        UaType.HELICOPTER_OR_MULTIROTOR -> "a-u-A-M-H-Q"
        UaType.AEROPLANE,
        UaType.HYBRID_LIFT,
        UaType.GLIDER,
        UaType.GYROPLANE -> "a-u-A-M-F-Q"
        else -> "a-u-A"
    }

    /** The UIDs a track can own — used to clear markers on purge/loss. */
    fun uidsFor(track: RemoteIdTrack): List<String> =
        listOf(UID_PREFIX + track.uasId, OP_UID_PREFIX + track.uasId)

    /**
     * All renderable CoT events for a track: the drone (when it has a valid
     * GPS fix) and the operator/pilot (when broadcast). Either or both may be
     * present; an empty list means nothing is plottable yet.
     */
    fun toCoTs(track: RemoteIdTrack): List<CoTEvent> {
        val out = mutableListOf<CoTEvent>()
        track.lastLocation?.takeIf { it.hasValidPosition }?.let { out += droneCoT(track, it) }
        track.lastOperatorLocation?.takeIf { it.hasValidPosition }?.let { out += operatorCoT(track, it) }
        return out
    }

    /**
     * Drone-only CoT, or null if the drone has no valid GPS yet.
     * Kept for callers that only care about the aircraft marker.
     */
    fun toCoT(track: RemoteIdTrack): CoTEvent? =
        track.lastLocation?.takeIf { it.hasValidPosition }?.let { droneCoT(track, it) }

    private fun droneCoT(track: RemoteIdTrack, loc: OpenDroneIdMessage.Location): CoTEvent {
        // Callsign: keep the UAS ID readable. DJI serials are long
        // (16-20 chars) but operators care about the last few digits
        // — they're the unique part of a fleet. We surface the full
        // ID so it stays searchable.
        val callsign = "DRONE-${track.uasId}"

        val remarks = buildString {
            append("FAA Remote ID detection.")
            append(" UA: ").append(track.uaType.name)
            append(" / ID: ").append(track.idType.name)
            loc.geodeticAltitudeM?.let {
                append(" / Alt: ").append("%.0f".format(it)).append(" m MSL")
            }
            loc.heightAboveTakeoffM?.let {
                append(" / AGL: ").append("%.0f".format(it)).append(" m")
            }
            if (loc.groundSpeedMs > 0) {
                append(" / Speed: ").append("%.1f".format(loc.groundSpeedMs)).append(" m/s")
            }
            append(" / Heading: ").append(loc.trackDirectionDeg).append("°")
            track.lastOperatorLocation?.takeIf { it.hasValidPosition }?.let {
                append(" / Pilot: PILOT-").append(track.uasId)
            }
        }

        return CoTEvent(
            uid = UID_PREFIX + track.uasId,
            type = cotTypeFor(track.uaType),
            lat = loc.latitude,
            lon = loc.longitude,
            hae = loc.geodeticAltitudeM ?: 0.0,
            callsign = callsign,
            remarks = remarks,
        )
    }

    private fun operatorCoT(track: RemoteIdTrack, op: OpenDroneIdMessage.OperatorLocation): CoTEvent {
        val remarks = buildString {
            append("FAA Remote ID operator (pilot) location.")
            append(" Drone: DRONE-").append(track.uasId)
            if (track.lastLocation?.hasValidPosition != true) {
                append(" / drone GPS not yet acquired")
            }
        }
        return CoTEvent(
            uid = OP_UID_PREFIX + track.uasId,
            type = OPERATOR_COT_TYPE,
            lat = op.latitude,
            lon = op.longitude,
            hae = op.altitudeM ?: 0.0,
            callsign = "PILOT-${track.uasId}",
            remarks = remarks,
        )
    }
}
