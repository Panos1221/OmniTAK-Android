package soy.engindearing.omnitak.mobile.ui.components

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import soy.engindearing.omnitak.mobile.data.uas.DroneState

/**
 * Compose-side overlay that pins a distinct UAS marker onto the map by
 * projecting the drone's lat/lon to screen pixels via
 * [MapLibreMap.projection.toScreenLocation] on a 4 Hz tick. Sits on top
 * of the MapView so the operator can never lose the drone behind the
 * self-marker or an ADS-B circle.
 *
 * Why a Compose overlay instead of a MapLibre runtime layer:
 *  - MapLibre Android's `style.addLayer(...)` after the initial style
 *    load is unreliable on the emulator GL path — even verbose debug
 *    circles (60 px bright red) won't render, despite the layer being
 *    present in the style's layer list. Compose Box positioning works
 *    deterministically against the same projection API the rest of the
 *    code (lasso, single-tap CoT marker) already uses.
 *  - It also gives us free `Modifier.clickable`, accessible
 *    `contentDescription`, and animation primitives.
 *
 * Renders nothing when [drone] is null or has no fix.
 */
@Composable
fun DroneOverlay(
    drone: DroneState?,
    mapboxMap: MapLibreMap?,
) {
    val map = mapboxMap
    if (drone == null || !drone.hasFix() || map == null) return

    val density = LocalDensity.current
    // Recompute projected screen position at ~10 Hz so the marker
    // tracks both the camera (pan / zoom) and the drone (telemetry
    // updates) without lag.
    val screen = rememberScreenProjection(map, drone.latDeg, drone.lonDeg, periodMs = 100) { proj ->
        drone.latDeg?.let { lat ->
            drone.lonDeg?.let { lon ->
                proj.toScreenLocation(LatLng(lat, lon))
            }
        }
    }

    val pt = screen ?: return
    val xDp = with(density) { pt.x.toDp() }
    val yDp = with(density) { pt.y.toDp() }
    // Marker fill encodes the platform class: cyan = air (UAS),
    // lime = ground (UGV), teal = surface/sub. Keeps a fleet of mixed
    // platforms visually distinct on one map.
    val markerColor = when (drone.vehicleClass) {
        soy.engindearing.omnitak.mobile.data.uas.VehicleClass.GROUND -> Color(0xFF76FF03)
        soy.engindearing.omnitak.mobile.data.uas.VehicleClass.SURFACE,
        soy.engindearing.omnitak.mobile.data.uas.VehicleClass.SUB -> Color(0xFF1DE9B6)
        else -> Color(0xFF00E5FF)
    }
    Box(modifier = Modifier.fillMaxSize()) {
        // Cyan ring with white halo, 36 dp diameter — pops over ADS-B
        // dots and the self-position symbol.
        Box(
            modifier = Modifier
                .offset(x = xDp - 18.dp, y = yDp - 18.dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.85f)),
        )
        Box(
            modifier = Modifier
                .offset(x = xDp - 14.dp, y = yDp - 14.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(markerColor),
        )
        // Heading chevron — small black triangle pointing forward.
        // Skip when heading is unknown (drone disarmed / no fix yet).
        drone.headingDeg?.let { hdg ->
            val hdgRad = Math.toRadians(hdg).toFloat()
            val cosH = kotlin.math.cos(hdgRad.toDouble()).toFloat()
            val sinH = kotlin.math.sin(hdgRad.toDouble()).toFloat()
            Canvas(
                modifier = Modifier
                    .offset(x = xDp - 18.dp, y = yDp - 18.dp)
                    .size(36.dp),
            ) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                // Chevron coordinates in marker-local frame (north-up).
                // Apex at (0, -14) i.e. forward, base at (-6, -4) / (6, -4).
                fun rot(x: Float, y: Float): Offset {
                    // y axis on screen grows downward; bearing 0° = north = -y.
                    val nx = sinH * x + cosH * y
                    val ny = -cosH * x + sinH * y
                    return Offset(cx + nx, cy + ny)
                }
                val tip = rot(0f, -14f)
                val left = rot(-6f, -4f)
                val right = rot(6f, -4f)
                val path = Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(left.x, left.y)
                    lineTo(right.x, right.y)
                    close()
                }
                drawPath(path, color = Color(0xFF003D44))
                drawPath(
                    path,
                    color = Color.White,
                    style = Stroke(width = 1.5f, cap = StrokeCap.Round),
                )
            }
        }
        // Callsign + altitude pill below the marker. Tag adapts to the
        // platform class (UAS air / UGV ground / USV surface); altitude
        // is only meaningful for air vehicles so it's dropped on the
        // ground/surface platforms.
        val classTag = when (drone.vehicleClass) {
            soy.engindearing.omnitak.mobile.data.uas.VehicleClass.GROUND -> "UGV"
            soy.engindearing.omnitak.mobile.data.uas.VehicleClass.SURFACE -> "USV"
            soy.engindearing.omnitak.mobile.data.uas.VehicleClass.SUB -> "UUV"
            else -> "UAS"
        }
        val isAir = drone.vehicleClass == soy.engindearing.omnitak.mobile.data.uas.VehicleClass.AIR ||
            drone.vehicleClass == soy.engindearing.omnitak.mobile.data.uas.VehicleClass.UNKNOWN
        val label = buildString {
            append(classTag)
            if (isAir) drone.altAglMeters?.let { append(" • ${it.toInt()} m") }
        }
        Box(
            modifier = Modifier
                .offset(x = xDp - 60.dp, y = yDp + 22.dp)
                .size(width = 120.dp, height = 22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Color.Black.copy(alpha = 0.75f)),
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxSize(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
