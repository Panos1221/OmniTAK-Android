package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-platform parity gates for the Meshtastic + MeshCore channel-share
 * codecs. The two golden vectors here MUST stay byte-identical to the iOS
 * `MeshtasticChannelCodec` / `MeshCoreChannelCodec` so a channel shared from
 * an iPhone joins on Android (and vice versa) without re-keying.
 */
class MeshChannelCodecTest {

    // region Meshtastic ---------------------------------------------------

    /**
     * PARITY GATE: name "OmniTAK" + psk ByteArray(16){ (it*7+3) } MUST encode
     * to this exact URL — the same byte sequence iOS produces.
     */
    @Test
    fun `Meshtastic channel encodes to byte-identical parity URL`() {
        val psk = ByteArray(16) { (it * 7 + 3).toByte() }
        val channel = MeshChannel(name = "OmniTAK", psk = psk)

        val url = MeshtasticChannelCodec.encodeURL(listOf(channel))

        val expected = "https://meshtastic.org/e/#ChsSEAMKERgfJi00O0JJUFdeZWwaB09tbmlUQUs"
        assertEquals(expected, url)
    }

    @Test
    fun `Meshtastic parity URL round-trips back to the same channel`() {
        val psk = ByteArray(16) { (it * 7 + 3).toByte() }
        val original = MeshChannel(name = "OmniTAK", psk = psk)

        val url = MeshtasticChannelCodec.encodeURL(listOf(original))
        val decoded = MeshtasticChannelCodec.decodeURL(url)

        assertEquals(1, decoded?.size)
        assertEquals("OmniTAK", decoded!![0].name)
        assertArrayEquals(psk, decoded[0].psk)
    }

    @Test
    fun `Meshtastic decodes the bare base64url fragment`() {
        val fragment = "ChsSEAMKERgfJi00O0JJUFdeZWwaB09tbmlUQUs"
        val decoded = MeshtasticChannelCodec.decodeURL(fragment)

        assertEquals(1, decoded?.size)
        assertEquals("OmniTAK", decoded!![0].name)
        assertArrayEquals(ByteArray(16) { (it * 7 + 3).toByte() }, decoded[0].psk)
    }

    @Test
    fun `Meshtastic encodes uplink and downlink flags`() {
        val ch = MeshChannel(
            name = "Cmd",
            psk = ByteArray(16) { it.toByte() },
            uplinkEnabled = true,
            downlinkEnabled = true,
        )
        val url = MeshtasticChannelCodec.encodeURL(listOf(ch))
        val decoded = MeshtasticChannelCodec.decodeURL(url)

        assertEquals(1, decoded?.size)
        assertTrue(decoded!![0].uplinkEnabled)
        assertTrue(decoded[0].downlinkEnabled)
    }

    @Test
    fun `Meshtastic empty-psk channel round-trips with no crypto`() {
        val ch = MeshChannel(name = "Public", psk = ByteArray(0))
        val url = MeshtasticChannelCodec.encodeURL(listOf(ch))
        val decoded = MeshtasticChannelCodec.decodeURL(url)

        assertEquals(1, decoded?.size)
        assertEquals("Public", decoded!![0].name)
        assertEquals(0, decoded[0].psk.size)
    }

    @Test
    fun `Meshtastic malformed link returns null`() {
        assertNull(MeshtasticChannelCodec.decodeURL("https://example.com/not-a-channel"))
        assertNull(MeshtasticChannelCodec.decodeURL("https://meshtastic.org/e/#"))
        assertNull(MeshtasticChannelCodec.decodeURL("#"))
    }

    // region MeshCore -----------------------------------------------------

    /**
     * GOLDEN VECTOR: this exact iOS-emitted link MUST decode to "Public" + the
     * 16 secret bytes 0x8b…0x72.
     */
    @Test
    fun `MeshCore golden vector decodes to name and 16-byte secret`() {
        val link = "meshcore://channel/add?name=Public&secret=8b3387e9c5cdea6ac9e5edbaa115cd72"
        val decoded = MeshCoreChannelCodec.decodeURL(link)

        assertEquals("Public", decoded?.name)
        val expectedSecret = byteArrayOf(
            0x8b.toByte(), 0x33, 0x87.toByte(), 0xe9.toByte(),
            0xc5.toByte(), 0xcd.toByte(), 0xea.toByte(), 0x6a,
            0xc9.toByte(), 0xe5.toByte(), 0xed.toByte(), 0xba.toByte(),
            0xa1.toByte(), 0x15, 0xcd.toByte(), 0x72,
        )
        assertEquals(16, decoded!!.secret.size)
        assertArrayEquals(expectedSecret, decoded.secret)
    }

    @Test
    fun `MeshCore channel round-trips name and secret`() {
        val secret = ByteArray(16) { (255 - it * 9).toByte() }
        val original = MeshCoreChannel(name = "Recon", secret = secret)

        val url = MeshCoreChannelCodec.encodeURL(original)
        val decoded = MeshCoreChannelCodec.decodeURL(url)

        assertEquals("Recon", decoded?.name)
        assertArrayEquals(secret, decoded!!.secret)
    }

    @Test
    fun `MeshCore public channel encodes without a secret param`() {
        val ch = MeshCoreChannel(name = "Public", secret = ByteArray(0))
        val url = MeshCoreChannelCodec.encodeURL(ch)

        assertEquals("meshcore://channel/add?name=Public", url)
        val decoded = MeshCoreChannelCodec.decodeURL(url)
        assertEquals("Public", decoded?.name)
        assertEquals(0, decoded!!.secret.size)
    }

    @Test
    fun `MeshCore encodes secret as lowercase hex`() {
        val secret = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte())
        assertEquals("abcdef", MeshCoreChannelCodec.hex(secret))
    }

    @Test
    fun `MeshCore name with space round-trips`() {
        val ch = MeshCoreChannel(name = "Team Alpha", secret = ByteArray(0))
        val url = MeshCoreChannelCodec.encodeURL(ch)

        assertTrue("space encodes as %20: $url", url.contains("name=Team%20Alpha"))
        val decoded = MeshCoreChannelCodec.decodeURL(url)
        assertEquals("Team Alpha", decoded?.name)
    }

    @Test
    fun `MeshCore malformed secret returns null`() {
        // odd-length hex
        assertNull(MeshCoreChannelCodec.decodeURL("meshcore://channel/add?name=X&secret=8b3"))
        // non-hex digit
        assertNull(MeshCoreChannelCodec.decodeURL("meshcore://channel/add?name=X&secret=zz3387e9c5cdea6ac9e5edbaa115cd72"))
    }

    @Test
    fun `MeshCore wrong scheme or path returns null`() {
        assertNull(MeshCoreChannelCodec.decodeURL("meshcore://node/add?name=X"))
        assertNull(MeshCoreChannelCodec.decodeURL("meshcore://channel/remove?name=X"))
        assertNull(MeshCoreChannelCodec.decodeURL("https://channel/add?name=X"))
    }
}
