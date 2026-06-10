package soy.engindearing.adsb

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.maplibre.android.maps.MapLibreMap
import soy.engindearing.omnitak.plugin.LocalMapEngineHandle

/**
 * The ADS-B plugin's registered map overlay. Renders no Compose UI of its own —
 * it bridges the plugin's aircraft state into the MapLibre GL layers via
 * [AircraftLayer].
 *
 * This reproduces the legacy `TacticalMap` `DisposableEffect(mapView, aircraft)`
 * behavior exactly, but from inside the plugin: it reads the live map engine
 * handle the host provides through [LocalMapEngineHandle], casts it to
 * [MapLibreMap], and on every change to the aircraft list (or active flag)
 * calls `AircraftLayer.update(map, ...)`.
 *
 * On the Cesium 3D globe the host provides `null` for the handle, so this
 * overlay no-ops — identical to the legacy behavior where aircraft were never
 * rendered on the globe (the ADS-B layer always lived on the MapLibre engine).
 */
@Composable
fun AdsbMapOverlay(service: AdsbService) {
    val mapHandle = LocalMapEngineHandle.current
    val map = mapHandle as? MapLibreMap
    val aircraft by service.aircraft.collectAsState()
    val active by service.active.collectAsState()

    // Re-push on any change to the live map handle, the aircraft list, or the
    // active flag. When inactive, push an empty list so a freshly-disabled
    // service clears its markers, mirroring the old `if (active) aircraft else
    // emptyList()` plumbing into TacticalMap.
    LaunchedEffect(map, aircraft, active) {
        val m = map ?: return@LaunchedEffect
        AircraftLayer.update(m, if (active) aircraft else emptyList())
    }
}
