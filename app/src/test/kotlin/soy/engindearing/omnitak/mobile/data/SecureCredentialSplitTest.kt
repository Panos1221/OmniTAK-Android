package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the secret split/rehydrate logic TAKServerStore runs around
 * its JSON blob (the real [SecureCredentialStore] needs the Android
 * Keystore, so it's swapped for an in-memory fake here).
 */
class SecureCredentialSplitTest {

    private class FakeCredentialStore(
        override val available: Boolean = true,
    ) : CredentialStore {
        val passwords = mutableMapOf<String, String>()
        val certPasswords = mutableMapOf<String, String>()

        override fun password(serverId: String) = passwords[serverId]
        override fun certificatePassword(serverId: String) = certPasswords[serverId]

        override fun store(serverId: String, password: String?, certificatePassword: String?) {
            if (password != null) passwords[serverId] = password else passwords.remove(serverId)
            if (certificatePassword != null) certPasswords[serverId] = certificatePassword
            else certPasswords.remove(serverId)
        }

        override fun prune(liveServerIds: Set<String>) {
            passwords.keys.retainAll(liveServerIds)
            certPasswords.keys.retainAll(liveServerIds)
        }
    }

    private fun server(
        id: String,
        password: String? = null,
        certPassword: String? = null,
    ) = TAKServer(
        id = id,
        name = "srv-$id",
        host = "tak.example.com",
        port = 8089,
        username = "operator",
        password = password,
        certificatePassword = certPassword,
    )

    @Test
    fun `stripSecrets blanks secret fields and writes them to the store`() {
        val creds = FakeCredentialStore()
        val list = listOf(server("a", password = "hunter2", certPassword = "p12pass"))

        val persisted = stripSecrets(list, creds)

        assertNull(persisted[0].password)
        assertNull(persisted[0].certificatePassword)
        assertEquals("hunter2", creds.passwords["a"])
        assertEquals("p12pass", creds.certPasswords["a"])
        // Non-secret fields untouched.
        assertEquals("operator", persisted[0].username)
        assertEquals("tak.example.com", persisted[0].host)
    }

    @Test
    fun `rehydrateSecrets restores what stripSecrets removed`() {
        val creds = FakeCredentialStore()
        val original = listOf(
            server("a", password = "hunter2", certPassword = "p12pass"),
            server("b", password = null, certPassword = "only-cert"),
            server("c"),
        )

        val roundTripped = rehydrateSecrets(stripSecrets(original, creds), creds)

        assertEquals(original, roundTripped)
    }

    @Test
    fun `migration path - legacy plaintext json rehydrates from itself then strips on save`() {
        val creds = FakeCredentialStore()
        // Pre-0.36 blob: secrets still inline, nothing in the encrypted store.
        val legacy = listOf(server("a", password = "legacy-pw", certPassword = "legacy-cert"))

        // Read before migration: rehydrate falls back to the JSON's values.
        val read = rehydrateSecrets(legacy, creds)
        assertEquals("legacy-pw", read[0].password)
        assertEquals("legacy-cert", read[0].certificatePassword)

        // Migration = a save: secrets move into the store, JSON goes blank.
        val persisted = stripSecrets(read, creds)
        assertNull(persisted[0].password)
        assertNull(persisted[0].certificatePassword)
        assertEquals("legacy-pw", creds.passwords["a"])

        // Post-migration read of the blanked JSON restores the secrets.
        val reread = rehydrateSecrets(persisted, creds)
        assertEquals("legacy-pw", reread[0].password)
        assertEquals("legacy-cert", reread[0].certificatePassword)
    }

    @Test
    fun `deleting a server prunes its credentials`() {
        val creds = FakeCredentialStore()
        stripSecrets(
            listOf(
                server("a", password = "pw-a"),
                server("b", password = "pw-b"),
            ),
            creds,
        )
        assertTrue(creds.passwords.containsKey("a"))

        // Server "a" deleted; next save carries only "b".
        stripSecrets(listOf(server("b", password = "pw-b")), creds)

        assertFalse(creds.passwords.containsKey("a"))
        assertEquals("pw-b", creds.passwords["b"])
    }

    @Test
    fun `clearing a password removes the stored entry`() {
        val creds = FakeCredentialStore()
        stripSecrets(listOf(server("a", password = "old")), creds)
        assertEquals("old", creds.passwords["a"])

        stripSecrets(listOf(server("a", password = null)), creds)

        assertNull(creds.passwords["a"])
        assertNull(rehydrateSecrets(listOf(server("a")), creds)[0].password)
    }

    @Test
    fun `unavailable store falls back to legacy plaintext behavior`() {
        val creds = FakeCredentialStore(available = false)
        val list = listOf(server("a", password = "hunter2", certPassword = "p12pass"))

        // Identity in both directions — credentials must not be dropped.
        assertSame(list, stripSecrets(list, creds))
        assertSame(list, rehydrateSecrets(list, creds))
        assertTrue(creds.passwords.isEmpty())
    }
}
