package soy.engindearing.omnitak.plugin

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * SDK-owned value types. These exist so the plugin SDK has **no** dependency
 * on `:app` (its `CoTEvent`) or on MapLibre (`LatLng`). Keeping the SDK
 * dependency-light avoids a circular dependency: `:app → plugin-sdk`, never
 * the reverse. The host adapts its core types to/from these at the seam (e.g.
 * `AppPluginHost.dispatchCoT` adapts the app's `CoTEvent` → [PluginCoTEvent]).
 */

/** A long-press radial-menu entry contributed by a plugin. */
data class PluginRadialAction(
    /** Stable id, namespaced by the plugin (e.g. `"soy.engindearing.adsb.show"`). */
    val id: String,
    val icon: ImageVector,
    val label: String,
)

/** A geographic coordinate, decoupled from any map engine's LatLng type. */
data class PluginLatLng(
    val latitude: Double,
    val longitude: Double,
)

/**
 * A stable subset of the host's inbound CoT event, handed to plugin CoT
 * handlers **after** the core ContactStore has already ingested it. Mirrors
 * the fields a plugin is likely to need without exposing the app's full
 * internal `CoTEvent`.
 */
data class PluginCoTEvent(
    val uid: String,
    val lat: Double,
    val lon: Double,
    val callsign: String?,
    val type: String?,
    val rawXml: String?,
)
