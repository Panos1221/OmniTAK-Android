package soy.engindearing.omnitak.mobile.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Per-server secret storage, keyed by [TAKServer.id]. Backed by an
 * implementation so [TAKServerStore]'s split/rehydrate logic is unit-testable
 * on the JVM (the real implementation needs the Android Keystore).
 */
interface CredentialStore {
    /** False when the Keystore-backed store could not be created. */
    val available: Boolean

    fun password(serverId: String): String?
    fun certificatePassword(serverId: String): String?

    /** Null values delete the corresponding entry. */
    fun store(serverId: String, password: String?, certificatePassword: String?)

    /** Drop entries for servers no longer in [liveServerIds]. */
    fun prune(liveServerIds: Set<String>)
}

/**
 * Keystore-backed credential store for TAK server basic-auth passwords and
 * `.p12` passphrases ([EncryptedSharedPreferences], AES256-GCM values under a
 * hardware-backed master key where the device supports it).
 *
 * Before 0.36 these secrets lived as plaintext fields inside the server-list
 * JSON in Preferences DataStore — see [TAKServerStore], which now strips them
 * out of the JSON on save and rehydrates them from here on read, with a
 * transparent one-time migration for pre-existing plaintext blobs.
 *
 * The `.p12` key material itself stays as files in app-private storage
 * ([CertVault] under `filesDir/tak-certs/`) — not encrypted at the app layer,
 * but covered by Android FBE plus `allowBackup=false` / dataExtractionRules,
 * and now no longer accompanied by a recoverable plaintext passphrase.
 *
 * If the Keystore is unavailable (rare: corrupted keystore, broken vendor
 * HALs), [available] is false and TAKServerStore falls back to the legacy
 * plaintext-in-JSON behavior rather than losing credentials.
 */
class SecureCredentialStore(context: Context) : CredentialStore {

    private val prefs: SharedPreferences? = runCatching {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.onFailure {
        Log.e(TAG, "Keystore credential store unavailable — falling back to legacy storage", it)
    }.getOrNull()

    override val available: Boolean get() = prefs != null

    override fun password(serverId: String): String? =
        prefs?.getString(PW_PREFIX + serverId, null)

    override fun certificatePassword(serverId: String): String? =
        prefs?.getString(CERT_PW_PREFIX + serverId, null)

    override fun store(serverId: String, password: String?, certificatePassword: String?) {
        val p = prefs ?: return
        p.edit().apply {
            if (password != null) putString(PW_PREFIX + serverId, password)
            else remove(PW_PREFIX + serverId)
            if (certificatePassword != null) putString(CERT_PW_PREFIX + serverId, certificatePassword)
            else remove(CERT_PW_PREFIX + serverId)
        }.apply()
    }

    override fun prune(liveServerIds: Set<String>) {
        val p = prefs ?: return
        val stale = p.all.keys.filter { key ->
            val id = when {
                key.startsWith(CERT_PW_PREFIX) -> key.substring(CERT_PW_PREFIX.length)
                key.startsWith(PW_PREFIX) -> key.substring(PW_PREFIX.length)
                else -> return@filter false
            }
            id !in liveServerIds
        }
        if (stale.isEmpty()) return
        p.edit().apply { stale.forEach { remove(it) } }.apply()
    }

    private companion object {
        const val TAG = "SecureCredentialStore"
        const val FILE_NAME = "tak_credentials"
        const val PW_PREFIX = "pw."
        const val CERT_PW_PREFIX = "certpw."
    }
}
