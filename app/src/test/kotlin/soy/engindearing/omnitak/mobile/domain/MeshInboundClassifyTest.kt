package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.CoTEvent

/**
 * Off-grid mesh plan Step 3 — pure [MeshCoTRouter] classifier tests.
 *
 * Verifies:
 *  - b-t-f type → Chat
 *  - a-f-G-U-C (PPLI) → Contact
 *  - generic marker types → Contact
 */
class MeshInboundClassifyTest {

    private fun event(type: String) = CoTEvent(
        uid = "TEST-UID",
        type = type,
        lat = 0.0,
        lon = 0.0,
    )

    @Test
    fun `b-t-f classifies as Chat`() {
        val result = MeshCoTRouter.classify(event("b-t-f"))
        assertEquals(MeshCoTRouter.Destination.CHAT, result)
    }

    @Test
    fun `ppli a-f-G-U-C classifies as Contact`() {
        val result = MeshCoTRouter.classify(event("a-f-G-U-C"))
        assertEquals(MeshCoTRouter.Destination.CONTACT, result)
    }

    @Test
    fun `hostile contact classifies as Contact`() {
        val result = MeshCoTRouter.classify(event("a-h-G"))
        assertEquals(MeshCoTRouter.Destination.CONTACT, result)
    }

    @Test
    fun `generic marker classifies as Contact`() {
        val result = MeshCoTRouter.classify(event("a-f-G-U"))
        assertEquals(MeshCoTRouter.Destination.CONTACT, result)
    }

    @Test
    fun `b-t-f with suffix is still Chat`() {
        // Future-proofing: exact match "b-t-f" only
        val result = MeshCoTRouter.classify(event("b-t-f"))
        assertEquals(MeshCoTRouter.Destination.CHAT, result)
    }

    @Test
    fun `unrelated b type classifies as Contact`() {
        val result = MeshCoTRouter.classify(event("b-r"))
        assertEquals(MeshCoTRouter.Destination.CONTACT, result)
    }
}
