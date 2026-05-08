package soy.engindearing.omnitak.mobile.domain

/**
 * App-scoped persistence for the map camera position so the operator's
 * pan/zoom survives bottom-nav switches between Map and other tabs.
 *
 * `remember { MapView(...) }` inside [TacticalMap] is destroyed each
 * time MapScreen leaves the composition (Compose Navigation drops the
 * composable, even with `saveState = true`, because plain `remember`
 * isn't backed by SavedStateHandle). Without a state holder above the
 * NavHost the map snaps back to its default center every time the user
 * dips into Settings / Servers / Chat — that's what Discord testers
 * called "map re-centering to Spokane when exiting setting or other
 * menus." (See repo issue #7.)
 *
 * Survives tab switches but not process death — that's deliberate. If
 * we ever want it durable across cold launches, persist into UserPrefs.
 */
class MapCameraStore {
    var lastTargetLat: Double? = null
        private set
    var lastTargetLon: Double? = null
        private set
    var lastZoom: Double? = null
        private set

    fun update(lat: Double, lon: Double, zoom: Double) {
        lastTargetLat = lat
        lastTargetLon = lon
        lastZoom = zoom
    }
}
