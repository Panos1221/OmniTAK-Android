package soy.engindearing.diagnostics

import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.runtime.Composable
import soy.engindearing.omnitak.plugin.OmniTAKPlugin
import soy.engindearing.omnitak.plugin.PluginHost
import soy.engindearing.omnitak.plugin.PluginRadialAction

/**
 * The Diagnostics reference plugin — the second bundled OmniTAK plugin and the
 * Android counterpart of iOS's `DiagnosticsPlugin`.
 *
 * Where the ADS-B plugin exercises [PluginHost.registerMapOverlay] +
 * [PluginHost.registerSettingsRow], this one exercises the OTHER two host hooks
 * so the entire SDK surface is demonstrated by real bundled plugins (not only
 * the `:app` PluginHostSurfaceTest probe):
 *  - [PluginHost.registerRadialAction] — a "Diagnostics" long-press action that
 *    logs / records the tapped coordinate.
 *  - [PluginHost.registerCoTHandler]   — observes every inbound CoT event,
 *    counts it, and returns **false** (does NOT consume), so the core pipeline
 *    is untouched.
 *
 * It is **DISABLED by default** (see `OmniTAKApp.diagnosticsDisabledByDefault`,
 * seeded as `plugin_<id>_enabled = false` on first run), matching iOS's
 * `registeredDisabledByDefault`. It never affects shipping users — it only
 * proves the surface works and gives plugin authors a second, simpler template.
 *
 * Like ADS-B, it depends on ONLY `:plugins:plugin-sdk` (+ Compose) — never on
 * `:app` — so the dependency graph stays acyclic.
 */
class DiagnosticsPlugin : OmniTAKPlugin {

    override val pluginId = "soy.engindearing.diagnostics"
    override val displayName = "Diagnostics"
    override val pluginVersion = "1.0.0"
    override val pluginAuthor = "Engindearing"
    override val pluginDescription =
        "Internal SDK diagnostics — a radial probe that logs the tapped " +
            "coordinate and an inbound-CoT event counter. Off by default; " +
            "demonstrates the radial-action + CoT-handler host hooks."

    /** Live, observable state surfaced in [settingsContent]. */
    val state = DiagnosticsState()

    override fun activate(host: PluginHost) {
        // Hook 3: radial action. Adds a "Diagnostics" entry to the map
        // long-press radial menu; selecting it logs + records the coordinate.
        host.registerRadialAction(
            PluginRadialAction(
                id = "soy.engindearing.diagnostics.probe",
                icon = Icons.Filled.BugReport,
                label = "Diagnostics",
            ),
        ) { coordinate ->
            Log.i(TAG, "radial action at ${coordinate.latitude}, ${coordinate.longitude}")
            state.recordRadialCoordinate(coordinate)
        }

        // Hook 4: CoT handler. Counts every inbound event and returns false
        // (does NOT consume) so the rest of the pipeline is untouched.
        host.registerCoTHandler { event ->
            state.recordCoTEvent()
            Log.v(TAG, "observed CoT ${event.uid} (${event.type}) — count=${state.cotEventCount.value}")
            false
        }

        // Hook 2: a Settings row so the live counters are reachable from
        // Settings → Plugins → Diagnostics. (The plugin's own settingsContent()
        // is also reachable directly from the Plugins list.)
        host.registerSettingsRow(displayName, Icons.Filled.BugReport)
    }

    override fun deactivate() {
        state.reset()
    }

    override fun settingsContent(): (@Composable () -> Unit) = {
        DiagnosticsSettingsContent(state = state)
    }

    private companion object {
        const val TAG = "DiagnosticsPlugin"
    }
}
