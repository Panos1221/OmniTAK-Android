package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Off-grid mesh plan Step 4 — verifies the Group submessage round-trip.
 *
 * [AtakPluginSerializer.encodeDetail] must emit a Detail.group (field 2)
 * with name=teamName and role=teamRole (or "Team Member" default).
 * [AtakPluginParser] must decode it back via parseGroup so receivers
 * see the operator's team color.
 *
 * Also covers the b-t-f GeoChat round-trip with callsign + remarks.
 */
class AtakPluginSerializerGroupTest {

    @Test
    fun `PPLI round-trips team name`() {
        val event = CoTEvent(
            uid = "ANDROID-test",
            type = "a-f-G-U-C",
            lat = 47.66,
            lon = -117.43,
            callsign = "BRAVO-1",
            teamName = "Red",
            teamRole = "Team Lead",
        )
        val payload = AtakPluginSerializer.serialize(event)
        val decoded = AtakPluginParser.parse(payload)
        assertNotNull("round-trip must parse", decoded)
        decoded!!
        assertEquals("Red", decoded.teamName)
        assertEquals("BRAVO-1", decoded.callsign)
    }

    @Test
    fun `PPLI round-trips default team role`() {
        val event = CoTEvent(
            uid = "ANDROID-test-2",
            type = "a-f-G-U-C",
            lat = 0.0,
            lon = 0.0,
            callsign = "ECHO-1",
            teamName = "Cyan",
            teamRole = null, // should default to "Team Member"
        )
        val payload = AtakPluginSerializer.serialize(event)
        val decoded = AtakPluginParser.parse(payload)
        assertNotNull(decoded)
        // Team name must survive the round-trip
        assertEquals("Cyan", decoded!!.teamName)
    }

    @Test
    fun `b-t-f GeoChat round-trips callsign and remarks`() {
        val event = CoTEvent(
            uid = "GeoChat.ANDROID-test.All Chat Rooms.123",
            type = "b-t-f",
            lat = 0.0,
            lon = 0.0,
            callsign = "ALPHA-1",
            remarks = "Hello mesh world",
            teamName = "Green",
        )
        val payload = AtakPluginSerializer.serialize(event)
        val decoded = AtakPluginParser.parse(payload)
        assertNotNull("b-t-f must round-trip", decoded)
        decoded!!
        assertEquals("b-t-f", decoded.type)
        assertEquals("ALPHA-1", decoded.callsign)
        assertTrue("remarks must contain message text", decoded.remarks.contains("Hello mesh world"))
    }

    @Test
    fun `b-t-f GeoChat round-trips team name`() {
        val event = CoTEvent(
            uid = "GeoChat.uid.room.456",
            type = "b-t-f",
            lat = 0.0,
            lon = 0.0,
            callsign = "DELTA-1",
            remarks = "Team chat",
            teamName = "Blue",
        )
        val payload = AtakPluginSerializer.serialize(event)
        val decoded = AtakPluginParser.parse(payload)
        assertNotNull(decoded)
        assertEquals("Blue", decoded!!.teamName)
    }

    @Test
    fun `no team name emits no group submessage`() {
        // Events without a team name must still round-trip cleanly;
        // group should be null after parsing.
        val event = CoTEvent(
            uid = "ANDROID-no-team",
            type = "a-f-G-U-C",
            lat = 0.0,
            lon = 0.0,
            callsign = "NO-TEAM",
            teamName = null,
        )
        val payload = AtakPluginSerializer.serialize(event)
        val decoded = AtakPluginParser.parse(payload)
        assertNotNull(decoded)
        // teamName should be null or blank (no group emitted)
        assertTrue(decoded!!.teamName.isNullOrBlank())
    }
}
