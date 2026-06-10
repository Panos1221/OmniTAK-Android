package soy.engindearing.omnitak.plugin

import android.content.SharedPreferences

/**
 * Process-wide registry of bundled plugins. Populated at app start via
 * `OmniTAKApp.loadBundledPlugins()`, which calls [register] for each bundled
 * plugin and then [activateEnabled].
 *
 * The enable flag is a per-plugin `plugin_<id>_enabled` boolean in
 * SharedPreferences, **default true on first run** so first-time users see
 * plugin features (e.g. ADS-B) without hunting through Settings — and so an
 * existing tester sees no behavior change after the SDK refactor lands.
 */
object PluginRegistry {
    private val plugins = mutableListOf<OmniTAKPlugin>()
    private val activated = mutableSetOf<String>()

    /** The SharedPreferences key carrying a plugin's enable flag. */
    fun enabledKey(pluginId: String): String = "plugin_${pluginId}_enabled"

    /** Register a bundled plugin. Idempotent on [OmniTAKPlugin.pluginId]. */
    fun register(plugin: OmniTAKPlugin) {
        if (plugins.none { it.pluginId == plugin.pluginId }) plugins += plugin
    }

    /** All registered plugins, in registration order. */
    fun all(): List<OmniTAKPlugin> = plugins.toList()

    /** True if the plugin has been activated this process. */
    fun isActivated(pluginId: String): Boolean = pluginId in activated

    /**
     * Activate every registered plugin whose enable flag is true (default
     * true) and that isn't already active. Safe to call repeatedly — only
     * not-yet-activated, enabled plugins are activated.
     *
     * [onActivating] lets the host bracket each plugin's `activate()` call —
     * the app uses it to tag the hooks a plugin registers with that plugin's
     * id so they can be removed on disable. It's invoked as
     * `onActivating(pluginId) { plugin.activate(host) }`.
     */
    fun activateEnabled(
        host: PluginHost,
        prefs: SharedPreferences,
        onActivating: (pluginId: String, activate: () -> Unit) -> Unit = { _, run -> run() },
    ) {
        for (p in plugins) {
            if (prefs.getBoolean(enabledKey(p.pluginId), true) && p.pluginId !in activated) {
                onActivating(p.pluginId) { p.activate(host) }
                activated += p.pluginId
            }
        }
    }

    /**
     * Activate a single plugin by id if it isn't already active. Used when the
     * user flips its toggle on. Returns true if it was (or already is) active.
     */
    fun activate(
        pluginId: String,
        host: PluginHost,
        onActivating: (pluginId: String, activate: () -> Unit) -> Unit = { _, run -> run() },
    ): Boolean {
        if (pluginId in activated) return true
        val p = plugins.firstOrNull { it.pluginId == pluginId } ?: return false
        onActivating(pluginId) { p.activate(host) }
        activated += pluginId
        return true
    }

    /** Deactivate a plugin (user disabled it). Calls [OmniTAKPlugin.deactivate]. */
    fun deactivate(pluginId: String) {
        plugins.firstOrNull { it.pluginId == pluginId }?.deactivate()
        activated -= pluginId
    }

    /** Test-only: drop all registered/activated state. */
    fun clearForTest() {
        plugins.clear()
        activated.clear()
    }
}
