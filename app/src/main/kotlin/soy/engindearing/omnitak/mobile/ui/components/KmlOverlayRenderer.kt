package soy.engindearing.omnitak.mobile.ui.components

import android.graphics.Color
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import soy.engindearing.omnitak.mobile.data.KmlVectorOverlay
import soy.engindearing.omnitak.mobile.data.KmlVectorOverlayStore
import java.net.URI

/** Lets the Map Overlays sheet ask the map to frame an overlay's bounds. */
object KmlOverlayEvents {
    private val _zoomTo = MutableStateFlow<KmlVectorOverlay?>(null)
    val zoomTo: StateFlow<KmlVectorOverlay?> = _zoomTo.asStateFlow()
    fun requestZoom(overlay: KmlVectorOverlay) { _zoomTo.value = overlay }
    fun consumed() { _zoomTo.value = null }
}

/**
 * Renders imported KML overlays onto the MapLibre style as one GeoJsonSource
 * per overlay (loaded natively from the on-disk .geojson) plus line / fill /
 * circle layers. This is the GPU-vector approach that scales to 50k+ features
 * where per-feature annotations crash. Toggling = a layer-visibility flip.
 *
 * Call [apply] whenever overlays change AND after every style (re)load — a
 * setStyle wipes added sources/layers, so they must be re-applied.
 */
object KmlOverlayRenderer {
    private val installed = mutableSetOf<String>()

    fun apply(style: Style, overlays: List<KmlVectorOverlay>, store: KmlVectorOverlayStore) {
        val wanted = overlays.map { it.id }.toSet()

        // Remove overlays no longer present.
        for (id in installed - wanted) {
            for (layerId in layerIds(id)) style.removeLayer(layerId)
            style.removeSource("kmlsrc-$id")
        }
        installed.clear()
        installed.addAll(wanted)

        for (overlay in overlays) {
            val sourceId = "kmlsrc-${overlay.id}"
            val color = runCatching { Color.parseColor(overlay.colorHex) }.getOrDefault(Color.MAGENTA)

            if (style.getSource(sourceId) == null) {
                val uri = URI("file://" + store.fileFor(overlay).absolutePath)
                style.addSource(GeoJsonSource(sourceId, uri, GeoJsonOptions().withTolerance(1.0f)))

                style.addLayer(
                    FillLayer("kmlfill-${overlay.id}", sourceId).withProperties(
                        PropertyFactory.fillColor(color),
                        PropertyFactory.fillOpacity(0.18f),
                        PropertyFactory.fillOutlineColor(color),
                    ),
                )
                style.addLayer(
                    LineLayer("kmlline-${overlay.id}", sourceId).withProperties(
                        PropertyFactory.lineColor(color),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        PropertyFactory.lineWidth(
                            Expression.interpolate(
                                Expression.linear(), Expression.zoom(),
                                Expression.stop(6, 0.6f),
                                Expression.stop(12, 1.6f),
                                Expression.stop(16, 3.0f),
                            ),
                        ),
                    ),
                )
                style.addLayer(
                    CircleLayer("kmlpt-${overlay.id}", sourceId).withProperties(
                        PropertyFactory.circleColor(color),
                        PropertyFactory.circleRadius(3.0f),
                        PropertyFactory.circleStrokeColor(Color.WHITE),
                        PropertyFactory.circleStrokeWidth(1.0f),
                    ),
                )
            }

            val vis = if (overlay.visible) Property.VISIBLE else Property.NONE
            for (layerId in layerIds(overlay.id)) {
                style.getLayer(layerId)?.setProperties(PropertyFactory.visibility(vis))
            }
        }
    }

    private fun layerIds(id: String) = listOf("kmlfill-$id", "kmlline-$id", "kmlpt-$id")
}
