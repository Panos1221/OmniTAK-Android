package soy.engindearing.adsb

import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * A self-contained copy of the host's `GeoJsonLayerFeeder` push/clear helpers,
 * scoped to this plugin so example-adsb depends only on `:plugins:plugin-sdk`
 * + MapLibre and never on `:app`. The host's feeder stays in `:app` for its
 * other layers (contacts, grid, measurement, drawings); this ~25-line copy is
 * trivial and stable, and keeps the plugin module truly portable.
 *
 * Always uses the String overload of [GeoJsonSource.setGeoJson]: it goes
 * straight to nativeSetGeoJsonString, bypassing the `FeatureCollection.fromJson`
 * overload whose Java-layer null-features guard can silently no-op.
 */
internal object AdsbGeoJsonFeeder {

    private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""

    /** Wrap [features] in a FeatureCollection envelope and push it.
     *  Returns false when the style/source isn't ready (caller may log). */
    fun push(map: MapLibreMap, sourceId: String, features: JSONArray): Boolean {
        val style = map.style ?: return false
        val src = style.getSourceAs<GeoJsonSource>(sourceId) ?: return false
        src.setGeoJson(
            JSONObject()
                .put("type", "FeatureCollection")
                .put("features", features)
                .toString()
        )
        return true
    }

    /** Reset the source to an empty FeatureCollection. */
    fun clear(map: MapLibreMap, sourceId: String) {
        val src = map.style?.getSourceAs<GeoJsonSource>(sourceId) ?: return
        src.setGeoJson(EMPTY)
    }
}
