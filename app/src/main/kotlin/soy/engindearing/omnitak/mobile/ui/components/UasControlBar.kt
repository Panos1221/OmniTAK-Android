package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.FlightLand
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import soy.engindearing.omnitak.mobile.data.uas.MissionPhase
import soy.engindearing.omnitak.mobile.data.uas.WaypointMission

/**
 * In-flight control bar — operator safety levers that need to be one
 * tap away while the drone is airborne.
 *
 * Layout: horizontal row of icon buttons, only when the drone is armed.
 *  - **LAND** (NAV_LAND) — set down at current position.
 *  - **PAUSE / RESUME** (toggle) — only when a mission is uploaded /
 *    executing.
 *  - **RTL** (return to launch) — same as the UAS screen, here for
 *    quick reach.
 *  - **E-STOP** (DO_FLIGHTTERMINATION) — destructive: confirms first.
 *    The drone WILL fall. This is the "I'd rather it crash than hurt
 *    someone" lever, not a recovery action.
 */
@Composable
fun UasControlBar(
    drone: DroneState,
    mission: WaypointMission,
    onLand: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRtl: () -> Unit,
    onEmergencyStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (drone.armed != true) return // disarmed = nothing to do

    var confirmEStop by remember { mutableStateOf(false) }
    val missionExecuting = mission.phase == MissionPhase.STARTED
    val missionUploaded = mission.phase == MissionPhase.UPLOADED ||
        mission.phase == MissionPhase.STARTED ||
        mission.phase == MissionPhase.UPLOADING

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.82f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlBtn(
            icon = Icons.Filled.FlightLand,
            label = "LAND",
            tint = Color(0xFF00E5FF),
            onClick = onLand,
        )
        if (missionUploaded) {
            if (missionExecuting) {
                ControlBtn(
                    icon = Icons.Filled.Pause,
                    label = "PAUSE",
                    tint = Color(0xFFFFC107),
                    onClick = onPause,
                )
            } else {
                ControlBtn(
                    icon = Icons.Filled.PlayArrow,
                    label = "RESUME",
                    tint = Color(0xFF34C759),
                    onClick = onResume,
                )
            }
        }
        ControlBtn(
            icon = Icons.Filled.Home,
            label = "RTL",
            tint = Color(0xFFFFC107),
            onClick = onRtl,
        )
        ControlBtn(
            icon = Icons.Filled.Warning,
            label = "E-STOP",
            tint = Color(0xFFFF3B30),
            onClick = { confirmEStop = true },
        )
    }

    if (confirmEStop) {
        AlertDialog(
            onDismissRequest = { confirmEStop = false },
            title = { Text("Emergency stop?", color = Color(0xFFFF3B30)) },
            text = {
                Text(
                    "MAV_CMD_DO_FLIGHTTERMINATION cuts motors immediately. " +
                        "The drone WILL fall from current altitude. Only confirm if a " +
                        "controlled crash is safer than continued flight (fly-away, lost " +
                        "link, crowd risk).",
                    color = Color.White,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmEStop = false
                        onEmergencyStop()
                    },
                ) { Text("YES, cut motors", color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEStop = false }) { Text("Cancel") }
            },
            containerColor = Color(0xFF1A1F2E),
        )
    }
}

@Composable
private fun ControlBtn(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.0f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
            Text(
                label,
                color = tint,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
