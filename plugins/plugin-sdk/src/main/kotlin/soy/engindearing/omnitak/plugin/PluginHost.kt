package soy.engindearing.omnitak.plugin

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Passed to [OmniTAKPlugin.activate]. Plugins register their hooks through it.
 * Mirrors the iOS `PluginHost` shape so the same plugin design ports across
 * platforms.
 *
 * The host implementation lives in `:app` (`AppPluginHost`); the SDK only
 * declares the contract. All registrations happen inside [OmniTAKPlugin.activate].
 */
interface PluginHost {
    /**
     * Register a Compose overlay rendered in the map chrome layer, on **both**
     * the MapLibre 2D engine and the Cesium 3D globe. The overlay reads the
     * live map engine handle from [LocalMapEngineHandle] (a `MapLibreMap?` on
     * the 2D engine, `null` on the globe) and draws accordingly — a plugin
     * that can only paint on one engine simply no-ops on the other, exactly as
     * the built-in ADS-B layer does (it renders on MapLibre, no-ops on Cesium).
     */
    fun registerMapOverlay(overlay: @Composable () -> Unit)

    /**
     * Add an entry to the map long-press radial menu. [onSelect] is invoked
     * with the coordinate of the long-press when the operator picks the action.
     */
    fun registerRadialAction(action: PluginRadialAction, onSelect: (PluginLatLng) -> Unit)

    /**
     * Register a handler called for every inbound CoT event **after** the core
     * ContactStore ingests it. Return true to mark the event "consumed"
     * (advisory in v1 — the core store has already ingested, so consumed=true
     * does not un-ingest; it is reserved for future filtering).
     */
    fun registerCoTHandler(handler: (PluginCoTEvent) -> Boolean)

    /**
     * Add a row to the Settings screen's Plugins section. Tapping it navigates
     * to the plugin's [OmniTAKPlugin.settingsContent].
     */
    fun registerSettingsRow(label: String, icon: ImageVector)
}
