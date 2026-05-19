package soy.engindearing.omnitak.mobile.data.airspace

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * One UAS Facility Map grid cell — a polygon with a maximum flight
 * altitude (FAA-published, in feet AGL, for LAANC clearance).
 *
 *  - 0 ft = no-fly without prior authorization (typical inside an
 *    airport's Class B/C/D inner ring).
 *  - 50 / 100 / 200 / 300 / 400 ft = LAANC ceiling — operator can fly
 *    below this number per Part 107 without filing.
 *
 * [polygons] is a list of rings (lat, lon). Most grids are single-ring
 * rectangles, but the FeatureServer also returns multi-polygon features.
 */
data class FaaUasFmGrid(
    val ceilingFeet: Int,
    val unit: String?,         // "FT" almost always
    val airportName: String?,  // e.g. "Spokane Intl (GEG)"
    val polygons: List<List<Pair<Double, Double>>>, // outer ring(s); [lat, lon]
)

/**
 * Pulls UAS Facility Map polygons from the FAA's public ArcGIS
 * FeatureServer (FAA_UAS_FacilityMap_Data_V5). The data drives LAANC
 * altitude ceilings for the entire US; we render it as a soft overlay
 * so the operator can see at a glance whether the airspace they're
 * about to fly into is unrestricted (green), capped (yellow), or
 * no-fly (red).
 *
 * Authoritative source — same data the FAA's own B4UFLY app + every
 * commercial LAANC provider uses. Refreshed monthly by the FAA on the
 * 56-day chart cycle.
 *
 * One bbox fetch returns up to [MAX_RESULTS] polygons; that's enough
 * for ~100 km square around most metro areas, well under the actual
 * FeatureServer cap.
 */
class FaaUasFmClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val httpTimeoutMs: Int = 8_000,
) {
    private val cache = mutableMapOf<String, List<FaaUasFmGrid>>()
    private val fetchLock = Mutex()

    /**
     * Fetch grids whose polygons intersect the given lat/lon bbox.
     * [southLat] / [northLat] / [westLon] / [eastLon] in WGS84 degrees.
     * Cached per bbox key; same call within the session returns
     * instantly.
     */
    suspend fun fetch(
        southLat: Double, northLat: Double,
        westLon: Double, eastLon: Double,
    ): List<FaaUasFmGrid> = withContext(Dispatchers.IO) {
        val key = "%.3f_%.3f_%.3f_%.3f".format(southLat, northLat, westLon, eastLon)
        cache[key]?.let { return@withContext it }
        fetchLock.withLock {
            cache[key]?.let { return@withLock it }
            val grids = runCatching { fetchUncached(southLat, northLat, westLon, eastLon) }
                .getOrElse {
                    Log.w(TAG, "FAA UASFM fetch failed: ${it.message}")
                    emptyList()
                }
            cache[key] = grids
            grids
        }
    }

    private fun fetchUncached(
        southLat: Double, northLat: Double,
        westLon: Double, eastLon: Double,
    ): List<FaaUasFmGrid> {
        // GeoJSON output is the simplest format to parse — ArcGIS REST
        // also supports `f=json` (Esri JSON) but its polygon structure
        // is nested differently.
        val geometry = """{"xmin":$westLon,"ymin":$southLat,"xmax":$eastLon,"ymax":$northLat,"spatialReference":{"wkid":4326}}"""
        val params = listOf(
            "where=1=1",
            "outFields=CEILING,UNIT,APT1_NAME,APT1_FAAID",
            "geometry=${java.net.URLEncoder.encode(geometry, "UTF-8")}",
            "geometryType=esriGeometryEnvelope",
            "inSR=4326",
            "outSR=4326",
            "f=geojson",
            "resultRecordCount=$MAX_RESULTS",
        ).joinToString("&")
        val url = URL("$baseUrl/0/query?$params")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = httpTimeoutMs
            readTimeout = httpTimeoutMs
            requestMethod = "GET"
            setRequestProperty("User-Agent", "OmniTAK/0.21")
        }
        try {
            if (conn.responseCode != 200) {
                Log.w(TAG, "FAA UASFM HTTP ${conn.responseCode}")
                return emptyList()
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return parseGeoJson(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseGeoJson(body: String): List<FaaUasFmGrid> {
        val root = JSONObject(body)
        val features = root.optJSONArray("features") ?: return emptyList()
        val out = mutableListOf<FaaUasFmGrid>()
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val props = f.optJSONObject("properties") ?: continue
            val geom = f.optJSONObject("geometry") ?: continue
            val type = geom.optString("type")
            val coords = geom.optJSONArray("coordinates") ?: continue
            val ringsList = mutableListOf<List<Pair<Double, Double>>>()
            when (type) {
                "Polygon" -> {
                    val outerRing = coords.optJSONArray(0) ?: continue
                    val ring = mutableListOf<Pair<Double, Double>>()
                    for (j in 0 until outerRing.length()) {
                        val p = outerRing.optJSONArray(j) ?: continue
                        // GeoJSON is [lon, lat]
                        ring.add(p.optDouble(1) to p.optDouble(0))
                    }
                    if (ring.size >= 3) ringsList.add(ring)
                }
                "MultiPolygon" -> {
                    for (k in 0 until coords.length()) {
                        val poly = coords.optJSONArray(k) ?: continue
                        val outerRing = poly.optJSONArray(0) ?: continue
                        val ring = mutableListOf<Pair<Double, Double>>()
                        for (j in 0 until outerRing.length()) {
                            val p = outerRing.optJSONArray(j) ?: continue
                            ring.add(p.optDouble(1) to p.optDouble(0))
                        }
                        if (ring.size >= 3) ringsList.add(ring)
                    }
                }
                else -> continue
            }
            if (ringsList.isEmpty()) continue
            out.add(
                FaaUasFmGrid(
                    ceilingFeet = props.optInt("CEILING", 400),
                    unit = props.optString("UNIT", "FT"),
                    airportName = props.optString("APT1_NAME").takeIf { it.isNotBlank() }
                        ?.let { name ->
                            val faaId = props.optString("APT1_FAAID").takeIf { it.isNotBlank() }
                            if (faaId != null) "$name ($faaId)" else name
                        },
                    polygons = ringsList,
                )
            )
        }
        return out
    }

    companion object {
        private const val TAG = "FaaUasFmClient"
        const val DEFAULT_BASE_URL =
            "https://services6.arcgis.com/ssFJjBXIUyZDrSYZ/arcgis/rest/services/FAA_UAS_FacilityMap_Data_V5/FeatureServer"
        private const val MAX_RESULTS = 2000
    }
}
