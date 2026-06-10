package soy.engindearing.omnitak.mobile.ui.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.vector.ImageVector
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.plugin.PluginCoTEvent
import soy.engindearing.omnitak.plugin.PluginHost
import soy.engindearing.omnitak.plugin.PluginLatLng
import soy.engindearing.omnitak.plugin.PluginRadialAction

/**
 * Identifies which plugin contributed a Settings row, so the row can navigate
 * to the right plugin's [soy.engindearing.omnitak.plugin.OmniTAKPlugin.settingsContent].
 */
data class PluginSettingsRowSpec(
    val label: String,
    val icon: ImageVector,
    val pluginId: String,
)

/**
 * The `:app` implementation of [PluginHost]. Holds the registration lists the
 * four real seams read from:
 *  - [mapOverlays]   → rendered in MapScreen's chrome on BOTH map engines
 *  - [radialActions] → appended to the map long-press radial menu
 *  - [cotHandlers]   → run after ContactStore ingest (via [dispatchCoT])
 *  - [settingsRows]  → the Settings screen's Plugins section
 *
 * Registration lists are [SnapshotStateList]s so Compose recomposes when a
 * plugin activates/deactivates.
 *
 * The host adapts core types ⇄ SDK value types at this seam: [dispatchCoT]
 * maps the app's [CoTEvent] → [PluginCoTEvent] so the SDK never depends on the
 * app's internal CoT model.
 *
 * Because a single [PluginHost] interface stays minimal, the per-plugin
 * bookkeeping needed to *remove* a plugin's hooks on disable lives here as
 * [clearForPlugin]. Each registration tracks the id of the plugin that is
 * currently activating (set by [withActivating], which the registry's
 * `onActivating` bracket invokes); deactivation drops everything that plugin
 * contributed.
 */
class AppPluginHost {

    val mapOverlays: SnapshotStateList<RegisteredOverlay> = mutableStateListOf()
    val radialActions: SnapshotStateList<RegisteredRadial> = mutableStateListOf()
    val cotHandlers: SnapshotStateList<RegisteredCoT> = mutableStateListOf()
    val settingsRows: SnapshotStateList<PluginSettingsRowSpec> = mutableStateListOf()

    /** The plugin id currently activating; tagged onto each registration so
     *  [clearForPlugin] can remove exactly that plugin's hooks on disable. */
    private var activatingPluginId: String? = null

    data class RegisteredOverlay(val pluginId: String?, val content: @Composable () -> Unit)
    data class RegisteredRadial(
        val pluginId: String?,
        val action: PluginRadialAction,
        val onSelect: (PluginLatLng) -> Unit,
    )
    data class RegisteredCoT(val pluginId: String?, val handler: (PluginCoTEvent) -> Boolean)

    /**
     * Run [block] (a plugin's `activate(host)` call) with registrations tagged
     * to [pluginId], so disabling that plugin later removes its hooks.
     */
    fun <T> withActivating(pluginId: String, block: () -> T): T {
        val prev = activatingPluginId
        activatingPluginId = pluginId
        try {
            return block()
        } finally {
            activatingPluginId = prev
        }
    }

    override fun toString(): String = "AppPluginHost(overlays=${mapOverlays.size}, " +
        "radials=${radialActions.size}, cot=${cotHandlers.size}, settings=${settingsRows.size})"

    // --- PluginHost surface ------------------------------------------------

    private val host = object : PluginHost {
        override fun registerMapOverlay(overlay: @Composable () -> Unit) {
            mapOverlays.add(RegisteredOverlay(activatingPluginId, overlay))
        }

        override fun registerRadialAction(
            action: PluginRadialAction,
            onSelect: (PluginLatLng) -> Unit,
        ) {
            radialActions.add(RegisteredRadial(activatingPluginId, action, onSelect))
        }

        override fun registerCoTHandler(handler: (PluginCoTEvent) -> Boolean) {
            cotHandlers.add(RegisteredCoT(activatingPluginId, handler))
        }

        override fun registerSettingsRow(label: String, icon: ImageVector) {
            settingsRows.add(PluginSettingsRowSpec(label, icon, activatingPluginId ?: ""))
        }
    }

    /** The [PluginHost] view passed to [soy.engindearing.omnitak.plugin.OmniTAKPlugin.activate]. */
    fun asPluginHost(): PluginHost = host

    /** Remove every hook a plugin registered (called when it's disabled). */
    fun clearForPlugin(pluginId: String) {
        mapOverlays.removeAll { it.pluginId == pluginId }
        radialActions.removeAll { it.pluginId == pluginId }
        cotHandlers.removeAll { it.pluginId == pluginId }
        settingsRows.removeAll { it.pluginId == pluginId }
    }

    /**
     * Adapt a core [CoTEvent] → [PluginCoTEvent] and run every registered CoT
     * handler. Called from [soy.engindearing.omnitak.mobile.domain.ServerManager]
     * AFTER the core ContactStore has ingested the event. Returns true if any
     * handler claimed the event (advisory in v1 — ingest already happened).
     */
    fun dispatchCoT(event: CoTEvent): Boolean {
        if (cotHandlers.isEmpty()) return false
        val adapted = PluginCoTEvent(
            uid = event.uid,
            lat = event.lat,
            lon = event.lon,
            callsign = event.callsign,
            type = event.type,
            rawXml = event.rawXml,
        )
        var consumed = false
        // Snapshot to a plain list so a handler mutating registrations can't
        // ConcurrentModification the iteration.
        for (h in cotHandlers.toList()) {
            if (h.handler(adapted)) consumed = true
        }
        return consumed
    }
}
