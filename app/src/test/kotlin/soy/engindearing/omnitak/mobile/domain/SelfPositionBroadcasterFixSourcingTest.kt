package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.SelfFix
import soy.engindearing.omnitak.mobile.data.UserPrefs

/**
 * GAP-030b regression — PPLI broadcasts must come from the live GPS fix,
 * not the stale San Francisco prefs default. Issue #10: HUD showed SF for
 * users in Germany.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelfPositionBroadcasterFixSourcingTest {

    private fun broadcaster(
        fixFlow: MutableStateFlow<SelfFix?>,
        sent: MutableList<String>,
        prefs: UserPrefs = UserPrefs(selfUid = "ANDROID-test"),
    ): SelfPositionBroadcaster = SelfPositionBroadcaster(
        scope = CoroutineScope(Dispatchers.Unconfined),
        prefsFlow = MutableStateFlow(prefs),
        updatePrefs = { /* no-op for these unit tests */ },
        sendCoT = { xml -> sent += xml; true },
        locationFix = fixFlow,
    )

    @Test fun no_broadcast_when_fix_null_and_prefs_NaN() = runTest {
        val fixFlow = MutableStateFlow<SelfFix?>(null)
        val sent = mutableListOf<String>()
        // UserPrefs() defaults selfLat/selfLon to NaN per GAP-030b.
        val prefs = UserPrefs(selfUid = "ANDROID-test")
        broadcaster(fixFlow, sent, prefs).broadcastOnce(prefs)
        assertTrue("no PPLI should be sent without a fix", sent.isEmpty())
    }

    @Test fun broadcasts_berlin_coords_when_fix_set() = runTest {
        val berlin = SelfFix(
            lat = 52.5,
            lon = 13.4,
            altitudeM = 34.0,
            speedKmh = 0.0,
            accuracyM = 5f,
            timeMs = 1_700_000_000_000L,
        )
        val fixFlow = MutableStateFlow<SelfFix?>(berlin)
        val sent = mutableListOf<String>()
        val prefs = UserPrefs(selfUid = "ANDROID-test")
        broadcaster(fixFlow, sent, prefs).broadcastOnce(prefs)
        assertTrue("expected one PPLI", sent.size == 1)
        val xml = sent.single()
        assertTrue("Berlin lat in XML, was: $xml", xml.contains("lat=\"52.5\""))
        assertTrue("Berlin lon in XML, was: $xml", xml.contains("lon=\"13.4\""))
        assertFalse("must not contain SF lat", xml.contains("37.77"))
        assertFalse("must not contain SF lon", xml.contains("-122.4"))
    }

    @Test fun never_broadcasts_san_francisco_when_fix_null() = runTest {
        val fixFlow = MutableStateFlow<SelfFix?>(null)
        val sent = mutableListOf<String>()
        // Even if some legacy migration left non-NaN prefs, the
        // regression is about the *default*. Verify by leaving prefs at
        // their NaN defaults and asserting nothing went out.
        val prefs = UserPrefs(selfUid = "ANDROID-test")
        broadcaster(fixFlow, sent, prefs).broadcastOnce(prefs)
        assertNull("no XML should have been sent", sent.firstOrNull())
        // And as a paranoid belt-and-suspenders, no message anywhere
        // contains the old hardcoded SF coords.
        assertTrue(sent.none { it.contains("37.77") || it.contains("-122.4") })
    }
}
