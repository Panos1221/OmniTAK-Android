package soy.engindearing.omnitak.mobile.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * MBTiles raster basemap/imagery overlays — the offline tile-pyramid format
 * ATAK uses. An .mbtiles file is a SQLite DB of raster tiles; MapLibre can't
 * read it directly, so tiles are served by a tiny in-process HTTP server and
 * a RasterSource + RasterLayer point at http://127.0.0.1:port/<id>/{z}/{x}/{y}.
 * MBTiles store tiles in TMS row order; the server flips Y to XYZ.
 */

/** A read-only raster tile pyramid (MBTiles or GeoPackage) served over HTTP. */
interface RasterTileDb {
    val minZoom: Int
    val maxZoom: Int
    val format: String           // tile image format: "png" / "jpg"
    val bounds: DoubleArray?     // [north, south, east, west] or null
    fun tile(z: Int, x: Int, y: Int): ByteArray?
    fun close()
}

class MBTilesDb private constructor(private val db: SQLiteDatabase) : RasterTileDb {
    override val minZoom: Int
    override val maxZoom: Int
    override val format: String
    /** [north, south, east, west] if declared. */
    override val bounds: DoubleArray?

    init {
        val meta = HashMap<String, String>()
        runCatching {
            db.rawQuery("SELECT name, value FROM metadata", null).use { c ->
                while (c.moveToNext()) meta[c.getString(0)] = c.getString(1)
            }
        }
        format = meta["format"] ?: "png"
        minZoom = meta["minzoom"]?.toIntOrNull() ?: 0
        maxZoom = meta["maxzoom"]?.toIntOrNull() ?: 19
        val b = meta["bounds"]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
        bounds = if (b != null && b.size == 4) doubleArrayOf(b[3], b[1], b[2], b[0]) else null // n,s,e,w
    }

    /** Tile bytes for an XYZ request (flips Y to MBTiles' TMS row). */
    override fun tile(z: Int, x: Int, y: Int): ByteArray? {
        val tmsY = (1 shl z) - 1 - y
        return runCatching {
            db.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                arrayOf(z.toString(), x.toString(), tmsY.toString()),
            ).use { c -> if (c.moveToFirst()) c.getBlob(0) else null }
        }.getOrNull()
    }

    override fun close() { runCatching { db.close() } }

    companion object {
        fun open(path: String): MBTilesDb? = runCatching {
            MBTilesDb(SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY))
        }.getOrNull()
    }
}

/**
 * GeoPackage raster reader. Supports the standard case: a tiles table whose
 * grid matches the slippy-map (XYZ) scheme in EPSG:3857 or 4326. GPKG
 * tile_row is top-origin (= XYZ y, no TMS flip).
 */
class GPKGDb private constructor(
    private val db: SQLiteDatabase,
    private val table: String,
    override val minZoom: Int,
    override val maxZoom: Int,
    override val bounds: DoubleArray?,
) : RasterTileDb {
    override val format: String = "png"

    override fun tile(z: Int, x: Int, y: Int): ByteArray? = runCatching {
        db.rawQuery(
            "SELECT tile_data FROM \"$table\" WHERE zoom_level=? AND tile_column=? AND tile_row=?",
            arrayOf(z.toString(), x.toString(), y.toString()), // GPKG tile_row = XYZ y (no flip)
        ).use { c -> if (c.moveToFirst()) c.getBlob(0) else null }
    }.getOrNull()

    override fun close() { runCatching { db.close() } }

    companion object {
        fun open(path: String): GPKGDb? = runCatching {
            val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
            var table: String? = null
            var bounds: DoubleArray? = null
            db.rawQuery("SELECT table_name, min_x, min_y, max_x, max_y, srs_id FROM gpkg_contents WHERE data_type='tiles' LIMIT 1", null).use { c ->
                if (c.moveToFirst()) {
                    table = c.getString(0)
                    val minX = c.getDouble(1); val minY = c.getDouble(2)
                    val maxX = c.getDouble(3); val maxY = c.getDouble(4); val srs = c.getInt(5)
                    fun lon(x: Double) = if (srs == 3857 || srs == 900913) x / 6378137.0 * 180.0 / Math.PI else x
                    fun lat(y: Double) = if (srs == 3857 || srs == 900913) (2 * Math.atan(Math.exp(y / 6378137.0)) - Math.PI / 2) * 180.0 / Math.PI else y
                    val n = lat(maxY); val s = lat(minY); val e = lon(maxX); val w = lon(minX)
                    bounds = if (kotlin.math.abs(n) <= 90 && kotlin.math.abs(s) <= 90 && n > s) doubleArrayOf(n, s, e, w) else null
                }
            }
            val t = table ?: run { db.close(); return null }
            var lo = 0; var hi = 19
            db.rawQuery("SELECT min(zoom_level), max(zoom_level) FROM gpkg_tile_matrix WHERE table_name=?", arrayOf(t)).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) { lo = c.getInt(0); hi = c.getInt(1) }
            }
            GPKGDb(db, t, lo, hi, bounds)
        }.getOrNull()
    }
}

object MBTilesServer {
    @Volatile var port: Int = 0
        private set
    private var serverSocket: ServerSocket? = null
    private var started = false
    private val dbs = ConcurrentHashMap<String, RasterTileDb>()
    private val pool = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }

    @Synchronized
    fun register(id: String, db: RasterTileDb) {
        dbs[id]?.close()
        dbs[id] = db
        start()
    }

    fun unregister(id: String) { dbs.remove(id)?.close() }

    fun tileUrlTemplate(id: String): String? =
        if (port != 0) "http://127.0.0.1:$port/$id/{z}/{x}/{y}" else null

    @Synchronized
    private fun start() {
        if (started) return
        val ss = runCatching { ServerSocket(0) }.getOrNull() ?: return
        serverSocket = ss
        port = ss.localPort
        started = true
        Thread {
            while (!ss.isClosed) {
                val sock = runCatching { ss.accept() }.getOrNull() ?: break
                pool.execute { handle(sock) }
            }
        }.apply { isDaemon = true; name = "mbtiles-server" }.start()
    }

    private fun handle(sock: Socket) {
        sock.use {
            runCatching {
                val reader = it.getInputStream().bufferedReader()
                val line = reader.readLine() ?: return            // "GET /id/z/x/y HTTP/1.1"
                val path = line.split(" ").getOrNull(1) ?: return
                val parts = path.trim('/').split("/")             // [id, z, x, y(.ext)]
                val out = it.getOutputStream()
                if (parts.size >= 4) {
                    val id = parts[0]
                    val z = parts[1].toIntOrNull()
                    val x = parts[2].toIntOrNull()
                    val y = parts[3].substringBefore(".").toIntOrNull()
                    val data = if (z != null && x != null && y != null) dbs[id]?.tile(z, x, y) else null
                    if (data != null) {
                        val ctype = if (dbs[id]?.format == "jpg" || dbs[id]?.format == "jpeg") "image/jpeg" else "image/png"
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: $ctype\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(data)
                        out.flush()
                        return
                    }
                }
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
            }
        }
    }
}

@Serializable
data class MBTilesOverlay(
    val id: String,
    val name: String,
    val fileName: String,
    val minZoom: Int = 0,
    val maxZoom: Int = 19,
    val north: Double = 0.0,
    val south: Double = 0.0,
    val east: Double = 0.0,
    val west: Double = 0.0,
    val hasBounds: Boolean = false,
    val opacity: Float = 1.0f,
    val visible: Boolean = true,
    val createdAt: Long = 0L,
    /** Container format used to reopen the right reader: "mbtiles" or "gpkg". */
    val container: String = "mbtiles",
)

class MBTilesOverlayStore(context: Context) {
    private val dir = File(context.filesDir, "mbtiles").apply { mkdirs() }
    private val metaFile = File(dir, "mbtiles.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _overlays = MutableStateFlow(load())
    val overlays: StateFlow<List<MBTilesOverlay>> = _overlays.asStateFlow()
    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    init {
        // Re-register persisted tile sets with the server on launch.
        _overlays.value.forEach { o -> openDb(o)?.let { MBTilesServer.register(o.id, it) } }
    }

    fun fileFor(o: MBTilesOverlay): File = File(dir, o.fileName)
    fun tileUrlTemplate(o: MBTilesOverlay): String? = MBTilesServer.tileUrlTemplate(o.id)

    private fun openDb(o: MBTilesOverlay): RasterTileDb? =
        if (o.container == "gpkg") GPKGDb.open(fileFor(o).absolutePath) else MBTilesDb.open(fileFor(o).absolutePath)

    suspend fun importMBTiles(source: File, displayName: String): Boolean = importTileSet(source, displayName, "mbtiles")
    suspend fun importGPKG(source: File, displayName: String): Boolean = importTileSet(source, displayName, "gpkg")

    private suspend fun importTileSet(source: File, displayName: String, container: String): Boolean {
        _isImporting.value = true; _lastError.value = null
        val label = if (container == "gpkg") "GeoPackage" else "MBTiles"
        val id = UUID.randomUUID().toString()
        val dest = File(dir, "$id.$container")
        return try {
            withContext(Dispatchers.IO) { source.copyTo(dest, overwrite = true) }
            val db = (if (container == "gpkg") GPKGDb.open(dest.absolutePath) else MBTilesDb.open(dest.absolutePath))
                ?: throw IllegalStateException("not a valid $label file")
            MBTilesServer.register(id, db)
            val b = db.bounds
            _overlays.value = _overlays.value + MBTilesOverlay(
                id = id, name = displayName.substringBeforeLast("."), fileName = dest.name,
                minZoom = db.minZoom, maxZoom = db.maxZoom,
                north = b?.get(0) ?: 85.0, south = b?.get(1) ?: -85.0,
                east = b?.get(2) ?: 180.0, west = b?.get(3) ?: -180.0,
                hasBounds = b != null, createdAt = System.currentTimeMillis(), container = container,
            )
            persist()
            _isImporting.value = false
            true
        } catch (e: Exception) {
            dest.delete()
            _lastError.value = "$label import failed: ${e.message}"
            _isImporting.value = false
            false
        }
    }

    fun setVisible(id: String, visible: Boolean) = update(id) { it.copy(visible = visible) }
    fun setOpacity(id: String, value: Float) = update(id) { it.copy(opacity = value.coerceIn(0.05f, 1.0f)) }
    fun rename(id: String, name: String) {
        val t = name.trim(); if (t.isEmpty()) return
        update(id) { it.copy(name = t) }
    }

    fun remove(id: String) {
        MBTilesServer.unregister(id)
        _overlays.value.firstOrNull { it.id == id }?.let { fileFor(it).delete() }
        _overlays.value = _overlays.value.filterNot { it.id == id }
        persist()
    }

    fun removeAll() {
        _overlays.value.forEach { MBTilesServer.unregister(it.id); fileFor(it).delete() }
        _overlays.value = emptyList()
        persist()
    }

    private fun update(id: String, transform: (MBTilesOverlay) -> MBTilesOverlay) {
        _overlays.value = _overlays.value.map { if (it.id == id) transform(it) else it }
        persist()
    }

    private fun persist() {
        runCatching { metaFile.writeText(json.encodeToString(_overlays.value)) }
    }

    private fun load(): List<MBTilesOverlay> {
        if (!metaFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<MBTilesOverlay>>(metaFile.readText())
                .filter { File(dir, it.fileName).exists() }
        }.getOrDefault(emptyList())
    }
}
