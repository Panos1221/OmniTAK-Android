package soy.engindearing.omnitak.mobile.ui.components

import android.graphics.PointF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/**
 * Small "H" marker rendered at the drone's HOME_POSITION (the takeoff
 * point — what RTL returns to). White circle with a green H glyph so
 * it's distinguishable from the cyan drone marker + lavender mission
 * pins. Operator can see at a glance where the drone will land if RTL
 * triggers.
 */
@Composable
fun HomePositionOverlay(
    homeLatDeg: Double?,
    homeLonDeg: Double?,
    mapboxMap: MapLibreMap?,
) {
    val map = mapboxMap ?: return
    if (homeLatDeg == null || homeLonDeg == null) return

    val density = LocalDensity.current
    val pt = rememberScreenPosition(map, homeLatDeg, homeLonDeg, periodMs = 150) ?: return
    val xDp = with(density) { pt.x.toDp() }
    val yDp = with(density) { pt.y.toDp() }
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .offset(x = xDp - 12.dp, y = yDp - 12.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "H",
                color = Color(0xFF2E7D32),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
