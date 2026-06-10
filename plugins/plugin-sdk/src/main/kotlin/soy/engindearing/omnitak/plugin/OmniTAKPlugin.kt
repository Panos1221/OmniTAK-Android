package soy.engindearing.omnitak.plugin

import androidx.compose.runtime.Composable

/**
 * The OmniTAK Android plugin contract. Implement this interface to ship a
 * compile-time plugin. Mirrors the iOS `OmniTAKPlugin` protocol field-for-field
 * so an author can port a plugin between platforms in roughly a day.
 *
 * ## Lifecycle & threading
 * A plugin is a plain object registered with [PluginRegistry] at app start
 * (see `OmniTAKApp.loadBundledPlugins()`). [activate] is called once, on the
 * main thread, after the host is fully initialised; the plugin registers all
 * of its hooks through the supplied [PluginHost] there. [deactivate] is called
 * when the user disables the plugin in the Plugins screen — the plugin should
 * stop any background work it started.
 *
 * ## Security model — IMPORTANT
 * Plugins run **in the host process with the host's permissions**. There is no
 * sandbox and no AIDL boundary. A plugin can do anything the app can do
 * (network, location, files). Only bundle plugins you trust.
 *
 * ## Store compliance
 * Plugins are compile-time Kotlin/Gradle modules linked into the app at build
 * time. There is **no** DEX class loading, dylib loading, or remote code
 * download — the registry is populated from statically-linked classes at app
 * start. This is what keeps OmniTAK Play-Store compliant.
 */
interface OmniTAKPlugin {
    /** Reverse-DNS identifier, e.g. `"soy.engindearing.adsb"`. Must be stable
     *  and identical to the iOS plugin's id so settings can sync across
     *  platforms. Used as the key for the `plugin_<id>_enabled` flag. */
    val pluginId: String

    /** Human-readable name shown in the Plugins list. */
    val displayName: String

    /** Semver string, e.g. `"1.0.0"`. */
    val pluginVersion: String

    /** Author / vendor name. */
    val pluginAuthor: String

    /** One-line description shown on the plugin detail screen. */
    val pluginDescription: String

    /**
     * Called once at app start (or when the user re-enables the plugin), after
     * the host is fully initialised. Register all hooks via [host] here.
     */
    fun activate(host: PluginHost)

    /**
     * Called when the user disables the plugin. Stop background work and free
     * resources. The host removes this plugin's registered hooks separately
     * (see `AppPluginHost.clearForPlugin`), so the plugin only needs to stop
     * its own services here.
     */
    fun deactivate()

    /**
     * Optional Compose settings UI shown on the plugin detail screen, reached
     * from the Settings row the plugin registers. Return null (the default)
     * for a plugin with nothing to configure.
     */
    fun settingsContent(): (@Composable () -> Unit)? = null
}
