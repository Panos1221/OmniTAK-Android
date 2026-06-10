package soy.engindearing.omnitak.mobile.data.remoteid

/**
 * Parsed OpenDroneID / ASTM F3411-22a messages.
 *
 * Phase 2 (initial scope) only decodes [BasicId] and [Location]
 * because together they're all we need to plot a drone track on the
 * map. [Self] / [System] / [OperatorId] / [Authentication] frames
 * carry useful metadata (operator location, description string,
 * trust signatures) but aren't required for the first integration —
 * they can be added later without breaking the public shape.
 *
 * ## Spec references
 * - ASTM F3411-22a (the official spec)
 * - FAA Part 89 (the regulatory wrapper for the US)
 * - `opendroneid/receiver-android` (open-source Apache-2.0 reference
 *   implementation, useful for cross-checking byte layouts)
 *
 * ## Byte layout
 * Each Remote ID frame is 25 bytes after the 1-byte header (4 bits
 * message type + 4 bits protocol version). Fields are little-endian
 * unless noted.
 */
sealed class OpenDroneIdMessage {
    /** ASTM message type code (4 bits, 0x0–0xF). */
    abstract val messageType: Int

    /** Protocol version (4 bits, typically 0x1 or 0x2). */
    abstract val protocolVersion: Int

    /**
     * Basic ID — carries the UAS identifier (serial number, FAA
     * registration, UTM-issued UUID, or session ID) used to dedupe
     * frames into a single track.
     */
    data class BasicId(
        override val protocolVersion: Int,
        val idType: IdType,
        val uaType: UaType,
        val uasId: String,
    ) : OpenDroneIdMessage() {
        override val messageType: Int get() = TYPE_BASIC_ID
    }

    /**
     * Location/Vector — the field that lets us plot a track.
     * Latitude/longitude are signed 32-bit integers in degrees × 1e7.
     */
    data class Location(
        override val protocolVersion: Int,
        val operationalStatus: OperationalStatus,
        val heightType: HeightType,
        val trackDirectionDeg: Int,
        val groundSpeedMs: Double,
        val verticalSpeedMs: Double,
        val latitude: Double,
        val longitude: Double,
        val pressureAltitudeM: Double?,
        val geodeticAltitudeM: Double?,
        val heightAboveTakeoffM: Double?,
        val horizontalAccuracyM: Double?,
        val verticalAccuracyM: Double?,
        val timestampSec: Int?,
    ) : OpenDroneIdMessage() {
        override val messageType: Int get() = TYPE_LOCATION

        /** True when both lat and lon decoded to non-zero, non-NaN values. */
        val hasValidPosition: Boolean
            get() = !latitude.isNaN() && !longitude.isNaN() &&
                    (latitude != 0.0 || longitude != 0.0)
    }

    /**
     * Operator (pilot) location, from the RID System message. Often
     * available before the drone itself reports a GPS fix, and is the
     * tactically valuable point in a C-UAS picture (where the pilot is).
     */
    data class OperatorLocation(
        override val protocolVersion: Int,
        val latitude: Double,
        val longitude: Double,
        val altitudeM: Double?,
    ) : OpenDroneIdMessage() {
        override val messageType: Int get() = 4 // ODID System message

        val hasValidPosition: Boolean
            get() = !latitude.isNaN() && !longitude.isNaN() &&
                    (latitude != 0.0 || longitude != 0.0)
    }

    /**
     * Unparsed/unknown message — kept so the scanner can count
     * non-fatal frames it skipped, useful for diagnostics in the
     * field.
     */
    data class Unknown(
        override val messageType: Int,
        override val protocolVersion: Int,
    ) : OpenDroneIdMessage()

    enum class IdType(val code: Int) {
        NONE(0),
        /** Manufacturer-assigned serial. */
        SERIAL_NUMBER(1),
        /** FAA-issued operator registration. */
        REGISTRATION(2),
        /** UTM-assigned UUID (newer FAA Remote ID path). */
        UTM_ASSIGNED_UUID(3),
        /** Per-flight session ID. */
        SESSION_ID(4),
        RESERVED(5);

        companion object {
            fun fromCode(code: Int): IdType =
                values().firstOrNull { it.code == code } ?: RESERVED
        }
    }

    enum class UaType(val code: Int) {
        UNDECLARED(0),
        AEROPLANE(1),
        /** Multirotor — the DJI Mavic / consumer drone class. */
        HELICOPTER_OR_MULTIROTOR(2),
        GYROPLANE(3),
        HYBRID_LIFT(4),
        ORNITHOPTER(5),
        GLIDER(6),
        KITE(7),
        FREE_BALLOON(8),
        CAPTIVE_BALLOON(9),
        AIRSHIP(10),
        FREE_FALL(11),
        ROCKET(12),
        TETHERED_POWERED_AIRCRAFT(13),
        GROUND_OBSTACLE(14),
        OTHER(15);

        companion object {
            fun fromCode(code: Int): UaType =
                values().firstOrNull { it.code == code } ?: OTHER
        }
    }

    enum class OperationalStatus(val code: Int) {
        UNDECLARED(0),
        GROUND(1),
        AIRBORNE(2),
        EMERGENCY(3),
        REMOTE_ID_SYSTEM_FAILURE(4);

        companion object {
            fun fromCode(code: Int): OperationalStatus =
                values().firstOrNull { it.code == code } ?: UNDECLARED
        }
    }

    enum class HeightType(val code: Int) {
        /** Above takeoff point. */
        ABOVE_TAKEOFF(0),
        /** Above ground level. */
        ABOVE_GROUND_LEVEL(1);

        companion object {
            fun fromCode(code: Int): HeightType =
                if (code == 1) ABOVE_GROUND_LEVEL else ABOVE_TAKEOFF
        }
    }

    companion object {
        const val TYPE_BASIC_ID = 0x0
        const val TYPE_LOCATION = 0x1
        const val TYPE_AUTHENTICATION = 0x2
        const val TYPE_SELF_ID = 0x3
        const val TYPE_SYSTEM = 0x4
        const val TYPE_OPERATOR_ID = 0x5
        const val TYPE_MESSAGE_PACK = 0xF

        /** Service UUID (BT-SIG short form) for FAA Remote ID. */
        const val SERVICE_UUID_16 = 0xFFFA

        /** Each individual ASTM message is exactly 25 bytes after the 1-byte header. */
        const val MESSAGE_BODY_BYTES = 24

        /** 1-byte header + 24-byte body. */
        const val MESSAGE_TOTAL_BYTES = 25
    }
}
