package soy.engindearing.omnitak.mobile.ui.components

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.maps.MapLibreMap
import soy.engindearing.omnitak.mobile.data.CoTAffiliation
import soy.engindearing.omnitak.mobile.data.CoTEvent

/**
 * Pushes contacts into the pre-baked `contacts-src` GeoJsonSource that lives
 * inline in the tactical style JSON. The style declares a circle layer keyed
 * off the `color` property and a symbol layer that renders the `callsign`
 * property as a text label — so all contacts get callsign text and team-color
 * tinting without any per-marker mutation.
 *
 * History: the original Annotation-API path (`map.addMarker`) rendered MIL-STD
 * icons but used `.title()` as a tooltip (tap-only), not a visible label, and
 * ignored `<__group>` team color entirely — causing the "all blue, no callsign"
 * report from P-E on TAK Discord 2026-05-27. This path mirrors how AircraftLayer
 * feeds the aircraft-src source and is consistent with the inline-style approach
 * that avoids the MapLibre-Android addLayer GL quirk on Adreno 610.
 *
 * NOTE on setGeoJson path: pushes go through [GeoJsonLayerFeeder], which
 * always uses the `setGeoJson(String)` overload — the FeatureCollection
 * overload has a Java-layer null-features guard that can silently no-op.
 *
 * [previewColor] is kept for callers (e.g. MarkerEditSheet) that need an
 * affiliation color outside the map.
 */
object ContactLayer {
    private const val TAG = "ContactLayer"

    const val SOURCE_ID = "contacts-src"

    // Feature property keys — must match the style JSON expressions.
    private const val PROP_UID = "uid"
    private const val PROP_CALLSIGN = "callsign"
    private const val PROP_AFFILIATION = "affiliation"
    // Hex color string consumed by the circle-color paint expression in the style.
    private const val PROP_COLOR = "color"

    fun update(map: MapLibreMap, @Suppress("UNUSED_PARAMETER") context: Context, contacts: Collection<CoTEvent>) {
        val features = JSONArray()
        for (c in contacts) {
            if (c.lat.isNaN() || c.lon.isNaN()) continue
            features.put(featureFor(c))
        }
        if (!GeoJsonLayerFeeder.push(map, SOURCE_ID, features)) {
            Log.w(TAG, "contacts-src not found in style — skipping update")
            return
        }
        if (contacts.isNotEmpty()) {
            Log.i(TAG, "pushed ${contacts.size} contacts to $SOURCE_ID")
        }
    }

    fun clear(map: MapLibreMap) {
        GeoJsonLayerFeeder.clear(map, SOURCE_ID)
    }

    private fun featureFor(c: CoTEvent): JSONObject {
        val hexColor = argbToHex(c.displayColor)
        return JSONObject()
            .put("type", "Feature")
            .put("geometry", JSONObject()
                .put("type", "Point")
                .put("coordinates", JSONArray().put(c.lon).put(c.lat))
            )
            .put("properties", JSONObject()
                .put(PROP_UID, c.uid)
                .put(PROP_CALLSIGN, c.callsign ?: c.uid)
                .put(PROP_AFFILIATION, c.affiliation.code.toString())
                .put(PROP_COLOR, hexColor)
            )
    }

    /** Convert ARGB int to CSS hex string for use in MapLibre style expressions. */
    internal fun argbToHex(argb: Int): String =
        String.format("#%06X", argb and 0x00FFFFFF)

    /** Stable color for previewing an affiliation outside the map. */
    fun previewColor(affiliation: CoTAffiliation): Int = when (affiliation) {
        CoTAffiliation.FRIEND  -> 0xFF4ADE80.toInt()
        CoTAffiliation.HOSTILE -> 0xFFF44336.toInt()
        CoTAffiliation.NEUTRAL -> 0xFFFFC107.toInt()
        else                   -> 0xFFB39DDB.toInt()
    }
}
