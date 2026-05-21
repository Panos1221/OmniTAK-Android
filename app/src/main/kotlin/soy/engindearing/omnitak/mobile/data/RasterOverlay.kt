package soy.engindearing.omnitak.mobile.data

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Single-image georeferenced raster overlays (KMZ/KML GroundOverlay now;
 * GeoTIFF / GeoPDF to follow). Each is an image placed on the map by its
 * geographic corner box and rendered as a MapLibre ImageSource + RasterLayer
 * (the raster sibling of the KML vector overlay path).
 */
@Serializable
data class RasterOverlay(
    val id: String,
    val name: String,
    val fileName: String,
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
    val opacity: Float = 0.85f,
    val visible: Boolean = true,
    val createdAt: Long = 0L,
)

/** Extracts <GroundOverlay> records (image href + LatLonBox) from KML. */
object GroundOverlayParser {
    data class Item(var name: String = "GroundOverlay", var href: String = "", var north: Double = 0.0, var south: Double = 0.0, var east: Double = 0.0, var west: Double = 0.0)

    fun parse(kml: ByteArray): List<Item> {
        val items = ArrayList<Item>()
        var current: Item? = null
        var inGround = false; var inBox = false; var inIcon = false
        val text = StringBuilder()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(kml.inputStream(), null)
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    text.setLength(0)
                    when (parser.name) {
                        "GroundOverlay" -> { inGround = true; current = Item() }
                        "Icon" -> inIcon = true
                        "LatLonBox", "LatLonAltBox" -> inBox = true
                    }
                }
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val v = text.toString().trim()
                    when (parser.name) {
                        "GroundOverlay" -> { current?.let { items.add(it) }; current = null; inGround = false }
                        "Icon" -> inIcon = false
                        "LatLonBox", "LatLonAltBox" -> inBox = false
                        "name" -> if (inGround && !inBox && !inIcon && v.isNotEmpty()) current?.name = v
                        "href" -> if (inIcon) current?.href = v
                        "north" -> if (inBox) current?.north = v.toDoubleOrNull() ?: 0.0
                        "south" -> if (inBox) current?.south = v.toDoubleOrNull() ?: 0.0
                        "east" -> if (inBox) current?.east = v.toDoubleOrNull() ?: 0.0
                        "west" -> if (inBox) current?.west = v.toDoubleOrNull() ?: 0.0
                    }
                    text.setLength(0)
                }
            }
            ev = parser.next()
        }
        return items
    }
}

class RasterOverlayStore(context: Context) {
    private val dir = File(context.filesDir, "raster_overlays").apply { mkdirs() }
    private val metaFile = File(dir, "rasters.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _overlays = MutableStateFlow(load())
    val overlays: StateFlow<List<RasterOverlay>> = _overlays.asStateFlow()
    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun fileFor(o: RasterOverlay): File = File(dir, o.fileName)

    /** Import KMZ/KML GroundOverlay(s). Returns false if the file has no
     *  GroundOverlay (so the caller can fall back to a vector KML import). */
    suspend fun importGroundOverlay(source: File, displayName: String): Boolean {
        _isImporting.value = true; _lastError.value = null
        return try {
            val (kml, resources) = withContext(Dispatchers.IO) { unzipKmz(source, displayName) }
            val items = GroundOverlayParser.parse(kml)
            if (items.isEmpty()) { _isImporting.value = false; return false }
            var added = 0
            for (item in items) {
                val last = item.href.substringAfterLast('/')
                val imgBytes = resources[item.href] ?: resources[last]
                    ?: resources.entries.firstOrNull { it.key.endsWith(last) }?.value ?: continue
                val id = UUID.randomUUID().toString()
                val ext = last.substringAfterLast('.', "png").ifEmpty { "png" }
                val out = File(dir, "$id.$ext")
                withContext(Dispatchers.IO) { out.writeBytes(imgBytes) }
                _overlays.value = _overlays.value + RasterOverlay(
                    id = id, name = item.name, fileName = out.name,
                    north = item.north, south = item.south, east = item.east, west = item.west,
                    createdAt = System.currentTimeMillis(),
                )
                added++
            }
            if (added == 0) { _lastError.value = "No image found in that overlay." }
            persist()
            _isImporting.value = false
            added > 0
        } catch (e: Exception) {
            _lastError.value = "Import failed: ${e.message}"
            _isImporting.value = false
            false
        }
    }

    /** Returns (kmlBytes, resourceName→bytes). For plain KML, resources is empty. */
    private fun unzipKmz(source: File, displayName: String): Pair<ByteArray, Map<String, ByteArray>> {
        if (!displayName.lowercase().endsWith(".kmz")) return source.readBytes() to emptyMap()
        var kml: ByteArray = ByteArray(0)
        val res = HashMap<String, ByteArray>()
        ZipInputStream(source.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val bytes = zis.readBytes()
                    if (entry.name.lowercase().endsWith(".kml")) { if (kml.isEmpty()) kml = bytes } else res[entry.name] = bytes
                }
                entry = zis.nextEntry
            }
        }
        return kml to res
    }

    fun setVisible(id: String, visible: Boolean) = update(id) { it.copy(visible = visible) }
    fun setOpacity(id: String, value: Float) = update(id) { it.copy(opacity = value.coerceIn(0.05f, 1.0f)) }
    fun rename(id: String, name: String) { val t = name.trim(); if (t.isEmpty()) return; update(id) { it.copy(name = t) } }

    fun remove(id: String) {
        _overlays.value.firstOrNull { it.id == id }?.let { fileFor(it).delete() }
        _overlays.value = _overlays.value.filterNot { it.id == id }
        persist()
    }

    fun removeAll() {
        _overlays.value.forEach { fileFor(it).delete() }
        _overlays.value = emptyList(); persist()
    }

    private fun update(id: String, transform: (RasterOverlay) -> RasterOverlay) {
        _overlays.value = _overlays.value.map { if (it.id == id) transform(it) else it }
        persist()
    }

    private fun persist() { runCatching { metaFile.writeText(json.encodeToString(_overlays.value)) } }

    private fun load(): List<RasterOverlay> {
        if (!metaFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<RasterOverlay>>(metaFile.readText()).filter { File(dir, it.fileName).exists() }
        }.getOrDefault(emptyList())
    }
}
