package soy.engindearing.omnitak.mobile.data.gyb

import org.json.JSONObject
import soy.engindearing.omnitak.mobile.data.remoteid.OpenDroneIdMessage

/**
 * Parses the newline-delimited JSON the gyb_detect ESP32 streams over BLE GATT
 * into the SAME [OpenDroneIdMessage] shapes the on-device FAA Remote ID scanner
 * produces. Feeding gyb detections through the existing [RemoteIdTrackStore]
 * means a drone seen over WiFi by the gyb AND over BLE by the phone collapses
 * to one `RID-{uasId}` contact — automatic dedup.
 *
 * The gyb is the WiFi-beacon Remote ID path the phone physically can't do
 * (Android can't put WiFi into monitor mode for arbitrary beacons). The phone
 * keeps catching the Bluetooth Remote ID subset directly; the gyb fills the
 * WiFi gap and ships it over Bluetooth so the gyb's WiFi radio stays free.
 *
 * Wire contract: ghostnet-fw/src/gatt_link.h + main.cpp send_json(), and
 * tools/mock_gyb_peripheral.py. Keep the field set in lockstep.
 */
sealed class GybMessage {
    /** Sent once when a phone connects — make/model/serial/capabilities. */
    data class DeviceInfo(val model: String, val serialNumber: String, val version: String) : GybMessage()

    /** Sent every 5 s. [level] is 0…1. */
    data class Battery(val level: Double) : GybMessage()

    /** One drone detection, decoded to BasicID (+ Location when GPS is valid). */
    data class Drone(
        val uasId: String,
        val messages: List<OpenDroneIdMessage>,
        val recvMethod: Int,
    ) : GybMessage()
}

object GybDetectionParser {

    /** Parse one JSON line off the GATT stream. Returns null for malformed /
     *  unrecognised lines — consumers fail soft, never crash. */
    fun parse(line: String): GybMessage? {
        val obj = runCatching { JSONObject(line) }.getOrNull() ?: return null

        // Battery — distinct by its batteryLevel key.
        if (obj.has("batteryLevel")) {
            return GybMessage.Battery(obj.optDouble("batteryLevel", 0.0))
        }

        // Drone — distinct by uasId.
        if (obj.has("uasId")) {
            return parseDrone(obj)
        }

        // Device info — make/model handshake (no uasId, no batteryLevel).
        if (obj.has("model") || obj.has("manufacturer")) {
            return GybMessage.DeviceInfo(
                model = obj.optString("model", "gyb_detect"),
                serialNumber = obj.optString("serialNumber", ""),
                version = obj.optString("version", ""),
            )
        }

        return null
    }

    private fun parseDrone(obj: JSONObject): GybMessage? {
        val mac = obj.optString("uasId", "")
        val serial = obj.optString("serialNumber", "")
        val recvMethod = obj.optInt("recvMethod", 0)

        // Key by the drone serial when present so a WiFi (gyb) and BLE (phone)
        // sighting of the same aircraft share one `RID-` UID; fall back to the
        // MAC the firmware puts in uasId otherwise.
        val key = if (serial.isNotEmpty()) serial else mac
        if (key.isEmpty()) return null

        val uaType = OpenDroneIdMessage.UaType.fromCode(obj.optInt("uasType", 0))
        val idType = if (serial.isEmpty()) {
            OpenDroneIdMessage.IdType.NONE
        } else {
            OpenDroneIdMessage.IdType.SERIAL_NUMBER
        }

        val messages = mutableListOf<OpenDroneIdMessage>(
            OpenDroneIdMessage.BasicId(
                protocolVersion = 0,
                idType = idType,
                uaType = uaType,
                uasId = key,
            )
        )

        // Firmware omits uasLat/uasLon entirely when GPS is invalid.
        val lat = obj.optDoubleOrNull("uasLat")
        val lon = obj.optDoubleOrNull("uasLon")
        if (lat != null && lon != null && !(lat == 0.0 && lon == 0.0) && !lat.isNaN() && !lon.isNaN()) {
            messages.add(
                OpenDroneIdMessage.Location(
                    protocolVersion = 0,
                    operationalStatus = OpenDroneIdMessage.OperationalStatus.fromCode(obj.optInt("opStatus", 0)),
                    heightType = OpenDroneIdMessage.HeightType.ABOVE_GROUND_LEVEL,
                    trackDirectionDeg = obj.optInt("uasHeading", 0),
                    groundSpeedMs = obj.optDouble("uasHSpeed", 0.0),
                    verticalSpeedMs = obj.optDouble("uasVSpeed", 0.0),
                    latitude = lat,
                    longitude = lon,
                    pressureAltitudeM = obj.optDoubleOrNull("uasBaroPressure"),
                    geodeticAltitudeM = obj.optDoubleOrNull("uasHae"),
                    heightAboveTakeoffM = obj.optDoubleOrNull("uasHag"),
                    horizontalAccuracyM = obj.optDoubleOrNull("uasHorizontalError"),
                    verticalAccuracyM = obj.optDoubleOrNull("uasVerticalError"),
                    timestampSec = null,
                )
            )
        }

        // Operator (pilot) location from the RID System message. The firmware
        // sends opLat/opLon when known, often before the drone has a GPS fix.
        val opLat = obj.optDoubleOrNull("opLat")
        val opLon = obj.optDoubleOrNull("opLon")
        if (opLat != null && opLon != null && !(opLat == 0.0 && opLon == 0.0) && !opLat.isNaN() && !opLon.isNaN()) {
            messages.add(
                OpenDroneIdMessage.OperatorLocation(
                    protocolVersion = 0,
                    latitude = opLat,
                    longitude = opLon,
                    altitudeM = obj.optDoubleOrNull("opAlt"),
                )
            )
        }

        return GybMessage.Drone(uasId = key, messages = messages, recvMethod = recvMethod)
    }

    /** Human-readable detection method for remarks. */
    fun recvMethodLabel(code: Int): String = when (code) {
        1 -> "WiFi beacon"
        2 -> "WiFi NAN"
        16 -> "Bluetooth"
        else -> "unknown"
    }

    /** Firmware sends some numbers as strings; coerce leniently, null if absent. */
    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return when (val v = opt(key)) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
    }
}
