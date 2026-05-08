package soy.engindearing.omnitak.mobile.domain

import android.content.Context
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParser
import soy.engindearing.omnitak.mobile.data.CertVault
import soy.engindearing.omnitak.mobile.data.ConnectionProtocol
import soy.engindearing.omnitak.mobile.data.TAKServer
import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringReader
import java.util.zip.ZipInputStream

/**
 * Auto-import any TAK data-package `.zip` dropped into the app's external
 * import dir (`<app>/files/import/`). Used as a sideloading path on real
 * devices where the SAF file picker is flaky for sub-MB cert files.
 *
 * Push a zip with `adb push <name>.zip /sdcard/Android/data/<app-id>/files/import/`
 * and the next app launch will:
 *   1. Stream-extract MANIFEST/manifest.xml, certs/server.pref, any .p12s
 *   2. Parse server.pref for connectString + cert name/password
 *   3. Save .p12s into [CertVault]
 *   4. Add the server via [ServerManager.addServer] (auto-connects)
 *   5. Rename the zip to `<name>.zip.imported` so re-launches are idempotent
 *
 * No external storage permission required — getExternalFilesDir is
 * app-private under Android's scoped storage rules.
 */
class DataPackageBootstrap(
    private val context: Context,
    private val certVault: CertVault,
    private val serverManager: ServerManager,
) {

    private val scope = CoroutineScope(Dispatchers.IO)

    fun runIfNeeded() {
        scope.launch {
            val dir = context.getExternalFilesDir(IMPORT_DIR) ?: return@launch
            if (!dir.exists()) return@launch
            val zips = dir.listFiles { f -> f.isFile && f.name.endsWith(".zip", ignoreCase = true) }
                ?: return@launch
            for (zip in zips) {
                runCatching { importZip(zip) }
                    .onSuccess {
                        val renamed = File(zip.parentFile, "${zip.name}.imported")
                        zip.renameTo(renamed)
                        Log.i(TAG, "Imported ${zip.name} → renamed to ${renamed.name}")
                    }
                    .onFailure {
                        Log.w(TAG, "Import of ${zip.name} failed: ${it.javaClass.simpleName}: ${it.message}")
                    }
            }
        }
    }

    private fun importZip(zip: File) {
        var serverPrefXml: String? = null
        ZipInputStream(zip.inputStream()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (entry.isDirectory) {
                    zin.closeEntry()
                    continue
                }
                val bytes = zin.readBytes()
                zin.closeEntry()
                val name = entry.name
                when {
                    name.endsWith("server.pref", ignoreCase = true) -> {
                        serverPrefXml = String(bytes, Charsets.UTF_8)
                    }
                    name.endsWith(".p12", ignoreCase = true) -> {
                        val displayName = name.substringAfterLast('/')
                        certVault.importBytes(displayName, bytes)
                    }
                }
            }
        }

        val xml = serverPrefXml ?: error("server.pref missing in ${zip.name}")
        val parsed = parseServerPref(xml)
        val connect = parsed.connectString ?: error("connectString missing in ${zip.name}")
        val (host, port, protoTag) = parseConnectString(connect)
        val useTLS = protoTag.equals("ssl", ignoreCase = true) || protoTag.equals("tls", ignoreCase = true)
        val certFileName = parsed.certificateLocation?.substringAfterLast('/')

        val server = TAKServer(
            name = parsed.description ?: zip.nameWithoutExtension,
            host = host,
            port = port,
            protocol = if (useTLS) ConnectionProtocol.TLS.wire else ConnectionProtocol.TCP.wire,
            useTLS = useTLS,
            certificateName = certFileName,
            certificatePassword = parsed.clientPassword,
        )
        serverManager.addServer(server)
        Log.i(TAG, "Added server: ${server.name} → ${server.host}:${server.port} TLS=${server.useTLS} cert=${server.certificateName}")
    }

    private data class ParsedPref(
        val description: String?,
        val connectString: String?,
        val certificateLocation: String?,
        val clientPassword: String?,
        val caLocation: String?,
        val caPassword: String?,
    )

    private fun parseServerPref(xml: String): ParsedPref {
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))
        var description: String? = null
        var connect: String? = null
        var certLoc: String? = null
        var clientPw: String? = null
        var caLoc: String? = null
        var caPw: String? = null
        var event = parser.eventType
        var currentKey: String? = null
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "entry") {
                currentKey = parser.getAttributeValue(null, "key")
            } else if (event == XmlPullParser.TEXT && currentKey != null) {
                val text = parser.text?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    when (currentKey) {
                        "description0" -> description = text
                        "connectString0" -> connect = text
                        "certificateLocation" -> certLoc = text
                        "clientPassword" -> clientPw = text
                        "caLocation" -> caLoc = text
                        "caPassword" -> caPw = text
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "entry") {
                currentKey = null
            }
            event = parser.next()
        }
        return ParsedPref(description, connect, certLoc, clientPw, caLoc, caPw)
    }

    /** "host:port:proto" → triple. */
    private fun parseConnectString(s: String): Triple<String, Int, String> {
        val parts = s.split(":")
        require(parts.size >= 2) { "connectString must be host:port[:proto]: $s" }
        val host = parts[0]
        val port = parts[1].toInt()
        val proto = parts.getOrNull(2) ?: "tcp"
        return Triple(host, port, proto)
    }

    companion object {
        private const val TAG = "DataPackageBootstrap"
        private const val IMPORT_DIR = "import"
    }
}
