package soy.engindearing.omnitak.mobile.data

/**
 * Cross-transport entry point for "scan a QR / paste a link to join a
 * channel". Detects whether a shared channel link is Meshtastic or MeshCore
 * and decodes it with the matching clean-room codec, so the settings screen
 * has one call regardless of which mesh the operator is on.
 *
 * Kotlin port of iOS `MeshChannelShare.swift`.
 */

/** Result of parsing a shared channel link. */
sealed class MeshChannelImport {
    data class Meshtastic(val channels: List<MeshChannel>) : MeshChannelImport()
    data class MeshCore(val channel: MeshCoreChannel) : MeshChannelImport()
}

/**
 * Which mesh transport a share link targets.
 *
 * Named [MeshShareTransport] rather than `MeshTransport` because the latter is
 * already the byte-transport interface in this package.
 */
enum class MeshShareTransport {
    MESHTASTIC,
    MESHCORE,
}

object MeshChannelShare {

    /**
     * Parse any supported channel-share link/QR payload.
     * MeshCore links are `meshcore://channel/add?...`; Meshtastic links are
     * `https://meshtastic.org/e/#...` (or a bare base64url fragment).
     */
    fun parse(input: String): MeshChannelImport? {
        val s = input.trim()
        // MeshCore first — its scheme is unambiguous.
        MeshCoreChannelCodec.decodeURL(s)?.let { return MeshChannelImport.MeshCore(it) }
        MeshtasticChannelCodec.decodeURL(s)?.let { mt ->
            if (mt.isNotEmpty()) return MeshChannelImport.Meshtastic(mt)
        }
        return null
    }

    /** Build a share link for the active transport. */
    fun shareURL(
        transport: MeshShareTransport,
        meshtastic: List<MeshChannel> = emptyList(),
        meshcore: MeshCoreChannel? = null,
    ): String? = when (transport) {
        MeshShareTransport.MESHTASTIC ->
            if (meshtastic.isEmpty()) null else MeshtasticChannelCodec.encodeURL(meshtastic)
        MeshShareTransport.MESHCORE ->
            meshcore?.let { MeshCoreChannelCodec.encodeURL(it) }
    }
}
