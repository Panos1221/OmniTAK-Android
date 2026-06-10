package soy.engindearing.omnitak.mobile.data

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Single home for the CoT XML wire primitives every send path shares:
 * the `<event>` envelope, XML text escaping, and the two ISO-8601
 * timestamp shapes TAK actually speaks on the wire.
 *
 * Before this object existed the codebase had six hand-rolled envelope
 * builders, eight private XML-escape copies (4-vs-5 entity drift) and
 * seven SimpleDateFormat timestamp formatters. Envelope-level fixes had
 * to be re-applied in every copy — now they land here once.
 *
 * Formatters are java.time [DateTimeFormatter]s: thread-safe, no
 * synchronization, no per-call allocation (unlike SimpleDateFormat).
 */
object CotXml {

    /** Whole-second UTC — `2026-06-09T18:01:02Z`. */
    private val ISO_SECONDS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    /** Millisecond UTC — `2026-06-09T18:01:02.345Z`. */
    private val ISO_MILLIS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** ISO-8601 UTC at whole-second precision. */
    fun isoSeconds(epochMs: Long = System.currentTimeMillis()): String =
        ISO_SECONDS.format(Instant.ofEpochMilli(epochMs))

    /** ISO-8601 UTC at millisecond precision. */
    fun isoMillis(epochMs: Long = System.currentTimeMillis()): String =
        ISO_MILLIS.format(Instant.ofEpochMilli(epochMs))

    /** Escape all five XML predefined entities. */
    fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /** Inverse of [escape] — `&amp;` last so it can't double-unescape. */
    fun unescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    /**
     * Build a complete CoT `<event>` document.
     *
     * @param detailXml the full detail block INCLUDING its
     *   `<detail>…</detail>` wrapper (or empty for no detail).
     * @param timeIso null/empty omits `time`/`start` entirely (mesh
     *   replays without a clock); `staleIso` falls back to [timeIso].
     */
    fun buildEvent(
        uid: String,
        type: String,
        how: String,
        lat: Double,
        lon: Double,
        hae: Double = 0.0,
        ce: Double = 9_999_999.0,
        le: Double = 9_999_999.0,
        timeIso: String? = null,
        startIso: String? = timeIso,
        staleIso: String? = timeIso,
        detailXml: String = "",
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<event version=\"2.0\" uid=\"").append(escape(uid))
        append("\" type=\"").append(escape(type)).append('"')
        if (!timeIso.isNullOrEmpty()) {
            append(" time=\"").append(timeIso)
            append("\" start=\"").append(startIso ?: timeIso).append('"')
        }
        val stale = staleIso ?: timeIso
        if (!stale.isNullOrEmpty()) {
            append(" stale=\"").append(stale).append('"')
        }
        append(" how=\"").append(escape(how)).append("\">")
        append("<point lat=\"").append(lat)
        append("\" lon=\"").append(lon)
        append("\" hae=\"").append(hae)
        append("\" ce=\"").append(ce)
        append("\" le=\"").append(le).append("\"/>")
        append(detailXml)
        append("</event>")
    }
}
