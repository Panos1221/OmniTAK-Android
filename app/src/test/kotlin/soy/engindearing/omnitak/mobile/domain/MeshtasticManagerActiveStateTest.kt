package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #36 — Device Settings observes `activeConnectionState` instead of
 * `state` (TCP-only). With no transport selected yet — the cold-start
 * shape — the flow must report Disconnected. Without that guarantee the
 * screen would default to "Connected" and surface a useless push
 * button, or trip a null cast in the connection-label `when`.
 */
class MeshtasticManagerActiveStateTest {

    @Test fun initial_value_is_disconnected_when_no_transport_active() {
        val mgr = MeshtasticManager()
        assertEquals(ConnectionState.Disconnected, mgr.activeConnectionState.value)
    }
}
