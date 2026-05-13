package soy.engindearing.omnitak.mobile.data.remoteid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenDroneIdParserTest {

    // ---- Test vector helpers --------------------------------------------

    /**
     * Build a 25-byte Basic ID message: header byte (type 0x0, version v),
     * id-type/ua-type byte, 20 bytes of UAS ID (ASCII, null-padded),
     * 3 reserved bytes.
     */
    private fun basicIdFrame(
        version: Int = 1,
        idType: Int = OpenDroneIdMessage.IdType.SERIAL_NUMBER.code,
        uaType: Int = OpenDroneIdMessage.UaType.HELICOPTER_OR_MULTIROTOR.code,
        uasId: String = "DJI-MAVIC3-12345678X",
    ): ByteArray {
        val buf = ByteArray(OpenDroneIdMessage.MESSAGE_TOTAL_BYTES)
        buf[0] = ((OpenDroneIdMessage.TYPE_BASIC_ID shl 4) or (version and 0xF)).toByte()
        buf[1] = (((idType and 0xF) shl 4) or (uaType and 0xF)).toByte()
        val idBytes = uasId.toByteArray(Charsets.US_ASCII)
        System.arraycopy(idBytes, 0, buf, 2, minOf(idBytes.size, 20))
        return buf
    }

    /**
     * Build a 25-byte Location message with the given lat/lon and a
     * minimum-viable status byte. Other fields are zeroed.
     */
    private fun locationFrame(
        version: Int = 1,
        latitude: Double = 47.6588,
        longitude: Double = -117.4260,
        geodeticAltM: Double? = 700.0,
        trackDeg: Int = 90,
        groundSpeedMs: Double = 10.0,
        operationalStatus: Int = OpenDroneIdMessage.OperationalStatus.AIRBORNE.code,
    ): ByteArray {
        val buf = ByteArray(OpenDroneIdMessage.MESSAGE_TOTAL_BYTES)
        buf[0] = ((OpenDroneIdMessage.TYPE_LOCATION shl 4) or (version and 0xF)).toByte()

        // Status byte: status (4 bits) | reserved (1) | height type (1) | EW (1) | SM (1)
        val ewSegment = if (trackDeg >= 180) 1 else 0
        // Use Speed Mult = 0 → 0.25 m/s per unit, range 0..63.75 m/s
        val sm = 0
        buf[1] = (((operationalStatus and 0xF) shl 4) or (ewSegment shl 1) or sm).toByte()

        // Track direction
        buf[2] = (if (trackDeg >= 180) trackDeg - 180 else trackDeg).toByte()

        // Ground speed @ SM=0 → byte = speed / 0.25
        buf[3] = (groundSpeedMs / 0.25).toInt().toByte()

        // Vertical speed (signed × 0.5 m/s) — leave zero.
        buf[4] = 0

        // Latitude — degrees × 1e7, little-endian int32
        val latRaw = (latitude * 1e7).toInt()
        writeInt32LE(buf, 5, latRaw)

        // Longitude — degrees × 1e7, little-endian int32
        val lonRaw = (longitude * 1e7).toInt()
        writeInt32LE(buf, 9, lonRaw)

        // Pressure altitude — leave zero (invalid)
        // Geodetic altitude — encoded
        if (geodeticAltM != null) {
            val rawAlt = ((geodeticAltM + 1000.0) / 0.5).toInt()
            writeUInt16LE(buf, 15, rawAlt)
        }

        // Height above takeoff — leave zero (invalid)
        // Accuracy nibbles — leave zero (unknown)
        // Timestamp — leave 0xFFFF (invalid sentinel)
        buf[22] = 0xFF.toByte()
        buf[23] = 0xFF.toByte()

        return buf
    }

    private fun writeInt32LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeUInt16LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    // ---- Basic ID -------------------------------------------------------

    @Test
    fun `basic id decodes uas id and ua type`() {
        val frame = basicIdFrame(uasId = "DJI-MAVIC3-12345")
        val msg = OpenDroneIdParser.parseMessage(frame) as OpenDroneIdMessage.BasicId
        assertEquals("DJI-MAVIC3-12345", msg.uasId)
        assertEquals(OpenDroneIdMessage.IdType.SERIAL_NUMBER, msg.idType)
        assertEquals(OpenDroneIdMessage.UaType.HELICOPTER_OR_MULTIROTOR, msg.uaType)
        assertEquals(1, msg.protocolVersion)
    }

    @Test
    fun `basic id trims null padding from short uas ids`() {
        // UAS ID is only 6 chars; the rest of the 20-byte field is null-padded.
        val frame = basicIdFrame(uasId = "ABC123")
        val msg = OpenDroneIdParser.parseMessage(frame) as OpenDroneIdMessage.BasicId
        assertEquals("ABC123", msg.uasId)
    }

    @Test
    fun `basic id maps fixed-wing ua type`() {
        val frame = basicIdFrame(uaType = OpenDroneIdMessage.UaType.AEROPLANE.code)
        val msg = OpenDroneIdParser.parseMessage(frame) as OpenDroneIdMessage.BasicId
        assertEquals(OpenDroneIdMessage.UaType.AEROPLANE, msg.uaType)
    }

    // ---- Location -------------------------------------------------------

    @Test
    fun `location decodes lat lon to seven decimals`() {
        val frame = locationFrame(latitude = 47.6588, longitude = -117.4260)
        val msg = OpenDroneIdParser.parseMessage(frame) as OpenDroneIdMessage.Location
        assertEquals(47.6588, msg.latitude, 0.0000001)
        assertEquals(-117.4260, msg.longitude, 0.0000001)
        assertTrue(msg.hasValidPosition)
    }

    @Test
    fun `location decodes track direction in the upper half by adding 180`() {
        val frame = locationFrame(trackDeg = 270)
        val msg = OpenDroneIdParser.parseMessage(frame) as OpenDroneIdMessage.Location
        assertEquals(270, msg.trackDirectionDeg)
    }

    @Test
    fun `location decodes ground speed at quarter-mps multiplier`() {
        val frame = locationFrame(groundSpeedMs = 10.0)
        val msg = OpenDroneIdParser.parseMessage(frame) as OpenDroneIdMessage.Location
        // SM=0 → 0.25 m/s per unit. 10 / 0.25 = 40 (clean), so round-trip is exact.
        assertEquals(10.0, msg.groundSpeedMs, 0.001)
    }

    @Test
    fun `location decodes geodetic altitude with offset and half-metre scaling`() {
        val frame = locationFrame(geodeticAltM = 700.0)
        val msg = OpenDroneIdParser.parseMessage(frame) as OpenDroneIdMessage.Location
        assertEquals(700.0, msg.geodeticAltitudeM ?: -999.0, 0.5)
    }

    @Test
    fun `location flags valid position when both coords decoded`() {
        val msg = OpenDroneIdParser.parseMessage(locationFrame()) as OpenDroneIdMessage.Location
        assertTrue(msg.hasValidPosition)
    }

    @Test
    fun `location flags invalid position when both coords are zero`() {
        val frame = locationFrame(latitude = 0.0, longitude = 0.0)
        val msg = OpenDroneIdParser.parseMessage(frame) as OpenDroneIdMessage.Location
        assertFalse(msg.hasValidPosition)
    }

    @Test
    fun `location returns null altitude when raw bytes are zero (sentinel)`() {
        val frame = locationFrame(geodeticAltM = null)
        val msg = OpenDroneIdParser.parseMessage(frame) as OpenDroneIdMessage.Location
        assertNull(msg.geodeticAltitudeM)
    }

    // ---- Unknown / malformed --------------------------------------------

    @Test
    fun `unrecognised message type returns Unknown instead of throwing`() {
        val buf = ByteArray(OpenDroneIdMessage.MESSAGE_TOTAL_BYTES)
        // Type 0x7 (reserved/unused) + version 0x1
        buf[0] = 0x71.toByte()
        val msg = OpenDroneIdParser.parseMessage(buf) as OpenDroneIdMessage.Unknown
        assertEquals(0x7, msg.messageType)
        assertEquals(0x1, msg.protocolVersion)
    }

    @Test
    fun `short buffer returns null rather than crashing`() {
        assertNull(OpenDroneIdParser.parseMessage(ByteArray(10)))
        assertNull(OpenDroneIdParser.parseMessage(ByteArray(0)))
    }

    @Test
    fun `negative offset returns null`() {
        assertNull(OpenDroneIdParser.parseMessage(ByteArray(50), offset = -1))
    }

    // ---- Message Pack ---------------------------------------------------

    @Test
    fun `message pack with basic id plus location yields both decoded messages`() {
        val basic = basicIdFrame(uasId = "PACK-TEST-001")
        val location = locationFrame(latitude = 47.6588, longitude = -117.4260)
        val pack = buildMessagePack(listOf(basic, location))

        val msgs = OpenDroneIdParser.parseMessagePack(pack)
        assertEquals(2, msgs.size)
        assertTrue(msgs[0] is OpenDroneIdMessage.BasicId)
        assertTrue(msgs[1] is OpenDroneIdMessage.Location)
        assertEquals("PACK-TEST-001", (msgs[0] as OpenDroneIdMessage.BasicId).uasId)
    }

    @Test
    fun `message pack with wrong single-message size yields empty list`() {
        val buf = ByteArray(3 + 25)
        buf[0] = (OpenDroneIdMessage.TYPE_MESSAGE_PACK shl 4 or 1).toByte()
        buf[1] = 30 // wrong — should be 25
        buf[2] = 1
        assertTrue(OpenDroneIdParser.parseMessagePack(buf).isEmpty())
    }

    @Test
    fun `message pack with truncated payload yields empty list`() {
        // Claims 3 messages but only carries 1 worth of bytes.
        val buf = ByteArray(3 + 25)
        buf[0] = (OpenDroneIdMessage.TYPE_MESSAGE_PACK shl 4 or 1).toByte()
        buf[1] = 25
        buf[2] = 3
        assertTrue(OpenDroneIdParser.parseMessagePack(buf).isEmpty())
    }

    // ---- Service Data wrapper -------------------------------------------

    @Test
    fun `service data wraps a single basic id message correctly`() {
        val basic = basicIdFrame(uasId = "SVCDATA-001")
        val svcData = serviceData(basic)
        val msgs = OpenDroneIdParser.parseServiceData(svcData)
        assertEquals(1, msgs.size)
        assertEquals("SVCDATA-001", (msgs[0] as OpenDroneIdMessage.BasicId).uasId)
    }

    @Test
    fun `service data with wrong app code yields empty list`() {
        val basic = basicIdFrame()
        val svcData = ByteArray(2 + basic.size).also {
            it[0] = 0x99.toByte() // not OpenDroneID
            it[1] = 0x00
            System.arraycopy(basic, 0, it, 2, basic.size)
        }
        assertTrue(OpenDroneIdParser.parseServiceData(svcData).isEmpty())
    }

    @Test
    fun `service data wraps a message pack`() {
        val pack = buildMessagePack(listOf(
            basicIdFrame(uasId = "MULTI-001"),
            locationFrame(),
        ))
        val svcData = serviceData(pack)
        val msgs = OpenDroneIdParser.parseServiceData(svcData)
        assertEquals(2, msgs.size)
        assertTrue(msgs[0] is OpenDroneIdMessage.BasicId)
        assertTrue(msgs[1] is OpenDroneIdMessage.Location)
    }

    // ---- Service-data helpers -------------------------------------------

    private fun buildMessagePack(messages: List<ByteArray>): ByteArray {
        val singleSize = OpenDroneIdMessage.MESSAGE_TOTAL_BYTES
        val buf = ByteArray(3 + messages.size * singleSize)
        buf[0] = ((OpenDroneIdMessage.TYPE_MESSAGE_PACK shl 4) or 1).toByte()
        buf[1] = singleSize.toByte()
        buf[2] = messages.size.toByte()
        messages.forEachIndexed { i, msg ->
            System.arraycopy(msg, 0, buf, 3 + i * singleSize, singleSize)
        }
        return buf
    }

    /** Prepend the OpenDroneID app code (0x0D) + counter (0x00) to a message payload. */
    private fun serviceData(payload: ByteArray): ByteArray =
        ByteArray(2 + payload.size).also {
            it[0] = 0x0D
            it[1] = 0x00
            System.arraycopy(payload, 0, it, 2, payload.size)
        }
}
