package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.ChatMessage
import soy.engindearing.omnitak.mobile.data.ChatStatus

/**
 * #173 — the pure notify/don't-notify decision for incoming mesh GeoChat.
 * Kept Android-free so it runs on the JVM unit-test path alongside the codec
 * tests; the Android plumbing (channel + NotificationManager) is exercised at
 * runtime, not here.
 */
class MeshChatNotifierTest {

    private fun msg(
        text: String = "rally on me",
        fromSelf: Boolean = false,
        status: ChatStatus = ChatStatus.RECEIVED,
        conversation: String = "MESH-CH0",
    ) = ChatMessage(
        conversationId = conversation,
        senderUid = "MESHTASTIC-0A1B2C3D",
        senderCallsign = "BRAVO2",
        text = text,
        timeIso = "2026-01-01T00:00:00Z",
        status = status,
        isFromSelf = fromSelf,
    )

    @Test
    fun `notify on incoming GeoChat from a peer`() {
        assertTrue(MeshChatNotifier.shouldNotify(msg()))
    }

    @Test
    fun `do not notify for our own echo`() {
        assertFalse(MeshChatNotifier.shouldNotify(msg(fromSelf = true)))
    }

    @Test
    fun `do not notify for blank text`() {
        assertFalse(MeshChatNotifier.shouldNotify(msg(text = "")))
        assertFalse(MeshChatNotifier.shouldNotify(msg(text = "   ")))
    }

    @Test
    fun `incoming DM from a peer notifies`() {
        assertTrue(MeshChatNotifier.shouldNotify(msg(conversation = "MESH-DM-0A1B2C3D")))
    }

    @Test
    fun `notification id is stable per conversation and differs across conversations`() {
        val a1 = MeshChatNotifier.notificationId("MESH-CH0")
        val a2 = MeshChatNotifier.notificationId("MESH-CH0")
        val b = MeshChatNotifier.notificationId("MESH-DM-0A1B2C3D")
        assertEquals("same conversation -> same id", a1, a2)
        assertTrue("different conversation -> different id", a1 != b)
    }
}
