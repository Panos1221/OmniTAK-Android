package soy.engindearing.omnitak.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.gyb.GybDetectionParser
import soy.engindearing.omnitak.mobile.data.gyb.GybMessage
import soy.engindearing.omnitak.mobile.data.remoteid.OpenDroneIdMessage
import soy.engindearing.omnitak.mobile.data.remoteid.RemoteIdToCoTConverter
import soy.engindearing.omnitak.mobile.data.remoteid.RemoteIdTrack

class RemoteIdOperatorTest {

    private fun loc(lat: Double, lon: Double) = OpenDroneIdMessage.Location(
        protocolVersion = 0,
        operationalStatus = OpenDroneIdMessage.OperationalStatus.AIRBORNE,
        heightType = OpenDroneIdMessage.HeightType.ABOVE_GROUND_LEVEL,
        trackDirectionDeg = 90, groundSpeedMs = 5.0, verticalSpeedMs = 0.0,
        latitude = lat, longitude = lon,
        pressureAltitudeM = null, geodeticAltitudeM = 120.0, heightAboveTakeoffM = 100.0,
        horizontalAccuracyM = null, verticalAccuracyM = null, timestampSec = null,
    )

    private fun op(lat: Double, lon: Double) = OpenDroneIdMessage.OperatorLocation(
        protocolVersion = 0, latitude = lat, longitude = lon, altitudeM = 12.0,
    )

    private fun track(drone: OpenDroneIdMessage.Location?, operator: OpenDroneIdMessage.OperatorLocation?) =
        RemoteIdTrack(
            uasId = "TESTDRONE1",
            uaType = OpenDroneIdMessage.UaType.HELICOPTER_OR_MULTIROTOR,
            idType = OpenDroneIdMessage.IdType.SERIAL_NUMBER,
            lastLocation = drone,
            lastUpdateMs = 0L,
            lastOperatorLocation = operator,
        )

    @Test fun drone_and_pilot_both_emit() {
        val evs = RemoteIdToCoTConverter.toCoTs(track(loc(25.0, 121.5), op(25.01, 121.51)))
        assertEquals(2, evs.size)
        val drone = evs.first { it.uid == "RID-TESTDRONE1" }
        val pilot = evs.first { it.uid == "RID-OP-TESTDRONE1" }
        assertEquals("a-u-A-M-H-Q", drone.type)
        assertEquals("DRONE-TESTDRONE1", drone.callsign)
        assertEquals("a-u-G", pilot.type)
        assertEquals("PILOT-TESTDRONE1", pilot.callsign)
        assertEquals(25.01, pilot.lat, 1e-9)
    }

    @Test fun pilot_only_when_drone_has_no_gps() {
        val t = track(drone = null, operator = op(25.01, 121.51))
        assertTrue("track must be renderable on operator location alone", t.isRenderable)
        val evs = RemoteIdToCoTConverter.toCoTs(t)
        assertEquals(1, evs.size)
        assertEquals("RID-OP-TESTDRONE1", evs[0].uid)
        assertTrue(evs[0].remarks.contains("not yet acquired"))
        // drone-only converter returns null in this case
        assertNull(RemoteIdToCoTConverter.toCoT(t))
    }

    @Test fun nothing_emits_with_no_positions() {
        assertEquals(0, RemoteIdToCoTConverter.toCoTs(track(null, null)).size)
    }

    @Test fun parser_reads_operator_location() {
        val line = """{"type":"drone","uasId":"AA:BB","serialNumber":"SN123","uasType":2,"opLat":25.07,"opLon":121.55}"""
        val msg = GybDetectionParser.parse(line)
        assertNotNull(msg)
        val drone = msg as GybMessage.Drone
        val opLoc = drone.messages.filterIsInstance<OpenDroneIdMessage.OperatorLocation>().firstOrNull()
        assertNotNull("parser should decode opLat/opLon into OperatorLocation", opLoc)
        assertEquals(25.07, opLoc!!.latitude, 1e-9)
    }
}
