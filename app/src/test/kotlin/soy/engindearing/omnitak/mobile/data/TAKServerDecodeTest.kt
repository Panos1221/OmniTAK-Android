package soy.engindearing.omnitak.mobile.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TAKServer JSON round-trip guarantees that matter for upgrades:
 * pre-0.36 blobs (no `allowUntrustedTls` field) must decode with the
 * bypass OFF, and the explicit opt-in must survive a round trip.
 */
class TAKServerDecodeTest {

    // Mirror TAKServerStore's configuration.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun preExistingJsonWithoutAllowUntrustedTlsDecodesToFalse() {
        val raw = """
            [{"id":"abc","name":"Legacy","host":"tak.example.com","port":8089,
              "protocol":"tls","useTLS":true,"enabled":true,"isDefault":false,
              "certificateName":"client.p12","caCertificateName":null,
              "username":"op","password":"secret","certificatePassword":"atakatak"}]
        """.trimIndent()
        val list = json.decodeFromString<List<TAKServer>>(raw)
        assertEquals(1, list.size)
        assertFalse("legacy blobs must default to validated TLS", list[0].allowUntrustedTls)
    }

    @Test
    fun allowUntrustedTlsSurvivesRoundTrip() {
        val server = TAKServer(
            name = "Lab",
            host = "10.0.0.5",
            port = 8089,
            useTLS = true,
            allowUntrustedTls = true,
        )
        val decoded = json.decodeFromString<List<TAKServer>>(
            json.encodeToString(kotlinx.serialization.serializer(), listOf(server)),
        )
        assertTrue(decoded[0].allowUntrustedTls)
    }
}
