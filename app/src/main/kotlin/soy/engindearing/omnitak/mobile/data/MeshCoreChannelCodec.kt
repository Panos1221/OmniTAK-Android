package soy.engindearing.omnitak.mobile.data

import java.io.ByteArrayOutputStream

/**
 * Clean-room encoder/decoder for MeshCore channel share links:
 *     meshcore://channel/add?name=<name>&secret=<hex-16-bytes>
 *
 * MeshCore's analogue to the Meshtastic `meshtastic.org/e/#...` URL. Kotlin
 * port of iOS `MeshCoreChannelCodec.swift`.
 *
 * Format (MeshCore docs/qr_codes.md):
 *   - scheme/host/path: meshcore://channel/add
 *   - name:   channel name, URL-encoded
 *   - secret: lowercase hex of the 16-byte channel secret. Omitted/empty for
 *             the public channel (index 0, no secret).
 *
 * Independent clean-room implementation from the public URL spec.
 */

data class MeshCoreChannel(
    val name: String,
    /** 16-byte channel secret. Empty for the public channel (no crypto). */
    val secret: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeshCoreChannel) return false
        return name == other.name && secret.contentEquals(other.secret)
    }

    override fun hashCode(): Int = 31 * name.hashCode() + secret.contentHashCode()
}

object MeshCoreChannelCodec {

    const val SCHEME = "meshcore"
    const val CHANNEL_ADD_PATH = "channel/add"

    // region Encode -------------------------------------------------------

    fun encodeURL(ch: MeshCoreChannel): String {
        val sb = StringBuilder("$SCHEME://channel/add?name=")
        sb.append(queryEncode(ch.name))
        if (ch.secret.isNotEmpty()) {
            sb.append("&secret=").append(hex(ch.secret))
        }
        return sb.toString()
    }

    // region Decode -------------------------------------------------------

    /**
     * Parse a `meshcore://channel/add?...` link. Returns null for any other
     * scheme/host/path or a malformed secret.
     */
    fun decodeURL(input: String): MeshCoreChannel? {
        val trimmed = input.trim()

        // scheme
        val schemeSep = trimmed.indexOf("://")
        if (schemeSep < 0) return null
        val scheme = trimmed.substring(0, schemeSep).lowercase()
        if (scheme != SCHEME) return null

        // remainder: host/path?query
        var rest = trimmed.substring(schemeSep + 3)
        val query: String
        val qIdx = rest.indexOf('?')
        if (qIdx >= 0) {
            query = rest.substring(qIdx + 1)
            rest = rest.substring(0, qIdx)
        } else {
            query = ""
        }
        // Strip any fragment off the path portion.
        val hashIdx = rest.indexOf('#')
        if (hashIdx >= 0) rest = rest.substring(0, hashIdx)

        // host="channel", path="/add" (tolerate trailing slashes).
        val slashIdx = rest.indexOf('/')
        if (slashIdx < 0) return null
        val host = rest.substring(0, slashIdx).lowercase()
        val path = rest.substring(slashIdx).lowercase().trim('/')
        if (host != "channel" || path != "add") return null

        val items = parseQuery(query)
        val name = items["name"] ?: ""
        val secretHex = items["secret"] ?: ""

        val secret: ByteArray = when {
            secretHex.isEmpty() -> ByteArray(0)
            else -> dehex(secretHex) ?: return null // malformed secret
        }
        return MeshCoreChannel(name = name, secret = secret)
    }

    // region query helpers ------------------------------------------------

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq < 0) {
                out[queryDecode(pair)] = ""
            } else {
                val k = queryDecode(pair.substring(0, eq))
                val v = queryDecode(pair.substring(eq + 1))
                out[k] = v
            }
        }
        return out
    }

    /**
     * Percent-encode a query VALUE the way iOS `URLComponents` does: leaves
     * unreserved + a few sub-delims alone, encodes space as `%20`. Matches the
     * iOS output so encoded share links are byte-identical.
     */
    private fun queryEncode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            val safe = (ch in 'A'..'Z') || (ch in 'a'..'z') || (ch in '0'..'9') ||
                ch == '-' || ch == '.' || ch == '_' || ch == '~'
            if (safe) {
                sb.append(ch)
            } else {
                sb.append('%')
                sb.append("0123456789ABCDEF"[c ushr 4])
                sb.append("0123456789ABCDEF"[c and 0x0F])
            }
        }
        return sb.toString()
    }

    private fun queryDecode(s: String): String {
        if (s.indexOf('%') < 0 && s.indexOf('+') < 0) return s
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '%' && i + 2 < s.length -> {
                    val hi = Character.digit(s[i + 1], 16)
                    val lo = Character.digit(s[i + 2], 16)
                    if (hi >= 0 && lo >= 0) {
                        bytes.write((hi shl 4) or lo)
                        i += 3
                    } else {
                        bytes.write(c.code); i += 1
                    }
                }
                c == '+' -> { bytes.write(' '.code); i += 1 }
                else -> { bytes.write(c.code); i += 1 }
            }
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    // region hex helpers --------------------------------------------------

    fun hex(data: ByteArray): String {
        val sb = StringBuilder(data.size * 2)
        for (b in data) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    fun dehex(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        var i = 0
        var o = 0
        while (i < s.length) {
            val hi = Character.digit(s[i], 16)
            val lo = Character.digit(s[i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[o] = ((hi shl 4) or lo).toByte()
            i += 2
            o += 1
        }
        return out
    }
}
