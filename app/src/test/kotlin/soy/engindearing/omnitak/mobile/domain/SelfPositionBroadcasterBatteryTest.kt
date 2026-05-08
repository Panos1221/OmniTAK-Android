package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #11 — PPLI must carry the device's real battery level, not a
 * hardcoded 100. ATAK peers display whatever the `<status battery=>`
 * attribute says; lying read 100% to every operator that opened the
 * contact in their team pane.
 *
 * Branching policy: if the provider returns null OR a value outside
 * 0..100, omit the `<status>` element entirely so peers see a blank
 * battery column rather than a misleading number.
 */
class SelfPositionBroadcasterBatteryTest {

    private fun build(batteryPercent: Int?): String =
        SelfPositionBroadcaster.buildSelfCoT(
            uid = "ANDROID-test",
            callsign = "OMNI-1",
            team = "Cyan",
            lat = 47.66,
            lon = -117.43,
            staleSeconds = 180L,
            batteryPercent = batteryPercent,
        )

    @Test fun `null battery omits status element`() {
        val xml = build(null)
        assertFalse("status element should be absent", xml.contains("<status"))
    }

    @Test fun `valid battery emits exact value`() {
        val xml = build(21)
        assertTrue("expected battery=21 attribute", xml.contains("<status battery=\"21\"/>"))
    }

    @Test fun `0 battery emits zero`() {
        val xml = build(0)
        assertTrue(xml.contains("<status battery=\"0\"/>"))
    }

    @Test fun `100 battery emits 100`() {
        val xml = build(100)
        assertTrue(xml.contains("<status battery=\"100\"/>"))
    }

    @Test fun `negative battery omits status element`() {
        // BatteryManager returns -1 when capacity is unknown.
        val xml = build(-1)
        assertFalse("status element should be absent for -1 (unknown)", xml.contains("<status"))
    }

    @Test fun `out-of-range battery omits status element`() {
        val xml = build(200)
        assertFalse("status element should be absent for impossible value", xml.contains("<status"))
    }
}
