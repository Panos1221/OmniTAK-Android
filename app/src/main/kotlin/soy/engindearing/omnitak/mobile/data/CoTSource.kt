package soy.engindearing.omnitak.mobile.data

import kotlinx.serialization.Serializable

/**
 * #180 — How an ingested CoT event arrived. Tagged at the ingest point so the
 * marker / contact detail sheet can show the operator which transport carried a
 * point, for debugging comms ("is this guy coming over the server or the mesh?").
 *
 * [transport] is the broad bucket; [detail] is the specific endpoint within it
 * (a server name for TAK, the mesh framework name for mesh). Kept as a small
 * serializable value so it can ride along on a persisted [CoTEvent] without a
 * schema migration — old persisted markers decode to [source] == null.
 */
@Serializable
data class CoTSource(
    val transport: Transport,
    /** Specific endpoint: the TAK server name, or the mesh framework name.
     *  Null when only the broad transport is known. */
    val detail: String? = null,
) {
    enum class Transport { TAK_SERVER, MESH, LOCAL, OTHER }

    companion object {
        /** A point that arrived over a TAK server (TCP/TLS over ethernet/Wi-Fi). */
        fun takServer(serverName: String?): CoTSource =
            CoTSource(Transport.TAK_SERVER, serverName?.takeIf { it.isNotBlank() })

        /** A point that arrived over a mesh radio (Meshtastic / MeshCore). */
        fun mesh(framework: String?): CoTSource =
            CoTSource(Transport.MESH, framework?.takeIf { it.isNotBlank() })

        /** An operator-dropped / device-local point that never traversed a link. */
        val LOCAL: CoTSource = CoTSource(Transport.LOCAL)
    }

    /**
     * Human label for the detail sheet, e.g. "TAK: HQ-Server", "Mesh: Meshtastic",
     * "Mesh: MeshCore", "Local". Pure — unit-tested in CoTSourceTest.
     */
    val label: String
        get() = when (transport) {
            Transport.TAK_SERVER -> if (detail != null) "TAK: $detail" else "TAK server"
            Transport.MESH -> if (detail != null) "Mesh: $detail" else "Mesh"
            Transport.LOCAL -> "Local"
            Transport.OTHER -> detail ?: "Other"
        }
}
