package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.CoTEvent

/**
 * #29 — CotBuilders.rebuildEvent must emit `<usericon iconsetpath="…">`
 * when the source CoTEvent carries one. Without that, receiving ATAK
 * has no way to render the FEMA glyph and falls all the way back to
 * the generic friendly-installation default.
 */
class CotBuildersUserIconTest {

    @Test fun rebuildEvent_emits_usericon_when_iconsetPath_present() {
        val event = CoTEvent(
            uid = "test-fema-cp",
            type = "a-f-G-I-U-T",
            lat = 47.6588,
            lon = -117.4260,
            callsign = "CP-1",
            iconsetPath = "COT_MAPPING_FEMA/incidentCommand/command_post",
        )
        val xml = CotBuilders.rebuildEvent(event, destUids = emptyList())
        assertTrue(
            "missing usericon tag in:\n$xml",
            xml.contains("""<usericon iconsetpath="COT_MAPPING_FEMA/incidentCommand/command_post"/>"""),
        )
    }

    @Test fun rebuildEvent_omits_usericon_when_iconsetPath_null() {
        val event = CoTEvent(
            uid = "test-plain",
            type = "a-f-G-U-C",
            lat = 47.6588,
            lon = -117.4260,
            callsign = "PLAIN",
        )
        val xml = CotBuilders.rebuildEvent(event, destUids = emptyList())
        assertFalse(
            "unexpected usericon tag in plain-marker XML:\n$xml",
            xml.contains("<usericon"),
        )
    }

    @Test fun rebuildEvent_xml_escapes_iconsetPath() {
        // Defensive — iconsetPath values should never contain quotes in
        // practice, but the emitter has to escape just in case so a
        // pathological payload can't break the XML doc.
        val event = CoTEvent(
            uid = "u",
            type = "a-f-G-I-U-T",
            lat = 0.0,
            lon = 0.0,
            iconsetPath = """foo"bar""",
        )
        val xml = CotBuilders.rebuildEvent(event, destUids = emptyList())
        assertTrue(
            "iconsetPath quote not escaped in:\n$xml",
            xml.contains("""foo&quot;bar"""),
        )
    }
}
