package soy.engindearing.omnitak.mobile.data.onvif

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import soy.engindearing.omnitak.mobile.data.CotXml

/**
 * Minimal ONVIF PTZ + streaming client — enough to control a PTZ
 * ONVIF-compliant IP camera from OmniTAK and pull its RTSP feed.
 *
 * ONVIF is SOAP/XML over HTTP. We hit the device service, learn the
 * Media + PTZ service URLs, fetch a media profile + its RTSP stream URI,
 * and drive Pan/Tilt/Zoom via the PTZ ContinuousMove / Stop operations.
 *
 * Auth: WS-Security UsernameToken with PasswordDigest (the near-
 * universal ONVIF scheme) — digest = Base64(SHA1(nonce + created +
 * password)). We attach it to every request; cameras that allow
 * anonymous access ignore it.
 *
 * Scope: the operator-facing essentials — connect, get stream, joystick
 * PTZ, stop, presets. Not a full ONVIF implementation (no analytics,
 * imaging, events).
 *
 * NOTE: untested against live hardware yet — built to the ONVIF Core +
 * PTZ spec. Validate against a real camera before relying on it.
 */
class OnvifClient(
    private val host: String,
    private val port: Int = 80,
    private val username: String = "",
    private val password: String = "",
    private val httpTimeoutMs: Int = 6_000,
) {
    private val deviceServiceUrl = "http://$host:$port/onvif/device_service"
    private var mediaUrl: String = "http://$host:$port/onvif/media_service"
    private var ptzUrl: String = "http://$host:$port/onvif/ptz_service"

    data class Profile(val token: String, val name: String)

    /** Connection result: the RTSP stream URI + the PTZ profile token to
     *  use for movement commands. */
    data class Session(
        val rtspUri: String,
        val profileToken: String,
        val profileName: String,
        val hasPtz: Boolean,
    )

    /**
     * Connect: discover service URLs, fetch the first media profile, its
     * RTSP stream URI, and whether PTZ is available. Throws on hard
     * failure; caller surfaces the message.
     */
    suspend fun connect(): Session = withContext(Dispatchers.IO) {
        // 1. Service URLs (best-effort — fall back to conventional paths).
        runCatching { discoverServices() }
        // 2. Media profiles.
        val profiles = getProfiles()
        if (profiles.isEmpty()) error("Camera returned no media profiles")
        val profile = profiles.first()
        // 3. RTSP stream URI for that profile.
        val rtsp = getStreamUri(profile.token)
        // 4. PTZ availability — try a GetConfigurations; tolerate failure.
        val hasPtz = runCatching { ptzConfigured(profile.token) }.getOrDefault(false)
        Session(rtsp, profile.token, profile.name, hasPtz)
    }

    // -------------------------------------------------------- PTZ control

    /**
     * ContinuousMove — pan/tilt/zoom velocities in [-1.0, 1.0]. Call
     * [stop] when the operator releases the control. Standard PTZ
     * joystick pattern.
     */
    suspend fun continuousMove(profileToken: String, pan: Float, tilt: Float, zoom: Float) =
        withContext(Dispatchers.IO) {
            val body = """
                <tptz:ContinuousMove xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">
                  <tptz:ProfileToken>${esc(profileToken)}</tptz:ProfileToken>
                  <tptz:Velocity xmlns:tt="http://www.onvif.org/ver10/schema">
                    <tt:PanTilt x="$pan" y="$tilt"/>
                    <tt:Zoom x="$zoom"/>
                  </tptz:Velocity>
                </tptz:ContinuousMove>
            """.trimIndent()
            runCatching { soap(ptzUrl, "http://www.onvif.org/ver20/ptz/wsdl/ContinuousMove", body) }
                .onFailure { Log.w(TAG, "ContinuousMove failed: ${it.message}") }
            Unit
        }

    suspend fun stop(profileToken: String) = withContext(Dispatchers.IO) {
        val body = """
            <tptz:Stop xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">
              <tptz:ProfileToken>${esc(profileToken)}</tptz:ProfileToken>
              <tptz:PanTilt>true</tptz:PanTilt>
              <tptz:Zoom>true</tptz:Zoom>
            </tptz:Stop>
        """.trimIndent()
        runCatching { soap(ptzUrl, "http://www.onvif.org/ver20/ptz/wsdl/Stop", body) }
            .onFailure { Log.w(TAG, "Stop failed: ${it.message}") }
        Unit
    }

    suspend fun gotoPreset(profileToken: String, presetToken: String) = withContext(Dispatchers.IO) {
        val body = """
            <tptz:GotoPreset xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">
              <tptz:ProfileToken>${esc(profileToken)}</tptz:ProfileToken>
              <tptz:PresetToken>${esc(presetToken)}</tptz:PresetToken>
            </tptz:GotoPreset>
        """.trimIndent()
        runCatching { soap(ptzUrl, "http://www.onvif.org/ver20/ptz/wsdl/GotoPreset", body) }
            .onFailure { Log.w(TAG, "GotoPreset failed: ${it.message}") }
        Unit
    }

    // ------------------------------------------------------------ internal

    private fun discoverServices() {
        val body = """<tds:GetServices xmlns:tds="http://www.onvif.org/ver10/device/wsdl"><tds:IncludeCapability>false</tds:IncludeCapability></tds:GetServices>"""
        val resp = soap(deviceServiceUrl, "http://www.onvif.org/ver10/device/wsdl/GetServices", body)
        // Crude XML scrape — find <tds:XAddr> per namespace. Media2/Media
        // and PTZ namespaces both appear; take the first PTZ + media XAddr.
        Regex("""<[^>]*Namespace>([^<]*)</[^>]*Namespace>\s*<[^>]*XAddr>([^<]*)</[^>]*XAddr>""")
            .findAll(resp).forEach { mr ->
                val ns = mr.groupValues[1]; val addr = mr.groupValues[2]
                when {
                    ns.contains("/ptz/") -> ptzUrl = addr
                    ns.contains("/media") -> mediaUrl = addr
                }
            }
    }

    private fun getProfiles(): List<Profile> {
        val body = """<trt:GetProfiles xmlns:trt="http://www.onvif.org/ver10/media/wsdl"/>"""
        val resp = soap(mediaUrl, "http://www.onvif.org/ver10/media/wsdl/GetProfiles", body)
        // Profiles carry a `token="..."` attribute + a <tt:Name> child.
        return Regex("""<[^>]*Profiles[^>]*token="([^"]+)"[^>]*>.*?<[^>]*Name>([^<]*)</[^>]*Name>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(resp).map { Profile(it.groupValues[1], it.groupValues[2]) }.toList()
            .ifEmpty {
                // Some cameras put token without the Name in the same node.
                Regex("""<[^>]*Profiles[^>]*token="([^"]+)"""").findAll(resp)
                    .map { Profile(it.groupValues[1], it.groupValues[1]) }.toList()
            }
    }

    private fun getStreamUri(profileToken: String): String {
        // Media1 GetStreamUri with RTP-Unicast/RTSP.
        val body = """
            <trt:GetStreamUri xmlns:trt="http://www.onvif.org/ver10/media/wsdl">
              <trt:StreamSetup xmlns:tt="http://www.onvif.org/ver10/schema">
                <tt:Stream>RTP-Unicast</tt:Stream>
                <tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport>
              </trt:StreamSetup>
              <trt:ProfileToken>${esc(profileToken)}</trt:ProfileToken>
            </trt:GetStreamUri>
        """.trimIndent()
        val resp = soap(mediaUrl, "http://www.onvif.org/ver10/media/wsdl/GetStreamUri", body)
        val uri = Regex("""<[^>]*Uri>([^<]*)</[^>]*Uri>""").find(resp)?.groupValues?.get(1)
            ?: error("Camera did not return a stream URI")
        // Inject credentials into the RTSP URL so the player authenticates.
        return if (username.isNotBlank() && uri.startsWith("rtsp://"))
            uri.replaceFirst("rtsp://", "rtsp://$username:$password@")
        else uri
    }

    private fun ptzConfigured(profileToken: String): Boolean {
        val body = """<tptz:GetConfigurations xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl"/>"""
        val resp = runCatching { soap(ptzUrl, "http://www.onvif.org/ver20/ptz/wsdl/GetConfigurations", body) }
            .getOrNull() ?: return false
        return resp.contains("PTZConfiguration")
    }

    /** Issue a SOAP 1.2 request with a WS-Security UsernameToken header. */
    private fun soap(url: String, action: String, bodyXml: String): String {
        val envelope = """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">
  <s:Header>${securityHeader()}</s:Header>
  <s:Body>$bodyXml</s:Body>
</s:Envelope>"""
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = httpTimeoutMs
            readTimeout = httpTimeoutMs
            requestMethod = "POST"
            doOutput = true
            // SOAP 1.2 carries the action in the content-type, not SOAPAction.
            setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8; action=\"$action\"")
        }
        try {
            conn.outputStream.use { it.write(envelope.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw IOException("ONVIF HTTP $code: ${resp.take(200)}")
            }
            return resp
        } finally {
            conn.disconnect()
        }
    }

    /** WS-Security UsernameToken / PasswordDigest header. Empty when no
     *  credentials (anonymous cameras). */
    private fun securityHeader(): String {
        if (username.isBlank()) return ""
        val nonceBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonceB64 = Base64.encodeToString(nonceBytes, Base64.NO_WRAP)
        val created = isoNow()
        val sha = MessageDigest.getInstance("SHA-1").apply {
            update(nonceBytes)
            update(created.toByteArray())
            update(password.toByteArray())
        }.digest()
        val digest = Base64.encodeToString(sha, Base64.NO_WRAP)
        return """
            <wsse:Security xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
                           xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">
              <wsse:UsernameToken>
                <wsse:Username>${esc(username)}</wsse:Username>
                <wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digest</wsse:Password>
                <wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonceB64</wsse:Nonce>
                <wsu:Created>$created</wsu:Created>
              </wsse:UsernameToken>
            </wsse:Security>
        """.trimIndent()
    }

    private fun isoNow(): String = CotXml.isoSeconds()

    private fun esc(s: String) = CotXml.escape(s)

    companion object {
        private const val TAG = "OnvifClient"
    }
}
