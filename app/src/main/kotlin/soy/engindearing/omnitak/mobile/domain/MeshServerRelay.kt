package soy.engindearing.omnitak.mobile.domain

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.CoTSource

/**
 * #179 — CoT relay between the LoRa mesh and the TAK server (ATAK
 * Meshtastic-gateway parity).
 *
 * When the device is connected to BOTH a TAK server AND a mesh, and the
 * operator has turned the gateway on, this coordinator bridges CoT both ways:
 * mesh-only nodes get pushed up to the server (so the wider TAK network sees
 * them), and server contacts get pushed down to the mesh (so off-grid radios
 * see the server picture).
 *
 * This is powerful but dangerous if naive — a busy TAK server can flood the
 * LoRa channel and blow the radio's duty cycle. So the relay is built defensively:
 *
 *  - **Off by default.** Only relays when [RelayInputs.enabled] AND both
 *    transports are connected.
 *  - **Loop-proof.** The decision is driven entirely by the #180 [CoTSource]
 *    tag: a CoT that arrived from the mesh only ever goes UP to the server, a
 *    CoT that arrived from the server only ever goes DOWN to the mesh, and
 *    nothing is ever relayed back onto the transport it came from. A short
 *    per-uid+target recency window ([dedupWindowMs]) swallows the echo that
 *    comes back when the far side re-broadcasts the same uid (ping-pong).
 *  - **Throttled hard server→mesh** ([serverToMeshThrottleMs], default matching
 *    the existing per-uid mesh marker throttle), and only the meaningful CoT
 *    types (PLI/position, GeoChat, tactical markers) are sent down — chatter
 *    like t-x tasking / sensor / ack events stay off the air. mesh→server is
 *    looser (IP is cheap) and only deduped.
 *
 * The forwarding decision is the PURE function [relayTarget]; the per-uid
 * dedup/throttle gate is the PURE function [admitForward]. Both are
 * exhaustively unit-tested in `MeshServerRelayTest`. The instance just holds
 * the dedup state and wires the two pure functions to the injected send paths.
 */
class MeshServerRelay(
    /** Sends a CoT XML payload to every connected TAK server. Wired to
     *  `serverManager.sendCoT`. Returns true on accept. */
    private val sendToServer: suspend (xml: String) -> Boolean,
    /** Sends a CoT event over the active mesh transport. Wired to
     *  `activeMeshManager.sendCoTOverMesh`. Returns true on dispatch. */
    private val sendToMesh: suspend (event: CoTEvent) -> Boolean,
    /** Builds the CoT XML for a mesh-origin event being relayed up to the
     *  server. Wired to `CotBuilders.rebuildEvent` (preferring rawXml). */
    private val eventToServerXml: (CoTEvent) -> String,
    /** Snapshots whether ≥1 TAK server is connected. */
    private val serverConnected: () -> Boolean,
    /** Snapshots whether the active mesh transport is connected. */
    private val meshConnected: () -> Boolean,
    /** Snapshots the operator's relay/gateway toggle (default OFF). */
    private val relayEnabled: () -> Boolean,
    /** Injectable clock for deterministic dedup/throttle tests. */
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** Last wall-clock ms a (uid → target) pair was forwarded, for the
     *  dedup/throttle gate. ConcurrentHashMap: inbound streams fire from the
     *  server read-loop coroutines and the mesh frame coroutine concurrently. */
    private val lastForwardMs = ConcurrentHashMap<String, Long>()

    /**
     * Called for every inbound CoT, tagged with the transport it [source]
     * arrived on. Computes the relay decision and, if the dedup/throttle gate
     * admits it, forwards via the matching send path. Suspends only across the
     * actual send; the decision + gate are non-blocking.
     */
    suspend fun onInbound(event: CoTEvent, source: CoTSource?) {
        val target = relayTarget(
            RelayInputs(
                event = event,
                source = source,
                serverConnected = serverConnected(),
                meshConnected = meshConnected(),
                enabled = relayEnabled(),
            ),
        )
        when (target) {
            RelayTarget.NONE -> return
            RelayTarget.TO_SERVER -> {
                if (!admitForward(event.uid, target, clock())) return
                runCatching { sendToServer(eventToServerXml(event)) }
                    .onSuccess { ok ->
                        Log.i(TAG, "relay mesh→server uid=${event.uid} type=${event.type} ok=$ok")
                    }
                    .onFailure { Log.w(TAG, "relay mesh→server failed uid=${event.uid}: ${it.message}") }
            }
            RelayTarget.TO_MESH -> {
                if (!admitForward(event.uid, target, clock())) return
                runCatching { sendToMesh(event) }
                    .onSuccess { ok ->
                        Log.i(TAG, "relay server→mesh uid=${event.uid} type=${event.type} ok=$ok")
                    }
                    .onFailure { Log.w(TAG, "relay server→mesh failed uid=${event.uid}: ${it.message}") }
            }
        }
    }

    /**
     * Per-uid+target dedup / throttle gate. Pure given [lastForwardMs] and the
     * supplied [now]. Returns true (and records [now]) when the forward is
     * allowed; false when the same uid was forwarded to the same target inside
     * the applicable window.
     *
     *  - server→mesh ([RelayTarget.TO_MESH]) uses the hard LoRa throttle
     *    [serverToMeshThrottleMs] — at most one relay per uid per window.
     *  - mesh→server ([RelayTarget.TO_SERVER]) uses the cheap [dedupWindowMs]
     *    that only suppresses the immediate ping-pong echo.
     *
     * Internal (not private) so the unit test can exercise the real per-uid
     * state machine across simulated time.
     */
    internal fun admitForward(uid: String, target: RelayTarget, now: Long): Boolean {
        val window = when (target) {
            RelayTarget.TO_MESH -> serverToMeshThrottleMs
            RelayTarget.TO_SERVER -> dedupWindowMs
            RelayTarget.NONE -> return false
        }
        val key = "$target:$uid"
        val last = lastForwardMs[key]
        if (last != null && now - last < window) return false
        lastForwardMs[key] = now
        return true
    }

    /** Drop dedup state — e.g. on transport teardown so a reconnect starts clean. */
    fun reset() = lastForwardMs.clear()

    /** Inputs to the pure [relayTarget] decision. Grouped so the function
     *  signature stays readable and the test can build cases declaratively. */
    data class RelayInputs(
        val event: CoTEvent,
        val source: CoTSource?,
        val serverConnected: Boolean,
        val meshConnected: Boolean,
        val enabled: Boolean,
    )

    /** Where (if anywhere) an inbound CoT should be relayed. */
    enum class RelayTarget { NONE, TO_SERVER, TO_MESH }

    companion object {
        private const val TAG = "MeshServerRelay"

        /** Hard server→mesh per-uid throttle. Matches the existing per-uid
         *  marker send throttle in [MeshtasticManager] so the gateway never
         *  pushes a given uid onto LoRa faster than a local map-drop would. */
        const val serverToMeshThrottleMs: Long = 30_000L

        /** mesh→server dedup window. Just long enough to swallow the echo a
         *  re-broadcasting far side bounces back; IP relays are cheap so this
         *  stays small. */
        const val dedupWindowMs: Long = 5_000L

        /**
         * The PURE relay forwarding decision. No I/O, no state — given only the
         * event, its source transport, the two connection booleans and the
         * enable flag, returns which way (if any) to relay.
         *
         * Rules (in order):
         *  1. Gateway off → [RelayTarget.NONE].
         *  2. Either transport down → [RelayTarget.NONE] (a gateway needs both
         *     ends; relaying with one side down is pointless and risks queueing).
         *  3. Arrived from the mesh → [RelayTarget.TO_SERVER] (never back to mesh).
         *  4. Arrived from the server → [RelayTarget.TO_MESH], but ONLY for the
         *     meaningful CoT types ([isRelayableToMesh]) — never back to server.
         *  5. Anything else (LOCAL / OTHER / untagged) → [RelayTarget.NONE].
         *     Local self-markers are already broadcast by the dedicated
         *     [SelfPositionBroadcaster]; untagged events have no safe origin to
         *     reason about, so we refuse to relay them (loop-safe default).
         */
        fun relayTarget(inputs: RelayInputs): RelayTarget {
            if (!inputs.enabled) return RelayTarget.NONE
            if (!inputs.serverConnected || !inputs.meshConnected) return RelayTarget.NONE
            return when (inputs.source?.transport) {
                CoTSource.Transport.MESH -> RelayTarget.TO_SERVER
                CoTSource.Transport.TAK_SERVER ->
                    if (isRelayableToMesh(inputs.event.type)) RelayTarget.TO_MESH
                    else RelayTarget.NONE
                else -> RelayTarget.NONE
            }
        }

        /**
         * Whether a server-origin CoT [type] is worth spending LoRa airtime on.
         * To protect the channel + duty cycle we only push down the operationally
         * meaningful picture:
         *  - `a-*`     position/PLI + tactical markers (friendly/hostile/etc.)
         *  - `b-t-f`   GeoChat
         *  - `b-m-p-*` bookmark map points (waypoints / spot map)
         *
         * Everything else (`t-x-*` tasking/deletes, sensor `b-i-*`, ack/routing
         * chatter, etc.) is intentionally dropped — it's noise on a LoRa link.
         * Pure; unit-tested.
         */
        fun isRelayableToMesh(type: String): Boolean =
            type.startsWith("a-") ||
                type == "b-t-f" ||
                type.startsWith("b-m-p-")
    }
}
