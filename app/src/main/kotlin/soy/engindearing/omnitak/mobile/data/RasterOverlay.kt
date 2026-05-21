package soy.engindearing.omnitak.mobile.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mil.nga.tiff.TiffReader
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.Inflater
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp

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

/**
 * Reads the geo-registration of a GeoTIFF straight from the TIFF IFD — a
 * faithful port of the iOS GeoTIFFImporter.geoBounds so both platforms agree
 * to the bit. Supports the two common georef encodings (ModelPixelScale +
 * ModelTiepoint, or ModelTransformation) and EPSG 4326 / 3857. Returns null
 * when the file carries no usable georeferencing.
 */
object GeoTIFFParser {
    data class Box(val north: Double, val south: Double, val east: Double, val west: Double)

    fun bounds(b: ByteArray): Box? {
        if (b.size <= 8) return null
        val little = when {
            (b[0].toInt() and 0xFF) == 0x49 && (b[1].toInt() and 0xFF) == 0x49 -> true   // "II"
            (b[0].toInt() and 0xFF) == 0x4D && (b[1].toInt() and 0xFF) == 0x4D -> false  // "MM"
            else -> return null
        }

        fun u16(o: Int): Int {
            if (o + 1 >= b.size) return 0
            val b0 = b[o].toInt() and 0xFF; val b1 = b[o + 1].toInt() and 0xFF
            return if (little) b0 or (b1 shl 8) else (b0 shl 8) or b1
        }
        fun u32(o: Int): Int {
            if (o + 3 >= b.size) return 0
            val b0 = b[o].toInt() and 0xFF; val b1 = b[o + 1].toInt() and 0xFF
            val b2 = b[o + 2].toInt() and 0xFF; val b3 = b[o + 3].toInt() and 0xFF
            return if (little) b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            else (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }
        fun f64(o: Int): Double {
            if (o + 7 >= b.size) return 0.0
            var u = 0L
            for (i in 0 until 8) {
                val idx = o + (if (little) i else 7 - i)
                u = u or ((b[idx].toLong() and 0xFF) shl (8 * i))
            }
            return Double.fromBits(u)
        }

        if (u16(2) != 42) return null   // TIFF magic
        val ifd = u32(4)
        if (ifd + 2 > b.size) return null
        val entries = u16(ifd)

        var width = 0; var height = 0
        var pixelScale = DoubleArray(0); var tiepoint = DoubleArray(0); var transform = DoubleArray(0)
        var geoKeys = IntArray(0)
        val typeSizes = intArrayOf(0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8)

        for (e in 0 until entries) {
            val off = ifd + 2 + e * 12
            if (off + 12 > b.size) break
            val tag = u16(off); val type = u16(off + 2); val cnt = u32(off + 4)
            val tsize = if (type < typeSizes.size) typeSizes[type] else 0
            val len = tsize * cnt
            val valOff = if (len <= 4) off + 8 else u32(off + 8)
            when (tag) {
                256 -> width = if (type == 3) u16(off + 8) else u32(off + 8)
                257 -> height = if (type == 3) u16(off + 8) else u32(off + 8)
                33550 -> pixelScale = DoubleArray(cnt) { f64(valOff + it * 8) }
                33922 -> tiepoint = DoubleArray(cnt) { f64(valOff + it * 8) }
                34264 -> transform = DoubleArray(cnt) { f64(valOff + it * 8) }
                34735 -> geoKeys = IntArray(cnt) { u16(valOff + it * 2) }
            }
        }
        if (width <= 0 || height <= 0) return null

        // CRS: GeoKeyDirectory = header(4) then 4-short entries (key, loc, count, value).
        var epsg = 4326
        if (geoKeys.size >= 4) {
            var i = 4
            repeat(geoKeys[3]) {
                if (i + 3 < geoKeys.size) {
                    val key = geoKeys[i]; val loc = geoKeys[i + 1]; val value = geoKeys[i + 3]
                    if (loc == 0) {
                        if (key == 3072) epsg = value                       // ProjectedCSTypeGeoKey
                        else if (key == 2048 && epsg == 4326) epsg = value  // GeographicTypeGeoKey
                    }
                }
                i += 4
            }
        }

        // Pixel→world origin + scale.
        var originX = 0.0; var originY = 0.0; var sx = 0.0; var sy = 0.0
        if (pixelScale.size >= 2 && tiepoint.size >= 6) {
            sx = pixelScale[0]; sy = pixelScale[1]
            originX = tiepoint[3] - tiepoint[0] * sx
            originY = tiepoint[4] + tiepoint[1] * sy
        } else if (transform.size >= 16) {
            originX = transform[3]; originY = transform[7]
            sx = transform[0]; sy = -transform[5]
        } else {
            return null
        }

        val wX = originX; val eX = originX + width * sx
        val nY = originY; val sY = originY - height * sy

        fun lonLat(x: Double, y: Double): Pair<Double, Double> {
            if (epsg == 3857 || epsg == 900913 || epsg == 102100) {
                val lon = x / 6378137.0 * 180.0 / Math.PI
                val lat = (2.0 * atan(exp(y / 6378137.0)) - Math.PI / 2.0) * 180.0 / Math.PI
                return lon to lat
            }
            return x to y // assume degrees (EPSG:4326)
        }
        val (west, north) = lonLat(wX, nY)
        val (east, south) = lonLat(eX, sY)
        if (abs(north) > 90 || abs(south) > 90 || abs(east) > 180 || abs(west) > 180 ||
            north <= south || east == west
        ) return null
        return Box(north, south, east, west)
    }
}

/**
 * Reads the geo-registration of a GeoPDF (ISO 32000 geospatial). Android has
 * no PDF object-model API, so we parse the /VP → /Measure → /GPTS array (a
 * flat list of lat/lon corner pairs) straight out of the PDF bytes — both the
 * raw bytes and any FlateDecode object streams, since PDF 1.5+ tucks these
 * dictionaries inside compressed object streams. Mirrors the iOS
 * GeoPDFImporter.geoBounds (first viewport, min/max of GPTS → corner box).
 */
object GeoPDFParser {
    data class Box(val north: Double, val south: Double, val east: Double, val west: Double)

    private val NUM = Regex("[-+]?[0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?")
    private const val MAX_STREAM = 32 * 1024 * 1024
    private const val MAX_TOTAL = 64 * 1024 * 1024

    fun bounds(bytes: ByteArray): Box? {
        // Try the raw bytes first (uncompressed GeoPDFs), then inflated streams.
        extractGpts(String(bytes, Charsets.ISO_8859_1))?.let { boxFrom(it)?.let { b -> return b } }
        var total = 0
        for (text in inflatedStreams(bytes)) {
            total += text.length
            extractGpts(text)?.let { boxFrom(it)?.let { b -> return b } }
            if (total > MAX_TOTAL) break
        }
        return null
    }

    private fun boxFrom(gpts: List<Double>): Box? {
        if (gpts.size < 4 || gpts.size % 2 != 0) return null
        val lats = ArrayList<Double>(); val lons = ArrayList<Double>()
        var i = 0
        while (i + 1 < gpts.size) { lats.add(gpts[i]); lons.add(gpts[i + 1]); i += 2 } // GPTS = lat, lon
        val north = lats.maxOrNull() ?: return null
        val south = lats.minOrNull() ?: return null
        val east = lons.maxOrNull() ?: return null
        val west = lons.minOrNull() ?: return null
        if (abs(north) > 90 || abs(south) > 90 || abs(east) > 180 || abs(west) > 180 ||
            north <= south || east == west
        ) return null
        return Box(north, south, east, west)
    }

    /** First `/GPTS [ ... ]` numeric array in the text, or null. */
    private fun extractGpts(text: String): List<Double>? {
        var idx = text.indexOf("/GPTS")
        while (idx >= 0) {
            var j = idx + 5
            while (j < text.length && text[j].isWhitespace()) j++
            if (j < text.length && text[j] == '[') {
                val end = text.indexOf(']', j)
                if (end > j) {
                    val nums = NUM.findAll(text.substring(j + 1, end)).map { it.value.toDouble() }.toList()
                    if (nums.size >= 4) return nums
                }
            }
            idx = text.indexOf("/GPTS", idx + 5)
        }
        return null
    }

    /** Inflate every FlateDecode-looking `stream ... endstream` block. Brute
     *  force: try zlib then raw-deflate; skip blocks that aren't deflate. */
    private fun inflatedStreams(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        val streamKw = "stream".toByteArray(Charsets.ISO_8859_1)
        val endKw = "endstream".toByteArray(Charsets.ISO_8859_1)
        var pos = 0
        var total = 0
        while (true) {
            val s = indexOf(bytes, streamKw, pos)
            if (s < 0) break
            // Skip the "stream" inside "endstream".
            if (s >= 3 && bytes[s - 1].toInt() == 'd'.code && bytes[s - 2].toInt() == 'n'.code && bytes[s - 3].toInt() == 'e'.code) {
                pos = s + streamKw.size; continue
            }
            var c = s + streamKw.size
            if (c < bytes.size && bytes[c].toInt() == 0x0D) c++ // CR
            if (c < bytes.size && bytes[c].toInt() == 0x0A) c++ // LF
            val e = indexOf(bytes, endKw, c)
            if (e < 0) break
            var contentEnd = e
            if (contentEnd > c && bytes[contentEnd - 1].toInt() == 0x0A) contentEnd--
            if (contentEnd > c && bytes[contentEnd - 1].toInt() == 0x0D) contentEnd--
            val len = contentEnd - c
            if (len in 1..MAX_STREAM) {
                tryInflate(bytes, c, len)?.let {
                    out.add(String(it, Charsets.ISO_8859_1)); total += it.size
                }
            }
            if (total > MAX_TOTAL) break
            pos = e + endKw.size
        }
        return out
    }

    private fun tryInflate(buf: ByteArray, off: Int, len: Int): ByteArray? {
        for (nowrap in booleanArrayOf(false, true)) {
            try {
                val inf = Inflater(nowrap)
                inf.setInput(buf, off, len)
                val out = ByteArrayOutputStream(len * 4)
                val tmp = ByteArray(16384)
                while (!inf.finished()) {
                    val n = inf.inflate(tmp)
                    if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                    out.write(tmp, 0, n)
                    if (out.size() > MAX_STREAM) break
                }
                inf.end()
                if (out.size() > 0) return out.toByteArray()
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || from < 0) return -1
        var i = from
        val last = hay.size - needle.size
        while (i <= last) {
            var k = 0
            while (k < needle.size && hay[i + k] == needle[k]) k++
            if (k == needle.size) return i
            i++
        }
        return -1
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

    /** Import a georeferenced TIFF. Parses bounds from the GeoTIFF tags and
     *  decodes the raster (NGA tiff) to a PNG placed by its corner box.
     *  Returns false if the file isn't georeferenced or can't be decoded. */
    suspend fun importGeoTIFF(source: File, displayName: String): Boolean {
        _isImporting.value = true; _lastError.value = null
        return try {
            val bytes = withContext(Dispatchers.IO) { source.readBytes() }
            val box = GeoTIFFParser.bounds(bytes)
            if (box == null) {
                _lastError.value = "That TIFF isn't georeferenced (no GeoTIFF tags), or its projection isn't supported (WGS84 / Web Mercator only)."
                _isImporting.value = false; return false
            }
            val bmp = withContext(Dispatchers.IO) { decodeTiff(bytes) }
            if (bmp == null) {
                _lastError.value = "Couldn't decode that TIFF (only 8-bit grayscale / RGB / RGBA with uncompressed, LZW, Deflate or PackBits is supported)."
                _isImporting.value = false; return false
            }
            val id = UUID.randomUUID().toString()
            val out = File(dir, "$id.png")
            withContext(Dispatchers.IO) { out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            bmp.recycle()
            _overlays.value = _overlays.value + RasterOverlay(
                id = id, name = displayName.substringBeforeLast('.'), fileName = out.name,
                north = box.north, south = box.south, east = box.east, west = box.west,
                createdAt = System.currentTimeMillis(),
            )
            persist()
            _isImporting.value = false
            true
        } catch (e: Exception) {
            _lastError.value = "GeoTIFF import failed: ${e.message}"
            _isImporting.value = false
            false
        }
    }

    /** Decode a (Geo)TIFF raster to an ARGB Bitmap. v1 supports 8-bit
     *  grayscale / RGB / RGBA. Strided downsample caps the long edge so a
     *  huge orthophoto can't OOM the device. Returns null when unsupported. */
    private fun decodeTiff(bytes: ByteArray): Bitmap? = runCatching {
        val tiff = TiffReader.readTiff(bytes)
        val fd = tiff.fileDirectory
        val bits = fd.bitsPerSample?.firstOrNull() ?: 8
        if (bits != 8) return@runCatching null
        if (fd.photometricInterpretation == 3) return@runCatching null // palette (colormap) not supported
        val rasters = fd.readRasters()
        val w = rasters.width; val h = rasters.height
        if (w <= 0 || h <= 0) return@runCatching null
        val samples = rasters.samplesPerPixel

        val maxDim = 4096
        val step = maxOf(1, maxOf(w, h) / maxDim)
        val outW = (w + step - 1) / step
        val outH = (h + step - 1) / step
        val pixels = IntArray(outW * outH)

        var oy = 0; var y = 0
        while (y < h && oy < outH) {
            var ox = 0; var x = 0
            while (x < w && ox < outW) {
                val px = rasters.getPixel(x, y)
                val argb = when {
                    samples >= 4 -> {
                        val a = px[3].toInt() and 0xFF
                        (a shl 24) or ((px[0].toInt() and 0xFF) shl 16) or ((px[1].toInt() and 0xFF) shl 8) or (px[2].toInt() and 0xFF)
                    }
                    samples == 3 ->
                        (0xFF shl 24) or ((px[0].toInt() and 0xFF) shl 16) or ((px[1].toInt() and 0xFF) shl 8) or (px[2].toInt() and 0xFF)
                    else -> {
                        val v = px[0].toInt() and 0xFF
                        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                    }
                }
                pixels[oy * outW + ox] = argb
                ox++; x += step
            }
            oy++; y += step
        }
        Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    /** Import a georeferenced PDF (GeoPDF). Parses the /VP /Measure /GPTS
     *  corner box and rasterizes page 1 to a PNG placed by that box. Returns
     *  false if the PDF isn't georeferenced or can't be rendered. */
    suspend fun importGeoPDF(source: File, displayName: String): Boolean {
        _isImporting.value = true; _lastError.value = null
        return try {
            val bytes = withContext(Dispatchers.IO) { source.readBytes() }
            val box = GeoPDFParser.bounds(bytes)
            if (box == null) {
                _lastError.value = "That PDF isn't georeferenced (no GeoPDF /VP /Measure /GPTS registration)."
                _isImporting.value = false; return false
            }
            val bmp = withContext(Dispatchers.IO) { rasterizePdf(source) }
            if (bmp == null) {
                _lastError.value = "Couldn't render that PDF."
                _isImporting.value = false; return false
            }
            val id = UUID.randomUUID().toString()
            val out = File(dir, "$id.png")
            withContext(Dispatchers.IO) { out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            bmp.recycle()
            _overlays.value = _overlays.value + RasterOverlay(
                id = id, name = displayName.substringBeforeLast('.'), fileName = out.name,
                north = box.north, south = box.south, east = box.east, west = box.west,
                createdAt = System.currentTimeMillis(),
            )
            persist()
            _isImporting.value = false
            true
        } catch (e: Exception) {
            _lastError.value = "GeoPDF import failed: ${e.message}"
            _isImporting.value = false
            false
        }
    }

    /** Render page 1 of a PDF to an ARGB Bitmap at ~2x, longest edge capped at
     *  4096px (uniform scale preserves aspect). PdfRenderer draws onto a
     *  transparent surface, so we fill white first. */
    private fun rasterizePdf(source: File): Bitmap? = runCatching {
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount < 1) return@runCatching null
                renderer.openPage(0).use { page ->
                    val longest = maxOf(page.width, page.height)
                    if (longest <= 0) return@runCatching null
                    val scale = if (longest * 2.0 > 4096) 4096.0 / longest else 2.0
                    val w = (page.width * scale).toInt().coerceAtLeast(1)
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    Canvas(bmp).drawColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }.getOrNull()

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
