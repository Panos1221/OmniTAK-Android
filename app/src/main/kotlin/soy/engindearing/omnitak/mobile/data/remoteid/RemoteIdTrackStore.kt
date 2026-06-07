package soy.engindearing.omnitak.mobile.data.remoteid

/**
 * In-memory roster of [RemoteIdTrack]s keyed by UAS ID.
 *
 * Pure logic — no Android dependencies. The scanner feeds it
 * messages as they arrive, the converter pulls renderable tracks
 * out and turns them into CoT events.
 *
 * Two key behaviours:
 * 1. A single broadcast burst from a drone is typically Basic ID +
 *    Location in the same frame (BT5 Message Pack) or in
 *    consecutive frames (BT4 Legacy). We need both fields to render,
 *    so [ingest] merges them onto the same track keyed by UAS ID.
 * 2. Drones can fly out of range or land. [purgeStale] drops tracks
 *    that haven't received an update within [staleAfterMs] so the
 *    map doesn't accumulate ghosts.
 */
class RemoteIdTrackStore(
    private val staleAfterMs: Long = 30_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val tracks = mutableMapOf<String, RemoteIdTrack>()

    /** Snapshot of the current tracks. */
    fun snapshot(): List<RemoteIdTrack> = tracks.values.toList()

    /** Pull a single track by its UAS ID; null if unknown. */
    fun get(uasId: String): RemoteIdTrack? = tracks[uasId]

    /** Drop everything — used on scanner stop / app reset. */
    fun clear() {
        tracks.clear()
    }

    /**
     * Feed in a list of messages decoded from one BLE frame and
     * return the set of UAS IDs whose tracks changed in a way the
     * map should re-render. The empty set means "nothing to do."
     *
     * Note: messages from the same frame must share a UAS ID (the
     * Basic ID and Location in one broadcast cycle describe the
     * same drone). If a frame contains *only* a Location, we attach
     * it to the most recently seen UAS ID — Remote ID broadcasters
     * cycle through their messages, so a Location-only frame is
     * almost always from the drone that just broadcast its Basic ID
     * a few hundred ms earlier.
     *
     * @param messages decoded messages from one BLE advertisement
     * @param fallbackId if no Basic ID in this frame, attribute to
     *   this UAS ID — caller threads it through from the scanner's
     *   per-MAC-address cache.
     */
    fun ingest(
        messages: List<OpenDroneIdMessage>,
        fallbackId: String? = null,
    ): Set<String> {
        if (messages.isEmpty()) return emptySet()

        val basic = messages.firstOrNull { it is OpenDroneIdMessage.BasicId } as? OpenDroneIdMessage.BasicId
        val location = messages.firstOrNull { it is OpenDroneIdMessage.Location } as? OpenDroneIdMessage.Location
        val operator = messages.firstOrNull { it is OpenDroneIdMessage.OperatorLocation } as? OpenDroneIdMessage.OperatorLocation

        val uasId = basic?.uasId ?: fallbackId ?: return emptySet()
        if (uasId.isEmpty()) return emptySet()

        val now = clock()
        val previous = tracks[uasId]
        val updated = RemoteIdTrack(
            uasId = uasId,
            uaType = basic?.uaType ?: previous?.uaType ?: OpenDroneIdMessage.UaType.UNDECLARED,
            idType = basic?.idType ?: previous?.idType ?: OpenDroneIdMessage.IdType.NONE,
            lastLocation = location ?: previous?.lastLocation,
            lastUpdateMs = now,
            lastOperatorLocation = operator ?: previous?.lastOperatorLocation,
        )

        // Only emit when something actually changed (new sighting,
        // new position, new operator location, or new aircraft type).
        // PPLI-style identical updates don't need to thrash the map.
        val changed = when {
            previous == null -> true
            previous.lastLocation != updated.lastLocation -> true
            previous.lastOperatorLocation != updated.lastOperatorLocation -> true
            previous.uaType != updated.uaType -> true
            else -> false
        }

        tracks[uasId] = updated
        return if (changed) setOf(uasId) else emptySet()
    }

    /**
     * Remove tracks last updated more than [staleAfterMs] ago.
     * Returns the set of UAS IDs that were removed so callers can
     * tell ContactStore to drop their markers.
     */
    fun purgeStale(): Set<String> {
        val now = clock()
        val staleIds = tracks.entries
            .filter { now - it.value.lastUpdateMs > staleAfterMs }
            .map { it.key }
            .toSet()
        staleIds.forEach { tracks.remove(it) }
        return staleIds
    }
}
