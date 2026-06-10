package soy.engindearing.omnitak.mobile.plugin

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.plugin.OmniTAKPlugin
import soy.engindearing.omnitak.plugin.PluginCoTEvent
import soy.engindearing.omnitak.plugin.PluginHost
import soy.engindearing.omnitak.plugin.PluginLatLng
import soy.engindearing.omnitak.plugin.PluginRadialAction
import soy.engindearing.omnitak.plugin.PluginRegistry

/**
 * Proves the FULL plugin host surface fires — not just the two hooks the ADS-B
 * reference plugin uses (map overlay + settings row). A tiny test-only
 * NoOpProbePlugin registers a radial action + a CoT handler; we assert the
 * registrations land, that invoking the captured callbacks updates the probe's
 * state, and that PluginRegistry honors the default-true enable semantics.
 */
class PluginHostSurfaceTest {

    @After fun tearDown() = PluginRegistry.clearForTest()

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

    /** Exercises registerRadialAction + registerCoTHandler (the hooks ADS-B
     *  does not use), so the whole surface is covered. */
    private class NoOpProbePlugin : OmniTAKPlugin {
        override val pluginId = "soy.engindearing.probe"
        override val displayName = "Probe"
        override val pluginVersion = "0.0.1"
        override val pluginAuthor = "test"
        override val pluginDescription = "no-op probe exercising radial + CoT hooks"

        var lastCoord: PluginLatLng? = null
        var cotSeen = 0
        var deactivated = false

        override fun activate(host: PluginHost) {
            host.registerRadialAction(PluginRadialAction("probe.ping", Icons.Filled.Place, "Ping")) { coord ->
                lastCoord = coord
            }
            host.registerCoTHandler { _ ->
                cotSeen++
                false
            }
        }

        override fun deactivate() { deactivated = true }
    }

    @Test fun activate_registers_radial_and_cot_hooks() {
        val host = FakePluginHost()
        val probe = NoOpProbePlugin()

        probe.activate(host)

        assertEquals("one radial action registered", 1, host.radials.size)
        assertEquals("one CoT handler registered", 1, host.cotHandlers.size)
        assertEquals("probe.ping", host.radials[0].first.id)
        assertEquals("Ping", host.radials[0].first.label)
    }

    @Test fun captured_radial_callback_fires_with_coordinate() {
        val host = FakePluginHost()
        val probe = NoOpProbePlugin()
        probe.activate(host)

        host.radials[0].second(PluginLatLng(47.0, -117.0))

        assertEquals(47.0, probe.lastCoord?.latitude)
        assertEquals(-117.0, probe.lastCoord?.longitude)
    }

    @Test fun captured_cot_handler_fires_for_event() {
        val host = FakePluginHost()
        val probe = NoOpProbePlugin()
        probe.activate(host)

        val consumed = host.cotHandlers[0](
            PluginCoTEvent("uid-1", 47.0, -117.0, "ALPHA", "a-f-G-U-C", null)
        )

        assertEquals(1, probe.cotSeen)
        assertFalse("probe returns not-consumed", consumed)
    }

    @Test fun registry_activates_enabled_by_default_true() {
        PluginRegistry.clearForTest()
        val host = FakePluginHost()
        val probe = NoOpProbePlugin()
        PluginRegistry.register(probe)

        // Default-true: with no stored flag, activateEnabled activates.
        PluginRegistry.activateEnabled(host, FakePrefs(emptyMap()))

        assertTrue(PluginRegistry.isActivated(probe.pluginId))
        assertEquals(1, host.radials.size)
        assertEquals(1, host.cotHandlers.size)
    }

    @Test fun registry_respects_disabled_flag() {
        PluginRegistry.clearForTest()
        val host = FakePluginHost()
        val probe = NoOpProbePlugin()
        PluginRegistry.register(probe)

        PluginRegistry.activateEnabled(
            host,
            FakePrefs(mapOf(PluginRegistry.enabledKey(probe.pluginId) to false)),
        )

        assertFalse(PluginRegistry.isActivated(probe.pluginId))
        assertEquals("disabled plugin registers nothing", 0, host.radials.size)
    }

    @Test fun registry_deactivate_calls_plugin() {
        PluginRegistry.clearForTest()
        val host = FakePluginHost()
        val probe = NoOpProbePlugin()
        PluginRegistry.register(probe)
        PluginRegistry.activateEnabled(host, FakePrefs(emptyMap()))

        PluginRegistry.deactivate(probe.pluginId)

        assertTrue(probe.deactivated)
        assertFalse(PluginRegistry.isActivated(probe.pluginId))
    }

    @Test fun onActivating_bracket_wraps_each_activation() {
        PluginRegistry.clearForTest()
        val host = FakePluginHost()
        PluginRegistry.register(NoOpProbePlugin())
        val bracketed = mutableListOf<String>()

        PluginRegistry.activateEnabled(host, FakePrefs(emptyMap())) { id, activate ->
            bracketed += id
            activate()
        }

        assertEquals(listOf("soy.engindearing.probe"), bracketed)
    }
}
