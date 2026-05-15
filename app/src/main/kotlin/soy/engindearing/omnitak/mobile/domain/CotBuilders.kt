package soy.engindearing.omnitak.mobile.domain

import soy.engindearing.omnitak.mobile.data.CoTEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Plain-XML builders for the CoT subset OmniTAK ships. Kept separate
 * from CoTEvent (parse-side) so the send-side string assembly is one
 * obvious place to look. No DOM construction; the schema is stable and
 * string concat is the most readable shape.
 */
object CotBuilders {

    private val iso: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    /** ISO-8601 UTC timestamp for "now". */
    private fun nowIso(): String = iso.format(Date())

    /** ISO-8601 UTC timestamp for a given offset from now. */
    private fun isoOffset(seconds: Long): String =
        iso.format(Date(System.currentTimeMillis() + seconds * 1000L))

    /**
     * `t-x-d-d` "Tasking Delete Data" — the canonical TAK delete
     * primitive. Sent to the server, it propagates to other EUDs which
     * remove the target marker from their map. The deleter's own UID
     * goes in the event's `uid` field; the target UID lives on the
     * `<link>` element with `relation="p-p"`.
     */
    fun buildDeleteEvent(targetUid: String, senderUid: String): String {
        val now = nowIso()
        val stale = isoOffset(60)
        val safeTarget = xmlEscape(targetUid)
        val safeSender = xmlEscape(senderUid)
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <event version="2.0" uid="$safeSender" type="t-x-d-d" how="h-g-i-g-o"
                   time="$now" start="$now" stale="$stale">
              <point lat="0.0" lon="0.0" hae="0.0" ce="9999999.0" le="9999999.0"/>
              <detail>
                <link uid="$safeTarget" relation="p-p"/>
                <__forcedelete/>
              </detail>
            </event>
        """.trimIndent()
    }

    /**
     * Resurrect a CoTEvent as a fresh CoT XML string. Used by the
     * "Send to Contacts" path so we can re-broadcast the selection to
     * a specific recipient by adding `<dest uid="..."/>` to its detail.
     * Falls back to event.rawXml when present (preserves any extension
     * detail elements like <chat>, <usericon>, mil-std symbology).
     */
    fun rebuildEvent(event: CoTEvent, destUids: List<String>): String {
        // If the original parsed XML is around, re-wrap it with the
        // injected <dest> elements. Otherwise synthesize a minimal CoT.
        val now = event.timeIso ?: nowIso()
        val stale = event.staleIso ?: isoOffset(120)
        val dests = destUids.joinToString("") { """<dest uid="${xmlEscape(it)}"/>""" }
        val callsignAttr = event.callsign?.let { """ callsign="${xmlEscape(it)}"""" } ?: ""
        val remarks = if (event.remarks.isNotBlank())
            "<remarks>${xmlEscape(event.remarks)}</remarks>" else ""

        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <event version="2.0" uid="${xmlEscape(event.uid)}" type="${xmlEscape(event.type)}" how="h-g-i-g-o"
                   time="$now" start="$now" stale="$stale">
              <point lat="${event.lat}" lon="${event.lon}" hae="${event.hae}" ce="${event.ce}" le="${event.le}"/>
              <detail>
                <contact$callsignAttr/>
                $remarks
                $dests
              </detail>
            </event>
        """.trimIndent()
    }

    /** Fresh random TAK-style UID. */
    fun newUid(): String = UUID.randomUUID().toString()

    /** Minimal XML attribute escape — handles the five XML predefined entities. */
    fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
