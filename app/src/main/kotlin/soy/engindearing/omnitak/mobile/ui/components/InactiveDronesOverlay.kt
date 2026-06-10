package soy.engindearing.omnitak.mobile.ui.components

import android.graphics.PointF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import soy.engindearing.omnitak.mobile.domain.MultiUasRegistry
import soy.engindearing.omnitak.mobile.domain.UASManager

/**
 * Renders the markers for **inactive** drones — the active drone is
 * already drawn by [DroneOverlay] with the full HUD-bound treatment
 * (heading chevron, alt label, etc.). This component is the lighter-
 * weight "they're there too" view: a smaller dim cyan dot with the
 * drone's callsign, no heading. Operator sees the whole fleet at a
 * glance + knows which one the commands are bound to (the bigger
 * marker).
 */
@Composable
fun InactiveDronesOverlay(
    drones: List<MultiUasRegistry.Entry>,
    activeManager: UASManager,
    mapboxMap: MapLibreMap?,
) {
    val map = mapboxMap ?: return
    if (drones.isEmpty()) return
    Box(modifier = Modifier.fillMaxSize()) {
        drones.forEach { entry ->
            if (entry.manager === activeManager) return@forEach
            DroneMarker(entry, map)
        }
    }
}

@Composable
private fun DroneMarker(entry: MultiUasRegistry.Entry, map: MapLibreMap) {
    val state by entry.manager.state.collectAsState()
    val lat = state.latDeg
    val lon = state.lonDeg
    if (lat == null || lon == null || !state.hasFix()) return

    val density = LocalDensity.current
    val pt = rememberScreenPosition(map, lat, lon, periodMs = 150) ?: return
    val xDp = with(density) { pt.x.toDp() }
    val yDp = with(density) { pt.y.toDp() }
    // Small dim cyan dot — half the size of the active drone marker so
    // operator can't confuse it with the command target.
    Box(
        modifier = Modifier
            .offset(x = xDp - 7.dp, y = yDp - 7.dp)
            .size(14.dp)
            .clip(CircleShape)
            .background(Color(0xFF00E5FF).copy(alpha = 0.55f)),
    )
    Box(
        modifier = Modifier
            .offset(x = xDp - 36.dp, y = yDp + 12.dp)
            .size(width = 72.dp, height = 16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.55f)),
    ) {
        Text(
            text = entry.callsign,
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxSize(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
