package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-platform parity for the Meshtastic TAKPacketV2 (port 78) marker codec.
 * Encode must produce the 0xFF-envelope uncompressed body iOS produces, and a
 * decode on either platform must recover uid / type / position / color /
 * iconset. These gates prove an iOS-dropped marker re-materialises on Android.
 */
class TakPacketV2CodecTest {

    private fun sampleMarker(remarks: String = "") = CoTEvent(
        uid = "marker-7f3a1b2c-dead-beef-0011-223344556677",
        type = "a-u-G",
        lat = 34.052235,
        lon = -118.243683,
        hae = 0.0,
        ce = 9999.0,
        le = 9999.0,
        callsign = "ALPHA1",
        remarks = remarks,
        iconsetPath = "COT_MAPPING_2525B/a-u/a-u-G",
        colorArgb = -16744448, // 0xFF008000
    )

    @Test
    fun `encode produces a 0xFF-envelope packet under 160 bytes`() {
        val packet = TakPacketV2Codec.encodeMarker(sampleMarker())

        assertNotNull(packet)
        assertEquals("first byte is the uncompressed envelope flag", 0xFF, packet!![0].toInt() and 0xFF)
        assertTrue("packet is ${packet.size} bytes, expected < 160", packet.size < 160)
    }

    @Test
    fun `marker round-trips uid type position color iconset`() {
        val original = sampleMarker()
        val packet = TakPacketV2Codec.encodeMarker(original)!!
        val decoded = TakPacketV2Codec.decode(packet)

        assertNotNull(decoded)
        decoded!!
        // uid preserved VERBATIM
        assertEquals(original.uid, decoded.uid)
        // raw CoT type restored
        assertEquals("a-u-G", decoded.type)
        // position within 1e-6 deg
        assertEquals(34.052235, decoded.lat, 1e-6)
        assertEquals(-118.243683, decoded.lon, 1e-6)
        // signed ARGB bit pattern preserved
        assertEquals(-16744448, decoded.colorArgb)
        // iconset path drives the receiving glyph
        assertEquals("COT_MAPPING_2525B/a-u/a-u-G", decoded.iconsetPath)
        // callsign preserved
        assertEquals("ALPHA1", decoded.callsign)
    }

    @Test
    fun `decode rejects a dict-compressed 0x00 envelope`() {
        val packet = TakPacketV2Codec.encodeMarker(sampleMarker())!!
        // Flip the envelope flag from 0xFF to 0x00 (dict-compressed; unsupported).
        val tampered = packet.copyOf()
        tampered[0] = 0x00
        assertNull(TakPacketV2Codec.decode(tampered))
    }

    @Test
    fun `decode rejects empty input`() {
        assertNull(TakPacketV2Codec.decode(ByteArray(0)))
    }

    @Test
    fun `size guard drops oversized remarks so the marker still fits`() {
        // A remark too large to fit alongside the marker body.
        val huge = "X".repeat(400)
        val packet = TakPacketV2Codec.encodeMarker(sampleMarker(remarks = huge))

        // Encoder strips remarks and retries — packet must still be produced
        // and stay within the LoRa wire budget.
        assertNotNull(packet)
        assertTrue(packet!!.size <= TakPacketV2Codec.MAX_WIRE_BYTES)

        val decoded = TakPacketV2Codec.decode(packet)
        assertNotNull(decoded)
        // remarks were dropped under the guard
        assertEquals("", decoded!!.remarks)
        // identity + position still intact
        assertEquals(sampleMarker().uid, decoded.uid)
        assertEquals(34.052235, decoded.lat, 1e-6)
    }

    @Test
    fun `small remarks survive when within budget`() {
        val packet = TakPacketV2Codec.encodeMarker(sampleMarker(remarks = "rally point"))!!
        val decoded = TakPacketV2Codec.decode(packet)
        assertEquals("rally point", decoded!!.remarks)
    }

    @Test
    fun `altitude HAE round-trips via sint32 zigzag`() {
        val event = sampleMarker().copy(hae = -250.0)
        val packet = TakPacketV2Codec.encodeMarker(event)!!
        val decoded = TakPacketV2Codec.decode(packet)
        assertEquals(-250.0, decoded!!.hae, 0.5)
    }
}
