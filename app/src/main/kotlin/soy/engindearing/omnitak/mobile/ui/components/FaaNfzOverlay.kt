package soy.engindearing.omnitak.mobile.ui.components

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import soy.engindearing.omnitak.mobile.data.airspace.FaaUasFmClient
import soy.engindearing.omnitak.mobile.data.airspace.FaaUasFmGrid

/**
 * Renders FAA UAS Facility Map polygons (LAANC ceilings) as a soft
 * overlay on the map. Color encodes the ceiling:
 *  - **red**   0 ft = no-fly without prior authorization
 *  - **orange** 1–199 ft = severe ceiling
 *  - **yellow** 200–349 ft = restricted ceiling
 *  - **green**  ≥350 ft = unrestricted (typical Part 107 ceiling)
 *
 * Fetch strategy: pull polygons whose bbox includes [centerLat]/[centerLon]
 * with a [bboxHalfDeg] half-edge. Re-fetches only when the center moves
 * more than that distance — same ~50 km square will usually serve a
 * whole flight. Result is cached in the client.
 *
 * Only renders when a UAS is connected — airspace overlays clutter the
 * map for non-drone operators.
 */
@Composable
fun FaaNfzOverlay(
    visible: Boolean,
    centerLatDeg: Double?,
    centerLonDeg: Double?,
    mapboxMap: MapLibreMap?,
    bboxHalfDeg: Double = 0.5, // ~55 km at temperate lats
) {
    val map = mapboxMap ?: return
    if (!visible || centerLatDeg == null || centerLonDeg == null) return

    // One client per composition — its in-memory cache means repeat
    // panning around the same area costs zero network.
    val client = remember { FaaUasFmClient() }
    var grids by remember { mutableStateOf<List<FaaUasFmGrid>>(emptyList()) }
    LaunchedEffect(centerLatDeg, centerLonDeg) {
        grids = client.fetch(
            southLat = centerLatDeg - bboxHalfDeg,
            northLat = centerLatDeg + bboxHalfDeg,
            westLon = centerLonDeg - bboxHalfDeg,
            eastLon = centerLonDeg + bboxHalfDeg,
        )
    }
    if (grids.isEmpty()) return

    // Project all polygon points at ~5 Hz so they track pan/zoom.
    data class ProjectedGrid(val ceiling: Int, val rings: List<List<PointF>>)
    val projected = rememberScreenProjection(map, grids, periodMs = 200) { proj ->
        grids.map { g ->
            ProjectedGrid(
                ceiling = g.ceilingFeet,
                rings = g.polygons.map { ring ->
                    ring.map { (lat, lon) ->
                        proj.toScreenLocation(LatLng(lat, lon))
                    }
                },
            )
        }
    } ?: emptyList()

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            for (pg in projected) {
                val (fill, stroke) = colorFor(pg.ceiling)
                for (ring in pg.rings) {
                    if (ring.size < 3) continue
                    val path = Path().apply {
                        moveTo(ring[0].x, ring[0].y)
                        for (i in 1 until ring.size) lineTo(ring[i].x, ring[i].y)
                        close()
                    }
                    drawPath(path, color = fill)
                    drawPath(path, color = stroke, style = Stroke(width = 1.5f))
                }
            }
        }
    }
}

private fun colorFor(ceilingFt: Int): Pair<Color, Color> = when {
    ceilingFt <= 0 -> Color(0xFFFF3B30).copy(alpha = 0.22f) to Color(0xFFFF3B30).copy(alpha = 0.6f)
    ceilingFt < 200 -> Color(0xFFFF8800).copy(alpha = 0.18f) to Color(0xFFFF8800).copy(alpha = 0.55f)
    ceilingFt < 350 -> Color(0xFFFFC107).copy(alpha = 0.14f) to Color(0xFFFFC107).copy(alpha = 0.45f)
    else -> Color(0xFF34C759).copy(alpha = 0.08f) to Color(0xFF34C759).copy(alpha = 0.35f)
}
