package soy.engindearing.omnitak.mobile.ui

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import soy.engindearing.omnitak.mobile.MainActivity
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.ui.components.KmlOverlayEvents
import java.io.File

/**
 * Captures the GeoTIFF imagery overlay rendered on the live MapLibre map —
 * the last hop the import unit/instrumented tests don't cover (ImageSource +
 * RasterLayer on the GL surface). Imports the fixture into the running app's
 * rasterOverlayStore (the same instance MapScreen observes), frames the camera
 * on its bounds, and screencaps the framebuffer.
 *
 * Output: /sdcard/Download/overlay-geotiff-onmap.png — adb pull after the run.
 */
@RunWith(AndroidJUnit4::class)
class OverlayRenderScreenshotTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @Test
    fun geotiff_rendersOnMap() = captureOnMap("test_geotiff.tif", "geotiff") { store, f, n ->
        runBlocking { store.importGeoTIFF(f, n) }
    }

    @Test
    fun geopdf_rendersOnMap() = captureOnMap("test_geopdf.pdf", "geopdf") { store, f, n ->
        runBlocking { store.importGeoPDF(f, n) }
    }

    @Test
    fun groundOverlay_rendersOnMap() = captureOnMap("test_groundoverlay.kmz", "groundoverlay") { store, f, n ->
        runBlocking { store.importGroundOverlay(f, n) }
    }

    /**
     * Imports a fixture into the running app's store, frames the camera on the
     * imported overlay's bounds, and screencaps the live map to
     * /sdcard/Download/overlay-<label>-onmap.png.
     */
    private fun captureOnMap(
        assetName: String,
        label: String,
        import: (store: soy.engindearing.omnitak.mobile.data.RasterOverlayStore, file: File, name: String) -> Boolean,
    ) {
        val instr = InstrumentationRegistry.getInstrumentation()
        val app = instr.targetContext.applicationContext as OmniTAKApp

        val fixture = File(app.cacheDir, assetName)
        instr.context.assets.open(assetName).use { i -> fixture.outputStream().use { i.copyTo(it) } }

        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(3000) // let the map style finish loading

            app.rasterOverlayStore.removeAll()
            val ok = import(app.rasterOverlayStore, fixture, assetName)
            assertTrue("$label import failed", ok)
            val o = app.rasterOverlayStore.overlays.value.first()

            // Frame the camera on the overlay bounds (MapScreen observes this).
            KmlOverlayEvents.requestZoomBounds(o.north, o.south, o.east, o.west)
            Thread.sleep(4000) // camera animation + raster + basemap tiles

            val outPath = "/sdcard/Download/overlay-$label-onmap.png"
            instr.uiAutomation.executeShellCommand("screencap -p $outPath").let { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                pfd.close()
            }
            val sizeOut = instr.uiAutomation.executeShellCommand("wc -c $outPath").let { pfd ->
                val s = java.io.FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
                pfd.close(); s
            }
            val bytes = sizeOut.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: 0L
            assertTrue("screencap empty/missing at $outPath (wc -c: '${sizeOut.trim()}')", bytes > 1024)

            app.rasterOverlayStore.removeAll()
        }
    }
}
