package soy.engindearing.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import soy.engindearing.omnitak.plugin.PluginLatLng

/**
 * The Diagnostics plugin's tiny observable state. Exposed as StateFlows so the
 * plugin's [DiagnosticsSettingsContent] re-renders live (same reactive shape as
 * the ADS-B plugin's `AdsbService`), without the plugin needing to hold any
 * Compose state itself.
 *
 * It carries exactly the two liveness signals the two hooks produce:
 *  - [cotEventCount] — incremented by the registered CoT handler.
 *  - [lastRadialCoordinate] — set by the registered radial action's onSelect.
 */
class DiagnosticsState {

    private val _cotEventCount = MutableStateFlow(0)
    /** Number of inbound CoT events seen by the registered handler this session. */
    val cotEventCount: StateFlow<Int> = _cotEventCount.asStateFlow()

    private val _lastRadialCoordinate = MutableStateFlow<PluginLatLng?>(null)
    /** Coordinate of the most recent "Diagnostics" radial long-press, or null. */
    val lastRadialCoordinate: StateFlow<PluginLatLng?> = _lastRadialCoordinate.asStateFlow()

    /** Called by the CoT handler for every inbound event (after core ingest). */
    fun recordCoTEvent() {
        _cotEventCount.value += 1
    }

    /** Called by the radial action's onSelect with the long-pressed coordinate. */
    fun recordRadialCoordinate(coordinate: PluginLatLng) {
        _lastRadialCoordinate.value = coordinate
    }

    /** Reset on deactivate so a re-enable starts from a clean slate (parity with
     *  the iOS DiagnosticsPlugin, which zeroes its counter in deactivate). */
    fun reset() {
        _cotEventCount.value = 0
        _lastRadialCoordinate.value = null
    }
}
