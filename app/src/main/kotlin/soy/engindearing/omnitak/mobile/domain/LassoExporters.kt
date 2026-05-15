package soy.engindearing.omnitak.mobile.domain

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import soy.engindearing.omnitak.mobile.data.CoTEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Real exporters for the lasso selection — KML and TAK Mission
 * Package zip. Both write to the app's externalCacheDir so the
 * results are shareable via FileProvider + ACTION_SEND without
 * stomping on internal app state.
 *
 * The output directory is `<externalCacheDir>/exports/`. The
 * FileProvider authority is `${applicationId}.fileprovider` — declared
 * in AndroidManifest.xml and backed by `xml/file_paths.xml`. If the
 * provider isn't registered, FileProvider.getUriForFile will throw —
 * caller catches and falls back to a snackbar.
 */
object LassoExporters {

    private const val PROVIDER_SUFFIX = ".fileprovider"
    private val tsFmt: SimpleDateFormat
        get() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    private fun exportsDir(context: Context): File {
        val dir = File(context.externalCacheDir ?: context.cacheDir, "exports")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ---------------------------------------------------------------
    // KML — OGC KML 2.2, one Placemark per marker.
    // ---------------------------------------------------------------

    /**
     * Build a KML document from a list of CoT contacts (lasso
     * selection). Returns the written File so the caller can pass it
     * to [shareFile].
     */
    fun writeKml(context: Context, name: String, events: List<CoTEvent>): File {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.append("\n<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n")
        sb.append("  <Document>\n")
        sb.append("    <name>").append(CotBuilders.xmlEscape(name)).append("</name>\n")
        sb.append("    <description>Lasso selection exported from OmniTAK — ")
            .append(events.size).append(" feature(s).</description>\n")

        // Color tiers per affiliation — same palette as iOS lasso ring.
        sb.append("""    <Style id="friend">  <IconStyle><color>ff00ff00</color><scale>1.1</scale></IconStyle></Style>""").append('\n')
        sb.append("""    <Style id="hostile"> <IconStyle><color>ff0000ff</color><scale>1.1</scale></IconStyle></Style>""").append('\n')
        sb.append("""    <Style id="neutral"> <IconStyle><color>ffff00ff</color><scale>1.1</scale></IconStyle></Style>""").append('\n')
        sb.append("""    <Style id="unknown"> <IconStyle><color>ff00ffff</color><scale>1.1</scale></IconStyle></Style>""").append('\n')

        for (e in events) {
            val styleId = when (e.affiliation.code) {
                'f' -> "friend"
                'h' -> "hostile"
                'n' -> "neutral"
                else -> "unknown"
            }
            val nm = e.callsign?.takeIf { it.isNotBlank() } ?: e.uid
            sb.append("    <Placemark>\n")
            sb.append("      <name>").append(CotBuilders.xmlEscape(nm)).append("</name>\n")
            sb.append("      <styleUrl>#").append(styleId).append("</styleUrl>\n")
            if (e.remarks.isNotBlank()) {
                sb.append("      <description>").append(CotBuilders.xmlEscape(e.remarks)).append("</description>\n")
            }
            sb.append("      <ExtendedData>\n")
            sb.append("        <Data name=\"uid\"><value>").append(CotBuilders.xmlEscape(e.uid)).append("</value></Data>\n")
            sb.append("        <Data name=\"type\"><value>").append(CotBuilders.xmlEscape(e.type)).append("</value></Data>\n")
            sb.append("        <Data name=\"affiliation\"><value>").append(e.affiliation.name).append("</value></Data>\n")
            sb.append("      </ExtendedData>\n")
            sb.append("      <Point><coordinates>")
                .append(e.lon).append(",").append(e.lat).append(",").append(e.hae)
                .append("</coordinates></Point>\n")
            sb.append("    </Placemark>\n")
        }
        sb.append("  </Document>\n</kml>\n")

        val file = File(exportsDir(context), "lasso-${tsFmt.format(Date())}.kml")
        file.writeText(sb.toString())
        return file
    }

    // ---------------------------------------------------------------
    // TAK Mission Package — zip with MANIFEST/manifest.xml + per-event
    // CoT XML under `cot/`. Importable on any TAK client.
    // ---------------------------------------------------------------

    fun writeMissionPackage(context: Context, name: String, events: List<CoTEvent>): File {
        val pkgUid = CotBuilders.newUid()
        val ts = tsFmt.format(Date())
        val file = File(exportsDir(context), "lasso-$ts.zip")

        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            // MANIFEST/manifest.xml — TAK Mission Package descriptor.
            val manifest = buildManifest(pkgUid, name, events)
            zip.putNextEntry(ZipEntry("MANIFEST/manifest.xml"))
            zip.write(manifest.toByteArray())
            zip.closeEntry()

            // One CoT file per marker, named by UID. Other clients
            // unpack the zip and import each CoT as a fresh marker.
            for (e in events) {
                val safeName = e.uid.replace(Regex("[^A-Za-z0-9._-]"), "_")
                zip.putNextEntry(ZipEntry("cot/$safeName.cot"))
                zip.write(buildCoTXml(e).toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun buildManifest(pkgUid: String, name: String, events: List<CoTEvent>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("\n<MissionPackageManifest version=\"2\">\n")
        sb.append("  <Configuration>\n")
        sb.append("    <Parameter name=\"uid\" value=\"").append(CotBuilders.xmlEscape(pkgUid)).append("\"/>\n")
        sb.append("    <Parameter name=\"name\" value=\"").append(CotBuilders.xmlEscape(name)).append("\"/>\n")
        sb.append("    <Parameter name=\"onReceiveImport\" value=\"true\"/>\n")
        sb.append("    <Parameter name=\"onReceiveDelete\" value=\"false\"/>\n")
        sb.append("  </Configuration>\n")
        sb.append("  <Contents>\n")
        for (e in events) {
            val safeName = e.uid.replace(Regex("[^A-Za-z0-9._-]"), "_")
            sb.append("    <Content ignore=\"false\" zipEntry=\"cot/").append(safeName)
                .append(".cot\">\n")
            sb.append("      <Parameter name=\"uid\" value=\"").append(CotBuilders.xmlEscape(e.uid)).append("\"/>\n")
            sb.append("      <Parameter name=\"name\" value=\"").append(CotBuilders.xmlEscape(e.callsign ?: e.uid)).append("\"/>\n")
            sb.append("    </Content>\n")
        }
        sb.append("  </Contents>\n</MissionPackageManifest>\n")
        return sb.toString()
    }

    private fun buildCoTXml(e: CoTEvent): String {
        // Re-emit a clean CoT for each event so receivers import them
        // fresh. We don't dest-route here — the package targets every
        // recipient that imports the zip.
        return CotBuilders.rebuildEvent(e, emptyList())
    }

    // ---------------------------------------------------------------
    // Share intent — hands a File off to the user's share sheet via
    // FileProvider.
    // ---------------------------------------------------------------

    /**
     * Launch the system share sheet with the given exported file.
     * Returns true on success, false if FileProvider isn't wired up
     * (caller falls back to a snackbar).
     */
    fun shareFile(context: Context, file: File, mimeType: String, title: String): Boolean {
        return runCatching {
            val authority = context.packageName + PROVIDER_SUFFIX
            val uri = FileProvider.getUriForFile(context, authority, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, title).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        }.getOrDefault(false)
    }
}
