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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/**
 * Soft geofence circle centered on [centerLatDeg] / [centerLonDeg] at
 * [radiusMeters]. Drawn as a dashed cyan ring with a faint fill — visual
 * cue for the operator-set max range. The actual enforcement happens in
 * UASManager.flyTo; this component is the "where can I fly" surface.
 *
 * Hidden when [radiusMeters] ≤ 0 (geofence disabled) or center is null.
 */
@Composable
fun GeofenceOverlay(
    centerLatDeg: Double?,
    centerLonDeg: Double?,
    radiusMeters: Int,
    mapboxMap: MapLibreMap?,
) {
    val map = mapboxMap ?: return
    if (centerLatDeg == null || centerLonDeg == null || radiusMeters <= 0) return

    // Project center + a point 1 metre east → ratio gives us pixels-per-
    // metre at the current zoom, which we multiply by radius for the
    // visual ring. Recomputed at 10 Hz so the ring stays sized correctly
    // through zoom changes.
    val projectedRing = rememberScreenProjection(
        map, centerLatDeg, centerLonDeg, radiusMeters, periodMs = 100,
    ) { proj ->
        val cp = proj.toScreenLocation(LatLng(centerLatDeg, centerLonDeg))
        val metersPerDegLon = 111_320.0 * kotlin.math.cos(Math.toRadians(centerLatDeg))
        val edgeLon = centerLonDeg + 1.0 / metersPerDegLon
        val ep = proj.toScreenLocation(LatLng(centerLatDeg, edgeLon))
        val pixPerMeter = kotlin.math.hypot(ep.x - cp.x, ep.y - cp.y)
        cp to (pixPerMeter * radiusMeters).toFloat()
    }

    val (cp, radiusPx) = projectedRing ?: return
    if (radiusPx < 4f) return // skip when ring would be invisible

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = Color(0xFF00E5FF).copy(alpha = 0.08f),
                radius = radiusPx,
                center = Offset(cp.x, cp.y),
            )
            drawCircle(
                color = Color(0xFF00E5FF).copy(alpha = 0.7f),
                radius = radiusPx,
                center = Offset(cp.x, cp.y),
                style = Stroke(
                    width = 2.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 14f), 0f),
                ),
            )
        }
    }
}
