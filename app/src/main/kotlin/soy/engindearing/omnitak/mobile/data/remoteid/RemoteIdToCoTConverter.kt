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

    /**
     * Convert a renderable track to a CoT event. Returns null if
     * the track doesn't have a valid lat/lon yet.
     */
    fun toCoT(track: RemoteIdTrack): CoTEvent? {
        val loc = track.lastLocation?.takeIf { it.hasValidPosition } ?: return null

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
}
