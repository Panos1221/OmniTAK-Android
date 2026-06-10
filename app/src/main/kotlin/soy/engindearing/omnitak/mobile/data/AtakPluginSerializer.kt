package soy.engindearing.omnitak.mobile.data

import java.io.ByteArrayOutputStream

/**
 * Serializes a [CoTEvent] into a Meshtastic portnum-72 (ATAK_PLUGIN)
 * payload. Inverse of [AtakPluginParser]. Mirrors
 * `ATAKPluginSerializer.swift` on iOS.
 *
 * Produces a TAK-protobuf TAKMessage with a CoTEvent submessage
 * populated from the OmniTAK [CoTEvent] model. Like the parser this
 * hand-rolls the wire format to avoid pulling in protobuf-javalite and
 * to stay consistent with the existing manual encoders in
 * [MeshtasticTcpClient] / [MeshtasticProtoParser].
 */
object AtakPluginSerializer {

    private const val PORTNUM_ATAK_PLUGIN: ULong = 72UL

    /** Serialize a [CoTEvent] into the bytes of a TAKMessage protobuf,
     *  suitable for use as the payload of a Meshtastic Data message at
     *  portnum 72. */
    fun serialize(
        event: CoTEvent,
        how: String = "m-g",
        sendTimeMs: Long = System.currentTimeMillis(),
        startTimeMs: Long? = null,
        staleTimeMs: Long? = null,
    ): ByteArray {
        val cotEvent = encodeCoTEvent(
            event = event,
            how = how,
            sendTimeMs = sendTimeMs,
            startTimeMs = startTimeMs ?: sendTimeMs,
            staleTimeMs = staleTimeMs ?: (sendTimeMs + 60_000L),
        )
        // Wrap in TAKMessage (field 2 = cotEvent, wire type 2).
        val out = ByteArrayOutputStream()
        MeshWire.appendLenField(out, field = 2, bytes = cotEvent)
        return out.toByteArray()
    }

    /** Build a Meshtastic ToRadio bytes blob with a MeshPacket carrying
     *  a portnum-72 payload. Caller is responsible for any TCP framing
     *  (see [MeshtasticTcpClient.sendBytes] / `sendFrame`). */
    fun buildToRadio(
        payloadBytes: ByteArray,
        to: UInt = MeshWire.BROADCAST_ADDR,
        channelIndex: UInt = 0u,
        packetId: UInt? = null,
        wantAck: Boolean = false,
    ): ByteArray = MeshWire.buildToRadio(
        portnum = PORTNUM_ATAK_PLUGIN,
        payload = payloadBytes,
        to = to,
        channelIndex = channelIndex,
        packetId = packetId,
        wantAck = wantAck,
    )

    // region CoTEvent encoder --------------------------------------------

    private fun encodeCoTEvent(
        event: CoTEvent,
        how: String,
        sendTimeMs: Long,
        startTimeMs: Long,
        staleTimeMs: Long,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // 1: type
        MeshWire.appendString(out, field = 1, value = event.type)
        // 5: uid
        MeshWire.appendString(out, field = 5, value = event.uid)
        // 6: sendTime (uint64 ms)
        MeshWire.appendVarintField(out, field = 6, value = millis(sendTimeMs))
        // 7: startTime
        MeshWire.appendVarintField(out, field = 7, value = millis(startTimeMs))
        // 8: staleTime
        MeshWire.appendVarintField(out, field = 8, value = millis(staleTimeMs))
        // 9: how
        MeshWire.appendString(out, field = 9, value = how)
        // 10..14: doubles via fixed64 bit pattern
        MeshWire.appendDouble(out, field = 10, value = event.lat)
        MeshWire.appendDouble(out, field = 11, value = event.lon)
        MeshWire.appendDouble(out, field = 12, value = event.hae)
        MeshWire.appendDouble(out, field = 13, value = event.ce)
        MeshWire.appendDouble(out, field = 14, value = event.le)
        // 15: detail
        val detailBytes = encodeDetail(event)
        if (detailBytes.isNotEmpty()) {
            MeshWire.appendLenField(out, field = 15, bytes = detailBytes)
        }
        return out.toByteArray()
    }

    private fun encodeDetail(event: CoTEvent): ByteArray {
        val out = ByteArrayOutputStream()
        // 6: contact (callsign)
        event.callsign?.takeIf { it.isNotEmpty() }?.let { cs ->
            val contact = ByteArrayOutputStream().apply {
                MeshWire.appendString(this, field = 1, value = cs)
            }.toByteArray()
            MeshWire.appendLenField(out, field = 6, bytes = contact)
        }
        // 2: group (team name + role) — required so receiving ATAK/OmniTAK
        // shows the operator's team color. Parser already reads group via
        // parseGroup (field 1=name, field 2=role). Step 4 of off-grid plan.
        event.teamName?.takeIf { it.isNotEmpty() }?.let { teamName ->
            val role = event.teamRole ?: "Team Member"
            val group = ByteArrayOutputStream().apply {
                MeshWire.appendString(this, field = 1, value = teamName)
                MeshWire.appendString(this, field = 2, value = role)
            }.toByteArray()
            MeshWire.appendLenField(out, field = 2, bytes = group)
        }
        // 1: xmlDetail — stash remarks here so parsers that ignore
        // structured submessages still see them.
        if (event.remarks.isNotEmpty()) {
            val xml = "<remarks>${escape(event.remarks)}</remarks>"
            MeshWire.appendString(out, field = 1, value = xml)
        }
        return out.toByteArray()
    }

    // endregion

    // region Wire helpers ------------------------------------------------

    private fun millis(ms: Long): ULong = if (ms < 0) 0uL else ms.toULong()

    private fun escape(s: String): String = CotXml.escape(s)

    // endregion
}
