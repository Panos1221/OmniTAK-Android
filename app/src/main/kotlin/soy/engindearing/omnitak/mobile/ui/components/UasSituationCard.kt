package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import soy.engindearing.omnitak.mobile.data.GeoMath
import soy.engindearing.omnitak.mobile.data.SelfFix
import soy.engindearing.omnitak.mobile.data.uas.DroneState

/**
 * Compact "situational" HUD card — three lines of operator-relevant
 * derived telemetry that the autopilot doesn't directly emit:
 *  - **Distance + bearing from operator** (haversine vs LocationProvider fix)
 *  - **Above terrain (AGL via TAK DEM)** — collision-relevant alt the drone
 *    can't compute from home-relative `relative_alt`
 *  - **Battery** — % readout (time-remaining when BATTERY_STATUS lands;
 *    today just % until we wire that message)
 *
 * Lives under the cruise / Follow Me pills in the top-right HUD column.
 * Renders only when there's enough data — silent if drone has no fix
 * or operator has no GPS yet.
 */
@Composable
fun UasSituationCard(
    drone: DroneState,
    operator: SelfFix?,
    terrainBelowDroneMsl: Double?,
    modifier: Modifier = Modifier,
) {
    if (drone.latDeg == null || drone.lonDeg == null) return

    val distLine: String? = operator?.let { op ->
        val m = GeoMath.haversineMeters(op.lat, op.lon, drone.latDeg, drone.lonDeg)
        val bearing = GeoMath.bearingDegrees(op.lat, op.lon, drone.latDeg, drone.lonDeg)
        "${GeoMath.formatDistance(m)} @ ${GeoMath.formatBearing(bearing)}"
    }

    // AGL-above-terrain is meaningless for ground / surface vehicles —
    // they're ON the terrain. Only compute it for air platforms.
    val isAir = drone.vehicleClass == soy.engindearing.omnitak.mobile.data.uas.VehicleClass.AIR ||
        drone.vehicleClass == soy.engindearing.omnitak.mobile.data.uas.VehicleClass.UNKNOWN
    val terrainLine: Pair<String, Color>? = if (!isAir) null else terrainBelowDroneMsl?.let { terr ->
        drone.altMslMeters?.let { msl ->
            val agl = msl - terr
            val color = when {
                agl < 5  -> Color(0xFFFF3B30)   // crash territory
                agl < 20 -> Color(0xFFFFC107)   // very low
                else     -> Color.White
            }
            "%.0f m AGL".format(agl) to color
        }
    }

    val batLine: Pair<String, Color>? = drone.batteryPct?.let { pct ->
        val color = when {
            pct < 15 -> Color(0xFFFF3B30)
            pct < 30 -> Color(0xFFFFC107)
            else     -> Color.White
        }
        // Append "(N min)" when the autopilot has a real time-remaining
        // estimate (BATTERY_STATUS.time_remaining > 0).
        val text = drone.batteryTimeRemainingSec?.let { sec ->
            "$pct% (${sec / 60} min)"
        } ?: "$pct%"
        text to color
    }

    // SPD — airspeed (preferred, from VFR_HUD) else ground speed.
    // Always rendered when armed so hovering reads "0.0 m/s" rather than
    // hiding the row — operator wants to see "drone is stationary" too.
    val spdLine: String? = when {
        drone.airspeedMps != null -> "%.1f m/s air".format(drone.airspeedMps)
        drone.groundSpeedMps != null -> "%.1f m/s gnd".format(drone.groundSpeedMps)
        else -> null
    }

    // WIND — direction wind is FROM + speed. Coloured yellow ≥10 m/s,
    // red ≥15 m/s (typical Phantom-class max wind: 10 m/s; fixed-wing
    // pushed past 15 m/s often gets blown off course).
    val windLine: Pair<String, Color>? = drone.windSpeedMps?.let { sp ->
        val color = when {
            sp >= 15.0 -> Color(0xFFFF3B30)
            sp >= 10.0 -> Color(0xFFFFC107)
            else -> Color.White
        }
        val fromDir = drone.windFromDeg?.let { " from %03.0f°".format(it) } ?: ""
        "%.1f m/s%s".format(sp, fromDir) to color
    }

    // FLT — elapsed flight time (since armed-true).
    val fltLine: String? = drone.armedAtMs?.let { start ->
        val secs = ((System.currentTimeMillis() - start) / 1000L).coerceAtLeast(0L)
        "%d:%02d".format(secs / 60, secs % 60)
    }

    // GPS — "3D · 12 sat · HDOP 0.8". Coloured by quality bracket.
    val gpsLine: Pair<String, Color>? = drone.gpsFix?.let { fix ->
        val parts = mutableListOf(fix)
        drone.gpsSatellites?.let { parts.add("$it sat") }
        drone.gpsHdop?.let { parts.add("HDOP %.1f".format(it)) }
        val color = when {
            fix.contains("NO_FIX") -> Color(0xFFFF3B30)
            (drone.gpsHdop ?: 0.0) > 2.0 -> Color(0xFFFFC107)
            (drone.gpsSatellites ?: 99) < 6 -> Color(0xFFFFC107)
            else -> Color.White
        }
        parts.joinToString(" · ") to color
    }

    // Don't render an empty card.
    if (distLine == null && terrainLine == null && batLine == null && gpsLine == null) return

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.78f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            distLine?.let { Line("DIST", it, Color.White) }
            terrainLine?.let { (text, c) -> Line("AGL", text, c) }
            spdLine?.let { Line("SPD", it, Color.White) }
            windLine?.let { (text, c) -> Line("WIND", text, c) }
            fltLine?.let { Line("FLT", it, Color.White) }
            batLine?.let { (text, c) -> Line("BAT", text, c) }
            gpsLine?.let { (text, c) -> Line("GPS", text, c) }
        }
    }
}

@Composable
private fun Line(label: String, value: String, valueColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            color = Color(0xFF00E5FF),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(end = 2.dp),
        )
        Text(
            text = value,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
