package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.UserPrefs

/**
 * Verifies the camera-persistence seeding logic without an Android context.
 *
 * The priority chain tested here mirrors what MapScreen applies on cold
 * start: persisted view → self-fix → global fallback (Spokane removed,
 * P-E TAK Discord 2026-05-27).
 */
class MapCameraStoreTest {

    @Test fun extractCamera_returns_triple_when_all_present() {
        val prefs = UserPrefs(lastCameraLat = 51.5, lastCameraLon = -0.12, lastCameraZoom = 14.0)
        val result = MapCameraStore.extractCamera(prefs)
        assertNotNull(result)
        assertEquals(51.5, result!!.first, 0.0001)
        assertEquals(-0.12, result.second, 0.0001)
        assertEquals(14.0, result.third, 0.0001)
    }

    @Test fun extractCamera_returns_null_when_lat_missing() {
        val prefs = UserPrefs(lastCameraLat = null, lastCameraLon = -0.12, lastCameraZoom = 14.0)
        assertNull(MapCameraStore.extractCamera(prefs))
    }

    @Test fun extractCamera_returns_null_when_lon_missing() {
        val prefs = UserPrefs(lastCameraLat = 51.5, lastCameraLon = null, lastCameraZoom = 14.0)
        assertNull(MapCameraStore.extractCamera(prefs))
    }

    @Test fun extractCamera_returns_null_when_zoom_missing() {
        val prefs = UserPrefs(lastCameraLat = 51.5, lastCameraLon = -0.12, lastCameraZoom = null)
        assertNull(MapCameraStore.extractCamera(prefs))
    }

    @Test fun extractCamera_returns_null_for_NaN_lat() {
        val prefs = UserPrefs(lastCameraLat = Double.NaN, lastCameraLon = -0.12, lastCameraZoom = 14.0)
        assertNull(MapCameraStore.extractCamera(prefs))
    }

    @Test fun extractCamera_returns_null_for_default_prefs() {
        // Fresh install — no saved camera. Must not produce a fallback view.
        assertNull(MapCameraStore.extractCamera(UserPrefs()))
    }

    @Test fun extractCamera_accepts_equator_prime_meridian_coordinates() {
        // Verify the global fallback coords (0,0) are themselves valid
        // when explicitly persisted — the check must not treat them as missing.
        val prefs = UserPrefs(lastCameraLat = 0.0, lastCameraLon = 0.0, lastCameraZoom = 2.0)
        val result = MapCameraStore.extractCamera(prefs)
        assertNotNull(result)
        assertEquals(0.0, result!!.first, 0.0001)
        assertEquals(0.0, result.second, 0.0001)
    }
}
