package soy.engindearing.omnitak.mobile.ui.components

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.maps.MapLibreMap
import soy.engindearing.omnitak.mobile.data.Aircraft

/**
 * Pushes ADS-B aircraft state into the pre-baked `aircraft-src`
 * GeoJson source. The style JSON declares a circle layer + a symbol
 * layer for the callsign so that the emulator GL path renders
 * reliably (see ContactLayer for the same reason).
 *
 * Each aircraft point carries `heading` and `callsign` properties
 * so the SymbolLayer can rotate + label.
 */
object AircraftLayer {
    const val SOURCE_ID = "aircraft-src"
    const val CIRCLE_LAYER_ID = "aircraft-circle"
    const val LABEL_LAYER_ID = "aircraft-label"

    fun update(map: MapLibreMap, aircraft: List<Aircraft>) {
        if (!GeoJsonLayerFeeder.push(map, SOURCE_ID, toFeatures(aircraft))) return
        if (aircraft.isNotEmpty()) {
            Log.i("AircraftLayer", "pushed ${aircraft.size} aircraft to $SOURCE_ID")
        }
    }

    fun clear(map: MapLibreMap) {
        GeoJsonLayerFeeder.clear(map, SOURCE_ID)
    }

    private fun toFeatures(aircraft: List<Aircraft>): JSONArray {
        val features = JSONArray()
        aircraft.forEach { a ->
            features.put(
                JSONObject()
                    .put("type", "Feature")
                    .put(
                        "properties",
                        JSONObject()
                            .put("icao24", a.icao24)
                            .put("callsign", a.callsign)
                            .put("heading", a.headingDeg)
                            .put("onGround", a.onGround)
                            .put("altitudeFt", a.altitudeFt)
                            .put("speedKt", a.speedKt)
                    )
                    .put(
                        "geometry",
                        JSONObject()
                            .put("type", "Point")
                            .put("coordinates", JSONArray().put(a.lon).put(a.lat))
                    )
            )
        }
        return features
    }
}
