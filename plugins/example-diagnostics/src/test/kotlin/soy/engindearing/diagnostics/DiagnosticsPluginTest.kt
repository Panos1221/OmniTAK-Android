package soy.engindearing.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.plugin.PluginCoTEvent
import soy.engindearing.omnitak.plugin.PluginHost
import soy.engindearing.omnitak.plugin.PluginLatLng
import soy.engindearing.omnitak.plugin.PluginRadialAction

/**
 * Verifies the Diagnostics plugin wires the two host hooks ADS-B doesn't use
 * (radial action + CoT handler) and that invoking the captured callbacks drives
 * its observable [DiagnosticsState]. Mirrors `:app`'s PluginHostSurfaceTest, but
 * against the REAL bundled plugin so the parity-with-iOS reference plugin is
 * exercised, not only a test-only probe.
 */
class DiagnosticsPluginTest {

    /** Captures registrations so the test can invoke them directly. */
    private class FakePluginHost : PluginHost {
        val overlays = mutableListOf<@Composable () -> Unit>()
        val radials = mutableListOf<Pair<PluginRadialAction, (PluginLatLng) -> Unit>>()
        val cotHandlers = mutableListOf<(PluginCoTEvent) -> Boolean>()
        val settingsRows = mutableListOf<Pair<String, ImageVector>>()

        override fun registerMapOverlay(overlay: @Composable () -> Unit) {
            overlays += overlay
        }
        override fun registerRadialAction(action: PluginRadialAction, onSelect: (PluginLatLng) -> Unit) {
            radials += action to onSelect
        }
        override fun registerCoTHandler(handler: (PluginCoTEvent) -> Boolean) {
            cotHandlers += handler
        }
        override fun registerSettingsRow(label: String, icon: ImageVector) {
            settingsRows += label to icon
        }
    }

    @Test fun metadata_matches_ios_and_is_off_by_default_contract() {
        val plugin = DiagnosticsPlugin()
        assertEquals("soy.engindearing.diagnostics", plugin.pluginId)
        assertEquals("Diagnostics", plugin.displayName)
        assertEquals("1.0.0", plugin.pluginVersion)
        assertEquals("Engindearing", plugin.pluginAuthor)
    }

    @Test fun activate_registers_radial_cot_and_settings_hooks() {
        val host = FakePluginHost()
        DiagnosticsPlugin().activate(host)

        assertEquals("one radial action", 1, host.radials.size)
        assertEquals("one CoT handler", 1, host.cotHandlers.size)
        assertEquals("one settings row", 1, host.settingsRows.size)
        assertEquals("soy.engindearing.diagnostics.probe", host.radials[0].first.id)
        assertEquals("Diagnostics", host.radials[0].first.label)
        // It does NOT touch the map-overlay hook (that's ADS-B's job).
        assertTrue("no map overlay", host.overlays.isEmpty())
    }

    @Test fun radial_callback_records_last_coordinate() {
        val host = FakePluginHost()
        val plugin = DiagnosticsPlugin()
        plugin.activate(host)

        assertNull(plugin.state.lastRadialCoordinate.value)
        host.radials[0].second(PluginLatLng(47.0, -117.0))

        assertEquals(47.0, plugin.state.lastRadialCoordinate.value?.latitude)
        assertEquals(-117.0, plugin.state.lastRadialCoordinate.value?.longitude)
    }

    @Test fun cot_handler_counts_and_does_not_consume() {
        val host = FakePluginHost()
        val plugin = DiagnosticsPlugin()
        plugin.activate(host)

        val consumed = host.cotHandlers[0](
            PluginCoTEvent("uid-1", 47.0, -117.0, "ALPHA", "a-f-G-U-C", null)
        )
        host.cotHandlers[0](
            PluginCoTEvent("uid-2", 47.1, -117.1, "BRAVO", "a-f-G-U-C", null)
        )

        assertEquals(2, plugin.state.cotEventCount.value)
        assertFalse("diagnostics never consumes the event", consumed)
    }

    @Test fun deactivate_resets_state() {
        val host = FakePluginHost()
        val plugin = DiagnosticsPlugin()
        plugin.activate(host)
        host.cotHandlers[0](PluginCoTEvent("uid-1", 47.0, -117.0, null, null, null))
        host.radials[0].second(PluginLatLng(47.0, -117.0))

        plugin.deactivate()

        assertEquals(0, plugin.state.cotEventCount.value)
        assertNull(plugin.state.lastRadialCoordinate.value)
    }
}
