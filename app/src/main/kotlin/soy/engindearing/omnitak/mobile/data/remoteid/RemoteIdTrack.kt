package soy.engindearing.omnitak.mobile.data.remoteid

/**
 * Live state of one drone being tracked, aggregated across the
 * stream of OpenDroneID messages the scanner sees.
 *
 * A single broadcast cycle from a drone is typically Basic ID +
 * Location (and optionally System / OperatorID etc.) — we need both
 * the ID (to dedupe frames into one track) and the Location (to
 * actually plot a point), so this class accumulates them until we
 * have enough to emit a CoT event.
 */
data class RemoteIdTrack(
    /** Stable identifier — the UAS ID from Basic ID. */
    val uasId: String,
    val uaType: OpenDroneIdMessage.UaType,
    val idType: OpenDroneIdMessage.IdType,
    val lastLocation: OpenDroneIdMessage.Location?,
    /** Monotonic clock (System.nanoTime / 1_000_000) of the last update. */
    val lastUpdateMs: Long,
    /** Operator (pilot) location, when broadcast. Often present before the
     *  drone has a GPS fix, so it can be the only plottable point we have. */
    val lastOperatorLocation: OpenDroneIdMessage.OperatorLocation? = null,
) {
    /** True when we have an identifier and at least one plottable point —
     *  the drone's own GPS, or the operator/pilot location. */
    val isRenderable: Boolean
        get() = uasId.isNotEmpty() &&
            (lastLocation?.hasValidPosition == true || lastOperatorLocation?.hasValidPosition == true)
}
