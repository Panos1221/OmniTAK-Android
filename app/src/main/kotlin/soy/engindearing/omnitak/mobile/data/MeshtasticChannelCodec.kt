package soy.engindearing.omnitak.mobile.data

import java.io.ByteArrayOutputStream

/**
 * Clean-room encoder/decoder for the Meshtastic "channel set" share URL —
 * the `https://meshtastic.org/e/#<base64url>` link / QR that the stock
 * Meshtastic app uses to share a channel (name + PSK) with other devices.
 *
 * Kotlin port of iOS `MeshtasticChannelCodec.swift`; byte-identical on the
 * wire. Protobuf field numbers and the URL layout are an interface (not
 * copyrightable); no GPL proto or SDK code is copied. Same approach as the
 * existing v1 TAKPacket codec / [AtakPluginSerializer].
 *
 * Wire format:
 *   URL  = "https://meshtastic.org/e/#" + base64url(ChannelSet protobuf), no '='
 *   ChannelSet { repeated ChannelSettings settings = 1; LoRaConfig lora_config = 2 }
 *   ChannelSettings { bytes psk = 2; string name = 3; fixed32 id = 4;
 *                     bool uplink_enabled = 5; bool downlink_enabled = 6 }
 */

/** One Meshtastic channel as carried in a share URL. */
data class MeshChannel(
    val name: String,
    /**
     * Pre-shared key. 0 bytes = no crypto; 1 byte = default-key shorthand
     * (n selects the well-known key + n); 16/32 bytes = AES128/256.
     */
    val psk: ByteArray,
    val uplinkEnabled: Boolean = false,
    val downlinkEnabled: Boolean = false,
) {
    // ByteArray needs structural equals/hashCode for Equatable parity.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeshChannel) return false
        return name == other.name &&
            psk.contentEquals(other.psk) &&
            uplinkEnabled == other.uplinkEnabled &&
            downlinkEnabled == other.downlinkEnabled
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + psk.contentHashCode()
        result = 31 * result + uplinkEnabled.hashCode()
        result = 31 * result + downlinkEnabled.hashCode()
        return result
    }
}

object MeshtasticChannelCodec {

    const val URL_PREFIX = "https://meshtastic.org/e/#"

    // region Encode -------------------------------------------------------

    /**
     * Build the shareable channel-set URL for one or more channels.
     * [loraConfig] is optional opaque LoRaConfig protobuf bytes (passed
     * through verbatim when present so the preset/region travel with the
     * share).
     */
    fun encodeURL(channels: List<MeshChannel>, loraConfig: ByteArray? = null): String {
        val set = ByteArrayOutputStream()
        for (ch in channels) {
            MeshWire.appendLenField(set, field = 1, bytes = encodeSettings(ch))
        }
        if (loraConfig != null && loraConfig.isNotEmpty()) {
            MeshWire.appendLenField(set, field = 2, bytes = loraConfig)
        }
        return URL_PREFIX + base64url(set.toByteArray())
    }

    private fun encodeSettings(ch: MeshChannel): ByteArray {
        val s = ByteArrayOutputStream()
        if (ch.psk.isNotEmpty()) {
            MeshWire.appendLenField(s, field = 2, bytes = ch.psk)
        }
        if (ch.name.isNotEmpty()) {
            MeshWire.appendLenField(s, field = 3, bytes = ch.name.toByteArray(Charsets.UTF_8))
        }
        if (ch.uplinkEnabled) MeshWire.appendVarintField(s, field = 5, value = 1UL)
        if (ch.downlinkEnabled) MeshWire.appendVarintField(s, field = 6, value = 1UL)
        return s.toByteArray()
    }

    // region Decode -------------------------------------------------------

    /**
     * Parse a channel-set URL (or the bare base64url fragment) into channels.
     * Returns null if the input isn't a recognizable channel-set link.
     */
    fun decodeURL(input: String): List<MeshChannel>? {
        val frag: String
        val hashIdx = input.indexOf('#')
        if (hashIdx >= 0) {
            frag = input.substring(hashIdx + 1)
        } else if (!input.contains("/")) {
            frag = input // bare fragment
        } else {
            return null
        }
        if (frag.isEmpty()) return null
        val data = base64urlDecode(frag) ?: return null

        val channels = ArrayList<MeshChannel>()
        val r = ProtoReader(data)
        while (r.hasMore()) {
            val tag = r.readTag() ?: return channels.ifEmpty { null }
            when (tag.field to tag.wire) {
                1 to 2 -> {
                    val body = r.readLengthDelimited()
                    if (body != null) decodeSettings(body)?.let { channels.add(it) }
                }
                else -> if (!r.skip(tag.wire)) return channels.ifEmpty { null }
            }
        }
        return channels.ifEmpty { null }
    }

    private fun decodeSettings(data: ByteArray): MeshChannel? {
        val r = ProtoReader(data)
        var psk = ByteArray(0)
        var name = ""
        var up = false
        var down = false
        while (r.hasMore()) {
            val tag = r.readTag() ?: break
            when (tag.field to tag.wire) {
                2 to 2 -> psk = r.readLengthDelimited() ?: psk
                3 to 2 -> name = r.readString() ?: name
                5 to 0 -> up = (r.readVarint() ?: 0UL) != 0UL
                6 to 0 -> down = (r.readVarint() ?: 0UL) != 0UL
                else -> if (!r.skip(tag.wire)) return null
            }
        }
        return MeshChannel(name = name, psk = psk, uplinkEnabled = up, downlinkEnabled = down)
    }

    // region base64url ----------------------------------------------------

    fun base64url(data: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(data)
            .replace('+', '-')
            .replace('/', '_')
            .replace("=", "")

    fun base64urlDecode(s: String): ByteArray? {
        var b64 = s.replace('-', '+').replace('_', '/')
        val pad = (4 - b64.length % 4) % 4
        b64 += "=".repeat(pad)
        return try {
            java.util.Base64.getDecoder().decode(b64)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
