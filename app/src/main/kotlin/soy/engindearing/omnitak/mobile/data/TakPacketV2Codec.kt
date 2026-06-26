package soy.engindearing.omnitak.mobile.data

import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Clean-room encoder/decoder for Meshtastic TAKPacketV2 (PortNum 78,
 * ATAK_PLUGIN_V2) carrying a tactical MARKER — the wire format the legacy
 * TAKPacket v1 (port 72, PLI + GeoChat only) cannot represent.
 *
 * Kotlin port of iOS `TAKPacketV2Codec.swift`; byte-identical on the wire.
 *
 * IMPORTANT — licensing: this is an independent, clean-room implementation.
 * The Meshtastic TAK SDKs and the `atak.proto` source file are GPL-3.0 (no
 * linking exception); OmniTAK is Apache-2.0. Protobuf field NUMBERS and the
 * wire layout are an interface, not copyrightable expression, so we hand-roll
 * the bytes here exactly as [AtakPluginSerializer] already hand-rolls v1 —
 * copying no GPL code or .proto text. We emit ONLY the 0xFF (uncompressed)
 * envelope, which stock ATAK / iTAK / Meshtastic >= 2.8.0 accept, so we
 * interoperate without the GPL-licensed zstd dictionary.
 *
 * Envelope on the wire (Data.payload for portnum 78):
 *     [1 byte flags = 0xFF][TAKPacketV2 protobuf body]
 *
 * TAKPacketV2 fields used (numbers are facts from the published wire spec):
 *     callsign       = 3   (string)
 *     latitude_i     = 6   (sfixed32, degrees * 1e7)
 *     longitude_i    = 7   (sfixed32, degrees * 1e7)
 *     altitude       = 8   (sint32, metres HAE, zigzag)
 *     uid            = 14  (string)   <- preserved verbatim
 *     stale_seconds  = 16  (uint32)
 *     cot_type_str   = 23  (string)   <- raw CoT type, e.g. "a-u-G"
 *     remarks        = 24  (string)
 *     marker         = 35  (Marker submessage, payload_variant oneof)
 * Marker submessage:
 *     kind           = 1   (enum)
 *     color_argb     = 3   (fixed32, signed ARGB as bit pattern)
 *     readiness      = 4   (bool)
 *     iconset        = 8   (string)
 */
object TakPacketV2Codec {

    /** Meshtastic PortNum for TAKPacketV2. */
    const val PORTNUM: ULong = 78UL

    /** Envelope flag byte signalling an uncompressed protobuf body. */
    const val FLAG_UNCOMPRESSED: Int = 0xFF

    /** Conservative wire budget; a Meshtastic Data payload caps at 233 bytes. */
    const val MAX_WIRE_BYTES: Int = 225

    /** Marker kind (clean-room enum mirroring the wire values). */
    enum class MarkerKind(val raw: ULong) {
        UNSPECIFIED(0UL),
        SPOT(1UL),
        WAYPOINT(2UL),
        CHECKPOINT(3UL),
        SELF_POSITION(4UL),
        SYMBOL_2525(5UL),
        SPOT_MAP(6UL),
        CUSTOM_ICON(7UL),
        GO_TO_POINT(8UL),
        INITIAL_POINT(9UL),
        CONTACT_POINT(10UL),
        OBSERVATION_POST(11UL),
        IMAGE_MARKER(12UL),
    }

    // region Encode -------------------------------------------------------

    /**
     * Encode a dropped-marker [CoTEvent] into a port-78 payload.
     *
     * Returns null if the resulting packet would exceed the LoRa wire budget
     * (the caller should fall back to a v1 GeoChat-degraded text line).
     */
    fun encodeMarker(event: CoTEvent, staleSeconds: UInt = 3600u): ByteArray? {
        val body = ByteArrayOutputStream()

        // field 3: callsign
        val callsign = event.callsign
        if (!callsign.isNullOrEmpty()) {
            MeshWire.appendLenField(body, field = 3, bytes = callsign.toByteArray(Charsets.UTF_8))
        }

        // field 6 / 7: latitude_i / longitude_i (sfixed32, 1e-7 deg)
        val latI = (event.lat * 1e7).roundToInt()
        val lonI = (event.lon * 1e7).roundToInt()
        MeshWire.appendTag(body, field = 6, wire = 5)
        MeshWire.appendSFixed32(body, latI)
        MeshWire.appendTag(body, field = 7, wire = 5)
        MeshWire.appendSFixed32(body, lonI)

        // field 8: altitude (sint32 HAE), only if meaningful
        val alt = event.hae.roundToInt()
        if (alt != 0) {
            MeshWire.appendVarintField(body, field = 8, value = zigzag32(alt))
        }

        // field 14: uid — preserved VERBATIM (the marker's identity across
        // mesh hops and the eventual server-reconnect dedup)
        MeshWire.appendLenField(body, field = 14, bytes = event.uid.toByteArray(Charsets.UTF_8))

        // field 16: stale_seconds
        if (staleSeconds > 0u) {
            MeshWire.appendVarintField(body, field = 16, value = staleSeconds.toULong())
        }

        // field 23: cot_type_str — the raw CoT type restores the marker faithfully
        MeshWire.appendLenField(body, field = 23, bytes = event.type.toByteArray(Charsets.UTF_8))

        // field 24: remarks (optional; first to be dropped under the size guard)
        if (event.remarks.isNotEmpty()) {
            MeshWire.appendLenField(body, field = 24, bytes = event.remarks.toByteArray(Charsets.UTF_8))
        }

        // field 35: marker submessage
        MeshWire.appendLenField(body, field = 35, bytes = encodeMarkerSubmessage(event))

        val bodyBytes = body.toByteArray()

        // Size guard: strip remarks and retry once if over budget.
        if (bodyBytes.size + 1 > MAX_WIRE_BYTES && event.remarks.isNotEmpty()) {
            val stripped = event.copy(remarks = "")
            return encodeMarker(stripped, staleSeconds = staleSeconds)
        }
        if (bodyBytes.size + 1 > MAX_WIRE_BYTES) return null

        // Prepend the uncompressed-envelope flag byte.
        val out = ByteArrayOutputStream()
        out.write(FLAG_UNCOMPRESSED)
        out.write(bodyBytes)
        return out.toByteArray()
    }

    private fun encodeMarkerSubmessage(event: CoTEvent): ByteArray {
        val marker = ByteArrayOutputStream()

        // field 1: kind — inferred from CoT type + iconset
        val kind = inferKind(type = event.type, iconset = event.iconsetPath)
        if (kind != MarkerKind.UNSPECIFIED) {
            MeshWire.appendVarintField(marker, field = 1, value = kind.raw)
        }

        // field 3: color_argb (fixed32, signed ARGB bit pattern)
        event.colorArgb?.let { argb ->
            MeshWire.appendTag(marker, field = 3, wire = 5)
            MeshWire.appendSFixed32(marker, argb)
        }

        // field 4: readiness (default true)
        MeshWire.appendVarintField(marker, field = 4, value = 1UL)

        // field 8: iconset path (drives the 2525 / FEMA / custom icon on receive)
        val iconset = event.iconsetPath
        if (!iconset.isNullOrEmpty()) {
            MeshWire.appendLenField(marker, field = 8, bytes = iconset.toByteArray(Charsets.UTF_8))
        }

        return marker.toByteArray()
    }

    private fun inferKind(type: String, iconset: String?): MarkerKind {
        if (iconset != null) {
            val icon = iconset.uppercase()
            if (icon.contains("2525")) return MarkerKind.SYMBOL_2525
            if (icon.contains("SPOTMAP")) return MarkerKind.SPOT_MAP
            if (icon.contains(".PNG")) return MarkerKind.CUSTOM_ICON
        }
        // CoT waypoint / spot families
        if (type.startsWith("b-m-p-w")) return MarkerKind.WAYPOINT
        if (type.startsWith("b-m-p-c")) return MarkerKind.CHECKPOINT
        if (type.startsWith("b-m-p-s")) return MarkerKind.SPOT
        return MarkerKind.SPOT
    }

    // region Decode -------------------------------------------------------

    /**
     * Decode a port-78 payload back into a marker [CoTEvent].
     *
     * Preserves the original uid verbatim and restores the real CoT type,
     * position, color and iconset, so the result re-enters the map-marker
     * pipeline (NOT the chat path) and dedups by uid.
     *
     * Returns null if the flag byte is not 0xFF (a dict-compressed body we
     * cannot decode without the GPL dictionary — caller should log + drop).
     */
    fun decode(data: ByteArray): CoTEvent? {
        if (data.isEmpty()) return null
        val flags = data[0].toInt() and 0xFF
        if (flags != FLAG_UNCOMPRESSED) return null // 0x00/0x01 = dict-compressed; unsupported by design

        // Read past the 1-byte flag envelope; protobuf body starts at byte 1.
        val reader = ProtoReader(data.copyOfRange(1, data.size))

        var callsign = ""
        var latI = 0
        var lonI = 0
        var alt = 0
        var uid = ""
        var cotType = ""
        var remarks: String? = null
        var markerBytes: ByteArray? = null

        while (reader.hasMore()) {
            val tag = reader.readTag() ?: break
            when (tag.field to tag.wire) {
                3 to 2 -> callsign = reader.readString() ?: callsign
                6 to 5 -> reader.readFixed32()?.let { latI = it.toInt() }
                7 to 5 -> reader.readFixed32()?.let { lonI = it.toInt() }
                8 to 0 -> reader.readVarint()?.let { alt = unzigzag32(it) }
                14 to 2 -> uid = reader.readString() ?: uid
                23 to 2 -> cotType = reader.readString() ?: cotType
                24 to 2 -> remarks = reader.readString()
                35 to 2 -> markerBytes = reader.readLengthDelimited()
                else -> if (!reader.skip(tag.wire)) return null
            }
        }

        if (uid.isEmpty()) return null

        var argb: Int? = null
        var iconset: String? = null
        markerBytes?.let {
            val m = decodeMarkerSubmessage(it)
            argb = m.first
            iconset = m.second
        }

        return CoTEvent(
            uid = uid,
            type = cotType.ifEmpty { "a-u-G" },
            lat = latI.toDouble() / 1e7,
            lon = lonI.toDouble() / 1e7,
            hae = alt.toDouble(),
            ce = 9999.0,
            le = 9999.0,
            callsign = callsign.ifEmpty { uid },
            remarks = remarks ?: "",
            iconsetPath = iconset,
            colorArgb = argb,
        )
    }

    private fun decodeMarkerSubmessage(data: ByteArray): Pair<Int?, String?> {
        val r = ProtoReader(data)
        var argb: Int? = null
        var iconset: String? = null
        while (r.hasMore()) {
            val tag = r.readTag() ?: break
            when (tag.field to tag.wire) {
                3 to 5 -> r.readFixed32()?.let { argb = it.toInt() }
                8 to 2 -> iconset = r.readString()
                else -> if (!r.skip(tag.wire)) return Pair(argb, iconset)
            }
        }
        return Pair(argb, iconset)
    }

    // region zigzag -------------------------------------------------------

    private fun zigzag32(v: Int): ULong {
        val z = (v shl 1) xor (v shr 31)
        return z.toUInt().toULong()
    }

    private fun unzigzag32(v: ULong): Int {
        val u = v.toUInt()
        return ((u shr 1) xor (0u - (u and 1u))).toInt()
    }
}
