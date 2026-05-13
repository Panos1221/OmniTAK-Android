package soy.engindearing.omnitak.mobile.data.remoteid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteIdPipelineTest {

    // ---- TrackStore -----------------------------------------------------

    @Test
    fun `track store keys multiple frames from one drone by UAS ID`() {
        val store = RemoteIdTrackStore()
        val basic = OpenDroneIdMessage.BasicId(
            protocolVersion = 1,
            idType = OpenDroneIdMessage.IdType.SERIAL_NUMBER,
            uaType = OpenDroneIdMessage.UaType.HELICOPTER_OR_MULTIROTOR,
            uasId = "MAVIC3-ABC",
        )
        val location = OpenDroneIdMessage.Location(
            protocolVersion = 1,
            operationalStatus = OpenDroneIdMessage.OperationalStatus.AIRBORNE,
            heightType = OpenDroneIdMessage.HeightType.ABOVE_TAKEOFF,
            trackDirectionDeg = 90,
            groundSpeedMs = 10.0,
            verticalSpeedMs = 0.0,
            latitude = 47.6588,
            longitude = -117.4260,
            pressureAltitudeM = null,
            geodeticAltitudeM = 700.0,
            heightAboveTakeoffM = null,
            horizontalAccuracyM = null,
            verticalAccuracyM = null,
            timestampSec = null,
        )

        val firstChanged = store.ingest(listOf(basic, location))
        assertEquals(setOf("MAVIC3-ABC"), firstChanged)
        assertEquals(1, store.snapshot().size)
        val track = store.get("MAVIC3-ABC")!!
        assertEquals("MAVIC3-ABC", track.uasId)
        assertTrue(track.isRenderable)
    }

    @Test
    fun `track store deduplicates frames at the same position`() {
        val store = RemoteIdTrackStore()
        val basic = basicId("X-1")
        val loc = location(47.0, -117.0)
        store.ingest(listOf(basic, loc))
        val secondChanged = store.ingest(listOf(basic, loc))
        // Same position, same UA type → no map-thrash update.
        assertEquals(emptySet<String>(), secondChanged)
    }

    @Test
    fun `track store emits on position change`() {
        val store = RemoteIdTrackStore()
        val basic = basicId("X-1")
        store.ingest(listOf(basic, location(47.0, -117.0)))
        val nextChanged = store.ingest(listOf(basic, location(47.0001, -117.0)))
        assertEquals(setOf("X-1"), nextChanged)
    }

    @Test
    fun `track store uses fallback id when frame carries no BasicID`() {
        val store = RemoteIdTrackStore()
        val locationOnly = listOf<OpenDroneIdMessage>(location(47.0, -117.0))
        val changed = store.ingest(locationOnly, fallbackId = "MAC-AA:BB:CC")
        assertEquals(setOf("MAC-AA:BB:CC"), changed)
        assertNotNull(store.get("MAC-AA:BB:CC"))
    }

    @Test
    fun `track store ignores frames with no BasicID and no fallback`() {
        val store = RemoteIdTrackStore()
        val locationOnly = listOf<OpenDroneIdMessage>(location(47.0, -117.0))
        val changed = store.ingest(locationOnly)
        assertTrue(changed.isEmpty())
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun `track store later BasicID upgrades the fallback ID to the real UAS ID`() {
        val store = RemoteIdTrackStore()
        val first = store.ingest(listOf(location(47.0, -117.0)), fallbackId = "MAC-AA")
        assertEquals(setOf("MAC-AA"), first)

        // The real Basic ID arrives in the next broadcast cycle. We
        // create a new entry under the real UAS ID; the MAC-keyed
        // fallback entry can be left to time out (stale purge).
        val second = store.ingest(listOf(basicId("REAL-001"), location(47.0001, -117.0)))
        assertEquals(setOf("REAL-001"), second)
        assertNotNull(store.get("REAL-001"))
    }

    @Test
    fun `track store purges entries older than stale threshold`() {
        var fakeNow = 1_000L
        val store = RemoteIdTrackStore(staleAfterMs = 1_000L, clock = { fakeNow })
        store.ingest(listOf(basicId("Y-1"), location(47.0, -117.0)))

        fakeNow = 500L + 1_000L // 1.5 s later
        val purged = store.purgeStale()
        assertEquals(emptySet<String>(), purged) // exactly 1 s — under threshold

        fakeNow = 3_000L
        val purged2 = store.purgeStale()
        assertEquals(setOf("Y-1"), purged2)
        assertNull(store.get("Y-1"))
    }

    // ---- Converter ------------------------------------------------------

    @Test
    fun `converter renders multirotor track to a-u-A-M-H-Q CoT event`() {
        val track = renderableTrack(
            "DJI-MAVIC3-12345",
            uaType = OpenDroneIdMessage.UaType.HELICOPTER_OR_MULTIROTOR,
        )
        val event = RemoteIdToCoTConverter.toCoT(track)!!
        assertEquals("a-u-A-M-H-Q", event.type)
        assertEquals("RID-DJI-MAVIC3-12345", event.uid)
        assertEquals("DRONE-DJI-MAVIC3-12345", event.callsign)
        assertEquals(47.6588, event.lat, 0.0001)
        assertEquals(-117.4260, event.lon, 0.0001)
    }

    @Test
    fun `converter renders fixed-wing track to a-u-A-M-F-Q CoT event`() {
        val track = renderableTrack("FIXED-001", uaType = OpenDroneIdMessage.UaType.AEROPLANE)
        val event = RemoteIdToCoTConverter.toCoT(track)!!
        assertEquals("a-u-A-M-F-Q", event.type)
    }

    @Test
    fun `converter falls back to generic unknown-air for undeclared UA type`() {
        val track = renderableTrack("UNK-001", uaType = OpenDroneIdMessage.UaType.UNDECLARED)
        val event = RemoteIdToCoTConverter.toCoT(track)!!
        assertEquals("a-u-A", event.type)
    }

    @Test
    fun `converter returns null when location is missing`() {
        val track = RemoteIdTrack(
            uasId = "NOPOS-001",
            uaType = OpenDroneIdMessage.UaType.HELICOPTER_OR_MULTIROTOR,
            idType = OpenDroneIdMessage.IdType.SERIAL_NUMBER,
            lastLocation = null,
            lastUpdateMs = 0,
        )
        assertNull(RemoteIdToCoTConverter.toCoT(track))
    }

    @Test
    fun `converter remarks include UA type and altitude when present`() {
        val track = renderableTrack(
            "REMARKS-001",
            uaType = OpenDroneIdMessage.UaType.HELICOPTER_OR_MULTIROTOR,
        )
        val event = RemoteIdToCoTConverter.toCoT(track)!!
        assertTrue(event.remarks.contains("UA: HELICOPTER_OR_MULTIROTOR"))
        assertTrue(event.remarks.contains("Alt: "))
    }

    // ---- End-to-end through the parser ----------------------------------

    @Test
    fun `synthetic service data flows through parser, store, and converter to a CoT event`() {
        // Hand-build a service-data buffer carrying BasicID + Location
        // for a multirotor over Spokane, then run it through the full
        // pipeline (parser → trackstore → converter) and assert on
        // the emitted CoT shape.
        val basicId = buildBasicIdFrame(uasId = "E2E-001")
        val locationFrame = buildLocationFrame(lat = 47.6588, lon = -117.4260)
        val serviceData = wrapServiceData(buildMessagePack(listOf(basicId, locationFrame)))

        val store = RemoteIdTrackStore()
        val messages = OpenDroneIdParser.parseServiceData(serviceData)
        assertEquals(2, messages.size)
        val changed = store.ingest(messages)
        assertEquals(setOf("E2E-001"), changed)

        val track = store.get("E2E-001")!!
        val event = RemoteIdToCoTConverter.toCoT(track)!!
        assertEquals("a-u-A-M-H-Q", event.type)
        assertEquals(47.6588, event.lat, 0.0001)
        assertEquals(-117.4260, event.lon, 0.0001)
    }

    // ---- Byte-vector helpers for the E2E test ---------------------------

    private fun buildBasicIdFrame(uasId: String): ByteArray {
        val buf = ByteArray(OpenDroneIdMessage.MESSAGE_TOTAL_BYTES)
        buf[0] = ((OpenDroneIdMessage.TYPE_BASIC_ID shl 4) or 1).toByte()
        // ID Type SERIAL_NUMBER (1), UA Type HELICOPTER_OR_MULTIROTOR (2)
        buf[1] = ((1 shl 4) or 2).toByte()
        val idBytes = uasId.toByteArray(Charsets.US_ASCII)
        System.arraycopy(idBytes, 0, buf, 2, minOf(idBytes.size, 20))
        return buf
    }

    private fun buildLocationFrame(lat: Double, lon: Double): ByteArray {
        val buf = ByteArray(OpenDroneIdMessage.MESSAGE_TOTAL_BYTES)
        buf[0] = ((OpenDroneIdMessage.TYPE_LOCATION shl 4) or 1).toByte()
        // Status airborne (2 = 0b0010), height=above-takeoff, EW=0, SM=0
        buf[1] = ((2 shl 4)).toByte()
        // Track / speed / vert speed all zero
        val latRaw = (lat * 1e7).toInt()
        writeInt32LE(buf, 5, latRaw)
        val lonRaw = (lon * 1e7).toInt()
        writeInt32LE(buf, 9, lonRaw)
        // Geodetic altitude: 700 m → (700 + 1000) / 0.5 = 3400
        writeUInt16LE(buf, 15, 3400)
        // Timestamp sentinel (invalid)
        buf[22] = 0xFF.toByte()
        buf[23] = 0xFF.toByte()
        return buf
    }

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

    private fun wrapServiceData(payload: ByteArray): ByteArray =
        ByteArray(2 + payload.size).also {
            it[0] = 0x0D // OpenDroneID app code
            it[1] = 0x00 // counter
            System.arraycopy(payload, 0, it, 2, payload.size)
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

    // ---- Helpers --------------------------------------------------------

    private fun basicId(uasId: String) = OpenDroneIdMessage.BasicId(
        protocolVersion = 1,
        idType = OpenDroneIdMessage.IdType.SERIAL_NUMBER,
        uaType = OpenDroneIdMessage.UaType.HELICOPTER_OR_MULTIROTOR,
        uasId = uasId,
    )

    private fun location(lat: Double, lon: Double) = OpenDroneIdMessage.Location(
        protocolVersion = 1,
        operationalStatus = OpenDroneIdMessage.OperationalStatus.AIRBORNE,
        heightType = OpenDroneIdMessage.HeightType.ABOVE_TAKEOFF,
        trackDirectionDeg = 0,
        groundSpeedMs = 0.0,
        verticalSpeedMs = 0.0,
        latitude = lat,
        longitude = lon,
        pressureAltitudeM = null,
        geodeticAltitudeM = 700.0,
        heightAboveTakeoffM = null,
        horizontalAccuracyM = null,
        verticalAccuracyM = null,
        timestampSec = null,
    )

    private fun renderableTrack(
        uasId: String,
        uaType: OpenDroneIdMessage.UaType,
    ) = RemoteIdTrack(
        uasId = uasId,
        uaType = uaType,
        idType = OpenDroneIdMessage.IdType.SERIAL_NUMBER,
        lastLocation = location(47.6588, -117.4260),
        lastUpdateMs = 0,
    )
}
