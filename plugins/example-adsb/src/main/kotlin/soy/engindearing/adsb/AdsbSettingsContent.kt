package soy.engindearing.adsb

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The canonical ADS-B control surface, reached from Settings → Plugins → ADS-B.
 * Replaces the old Tools-drawer "ADSB" button: the same on/off toggle, the same
 * OpenSky polling, the same "center on the camera target (else self position)"
 * box-seeding logic — just relocated behind the plugin's settings.
 *
 * [onToggle] is supplied by [AdsbPlugin.settingsContent]; the host wires the
 * camera-center supplier so the query box seeds on what the operator is looking
 * at, preserving the exact behavior from the legacy MapScreen toggle.
 */
@Composable
fun AdsbSettingsContent(
    service: AdsbService,
    onToggle: (Boolean) -> Unit,
) {
    val active by service.active.collectAsState()
    val aircraft by service.aircraft.collectAsState()
    val lastError by service.lastError.collectAsState()

    Column(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("ADS-B aircraft overlay", color = MaterialTheme.colorScheme.onBackground)
                Text(
                    if (active) "On — polling OpenSky every 15s" else "Off",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = active, onCheckedChange = onToggle)
        }

        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Tracked aircraft",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (active) "${aircraft.size}" else "—",
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Query box",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "±2.5° around view",
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        lastError?.let { err ->
            Text(
                "Last error: $err",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Text(
            "Source: OpenSky Network (anonymous tier). The box follows the map " +
                "as you pan. Visibility of the aircraft layer is also gated by " +
                "the \"Aircraft (ADSB)\" toggle in the map Layers dialog.",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
