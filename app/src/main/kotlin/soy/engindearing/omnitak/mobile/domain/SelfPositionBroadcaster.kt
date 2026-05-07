package soy.engindearing.omnitak.mobile.domain

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.data.UserPrefs
import soy.engindearing.omnitak.mobile.data.UserPrefsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Periodic Position Location Information (PPLI) broadcaster. Mirrors the
 * iOS `PositionBroadcastService`: emits a self-SA CoT event every
 * [intervalMs] for as long as [start] has been called, until [stop].
 *
 * Lifecycle is owned by the caller (ServerManager): start when the
 * connection becomes Connected, stop on Disconnected. The first broadcast
 * fires immediately so TAK servers see a `Set client for subscription`
 * almost as soon as the TLS handshake completes — matching ATAK behavior.
 *
 * Self-UID is generated once and persisted via [UserPrefsStore] so the
 * server treats this device as a stable contact across restarts. The
 * `ANDROID-` prefix triggers the correct ATAK icon set.
 */
class SelfPositionBroadcaster(
    private val scope: CoroutineScope,
    private val prefsStore: UserPrefsStore,
    private val sendCoT: suspend (String) -> Boolean,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val staleSeconds: Long = DEFAULT_STALE_SECONDS,
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // Ensure we have a stable UID before broadcasting. First boot
            // generates one and writes it back so future runs reuse it.
            val prefs = ensureSelfUid()
            Log.i(TAG, "Starting PPLI broadcast — uid=${prefs.selfUid} callsign=${prefs.callsign}")
            broadcastOnce(prefs)
            while (isActive) {
                delay(intervalMs)
                if (!isActive) break
                val latest = currentPrefs()
                broadcastOnce(latest)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun ensureSelfUid(): UserPrefs {
        val current = currentPrefs()
        if (current.selfUid.isNotBlank()) return current
        val generated = "ANDROID-${UUID.randomUUID()}"
        prefsStore.update { it.copy(selfUid = generated) }
        return current.copy(selfUid = generated)
    }

    private suspend fun currentPrefs(): UserPrefs = prefsStore.prefs.first()

    private suspend fun broadcastOnce(prefs: UserPrefs) {
        val xml = buildSelfCoT(
            uid = prefs.selfUid.ifBlank { "ANDROID-fallback" },
            callsign = prefs.callsign,
            team = prefs.team,
            lat = prefs.selfLat,
            lon = prefs.selfLon,
            staleSeconds = staleSeconds,
        )
        val ok = sendCoT(xml)
        if (ok) {
            Log.d(TAG, "PPLI sent — ${prefs.callsign} @ ${prefs.selfLat},${prefs.selfLon}")
        } else {
            Log.w(TAG, "PPLI send failed (no socket?)")
        }
    }

    companion object {
        private const val TAG = "SelfPositionBroadcaster"
        const val DEFAULT_INTERVAL_MS: Long = 30_000L
        const val DEFAULT_STALE_SECONDS: Long = 180L

        /**
         * Build a self-SA CoT event matching the iOS `generateSelfSACoT`
         * format closely enough for TAK Server 5.7 to assign a callsign
         * and federate to other clients.
         */
        fun buildSelfCoT(
            uid: String,
            callsign: String,
            team: String,
            lat: Double,
            lon: Double,
            staleSeconds: Long,
        ): String {
            val now = System.currentTimeMillis()
            val time = isoUtc(now)
            val stale = isoUtc(now + staleSeconds * 1000L)
            val safeCallsign = escapeXml(callsign)
            val safeTeam = escapeXml(team.replaceFirstChar { it.uppercase() })
            return buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                append("<event version=\"2.0\"")
                append(" uid=\"").append(escapeXml(uid)).append('"')
                append(" type=\"a-f-G-U-C\"")
                append(" time=\"").append(time).append('"')
                append(" start=\"").append(time).append('"')
                append(" stale=\"").append(stale).append('"')
                append(" how=\"m-g\">")
                append("<point lat=\"").append(lat).append('"')
                append(" lon=\"").append(lon).append('"')
                append(" hae=\"0.0\" ce=\"9999999.0\" le=\"9999999.0\"/>")
                append("<detail>")
                append("<contact callsign=\"").append(safeCallsign).append("\" endpoint=\"*:-1:stcp\"/>")
                append("<__group name=\"").append(safeTeam).append("\" role=\"Team Member\"/>")
                append("<status battery=\"100\"/>")
                append("<takv device=\"AVD\" platform=\"OmniTAK-Android\" os=\"Android\" version=\"0.1\"/>")
                append("<track speed=\"0.00\" course=\"0.00\"/>")
                append("<precisionlocation altsrc=\"GPS\" geopointsrc=\"GPS\"/>")
                append("<uid Droid=\"").append(safeCallsign).append("\"/>")
                append("<usericon iconsetpath=\"COT_MAPPING_2525B/a-f/a-f-G-U-C\"/>")
                append("</detail>")
                append("</event>")
            }
        }

        private val ISO_FMT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        private fun isoUtc(epochMs: Long): String = synchronized(ISO_FMT) {
            ISO_FMT.format(Date(epochMs))
        }

        private fun escapeXml(s: String): String = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
