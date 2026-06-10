package soy.engindearing.omnitak.mobile.ui.components

import android.graphics.PointF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Projection

/**
 * Shared projection pump for the Compose overlays floating above the
 * MapLibre view (drone, home, trail, geofence, LAANC grids, mission
 * waypoints…). Polls `map.projection` on the given period so the
 * overlay tracks both camera moves and telemetry updates.
 *
 * This replaces seven copy-pasted `while (isActive) { toScreenLocation;
 * delay(N) }` loops that had drifted to three different poll rates.
 * Poll-based on purpose for now — behavior identical to the loops it
 * replaces; an OnCameraMove-listener-driven projector is the follow-up
 * that would retire the polling entirely.
 *
 * @param keys restart the loop when telemetry changes (same contract
 *   as the LaunchedEffect keys in the original loops).
 * @param project runs every tick on the main thread (MapLibre
 *   projection is main-thread-only); returns whatever screen-space
 *   model the overlay draws from.
 */
@Composable
fun <T> rememberScreenProjection(
    map: MapLibreMap,
    vararg keys: Any?,
    periodMs: Long = 100,
    project: (Projection) -> T,
): T? {
    var value by remember { mutableStateOf<T?>(null) }
    val currentProject by rememberUpdatedState(project)
    LaunchedEffect(map, *keys) {
        while (isActive) {
            value = currentProject(map.projection)
            delay(periodMs)
        }
    }
    return value
}

/** Single-point convenience — projects one lat/lon to screen pixels. */
@Composable
fun rememberScreenPosition(
    map: MapLibreMap,
    lat: Double,
    lon: Double,
    periodMs: Long = 100,
): PointF? = rememberScreenProjection(map, lat, lon, periodMs = periodMs) { proj ->
    proj.toScreenLocation(LatLng(lat, lon))
}
