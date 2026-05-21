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

class MBTilesDb private constructor(private val db: SQLiteDatabase) {
    val minZoom: Int
    val maxZoom: Int
    val format: String
    /** [north, south, east, west] if declared. */
    val bounds: DoubleArray?

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
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        val tmsY = (1 shl z) - 1 - y
        return runCatching {
            db.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                arrayOf(z.toString(), x.toString(), tmsY.toString()),
            ).use { c -> if (c.moveToFirst()) c.getBlob(0) else null }
        }.getOrNull()
    }

    fun close() = runCatching { db.close() }

    companion object {
        fun open(path: String): MBTilesDb? = runCatching {
            MBTilesDb(SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY))
        }.getOrNull()
    }
}

object MBTilesServer {
    @Volatile var port: Int = 0
        private set
    private var serverSocket: ServerSocket? = null
    private var started = false
    private val dbs = ConcurrentHashMap<String, MBTilesDb>()
    private val pool = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }

    @Synchronized
    fun register(id: String, db: MBTilesDb) {
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
        _overlays.value.forEach { o -> MBTilesDb.open(fileFor(o).absolutePath)?.let { MBTilesServer.register(o.id, it) } }
    }

    fun fileFor(o: MBTilesOverlay): File = File(dir, o.fileName)
    fun tileUrlTemplate(o: MBTilesOverlay): String? = MBTilesServer.tileUrlTemplate(o.id)

    suspend fun importMBTiles(source: File, displayName: String): Boolean {
        _isImporting.value = true; _lastError.value = null
        val id = UUID.randomUUID().toString()
        val dest = File(dir, "$id.mbtiles")
        return try {
            withContext(Dispatchers.IO) { source.copyTo(dest, overwrite = true) }
            val db = MBTilesDb.open(dest.absolutePath) ?: throw IllegalStateException("not a valid MBTiles file")
            MBTilesServer.register(id, db)
            val b = db.bounds
            _overlays.value = _overlays.value + MBTilesOverlay(
                id = id, name = displayName.substringBeforeLast("."), fileName = dest.name,
                minZoom = db.minZoom, maxZoom = db.maxZoom,
                north = b?.get(0) ?: 85.0, south = b?.get(1) ?: -85.0,
                east = b?.get(2) ?: 180.0, west = b?.get(3) ?: -180.0,
                hasBounds = b != null, createdAt = System.currentTimeMillis(),
            )
            persist()
            _isImporting.value = false
            true
        } catch (e: Exception) {
            dest.delete()
            _lastError.value = "MBTiles import failed: ${e.message}"
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
