package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import soy.engindearing.omnitak.mobile.data.CoTEvent

/**
 * Application-scoped roster of last-known CoT contacts keyed by UID.
 * Updates are diff-friendly — the underlying StateFlow re-emits only
 * when a referentially-new map is assigned, and the GeoJson layer on
 * the map rebuilds features from whatever is latest here.
 *
 * Writers are concurrent (per-server collectors on Dispatchers.Default,
 * the Meshtastic cotSink, gyb + Remote ID pipelines on appScope), so
 * every read-modify-write goes through [MutableStateFlow.update]'s CAS
 * loop — a plain `value = value + x` can silently drop a concurrent
 * ingest under multi-server CoT burst.
 */
class ContactStore {
    private val _contacts = MutableStateFlow<Map<String, CoTEvent>>(emptyMap())
    val contacts: StateFlow<Map<String, CoTEvent>> = _contacts.asStateFlow()

    /** Insert or update a contact. Stale logic arrives with Slice 15. */
    fun ingest(event: CoTEvent) {
        _contacts.update { it + (event.uid to event) }
    }

    /** Remove a contact by UID. */
    fun remove(uid: String) {
        _contacts.update { if (uid in it) it - uid else it }
    }

    /** Drop everything — used on connection teardown or manual reset. */
    fun clear() {
        _contacts.value = emptyMap()
    }
}
