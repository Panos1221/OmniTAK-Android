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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import soy.engindearing.omnitak.mobile.data.SelfFix
import soy.engindearing.omnitak.mobile.data.uas.DroneState
import soy.engindearing.omnitak.mobile.data.uas.TrailPoint

/**
 * Two operator-orientation overlays for the active UAS:
 *  - **Link line** — dashed cyan line from the operator's GPS fix to
 *    the drone's current position. Visual reinforcement of the DIST
 *    HUD readout — operator can glance and immediately see *where*
 *    the drone is relative to them, no math.
 *  - **Trail** — fading polyline of the drone's recent positions
 *    (oldest faded, newest opaque). Standard pattern in DJI Pilot /
 *    Auterion Mission Control — gives a sense of recent flight path
 *    even when the camera is panned away.
 *
 * Same 10 Hz projection pump as the other overlays so the lines track
 * map pan/zoom in real time.
 */
@Composable
fun UasLinkAndTrail(
    drone: DroneState,
    operator: SelfFix?,
    mapboxMap: MapLibreMap?,
) {
    val map = mapboxMap ?: return
    if (drone.latDeg == null || drone.lonDeg == null) return

    // Project drone + operator + all trail points at 10 Hz.
    val projectedLink = rememberScreenProjection(
        map, drone.latDeg, drone.lonDeg, operator, drone.trail, periodMs = 100,
    ) { proj ->
        Triple(
            proj.toScreenLocation(LatLng(drone.latDeg, drone.lonDeg)),
            operator?.let { proj.toScreenLocation(LatLng(it.lat, it.lon)) },
            drone.trail.map { tp -> proj.toScreenLocation(LatLng(tp.latDeg, tp.lonDeg)) to tp },
        )
    }
    val dronePt = projectedLink?.first
    val operatorPt = projectedLink?.second
    val trailPts = projectedLink?.third ?: emptyList()

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 1. Trail — line segments with alpha ramped from old → new.
            val newest = trailPts.lastOrNull()?.second?.timeMs ?: 0L
            if (trailPts.size >= 2) {
                for (i in 0 until trailPts.size - 1) {
                    val (a, _) = trailPts[i]
                    val (b, bp) = trailPts[i + 1]
                    // Alpha based on age — newer = more opaque
                    val ageNorm = if (newest > 0) {
                        ((bp.timeMs - (newest - 30_000L)).coerceAtLeast(0L) / 30_000.0).coerceIn(0.0, 1.0)
                    } else 1.0
                    drawLine(
                        color = Color(0xFF00E5FF).copy(alpha = (0.25 + 0.55 * ageNorm).toFloat()),
                        start = Offset(a.x, a.y),
                        end = Offset(b.x, b.y),
                        strokeWidth = 3f,
                        cap = StrokeCap.Round,
                    )
                }
            }

            // 2. Link line operator → drone (dashed).
            val op = operatorPt
            val dr = dronePt
            if (op != null && dr != null) {
                drawLine(
                    color = Color(0xFF00E5FF).copy(alpha = 0.65f),
                    start = Offset(op.x, op.y),
                    end = Offset(dr.x, dr.y),
                    strokeWidth = 2.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
