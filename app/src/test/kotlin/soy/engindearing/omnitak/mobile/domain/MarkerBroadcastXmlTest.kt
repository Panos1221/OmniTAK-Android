package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.CoTEvent

/**
 * Dropped/edited markers must broadcast to the TAK server (audit:
 * "markers never broadcast — local-only ingest"). MapScreen's
 * MarkerEditSheet onSave and the Go-to-Coordinate drop path both build
 * their wire frame via CotBuilders.rebuildEvent(event, emptyList()) —
 * these tests pin the exact XML shape that goes out on that path.
 */
class MarkerBroadcastXmlTest {

    private fun markerEvent(
        uid: String = "local-1718000000000",
        callsign: String = "OP-1",
        remarks: String = "",
        hae: Double = 0.0,
    ) = CoTEvent(
        uid = uid,
        type = "a-f-G-U-C",
        lat = 47.6588,
        lon = -117.4260,
        hae = hae,
        callsign = callsign,
        remarks = remarks,
    )

    @Test fun marker_broadcast_xml_carries_envelope_and_position() {
        val xml = CotBuilders.rebuildEvent(markerEvent(hae = 123.5), destUids = emptyList())
        assertTrue("missing uid in:\n$xml", xml.contains("""uid="local-1718000000000""""))
        assertTrue("missing type in:\n$xml", xml.contains("""type="a-f-G-U-C""""))
        assertTrue("missing lat in:\n$xml", xml.contains("""lat="47.6588""""))
        assertTrue("missing lon in:\n$xml", xml.contains("""lon="-117.426""""))
        assertTrue("missing hae in:\n$xml", xml.contains("""hae="123.5""""))
        assertTrue("missing time attr in:\n$xml", xml.contains(" time=\""))
        assertTrue("missing stale attr in:\n$xml", xml.contains(" stale=\""))
    }

    @Test fun marker_broadcast_xml_carries_callsign_contact() {
        val xml = CotBuilders.rebuildEvent(markerEvent(callsign = "OP-1"), destUids = emptyList())
        assertTrue(
            "missing contact callsign in:\n$xml",
            xml.contains("""<contact callsign="OP-1"/>"""),
        )
    }

    @Test fun marker_broadcast_xml_escapes_remarks_and_has_no_dest() {
        val xml = CotBuilders.rebuildEvent(
            markerEvent(remarks = """observe & report <here>"""),
            destUids = emptyList(),
        )
        assertTrue(
            "remarks not escaped in:\n$xml",
            xml.contains("<remarks>observe &amp; report &lt;here&gt;</remarks>"),
        )
        // Broadcast (not directed) — no <dest> element may appear, or the
        // server would route it to a single EUD instead of everyone.
        assertFalse("unexpected <dest> in broadcast frame:\n$xml", xml.contains("<dest"))
    }

    @Test fun marker_broadcast_xml_omits_empty_remarks() {
        val xml = CotBuilders.rebuildEvent(markerEvent(remarks = ""), destUids = emptyList())
        assertFalse("empty remarks should be omitted in:\n$xml", xml.contains("<remarks>"))
    }
}
