package soy.engindearing.omnitak.mobile.domain

import soy.engindearing.omnitak.mobile.data.CoTEvent

/**
 * Pure routing helper for inbound Meshtastic portnum-72 CoT events.
 *
 * Classifies whether a decoded [CoTEvent] should be routed to
 * [ChatStore] (as a GeoChat message) or [ContactStore] (as a map contact).
 *
 * Phase 1 off-grid plan (Step 3): a type "b-t-f" event is a GeoChat;
 * everything else (PPLI "a-f-G-U-C", markers, etc.) is a contact.
 *
 * This is intentionally a stateless object — no side-effects, easy to
 * unit-test without any Android dependencies.
 */
object MeshCoTRouter {

    /** Routing destination for an inbound mesh CoT event. */
    enum class Destination { CHAT, CONTACT }

    /**
     * Classify [event] into a routing destination.
     *  - "b-t-f" (Broadcast-TAK-friendly) → [Destination.CHAT]
     *  - everything else                   → [Destination.CONTACT]
     */
    fun classify(event: CoTEvent): Destination =
        if (event.type == "b-t-f") Destination.CHAT else Destination.CONTACT
}
