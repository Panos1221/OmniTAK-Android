package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #172 — byte-layout assertions for the channel-apply encoders. These are
 * protocol-defined (admin.proto / channel.proto / config.proto field numbers,
 * and the MeshCore companion "Set Channel" command), so they must match the
 * iOS encoder written in parallel. Asserting tags / lengths / enum values
 * keeps both platforms spec-faithful.
 */
class ChannelApplyEncoderTest {

    // region helpers ------------------------------------------------------

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    /** Does [needle] appear contiguously inside [this]? The admin blobs are
     *  wrapped in a ToRadio frame, so we assert the admin sub-bytes are
     *  present rather than reproducing the random packet id / framing. */
    private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (start in 0..size - needle.size) {
            for (i in needle.indices) if (this[start + i] != needle[i]) continue@outer
            return true
        }
        return false
    }

    // region Meshtastic set_channel --------------------------------------

    @Test
    fun `Meshtastic set_channel encodes psk name role with correct tags`() {
        val psk = ByteArray(16) { (it * 7 + 3).toByte() }
        val channel = MeshChannel(name = "OmniTAK", psk = psk)

        val frame = AdminMessageSerializer.buildSetChannel(channel, index = 0)

        // Ground-truth admin bytes (computed from admin/channel.proto field
        // numbers): set_channel=33 (tag 8a 02), Channel{ settings=2{ psk=2,
        // name=3 }, role=3=PRIMARY(1) }; index 0 omitted (proto3 default).
        val expectedAdmin = "8a021f121b1210030a11181f262d343b424950575e656c1a074f6d6e6954414b1801"
        val expectedBytes = expectedAdmin.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        assertTrue(
            "admin set_channel bytes must appear in frame; got ${frame.hex()}",
            frame.containsBytes(expectedBytes),
        )
    }

    @Test
    fun `Meshtastic set_channel at non-zero index sets SECONDARY role and index`() {
        val psk = ByteArray(16) { it.toByte() }
        val frame = AdminMessageSerializer.buildSetChannel(MeshChannel(name = "Cmd", psk = psk), index = 2)

        // Channel.index = 1 (varint) = 2, Channel.role = 3 (varint) = SECONDARY(2).
        // index field present: tag 08 02; role field: tag 18 02.
        assertTrue("index field 08 02 present", frame.containsBytes(byteArrayOf(0x08, 0x02)))
        assertTrue("role SECONDARY 18 02 present", frame.containsBytes(byteArrayOf(0x18, 0x02)))
    }

    // region Meshtastic set_config rebroadcast_mode ----------------------

    @Test
    fun `Meshtastic set_config rebroadcast KNOWN_ONLY encodes enum 3`() {
        val frame = AdminMessageSerializer.buildSetRebroadcastMode(RebroadcastMode.KNOWN_ONLY)

        // set_config=34 (tag 92 02), Config.device=1{ rebroadcast_mode=6 = 3 }.
        val expected = "9202040a023003".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertTrue("rebroadcast KNOWN_ONLY admin bytes present; got ${frame.hex()}", frame.containsBytes(expected))
    }

    @Test
    fun `RebroadcastMode wire ordinals match config_proto`() {
        assertEquals(0, RebroadcastMode.ALL.wire)
        assertEquals(2, RebroadcastMode.LOCAL_ONLY.wire)
        assertEquals(3, RebroadcastMode.KNOWN_ONLY.wire)
        assertEquals(4, RebroadcastMode.NONE.wire)
    }

    // region MeshCore CMD_SET_CHANNEL (0x20) -----------------------------

    @Test
    fun `MeshCore set_channel is 50 bytes with 0x20 opcode and null-padded name`() {
        val secret = byteArrayOf(
            0x8b.toByte(), 0x33, 0x87.toByte(), 0xe9.toByte(),
            0xc5.toByte(), 0xcd.toByte(), 0xea.toByte(), 0x6a,
            0xc9.toByte(), 0xe5.toByte(), 0xed.toByte(), 0xba.toByte(),
            0xa1.toByte(), 0x15, 0xcd.toByte(), 0x72,
        )
        val cmd = MeshCoreFrameCodec.buildSetChannel(index = 1, name = "Public", secret = secret)

        assertEquals("fixed 50-byte layout", 50, cmd.size)
        assertEquals("opcode 0x20", 0x20, cmd[0].toInt() and 0xFF)
        assertEquals("channel index", 1, cmd[1].toInt() and 0xFF)

        // Name "Public" UTF-8 at bytes 2..7, null padding through byte 33.
        assertEquals("Public", String(cmd, 2, 6, Charsets.UTF_8))
        for (i in 8 until 34) assertEquals("name null-padded at $i", 0, cmd[i].toInt() and 0xFF)

        // Secret verbatim at bytes 34..49.
        val outSecret = cmd.copyOfRange(34, 50)
        assertEquals(secret.hex(), outSecret.hex())
    }

    @Test
    fun `MeshCore public channel encodes all-zero secret`() {
        val cmd = MeshCoreFrameCodec.buildSetChannel(index = 0, name = "Public", secret = ByteArray(0))
        assertEquals(50, cmd.size)
        for (i in 34 until 50) assertEquals("public secret zero at $i", 0, cmd[i].toInt() and 0xFF)
    }

    @Test
    fun `MeshCore set_channel truncates an over-long name to 32 bytes`() {
        val longName = "X".repeat(40)
        val cmd = MeshCoreFrameCodec.buildSetChannel(index = 1, name = longName, secret = ByteArray(16) { 1 })
        assertEquals(50, cmd.size)
        // bytes 2..33 are all 'X' (32 of them); secret still intact after.
        for (i in 2 until 34) assertEquals('X'.code, cmd[i].toInt() and 0xFF)
    }
}
