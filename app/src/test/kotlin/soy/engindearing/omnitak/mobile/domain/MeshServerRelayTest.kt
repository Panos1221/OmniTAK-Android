package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.CoTSource
import soy.engindearing.omnitak.mobile.domain.MeshServerRelay.RelayInputs
import soy.engindearing.omnitak.mobile.domain.MeshServerRelay.RelayTarget

/**
 * #179 — exhaustive coverage of the PURE relay decision [MeshServerRelay.relayTarget]
 * and the per-uid dedup/throttle gate [MeshServerRelay.admitForward]:
 *  - mesh-origin → server
 *  - server-origin → mesh
 *  - loop block both ways (never relayed back onto its own transport)
 *  - disabled → none
 *  - one-transport-down → none
 *  - type filtering server→mesh (only meaningful types ride LoRa)
 *  - dedup (mesh→server ping-pong) + throttle (server→mesh airtime)
 */
class MeshServerRelayTest {

    private fun event(uid: String = "U1", type: String = "a-f-G-U-C"): CoTEvent =
        CoTEvent(uid = uid, type = type, lat = 1.0, lon = 2.0)

    private fun inputs(
        source: CoTSource?,
        type: String = "a-f-G-U-C",
        serverConnected: Boolean = true,
        meshConnected: Boolean = true,
        enabled: Boolean = true,
    ) = RelayInputs(
        event = event(type = type),
        source = source,
        serverConnected = serverConnected,
        meshConnected = meshConnected,
        enabled = enabled,
    )

    // region direction --------------------------------------------------------

    @Test
    fun `mesh origin relays to server`() {
        assertEquals(
            RelayTarget.TO_SERVER,
            MeshServerRelay.relayTarget(inputs(CoTSource.mesh("Meshtastic"))),
        )
    }

    @Test
    fun `server origin relays to mesh`() {
        assertEquals(
            RelayTarget.TO_MESH,
            MeshServerRelay.relayTarget(inputs(CoTSource.takServer("HQ"))),
        )
    }

    // region loop block -------------------------------------------------------

    @Test
    fun `mesh origin never goes back to mesh`() {
        // The ONLY non-NONE result for a mesh-origin event is TO_SERVER.
        val target = MeshServerRelay.relayTarget(inputs(CoTSource.mesh("MeshCore")))
        assertTrue(target == RelayTarget.TO_SERVER)
        assertFalse(target == RelayTarget.TO_MESH)
    }

    @Test
    fun `server origin never goes back to server`() {
        val target = MeshServerRelay.relayTarget(inputs(CoTSource.takServer("HQ")))
        assertTrue(target == RelayTarget.TO_MESH)
        assertFalse(target == RelayTarget.TO_SERVER)
    }

    // region gating -----------------------------------------------------------

    @Test
    fun `disabled relays nothing`() {
        assertEquals(
            RelayTarget.NONE,
            MeshServerRelay.relayTarget(inputs(CoTSource.mesh("Meshtastic"), enabled = false)),
        )
        assertEquals(
            RelayTarget.NONE,
            MeshServerRelay.relayTarget(inputs(CoTSource.takServer("HQ"), enabled = false)),
        )
    }

    @Test
    fun `server down relays nothing even for mesh origin`() {
        assertEquals(
            RelayTarget.NONE,
            MeshServerRelay.relayTarget(inputs(CoTSource.mesh("Meshtastic"), serverConnected = false)),
        )
    }

    @Test
    fun `mesh down relays nothing even for server origin`() {
        assertEquals(
            RelayTarget.NONE,
            MeshServerRelay.relayTarget(inputs(CoTSource.takServer("HQ"), meshConnected = false)),
        )
    }

    @Test
    fun `both down relays nothing`() {
        assertEquals(
            RelayTarget.NONE,
            MeshServerRelay.relayTarget(
                inputs(CoTSource.mesh("Meshtastic"), serverConnected = false, meshConnected = false),
            ),
        )
    }

    // region source classification -------------------------------------------

    @Test
    fun `local origin relays nothing`() {
        assertEquals(
            RelayTarget.NONE,
            MeshServerRelay.relayTarget(inputs(CoTSource.LOCAL)),
        )
    }

    @Test
    fun `untagged origin relays nothing (loop-safe default)`() {
        assertEquals(
            RelayTarget.NONE,
            MeshServerRelay.relayTarget(inputs(null)),
        )
    }

    @Test
    fun `other transport relays nothing`() {
        assertEquals(
            RelayTarget.NONE,
            MeshServerRelay.relayTarget(inputs(CoTSource(CoTSource.Transport.OTHER, "x"))),
        )
    }

    // region server->mesh type filter ----------------------------------------

    @Test
    fun `meaningful server types ride down to mesh`() {
        assertTrue(MeshServerRelay.isRelayableToMesh("a-f-G-U-C")) // PLI
        assertTrue(MeshServerRelay.isRelayableToMesh("a-h-G"))     // hostile marker
        assertTrue(MeshServerRelay.isRelayableToMesh("a-u-G"))     // unknown marker
        assertTrue(MeshServerRelay.isRelayableToMesh("b-t-f"))     // GeoChat
        assertTrue(MeshServerRelay.isRelayableToMesh("b-m-p-w"))   // waypoint
    }

    @Test
    fun `chatter server types do not ride down to mesh`() {
        assertFalse(MeshServerRelay.isRelayableToMesh("t-x-d-d"))  // delete tasking
        assertFalse(MeshServerRelay.isRelayableToMesh("t-x-c-t"))  // ping
        assertFalse(MeshServerRelay.isRelayableToMesh("b-i-x-i"))  // sensor/image
        assertFalse(MeshServerRelay.isRelayableToMesh("b-r-f-h-c")) // medevac request etc
    }

    @Test
    fun `server origin with chatter type relays nothing`() {
        assertEquals(
            RelayTarget.NONE,
            MeshServerRelay.relayTarget(inputs(CoTSource.takServer("HQ"), type = "t-x-d-d")),
        )
    }

    @Test
    fun `mesh origin relays to server regardless of type`() {
        // mesh→server is type-agnostic (IP is cheap); even a tasking event flows.
        assertEquals(
            RelayTarget.TO_SERVER,
            MeshServerRelay.relayTarget(inputs(CoTSource.mesh("Meshtastic"), type = "t-x-d-d")),
        )
    }

    // region dedup / throttle gate -------------------------------------------

    private fun newRelay(): MeshServerRelay = MeshServerRelay(
        sendToServer = { true },
        sendToMesh = { true },
        eventToServerXml = { "" },
        serverConnected = { true },
        meshConnected = { true },
        relayEnabled = { true },
    )

    @Test
    fun `server-to-mesh throttles repeated uid inside the window`() {
        val r = newRelay()
        val t0 = 1_000_000L
        assertTrue("first send admitted", r.admitForward("U1", RelayTarget.TO_MESH, t0))
        // 10s later — still inside the 30s LoRa throttle window.
        assertFalse(
            "second send within throttle window blocked",
            r.admitForward("U1", RelayTarget.TO_MESH, t0 + 10_000L),
        )
        // After the window elapses, a fresh send is admitted.
        assertTrue(
            "send after throttle window admitted",
            r.admitForward("U1", RelayTarget.TO_MESH, t0 + MeshServerRelay.serverToMeshThrottleMs + 1L),
        )
    }

    @Test
    fun `mesh-to-server dedup swallows the immediate ping-pong echo`() {
        val r = newRelay()
        val t0 = 2_000_000L
        assertTrue(r.admitForward("U2", RelayTarget.TO_SERVER, t0))
        // The far side re-broadcasts; the echo arrives ~1s later → suppressed.
        assertFalse(
            "echo within dedup window blocked",
            r.admitForward("U2", RelayTarget.TO_SERVER, t0 + 1_000L),
        )
        // Past the small dedup window, a genuine fresh update gets through.
        assertTrue(
            "update past dedup window admitted",
            r.admitForward("U2", RelayTarget.TO_SERVER, t0 + MeshServerRelay.dedupWindowMs + 1L),
        )
    }

    @Test
    fun `dedup is keyed per uid and per direction`() {
        val r = newRelay()
        val t0 = 3_000_000L
        // Same uid going opposite directions does not share a throttle bucket.
        assertTrue(r.admitForward("U3", RelayTarget.TO_MESH, t0))
        assertTrue(r.admitForward("U3", RelayTarget.TO_SERVER, t0))
        // Different uid is independent.
        assertTrue(r.admitForward("U4", RelayTarget.TO_MESH, t0))
        // ...but the same uid+direction repeats are still gated.
        assertFalse(r.admitForward("U3", RelayTarget.TO_MESH, t0 + 1L))
    }

    @Test
    fun `reset clears the throttle state`() {
        val r = newRelay()
        val t0 = 4_000_000L
        assertTrue(r.admitForward("U5", RelayTarget.TO_MESH, t0))
        assertFalse(r.admitForward("U5", RelayTarget.TO_MESH, t0 + 1L))
        r.reset()
        assertTrue("after reset the uid is admitted again", r.admitForward("U5", RelayTarget.TO_MESH, t0 + 2L))
    }

    @Test
    fun `none target is never admitted`() {
        val r = newRelay()
        assertFalse(r.admitForward("U6", RelayTarget.NONE, 5_000_000L))
    }
}
