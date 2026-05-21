package soy.engindearing.omnitak.mobile.data

import mil.nga.tiff.TiffReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Verifies the GeoTIFF georeferencing parser (pure-Kotlin port of the iOS
 * GeoTIFFImporter) and that the NGA tiff library decodes the raster the
 * importer relies on. Uses a real GeoTIFF authored over DC at
 * /tmp/test_geotiff.tif (200x150, 0.0001 deg/px, EPSG:4326,
 * top-left = -77.05, 38.95). The test is skipped if that fixture is absent.
 */
class GeoTIFFParserTest {
    private val fixture = File("/tmp/test_geotiff.tif")

    @Test
    fun bounds_matchExpectedDcBox() {
        assumeTrue("fixture /tmp/test_geotiff.tif missing", fixture.exists())
        val box = GeoTIFFParser.bounds(fixture.readBytes())
        assertNotNull("expected georeferenced bounds", box)
        box!!
        assertEquals(38.95, box.north, 1e-6)
        assertEquals(38.935, box.south, 1e-6)
        assertEquals(-77.05, box.west, 1e-6)
        assertEquals(-77.03, box.east, 1e-6)
    }

    @Test
    fun ngaTiff_decodesRasterAndOrientation() {
        assumeTrue("fixture /tmp/test_geotiff.tif missing", fixture.exists())
        val tiff = TiffReader.readTiff(fixture.readBytes())
        val rasters = tiff.fileDirectory.readRasters()
        assertEquals(200, rasters.width)
        assertEquals(150, rasters.height)
        assertTrue("expected RGB(A)", rasters.samplesPerPixel >= 3)

        // Top-left 20x20 marker is red; center is dodger blue.
        val tl = rasters.getPixel(2, 2)
        assertEquals(255, tl[0].toInt() and 0xFF) // R
        assertEquals(0, tl[1].toInt() and 0xFF)   // G
        assertEquals(0, tl[2].toInt() and 0xFF)   // B

        val center = rasters.getPixel(100, 75)
        assertEquals(30, center[0].toInt() and 0xFF)
        assertEquals(144, center[1].toInt() and 0xFF)
        assertEquals(255, center[2].toInt() and 0xFF)
    }

    @Test
    fun nonGeoreferencedTiff_returnsNull() {
        // A plain TIFF header with no model tags must not yield bounds.
        val header = byteArrayOf(0x49, 0x49, 42, 0, 8, 0, 0, 0, 0, 0)
        assertNull(GeoTIFFParser.bounds(header))
        assertNull(GeoTIFFParser.bounds(ByteArray(4)))
    }
}
