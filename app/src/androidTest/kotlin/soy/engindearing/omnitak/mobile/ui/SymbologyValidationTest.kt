package soy.engindearing.omnitak.mobile.ui

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import soy.engindearing.omnitak.mobile.MainActivity
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.data.CoTEvent

/**
 * Phase E (Phase B gate) — visual validation that ContactLayer
 * renders MIL-STD-2525 symbols for varied CoT types instead of the
 * pre-Phase-B four-affiliation tinted dots.
 *
 * Why instrumented and not a JVM/Compose unit test:
 *   MapLibre renders into a SurfaceView via native GL. The pixels
 *   never touch the View hierarchy that Compose / Robolectric can
 *   read — only `UiDevice.takeScreenshot()` against the actual
 *   framebuffer captures the rendered map.
 *
 * Output: `/sdcard/Pictures/omnitak/phase-b-symbology.png` on the
 * emulator. `adb pull` it after the run; the asset goes alongside
 * iOS in the Phase E side-by-side composite.
 */
@RunWith(AndroidJUnit4::class)
class SymbologyValidationTest {

    // Pre-grant the runtime location permission so MapScreen renders
    // the map UI directly instead of stalling on the system permission
    // sheet (which would block the screenshot capture).
    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    /**
     * Nine sample contacts spanning the SIDC families that exist in
     * `assets/milstd/` today. Lays them out as a 3×3 grid centred on
     * Spokane so a single map zoom level sees all of them.
     */
    private data class Sample(val uid: String, val type: String, val callsign: String)

    private val samples = listOf(
        Sample("u1", "a-f-G-U-C-I", "FRND-INF"),     // SFGPUCI  — friendly infantry
        Sample("u2", "a-f-G-U-C-A", "FRND-ARM"),     // SFGPUCA  — friendly armor
        Sample("u3", "a-f-A", "FRND-AIR"),           // SFAP     — friendly air
        Sample("u4", "a-h-G-U-C-I", "HSTL-INF"),     // SHGPUCI  — hostile infantry
        Sample("u5", "a-h-G-U-C-A", "HSTL-ARM"),     // SHGPUCA  — hostile armor
        Sample("u6", "a-n-G-U", "NEUT-GND"),         // SNGPU    — neutral ground
        Sample("u7", "a-u-G-U", "UNKN-GND"),         // SUGPU    — unknown ground
        Sample("u8", "a-u-A", "UNKN-AIR"),           // SUA      — unknown air
        Sample("u9", "a-u-A-M-H-Q", "DRONE-RID"),    // truncates → SUA — drone
    )

    @Test
    fun varied_cot_types_render_milstd_symbols() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext.applicationContext as OmniTAKApp
        val device = UiDevice.getInstance(instrumentation)

        ActivityScenario.launch(MainActivity::class.java).use {
            // Let the map style finish loading before injecting contacts.
            // ContactLayer.update is a no-op if the map style isn't ready.
            Thread.sleep(2500)

            val baseLat = 47.66
            val baseLon = -117.42
            samples.forEachIndexed { i, sample ->
                val row = i / 3
                val col = i % 3
                val lat = baseLat + (1 - row) * 0.008
                val lon = baseLon + (col - 1) * 0.012
                targetContext.contactStore.ingest(
                    CoTEvent(
                        uid = sample.uid,
                        type = sample.type,
                        lat = lat,
                        lon = lon,
                        callsign = sample.callsign,
                    )
                )
            }

            // Give MapLibre a beat to swap in the new markers.
            Thread.sleep(2500)

            // Use `screencap` via the UiAutomation shell channel rather
            // than `UiDevice.takeScreenshot(File)` — shell runs outside
            // scoped storage so the PNG survives the post-test app
            // uninstall that AGP performs by default. Path is stable
            // for `adb pull` from the host.
            //
            // We have to drain the PFD to wait for screencap to finish
            // — `executeShellCommand` is async and the PFD's read side
            // only closes when the shell process exits.
            val outPath = "/sdcard/Download/phase-c-symbology.png"
            instrumentation.uiAutomation.executeShellCommand("screencap -p $outPath").let { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                pfd.close()
            }
            println("symbology screenshot → $outPath")

            // Verify the file landed and isn't empty. Read size via
            // shell `wc -c` because the test process itself can't see
            // /sdcard/Download/ under scoped storage.
            val sizeOut = instrumentation.uiAutomation
                .executeShellCommand("wc -c $outPath")
                .let { pfd ->
                    val s = java.io.FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
                    pfd.close()
                    s
                }
            val sizeBytes = sizeOut.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: 0L
            assertTrue("screencap produced an empty/missing file at $outPath (wc -c: '${sizeOut.trim()}')", sizeBytes > 1024)
        }
    }
}
