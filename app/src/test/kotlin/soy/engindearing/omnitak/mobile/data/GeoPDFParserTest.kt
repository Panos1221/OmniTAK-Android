package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Verifies the GeoPDF /VP /Measure /GPTS corner-box parser. PdfRenderer
 * rasterization is Android-only (covered visually on device); these tests
 * exercise the byte-level georeferencing logic — the part with no platform
 * API — for both uncompressed PDFs and PDF 1.5+ FlateDecode object streams.
 */
class GeoPDFParserTest {

    // GPTS = lat, lon pairs (the four DC corners). north=38.95 south=38.935
    // west=-77.05 east=-77.03.
    private val gptsSnippet =
        "<< /Measure << /Subtype /GEO /GPTS [ 38.95 -77.05 38.935 -77.05 38.935 -77.03 38.95 -77.03 ] >> >>"

    private fun pdfBytes(body: String): ByteArray =
        ("%PDF-1.4\n$body\n%%EOF\n").toByteArray(Charsets.ISO_8859_1)

    private fun deflate(s: ByteArray): ByteArray {
        val d = Deflater(); d.setInput(s); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end(); return out.toByteArray()
    }

    @Test
    fun parsesUncompressedGpts() {
        val box = GeoPDFParser.bounds(pdfBytes(gptsSnippet))
        assertNotNull(box); box!!
        assertEquals(38.95, box.north, 1e-9)
        assertEquals(38.935, box.south, 1e-9)
        assertEquals(-77.05, box.west, 1e-9)
        assertEquals(-77.03, box.east, 1e-9)
    }

    @Test
    fun parsesGptsInsideFlateStream() {
        val compressed = deflate(gptsSnippet.toByteArray(Charsets.ISO_8859_1))
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.5\n5 0 obj << /Length ${compressed.size} /Filter /FlateDecode >>\nstream\n".toByteArray(Charsets.ISO_8859_1))
        out.write(compressed)
        out.write("\nendstream\nendobj\n%%EOF\n".toByteArray(Charsets.ISO_8859_1))
        val box = GeoPDFParser.bounds(out.toByteArray())
        assertNotNull("GPTS in a FlateDecode stream should be found", box); box!!
        assertEquals(38.95, box.north, 1e-9)
        assertEquals(-77.03, box.east, 1e-9)
    }

    @Test
    fun rejectsNonGeoreferencedPdf() {
        assertNull(GeoPDFParser.bounds(pdfBytes("<< /Type /Page /MediaBox [0 0 612 792] >>")))
        assertNull(GeoPDFParser.bounds(ByteArray(0)))
    }

    @Test
    fun rejectsDegenerateBox() {
        // All four corners identical → east == west, north == south → invalid.
        val bad = "<< /GPTS [ 38.95 -77.05 38.95 -77.05 38.95 -77.05 38.95 -77.05 ] >>"
        assertNull(GeoPDFParser.bounds(pdfBytes(bad)))
    }
}
