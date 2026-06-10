package soy.engindearing.omnitak.plugin

import androidx.compose.runtime.compositionLocalOf

/**
 * The live map engine handle, provided by the host into the Composition around
 * plugin map overlays. Typed as [Any]? so the SDK stays engine-agnostic (no
 * MapLibre dependency in the SDK — that would create a circular-dependency
 * risk and bloat the SDK).
 *
 * On the MapLibre 2D engine the host provides the live `MapLibreMap`; a plugin
 * casts it (`current as? MapLibreMap`) inside its overlay. On the Cesium 3D
 * globe the host provides `null` — overlays that can only draw on MapLibre
 * then naturally no-op, which is identical to the legacy ADS-B behavior (the
 * globe never rendered aircraft).
 *
 * Default is null so an overlay rendered outside the host's provider (e.g. in
 * a @Preview) degrades to "no map" rather than crashing.
 */
val LocalMapEngineHandle = compositionLocalOf<Any?> { null }
