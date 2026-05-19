package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import soy.engindearing.omnitak.mobile.data.uas.DroneState

/**
 * Pre-flight readiness card. Lives at the top of the UAS HUD column
 * when the drone is connected and not yet armed — disappears the
 * moment armed flips true (you're flying; the data is now in the
 * Situation card and STATUSTEXT banner).
 *
 * Collapsed: a single line "Pre-flight ✓ READY" / "⚠ 2 ISSUES" /
 * "✗ NOT READY".
 * Expanded: a bullet list of every check with its current value and
 * green/yellow/red status.
 *
 * Doesn't gate arm — operator can still press Arm. This is information,
 * not enforcement, matching DJI Pilot 2 / Auterion practice (operator
 * is final authority).
 */
@Composable
fun UasPreflightCard(
    drone: DroneState,
    modifier: Modifier = Modifier,
) {
    if (drone.armed == true) return // hide once airborne

    val checks = buildChecks(drone)
    val worst = checks.maxOfOrNull { it.severity } ?: 0
    val (label, color) = when (worst) {
        0 -> "READY" to Color(0xFF34C759)
        1 -> "${checks.count { it.severity >= 1 }} ISSUE${if (checks.count { it.severity >= 1 } == 1) "" else "S"}" to Color(0xFFFFC107)
        else -> "NOT READY" to Color(0xFFFF3B30)
    }
    val icon = when (worst) {
        0 -> Icons.Filled.CheckCircle
        1 -> Icons.Filled.Warning
        else -> Icons.Filled.Cancel
    }

    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.82f))
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(18.dp))
            Text(
                "Pre-flight",
                color = Color(0xFF00E5FF),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                label,
                color = color,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (expanded) {
            checks.forEach { c ->
                CheckRow(c)
            }
        }
    }
}

private data class Check(
    val label: String,
    val value: String,
    /** 0 = ok (green), 1 = warning (yellow), 2 = fail (red). */
    val severity: Int,
)

@Composable
private fun CheckRow(c: Check) {
    val color = when (c.severity) {
        0 -> Color(0xFF34C759)
        1 -> Color(0xFFFFC107)
        else -> Color(0xFFFF3B30)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, shape = androidx.compose.foundation.shape.CircleShape),
        )
        Text(
            c.label,
            color = Color.White.copy(alpha = 0.85f),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            c.value,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun buildChecks(d: DroneState): List<Check> {
    val now = System.currentTimeMillis()
    val link = when {
        d.lastHeartbeat == null -> Check("LINK", "no HB yet", 2)
        now - d.lastHeartbeat < 2_000 -> Check("LINK", "OK", 0)
        now - d.lastHeartbeat < 5_000 -> Check("LINK", "stale", 1)
        else -> Check("LINK", "lost", 2)
    }
    val gps = run {
        val fix = d.gpsFix
        val sats = d.gpsSatellites
        val hdop = d.gpsHdop
        when {
            fix == null || fix.contains("NO_FIX") -> Check("GPS", "no fix", 2)
            fix == "FIX_3D" && (sats ?: 0) >= 8 && (hdop ?: 99.0) < 1.5 ->
                Check("GPS", "3D · $sats sat · HDOP %.1f".format(hdop ?: 0.0), 0)
            (sats ?: 0) >= 6 && (hdop ?: 99.0) < 2.0 ->
                Check("GPS", "$fix · $sats sat · HDOP %.1f".format(hdop ?: 0.0), 1)
            else -> Check("GPS", "$fix · ${sats ?: "?"} sat · HDOP ${hdop?.let { "%.1f".format(it) } ?: "?"}", 2)
        }
    }
    val battery = d.batteryPct?.let { pct ->
        when {
            pct >= 50 -> Check("BAT", "$pct%", 0)
            pct >= 25 -> Check("BAT", "$pct%", 1)
            else -> Check("BAT", "$pct%", 2)
        }
    } ?: Check("BAT", "unknown", 1)
    val home = if (d.homeLatDeg != null && d.homeLonDeg != null)
        Check("HOME", "set", 0)
    else
        Check("HOME", "not set", 1)
    val alerts = d.latestAlert?.let { (sev, _, ts) ->
        if (now - ts < 30_000 && sev <= 2) Check("ALERT", "recent CRITICAL", 2)
        else Check("ALERT", "clear", 0)
    } ?: Check("ALERT", "clear", 0)
    return listOf(link, gps, battery, home, alerts)
}
