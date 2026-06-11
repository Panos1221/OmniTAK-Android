package soy.engindearing.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The Diagnostics plugin's settings surface, reached from Settings → Plugins →
 * Diagnostics. Read-only liveness readouts — there is no on/off control here
 * (the plugin enable toggle on the Plugins list / detail screen IS the control);
 * this just shows what the two registered hooks have observed:
 *  - the running inbound-CoT event count
 *  - the last "Diagnostics" radial long-press coordinate
 *
 * Both come from [DiagnosticsState] as StateFlows, so the readouts update live
 * while the plugin is enabled (same reactive pattern as `AdsbSettingsContent`).
 */
@Composable
fun DiagnosticsSettingsContent(state: DiagnosticsState) {
    val cotCount by state.cotEventCount.collectAsState()
    val lastCoord by state.lastRadialCoordinate.collectAsState()

    Column(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
        Text(
            "SDK diagnostics probe",
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "Counts inbound CoT events and records the last Diagnostics radial " +
                "long-press. Does not modify any traffic.",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp),
        )

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "CoT events seen",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "$cotCount",
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Last radial coordinate",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                lastCoord?.let { "%.5f, %.5f".format(it.latitude, it.longitude) } ?: "—",
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Text(
            "Long-press the map and pick \"Diagnostics\" to capture a coordinate. " +
                "Enabling this plugin does not affect shipping behavior — it only " +
                "exercises the radial-action and CoT-handler SDK hooks.",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
