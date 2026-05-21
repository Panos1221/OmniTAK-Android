package soy.engindearing.omnitak.mobile.data.uas

/**
 * Operator's chosen cruise altitude — what "fly here" and new waypoints
 * use. Held by [soy.engindearing.omnitak.mobile.domain.UASManager],
 * editable from the UAS altitude pill in the map HUD.
 *
 * AGL is the operator default — most pilots think in "X meters above my
 * takeoff point" and that's what the cruise slider in QGC/DJI Pilot
 * shows. MSL is exposed for advanced ops (search-and-rescue often plans
 * in MSL because terrain matters more than home position).
 */
enum class AltFrame { AGL, MSL }

data class CruiseAltitude(
    val meters: Double = 80.0,  // sensible default if no telemetry yet
    val frame: AltFrame = AltFrame.AGL,
) {
    /** Project this altitude to an MSL value the autopilot can accept.
     *  Needs the drone's current home/MSL ref for AGL → MSL math. */
    fun toMsl(homeAltMsl: Double): Double = when (frame) {
        AltFrame.MSL -> meters
        AltFrame.AGL -> homeAltMsl + meters
    }
}
