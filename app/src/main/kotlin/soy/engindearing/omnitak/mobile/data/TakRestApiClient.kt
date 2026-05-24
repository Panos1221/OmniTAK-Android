package soy.engindearing.omnitak.mobile.data

import android.util.Base64
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager

/**
 * TAK Server Marti REST API client — mission + data-package sync. The Android
 * counterpart to iOS's TAKRestAPIClient, built on plain [HttpsURLConnection]
 * with mutual-TLS (same approach as [CSREnrollmentService]) so we add no new
 * HTTP dependency.
 *
 * One instance is bound to one [TAKServer]; [MissionSyncManager] spins up one
 * per enabled server and aggregates. mTLS uses the operator's imported `.p12`
 * (via [CertVault]); server trust is dev-mode trust-all, matching
 * [TAKConnection] until CA pinning lands.
 *
 * Dialect tolerance (verified against the 4-server matrix — TAK Server 5.7,
 * OpenTAKServer 1.7.x, taky 0.10):
 *  - reachability uses `/Marti/api/version/config` (the one endpoint all three
 *    return 200 for — `/Marti/api/version` 404s on OpenTAKServer).
 *  - list envelopes differ: TAK5.7/OTS wrap in `{data:[…]}`, taky's sync
 *    search returns `{resultCount,results:[…]}` — both handled, plus a bare
 *    top-level array.
 *  - taky has no `/Marti/api/missions` route; that fetch is tolerated empty.
 */
class TakRestApiClient(
    private val server: TAKServer,
    private val certVault: CertVault?,
) {
    /** TAK HTTPS API port. Distinct from the CoT streaming port in [server]. */
    private val apiPort = SECURE_API_PORT

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun baseUrl(path: String) = "https://${server.host}:$apiPort$path"

    // ------------------------------------------------------------------
    // Public API (each is a blocking network call — invoke off the main
    // thread; MissionSyncManager wraps these in Dispatchers.IO).
    // ------------------------------------------------------------------

    /**
     * Lightweight reachability + mTLS probe. Throws [ApiException] when the
     * host is unreachable, the cert is rejected, or the server errors —
     * otherwise returns normally (the body is ignored).
     */
    fun checkReachability() {
        val (code, body) = httpGet("/Marti/api/version/config")
        if (code !in 200..299) throw ApiException(httpReason(code, body))
    }

    /** Available missions. Empty on dialects without the endpoint (taky). */
    fun getMissions(): List<TakMissionInfo> {
        val (code, body) = httpGet("/Marti/api/missions")
        if (code !in 200..299) throw ApiException(httpReason(code, body))
        return parseMissions(body)
    }

    /** Available data packages via the sync search index. */
    fun getDataPackages(): List<TakDataPackageInfo> {
        val (code, body) = httpGet("/Marti/api/sync/search")
        if (code !in 200..299) throw ApiException(httpReason(code, body))
        return parseDataPackages(body)
    }

    // ------------------------------------------------------------------
    // JSON parsing — tolerant of the three envelope shapes.
    // ------------------------------------------------------------------

    internal fun parseMissions(body: String): List<TakMissionInfo> {
        val arr = listEnvelope(body) ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            TakMissionInfo(
                name = name,
                description = o.str("description"),
                creatorUid = o.str("creatorUid"),
                keywords = o.strList("keywords"),
                contentCount = (o["contents"] as? JsonArray)?.size ?: 0,
            )
        }
    }

    internal fun parseDataPackages(body: String): List<TakDataPackageInfo> {
        val arr = listEnvelope(body) ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val hash = o.str("hash") ?: o.str("Hash") ?: return@mapNotNull null
            TakDataPackageInfo(
                hash = hash,
                name = o.str("name") ?: o.str("Name") ?: hash,
                mimeType = o.str("mimeType") ?: o.str("MIMEType"),
                size = o.long("size") ?: o.long("Size") ?: 0L,
                submitter = o.str("submitter") ?: o.str("SubmissionUser"),
            )
        }
    }

    /**
     * Pull the list out of whichever envelope the dialect used:
     * `{data:[…]}` (TAK5.7/OTS), `{results:[…]}` (taky), or a bare array.
     */
    private fun listEnvelope(body: String): JsonArray? {
        val root: JsonElement = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        return when (root) {
            is JsonArray -> root
            is JsonObject -> (root["data"] ?: root["results"]) as? JsonArray
            else -> null
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull
            ?: (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun JsonObject.strList(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

    // ------------------------------------------------------------------
    // HTTP + mTLS (HttpsURLConnection, no extra deps).
    // ------------------------------------------------------------------

    private fun httpGet(path: String): Pair<Int, String> {
        val conn = (URL(baseUrl(path)).openConnection() as HttpsURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            // Some dialects (taky / OTS local-auth) accept basic auth in
            // addition to mTLS; send it when the operator provided creds.
            val user = server.username
            val pass = server.password
            if (!user.isNullOrBlank() && !pass.isNullOrBlank()) {
                val raw = "$user:$pass".toByteArray(Charsets.UTF_8)
                setRequestProperty("Authorization", "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP))
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(loadKeyManagers(), arrayOf(TrustAll), SecureRandom())
            sslSocketFactory = ctx.socketFactory
            hostnameVerifier = AcceptAllHostnames
        }
        return try {
            conn.connect()
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            code to text
        } catch (t: Throwable) {
            Log.w(TAG, "GET $path failed: ${t.javaClass.simpleName}: ${t.message}")
            throw ApiException(t.message ?: t.javaClass.simpleName, t)
        } finally {
            conn.disconnect()
        }
    }

    /** Client KeyManagers from the operator's imported `.p12`, or null. */
    private fun loadKeyManagers(): Array<KeyManager>? {
        val name = server.certificateName ?: return null
        val pass = server.certificatePassword ?: return null
        val bytes = certVault?.read(name) ?: return null
        return runCatching {
            val ks = KeyStore.getInstance("PKCS12")
            ByteArrayInputStream(bytes).use { ks.load(it, pass.toCharArray()) }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, pass.toCharArray())
            kmf.keyManagers
        }.onFailure {
            Log.w(TAG, "Cert load failed: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
    }

    private fun httpReason(code: Int, body: String): String = when (code) {
        401 -> "Authentication required"
        403 -> "Access forbidden"
        404 -> "Not found"
        else -> "Server error ($code)" + body.take(80).let { if (it.isBlank()) "" else ": $it" }
    }

    class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private object AcceptAllHostnames : javax.net.ssl.HostnameVerifier {
        override fun verify(hostname: String?, session: SSLSession?) = true
    }

    companion object {
        private const val TAG = "TakRestApiClient"
        const val SECURE_API_PORT = 8443
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
    }
}

// MARK: - Marti API models (minimal subset the sync UI needs)

data class TakMissionInfo(
    val name: String,
    val description: String? = null,
    val creatorUid: String? = null,
    val keywords: List<String> = emptyList(),
    val contentCount: Int = 0,
)

data class TakDataPackageInfo(
    val hash: String,
    val name: String,
    val mimeType: String? = null,
    val size: Long = 0L,
    val submitter: String? = null,
)
