package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import soy.engindearing.omnitak.mobile.data.uas.MissionPhase
import soy.engindearing.omnitak.mobile.data.uas.WaypointMission

/**
 * Floating top-of-map banner that surfaces mission state + the two
 * actions the operator needs while building one: Upload & Start, and
 * Cancel. Shows nothing when [missionMode] is off AND the mission is
 * empty — keeps the map clean for users who never touch UAS.
 */
@Composable
fun MissionBanner(
    missionMode: Boolean,
    mission: WaypointMission,
    onUploadAndStart: () -> Unit,
    onUndo: () -> Unit,
    onCancel: () -> Unit,
    onExitMissionMode: () -> Unit,
    onRehearse: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val visible = missionMode || mission.waypoints.isNotEmpty()
    if (!visible) return

    val (statusText, statusColor) = when (mission.phase) {
        MissionPhase.DRAFT -> "DRAFT" to Color(0xFF00E5FF)
        MissionPhase.UPLOADING -> "UPLOADING…" to Color(0xFFFFC107)
        MissionPhase.UPLOADED -> "UPLOADED" to Color(0xFF00E5FF)
        MissionPhase.STARTED -> "EXECUTING leg ${mission.currentSeq + 1}/${mission.waypoints.size}" to Color(0xFFFFC107)
        MissionPhase.FAILED -> "FAILED" to Color(0xFFFF3B30)
    }
    val canUpload = mission.canUpload

    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.78f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "UAS Mission",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = statusText,
                    color = statusColor,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                text = "${mission.waypoints.size} waypoint(s) — " +
                        if (missionMode) "tap the map to add" else "exit add-mode to upload",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
            )
            mission.errorMessage?.let {
                Text(it, color = Color(0xFFFF3B30), fontSize = MaterialTheme.typography.bodySmall.fontSize)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (missionMode) {
                    OutlinedButton(
                        onClick = onUndo,
                        enabled = mission.waypoints.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Undo") }
                    Button(
                        onClick = onExitMissionMode,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00E5FF),
                            contentColor = Color.Black,
                        ),
                    ) { Text("Done") }
                } else {
                    OutlinedButton(
                        onClick = onRehearse,
                        enabled = canUpload,
                        modifier = Modifier.weight(1f),
                    ) { Text("Rehearse") }
                    Button(
                        onClick = onUploadAndStart,
                        enabled = canUpload,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF34C759),
                            contentColor = Color.Black,
                            disabledContainerColor = Color.Gray.copy(alpha = 0.3f),
                        ),
                    ) { Text("Upload & Start") }
                    TextButton(onClick = onCancel) { Text("Cancel", color = Color(0xFFFF3B30)) }
                }
            }
        }
    }
}
