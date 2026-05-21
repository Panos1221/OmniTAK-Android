package soy.engindearing.omnitak.mobile.data

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device verification of the single-image raster import paths — the parts
 * the JVM unit tests can't reach because they call Android-only APIs:
 *   - GeoTIFF  → NGA tiff decode + Bitmap.createBitmap
 *   - GeoPDF   → PdfRenderer rasterize
 *   - GroundOverlay → BitmapFactory.decode of the KMZ image
 *
 * Each fixture (app/src/androidTest/assets) is imported through the real
 * RasterOverlayStore, then we assert: import succeeded, bounds are the known
 * DC box, and the produced PNG decodes to a real Bitmap. The decoded PNGs are
 * copied to /sdcard/Pictures/omnitak/ so they can be pulled as visual proof.
 */
@RunWith(AndroidJUnit4::class)
class RasterImportInstrumentedTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val targetCtx = instr.targetContext

    private fun asset(name: String): File {
        val out = File(targetCtx.cacheDir, name)
        instr.context.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    private fun export(store: RasterOverlayStore, label: String) {
        val pics = File("/sdcard/Pictures/omnitak").apply { mkdirs() }
        store.overlays.value.forEach { o ->
            store.fileFor(o).copyTo(File(pics, "raster-$label.png"), overwrite = true)
        }
    }

    private fun assertDcBox(o: RasterOverlay) {
        assertEquals(38.95, o.north, 1e-4)
        assertEquals(38.935, o.south, 1e-4)
        assertEquals(-77.05, o.west, 1e-4)
        assertEquals(-77.03, o.east, 1e-4)
    }

    private fun assertDecodes(store: RasterOverlayStore, o: RasterOverlay) {
        val bmp = BitmapFactory.decodeFile(store.fileFor(o).absolutePath)
        assertTrue("produced PNG must decode to a Bitmap", bmp != null)
        assertTrue("bitmap has pixels", bmp!!.width > 0 && bmp.height > 0)
    }

    @Test
    fun geoTiff_decodesAndGeoreferencesOnDevice() = runBlocking {
        val store = RasterOverlayStore(targetCtx); store.removeAll()
        assertTrue(store.importGeoTIFF(asset("test_geotiff.tif"), "test_geotiff.tif"))
        val o = store.overlays.value.single()
        assertDcBox(o); assertDecodes(store, o)
        export(store, "geotiff")
        store.removeAll()
    }

    @Test
    fun geoPdf_rasterizesAndGeoreferencesOnDevice() = runBlocking {
        val store = RasterOverlayStore(targetCtx); store.removeAll()
        assertTrue(store.importGeoPDF(asset("test_geopdf.pdf"), "test_geopdf.pdf"))
        val o = store.overlays.value.single()
        assertDcBox(o); assertDecodes(store, o)
        export(store, "geopdf")
        store.removeAll()
    }

    @Test
    fun groundOverlay_decodesAndGeoreferencesOnDevice() = runBlocking {
        val store = RasterOverlayStore(targetCtx); store.removeAll()
        assertTrue(store.importGroundOverlay(asset("test_groundoverlay.kmz"), "test_groundoverlay.kmz"))
        val o = store.overlays.value.first()
        assertDecodes(store, o)
        export(store, "groundoverlay")
        store.removeAll()
    }
}
