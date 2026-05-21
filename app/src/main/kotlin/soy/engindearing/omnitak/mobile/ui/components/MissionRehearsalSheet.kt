package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import soy.engindearing.omnitak.mobile.domain.MissionRehearsal

/**
 * Pre-upload mission rehearsal sheet. Shows TAK Terrain DEM samples for
 * every waypoint + interpolated points on every leg, with per-sample
 * clearance colored red/yellow/green. Overall verdict + reason at the
 * top, "Upload anyway" / "Upload & Start" buttons at the bottom:
 *  - GO  → green Upload & Start, no warning
 *  - WARN → yellow Upload anyway, with the reason called out
 *  - NO_GO → red banner, Upload & Start disabled (operator must raise
 *            cruise alt or move the waypoint to fix it)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissionRehearsalSheet(
    result: MissionRehearsal.Result?,
    running: Boolean,
    onUploadAnyway: () -> Unit,
    onUploadAndStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1A1F2E),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Mission rehearsal",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            if (running || result == null) {
                Text(
                    "Sampling TAK Terrain along the mission path…",
                    color = Color(0xFFFFC107),
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }

            VerdictBanner(result)

            // Sample table — scrollable so even a 30-step survey fits.
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                result.samples.forEach { s -> SampleRow(s) }
            }

            // Action row. NO_GO disables the cleared paths; operator
            // can still escape via Cancel + edit waypoints.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                when (result.verdict) {
                    MissionRehearsal.Verdict.GO -> Button(
                        onClick = onUploadAndStart,
                        modifier = Modifier.weight(1.5f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF34C759),
                            contentColor = Color.Black,
                        ),
                    ) { Text("Upload & Start") }
                    MissionRehearsal.Verdict.WARN -> Button(
                        onClick = onUploadAnyway,
                        modifier = Modifier.weight(1.5f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFFC107),
                            contentColor = Color.Black,
                        ),
                    ) { Text("Upload anyway") }
                    MissionRehearsal.Verdict.NO_GO -> Button(
                        onClick = {}, enabled = false,
                        modifier = Modifier.weight(1.5f),
                        colors = ButtonDefaults.buttonColors(
                            disabledContainerColor = Color(0xFFFF3B30).copy(alpha = 0.4f),
                            disabledContentColor = Color.White,
                        ),
                    ) { Text("NO-GO — edit waypoints") }
                }
            }
        }
    }
}

@Composable
private fun VerdictBanner(r: MissionRehearsal.Result) {
    val (bg, fg, label) = when (r.verdict) {
        MissionRehearsal.Verdict.GO ->
            Triple(Color(0xFF34C759).copy(alpha = 0.18f), Color(0xFF34C759), "GO")
        MissionRehearsal.Verdict.WARN ->
            Triple(Color(0xFFFFC107).copy(alpha = 0.18f), Color(0xFFFFC107), "WARN")
        MissionRehearsal.Verdict.NO_GO ->
            Triple(Color(0xFFFF3B30).copy(alpha = 0.22f), Color(0xFFFF3B30), "NO-GO")
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, color = fg, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleMedium)
                Text(
                    "${r.samples.size} samples, ${r.unknownTerrainCount} no-data",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                r.verdictReason,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SampleRow(s: MissionRehearsal.Sample) {
    val color = when {
        s.clearanceM == null -> Color(0xFFFFC107)
        s.clearanceM < 10 -> Color(0xFFFF3B30)
        s.clearanceM < 20 -> Color(0xFFFFC107)
        else -> Color(0xFF34C759)
    }
    val rightText = s.clearanceM?.let { "${it.toInt()} m" } ?: "?"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.3f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(s.label, color = Color.White, fontFamily = FontFamily.Monospace,
             style = MaterialTheme.typography.bodySmall)
        Text(
            "alt %.0fm · terr %s · %s".format(
                s.plannedAltMsl,
                s.terrainMsl?.let { "%.0fm".format(it) } ?: "?",
                rightText,
            ),
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
