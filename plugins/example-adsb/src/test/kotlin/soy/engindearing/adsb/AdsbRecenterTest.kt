package soy.engindearing.adsb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADSB query box follows the map viewport (audit: "ADSB toggle
 * hardcoded to Bay Area"). [AdsbService.shouldRecenter] decides when a
 * camera move is significant enough to move the OpenSky bounding box.
 */
class AdsbRecenterTest {

    @Test fun small_pan_inside_box_does_not_recenter() {
        // halfWidth 2.5 → threshold 1.25°; a 0.5° pan stays put.
        assertFalse(AdsbService.shouldRecenter(47.0, -117.0, 47.5, -117.0, 2.5))
        assertFalse(AdsbService.shouldRecenter(47.0, -117.0, 47.0, -116.5, 2.5))
    }

    @Test fun significant_pan_recenters() {
        assertTrue(AdsbService.shouldRecenter(47.0, -117.0, 49.0, -117.0, 2.5))
        assertTrue(AdsbService.shouldRecenter(47.0, -117.0, 47.0, -114.0, 2.5))
    }

    @Test fun cross_continent_jump_recenters() {
        // Spokane → Taipei. The old hardcoded box would have kept
        // querying California forever.
        assertTrue(AdsbService.shouldRecenter(37.42, -122.08, 25.03, 121.56, 2.5))
    }
}
