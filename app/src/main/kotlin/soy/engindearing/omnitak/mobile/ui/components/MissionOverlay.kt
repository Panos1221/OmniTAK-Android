package soy.engindearing.omnitak.mobile.ui.components

import android.graphics.PointF
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import soy.engindearing.omnitak.mobile.data.uas.MissionPhase
import soy.engindearing.omnitak.mobile.data.uas.WaypointMission

/**
 * Render the operator's drawn / uploaded mission on top of the map:
 *  - numbered cyan pins for each waypoint
 *  - polyline connecting them in sequence
 *  - currently-executing leg highlighted in amber
 *
 * Same projection trick as [DroneOverlay] — `map.projection.toScreenLocation`
 * on a 10 Hz tick so pins track camera pan/zoom in real time.
 */
@Composable
fun MissionOverlay(
    mission: WaypointMission,
    mapboxMap: MapLibreMap?,
    onWaypointClick: (Int) -> Unit = {},
) {
    val map = mapboxMap
    if (mission.isEmpty || map == null) return

    val density = LocalDensity.current

    // Project all waypoints to screen pixels at 10 Hz.
    val screenPts = rememberScreenProjection(map, mission.waypoints, periodMs = 100) { proj ->
        mission.waypoints.map { wp ->
            proj.toScreenLocation(LatLng(wp.latDeg, wp.lonDeg))
        }
    } ?: emptyList()
    if (screenPts.isEmpty()) return

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Connecting polyline — draw first so pins land on top.
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (screenPts.size < 2) return@Canvas
            for (i in 0 until screenPts.size - 1) {
                val a = screenPts[i]; val b = screenPts[i + 1]
                val isActiveLeg = (mission.phase == MissionPhase.STARTED &&
                        mission.currentSeq == i)
                drawLine(
                    color = if (isActiveLeg) Color(0xFFFFC107) else Color(0xFF00E5FF),
                    start = Offset(a.x, a.y),
                    end = Offset(b.x, b.y),
                    strokeWidth = if (isActiveLeg) 7f else 4f,
                )
            }
        }

        // 2. Numbered pins. Tap a pin → edit sheet for that waypoint.
        //    Pin is 36dp clickable area so the tap target meets Material
        //    minimums even though the visual is 28dp.
        screenPts.forEachIndexed { idx, pt ->
            val xDp = with(density) { pt.x.toDp() }
            val yDp = with(density) { pt.y.toDp() }
            val reached = idx <= mission.currentSeq && mission.phase == MissionPhase.STARTED
            val wp = mission.waypoints[idx]
            val hasOverride = wp.altMslMeters > 0.0 || wp.speedMps != null
            Box(
                modifier = Modifier
                    .offset(x = xDp - 18.dp, y = yDp - 18.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable { onWaypointClick(idx) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        // Slightly different fill when overridden so the
                        // operator can tell at a glance which waypoints
                        // have non-default alt/speed.
                        .background(
                            when {
                                reached -> Color(0xFFFFC107)
                                hasOverride -> Color(0xFFB388FF) // lavender
                                else -> Color(0xFF00E5FF)
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${idx + 1}",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            // Altitude badge under the pin when overridden.
            if (hasOverride) {
                val altLabel = if (wp.altMslMeters > 0.0) "${wp.altMslMeters.toInt()}m" else "spd"
                Box(
                    modifier = Modifier
                        .offset(x = xDp - 20.dp, y = yDp + 18.dp)
                        .size(width = 40.dp, height = 16.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.75f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = altLabel,
                        color = Color(0xFFB388FF),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
