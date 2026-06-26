package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #180 — data-source tagging + detail-sheet labels. The transport is tagged at
 * the ingest point (server sink vs mesh sink); these lock the factory + label
 * logic the UI shows ("Source: …").
 */
class CoTSourceTest {

    @Test fun tak_server_label_includes_server_name() {
        val s = CoTSource.takServer("HQ-Server")
        assertEquals(CoTSource.Transport.TAK_SERVER, s.transport)
        assertEquals("HQ-Server", s.detail)
        assertEquals("TAK: HQ-Server", s.label)
    }

    @Test fun tak_server_without_a_name_falls_back_to_generic_label() {
        val s = CoTSource.takServer(null)
        assertNull(s.detail)
        assertEquals("TAK server", s.label)
        // Blank name is treated the same as null.
        assertNull(CoTSource.takServer("   ").detail)
    }

    @Test fun mesh_meshtastic_label() {
        assertEquals("Mesh: Meshtastic", CoTSource.mesh("Meshtastic").label)
    }

    @Test fun mesh_meshcore_label() {
        assertEquals("Mesh: MeshCore", CoTSource.mesh("MeshCore").label)
    }

    @Test fun mesh_without_framework_falls_back() {
        assertEquals("Mesh", CoTSource.mesh(null).label)
    }

    @Test fun local_label() {
        assertEquals(CoTSource.Transport.LOCAL, CoTSource.LOCAL.transport)
        assertEquals("Local", CoTSource.LOCAL.label)
    }

    @Test fun source_rides_along_on_a_cotevent_copy() {
        val base = CoTEvent(uid = "ALPHA-1", type = "a-f-G-U-C", lat = 1.0, lon = 2.0)
        assertNull("default CoTEvent has no source", base.source)
        val tagged = base.copy(source = CoTSource.mesh("Meshtastic"))
        assertEquals("Mesh: Meshtastic", tagged.source?.label)
    }
}
