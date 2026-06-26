package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Router-level parity for [MeshChannelShare]: one parse() entry point must
 * detect whether a scanned link is Meshtastic or MeshCore and decode it with
 * the matching codec, exactly as the iOS `MeshChannelShare` does.
 */
class MeshChannelShareTest {

    @Test
    fun `parse detects Meshtastic link`() {
        val link = "https://meshtastic.org/e/#ChsSEAMKERgfJi00O0JJUFdeZWwaB09tbmlUQUs"
        val result = MeshChannelShare.parse(link)

        assertTrue(result is MeshChannelImport.Meshtastic)
        val channels = (result as MeshChannelImport.Meshtastic).channels
        assertEquals(1, channels.size)
        assertEquals("OmniTAK", channels[0].name)
        assertArrayEquals(ByteArray(16) { (it * 7 + 3).toByte() }, channels[0].psk)
    }

    @Test
    fun `parse detects MeshCore link`() {
        val link = "meshcore://channel/add?name=Public&secret=8b3387e9c5cdea6ac9e5edbaa115cd72"
        val result = MeshChannelShare.parse(link)

        assertTrue(result is MeshChannelImport.MeshCore)
        val ch = (result as MeshChannelImport.MeshCore).channel
        assertEquals("Public", ch.name)
        assertEquals(16, ch.secret.size)
    }

    @Test
    fun `parse trims surrounding whitespace`() {
        val link = "  meshcore://channel/add?name=Public&secret=8b3387e9c5cdea6ac9e5edbaa115cd72  \n"
        val result = MeshChannelShare.parse(link)
        assertTrue(result is MeshChannelImport.MeshCore)
    }

    @Test
    fun `parse returns null for unknown payload`() {
        assertNull(MeshChannelShare.parse("hello world"))
        assertNull(MeshChannelShare.parse("https://example.com/foo"))
    }

    @Test
    fun `shareURL meshtastic builds a link, null when empty`() {
        val ch = MeshChannel(name = "OmniTAK", psk = ByteArray(16) { (it * 7 + 3).toByte() })
        val url = MeshChannelShare.shareURL(MeshShareTransport.MESHTASTIC, meshtastic = listOf(ch))
        assertEquals("https://meshtastic.org/e/#ChsSEAMKERgfJi00O0JJUFdeZWwaB09tbmlUQUs", url)

        assertNull(MeshChannelShare.shareURL(MeshShareTransport.MESHTASTIC, meshtastic = emptyList()))
    }

    @Test
    fun `shareURL meshcore builds a link, null when absent`() {
        val ch = MeshCoreChannel(name = "Public", secret = ByteArray(0))
        val url = MeshChannelShare.shareURL(MeshShareTransport.MESHCORE, meshcore = ch)
        assertEquals("meshcore://channel/add?name=Public", url)

        assertNull(MeshChannelShare.shareURL(MeshShareTransport.MESHCORE, meshcore = null))
    }

    @Test
    fun `shareURL then parse round-trips for both transports`() {
        val mt = MeshChannel(name = "Cmd", psk = ByteArray(16) { it.toByte() })
        val mtUrl = MeshChannelShare.shareURL(MeshShareTransport.MESHTASTIC, meshtastic = listOf(mt))!!
        val mtBack = MeshChannelShare.parse(mtUrl)
        assertTrue(mtBack is MeshChannelImport.Meshtastic)

        val mc = MeshCoreChannel(name = "Recon", secret = ByteArray(16) { (it + 1).toByte() })
        val mcUrl = MeshChannelShare.shareURL(MeshShareTransport.MESHCORE, meshcore = mc)!!
        val mcBack = MeshChannelShare.parse(mcUrl)
        assertTrue(mcBack is MeshChannelImport.MeshCore)
        assertArrayEquals(mc.secret, (mcBack as MeshChannelImport.MeshCore).channel.secret)
    }
}
