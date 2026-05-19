package soy.engindearing.omnitak.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.data.uas.MavlinkConnection
import soy.engindearing.omnitak.mobile.ui.theme.HostileRed
import soy.engindearing.omnitak.mobile.ui.theme.NeutralYellow
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground
import soy.engindearing.omnitak.mobile.ui.theme.TacticalSurface

/**
 * UAS Quick Connect. Connect a MAVLink-speaking UAS (PX4 / ArduPilot /
 * Skydio X2D / Teal / Pixhawk-based), see its telemetry live, and issue
 * arm / takeoff / RTL. Day-one is developer-grade — operator radial
 * menu, waypoint mission builder, video feed, and per-autopilot mode
 * names are fast-follows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UASScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as OmniTAKApp
    val uas = app.uasManager
    val scope = rememberCoroutineScope()

    val state by uas.state.collectAsState()
    val isConnected = state.isConnected()

    var transport by remember { mutableStateOf(MavlinkConnection.Transport.UDP) }
    var host by remember { mutableStateOf("10.0.2.2") } // emulator-to-host SITL default
    var portText by remember { mutableStateOf("14550") }
    var callsign by remember { mutableStateOf("OmniTAK-UAS") }

    val currentRtsp by uas.rtspUrl.collectAsState()
    var rtspText by remember(currentRtsp) { mutableStateOf(currentRtsp) }
    val currentGeofence by uas.geofenceMeters.collectAsState()
    var geofenceText by remember(currentGeofence) { mutableStateOf(currentGeofence.toString()) }
    val failsafes by uas.failsafeParams.collectAsState()
    var failsafeSheetOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = TacticalBackground,
        topBar = {
            TopAppBar(
                title = { Text("UAS Quick Connect") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TacticalBackground,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TacticalBackground)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .imePadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                if (!isConnected) {
                    Button(
                        onClick = {
                            val port = portText.toIntOrNull() ?: 14550
                            uas.connect(host.trim(), port, callsign.trim(), transport = transport)
                        },
                        enabled = host.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TacticalAccent,
                            contentColor = TacticalBackground,
                        ),
                    ) { Text("Connect") }
                } else {
                    OutlinedButton(
                        onClick = { uas.disconnect() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Disconnect", color = HostileRed) }
                }
            }
        },
    ) { inner: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Connect a MAVLink UAS. Day-one supports PX4 / ArduPilot per the RAS-A v1.2 IOP. Your CoT marker flows to every other operator on the active TAK server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )

            // Transport selector. UDP is the field default (vehicle Wi-Fi).
            // TCP is for SSH-tunneled SITL or any net where UDP is blocked
            // (consumer routers often filter inter-VLAN UDP).
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Transport", style = MaterialTheme.typography.labelLarge,
                     color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                MavlinkConnection.Transport.entries.forEach { t ->
                    FilterChip(
                        selected = transport == t,
                        onClick = {
                            transport = t
                            // Helpful default ports: UDP=14550, TCP=14555.
                            // Operator can override.
                            portText = if (t == MavlinkConnection.Transport.UDP) "14550" else "14555"
                        },
                        label = { Text(t.name) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TacticalAccent,
                            selectedLabelColor = TacticalBackground,
                        ),
                    )
                }
            }

            TextField(
                value = host,
                onValueChange = { host = it.trim() },
                label = { Text("UAS host") },
                placeholder = { Text("10.0.2.2 (emulator→Mac) / 192.168.4.1 (vehicle Wi-Fi)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit) },
                    label = { Text(if (transport == MavlinkConnection.Transport.TCP) "TCP port" else "UDP port") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                TextField(
                    value = callsign,
                    onValueChange = { callsign = it },
                    label = { Text("Callsign") },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                )
            }

            HorizontalDivider(color = TacticalSurface)

            // -------- Live video (optional) --------
            TextField(
                value = rtspText,
                onValueChange = { rtspText = it.trim(); uas.setRtspUrl(rtspText) },
                label = { Text("Video URL (RTSP)") },
                placeholder = { Text("rtsp://10.0.2.2:8555/test  •  blank to hide") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    rtspText = "rtsp://10.0.2.2:8555/test"
                    uas.setRtspUrl(rtspText)
                }) { Text("Use SITL test stream") }
                if (rtspText.isNotBlank()) {
                    OutlinedButton(onClick = {
                        rtspText = ""
                        uas.setRtspUrl("")
                    }) { Text("Clear", color = HostileRed) }
                }
            }

            // -------- Failsafe inspection (read-only) --------
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { failsafeSheetOpen = true },
                    enabled = isConnected,
                ) { Text("Failsafes (${failsafes.size} params)") }
                if (isConnected) {
                    OutlinedButton(onClick = { scope.launch { uas.requestFailsafes() } }) {
                        Text("Re-fetch")
                    }
                }
            }
            if (failsafeSheetOpen) {
                soy.engindearing.omnitak.mobile.ui.components.UasFailsafeSheet(
                    params = failsafes,
                    autopilot = state.autopilot,
                    onDismiss = { failsafeSheetOpen = false },
                )
            }

            // -------- Geofence radius (meters from home) --------
            TextField(
                value = geofenceText,
                onValueChange = { s ->
                    geofenceText = s.filter { it.isDigit() }
                    geofenceText.toIntOrNull()?.let { uas.setGeofenceMeters(it) }
                },
                label = { Text("Geofence radius (m from home)") },
                placeholder = { Text("500  •  0 to disable") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            HorizontalDivider(color = TacticalSurface)

            // -------- Telemetry HUD --------
            TelemetryRow("Link", if (isConnected) "CONNECTED" else "—",
                if (isConnected) TacticalAccent else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
            TelemetryRow("Autopilot", state.autopilot ?: "—")
            TelemetryRow("Vehicle", state.vehicleType ?: "—")
            TelemetryRow("Armed",
                state.armed?.let { if (it) "ARMED" else "DISARMED" } ?: "—",
                state.armed?.let { if (it) HostileRed else TacticalAccent } ?: MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
            TelemetryRow("Flight mode", state.flightMode ?: "—")
            TelemetryRow("Lat / Lon",
                state.latDeg?.let { "%.6f, %.6f".format(it, state.lonDeg ?: 0.0) } ?: "—")
            TelemetryRow("Alt AGL", state.altAglMeters?.let { "%.1f m".format(it) } ?: "—")
            TelemetryRow("Alt MSL", state.altMslMeters?.let { "%.1f m".format(it) } ?: "—")
            TelemetryRow("Heading", state.headingDeg?.let { "%.0f°".format(it) } ?: "—")
            TelemetryRow("Ground spd", state.groundSpeedMps?.let { "%.1f m/s".format(it) } ?: "—")
            TelemetryRow("Battery", state.batteryPct?.let { "$it%" } ?: "—")

            HorizontalDivider(color = TacticalSurface)

            // -------- Commands --------
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CommandBtn("Arm", enabled = isConnected && state.armed == false) {
                    scope.launch { uas.arm() }
                }
                CommandBtn("Disarm", enabled = isConnected && state.armed == true) {
                    scope.launch { uas.disarm() }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CommandBtn("Takeoff 10 m", enabled = isConnected && state.armed == true) {
                    scope.launch { uas.takeoff(10f) }
                }
                CommandBtn("RTL", enabled = isConnected, color = NeutralYellow) {
                    scope.launch { uas.returnToLaunch() }
                }
            }

            HorizontalDivider(color = TacticalSurface)

            Text("STATUSTEXT", style = MaterialTheme.typography.labelMedium)
            if (state.recentStatusText.isEmpty()) {
                Text("—", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
            } else {
                state.recentStatusText.takeLast(6).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun TelemetryRow(label: String, value: String, valueColor: Color? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
        Text(value, style = MaterialTheme.typography.bodyMedium,
             color = valueColor ?: MaterialTheme.colorScheme.onBackground,
             fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun CommandBtn(
    label: String,
    enabled: Boolean,
    color: Color = TacticalAccent,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(0.48f),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = TacticalBackground,
            disabledContainerColor = TacticalSurface,
        ),
    ) { Text(label) }
}
