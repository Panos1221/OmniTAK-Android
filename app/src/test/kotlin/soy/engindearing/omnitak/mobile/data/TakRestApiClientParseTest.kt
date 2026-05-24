package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Marti API envelope tolerance — the reason multi-server sync exists. The
 * 4-server matrix exposed three list shapes; the parser must handle all of
 * them and never throw on a dialect it doesn't recognise.
 */
class TakRestApiClientParseTest {

    private val client = TakRestApiClient(
        server = TAKServer(name = "t", host = "h", port = 8089),
        certVault = null,
    )

    @Test
    fun `TAK57 OTS data envelope parses missions`() {
        val body = """
            {"version":"3","type":"Mission","data":[
              {"name":"alpha","description":"first","contents":[{"hash":"a"},{"hash":"b"}]},
              {"name":"bravo"}
            ]}
        """.trimIndent()
        val missions = client.parseMissions(body)
        assertEquals(2, missions.size)
        assertEquals("alpha", missions[0].name)
        assertEquals("first", missions[0].description)
        assertEquals(2, missions[0].contentCount)
        assertEquals(0, missions[1].contentCount)
    }

    @Test
    fun `taky results envelope parses data packages`() {
        val body = """
            {"resultCount":1,"results":[
              {"hash":"abc123","name":"overlay.zip","size":2048,"mimeType":"application/zip"}
            ]}
        """.trimIndent()
        val pkgs = client.parseDataPackages(body)
        assertEquals(1, pkgs.size)
        assertEquals("abc123", pkgs[0].hash)
        assertEquals("overlay.zip", pkgs[0].name)
        assertEquals(2048L, pkgs[0].size)
    }

    @Test
    fun `bare top-level array parses`() {
        val body = """[{"name":"solo"}]"""
        val missions = client.parseMissions(body)
        assertEquals(1, missions.size)
        assertEquals("solo", missions[0].name)
    }

    @Test
    fun `unrecognised or empty body yields empty list, never throws`() {
        assertTrue(client.parseMissions("").isEmpty())
        assertTrue(client.parseMissions("not json").isEmpty())
        assertTrue(client.parseDataPackages("""{"unexpected":true}""").isEmpty())
        // an entry missing the required key is skipped, not fatal
        assertTrue(client.parseMissions("""{"data":[{"description":"no name"}]}""").isEmpty())
    }
}
