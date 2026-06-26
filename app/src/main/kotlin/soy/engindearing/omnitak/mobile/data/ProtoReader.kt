package soy.engindearing.omnitak.mobile.data

/**
 * Minimal clean-room protobuf reader — the read-side companion to
 * [MeshWire]'s write helpers. Mirrors the `readTag` / `readVarint` /
 * `readLengthDelimited` / `readString` / `skip` helpers hand-rolled in the
 * iOS `MeshtasticChannelCodec` and `TAKPacketV2Codec`, so the two platforms
 * decode byte-identical wire bytes the same way.
 *
 * No protobuf-javalite, no GPL proto: field numbers and wire types are an
 * interface, not copyrightable expression.
 */
class ProtoReader(private val data: ByteArray) {

    /** 0-based read offset into [data]. */
    var idx: Int = 0
        private set

    data class WireTag(val field: Int, val wire: Int)

    fun hasMore(): Boolean = idx < data.size

    fun readTag(): WireTag? {
        val v = readVarint() ?: return null
        return WireTag(field = (v shr 3).toInt(), wire = (v and 0x07UL).toInt())
    }

    fun readVarint(): ULong? {
        var result = 0UL
        var shift = 0
        while (idx < data.size) {
            val byte = data[idx].toInt() and 0xFF
            idx += 1
            result = result or ((byte.toULong() and 0x7FUL) shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
            if (shift >= 64) return null
        }
        return null
    }

    /** fixed32: 4-byte little-endian unsigned. */
    fun readFixed32(): UInt? {
        if (idx + 4 > data.size) return null
        var v = 0u
        for (i in 0 until 4) {
            v = v or ((data[idx + i].toUInt() and 0xFFu) shl (8 * i))
        }
        idx += 4
        return v
    }

    fun readLengthDelimited(): ByteArray? {
        val len = readVarint() ?: return null
        val end = idx + len.toInt()
        if (len.toInt() < 0 || end > data.size || end < idx) return null
        val slice = data.copyOfRange(idx, end)
        idx = end
        return slice
    }

    fun readString(): String? = readLengthDelimited()?.toString(Charsets.UTF_8)

    /** Advance past a field whose value we don't consume, by wire type. */
    fun skip(wire: Int): Boolean {
        return when (wire) {
            0 -> readVarint() != null
            1 -> { if (idx + 8 > data.size) false else { idx += 8; true } }
            2 -> {
                val len = readVarint() ?: return false
                val end = idx + len.toInt()
                if (len.toInt() < 0 || end > data.size || end < idx) false else { idx = end; true }
            }
            5 -> { if (idx + 4 > data.size) false else { idx += 4; true } }
            else -> false
        }
    }
}
