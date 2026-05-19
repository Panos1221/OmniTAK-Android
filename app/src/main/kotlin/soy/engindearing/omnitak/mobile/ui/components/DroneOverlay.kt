package soy.engindearing.omnitak.mobile.ui.components

import android.graphics.PointF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
    var screen by remember { mutableStateOf<PointF?>(null) }
    LaunchedEffect(drone.latDeg, drone.lonDeg, map) {
        while (isActive) {
            val pt = drone.latDeg?.let { lat ->
                drone.lonDeg?.let { lon ->
                    map.projection.toScreenLocation(LatLng(lat, lon))
                }
            }
            screen = pt
            delay(100)
        }
    }

    val pt = screen ?: return
    val xDp = with(density) { pt.x.toDp() }
    val yDp = with(density) { pt.y.toDp() }
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
                .background(Color(0xFF00E5FF)),
        )
        // Callsign + altitude pill below the marker.
        val label = buildString {
            append("UAS")
            drone.altAglMeters?.let { append(" • ${it.toInt()} m") }
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
