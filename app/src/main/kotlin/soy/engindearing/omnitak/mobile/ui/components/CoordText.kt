package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.data.CoordFormat
import soy.engindearing.omnitak.mobile.data.CoordFormatter

/**
 * Format a coordinate pair honoring the operator's Settings coordinate
 * format (decimal / DMS / MGRS / UTM / TWD97) — issues #3/#4. Sheets and
 * panels used to hardcode `"%.5f, %.5f"`, silently re-introducing the
 * "settings picker is a no-op" regression for MGRS/TWD97 operators.
 *
 * Reads the pref from [OmniTAKApp] so call sites don't have to plumb
 * UserPrefs manually; falls back to decimal in previews (no app context).
 */
@Composable
fun rememberCoordText(lat: Double, lon: Double): String {
    val app = LocalContext.current.applicationContext as? OmniTAKApp
    val format by remember(app) {
        app?.userPrefsStore?.prefs?.map { it.coordFormat }
            ?: flowOf(CoordFormat.LATLON_DECIMAL)
    }.collectAsState(initial = CoordFormat.LATLON_DECIMAL)
    return CoordFormatter.position(lat, lon, format)
}
