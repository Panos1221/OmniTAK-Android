package soy.engindearing.omnitak.mobile.ui.components

import org.json.JSONArray
import org.json.JSONObject
import soy.engindearing.omnitak.mobile.data.CoTAffiliation
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.symbology.MilStdIconService

/**
 * Build the entity JSON payload `window.OmniBridge.setEntities(...)` consumes.
 * Includes a MIL-STD-2525 `sidc` for each contact so cesium_scene.html's
 * `_billboard()` takes the milsymbol branch and renders the proper symbol
 * (multirotor / fixed-wing UAS / infantry / etc.) instead of falling back
 * to a generic affiliation frame. Self stays sidc-less so it keeps the
 * friendly-affiliation frame circle.
 */
internal fun buildCesiumEntitiesJson(
    contacts: List<CoTEvent>,
    selfLat: Double?,
    selfLon: Double?,
    selfCallsign: String,
): String {
    val arr = JSONArray()
    if (selfLat != null && selfLon != null && !selfLat.isNaN() && !selfLon.isNaN()) {
        arr.put(
            JSONObject().apply {
                put("uid", "__self__")
                put("lat", selfLat)
                put("lon", selfLon)
                put("callsign", selfCallsign)
                put("affiliation", "f")
                put("kind", "self")
            },
        )
    }
    for (c in contacts) {
        if (c.lat.isNaN() || c.lon.isNaN()) continue
        arr.put(
            JSONObject().apply {
                put("uid", c.uid)
                put("lat", c.lat)
                put("lon", c.lon)
                if (c.hae != 0.0) put("hae", c.hae)
                c.callsign?.let { put("callsign", it) }
                put("affiliation", affChar(c.affiliation))
                put("kind", "contact")
                put("sidc", MilStdIconService.getSidc(c.type))
            },
        )
    }
    return arr.toString()
}

private fun affChar(a: CoTAffiliation): String = when (a) {
    CoTAffiliation.FRIEND -> "f"
    CoTAffiliation.HOSTILE -> "h"
    CoTAffiliation.NEUTRAL -> "n"
    else -> "u"
}
