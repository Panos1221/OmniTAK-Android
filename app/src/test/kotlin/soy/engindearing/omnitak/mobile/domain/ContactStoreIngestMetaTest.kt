package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.CoTSource

/**
 * #178 / #180 — ContactStore.ingest stamps a received-at timestamp and threads
 * the data source through, preserving an existing source when a same-UID
 * re-ingest arrives untagged.
 */
class ContactStoreIngestMetaTest {

    private fun event(uid: String = "ALPHA-1", source: CoTSource? = null, receivedAtMs: Long = 0L) =
        CoTEvent(uid = uid, type = "a-f-G-U-C", lat = 0.0, lon = 0.0, source = source, receivedAtMs = receivedAtMs)

    @Test fun ingest_stamps_received_at_when_unset() {
        val store = ContactStore()
        store.ingest(event(), nowMs = 5_000L)
        assertEquals(5_000L, store.contacts.value["ALPHA-1"]?.receivedAtMs)
    }

    @Test fun ingest_respects_a_caller_supplied_received_at() {
        // Pre-stamped events (e.g. fixed-clock tests) keep their value.
        val store = ContactStore()
        store.ingest(event(receivedAtMs = 42L), nowMs = 5_000L)
        assertEquals(42L, store.contacts.value["ALPHA-1"]?.receivedAtMs)
    }

    @Test fun ingest_keeps_the_tagged_source() {
        val store = ContactStore()
        store.ingest(event(source = CoTSource.takServer("HQ")), nowMs = 1L)
        assertEquals("TAK: HQ", store.contacts.value["ALPHA-1"]?.source?.label)
    }

    @Test fun re_ingest_without_a_source_preserves_the_existing_one() {
        // A later untagged update for the same UID must not blank the source.
        val store = ContactStore()
        store.ingest(event(source = CoTSource.mesh("Meshtastic")), nowMs = 1L)
        store.ingest(event(source = null), nowMs = 2L)
        assertEquals("Mesh: Meshtastic", store.contacts.value["ALPHA-1"]?.source?.label)
        // ...but the timestamp still advances on the re-ingest.
        assertEquals(2L, store.contacts.value["ALPHA-1"]?.receivedAtMs)
    }

    @Test fun re_ingest_with_a_new_source_overrides_the_old_one() {
        val store = ContactStore()
        store.ingest(event(source = CoTSource.LOCAL), nowMs = 1L)
        store.ingest(event(source = CoTSource.takServer("HQ")), nowMs = 2L)
        assertEquals("TAK: HQ", store.contacts.value["ALPHA-1"]?.source?.label)
    }

    @Test fun fresh_event_has_no_source_or_stamp_until_ingested() {
        assertNull(event().source)
        assertEquals(0L, event().receivedAtMs)
    }
}
