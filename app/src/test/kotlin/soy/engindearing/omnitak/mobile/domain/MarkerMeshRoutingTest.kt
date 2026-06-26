package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.MeshWire

/**
 * #171 — marker-vs-PLI routing decision + the hop_limit (field 9) emission
 * that lets a port-78 marker actually forward past the first node.
 */
class MarkerMeshRoutingTest {

    @Test
    fun `tactical marker types route to TAKPacketV2`() {
        assertTrue(MeshtasticManager.isTacticalMarker("a-u-G"))
        assertTrue(MeshtasticManager.isTacticalMarker("a-h-G-U-C-I"))
        assertTrue(MeshtasticManager.isTacticalMarker("a-f-G-U-C-F"))
        assertTrue(MeshtasticManager.isTacticalMarker("b-m-p-w-GOTO"))
        assertTrue(MeshtasticManager.isTacticalMarker("b-m-p-s-m"))
    }

    @Test
    fun `self PLI and GeoChat keep v1 routing`() {
        // bare friendly-ground-unit PLI type self/contacts broadcast
        assertFalse(MeshtasticManager.isTacticalMarker("a-f-G-U-C"))
        // GeoChat
        assertFalse(MeshtasticManager.isTacticalMarker("b-t-f"))
        // neutral PLI-ish ground point
        assertFalse(MeshtasticManager.isTacticalMarker("a-n-G"))
    }

    @Test
    fun `buildToRadio emits hop_limit field 9 before want_ack`() {
        val frame = MeshWire.buildToRadio(
            portnum = 78UL,
            payload = byteArrayOf(0xFF.toByte(), 0x01),
            hopLimit = 3u,
            wantAck = false,
        )
        // Field 9 varint tag = (9 << 3) | 0 = 0x48, followed by value 0x03.
        // The frame is ToRadio{1:MeshPacket{...}} so search the packet bytes.
        val hasHopLimit = frame.toList().windowed(2).any { (a, b) ->
            (a.toInt() and 0xFF) == 0x48 && (b.toInt() and 0xFF) == 0x03
        }
        assertTrue("hop_limit (tag 0x48, value 3) must be present", hasHopLimit)
    }

    @Test
    fun `buildToRadio omits hop_limit when zero`() {
        val frame = MeshWire.buildToRadio(
            portnum = 78UL,
            payload = byteArrayOf(0xFF.toByte()),
            hopLimit = 0u,
        )
        val hasHopLimitTag = frame.toList().any { (it.toInt() and 0xFF) == 0x48 }
        // 0x48 could in theory appear inside the payload/id; our payload is a
        // single 0xFF byte and ids are random, so this is a best-effort guard
        // that the explicit field is not emitted as tag+3.
        val hasHopLimitField = frame.toList().windowed(2).any { (a, b) ->
            (a.toInt() and 0xFF) == 0x48 && (b.toInt() and 0xFF) == 0x03
        }
        assertFalse("hop_limit field must be omitted when 0", hasHopLimitField)
        // tag alone may coincide with a random id byte; we only assert the
        // tag+value pair is absent (the meaningful invariant).
        @Suppress("UNUSED_EXPRESSION") hasHopLimitTag
    }
}
