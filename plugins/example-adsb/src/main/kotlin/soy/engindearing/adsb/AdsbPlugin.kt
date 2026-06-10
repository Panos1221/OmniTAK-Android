package soy.engindearing.adsb

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flight
import androidx.compose.runtime.Composable
import soy.engindearing.omnitak.plugin.OmniTAKPlugin
import soy.engindearing.omnitak.plugin.PluginHost
import soy.engindearing.omnitak.plugin.PluginLatLng

/**
 * The ADS-B reference plugin. Demonstrates the minimal real-world plugin: one
 * HTTP poller ([AdsbService] → OpenSky), one map overlay ([AdsbMapOverlay] →
 * [AircraftLayer]), one settings surface ([AdsbSettingsContent]).
 *
 * Behavior is identical to the pre-SDK in-app feature — the OpenSky polling,
 * the GeoJSON-feeder GL render path, the "center on the camera target else
 * self" box seeding, and the camera-follow recenter all move here unchanged.
 * The only relocation is the on/off control: it used to be a Tools-drawer
 * button; it is now the toggle in this plugin's settings (reached via the
 * Settings → Plugins → ADS-B row).
 *
 * It uses exactly the two host hooks the SDK RFC names for it:
 * [PluginHost.registerMapOverlay] and [PluginHost.registerSettingsRow]. (The
 * radial-action and CoT-handler hooks are exercised by a separate probe plugin
 * + unit tests so the entire host surface is covered.)
 */
class AdsbPlugin : OmniTAKPlugin {

    override val pluginId = "soy.engindearing.adsb"
    override val displayName = "ADS-B"
    override val pluginVersion = "1.0.0"
    override val pluginAuthor = "Engindearing"
    override val pluginDescription =
        "OpenSky Network ADS-B aircraft overlay. Polls civilian transponder " +
            "states around the map view and plots them on the MapLibre engine."

    /** The single OpenSky poller this plugin owns. */
    val service = AdsbService()

    /**
     * Supplies the current map-view center (camera target, else own position),
     * so [toggle] can seed the OpenSky query box on what the operator is
     * looking at. Wired by the host (`AppPluginHost`) in [activate] via the
     * map-overlay/settings seam. Null until wired → toggle then no-ops on with
     * no center (matching the legacy "no center" toast guard).
     */
    var cameraCenterProvider: (() -> PluginLatLng?)? = null

    override fun activate(host: PluginHost) {
        host.registerMapOverlay { AdsbMapOverlay(service) }
        host.registerSettingsRow(displayName, Icons.Filled.Flight)
    }

    override fun deactivate() {
        service.stop()
    }

    override fun settingsContent(): (@Composable () -> Unit) = {
        AdsbSettingsContent(service = service, onToggle = ::toggle)
    }

    /**
     * Start/stop the OpenSky poll. On start, seeds the query box from
     * [cameraCenterProvider] (camera target, else self) exactly as the legacy
     * MapScreen toggle did — `halfWidthDegrees = 2.5`. With no center the start
     * is skipped (the box would otherwise default to 0,0).
     */
    fun toggle(on: Boolean) {
        if (!on) {
            service.stop()
            return
        }
        val center = cameraCenterProvider?.invoke() ?: return
        service.start(
            centerLat = center.latitude,
            centerLon = center.longitude,
            halfWidthDegrees = 2.5,
        )
    }

    /**
     * Follow the map camera — the host calls this on camera-idle so the OpenSky
     * box tracks the viewport (the next 15s poll picks up the new box). No-op
     * while the service is inactive, so it's safe to call on every idle event.
     */
    fun onCameraChanged(lat: Double, lon: Double) {
        service.recenterIfNeeded(lat, lon)
    }
}
