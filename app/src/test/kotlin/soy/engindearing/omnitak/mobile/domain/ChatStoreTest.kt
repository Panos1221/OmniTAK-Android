package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.ChatConversation
import soy.engindearing.omnitak.mobile.data.ChatParticipant

/**
 * GAP-123 — verifies the upsertOrRename behavior used to fold
 * radio-reported channel names into existing chat conversations.
 */
class ChatStoreTest {

    @Test fun upsertOrRename_creates_when_missing() {
        val store = ChatStore()
        store.upsertOrRenameConversation("MESH-CH3", "Mesh: Local")
        val convo = store.conversations.value["MESH-CH3"]
        assertEquals("Mesh: Local", convo?.title)
    }

    @Test fun upsertOrRename_updates_existing_title() {
        val store = ChatStore()
        store.upsertConversationIfMissing("MESH-CH0", "Mesh: Primary")
        store.upsertOrRenameConversation("MESH-CH0", "Mesh: OmniTAK")
        val convo = store.conversations.value["MESH-CH0"]
        assertEquals("Mesh: OmniTAK", convo?.title)
    }

    @Test fun upsertOrRename_no_op_when_title_matches() {
        val store = ChatStore()
        store.upsertConversationIfMissing("MESH-CH0", "Mesh: Primary")
        val before = store.conversations.value["MESH-CH0"]
        store.upsertOrRenameConversation("MESH-CH0", "Mesh: Primary")
        val after = store.conversations.value["MESH-CH0"]
        // Same instance — no churn for redundant updates.
        assertEquals(before, after)
    }

    // --- ContactStore merge behavior (P-E bug: contacts not shown in chat) ---

    @Test fun upsertConversation_full_object_seeds_with_participants() {
        val store = ChatStore()
        val stub = ChatConversation(
            id = "DM-uid-peer",
            title = "EAGLE-1",
            isGroup = false,
            participants = listOf(ChatParticipant(uid = "uid-peer", callsign = "EAGLE-1")),
        )
        store.upsertConversationIfMissing(stub)
        val convo = store.conversations.value["DM-uid-peer"]
        assertNotNull(convo)
        assertEquals("EAGLE-1", convo?.title)
        assertEquals(1, convo?.participants?.size)
        assertEquals("uid-peer", convo?.participants?.first()?.uid)
    }

    @Test fun upsertConversation_full_object_no_op_when_exists() {
        val store = ChatStore()
        store.upsertConversationIfMissing("DM-uid-peer", "EAGLE-1", isGroup = false)
        val before = store.conversations.value["DM-uid-peer"]
        val stub = ChatConversation(
            id = "DM-uid-peer",
            title = "EAGLE-1-renamed",
            isGroup = false,
        )
        store.upsertConversationIfMissing(stub)
        // Pre-existing entry must not be replaced.
        val after = store.conversations.value["DM-uid-peer"]
        assertEquals(before?.title, after?.title)
    }
}
