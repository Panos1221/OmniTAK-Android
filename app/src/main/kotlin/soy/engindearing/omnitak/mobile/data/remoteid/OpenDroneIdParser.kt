package soy.engindearing.omnitak.mobile.data.remoteid

import soy.engindearing.omnitak.mobile.data.remoteid.OpenDroneIdMessage.HeightType
import soy.engindearing.omnitak.mobile.data.remoteid.OpenDroneIdMessage.IdType
import soy.engindearing.omnitak.mobile.data.remoteid.OpenDroneIdMessage.OperationalStatus
import soy.engindearing.omnitak.mobile.data.remoteid.OpenDroneIdMessage.UaType

/**
 * Decodes ASTM F3411-22a / OpenDroneID frames from raw Bluetooth
 * Remote ID service data.
 *
 * Pure functions — no Android dependencies, no hardware required.
 * Unit-testable against known byte vectors from the receiver-android
 * reference implementation.
 *
 * ## Inputs
 * - [parseMessage] takes the 25-byte message buffer (1 header byte
 *   + 24 body bytes) and returns a single decoded message.
 * - [parseMessagePack] takes the full pack buffer (3-byte pack
 *   header + N × 25 message bytes) and returns the list of decoded
 *   messages.
 * - [parseServiceData] takes the raw `service_data` field from a
 *   BLE scan record (after stripping the 16-bit service UUID) and
 *   returns the message(s) inside.
 *
 * ## What's decoded today
 * - **Basic ID** (type 0x0): UAS ID + UA Type — needed to dedupe
 *   frames into a single track on the map.
 * - **Location** (type 0x1): lat/lon/alt/heading/speed — the field
 *   that lets us actually plot the track.
 *
 * Other ASTM types (Authentication, Self ID, System, Operator ID)
 * return [OpenDroneIdMessage.Unknown] for now. They carry useful
 * metadata but aren't required for v1 — add later without breaking
 * the public shape.
 */
object OpenDroneIdParser {

    /**
     * Decode a single 25-byte ASTM message buffer. Returns null when
     * the buffer is the wrong length or unrecognisable; otherwise
     * returns a typed [OpenDroneIdMessage] (which may be [Unknown]
     * for not-yet-decoded message types).
     */
    fun parseMessage(buf: ByteArray, offset: Int = 0): OpenDroneIdMessage? {
        if (offset < 0 || buf.size - offset < OpenDroneIdMessage.MESSAGE_TOTAL_BYTES) return null
        val header = buf[offset].toInt() and 0xFF
        val messageType = (header shr 4) and 0xF
        val protocolVersion = header and 0xF

        return when (messageType) {
            OpenDroneIdMessage.TYPE_BASIC_ID -> parseBasicId(buf, offset + 1, protocolVersion)
            OpenDroneIdMessage.TYPE_LOCATION -> parseLocation(buf, offset + 1, protocolVersion)
            else -> OpenDroneIdMessage.Unknown(messageType, protocolVersion)
        }
    }

    /**
     * Decode a Message Pack frame — the BT5 extended-advertising
     * structure that bundles Basic ID + Location + System + … into a
     * single broadcast. Pack header is 3 bytes (type+version, then
     * single-message size, then count), followed by N × 25-byte
     * messages.
     */
    fun parseMessagePack(buf: ByteArray, offset: Int = 0): List<OpenDroneIdMessage> {
        if (offset < 0 || buf.size - offset < 3) return emptyList()
        val header = buf[offset].toInt() and 0xFF
        val messageType = (header shr 4) and 0xF
        if (messageType != OpenDroneIdMessage.TYPE_MESSAGE_PACK) return emptyList()

        val singleMessageSize = buf[offset + 1].toInt() and 0xFF
        val numMessages = buf[offset + 2].toInt() and 0xFF
        if (singleMessageSize != OpenDroneIdMessage.MESSAGE_TOTAL_BYTES) return emptyList()
        if (numMessages == 0) return emptyList()

        val expectedTotal = 3 + numMessages * singleMessageSize
        if (buf.size - offset < expectedTotal) return emptyList()

        val out = ArrayList<OpenDroneIdMessage>(numMessages)
        for (i in 0 until numMessages) {
            val msgOffset = offset + 3 + i * singleMessageSize
            parseMessage(buf, msgOffset)?.let { out.add(it) }
        }
        return out
    }

    /**
     * Decode the BLE `service_data` field as captured by
     * `ScanRecord.getServiceData(ParcelUuid(0xFFFA))`. The data
     * begins with a 1-byte application code (0x0D for OpenDroneID)
     * + 1-byte counter, then the message or message-pack body.
     */
    fun parseServiceData(serviceData: ByteArray): List<OpenDroneIdMessage> {
        if (serviceData.size < 3) return emptyList()
        // Byte 0: application code (0x0D for OpenDroneID — required).
        val appCode = serviceData[0].toInt() and 0xFF
        if (appCode != APP_CODE_OPENDRONEID) return emptyList()
        // Byte 1: message counter (per-message, increments per broadcast).
        // Skip — useful for replay detection but not needed to decode.
        val msgOffset = 2

        // Sniff the message type to decide between single-message and
        // message-pack decode paths.
        val header = serviceData[msgOffset].toInt() and 0xFF
        val messageType = (header shr 4) and 0xF
        return if (messageType == OpenDroneIdMessage.TYPE_MESSAGE_PACK) {
            parseMessagePack(serviceData, msgOffset)
        } else {
            parseMessage(serviceData, msgOffset)?.let { listOf(it) } ?: emptyList()
        }
    }

    // ---- Type-specific decoders -----------------------------------------

    private fun parseBasicId(buf: ByteArray, bodyOffset: Int, protocolVersion: Int): OpenDroneIdMessage.BasicId {
        val typeByte = buf[bodyOffset].toInt() and 0xFF
        val idType = IdType.fromCode((typeByte shr 4) and 0xF)
        val uaType = UaType.fromCode(typeByte and 0xF)

        // UAS ID — 20 ASCII bytes, null-padded.
        val idBytes = buf.copyOfRange(bodyOffset + 1, bodyOffset + 1 + UAS_ID_BYTES)
        val nullTermLen = idBytes.indexOfFirst { it == 0.toByte() }.let { if (it < 0) idBytes.size else it }
        val uasId = String(idBytes, 0, nullTermLen, Charsets.US_ASCII).trim()

        return OpenDroneIdMessage.BasicId(
            protocolVersion = protocolVersion,
            idType = idType,
            uaType = uaType,
            uasId = uasId,
        )
    }

    private fun parseLocation(buf: ByteArray, bodyOffset: Int, protocolVersion: Int): OpenDroneIdMessage.Location {
        val statusByte = buf[bodyOffset].toInt() and 0xFF
        val operationalStatus = OperationalStatus.fromCode((statusByte shr 4) and 0xF)
        val heightType = HeightType.fromCode((statusByte shr 2) and 0x1)
        val ewDirSegment = (statusByte shr 1) and 0x1
        val speedMult = statusByte and 0x1

        // Track direction: 0-179, with EW-segment bit indicating the 180-359 half.
        val trackByte = buf[bodyOffset + 1].toInt() and 0xFF
        val trackDirectionDeg = if (ewDirSegment == 1) trackByte + 180 else trackByte

        // Speed encoding (ASTM F3411 §5.4.4.4):
        //   SM=0 → 0.25 m/s per unit (0..63.75 m/s)
        //   SM=1 → 0.75 m/s per unit + 63.75 m/s offset (63.75..254.25 m/s)
        val speedByte = buf[bodyOffset + 2].toInt() and 0xFF
        val groundSpeedMs = if (speedMult == 0) {
            speedByte * 0.25
        } else {
            speedByte * 0.75 + 255.0 * 0.25
        }

        // Vertical speed: signed 8-bit, each unit = 0.5 m/s, range ±63.5 m/s.
        val verticalSpeedMs = buf[bodyOffset + 3].toInt() * 0.5

        // Latitude/Longitude: signed little-endian int32, degrees × 1e7.
        val latRaw = readInt32LE(buf, bodyOffset + 4)
        val lonRaw = readInt32LE(buf, bodyOffset + 8)
        val latitude = latRaw / 1e7
        val longitude = lonRaw / 1e7

        // Altitudes: unsigned 16-bit, each unit = 0.5 m, offset = -1000 m.
        val pressureAlt = decodeAltitude(readUInt16LE(buf, bodyOffset + 12))
        val geodeticAlt = decodeAltitude(readUInt16LE(buf, bodyOffset + 14))
        val heightAboveTakeoff = decodeAltitude(readUInt16LE(buf, bodyOffset + 16))

        // Accuracy nibbles (lookup in §5.4.4.10/.11). 0 = unknown.
        val accByte = buf[bodyOffset + 18].toInt() and 0xFF
        val horizontalAccuracyM = horizontalAccuracyTable[(accByte shr 4) and 0xF]
        val verticalAccuracyM = verticalAccuracyTable[accByte and 0xF]

        // Timestamp: 1/10 second since the UTC hour. Caller can resolve
        // to wall clock when needed.
        val timestampRaw = readUInt16LE(buf, bodyOffset + 21)
        val timestampSec = if (timestampRaw == 0xFFFF) null else timestampRaw / 10

        return OpenDroneIdMessage.Location(
            protocolVersion = protocolVersion,
            operationalStatus = operationalStatus,
            heightType = heightType,
            trackDirectionDeg = trackDirectionDeg,
            groundSpeedMs = groundSpeedMs,
            verticalSpeedMs = verticalSpeedMs,
            latitude = latitude,
            longitude = longitude,
            pressureAltitudeM = pressureAlt,
            geodeticAltitudeM = geodeticAlt,
            heightAboveTakeoffM = heightAboveTakeoff,
            horizontalAccuracyM = horizontalAccuracyM,
            verticalAccuracyM = verticalAccuracyM,
            timestampSec = timestampSec,
        )
    }

    // ---- Helpers --------------------------------------------------------

    private fun readInt32LE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)

    private fun readUInt16LE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8)

    private fun decodeAltitude(raw: Int): Double? =
        if (raw == 0) null else raw * 0.5 - 1000.0

    private const val APP_CODE_OPENDRONEID = 0x0D
    private const val UAS_ID_BYTES = 20

    /** ASTM F3411 §5.4.4.10 — Horizontal Accuracy in metres. */
    private val horizontalAccuracyTable: Array<Double?> = arrayOf(
        null,  // 0 = unknown
        18520.0, 7408.0, 3704.0, 1852.0, 926.0, 555.6, 185.2, 92.6, 30.0, 10.0, 3.0, 1.0,
        null, null, null,  // 13-15 reserved
    )

    /** ASTM F3411 §5.4.4.11 — Vertical Accuracy in metres. */
    private val verticalAccuracyTable: Array<Double?> = arrayOf(
        null, 150.0, 45.0, 25.0, 10.0, 3.0, 1.0,
        null, null, null, null, null, null, null, null, null,
    )
}
